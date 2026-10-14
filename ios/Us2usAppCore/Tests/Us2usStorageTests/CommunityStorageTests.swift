import Foundation
import LibSignalClient
import Testing
import Us2usKit
@testable import Us2usStorage

@Suite("Communities: encrypted persistence, dedupe and atomic receive")
struct CommunityStorageTests {
    @Test func encryptedContentAndKeySlotSurviveReopen() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: 1, isPublic: false, owner: owner, name: "", members: [owner])
        let signed = SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        var content = BRPCommunityMessage(); content.communityID = state.id.bytes; content.messageID = AccountId(uuid: UUID()).bytes
        content.revision = 1; content.text = "community secret must not appear in the database"; content.sentAt = 123
        try storage.write {
            try $0.communities.save(signed, localName: "private secret name")
            try $0.communities.store(content, sender: owner.accountId, outgoing: false)
            try $0.communities.store(content, sender: owner.accountId, outgoing: false)
        }
        #expect(environment.databaseBytes().range(of: Data(content.text.utf8)) == nil)
        #expect(try storage.read { try $0.contacts.all() }.isEmpty)
        storage.close()
        let reopened = try environment.open()
        #expect(try reopened.read { try $0.communities.messages(state.id) }.map(\.text) == [content.text])
        #expect(try reopened.read { try $0.communities.get(state.id)?.name } == "private secret name")
        try reopened.write { try $0.communities.forget(state.id) }
        #expect(try reopened.read { try $0.communities.all() }.isEmpty)
    }

    @Test func atomicRatchetAndCommunityReceiveWithoutContact() async throws {
        let environment = try TestEnvironment(); let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let bob = Party(account: account.accountId, store: account.store); let alice = Party.inMemory()
        try alice.cipher.processBundle(try bob.bundle())
        let owner = CommunityMember(accountId: alice.account, identityKey: alice.identity.identityKey.serialize())
        let member = CommunityMember(accountId: bob.account, identityKey: bob.identity.identityKey.serialize())
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: 2, isPublic: true, owner: owner, name: "Atomic", members: [owner, member])
        let signed = SignedCommunityState(state: state.bytes, signature: alice.identity.privateKey.generateSignature(message: state.bytes))
        try storage.write { try $0.communities.save(signed) }
        var message = BRPCommunityMessage(); message.communityID = state.id.bytes; message.revision = 2
        message.messageID = RandomId.make(); message.text = "atomic community"; message.sentAt = 123
        var content = BRPContent(); content.body = .community(message)
        let envelope = try alice.seal(content, to: bob)
        struct Failure: Error {}
        try await account.store.withStagedInboundWrites {
            let decrypted = try bob.receive(envelope)
            #expect(throws: Failure.self) {
                try storage.write { tx in
                    try tx.communities.store(decrypted.content.community, sender: alice.account, outgoing: false)
                    throw Failure()
                }
            }
            #expect(try account.store.discardStagedInboundWrites())
        }
        try await account.store.withStagedInboundWrites {
            let decrypted = try bob.receive(envelope)
            try storage.write { tx in
                try tx.communities.store(decrypted.content.community, sender: alice.account, outgoing: false)
                try tx.communities.recordEnvelope("community-envelope")
            }
            #expect(!account.store.hasStagedWrites)
        }
        #expect(try storage.read { try $0.contacts.all() }.isEmpty)
        #expect(try storage.read { try $0.communities.messages(state.id) }.count == 1)
        storage.close()
        let reopened = try environment.open()
        #expect(try reopened.read { try $0.communities.sawEnvelope("community-envelope") })
        #expect(try reopened.read { try $0.communities.messages(state.id) }.map(\.text) == ["atomic community"])
    }
    // MARK: Order, outbox and pins

    private func ownedCommunity(_ storage: LocalStorage, _ account: TestAccount, members extra: [CommunityMember] = []) throws -> (CommunityState, CommunityMember) {
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: 1, isPublic: true, owner: owner, name: "Sıra", members: [owner] + extra)
        try storage.write { try $0.communities.save(SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))) }
        return (state, owner)
    }

    private func text(_ body: String, in state: CommunityState, sentAt: UInt64, id: AccountId = AccountId(uuid: UUID())) -> BRPCommunityMessage {
        var message = BRPCommunityMessage()
        message.communityID = state.id.bytes; message.messageID = id.bytes; message.revision = state.revision
        message.sentAt = sentAt; message.text = body
        return message
    }

    @Test("the latest 1000 messages show, in local receive order; a sender's clock cannot reorder them")
    func latestMessagesInReceiveOrder() throws {
        let environment = try TestEnvironment(); let storage = try environment.open(); defer { storage.close() }
        let account = try TestAccount(storage: storage)
        let peer = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        let (state, _) = try ownedCommunity(storage, account, members: [peer])
        let start = t0.millisecondsSince1970
        try storage.write { tx in
            for index in 0..<1002 {
                let now = t0.addingTimeInterval(Double(index))
                try tx.communities.store(text("m\(index)", in: state, sentAt: start + UInt64(index) * 1000), sender: peer.accountId, outgoing: false, now: now)
            }
            // Claims to be from long ago and from far ahead: stored where they arrived.
            try tx.communities.store(text("past", in: state, sentAt: 1), sender: peer.accountId, outgoing: false, now: t0.addingTimeInterval(2000))
            try tx.communities.store(text("future", in: state, sentAt: UInt64(Int64.max)), sender: peer.accountId, outgoing: false, now: t0.addingTimeInterval(2001))
        }
        let page = try storage.read { try $0.communities.messages(state.id) }
        #expect(page.count == CommunityRepository.pageSize)
        #expect(page.last?.text == "future")
        #expect(page[page.count - 2].text == "past")
        #expect(page.first?.text == "m4")
        #expect(zip(page, page.dropFirst()).allSatisfy { $0.receivedAt <= $1.receivedAt })
        // The displayed time stays near the arrival.
        let future = try #require(page.last)
        #expect(future.sentAt <= future.receivedAt + 60_000)
        let past = page[page.count - 2]
        #expect(past.sentAt >= past.receivedAt - 15 * 86_400_000)
    }

    @Test("one unreachable recipient does not stop the message for the others")
    func perRecipientOutbox() throws {
        let environment = try TestEnvironment(); let storage = try environment.open(); defer { storage.close() }
        let account = try TestAccount(storage: storage)
        let members = (0..<3).map { _ in CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize()) }
        let (state, owner) = try ownedCommunity(storage, account, members: members)
        func queue(_ body: String) throws -> [CommunityOutboxItem] {
            let id = AccountId(uuid: UUID())
            try storage.write { tx in
                try tx.communities.store(text(body, in: state, sentAt: 1, id: id), sender: owner.accountId, outgoing: true)
                for member in members { try tx.communities.enqueue(community: state.id, message: id, recipient: member.accountId, revision: 1, envelope: Data([1])) }
            }
            return try storage.read { try $0.communities.pending() }.filter { $0.messageId == id }
        }
        func status(_ body: String) throws -> CommunityDeliveryStatus? {
            try storage.read { try $0.communities.messages(state.id) }.first { $0.text == body }?.status
        }
        let partial = try queue("partial")
        try storage.write { try $0.communities.complete(partial[0]) }
        try storage.write { try $0.communities.dropRecipient(partial[1]) }
        #expect(try status("partial") == .partial)
        #expect(try storage.read { try $0.communities.pending() }.map(\.recipient) == [partial[2].recipient])
        try storage.write { try $0.communities.complete(partial[2]) }
        #expect(try status("partial") == .partial)

        let whole = try queue("whole")
        for item in whole { try storage.write { try $0.communities.complete(item) } }
        #expect(try status("whole") == .sent)

        let stopped = try queue("stopped")
        try storage.write { try $0.communities.complete(stopped[0]) }
        try storage.write { try $0.communities.cancel(stopped[1]) }
        #expect(try status("stopped") == .failed)
        #expect(try storage.read { try $0.communities.pending() }.isEmpty)
    }

    @Test("a pinned owner key cannot be substituted by a later state")
    func ownerSubstitutionIsRefused() throws {
        let environment = try TestEnvironment(); let storage = try environment.open(); defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let real = IdentityKeyPair.generate(), forged = IdentityKeyPair.generate()
        let id = AccountId(uuid: UUID())
        try storage.write { try $0.communities.pin(id, ownerKey: real.identityKey.serialize(), name: "Aile 👨‍👩‍👧") }
        let impostor = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: forged.identityKey.serialize())
        let state = try CommunityState(id: id, revision: 1, isPublic: true, owner: impostor, name: "Aile 👨‍👩‍👧", members: [impostor])
        let signed = SignedCommunityState(state: state.bytes, signature: forged.privateKey.generateSignature(message: state.bytes))
        #expect(throws: CommunityError.identityMismatch) { try storage.write { try $0.communities.save(signed) } }
        let stored = try #require(try storage.read { try $0.communities.get(id) })
        #expect(stored.signedState == nil && !stored.isActive && stored.ownerKey == real.identityKey.serialize())
        // Names follow the shared rule.
        #expect(throws: CommunityError.invalidState) {
            try storage.write { try $0.communities.pin(AccountId(uuid: UUID()), ownerKey: real.identityKey.serialize(), name: "a\u{202E}b") }
        }
    }

    @Test("an ended membership stops its queue; forgetting destroys its key slot")
    func endedMembershipAndForget() throws {
        let environment = try TestEnvironment(); let storage = try environment.open(); defer { storage.close() }
        let account = try TestAccount(storage: storage)
        let member = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        let (state, owner) = try ownedCommunity(storage, account, members: [member])
        let id = AccountId(uuid: UUID())
        try storage.write { tx in
            try tx.communities.store(text("queued", in: state, sentAt: 1, id: id), sender: owner.accountId, outgoing: true)
            try tx.communities.enqueue(community: state.id, message: id, recipient: member.accountId, revision: 1, envelope: Data([1]))
            try tx.communities.markCurrentMemberships([])
        }
        #expect(try storage.read { try $0.communities.get(state.id)?.isActive } == false)
        #expect(try storage.read { try $0.communities.pending() }.isEmpty)
        #expect(try storage.read { try $0.communities.messages(state.id) }.first?.status == .failed)
        let slots = storage.keyring.slotIds()
        try storage.write { try $0.communities.forget(state.id) }
        #expect(storage.keyring.slotIds().count == slots.count - 1)
        #expect(try storage.read { try $0.communities.get(state.id) } == nil)
        #expect(environment.databaseBytes().range(of: Data("queued".utf8)) == nil)
    }
}
