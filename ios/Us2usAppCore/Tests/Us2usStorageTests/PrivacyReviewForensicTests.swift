// us2us — AGPL-3.0-only
//
// Privacy review (forensics): what a copy of the storage container still
// holds after content was deleted, for an examiner who has the DEK.
//
// Why the DEK is a realistic assumption: PROTOCOL.md §9 says that whoever can
// read the device secret and the vault file out of the phone can try every
// six-digit code offline within minutes. For a whole conversation the
// crypto-shred (key slot deleted) still protects the content then; for single
// messages (delete for everyone, view-once, delete for me) only secure_delete
// plus a WAL checkpoint does, because they share the conversation key.
//
// These tests are expected to FAIL until every single-message deletion path
// runs `PRAGMA wal_checkpoint(TRUNCATE)` (as `performDueWipes` already does
// for expired disappearing messages).

import Foundation
import Testing
import Us2usKit

@testable import Us2usStorage

@Suite("Privacy review: deleted messages stay recoverable from the WAL")
struct PrivacyReviewForensicTests {
    enum Deletion: String, CaseIterable, Sendable {
        /// The contact deleted their message for everyone (§8.5).
        case remoteDeleteForEveryone
        /// A received view-once item was opened and closed.
        case viewOnceConsumed
        /// The user deleted a message on this phone ("delete for me").
        case deleteForMe
        /// A live-location bubble is "updated in place" (§8.3); earlier
        /// positions should not survive the update.
        case liveLocationUpdatedInPlace
    }

    /// Size of the WAL right now (a commit boundary: nothing else is writing).
    static func walSize(_ environment: TestEnvironment) throws -> Int {
        let attributes = try FileManager.default.attributesOfItem(atPath: environment.layout.databaseFiles[1].path)
        return (attributes[.size] as? NSNumber)?.intValue ?? 0
    }

    /// What an examiner does with a copy of `db/` taken before the app locked
    /// (a crash, a jetsam kill in the grace period, or an acquisition while
    /// the app runs): roll the WAL back to an earlier commit — its old frames
    /// are still in the file — and decrypt with keys derived from the DEK.
    static func recover(
        _ messageId: Data, environment: TestEnvironment, walPrefix: Int, slotId: Data, scratch: URL
    ) throws -> BRPDataMessage? {
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        let copy = scratch.appendingPathComponent("main.sqlite")
        try FileManager.default.copyItem(at: environment.layout.databaseURL, to: copy)
        let wal = try Data(contentsOf: environment.layout.databaseFiles[1])
        try Data(wal.prefix(walPrefix)).write(to: scratch.appendingPathComponent("main.sqlite-wal"))

        let connection = try SQLiteConnection(path: copy.path)
        defer { connection.close() }
        try connection.key(rawKey: LocalKeyDerivation.databaseKey(dek: environment.dek))
        guard
            let body = try connection.queryOne(
                "SELECT body FROM messages WHERE message_id = ?", [messageId])?.blob("body")
        else { return nil }
        // The conversation still exists, so its key slot opens with the DEK.
        let slotFile = try Data(
            contentsOf: environment.layout.slotDirectory.appendingPathComponent(slotId.hexString + ".slot"))
        let conversationKey = try KeySlot.open(slotFile, slotId: slotId, dek: environment.dek)
        let plaintext = try ItemCipher.open(body, itemId: messageId, conversationKey: conversationKey)
        return try BRPDataMessage(serializedBytes: plaintext)
    }

