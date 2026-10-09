// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

private struct Env {
    let environment: TestEnvironment
    let storage: LocalStorage
    let me: AccountId

    init(now: Date = t0) throws {
        environment = try TestEnvironment()
        storage = try environment.open(now: now)
        me = try TestAccount(storage: storage).accountId
    }
}

@Suite("Contacts and message requests (§6.4, §8.7)")
struct ContactTests {
    @Test("sending first opens an outgoing request; only ACCEPT ends it")
    func outgoingRequest() throws {
        let env = try Env()
        let bob = AccountId(uuid: UUID())
        try env.storage.write { tx throws in
            #expect(try tx.contacts.prepareToSend(to: bob, now: t0) == .outgoingRequest)
            #expect(try tx.contacts.noteVerifiedMessage(.text("hi"), from: bob) == .outgoingRequest)
            #expect(try tx.contacts.noteVerifiedMessage(.accessKeyUpdate(Data(repeating: 1, count: 16)), from: bob)
                == .outgoingRequest)
            #expect(try tx.contacts.noteVerifiedMessage(.messageRequestResponse(.accept), from: bob) == .accepted)
        }
    }

    @Test("a first message opens an incoming request; accepting needs the relay's 202")
    func incomingRequest() throws {
        let env = try Env()
        let carol = AccountId(uuid: UUID())
        try env.storage.write { tx throws in
            #expect(try tx.contacts.noteVerifiedMessage(.text("merhaba"), from: carol) == .incomingRequest)
            #expect(try tx.contacts.noteVerifiedMessage(.messageRequestResponse(.accept), from: carol)
                == .incomingRequest)
            try tx.contacts.acceptIncomingRequest(from: carol)
            #expect(try tx.contacts.requestState(of: carol) == .accepted)
            #expect(throws: StorageError.self) { try tx.contacts.acceptIncomingRequest(from: AccountId(uuid: UUID())) }
        }
    }

    @Test("block from any state, unblock to the previous one; blocked accounts cannot be sent to")
    func blocking() throws {
        let env = try Env()
        let dave = AccountId(uuid: UUID())
        try env.storage.write { tx throws in
            try tx.contacts.create(dave, state: .accepted)
            try tx.contacts.block(dave)
            #expect(try tx.contacts.get(dave)?.isBlocked == true)
            #expect(throws: StorageError.invalidState("blocked")) { try tx.contacts.prepareToSend(to: dave) }
            #expect(try tx.contacts.noteVerifiedMessage(.text("x"), from: dave) == .blocked)
            try tx.contacts.unblock(dave)
            #expect(try tx.contacts.requestState(of: dave) == .accepted)
            let stranger = AccountId(uuid: UUID())
            try tx.contacts.block(stranger)
            #expect(try tx.contacts.requestState(of: stranger) == .blocked)
        }
    }

    @Test("access keys both ways, rotation bookkeeping")
    func accessKeys() throws {
        let env = try Env()
        let erin = AccountId(uuid: UUID())
        let ours = Data(repeating: 8, count: 16)
        try env.storage.write { tx throws in
            try tx.contacts.create(erin, state: .accepted)
            try tx.contacts.setTheirAccessKey(Data(repeating: 2, count: 16), for: erin)
            #expect(throws: StorageError.self) { try tx.contacts.setTheirAccessKey(Data(count: 3), for: erin) }
            #expect(try tx.contacts.needingAccessKey(current: ours).map(\.accountId) == [erin])
            try tx.contacts.setOurAccessKeySent(ours, to: erin)
            #expect(try tx.contacts.needingAccessKey(current: ours).isEmpty)
            let contact = try #require(try tx.contacts.get(erin))
            #expect(contact.theirAccessKey == Data(repeating: 2, count: 16))
            #expect(contact.ourAccessKeySent == ours)
        }
    }

    @Test("complete profile updates replace the stored profile; older ones are ignored; username is a claim")
    func profiles() throws {
        let env = try Env()
        let fay = AccountId(uuid: UUID())
        var update = BRPProfileUpdate()
        update.displayName = "Fay"
        update.username = "fay_99"
        update.presenceSharing = true
        update.updatedAt = 200
        try env.storage.write { tx throws in
            try tx.contacts.create(fay, state: .accepted)
            #expect(try tx.contacts.get(fay)?.presenceSharing == false)  // false until the first update (§8.6)
            let profile = try tx.contacts.applyProfileUpdate(update, from: fay)
            #expect(profile.displayName == "Fay")
            #expect(profile.usernameStatus == .unchecked)
            var older = update
            older.displayName = "Old"
            older.updatedAt = 100
            #expect(try tx.contacts.applyProfileUpdate(older, from: fay).displayName == "Fay")
            try tx.contacts.recordUsernameCheck(try NormalizedUsername("fay_99"), owner: fay, for: fay)
            let contact = try #require(try tx.contacts.get(fay))
            #expect(contact.profile.usernameStatus == .verified)
            #expect(contact.presenceSharing)
        }
    }

    @Test("identity fields come from the protocol store")
    func identityFields() throws {
        let env = try Env()
        let store = try #require(env.storage.protocolStore)
        let gus = AccountId(uuid: UUID())
        let key = IdentityKeyPair.generate().identityKey
        try env.storage.write { tx throws in try tx.contacts.create(gus, state: .accepted) }
        _ = try store.saveIdentity(key, for: gus.protocolAddress, context: NullContext())
        try store.setIdentityVerified(true, for: gus)
        var contact = try #require(try env.storage.read { try $0.contacts.get(gus) })
        #expect(contact.identityKey == key)
        #expect(contact.isVerified)
        _ = try store.saveIdentity(IdentityKeyPair.generate().identityKey, for: gus.protocolAddress, context: NullContext())
        contact = try #require(try env.storage.read { try $0.contacts.get(gus) })
        #expect(contact.identityChanged)
        #expect(!contact.isVerified)
    }
}

