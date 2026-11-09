import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// A blocked member stays a member: what they write is stored, but the
/// conversation, the chats list and the receipts leave it out until the
/// user unblocks them (PROTOCOL §6.4, App Review 1.2).
@Suite("Communities: blocked members are hidden")
struct CommunityBlockingStorageTests {
    struct Setup {
        let environment: TestEnvironment
        let storage: LocalStorage
        let account: TestAccount
        let state: CommunityState
        let owner: CommunityMember
        let ownerIdentity: IdentityKeyPair
        let members: [CommunityMember]
    }

    /// A community another account owns, with this account and `count` other members.
    private func setup(members count: Int = 2) throws -> Setup {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let identity = try account.store.identityKeyPair(context: NullContext())
        let me = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let ownerIdentity = IdentityKeyPair.generate()
        let owner = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: ownerIdentity.identityKey.serialize())
        let members = (0..<count).map { _ in
            CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        }
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: true, owner: owner, name: "Kitap",
            members: [owner, me] + members)
        try storage.write { try $0.communities.save(Self.signed(state, by: ownerIdentity)) }
        return Setup(
            environment: environment, storage: storage, account: account, state: state, owner: owner,
            ownerIdentity: ownerIdentity, members: members)
    }

    private static func signed(_ state: CommunityState, by owner: IdentityKeyPair) -> SignedCommunityState {
        SignedCommunityState(state: state.bytes, signature: owner.privateKey.generateSignature(message: state.bytes))
    }

    private func text(_ body: String, in state: CommunityState, id: AccountId = AccountId(uuid: UUID())) -> BRPCommunityMessage {
        BRPCommunityMessage.text(body, community: state.id, revision: state.revision, messageId: id.bytes)
    }

    private func shown(_ setup: Setup) throws -> [String] {
        try setup.storage.read { try $0.communities.history(setup.state.id, hidingBlocked: true) }.items.map(\.text)
    }

    private func summary(_ setup: Setup) throws -> CommunityChatSummary {
        try #require(try setup.storage.read { try $0.communities.summaries() }.first)
    }

    @Test("a blocked member's messages are hidden, unread nowhere and owed no receipt; the others' are shown")
    func blockedMemberIsHidden() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let (blocked, other) = (setup.members[0].accountId, setup.members[1].accountId)
        try setup.storage.write { tx in
            try tx.communities.store(text("merhaba", in: setup.state), sender: other, outgoing: false, now: t0)
            try tx.communities.store(text("benden", in: setup.state), sender: setup.account.accountId, outgoing: true, now: t0.addingTimeInterval(1))
            try tx.communities.store(text("spam 1", in: setup.state), sender: blocked, outgoing: false, now: t0.addingTimeInterval(2))
            try tx.contacts.block(blocked, now: t0)
            // Arrives after the block: stored all the same (the roster and acks are untouched).
            try tx.communities.store(text("spam 2", in: setup.state), sender: blocked, outgoing: false, now: t0.addingTimeInterval(3))
        }
        #expect(try shown(setup) == ["merhaba", "benden"])
        // Stored, never dropped: a transfer or an unblock still has it.
        #expect(try setup.storage.read { try $0.communities.history(setup.state.id) }.items.count == 4)
        let listed = try summary(setup)
        #expect(listed.unread == 1)
        #expect(listed.last?.text == "benden")
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.delivery) }.map(\.sender) == [other])
        // Showing the conversation reads only what it shows: no READ for the blocked member.
        let read = try setup.storage.write { try $0.communities.markDisplayed(community: setup.state.id, readReceipts: true) }
        #expect(read == 1)
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.read) }.map(\.sender) == [other])
        #expect(try summary(setup).unread == 0)
        // Still a member of the roster.
        #expect(try setup.storage.read { try $0.communities.get(setup.state.id) }?.state?.member(blocked) != nil)
    }

    @Test("history pages skip a blocked member without losing anyone else's messages")
    func pagesSkipBlockedMember() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let (blocked, other) = (setup.members[0].accountId, setup.members[1].accountId)
        try setup.storage.write { tx in
            for index in 0..<150 {
                let sender = index.isMultiple(of: 2) ? other : blocked
                try tx.communities.store(text("m\(index)", in: setup.state), sender: sender, outgoing: false, now: t0.addingTimeInterval(Double(index)))
            }
            try tx.contacts.block(blocked, now: t0)
        }
        var page = try setup.storage.read { try $0.communities.history(setup.state.id, limit: 50, hidingBlocked: true) }
        var seen = page.items.map(\.text)
        while page.hasOlder {
            page = try setup.storage.read {
                try $0.communities.history(setup.state.id, before: page.oldest, limit: 50, hidingBlocked: true)
            }
            seen.insert(contentsOf: page.items.map(\.text), at: 0)
        }
        #expect(seen == (0..<150).filter { $0.isMultiple(of: 2) }.map { "m\($0)" })
    }

    @Test("unblocking shows what was hidden again, unread, with its receipts owed")
    func unblockRestores() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let blocked = setup.members[0].accountId
        try setup.storage.write { tx in
            try tx.contacts.block(blocked, now: t0)
            try tx.communities.store(text("gizli", in: setup.state), sender: blocked, outgoing: false, now: t0)
        }
        #expect(try shown(setup).isEmpty)
        #expect(try summary(setup).unread == 0)
        _ = try setup.storage.write { try $0.communities.markDisplayed(community: setup.state.id, readReceipts: true) }

        // A stranger blocked from a community has nothing to go back to: forgotten, visible again.
        let unblocked = try setup.storage.write { try $0.contacts.unblockLeavingNothing(blocked) }
        #expect(unblocked)
        #expect(try shown(setup) == ["gizli"])
        let listed = try summary(setup)
        #expect(listed.unread == 1 && listed.last?.text == "gizli")
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.delivery) }.map(\.sender) == [blocked])
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.read) }.isEmpty)
    }

    @Test("blocking the owner hides their messages but not their moderation or roster changes")
    func blockedOwnerKeepsAdminEvents() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let owner = setup.owner.accountId
        let other = setup.members[1].accountId
        let target = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.contacts.block(owner, now: t0)
            try tx.communities.store(text("sahibin mesajı", in: setup.state), sender: owner, outgoing: false, now: t0)
            try tx.communities.store(text("silinecek", in: setup.state, id: target), sender: other, outgoing: false, now: t0.addingTimeInterval(1))
            // The owner removes someone else's message (moderation).
            let outcome = try tx.communities.applyDelete(
                community: setup.state.id, targetSender: other, targetId: target, by: .byOwner, own: setup.account.accountId,
                now: t0.addingTimeInterval(2))
            #expect(outcome != .remembered)
        }
        let items = try setup.storage.read { try $0.communities.history(setup.state.id, hidingBlocked: true) }.items
        #expect(items.count == 1)
        #expect(items.first?.sender == other && items.first?.deletion == .byOwner)

        // A new roster from the blocked owner still applies: it drops a member.
        let removed = setup.members[0]
        let next = try CommunityState(
            id: setup.state.id, revision: 2, isPublic: true, owner: setup.owner, name: "Kitap",
            members: setup.state.members.filter { $0.accountId != removed.accountId })
        try setup.storage.write { try $0.communities.save(Self.signed(next, by: setup.ownerIdentity)) }
        let stored = try #require(try setup.storage.read { try $0.communities.get(setup.state.id) }?.state)
        #expect(stored.revision == 2 && stored.member(removed.accountId) == nil && stored.member(owner) != nil)
    }

    @Test("unknown senders, contacts in other states and own messages are never hidden")
    func onlyBlockedAccountsAreHidden() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let (contact, stranger) = (setup.members[0].accountId, setup.members[1].accountId)
        try setup.storage.write { tx in
            try tx.contacts.create(contact, state: .accepted, now: t0)
            try tx.communities.store(text("kişi", in: setup.state), sender: contact, outgoing: false, now: t0)
            try tx.communities.store(text("yabancı", in: setup.state), sender: stranger, outgoing: false, now: t0.addingTimeInterval(1))
            try tx.communities.store(text("ben", in: setup.state), sender: setup.account.accountId, outgoing: true, now: t0.addingTimeInterval(2))
            // Odd, but possible in a damaged store: this account's own id marked blocked.
            try tx.contacts.block(setup.account.accountId, now: t0)
        }
        #expect(try shown(setup) == ["kişi", "yabancı", "ben"])
        #expect(try summary(setup).unread == 2)
    }
}