    @Test("a deleted message cannot be read back from the database files with the DEK", arguments: Deletion.allCases)
    func deletedContentIsGoneFromTheWAL(_ deletion: Deletion) throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        let account = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage, now: t0)

        var secret = "FORENSIC-\(deletion.rawValue)-\(UUID().uuidString)"
        let messageId = RandomId.make()
        let liveSession = RandomId.make()
        var data: BRPDataMessage
        switch deletion {
        case .liveLocationUpdatedInPlace:
            var live = BRPLiveLocation()
            live.sessionID = liveSession
            live.latitude = 41.012345
            live.longitude = 28.976543
            live.expiresAt = UInt64(t0.timeIntervalSince1970 * 1000) + 3_600_000
            live.sequence = 0
            secret = "\(live.latitude)"
            data = BRPDataMessage(payload: .liveLocation(live), messageId: messageId, sentAt: t0)
        case .viewOnceConsumed:
            var attachment = sampleAttachment(caption: secret)
            attachment.thumbnail = Data("tiny-jpeg-preview".utf8)
            data = BRPDataMessage(payload: .attachment(attachment), messageId: messageId, sentAt: t0, viewOnce: true)
        case .remoteDeleteForEveryone, .deleteForMe:
            data = textMessage(secret, id: messageId, sentAt: t0)
        }
        try storage.write { tx in
            _ = try tx.messages.storeIncoming(data, in: conversation.id, receivedAt: t0)
        }
        let walAfterStore = try Self.walSize(environment)
        #expect(walAfterStore > 0)

        try storage.write { tx in
            switch deletion {
            case .remoteDeleteForEveryone:
                var delete = BRPDelete()
                delete.targetMessageID = messageId
                _ = try tx.messages.storeIncoming(
                    BRPDataMessage(payload: .delete(delete), sentAt: t0), in: conversation.id, receivedAt: t0)
            case .viewOnceConsumed:
                try tx.messages.consumeViewOnce(messageId, in: conversation.id, at: t0)
            case .deleteForMe:
                try tx.messages.deleteLocally([messageId], in: conversation.id)
            case .liveLocationUpdatedInPlace:
                var live = BRPLiveLocation()
                live.sessionID = liveSession
                live.latitude = 41.5
                live.longitude = 29.5
                live.expiresAt = UInt64(t0.timeIntervalSince1970 * 1000) + 3_600_000
                live.sequence = 1
                _ = try tx.messages.storeIncoming(
                    BRPDataMessage(payload: .liveLocation(live), sentAt: t0), in: conversation.id, receivedAt: t0)
            }
        }
        // The app itself no longer shows it.
        let visible = try storage.read { try $0.messages.get(messageId, in: conversation.id, now: t0) }
        if deletion == .liveLocationUpdatedInPlace {
            guard case .liveLocation(let shown)? = visible?.content?.payload else {
                Issue.record("no live-location bubble")
                return
            }
            #expect(shown.latitude == 41.5)
        } else {
            #expect(visible?.content == nil)
        }
        _ = account
        _ = contact

        let scratch = environment.directory.appendingPathComponent("examiner", isDirectory: true)
        let recovered = try Self.recover(
            messageId, environment: environment, walPrefix: walAfterStore, slotId: conversation.slotId,
            scratch: scratch)
        var recoveredText: String?
        switch recovered?.payload {
        case .text(let text)?: recoveredText = text.body
        case .attachment(let attachment)?: recoveredText = attachment.caption
        case .liveLocation(let live)?: recoveredText = "\(live.latitude)"
        default: recoveredText = nil
        }
        // Expected: nothing of the deleted message is left to recover.
        #expect(recoveredText != secret, "\(deletion.rawValue): deleted content recovered from stale WAL frames")
    }

    /// Ghost mode promises the whole conversation is gone (§8.2). The relay-id
    /// dedupe log (`processed_envelopes`) is not tied to a conversation and
    /// keeps the exact millisecond receive time of every envelope for 15
    /// days, so after the wipe an examiner with the DEK still reads when, how
    /// often and in which bursts the conversation was active. (Its only use is
    /// pruning, which needs no more than day precision.)
    @Test("a ghost wipe leaves no per-message receive timeline")
    func ghostWipeLeavesNoTimeline() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        defer { storage.close() }
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        for minute in 0..<5 {
            let at = t0.addingTimeInterval(Double(minute) * 97)
            try storage.write { tx in
                // As MessagingEngine.applyInbound does for every envelope.
                try tx.envelopes.record("relay-\(minute)", at: at)
                _ = try tx.messages.storeIncoming(textMessage("m\(minute)"), in: conversation.id, receivedAt: at)
            }
        }
        _ = try storage.wipeConversation(conversation.id, reason: .ghostTimeout, now: t0.addingTimeInterval(3600))
        #expect(try storage.read { try $0.conversations.get(conversation.id) } == nil, "control: wiped")

        let times = try storage.read { tx in
            try tx.connection.query("SELECT processed_at FROM processed_envelopes").compactMap { $0.int64("processed_at") }
        }
        #expect(Set(times).count <= 1, "exact receive times of the wiped conversation remain: \(times.sorted())")
    }
}
