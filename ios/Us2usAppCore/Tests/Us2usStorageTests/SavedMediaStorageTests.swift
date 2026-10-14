// us2us — AGPL-3.0-only

import Foundation
import Testing
import Us2usKit

@testable import Us2usStorage

// Saved media (PROTOCOL.md §8.4 SAVED) and the media list of a
// conversation, at the storage level; schema v6.

private func attachmentMessage(
    _ kind: BRPAttachment.Kind, id: Data = RandomId.make(), timer: UInt32 = 0, viewOnce: Bool = false
) -> BRPDataMessage {
    var attachment = sampleAttachment()
    attachment.kind = kind
    return BRPDataMessage(payload: .attachment(attachment), messageId: id, expireTimerSeconds: timer, viewOnce: viewOnce)
}

private func saved(_ ids: [Data]) -> BRPReceiptMessage {
    var receipt = BRPReceiptMessage()
    receipt.type = .saved
    receipt.messageIds = ids
    return receipt
}

@Suite("Saved media and the media list (§8.4, schema v6)")
struct SavedMediaStorageTests {
    @Test("SAVED marks only our own permanent photos, videos and files, once")
    func savedByPeer() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        let (_, other) = try makeContactWithConversation(storage, now: t0)
        let photo = attachmentMessage(.image)
        let video = attachmentMessage(.video)
        let file = attachmentMessage(.file)
        let voice = attachmentMessage(.voice)
        let viewOnce = attachmentMessage(.image, viewOnce: true)
        let disappearing = attachmentMessage(.image, timer: 3600)
        let text = textMessage("metin")
        let theirs = attachmentMessage(.image)
        let elsewhere = attachmentMessage(.image)
        let unknown = RandomId.make()
        try storage.write { tx throws in
            for message in [photo, video, file, voice, viewOnce, disappearing, text] {
                try tx.messages.storeOutgoing(message, in: conversation.id, at: t0)
            }
            try tx.messages.storeIncoming(theirs, in: conversation.id, receivedAt: t0)
            try tx.messages.storeOutgoing(elsewhere, in: other.id, at: t0)
        }
        let ids = [photo, video, file, voice, viewOnce, disappearing, text, theirs, elsewhere].map(\.messageID) + [unknown]
        let first = t0.addingTimeInterval(60)
        let changed = try storage.write { tx in try tx.messages.applyReceipt(saved(ids), in: conversation.id, at: first) }
        #expect(Set(changed) == Set([photo, video, file].map(\.messageID)))

