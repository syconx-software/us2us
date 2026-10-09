// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// Pending sends with retry (§5.4: messages wait for the relay, not the
/// other way round). Content bound to a conversation is an item under its
/// `CK`, so a crypto-shredded conversation leaves no readable pending text;
/// conversation-less sends (receipts, `ConversationWipe` after a wipe,
/// profile and access-key updates) are protobufs inside the database.
///
/// Typical loop: ``due(now:limit:)`` → `Messenger.send` → ``markSent(_:now:)``
/// (which also marks the message *Sent*) or ``markFailed(_:error:retryable:policy:now:)``.
public struct OutboxRepository {
    let tx: StorageTransaction

    private static let columns = """
        id, recipient, conversation_id, message_id, payload, payload_id, ephemeral, state, attempts,
        next_attempt_at, created_at, last_error
        """

    @discardableResult
    public func enqueue(
        _ content: BRPContent, to recipient: AccountId, conversation conversationId: ConversationId? = nil,
        messageId: Data? = nil, ephemeral: Bool = false, waitingForUpload: Bool = false, now: Date = Date()
    ) throws -> Int64 {
        try tx.requireWritable()
        let payloadId = RandomId.make()
        var payload: Data = try content.serializedBytes()
        if let conversationId {
            payload = try tx.seal(payload, itemId: payloadId, key: try tx.conversationKey(conversationId))
        }
        try tx.connection.run(
            """
            INSERT INTO outbox (recipient, conversation_id, message_id, payload, payload_id, ephemeral, state,
                next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [recipient.bytes, conversationId?.rawValue, messageId, payload, payloadId, ephemeral,
             (waitingForUpload ? OutboxState.waitingForUpload : .ready).rawValue, now.storageMilliseconds,
             now.storageMilliseconds])
        return tx.connection.lastInsertRowId
    }

    public func get(_ id: Int64) throws -> OutboxEntry? {
        try tx.connection.queryOne("SELECT \(Self.columns) FROM outbox WHERE id = ?", [id]).map(entry(from:))
    }

    /// Ready entries whose retry time has come, oldest first (per-recipient order is kept).
    public func due(now: Date = Date(), limit: Int = 50) throws -> [OutboxEntry] {
        try tx.connection.query(
            """
            SELECT \(Self.columns) FROM outbox WHERE state = ? AND next_attempt_at <= ?
            ORDER BY id LIMIT ?
            """, [OutboxState.ready.rawValue, now.storageMilliseconds, limit]
        ).map(entry(from:))
    }

    public func all(state: OutboxState? = nil) throws -> [OutboxEntry] {
        if let state {
            return try tx.connection.query(
                "SELECT \(Self.columns) FROM outbox WHERE state = ? ORDER BY id", [state.rawValue]
            ).map(entry(from:))
        }
        return try tx.connection.query("SELECT \(Self.columns) FROM outbox ORDER BY id").map(entry(from:))
    }

    /// The queued sends of one message (normally one; none once the relay accepted it).
    public func entries(for messageId: Data, in conversationId: ConversationId) throws -> [OutboxEntry] {
        try tx.connection.query(
            "SELECT \(Self.columns) FROM outbox WHERE conversation_id = ? AND message_id = ? ORDER BY id",
            [conversationId.rawValue, messageId]
        ).map(entry(from:))
    }

    /// When the next ready entry is due (for a retry timer).
    public func nextAttemptDate() throws -> Date? {
        try tx.connection.queryOne(
            "SELECT min(next_attempt_at) AS t FROM outbox WHERE state = ?", [OutboxState.ready.rawValue])?.date("t")
    }

    /// The attachment upload finished: the entry may be sent.
    public func markReady(_ id: Int64, content: BRPContent? = nil, now: Date = Date()) throws {
        try tx.requireWritable()
        if let content {
            guard let existing = try get(id) else { throw StorageError.notFound }
            var payload: Data = try content.serializedBytes()
            if let conversationId = existing.conversationId {
                let payloadId = try payloadId(of: id)
                payload = try tx.seal(payload, itemId: payloadId, key: try tx.conversationKey(conversationId))
            }
            try tx.connection.run("UPDATE outbox SET payload = ? WHERE id = ?", [payload, id])
        }
        try tx.connection.run(
            "UPDATE outbox SET state = ?, next_attempt_at = ? WHERE id = ?",
            [OutboxState.ready.rawValue, now.storageMilliseconds, id])
    }

    /// The relay accepted the envelope: the entry is removed and its message
    /// becomes *Sent*. A contact waiting to be forgotten (ghost
    /// `wipe_contact`) is forgotten once nothing is left to send to it.
    public func markSent(_ id: Int64, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let entry = try get(id) else { return }
        try tx.connection.run("DELETE FROM outbox WHERE id = ?", [id])
        if let messageId = entry.messageId, let conversationId = entry.conversationId {
            try tx.messages.markAccepted(messageId, in: conversationId, at: now)
        }
        try forgetIfPending(entry.recipient)
    }

    /// Records a failed attempt. Retryable failures back off per `policy`;
    /// permanent ones (or entries older than `policy.maximumAge`) become
    /// `failed` and mark their message *Failed*. `error` is a short code,
    /// never content.
    public func markFailed(
        _ id: Int64, error: String, retryable: Bool, policy: RetryPolicy = .standard, now: Date = Date()
    ) throws {
        try tx.requireWritable()
        guard let entry = try get(id) else { return }
        let attempts = entry.attempts + 1
        let tooOld = now.timeIntervalSince(entry.createdAt) >= policy.maximumAge
        if retryable && !tooOld {
            let next = now.addingTimeInterval(policy.delay(afterAttempts: attempts))
            try tx.connection.run(
                "UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                [attempts, next.storageMilliseconds, String(error.prefix(64)), id])
            return
        }
        try tx.connection.run(
            "UPDATE outbox SET attempts = ?, state = ?, last_error = ? WHERE id = ?",
            [attempts, OutboxState.failed.rawValue, String(error.prefix(64)), id])
        if let messageId = entry.messageId, let conversationId = entry.conversationId {
            try tx.messages.markFailed(messageId, in: conversationId, at: now)
        }
        try forgetIfPending(entry.recipient)
    }

    /// Puts a failed entry back in the queue (user tapped "retry").
    public func retry(_ id: Int64, now: Date = Date()) throws {
        try tx.requireWritable()
        try tx.connection.run(
            "UPDATE outbox SET state = ?, next_attempt_at = ?, attempts = 0 WHERE id = ? AND state = ?",
            [OutboxState.ready.rawValue, now.storageMilliseconds, id, OutboxState.failed.rawValue])
        guard let entry = try get(id), let messageId = entry.messageId, let conversationId = entry.conversationId
        else { return }
        try tx.connection.run(
            """
            UPDATE messages SET status = ?, failed_at = NULL
            WHERE conversation_id = ? AND message_id = ? AND status = ?
            """,
            [MessageStatus.sending.rawValue, conversationId.rawValue, messageId, MessageStatus.failed.rawValue])
    }

    public func remove(_ id: Int64) throws {
        try tx.requireWritable()
        guard let entry = try get(id) else { return }
        try tx.connection.run("DELETE FROM outbox WHERE id = ?", [id])
        try forgetIfPending(entry.recipient)
    }

    private func forgetIfPending(_ recipient: AccountId) throws {
        guard let contact = try tx.contacts.get(recipient), contact.pendingForget else { return }
        let remaining = try tx.connection.queryOne(
            "SELECT 1 FROM outbox WHERE recipient = ? AND state != ? LIMIT 1",
            [recipient.bytes, OutboxState.failed.rawValue])
        if remaining == nil { try tx.contacts.forget(recipient) }
    }

    private func payloadId(of id: Int64) throws -> Data {
        guard let payloadId = try tx.connection.queryOne("SELECT payload_id FROM outbox WHERE id = ?", [id])?
            .blob("payload_id")
        else { throw StorageError.notFound }
        return payloadId
    }

    private func entry(from row: Row) throws -> OutboxEntry {
        guard let id = row.int64("id"), let recipient = row.blob("recipient").flatMap(AccountId.init(bytes:)),
            var payload = row.blob("payload"), let payloadId = row.blob("payload_id"),
            let state = row.int("state").flatMap(OutboxState.init(rawValue:))
        else { throw StorageError.integrity }
        let conversationId = row.blob("conversation_id").flatMap(ConversationId.init(rawValue:))
        if let conversationId {
            payload = try tx.open(payload, itemId: payloadId, key: try tx.conversationKey(conversationId))
        }
        let content: BRPContent
        do {
            content = try BRPContent(serializedBytes: payload)
        } catch {
            throw StorageError.integrity
        }
        return OutboxEntry(
            id: id, recipient: recipient, conversationId: conversationId, messageId: row.blob("message_id"),
            content: content, ephemeral: row.bool("ephemeral"), state: state, attempts: row.int("attempts") ?? 0,
            nextAttemptAt: row.date("next_attempt_at") ?? Date(timeIntervalSince1970: 0),
            createdAt: row.date("created_at") ?? Date(timeIntervalSince1970: 0), lastError: row.text("last_error"))
    }
}

/// Relay ids already stored or dropped, so a redelivered envelope (§4.7: the
/// relay re-sends until acknowledged) is acknowledged without being handled
/// twice — also across app restarts, which Messenger's in-memory set cannot
/// cover. Record the id in the same transaction as the message.
///
/// Privacy: only the UTC **day** of processing is kept, never the exact
/// time. The log is not tied to a conversation, so exact times would outlive
/// a ghost wipe (§8.2) as a per-message receive timeline; pruning needs no
/// more than the day. An entry stays at least 14 days (the relay's
/// retention, after which no redelivery can happen) and at most 15.
public struct EnvelopeLog {
    let tx: StorageTransaction

    public static let retention: TimeInterval = 15 * 24 * 3600
    static let dayMilliseconds: Int64 = 86_400_000

    /// Midnight UTC of `time`'s day, in milliseconds.
    static func day(of time: Date) -> Int64 {
        let milliseconds = time.storageMilliseconds
        return milliseconds - ((milliseconds % dayMilliseconds) + dayMilliseconds) % dayMilliseconds
    }

    public func contains(_ relayId: String) throws -> Bool {
        try tx.connection.queryOne("SELECT 1 FROM processed_envelopes WHERE relay_id = ?", [relayId]) != nil
    }

    public func record(_ relayId: String, at time: Date = Date()) throws {
        try tx.requireWritable()
        try tx.connection.run(
            "INSERT OR IGNORE INTO processed_envelopes (relay_id, processed_at) VALUES (?, ?)",
            [relayId, Self.day(of: time)])
    }

    @discardableResult
    public func prune(now: Date = Date()) throws -> Int {
        try tx.requireWritable()
        try tx.connection.run(
            "DELETE FROM processed_envelopes WHERE processed_at < ?",
            [now.addingTimeInterval(-Self.retention).storageMilliseconds])
        return tx.connection.changes
    }
}