@Suite("Messages (§8)")
struct MessageTests {
    @Test("every DataMessage payload round-trips through the encrypted body")
    func allContentTypes() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        var location = BRPLocation()
        location.latitude = 41.0082
        location.longitude = 28.9784
        location.name = "İstanbul"
        var sticker = BRPSticker()
        sticker.packID = "cb.basic"
        sticker.stickerID = 7
        sticker.emoji = "😀"
        var customSticker = BRPSticker()
        customSticker.custom = sampleAttachment()
        var live = BRPLiveLocation()
        live.sessionID = RandomId.make()
        live.latitude = 1
        live.sequence = 1
        let payloads: [BRPDataMessage.OneOf_Payload] = [
            .text({ var t = BRPText(); t.body = "Gizli mesaj ✓"; return t }()),
            .attachment(sampleAttachment(caption: "foto")), .location(location), .sticker(sticker),
            .sticker(customSticker), .liveLocation(live),
        ]
        // The reply quotes a message that is here and permanent, so it keeps
        // its excerpt (a reply to a missing, expiring, view-once or deleted
        // target loses it: §9, privacy review 2026-10-08).
        let target = BRPDataMessage(
            payload: .text({ var t = BRPText(); t.body = "alıntı"; return t }()), sentAt: t0, viewOnce: false)
        try env.storage.write { tx throws in
            _ = try tx.messages.storeIncoming(target, in: conversation.id, receivedAt: t0)
        }
        var quote = BRPQuote()
        let messages = payloads.map { payload -> BRPDataMessage in
            var message = BRPDataMessage(payload: payload, sentAt: t0, viewOnce: false)
            if case .text = payload {
                quote.targetMessageID = target.messageID
                quote.previewText = "alıntı"
                message.quote = quote
            }
            return message
        }
        try env.storage.write { tx throws in
            for message in messages {
                guard case .stored = try tx.messages.storeIncoming(message, in: conversation.id, receivedAt: t0) else {
                    Issue.record("not stored")
                    return
                }
            }
        }
        let page = Array(try env.storage.read { try $0.messages.page(in: conversation.id, now: t0) }.dropFirst())
        #expect(page.map(\.content) == messages.map { Optional($0) })
        #expect(page.map(\.kind) == [.text, .attachment, .location, .sticker, .sticker, .liveLocation])
        #expect(page[0].quoteTarget == quote.targetMessageID)
        #expect(page.allSatisfy { $0.status == .received && !$0.isOutgoing })
        let attachments = try env.storage.read { tx throws in
            try page.flatMap { try tx.attachments.forMessage($0.messageId, in: conversation.id) }
        }
        #expect(attachments.count == 2)
        #expect(attachments.allSatisfy { $0.state == .pendingDownload && $0.descriptor != nil })
    }

    @Test("dedupe by message id; pages are chronological and paginate")
    func dedupeAndPaging() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        let messages = (0..<10).map { textMessage("m\($0)") }
        try env.storage.write { tx throws in
            for message in messages { try tx.messages.storeIncoming(message, in: conversation.id) }
            #expect(try tx.messages.storeIncoming(messages[3], in: conversation.id) == .duplicate)
        }
        try env.storage.read { tx throws in
            let last = try tx.messages.page(in: conversation.id, limit: 4)
            #expect(last.map(\.messageId) == messages[6...].map(\.messageID))
            let before = try tx.messages.page(in: conversation.id, before: last.first?.localId, limit: 4)
            #expect(before.map(\.messageId) == messages[2..<6].map(\.messageID))
            #expect(try tx.conversations.unreadCount(conversation.id) == 10)
            #expect(try tx.messages.latest(in: conversation.id)?.messageId == messages[9].messageID)
        }
    }

    @Test("status: Sending → Sent → Delivered → Read, never backwards; Not delivered after 14 days")
    func statuses() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        let a = textMessage("a")
        let b = textMessage("b")
        try env.storage.write { tx throws in
            try tx.messages.storeOutgoing(a, in: conversation.id, at: t0)
            try tx.messages.storeOutgoing(b, in: conversation.id, at: t0)
            #expect(try tx.messages.get(a.messageID, in: conversation.id, now: t0)?.status == .sending)
            try tx.messages.markAccepted(a.messageID, in: conversation.id, at: t0 + 1)
            try tx.messages.markAccepted(b.messageID, in: conversation.id, at: t0 + 1)
            var read = BRPReceiptMessage()
            read.type = .read
            read.messageIds = [a.messageID]
            #expect(try tx.messages.applyReceipt(read, in: conversation.id, at: t0 + 3) == [a.messageID])
            var delivery = BRPReceiptMessage()
            delivery.type = .delivery
            delivery.messageIds = [a.messageID]
            #expect(try tx.messages.applyReceipt(delivery, in: conversation.id, at: t0 + 4).isEmpty)
            let message = try #require(try tx.messages.get(a.messageID, in: conversation.id, now: t0))
            #expect(message.status == .read)
            #expect(message.timestamps.readAt == t0 + 3)
            #expect(message.timestamps.deliveredAt == t0 + 3)
            #expect(message.timestamps.acceptedAt == t0 + 1)

            #expect(try tx.messages.markUndelivered(now: t0 + 14 * 24 * 3600) == 0)  // accepted at t0 + 1
            #expect(try tx.messages.markUndelivered(now: t0 + 14 * 24 * 3600 + 1) == 1)
            #expect(try tx.messages.get(b.messageID, in: conversation.id, now: t0)?.status == .notDelivered)
            // A late delivery receipt still upgrades it.
            delivery.messageIds = [b.messageID]
            #expect(try tx.messages.applyReceipt(delivery, in: conversation.id) == [b.messageID])
            #expect(try tx.messages.get(b.messageID, in: conversation.id, now: t0)?.status == .delivered)
        }
    }

    @Test("reactions: one per reactor, last writer wins, removable, encrypted")
    func reactions() throws {
        let env = try Env()
        let (contact, conversation) = try makeContactWithConversation(env.storage)
        let target = textMessage("güzel")
        func reaction(_ emoji: String, remove: Bool = false, sentAt: Date) -> BRPDataMessage {
            var reaction = BRPReaction()
            reaction.targetMessageID = target.messageID
            reaction.emoji = emoji
            reaction.remove = remove
            return BRPDataMessage(payload: .reaction(reaction), sentAt: sentAt)
        }
        try env.storage.write { tx throws in
            try tx.messages.storeOutgoing(target, in: conversation.id)
            try tx.messages.storeIncoming(reaction("❤️", sentAt: t0 + 10), in: conversation.id)
            try tx.messages.storeIncoming(reaction("😂", sentAt: t0 + 5), in: conversation.id)  // older: ignored
            try tx.messages.storeOutgoing(reaction("👍", sentAt: t0 + 6), in: conversation.id)
        }
        var reactions = try env.storage.read { try $0.messages.reactions(on: [target.messageID], in: conversation.id) }
        #expect(Set(reactions[target.messageID]?.map(\.emoji) ?? []) == ["❤️", "👍"])
        #expect(reactions[target.messageID]?.first { $0.reactor == contact }?.emoji == "❤️")
        let raw = env.environment.databaseBytes()
        #expect(!raw.contains(Data("❤️".utf8)))
        _ = try env.storage.write { tx throws in
            try tx.messages.storeIncoming(reaction("❤️", remove: true, sentAt: t0 + 20), in: conversation.id)
        }
        reactions = try env.storage.read { try $0.messages.reactions(on: [target.messageID], in: conversation.id) }
        #expect(reactions[target.messageID]?.map(\.emoji) == ["👍"])
    }

    @Test("delete for everyone: only by the original sender, also when it arrives first")
    func deleteForEveryone() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        let theirs = textMessage("sil beni")
        let mine = textMessage("benim")
        let early = textMessage("sonra gelen")
        func delete(_ target: BRPDataMessage) -> BRPDataMessage {
            var delete = BRPDelete()
            delete.targetMessageID = target.messageID
            return BRPDataMessage(payload: .delete(delete))
        }
        try env.storage.write { tx throws in
            try tx.messages.storeIncoming(theirs, in: conversation.id)
            try tx.messages.storeOutgoing(mine, in: conversation.id)
            #expect(try tx.messages.storeIncoming(delete(mine), in: conversation.id) == .ignored)
            #expect(try tx.messages.storeIncoming(delete(theirs), in: conversation.id) == .deleted(target: theirs.messageID))
            // The delete overtakes its message.
            #expect(try tx.messages.storeIncoming(delete(early), in: conversation.id) == .ignored)
            try tx.messages.storeIncoming(early, in: conversation.id)
            try tx.messages.deleteForEveryone(mine.messageID, in: conversation.id)
            #expect(throws: StorageError.self) { try tx.messages.deleteForEveryone(theirs.messageID, in: conversation.id) }
        }
        try env.storage.read { tx throws in
            for id in [theirs.messageID, early.messageID, mine.messageID] {
                let message = try #require(try tx.messages.get(id, in: conversation.id))
                #expect(message.isDeleted)
                #expect(message.content == nil)
            }
        }
        #expect(!env.environment.databaseBytes().contains(Data("sil beni".utf8)))
    }

    @Test("view-once: opening destroys the attachment file and the body")
    func viewOnce() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        var data = BRPDataMessage(payload: .attachment(sampleAttachment()), viewOnce: true)
        data.messageID = RandomId.make()
        let attachmentId = try env.storage.write { tx -> Data in
            try tx.messages.storeIncoming(data, in: conversation.id)
            let id = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first?.id)
            try tx.attachments.storeFile(Data("once".utf8), for: id)
            return id
        }
        try env.storage.write { tx throws in try tx.messages.consumeViewOnce(data.messageID, in: conversation.id, at: t0) }
        let message = try #require(try env.storage.read { try $0.messages.get(data.messageID, in: conversation.id) })
        #expect(message.content == nil)
        #expect(message.timestamps.viewedAt == t0)
        #expect(!env.storage.files.exists(attachmentId))
        #expect(try env.storage.read { try $0.attachments.get(attachmentId) } == nil)
    }

    @Test("live location updates one bubble in place; stale or stopped sessions are not changed")
    func liveLocation() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        var live = BRPLiveLocation()
        live.sessionID = RandomId.make()
        live.sequence = 1
        live.latitude = 10
        let start = BRPDataMessage(payload: .liveLocation(live))
        try env.storage.write { tx throws in
            try tx.messages.storeIncoming(start, in: conversation.id)
            live.sequence = 3
            live.latitude = 30
            guard case .liveLocationUpdated(let updated) = try tx.messages.storeIncoming(
                BRPDataMessage(payload: .liveLocation(live)), in: conversation.id)
            else {
                Issue.record("no update")
                return
            }
            #expect(updated.messageId == start.messageID)
            live.sequence = 2
            #expect(try tx.messages.storeIncoming(BRPDataMessage(payload: .liveLocation(live)), in: conversation.id)
                == .ignored)
            live.sequence = 4
            live.stopped = true
            _ = try tx.messages.storeIncoming(BRPDataMessage(payload: .liveLocation(live)), in: conversation.id)
            live.sequence = 5
            live.stopped = false
            #expect(try tx.messages.storeIncoming(BRPDataMessage(payload: .liveLocation(live)), in: conversation.id)
                == .ignored)
            #expect(try tx.messages.count(in: conversation.id) == 1)
        }
        let bubble = try #require(try env.storage.read { try $0.messages.get(start.messageID, in: conversation.id) })
        guard case .liveLocation(let stored)? = bubble.content?.payload else {
            Issue.record("payload")
            return
        }
        #expect(stored.stopped)
        #expect(stored.sequence == 4)
    }
}

