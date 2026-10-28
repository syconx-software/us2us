import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Communities v2: media, receipts, deletes and migration v8")
struct CommunityV2StorageTests {
    struct Setup {
        let environment: TestEnvironment
        let storage: LocalStorage
        let account: TestAccount
        let state: CommunityState
        let owner: CommunityMember
        let members: [CommunityMember]
    }

    /// A community this account owns, with `count` other members.
    private func setup(members count: Int = 3) throws -> Setup {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        let account = try TestAccount(storage: storage)
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let members = (0..<count).map { _ in
            CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: IdentityKeyPair.generate().identityKey.serialize())
        }
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: true, owner: owner, name: "Medya", members: [owner] + members)
        try storage.write {
            try $0.communities.save(SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes)))
        }
        return Setup(environment: environment, storage: storage, account: account, state: state, owner: owner, members: members)
    }

    private func text(_ body: String, in state: CommunityState, id: AccountId = AccountId(uuid: UUID())) -> BRPCommunityMessage {
        BRPCommunityMessage.text(body, community: state.id, revision: state.revision, messageId: id.bytes)
    }

    private func media(in state: CommunityState, caption: String = "", id: AccountId = AccountId(uuid: UUID()), token: Bool = false) -> BRPCommunityMessage {
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = RandomId.make()
        attachment.key = Data(repeating: 6, count: 64)
        attachment.digest = Data(repeating: 7, count: 32)
        attachment.size = 1234
        attachment.contentType = "image/jpeg"
        attachment.thumbnail = Data([0xFF, 0xD8, 0xFF, 0xD9])
        if token { attachment.deleteToken = Data(repeating: 0x5A, count: 32) }
        return BRPCommunityMessage.media(attachment, caption: caption, community: state.id, revision: state.revision, messageId: id.bytes)
    }

    @Test func historyPagesReachAll5107MessagesWithEqualTimesAndDeletedBoundary() throws {
        let setup = try setup(members: 1)
        defer { setup.storage.close() }
        let id = setup.state.id
        try setup.storage.write { tx in
            for index in 0..<5107 {
                let message = text("history-\(index)", in: setup.state)
                try tx.communities.store(message, sender: setup.owner.accountId, outgoing: false, now: t0)
            }
        }
        var page = try setup.storage.read { try $0.communities.history(id) }
        #expect(page.items.count == 100 && page.hasOlder)
        let firstFloor = try #require(page.oldest)
        let boundary = try #require(page.items.first)
        var seen: [String] = []
        while true {
            seen.insert(contentsOf: page.items.map(\.id), at: 0)
            if !page.hasOlder { break }
            page = try setup.storage.read { try $0.communities.history(id, before: page.oldest) }
        }
        #expect(seen.count == 5107 && Set(seen).count == 5107)
        try setup.storage.write { tx in
            try tx.communities.deleteForMe(community: id, sender: boundary.sender, messageId: boundary.messageId, now: t0)
        }
        let older = try setup.storage.read { try $0.communities.history(id, before: firstFloor) }
        #expect(older.items.count == 100)
        try setup.storage.write { tx in
            try tx.communities.store(text("new arrival", in: setup.state), sender: setup.owner.accountId,
                outgoing: false, now: t0.addingTimeInterval(1))
        }
        let window = try setup.storage.read { try $0.communities.history(id, through: older.oldest) }
        #expect(window.items.count == 200)
        #expect(window.items.last?.text == "new arrival")
        #expect(!window.items.contains { $0.id == boundary.id })
    }

    // MARK: Migration

    @Test("a v7 database with community messages is upgraded to v8: text kept, nothing unread, new tables work")
    func migrationFromV7() throws {
        let setup = try setup(members: 1)
        let peer = setup.members[0].accountId
        try setup.storage.write { tx in
            try tx.communities.store(text("eski mesaj", in: setup.state), sender: peer, outgoing: false, now: t0)
            try tx.communities.store(
                text("benim", in: setup.state), sender: setup.owner.accountId, outgoing: true, now: t0.addingTimeInterval(0.001))
        }
        // Turn the database back into what v7 wrote.
        try setup.storage.database.locked { connection in
            for statement in [
                "ALTER TABLE contacts DROP COLUMN request_sent_at",
                "DROP TABLE former_contacts",
                "DROP TABLE community_media_deletes", "DROP TABLE community_tombstones", "DROP TABLE community_recipients",
                "DROP TABLE community_attachments", "DROP INDEX community_messages_unread", "DROP INDEX community_messages_receipts_due",
                "ALTER TABLE community_messages DROP COLUMN kind", "ALTER TABLE community_messages DROP COLUMN deleted_by",
                "ALTER TABLE community_messages DROP COLUMN displayed_at", "ALTER TABLE community_messages DROP COLUMN delivery_due",
                "ALTER TABLE community_messages DROP COLUMN read_due", "ALTER TABLE community_outbox DROP COLUMN event",
                "ALTER TABLE community_messages DROP COLUMN sticker",
                "PRAGMA user_version = 7",
            ] { try connection.execute(statement) }
        }
        setup.storage.close()
        let storage = try setup.environment.open()
        defer { storage.close() }
        #expect(try storage.database.locked { try $0.scalar("PRAGMA user_version") } == .integer(Int64(Schema.latestVersion)))
        let items = try storage.read { try $0.communities.messages(setup.state.id) }
        #expect(items.map(\.text) == ["eski mesaj", "benim"])
        #expect(items.allSatisfy { $0.kind == .text && $0.deletion == nil && $0.isDisplayed })
        #expect(try storage.read { try $0.communities.summaries() }.first?.unread == 0)
        #expect(try storage.read { try $0.communities.dueReceipts(.delivery) }.isEmpty)
        // The v8 tables are there.
        _ = try storage.write { tx in
            try tx.communities.store(self.media(in: setup.state, caption: "yeni"), sender: peer, outgoing: false, now: t0.addingTimeInterval(10))
        }
        #expect(try storage.read { try $0.communities.messages(setup.state.id) }.last?.attachment?.state == .remote)
        // A queued receipt keeps its content (sealed) for a later revision.
        let receipt = BRPCommunityMessage.receipt(.delivery, for: [RandomId.make()], community: setup.state.id, revision: 1)
        let event = try #require(AccountId(bytes: receipt.messageID))
        try storage.write {
            try $0.communities.enqueue(
                community: setup.state.id, message: event, recipient: peer, revision: 1, envelope: Data([1]), event: receipt)
        }
        let queued = try #require(try storage.read { try $0.communities.pending() }.first)
        #expect(queued.event == receipt && queued.messageId == event)
        let resealed = try storage.write { try $0.communities.reseal(queued, revision: 2, envelope: Data([2])) }
        #expect(try storage.read { try $0.communities.pending() } == [resealed])
        #expect(resealed.revision == 2 && resealed.envelope == Data([2]) && resealed.event == receipt)
    }

    // MARK: Media

    @Test("own media: file encrypted at rest, the token sealed apart and never in the pointer")
    func ownMediaStorage() throws {
        let setup = try setup()
        defer { setup.storage.close() }
        let own = setup.owner.accountId
        let id = AccountId(uuid: UUID())
        let photo = Data("pixels-that-must-not-appear".utf8)
        try setup.storage.write { tx in
            try tx.communities.store(self.media(in: setup.state, caption: "Gizli başlık", id: id), sender: own, outgoing: true)
            try tx.communities.storeAttachmentFile(photo, community: setup.state.id, sender: own, messageId: id)
        }
        var uploaded = media(in: setup.state, id: id, token: true).attachment
        uploaded.deleteToken = Data(repeating: 0x5A, count: 32)
        try setup.storage.write { try $0.communities.setUploaded(uploaded, community: setup.state.id, sender: own, messageId: id) }
        let item = try #require(try setup.storage.read { try $0.communities.message(setup.state.id, sender: own, messageId: id) })
        #expect(item.kind == .media && item.text == "Gizli başlık")
        #expect(item.attachment?.state == .uploaded && item.attachment?.hasFile == true)
        #expect(item.attachment?.descriptor.deleteToken.isEmpty == true)
        #expect(try setup.storage.read { try $0.communities.readAttachmentFile(community: setup.state.id, sender: own, messageId: id) } == photo)
        let bytes = setup.environment.databaseBytes()
        #expect(bytes.range(of: Data(repeating: 0x5A, count: 32)) == nil)
        #expect(bytes.range(of: Data("Gizli başlık".utf8)) == nil)
        let files = try FileManager.default.contentsOfDirectory(at: setup.environment.layout.filesDirectory, includingPropertiesForKeys: nil)
        #expect(files.allSatisfy { (try? Data(contentsOf: $0))?.range(of: photo) == nil })
        // Garbage collection keeps community files.
        _ = try setup.storage.performDueWipes()
        #expect(try setup.storage.read { try $0.communities.readAttachmentFile(community: setup.state.id, sender: own, messageId: id) } == photo)
        // Unsent until its fanout is prepared.
        #expect(try setup.storage.read { try $0.communities.unsentMedia(sender: own) }.map(\.messageId) == [id])
        try setup.storage.write { try $0.communities.setRecipients([setup.members[0].accountId: .pending], community: setup.state.id, messageId: id) }
        #expect(try setup.storage.read { try $0.communities.unsentMedia(sender: own) }.isEmpty)
    }

    // MARK: Receipts

    @Test("receipts: only from recipients, only for own live messages; aggregate sent → delivered → read")
    func receiptAggregation() throws {
        let setup = try setup(members: 3)
        defer { setup.storage.close() }
        let own = setup.owner.accountId
        let (a, b, c) = (setup.members[0].accountId, setup.members[1].accountId, setup.members[2].accountId)
        let outsider = AccountId(uuid: UUID())
        let id = AccountId(uuid: UUID())
        let incoming = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.communities.store(text("herkese", in: setup.state, id: id), sender: own, outgoing: true)
            try tx.communities.store(text("gelen", in: setup.state, id: incoming), sender: a, outgoing: false)
            try tx.communities.setRecipients([a: .pending, b: .pending, c: .pending], community: setup.state.id, messageId: id)
            for (recipient, done) in [(a, true), (b, true), (c, false)] {
                let item = CommunityOutboxItem(communityId: setup.state.id, messageId: id, recipient: recipient, revision: 1, envelope: Data([1]))
                try tx.communities.enqueue(item)
                if done { try tx.communities.complete(item) } else { try tx.communities.dropRecipient(item) }
            }
        }
        func summary() throws -> CommunityReceiptSummary? {
            try setup.storage.read { try $0.communities.message(setup.state.id, sender: own, messageId: id)?.receipts }
        }
        #expect(try summary() == CommunityReceiptSummary(accepted: 2, delivered: 0, read: 0, pending: 0))
        try setup.storage.write { tx in
            // A receipt from someone outside the recipient set, and one for a message this phone did not send.
            do { let value = try tx.communities.applyReceipt(.delivery, ids: [id.bytes], from: outsider, own: own, community: setup.state.id); #expect(value == 0) }
            do { let value = try tx.communities.applyReceipt(.read, ids: [incoming.bytes], from: b, own: own, community: setup.state.id); #expect(value == 0) }
            do { let value = try tx.communities.applyReceipt(.delivery, ids: [id.bytes, id.bytes], from: a, own: own, community: setup.state.id); #expect(value == 1) }
        }
        #expect(try summary()?.delivered == 1)
        #expect(try summary()?.deliveredToAll == false)
        _ = try setup.storage.write { tx in
            try tx.communities.applyReceipt(.read, ids: [id.bytes], from: b, own: own, community: setup.state.id, now: t0)
        }
        #expect(try summary()?.deliveredToAll == true)
        let recipients = try setup.storage.read { try $0.communities.recipients(community: setup.state.id, messageId: id) }
        let rowB = try #require(recipients.first { $0.recipient == b })
        #expect(rowB.readAt == t0.storageMilliseconds && rowB.deliveredAt == t0.storageMilliseconds)
        #expect(recipients.first { $0.recipient == c }?.state == .skipped)
        // Times never move.
        try setup.storage.write { tx in
            try tx.communities.applyReceipt(.read, ids: [id.bytes], from: b, own: own, community: setup.state.id, now: t0.addingTimeInterval(60))
            try tx.communities.applyReceipt(.read, ids: [id.bytes], from: a, own: own, community: setup.state.id)
        }
        #expect(try setup.storage.read { try $0.communities.recipients(community: setup.state.id, messageId: id) }.first { $0.recipient == b }?.readAt == t0.storageMilliseconds)
        #expect(try summary()?.readByAll == true)
        // Deleted: receipts are ignored from then on.
        try setup.storage.write { tx in
            try tx.communities.applyDelete(community: setup.state.id, targetSender: own, targetId: id, by: .bySender, own: own)
            do { let value = try tx.communities.applyReceipt(.read, ids: [id.bytes], from: c, own: own, community: setup.state.id); #expect(value == 0) }
        }
        #expect(try summary() == nil)
    }

    @Test("DELIVERY is owed for every stored message, READ only when displayed with read receipts on")
    func dueReceipts() throws {
        let setup = try setup(members: 2)
        defer { setup.storage.close() }
        let (a, b) = (setup.members[0].accountId, setup.members[1].accountId)
        let ids = (0..<3).map { _ in AccountId(uuid: UUID()) }
        try setup.storage.write { tx in
            try tx.communities.store(text("1", in: setup.state, id: ids[0]), sender: a, outgoing: false, now: t0)
            try tx.communities.store(self.media(in: setup.state, id: ids[1]), sender: a, outgoing: false, now: t0.addingTimeInterval(1))
            try tx.communities.store(text("3", in: setup.state, id: ids[2]), sender: b, outgoing: false, now: t0.addingTimeInterval(2))
            // A duplicate owes nothing more.
            do { let value = try tx.communities.store(text("1", in: setup.state, id: ids[0]), sender: a, outgoing: false); #expect(value == .duplicate) }
        }
        let due = try setup.storage.read { try $0.communities.dueReceipts(.delivery) }
        #expect(due.map(\.sender) == [a, b])
        #expect(due.first?.messageIds == [ids[0], ids[1]])
        #expect(try setup.storage.read { try $0.communities.summaries() }.first?.unread == 3)
        try setup.storage.write { tx in
            for group in due { try tx.communities.clearDue(.delivery, community: group.community, sender: group.sender, messageIds: group.messageIds) }
        }
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.delivery) }.isEmpty)

        // Read while read receipts are off: nothing owed, also not later.
        try setup.storage.write { let value = try $0.communities.markDisplayed(community: setup.state.id, readReceipts: false); #expect(value == 3) }
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.read) }.isEmpty)
        #expect(try setup.storage.read { try $0.communities.summaries() }.first?.unread == 0)
        let later = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.communities.store(text("sonra", in: setup.state, id: later), sender: b, outgoing: false)
            try tx.communities.markDisplayed(community: setup.state.id, readReceipts: true)
        }
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.read) }.map(\.messageIds) == [[later]])
    }

    // MARK: Deletes

    @Test("a delete before its target is remembered; the target arrives as its placeholder, owing DELIVERY but never READ")
    func earlyDelete() throws {
        let setup = try setup(members: 1)
        defer { setup.storage.close() }
        let own = setup.owner.accountId
        let peer = setup.members[0].accountId
        let id = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            do { let value = try tx.communities.applyDelete(community: setup.state.id, targetSender: peer, targetId: id, by: .byOwner, own: own); #expect(value == .remembered) }
            do { let value = try tx.communities.pendingDelete(community: setup.state.id, sender: peer, messageId: id); #expect(value == .byOwner) }
            do { let value = try tx.communities.store(self.media(in: setup.state, caption: "kaldırılacak", id: id), sender: peer, outgoing: false); #expect(value == .placeholder(.byOwner)) }
        }
        let item = try #require(try setup.storage.read { try $0.communities.messages(setup.state.id) }.first)
        #expect(item.deletion == .byOwner && item.text.isEmpty && item.attachment == nil && item.isDisplayed)
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.delivery) }.first?.messageIds == [id])
        _ = try setup.storage.write { try $0.communities.markDisplayed(community: setup.state.id, readReceipts: true) }
        #expect(try setup.storage.read { try $0.communities.dueReceipts(.read) }.isEmpty)
        #expect(try setup.storage.read { try $0.communities.pendingDelete(community: setup.state.id, sender: peer, messageId: id) } == nil)
        #expect(setup.environment.databaseBytes().range(of: Data("kaldırılacak".utf8)) == nil)
        // A second delete does nothing.
        try setup.storage.write {
            let value = try $0.communities.applyDelete(community: setup.state.id, targetSender: peer, targetId: id, by: .bySender, own: own)
            #expect(value == .alreadyDeleted)
        }
    }

    @Test("pending deletes: at most 1000 per community, oldest first, and gone after 30 days")
    func pendingDeleteBounds() throws {
        let setup = try setup(members: 1)
        defer { setup.storage.close() }
        let own = setup.owner.accountId
        let peer = setup.members[0].accountId
        let first = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.communities.applyDelete(community: setup.state.id, targetSender: peer, targetId: first, by: .bySender, own: own, now: t0)
            for index in 1...1000 {
                try tx.communities.applyDelete(
                    community: setup.state.id, targetSender: peer, targetId: AccountId(uuid: UUID()), by: .bySender, own: own,
                    now: t0.addingTimeInterval(Double(index)))
            }
        }
        let count = try setup.storage.database.locked { try $0.queryOne("SELECT COUNT(*) AS n FROM community_tombstones")?.int("n") }
        #expect(count == 1000)
        #expect(try setup.storage.read { try $0.communities.pendingDelete(community: setup.state.id, sender: peer, messageId: first) } == nil)
        try setup.storage.write { try $0.communities.prune(now: t0.addingTimeInterval(31 * 86_400)) }
        #expect(try setup.storage.database.locked { try $0.queryOne("SELECT COUNT(*) AS n FROM community_tombstones")?.int("n") } == 0)
    }

    @Test("delete for everyone of own media queues its blob with the token; delete for me sends nothing and dedupes")
    func deleteForEveryoneAndForMe() throws {
        let setup = try setup(members: 1)
        defer { setup.storage.close() }
        let own = setup.owner.accountId
        let peer = setup.members[0].accountId
        let id = AccountId(uuid: UUID())
        var uploaded = media(in: setup.state, id: id).attachment
        uploaded.deleteToken = Data(repeating: 0x33, count: 32)
        try setup.storage.write { tx in
            try tx.communities.store(self.media(in: setup.state, id: id), sender: own, outgoing: true)
            try tx.communities.storeAttachmentFile(Data("x".utf8), community: setup.state.id, sender: own, messageId: id)
            try tx.communities.setUploaded(uploaded, community: setup.state.id, sender: own, messageId: id)
            try tx.communities.setRecipients([peer: .pending], community: setup.state.id, messageId: id)
            try tx.communities.enqueue(community: setup.state.id, message: id, recipient: peer, revision: 1, envelope: Data([1]))
        }
        let filesBefore = try FileManager.default.contentsOfDirectory(atPath: setup.environment.layout.filesDirectory.path).count
        try setup.storage.write {
            let value = try $0.communities.applyDelete(community: setup.state.id, targetSender: own, targetId: id, by: .bySender, own: own)
            #expect(value == .applied)
        }
        let deletes = try setup.storage.read { try $0.communities.pendingMediaDeletes() }
        #expect(deletes.map(\.mediaId) == [uploaded.mediaID] && deletes.first?.deleteToken == uploaded.deleteToken)
        #expect(try setup.storage.read { try $0.communities.pending() }.isEmpty)
        #expect(try FileManager.default.contentsOfDirectory(atPath: setup.environment.layout.filesDirectory.path).count == filesBefore - 1)
        try setup.storage.write { try $0.communities.finishMediaDelete(uploaded.mediaID) }
        #expect(try setup.storage.read { try $0.communities.pendingMediaDeletes() }.isEmpty)

        // Delete for me: gone, a retransmission is a duplicate, nothing queued.
        let other = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.communities.store(self.media(in: setup.state, id: other), sender: peer, outgoing: false)
            try tx.communities.storeAttachmentFile(Data("y".utf8), community: setup.state.id, sender: peer, messageId: other)
            try tx.communities.deleteForMe(community: setup.state.id, sender: peer, messageId: other)
            do { let value = try tx.communities.store(self.media(in: setup.state, id: other), sender: peer, outgoing: false); #expect(value == .duplicate) }
        }
        #expect(try setup.storage.read { try $0.communities.messages(setup.state.id) }.map(\.messageId) == [id])
        #expect(try setup.storage.read { try $0.communities.pendingMediaDeletes() }.isEmpty)
    }

    @Test("the chats list: the latest message and the unread count per community; forget removes files")
    func summariesAndForget() throws {
        let setup = try setup(members: 1)
        defer { setup.storage.close() }
        let peer = setup.members[0].accountId
        let id = AccountId(uuid: UUID())
        try setup.storage.write { tx in
            try tx.communities.store(text("ilk", in: setup.state), sender: peer, outgoing: false, now: t0)
            try tx.communities.store(self.media(in: setup.state, caption: "Foto", id: id), sender: peer, outgoing: false, now: t0.addingTimeInterval(5))
            try tx.communities.storeAttachmentFile(Data("z".utf8), community: setup.state.id, sender: peer, messageId: id)
        }
        let summary = try #require(try setup.storage.read { try $0.communities.summaries() }.first)
        #expect(summary.unread == 2 && summary.last?.messageId == id && summary.last?.kind == .media)
        #expect(summary.lastActivity == t0.addingTimeInterval(5).storageMilliseconds)
        try setup.storage.write { try $0.communities.forget(setup.state.id) }
        #expect(try FileManager.default.contentsOfDirectory(atPath: setup.environment.layout.filesDirectory.path).filter { $0.hasSuffix(".item") }.isEmpty)
        #expect(try setup.storage.read { try $0.communities.summaries() }.isEmpty)
    }
}