        // A second SAVED keeps the first time.
        let again = try storage.write { tx in
            try tx.messages.applyReceipt(saved([photo.messageID]), in: conversation.id, at: first.addingTimeInterval(60))
        }
        #expect(again.isEmpty)
        try storage.read { tx throws in
            for message in [photo, video, file] {
                let stored = try #require(try tx.messages.get(message.messageID, in: conversation.id, now: first))
                #expect(stored.timestamps.savedByPeerAt == first)
                #expect(stored.timestamps.savedByMeAt == nil)
            }
            for message in [voice, viewOnce, disappearing, text, theirs] {
                let stored = try #require(
                    try tx.messages.get(message.messageID, in: conversation.id, now: first, includeExpired: true))
                #expect(stored.timestamps.savedByPeerAt == nil)
            }
            // Another contact's conversation is not touched by this one's SAVED.
            let foreign = try #require(try tx.messages.get(elsewhere.messageID, in: other.id, now: first))
            #expect(foreign.timestamps.savedByPeerAt == nil)
        }

        // A deleted item takes no SAVED either.
        let late = attachmentMessage(.image)
        try storage.write { tx throws in
            try tx.messages.storeOutgoing(late, in: conversation.id, at: t0)
            try tx.messages.deleteForEveryone(late.messageID, in: conversation.id, at: t0)
        }
        #expect(try storage.write { try $0.messages.applyReceipt(saved([late.messageID]), in: conversation.id, at: first) }.isEmpty)
    }

    @Test("saving here: once, and only what can be saved")
    func savedByMe() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        let photo = attachmentMessage(.image)
        let mine = attachmentMessage(.file)
        let viewOnce = attachmentMessage(.video, viewOnce: true)
        let disappearing = attachmentMessage(.image, timer: 60)
        let voice = attachmentMessage(.voice)
        let text = textMessage("metin")
        try storage.write { tx throws in
            for message in [photo, viewOnce, disappearing, voice, text] {
                try tx.messages.storeIncoming(message, in: conversation.id, receivedAt: t0)
            }
            try tx.messages.storeOutgoing(mine, in: conversation.id, at: t0)
        }
        let first = t0.addingTimeInterval(5)
        #expect(try storage.write { try $0.messages.markSavedByMe(photo.messageID, in: conversation.id, at: first) })
        #expect(try !storage.write { try $0.messages.markSavedByMe(photo.messageID, in: conversation.id, at: t0.addingTimeInterval(9)) })
        #expect(try storage.write { try $0.messages.markSavedByMe(mine.messageID, in: conversation.id, at: first) })
        for message in [viewOnce, disappearing, voice, text] {
            #expect(throws: StorageError.self) {
                _ = try storage.write { try $0.messages.markSavedByMe(message.messageID, in: conversation.id, at: first) }
            }
        }
        #expect(throws: StorageError.self) {
            _ = try storage.write { try $0.messages.markSavedByMe(RandomId.make(), in: conversation.id, at: first) }
        }
        try storage.read { tx throws in
            #expect(try tx.messages.get(photo.messageID, in: conversation.id, now: first)?.timestamps.savedByMeAt == first)
            #expect(try tx.messages.get(mine.messageID, in: conversation.id, now: first)?.timestamps.savedByMeAt == first)
            #expect(try tx.messages.get(voice.messageID, in: conversation.id, now: first)?.timestamps.savedByMeAt == nil)
        }
    }

    @Test("the media list: photos and videos or files, newest first, in pages")
    func mediaPages() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        let oldest = attachmentMessage(.image)
        let file = attachmentMessage(.file)
        let video = attachmentMessage(.video)
        // More than one read batch of other rows between two photos.
        let voices = (0..<150).map { _ in attachmentMessage(.voice) }
        let viewOnce = attachmentMessage(.image, viewOnce: true)
        let unstarted = attachmentMessage(.image, timer: 60)  // incoming, never displayed
        let started = attachmentMessage(.video, timer: 60)  // ours: its timer runs from now (never listed)
        let deleted = attachmentMessage(.image)
        let newest = attachmentMessage(.image)
        try storage.write { tx throws in
            try tx.messages.storeIncoming(oldest, in: conversation.id, receivedAt: t0)
            try tx.messages.storeOutgoing(file, in: conversation.id, at: t0)
            try tx.messages.storeIncoming(textMessage("arada"), in: conversation.id, receivedAt: t0)
            try tx.messages.storeIncoming(video, in: conversation.id, receivedAt: t0)
            for voice in voices { try tx.messages.storeIncoming(voice, in: conversation.id, receivedAt: t0) }
            try tx.messages.storeIncoming(viewOnce, in: conversation.id, receivedAt: t0)
            try tx.messages.storeIncoming(unstarted, in: conversation.id, receivedAt: t0)
            try tx.messages.storeOutgoing(started, in: conversation.id, at: t0)
            try tx.messages.storeOutgoing(deleted, in: conversation.id, at: t0)
            try tx.messages.deleteForEveryone(deleted.messageID, in: conversation.id, at: t0)
            try tx.messages.storeIncoming(newest, in: conversation.id, receivedAt: t0)
        }
        let visual: Set<BRPAttachment.Kind> = [.image, .video]
        let now = t0.addingTimeInterval(1)
        let all = try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: visual, limit: 50, now: now) }
        #expect(all.map(\.messageId) == [newest, video, oldest].map(\.messageID))

        // Paging by the last item's row id.
        let first = try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: visual, limit: 2, now: now) }
        #expect(first.map(\.messageId) == [newest, video].map(\.messageID))
        let second = try storage.read {
            try $0.messages.mediaPage(in: conversation.id, kinds: visual, before: first.last?.localId, limit: 2, now: now)
        }
        #expect(second.map(\.messageId) == [oldest].map(\.messageID))
        let third = try storage.read {
            try $0.messages.mediaPage(in: conversation.id, kinds: visual, before: second.last?.localId, limit: 2, now: now)
        }
        #expect(third.isEmpty)
        let localId = try storage.read { try $0.messages.localId(of: video.messageID, in: conversation.id) }
        #expect(localId == first.last?.localId)

        let documents = try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: [.file], now: now) }
        #expect(documents.map(\.messageId) == [file.messageID])

        // A disappearing item is never listed (§8.1), also once its timer runs, nor after it expired.
        _ = try storage.write { tx throws in try tx.messages.markDisplayed([unstarted.messageID], in: conversation.id, at: now) }
        let shown = try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: visual, now: now) }
        #expect(shown.map(\.messageId) == [newest, video, oldest].map(\.messageID))
        let later = t0.addingTimeInterval(120)
        let expired = try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: visual, now: later) }
        #expect(expired.map(\.messageId) == [newest, video, oldest].map(\.messageID))
        #expect(try storage.read { try $0.messages.mediaPage(in: conversation.id, kinds: visual, limit: 0, now: now) }.isEmpty)
    }

    @Test("v6 adds the saved columns to an existing database and keeps every message")
    func migration() throws {
        let environment = try TestEnvironment()
        let photo = attachmentMessage(.image)
        let conversationId: ConversationId
        do {
            let storage = try environment.open(now: t0)
            _ = try TestAccount(storage: storage)
            let (_, conversation) = try makeContactWithConversation(storage, now: t0)
            conversationId = conversation.id
            try storage.write { tx throws in
                try tx.messages.storeIncoming(photo, in: conversation.id, receivedAt: t0)
                try tx.messages.storeIncoming(textMessage("eski mesaj"), in: conversation.id, receivedAt: t0)
            }
            // Back to what a v5 database looks like.
            try storage.database.write { connection in
                try connection.execute("DROP INDEX messages_attachments")
                try connection.execute("ALTER TABLE messages DROP COLUMN saved_by_peer_at")
                try connection.execute("ALTER TABLE messages DROP COLUMN saved_by_me_at")
                try connection.execute("PRAGMA user_version = 5")
            }
            storage.close()
        }
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        let version = try storage.database.locked { try $0.scalar("PRAGMA user_version") }
        #expect(version == .integer(6))
        let messages = try storage.read { try $0.messages.page(in: conversationId, now: t0) }
        #expect(messages.count == 2)
        #expect(messages.allSatisfy { $0.timestamps.savedByPeerAt == nil && $0.timestamps.savedByMeAt == nil })
        #expect(try storage.write { try $0.messages.markSavedByMe(photo.messageID, in: conversationId, at: t0) })
        let listed = try storage.read { try $0.messages.mediaPage(in: conversationId, kinds: [.image], now: t0) }
        #expect(listed.map(\.messageId) == [photo.messageID])
        #expect(listed.first?.timestamps.savedByMeAt == t0)
        let index = try storage.database.locked {
            try $0.queryOne("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'messages_attachments'")
        }
        #expect(index != nil)
    }
}