@Suite("Attachments, outbox, envelope log, account")
struct PlumbingTests {
    @Test("attachment upload and download state, files, thumbnails, staging import")
    func attachments() throws {
        let env = try Env()
        let (_, conversation) = try makeContactWithConversation(env.storage)
        let messageId = RandomId.make()
        let staging = env.storage.makeStagingURL()
        try Data("downloaded plaintext".utf8).write(to: staging)
        let attachment = try env.storage.write { tx -> StoredAttachment in
            let created = try tx.attachments.create(
                for: messageId, in: conversation.id, descriptor: nil, state: .pendingUpload)
            try tx.attachments.storeFile(Data("to upload".utf8), for: created.id)
            try tx.attachments.setState(.uploading, of: created.id, transferredBytes: 4, countsAsAttempt: true)
            try tx.attachments.setDescriptor(sampleAttachment(size: 9), of: created.id)
            try tx.attachments.storeThumbnail(Data("thumb".utf8), for: created.id)
            let incoming = try tx.attachments.create(
                for: RandomId.make(), in: conversation.id, descriptor: sampleAttachment(), state: .pendingDownload)
            #expect(try tx.attachments.inStates([.pendingDownload]).map(\.id) == [incoming.id])
            try tx.attachments.importFile(at: staging, for: incoming.id)
            #expect(try tx.attachments.get(incoming.id)?.state == .downloaded)
            return try #require(try tx.attachments.get(created.id))
        }
        #expect(!FileManager.default.fileExists(atPath: staging.path))
        #expect(attachment.state == .uploaded)
        #expect(attachment.attempts == 1)
        #expect(attachment.descriptor?.size == 9)
        try env.storage.read { tx throws in
            #expect(try tx.attachments.readFile(attachment.id) == Data("to upload".utf8))
            #expect(try tx.attachments.readThumbnail(attachment.id) == Data("thumb".utf8))
            let exported = try tx.attachments.exportToStaging(attachment.id)
            #expect(try Data(contentsOf: exported) == Data("to upload".utf8))
        }
    }

