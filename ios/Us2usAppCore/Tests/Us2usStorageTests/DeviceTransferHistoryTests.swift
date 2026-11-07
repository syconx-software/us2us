import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

/// PROTOCOL.md §5.6: what the old phone's history becomes on the new one, and what stays behind.
@Suite("Moving the history to a new phone")
struct DeviceTransferHistoryTests {
    private let peer = AccountId(bytes: Data(repeating: 0x21, count: 16))!

    /// An environment's directory goes with it: they stay for the whole test.
    private final class Kept: @unchecked Sendable {
        var environments: [TestEnvironment] = []
    }
    private let kept = Kept()

    private func openPhone() throws -> LocalStorage {
        let environment = try TestEnvironment()
        kept.environments.append(environment)
        return try environment.open()
    }

    private func text(_ body: String, id: UInt8, timer: UInt32 = 0, viewOnce: Bool = false) -> BRPDataMessage {
        var data = BRPDataMessage()
        data.messageID = Data(repeating: id, count: 16)
        data.sentAt = 1_700_000_000_000 + UInt64(id)
        data.text.body = body
        data.expireTimerSeconds = timer
        data.viewOnce = viewOnce
        return data
    }

    private func attachmentMessage(id: UInt8) -> BRPDataMessage {
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = Data(repeating: 0x55, count: 16)
        attachment.key = Data(repeating: 0x56, count: 64)
        attachment.digest = Data(repeating: 0x57, count: 32)
        attachment.size = 3
        attachment.contentType = "image/jpeg"
        var data = BRPDataMessage()
        data.messageID = Data(repeating: id, count: 16)
        data.sentAt = 1_700_000_000_000 + UInt64(id)
        data.attachment = attachment
        return data
    }