    @Test("outgoing attachment: bubble first, file kept encrypted, upload, then the final content is sent")
    func outgoingUploadFlow() throws {
        let env = try Env()
        let (contact, conversation) = try makeContactWithConversation(env.storage)
        var draft = BRPAttachment()
        draft.kind = .image
        draft.contentType = "image/jpeg"
        draft.size = 5
        var data = BRPDataMessage(payload: .attachment(draft))
        data.messageID = RandomId.make()
        let (attachmentId, outboxId) = try env.storage.write { tx throws -> (Data, Int64) in
            try tx.messages.storeOutgoing(data, in: conversation.id)
            let attachment = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first)
            #expect(attachment.state == .pendingUpload)
            try tx.attachments.storeFile(Data("photo".utf8), for: attachment.id)
            let outboxId = try tx.outbox.enqueue(
                BRPContent(data: data), to: contact, conversation: conversation.id, messageId: data.messageID,
                waitingForUpload: true)
            return (attachment.id, outboxId)
        }
        #expect(try env.storage.read { try $0.outbox.due() }.isEmpty)
        let plaintextURL = try env.storage.read { try $0.attachments.exportToStaging(attachmentId) }
        #expect(try Data(contentsOf: plaintextURL) == Data("photo".utf8))
        try? FileManager.default.removeItem(at: plaintextURL)

        // Messenger.uploadAttachment returned the pointer and keys.
        var uploaded = sampleAttachment(size: 5)
        uploaded.contentType = "image/jpeg"
        data.payload = .attachment(uploaded)
        try env.storage.write { tx throws in
            try tx.messages.updateOutgoingContent(data, in: conversation.id)
            try tx.outbox.markReady(outboxId, content: BRPContent(data: data))
            #expect(try tx.attachments.forMessage(data.messageID, in: conversation.id).count == 1)
            let attachment = try #require(try tx.attachments.get(attachmentId))
            #expect(attachment.state == .uploaded)
            #expect(attachment.hasFile)
            #expect(attachment.descriptor?.mediaID == uploaded.mediaID)
            #expect(try tx.outbox.due().first?.content == BRPContent(data: data))
            #expect(try tx.messages.get(data.messageID, in: conversation.id)?.content == data)
        }
    }

    @Test("outbox: due order, exponential backoff, permanent failure, retry, Sent on success")
    func outbox() throws {
        let env = try Env()
        let (contact, conversation) = try makeContactWithConversation(env.storage)
        let message = textMessage("gönder")
        let policy = RetryPolicy(base: 2, maximum: 60, jitter: 0, maximumAge: 3600)
        let (first, second) = try env.storage.write { tx -> (Int64, Int64) in
            try tx.messages.storeOutgoing(message, in: conversation.id, at: t0)
            let first = try tx.outbox.enqueue(
                BRPContent(data: message), to: contact, conversation: conversation.id, messageId: message.messageID,
                now: t0)
            let second = try tx.outbox.enqueue(.typingStartedContent, to: contact, ephemeral: true, now: t0)
            return (first, second)
        }
        try env.storage.write { tx throws in
            #expect(try tx.outbox.due(now: t0).map(\.id) == [first, second])
            #expect(try tx.outbox.get(first)?.content == BRPContent(data: message))
            try tx.outbox.markFailed(first, error: "network", retryable: true, policy: policy, now: t0)
            #expect(try tx.outbox.get(first)?.nextAttemptAt == t0 + 2)
            try tx.outbox.markFailed(first, error: "network", retryable: true, policy: policy, now: t0 + 2)
            #expect(try tx.outbox.get(first)?.nextAttemptAt == t0 + 6)
            #expect(try tx.outbox.due(now: t0 + 3).map(\.id) == [second])
            try tx.outbox.markFailed(second, error: "403", retryable: false, policy: policy, now: t0)
            #expect(try tx.outbox.get(second)?.state == .failed)
            try tx.outbox.markSent(first, now: t0 + 7)
            #expect(try tx.outbox.get(first) == nil)
            #expect(try tx.messages.get(message.messageID, in: conversation.id, now: t0)?.status == .sent)
            // Too old: given up, and the message is Failed.
            let late = textMessage("late")
            try tx.messages.storeOutgoing(late, in: conversation.id, at: t0)
            let lateId = try tx.outbox.enqueue(
                BRPContent(data: late), to: contact, conversation: conversation.id, messageId: late.messageID, now: t0)
            try tx.outbox.markFailed(lateId, error: "network", retryable: true, policy: policy, now: t0 + 3600)
            #expect(try tx.outbox.get(lateId)?.state == .failed)
            #expect(try tx.messages.get(late.messageID, in: conversation.id, now: t0)?.status == .failed)
            try tx.outbox.retry(lateId, now: t0 + 4000)
            #expect(try tx.outbox.get(lateId)?.state == .ready)
            #expect(try tx.messages.get(late.messageID, in: conversation.id, now: t0)?.status == .sending)
        }
        #expect(RetryPolicy(base: 2, maximum: 900, jitter: 0).delay(afterAttempts: 20) == 900)
        let jittered = RetryPolicy(base: 10, maximum: 900, jitter: 0.2)
        #expect((8...12).contains(jittered.delay(afterAttempts: 1)))
    }

    @Test("envelope log survives restarts and is pruned after 15 days")
    func envelopeLog() throws {
        let env = try Env()
        try env.storage.write { tx throws in
            try tx.envelopes.record("relay-1", at: t0)
            try tx.envelopes.record("relay-1", at: t0)
        }
        env.storage.close()
        let reopened = try env.environment.open(now: t0)
        #expect(try reopened.read { try $0.envelopes.contains("relay-1") })
        try reopened.write { tx throws in #expect(try tx.envelopes.prune(now: t0 + 15 * 24 * 3600 + 1) == 1) }
        #expect(try !reopened.read { try $0.envelopes.contains("relay-1") })
    }

    @Test("the local account is saved through the AccountSaver and survives restarts")
    func account() throws {
        let env = try Env()
        var account = try #require(try env.storage.read { try $0.account.load() })
        account.pendingSetup = [.replacePreKeys]
        try env.storage.accountSaver(account)
        env.storage.close()
        let reopened = try env.environment.open()
        #expect(try reopened.read { try $0.account.load() } == account)
        #expect(throws: StorageError.self) {
            try reopened.accountSaver(
                LocalAccount(
                    accountId: AccountId(uuid: UUID()), authToken: Data(count: 32),
                    username: try NormalizedUsername("other_1"), accessKey: Data(count: 16)))
        }
    }
}

extension BRPContent {
    static var typingStartedContent: BRPContent { BRPContent(typingStarted: true) }
}