    private final class Sink: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: [Data] = []
        func add(_ data: Data) { lock.withLock { stored.append(data) } }
        var records: [Data] { lock.withLock { stored } }
    }

    private func export(_ storage: LocalStorage) async throws -> DeviceTransferSpool {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let spool = try DeviceTransferSpool(directory: directory)
        let exporter = DeviceTransferHistoryExporter(storage: storage, settings: BRPTransferSettings())
        let sink = Sink()
        try await exporter.export { sink.add($0) }
        for record in sink.records { try spool.append(record) }
        try spool.finish()
        return spool
    }

    private func oldPhone() throws -> (LocalStorage, ConversationId) {
        let storage = try openPhone()
        _ = try TestAccount(storage: storage)
        let conversation = try storage.write { tx -> ConversationId in
            try tx.contacts.create(peer, state: .accepted)
            return try tx.conversations.ensure(for: peer).id
        }
        return (storage, conversation)
    }

    private func newPhone() throws -> (storage: LocalStorage, own: AccountId) {
        let storage = try openPhone()
        let account = try TestAccount(storage: storage)
        try storage.write { tx in try tx.contacts.create(peer, state: .accepted) }
        return (storage, account.accountId)
    }

    @Test("messages, files, reactions, states and the order arrive; what must not move does not")
    func roundTrip() async throws {
        let (old, conversation) = try oldPhone()
        let keep1 = text("merhaba", id: 1), keep2 = text("nasılsın", id: 2)
        let photo = attachmentMessage(id: 3)
        let timed = text("kaybolur", id: 4, timer: 60), once = text("tek görüntülük", id: 5, viewOnce: true)
        try old.write { tx in
            _ = try tx.messages.storeOutgoing(keep1, in: conversation)
            try tx.messages.markAccepted(keep1.messageID, in: conversation)
            _ = try tx.messages.storeIncoming(keep2, in: conversation)
            _ = try tx.messages.storeIncoming(photo, in: conversation)
            let attachment = try tx.attachments.forMessage(photo.messageID, in: conversation)[0]
            try tx.attachments.storeFile(Data([9, 8, 7]), for: attachment.id)
            try tx.attachments.storeThumbnail(Data([1, 2]), for: attachment.id)
            _ = try tx.messages.storeIncoming(timed, in: conversation)
            _ = try tx.messages.storeOutgoing(once, in: conversation)
            var reaction = BRPDataMessage()
            reaction.messageID = Data(repeating: 6, count: 16)
            reaction.sentAt = 1_700_000_000_100
            reaction.reaction.targetMessageID = keep2.messageID
            reaction.reaction.emoji = "👍"
            _ = try tx.messages.storeOutgoing(reaction, in: conversation)
            try tx.conversations.setDraft("taslak", for: conversation)
            try tx.chatBackgrounds.setForContact(.preset("dusk"), peer)
        }
        let lastActivity = try old.read { try $0.conversations.get(conversation)?.lastActivityAt }

        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        var settings: BRPTransferSettings?
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { settings = $0 }).run()
        #expect(settings != nil)

        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            let messages = try tx.messages.page(in: moved.id, limit: 100)
            #expect(messages.map(\.messageId) == [keep1.messageID, keep2.messageID, photo.messageID])
            #expect(messages[0].content?.text.body == "merhaba")
            #expect(messages[0].status == .sent)
            #expect(messages[0].isOutgoing)
            #expect(messages[0].sender == own)
            #expect(messages[1].sender == peer)
            #expect(messages[1].status == .received)
            #expect(try tx.conversations.unreadCount(moved.id) == 2)
            let reactions = try tx.messages.reactions(on: [keep2.messageID], in: moved.id)
            #expect(reactions[keep2.messageID]?.first?.emoji == "👍")
            let attachment = try #require(try tx.attachments.forMessage(photo.messageID, in: moved.id).first)
            #expect(attachment.hasFile)
            #expect(attachment.state == .downloaded)
            #expect(try tx.attachments.readFile(attachment.id) == Data([9, 8, 7]))
            #expect(try tx.attachments.readThumbnail(attachment.id) == Data([1, 2]))
            #expect(try tx.conversations.draft(for: moved.id) == "taslak")
            #expect(try tx.chatBackgrounds.forContact(peer) == .preset("dusk"))
            #expect(moved.lastActivityAt == lastActivity)
            // Neither the timed message nor the view-once one, and a retransmission stays out too.
            #expect(try tx.messages.wasRemoved(timed.messageID, in: moved.id))
            #expect(try tx.messages.wasRemoved(once.messageID, in: moved.id))
            #expect(try tx.messages.count(in: moved.id) == 3)
        }
        // Under this phone's own keys: the rows are not the old phone's.
        let oldSlot = try old.read { try $0.conversations.get(conversation)?.slotId }
        let newSlot = try new.read { try $0.conversations.forContact(peer)?.slotId }
        #expect(oldSlot != newSlot)
    }

    @Test("a ghost chat keeps its settings but none of its messages, with a fresh timeout")
    func ghostChat() async throws {
        let (old, conversation) = try oldPhone()
        let secret = text("ghost", id: 7)
        try old.write { tx in try tx.conversations.setDraft("yazılan", for: conversation) }
        try old.write { tx in
            _ = try tx.messages.storeIncoming(secret, in: conversation)
            _ = try tx.conversations.updateSettings(of: conversation) { settings in
                settings.ghost.enabled = true
                settings.ghost.timeoutSeconds = 3600
            }
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        let moment = Date()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }, now: moment).run()
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            #expect(moved.settings.ghost.enabled)
            #expect(try tx.conversations.draft(for: moved.id) == nil, "what was typed goes the way of the messages")
            #expect(try tx.messages.count(in: moved.id) == 0)
            #expect(try tx.messages.wasRemoved(secret.messageID, in: moved.id))
            let deadline = try #require(moved.ghostDeadline)
            #expect(deadline.timeIntervalSince(moment) > 3590)
        }
    }

    @Test("what was still being sent arrives as failed, and an unsent upload with it")
    func unsent() async throws {
        let (old, conversation) = try oldPhone()
        let pending = text("yolda", id: 8)
        try old.write { tx in _ = try tx.messages.storeOutgoing(pending, in: conversation) }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            let message = try #require(try tx.messages.get(pending.messageID, in: moved.id))
            #expect(message.status == .failed)
            #expect(message.timestamps.failedAt != nil)
        }
    }

    @Test("what had not gone out goes out from the new phone when asked to, under its own id, and not before")
    func sentAgainFromTheNewPhone() async throws {
        let (old, conversation) = try oldPhone()
        let waiting = text("yolda", id: 8)
        var notUploaded = attachmentMessage(id: 9)
        notUploaded.attachment.mediaID = Data()
        var uploaded = attachmentMessage(id: 10)
        uploaded.attachment.mediaID = Data()
        let pointer = attachmentMessage(id: 10).attachment
        try old.write { tx in
            _ = try tx.messages.storeOutgoing(waiting, in: conversation)
            for message in [notUploaded, uploaded] {
                _ = try tx.messages.storeOutgoing(message, in: conversation)
                let item = try tx.attachments.forMessage(message.messageID, in: conversation)[0]
                try tx.attachments.storeFile(Data([9, 8, 7]), for: item.id)
                try tx.attachments.setState(.uploading, of: item.id)
            }
            let item = try tx.attachments.forMessage(uploaded.messageID, in: conversation)[0]
            try tx.attachments.setDescriptor(pointer, of: item.id)
            try tx.attachments.setState(.uploaded, of: item.id)
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        let moved = try #require(try new.read { try $0.conversations.forContact(peer) }).id
        try new.read { tx in
            #expect(try tx.outbox.due(now: Date().addingTimeInterval(60)).isEmpty, "nothing goes out by itself")
            let failed = try tx.outbox.all(state: .failed)
            #expect(failed.map(\.messageId) == [waiting.messageID, notUploaded.messageID, uploaded.messageID])
            let item = try tx.attachments.forMessage(notUploaded.messageID, in: moved)[0]
            #expect(item.state == .pendingUpload)
            #expect(try tx.attachments.readFile(item.id) == Data([9, 8, 7]))
            let entry = try #require(failed.first { $0.messageId == uploaded.messageID })
            guard case .data(let data) = entry.content.body else { throw StorageError.integrity }
            #expect(data.attachment.mediaID == pointer.mediaID, "the pointer it got on the old phone goes with it")
        }
        try new.write { tx in
            for entry in try tx.outbox.all(state: .failed) { try tx.outbox.retry(entry.id) }
        }
        try new.read { tx throws in
            #expect(try tx.outbox.all(state: .ready).map(\.messageId) == [waiting.messageID, uploaded.messageID])
            #expect(try tx.outbox.all(state: .waitingForUpload).map(\.messageId) == [notUploaded.messageID])
            #expect(try tx.messages.get(waiting.messageID, in: moved)?.status == .sending)
        }
    }

    @Test("a blocked contact keeps what it was before the block")
    func blockedContact() async throws {
        let (old, conversation) = try oldPhone()
        try old.write { tx in
            _ = try tx.messages.storeIncoming(text("önceki", id: 1), in: conversation)
            try tx.contacts.block(peer)
        }
        let spool = try await export(old)
        defer { spool.remove() }
        // As a backup restores it.
        let new = try openPhone()
        let account = try TestAccount(storage: new)
        try new.write { tx in try tx.contacts.create(peer, state: .blocked) }
        try DeviceTransferHistoryImporter(
            storage: new, ownAccount: account.accountId, reader: spool.reader(), applySettings: { _ in }
        ).run()
        try new.write { tx throws in
            #expect(try tx.contacts.unblock(peer))
            #expect(try tx.contacts.requestState(of: peer) == .accepted)
        }
    }

    @Test("a sender's absurd clock does not stop the move")
    func absurdClock() async throws {
        let (old, conversation) = try oldPhone()
        var wild = text("saat", id: 1)
        wild.sentAt = .max
        try old.write { tx in _ = try tx.messages.storeIncoming(wild, in: conversation) }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            #expect(try tx.messages.count(in: moved.id) == 1)
        }
    }

    @Test("the new phone takes no ghost chat's messages or draft, even if they are sent")
    func ghostFromASender() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let spool = try DeviceTransferSpool(directory: directory)
        defer { spool.remove() }
        var head = BRPTransferConversation()
        head.contact = peer.bytes
        head.requestState = ConversationRequestState.accepted.rawValue
        head.settings.ghost.enabled = true
        head.settings.ghost.timeoutSeconds = 3600
        head.draft = "yazılan"
        var first = BRPTransferRecord()
        first.conversation = head
        var message = BRPTransferMessage()
        let secret = text("ghost", id: 7)
        message.messageID = secret.messageID
        message.content = try secret.serializedBytes()
        message.kind = 1
        message.status = 10
        var second = BRPTransferRecord()
        second.message = message
        try spool.append(try first.serializedBytes())
        try spool.append(try second.serializedBytes())
        try spool.finish()
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            #expect(try tx.messages.count(in: moved.id) == 0)
            #expect(try tx.conversations.draft(for: moved.id) == nil)
        }
    }

    private func ownCommunity(_ old: LocalStorage, _ account: TestAccount, others: [AccountId]) throws -> CommunityState {
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let members = others.map { CommunityMember(accountId: $0, identityKey: identity.identityKey.serialize()) }
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: false, owner: owner, name: "",
            members: ([owner] + members).sorted { $0.accountId.string < $1.accountId.string })
        let signed = SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        try old.write { try $0.communities.save(signed, localName: "topluluk") }
        return state
    }

    private func communityText(_ text: String, in state: CommunityState) -> BRPCommunityMessage {
        var content = BRPCommunityMessage()
        content.communityID = state.id.bytes
        content.revision = 1
        content.messageID = AccountId(uuid: UUID()).bytes
        content.sentAt = UInt64(Date().storageMilliseconds)
        content.text = text
        return content
    }

    @Test("an own community message nobody waits for stays sent; one someone waits for can be sent again")
    func partialCommunityMessages() async throws {
        let old = try openPhone()
        let account = try TestAccount(storage: old)
        let alice = AccountId(uuid: UUID()), bob = AccountId(uuid: UUID())
        let state = try ownCommunity(old, account, others: [alice, bob])
        let settled = communityText("tamam", in: state), waiting = communityText("yolda", in: state)
        try old.write { tx in
            for (message, last) in [(settled, CommunityRecipientState.skipped), (waiting, .pending)] {
                _ = try tx.communities.store(message, sender: account.accountId, outgoing: true)
                let id = try #require(AccountId(bytes: message.messageID))
                try tx.communities.setRecipients([alice: .accepted, bob: last], community: state.id, messageId: id)
                try tx.communities.setStatus(.partial, community: state.id, message: id)
            }
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let new = try openPhone()
        try DeviceTransferHistoryImporter(
            storage: new, ownAccount: account.accountId, reader: spool.reader(), applySettings: { _ in }
        ).run()
        _ = try TestAccount(storage: new, accountId: account.accountId)
        try new.read { tx in
            let status = Dictionary(uniqueKeysWithValues: try tx.communities.messages(state.id).map { ($0.text, $0.status) })
            #expect(status == ["tamam": .partial, "yolda": .failed])
            let id = try #require(AccountId(bytes: waiting.messageID))
            let recipients = try tx.communities.recipients(community: state.id, messageId: id)
            #expect(recipients.first { $0.recipient == bob }?.state == .failed)
        }
    }

    @Test("what cannot be read stays behind and the move goes on")
    func unreadableRows() async throws {
        let old = try openPhone()
        let account = try TestAccount(storage: old)
        let conversation = try old.write { tx -> ConversationId in
            try tx.contacts.create(peer, state: .accepted)
            return try tx.conversations.ensure(for: peer).id
        }
        let damagedDescriptor = attachmentMessage(id: 2)
        let state = try ownCommunity(old, account, others: [AccountId(uuid: UUID())])
        let keptInCommunity = communityText("kalır", in: state), damagedInCommunity = communityText("bozuk", in: state)
        try old.write { tx in
            _ = try tx.messages.storeIncoming(text("iyi", id: 1), in: conversation)
            _ = try tx.messages.storeIncoming(damagedDescriptor, in: conversation)
            try tx.conversations.setDraft("taslak", for: conversation)
            _ = try tx.communities.store(keptInCommunity, sender: account.accountId, outgoing: true)
            _ = try tx.communities.store(damagedInCommunity, sender: account.accountId, outgoing: true)
            try tx.connection.run("UPDATE attachments SET descriptor = ?", [Data([0])])
            try tx.connection.run("UPDATE conversations SET draft = ?", [Data([0])])
            try tx.connection.run(
                "UPDATE community_messages SET body = ? WHERE message_id = ?", [Data([0]), damagedInCommunity.messageID])
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let new = try openPhone()
        try DeviceTransferHistoryImporter(
            storage: new, ownAccount: account.accountId, reader: spool.reader(), applySettings: { _ in }
        ).run()
        _ = try TestAccount(storage: new, accountId: account.accountId)
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            #expect(try tx.messages.page(in: moved.id, limit: 10).map { $0.content?.text.body } == ["iyi"])
            #expect(try tx.conversations.draft(for: moved.id) == nil)
            #expect(try tx.communities.messages(state.id).map(\.text) == ["kalır"])
        }
    }

    @Test("a message request arrives with its contact, though no backup holds it")
    func messageRequest() async throws {
        let stranger = AccountId(bytes: Data(repeating: 0x41, count: 16))!
        let old = try openPhone()
        _ = try TestAccount(storage: old)
        let hello = text("selam", id: 9)
        try old.write { tx in
            try tx.contacts.create(stranger, state: .incomingRequest)
            let conversation = try tx.conversations.ensure(for: stranger).id
            _ = try tx.messages.storeIncoming(hello, in: conversation)
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx in
            #expect(try tx.contacts.requestState(of: stranger) == .incomingRequest)
            let moved = try #require(try tx.conversations.forContact(stranger))
            #expect(try tx.messages.count(in: moved.id) == 1)
        }
    }

    @Test("an outgoing request arrives knowing whether it went out: only then an answer counts (§6.4)")
    func outgoingRequest() async throws {
        let old = try openPhone()
        _ = try TestAccount(storage: old)
        let sentTo = AccountId(uuid: UUID()), openedOnly = AccountId(uuid: UUID())
        let sentAt = Date(timeIntervalSince1970: 1_700_000_000)
        try old.write { tx in
            for contact in [sentTo, openedOnly] {
                try tx.contacts.create(contact, state: .outgoingRequest)
                _ = try tx.conversations.ensure(for: contact)
            }
            try tx.contacts.markRequestSent(to: sentTo, now: sentAt)
        }
        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx throws in
            #expect(try tx.contacts.requestState(of: sentTo) == .outgoingRequest)
            #expect(try tx.contacts.requestSentAt(to: sentTo) == sentAt)
            #expect(try tx.contacts.requestState(of: openedOnly) == .outgoingRequest)
            #expect(try !tx.contacts.requestSent(to: openedOnly))
        }
    }

    @Test("everything goes in before the account is saved: a vault without one takes it all")
    func aVaultWithoutAnAccount() async throws {
        let old = try openPhone()
        let account = try TestAccount(storage: old)
        let conversation = try old.write { tx -> ConversationId in
            try tx.contacts.create(peer, state: .accepted)
            return try tx.conversations.ensure(for: peer).id
        }
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let member = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: identity.identityKey.serialize())
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: false, owner: owner, name: "",
            members: [owner, member].sorted { $0.accountId.string < $1.accountId.string })
        let signed = SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        var mine = BRPCommunityMessage()
        mine.communityID = state.id.bytes
        mine.revision = 1
        mine.messageID = AccountId(uuid: UUID()).bytes
        mine.sentAt = UInt64(Date().storageMilliseconds)
        mine.text = "benden"
        var theirs = mine
        theirs.messageID = AccountId(uuid: UUID()).bytes
        theirs.text = "ondan"
        let keep = text("giden", id: 1), got = text("gelen", id: 2)
        try old.write { tx in
            _ = try tx.messages.storeOutgoing(keep, in: conversation)
            _ = try tx.messages.storeIncoming(got, in: conversation)
            try tx.communities.save(signed, localName: "topluluk")
            _ = try tx.communities.store(mine, sender: account.accountId, outgoing: true)
            _ = try tx.communities.store(theirs, sender: member.accountId, outgoing: false)
        }

        let spool = try await export(old)
        defer { spool.remove() }
        let new = try openPhone()
        try new.write { tx in try tx.contacts.create(peer, state: .accepted) }
        #expect(try new.read { try $0.account.load() } == nil)
        try DeviceTransferHistoryImporter(
            storage: new, ownAccount: account.accountId, reader: spool.reader(), applySettings: { _ in }
        ).run()

        // The moment the account is saved it is all there, and the messages are its own.
        _ = try TestAccount(storage: new, accountId: account.accountId)
        try new.read { tx in
            let moved = try #require(try tx.conversations.forContact(peer))
            let messages = try tx.messages.page(in: moved.id, limit: 10)
            #expect(messages.map { $0.content?.text.body }.sorted { ($0 ?? "") < ($1 ?? "") } == ["gelen", "giden"])
            let outgoing = try #require(messages.first { $0.content?.text.body == "giden" })
            #expect(outgoing.sender == account.accountId)
            #expect(try tx.communities.messages(state.id).map(\.text).sorted() == ["benden", "ondan"])
            let own = try #require(try tx.communities.messages(state.id).first { $0.text == "benden" })
            #expect(own.sender == account.accountId)
            #expect(own.outgoing)
        }
    }

    @Test("a community arrives with its messages, its file and its place on the roster")
    func community() async throws {
        let old = try openPhone()
        let account = try TestAccount(storage: old)
        let identity = try account.store.identityKeyPair(context: NullContext())
        let owner = CommunityMember(accountId: account.accountId, identityKey: identity.identityKey.serialize())
        let member = CommunityMember(accountId: AccountId(uuid: UUID()), identityKey: identity.identityKey.serialize())
        let state = try CommunityState(
            id: AccountId(uuid: UUID()), revision: 1, isPublic: false, owner: owner, name: "",
            members: [owner, member].sorted { $0.accountId.string < $1.accountId.string })
        let signed = SignedCommunityState(state: state.bytes, signature: identity.privateKey.generateSignature(message: state.bytes))
        try old.write { try $0.communities.save(signed, localName: "topluluk") }
        var text = BRPCommunityMessage()
        text.communityID = state.id.bytes
        text.revision = 1
        text.messageID = AccountId(uuid: UUID()).bytes
        text.sentAt = UInt64(Date().storageMilliseconds)
        text.text = "herkese selam"
        var photo = text
        photo.messageID = AccountId(uuid: UUID()).bytes
        photo.text = "alt yazı"
        var pointer = BRPAttachment()
        pointer.kind = .image
        pointer.mediaID = Data(repeating: 0x55, count: 16)
        pointer.key = Data(repeating: 0x56, count: 64)
        pointer.digest = Data(repeating: 0x57, count: 32)
        pointer.size = 3
        pointer.contentType = "image/jpeg"
        photo.attachment = pointer
        try old.write { tx in
            _ = try tx.communities.store(text, sender: member.accountId, outgoing: false)
            _ = try tx.communities.store(photo, sender: member.accountId, outgoing: false)
            _ = try tx.communities.storeAttachmentFile(
                Data([9, 8, 7]), community: state.id, sender: member.accountId, messageId: AccountId(bytes: photo.messageID)!)
        }

        let spool = try await export(old)
        defer { spool.remove() }
        let (new, own) = try newPhone()
        try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        try new.read { tx in
            let moved = try #require(try tx.communities.get(state.id))
            #expect(moved.name == "topluluk")
            #expect(moved.isActive)
            #expect(moved.state?.revision == 1)
            let messages = try tx.communities.messages(state.id)
            #expect(messages.map(\.text).sorted() == ["alt yazı", "herkese selam"])
            let file = try tx.communities.readAttachmentFile(
                community: state.id, sender: member.accountId, messageId: AccountId(bytes: photo.messageID)!)
            #expect(file == Data([9, 8, 7]))
            let item = try #require(try tx.communities.attachment(state.id, sender: member.accountId, messageId: AccountId(bytes: photo.messageID)!))
            #expect(item.state == .downloaded)
        }
    }

    @Test("a record out of place ends the import")
    func strayFilePart() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let spool = try DeviceTransferSpool(directory: directory)
        defer { spool.remove() }
        var record = BRPTransferRecord()
        record.filePart.data = Data([1])
        try spool.append(try record.serializedBytes())
        try spool.finish()
        let (new, own) = try newPhone()
        #expect(throws: DeviceTransferError.badMessage) {
            try DeviceTransferHistoryImporter(storage: new, ownAccount: own, reader: spool.reader(), applySettings: { _ in }).run()
        }
    }

    @Test("the estimate leaves out what does not move")
    func estimate() async throws {
        let (old, conversation) = try oldPhone()
        try old.write { tx in _ = try tx.messages.storeIncoming(text("kalır", id: 1), in: conversation) }
        let exporter = DeviceTransferHistoryExporter(storage: old, settings: BRPTransferSettings())
        let small = try await exporter.estimatedBytes()
        #expect(small > 0)
        try old.write { tx in
            _ = try tx.messages.storeIncoming(text(String(repeating: "x", count: 50_000), id: 2, timer: 60), in: conversation)
        }
        #expect(try await exporter.estimatedBytes() == small)
    }
}
