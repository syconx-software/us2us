// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// Messages of PROTOCOL.md §8: every content type of `DataMessage`, delivery
/// status, reactions, quotes, view-once, delete-for-everyone and disappearing
/// deadlines.
///
/// The whole `DataMessage` is stored as an item under the conversation key
/// (`itemId = message_id`, §5.3); columns hold only what queries need.
///
/// **Disappearing (§8.1)**: an outgoing message with timer T expires at
/// `createdAt + T`; an incoming one at `displayedAt + T`, set the first time
/// it is displayed (``markDisplayed(_:in:at:)``). Wipes delete expired rows
/// and their files (``LocalStorage/performDueWipes(now:)``); queries never
/// return a message past its deadline even before that runs.
public struct MessageRepository {
    let tx: StorageTransaction

    static let columns = """
        id, conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at, server_timestamp,
        status, accepted_at, delivered_at, read_at, viewed_at, not_delivered_at, failed_at, displayed_at,
        expire_timer, expires_at, view_once, quote_target, live_session, deleted_at, saved_by_peer_at, saved_by_me_at
        """

    /// After this long without a delivery receipt a sent message is *Not delivered* (§5.4).
    public static let undeliveredAfter: TimeInterval = 14 * 24 * 3600

    // MARK: Storing

    /// Stores a `DataMessage` received from the conversation's contact and
    /// applies its semantics (bubble, reaction, delete-for-everyone,
    /// live-location update). Records activity for ghost mode, except for
    /// duplicates and live-location updates (§8.2).
    @discardableResult
    public func storeIncoming(
        _ data: BRPDataMessage, in conversationId: ConversationId, serverTimestamp: Int64? = nil,
        receivedAt: Date = Date()
    ) throws -> DataMessageOutcome {
        try tx.requireWritable()
        guard let conversation = try tx.conversations.get(conversationId) else { throw StorageError.notFound }
        return try store(
            data, conversation: conversation, outgoing: false, sender: conversation.contact,
            serverTimestamp: serverTimestamp, at: receivedAt)
    }

    /// Stores a `DataMessage` we are sending (status `sending`), with the
    /// same semantics as ``storeIncoming(_:in:serverTimestamp:receivedAt:)``.
    /// Enqueue the send with ``OutboxRepository/enqueue(_:to:conversation:messageId:ephemeral:waitingForUpload:now:)``
    /// in the same transaction.
    @discardableResult
    public func storeOutgoing(
        _ data: BRPDataMessage, in conversationId: ConversationId, at time: Date = Date()
    ) throws -> DataMessageOutcome {
        try tx.requireWritable()
        guard let conversation = try tx.conversations.get(conversationId) else { throw StorageError.notFound }
        return try store(
            data, conversation: conversation, outgoing: true, sender: try tx.ownAccountId(), serverTimestamp: nil,
            at: time)
    }

    private func store(
        _ data: BRPDataMessage, conversation: Conversation, outgoing: Bool, sender: AccountId,
        serverTimestamp: Int64?, at time: Date
    ) throws -> DataMessageOutcome {
        guard data.messageID.count == RandomId.byteCount else { throw StorageError.invalidState("message id") }
        if try exists(data.messageID, in: conversation.id) { return .duplicate }
        let outcome: DataMessageOutcome
        var countsAsActivity = true
        switch data.payload {
        case .reaction(let reaction):
            outcome = try applyReaction(
                reaction, reactionId: data.messageID, from: sender, sentAt: data.sentAt, in: conversation)
        case .delete(let delete):
            outcome = try applyDelete(target: delete.targetMessageID, from: sender, in: conversation, at: time)
        case .liveLocation(let live):
            if let existing = try liveLocationBubble(session: live.sessionID, in: conversation.id) {
                countsAsActivity = false
                outcome = try updateLiveLocation(
                    existing, with: data, live: live, from: sender, in: conversation, at: time)
            } else {
                outcome = try insertBubble(
                    data, conversation: conversation, outgoing: outgoing, sender: sender,
                    serverTimestamp: serverTimestamp, at: time)
            }
        case .text, .attachment, .location, .sticker:
            outcome = try insertBubble(
                data, conversation: conversation, outgoing: outgoing, sender: sender,
                serverTimestamp: serverTimestamp, at: time)
        case .none:
            // A payload added by a newer client: ignore it (brp.proto rules).
            return .ignored
        }
        if countsAsActivity, outcome != .duplicate {
            try tx.conversations.recordActivity(conversation.id, at: time)
        }
        return outcome
    }

    /// A row with this id, or one that was removed entirely (expired,
    /// deleted for me): a retransmission of the same `message_id` (the
    /// sender's outbox retries when a `202` was lost) must not bring it back.
    private func exists(_ messageId: Data, in conversationId: ConversationId) throws -> Bool {
        if try tx.connection.queryOne(
            "SELECT 1 FROM messages WHERE conversation_id = ? AND message_id = ?",
            [conversationId.rawValue, messageId]) != nil
        {
            return true
        }
        return try wasRemoved(messageId, in: conversationId)
    }

    func wasRemoved(_ messageId: Data, in conversationId: ConversationId) throws -> Bool {
        try tx.connection.queryOne(
            "SELECT 1 FROM removed_messages WHERE conversation_id = ? AND message_id = ?",
            [conversationId.rawValue, messageId]) != nil
    }

    private func insertBubble(
        _ data: BRPDataMessage, conversation: Conversation, outgoing: Bool, sender: AccountId,
        serverTimestamp: Int64?, at time: Date
    ) throws -> DataMessageOutcome {
        var data = data.strippingViewOncePreview()
        // A quote shows the excerpt only of a message that stays: never one
        // with a timer, a view-once item, a deleted or a removed one (§9:
        // nothing is displayed after its deadline, also not inside a reply).
        if data.hasQuote, !data.quote.previewText.isEmpty,
            try !mayKeepQuotePreview(of: data.quote.targetMessageID, in: conversation.id)
        {
            data.quote.previewText = ""
        }
        let key = try tx.conversationKey(slotId: conversation.slotId)
        var body: Data? = try tx.seal(try data.serializedBytes(), itemId: data.messageID, key: key)
        var deletedAt: Date?
        // A delete-for-everyone that arrived before its message (§8.5).
        if let pending = try tx.connection.queryOne(
            "SELECT sender, requested_at FROM pending_deletes WHERE conversation_id = ? AND message_id = ?",
            [conversation.id.rawValue, data.messageID]),
            pending.blob("sender") == sender.bytes
        {
            body = nil
            deletedAt = pending.date("requested_at") ?? time
            try tx.connection.run(
                "DELETE FROM pending_deletes WHERE conversation_id = ? AND message_id = ?",
                [conversation.id.rawValue, data.messageID])
        }
        let timer = data.expireTimerSeconds
        var expiresAt: Int64? =
            outgoing && timer > 0 ? time.storageMilliseconds + Int64(timer) * 1000 : nil
        var liveSession: Data?
        if case .liveLocation(let live) = data.payload {
            liveSession = live.sessionID
            if outgoing && timer > 0 {
                let now = time.storageMilliseconds
                expiresAt = Self.liveLocationDeadline(start: now, timer: timer, live: live, createdAt: now, now: now)
            }
        }
        try tx.connection.run(
            """
            INSERT INTO messages (conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at,
                server_timestamp, status, expire_timer, expires_at, view_once, quote_target, live_session, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                conversation.id.rawValue, data.messageID, outgoing, sender.bytes, MessageKind(data.payload).rawValue,
                body, Int64(clamping: data.sentAt), time.storageMilliseconds, serverTimestamp,
                (outgoing ? MessageStatus.sending : .received).rawValue, timer, expiresAt, data.viewOnce,
                data.hasQuote ? data.quote.targetMessageID : nil, liveSession, deletedAt?.storageMilliseconds,
            ])
        if deletedAt == nil {
            try linkAttachments(of: data, outgoing: outgoing, in: conversation.id, at: time)
        }
        guard let stored = try get(data.messageID, in: conversation.id) else { throw StorageError.notFound }
        return .stored(stored)
    }

    /// Creates the attachment rows of a stored message. An outgoing message
    /// whose rows already exist (created to hold the picked file before the
    /// upload) reuses them. An outgoing attachment without a relay pointer
    /// yet is `pendingUpload`.
    private func linkAttachments(of data: BRPDataMessage, outgoing: Bool, in conversationId: ConversationId, at time: Date)
        throws
    {
        let attachments = Self.attachments(in: data)
        let existing = outgoing ? try tx.attachments.forMessage(data.messageID, in: conversationId) : []
        for (index, attachment) in attachments.enumerated() {
            let uploaded = attachment.mediaID.count == RandomId.byteCount
            if index < existing.count {
                if uploaded { try tx.attachments.setDescriptor(attachment, of: existing[index].id, now: time) }
                continue
            }
            let state: AttachmentState = outgoing ? (uploaded ? .uploaded : .pendingUpload) : .pendingDownload
            _ = try tx.attachments.create(
                for: data.messageID, in: conversationId, descriptor: attachment, state: state, now: time)
        }
    }

    /// Replaces the content of an outgoing message that has not been sent
    /// yet — typically once its attachment was uploaded and the `Attachment`
    /// now carries the relay pointer and keys. Updates the attachment rows too.
    public func updateOutgoingContent(_ data: BRPDataMessage, in conversationId: ConversationId, at time: Date = Date())
        throws
    {
        try tx.requireWritable()
        guard let message = try get(data.messageID, in: conversationId, includeExpired: true), message.isOutgoing,
            message.status == .sending, !message.isDeleted, MessageKind(data.payload) == message.kind
        else { throw StorageError.invalidState("not an unsent outgoing message") }
        let key = try tx.conversationKey(conversationId)
        let body = try tx.seal(try data.serializedBytes(), itemId: data.messageID, key: key)
        try tx.connection.run("UPDATE messages SET body = ? WHERE id = ?", [body, message.localId])
        try linkAttachments(of: data, outgoing: true, in: conversationId, at: time)
    }

    /// The attachments a `DataMessage` points to (the attachment payload or a custom sticker).
    static func attachments(in data: BRPDataMessage) -> [BRPAttachment] {
        switch data.payload {
        case .attachment(let attachment): return [attachment]
        case .sticker(let sticker) where sticker.hasCustom: return [sticker.custom]
        default: return []
        }
    }

    // MARK: Reactions

    private func applyReaction(
        _ reaction: BRPReaction, reactionId: Data, from reactor: AccountId, sentAt: UInt64,
        in conversation: Conversation
    ) throws -> DataMessageOutcome {
        let target = reaction.targetMessageID
        guard
            let targetRow = try tx.connection.queryOne(
                "SELECT deleted_at FROM messages WHERE conversation_id = ? AND message_id = ?",
                [conversation.id.rawValue, target]),
            targetRow.int64("deleted_at") == nil
        else { return .ignored }
        let existing = try tx.connection.queryOne(
            """
            SELECT sent_at FROM reactions WHERE conversation_id = ? AND target_message_id = ? AND reactor = ?
            """, [conversation.id.rawValue, target, reactor.bytes])
        // Last writer wins per reactor and message.
        if let existingSentAt = existing?.int64("sent_at"), existingSentAt > Int64(clamping: sentAt) {
            return .ignored
        }
        if reaction.remove || reaction.emoji.isEmpty {
            try tx.connection.run(
                "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ? AND reactor = ?",
                [conversation.id.rawValue, target, reactor.bytes])
            return .reaction(target: target)
        }
        let key = try tx.conversationKey(slotId: conversation.slotId)
        let emoji = try tx.seal(Data(reaction.emoji.utf8), itemId: reactionId, key: key)
        try tx.connection.run(
            """
            INSERT OR REPLACE INTO reactions (conversation_id, target_message_id, reactor, reaction_id, emoji, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, [conversation.id.rawValue, target, reactor.bytes, reactionId, emoji, Int64(clamping: sentAt)])
        return .reaction(target: target)
    }

    /// Reactions on the given messages, keyed by target message id.
    public func reactions(on messageIds: [Data], in conversationId: ConversationId) throws -> [Data: [StoredReaction]] {
        guard !messageIds.isEmpty else { return [:] }
        let key = try tx.conversationKey(conversationId)
        var result: [Data: [StoredReaction]] = [:]
        for messageId in messageIds {
            let rows = try tx.connection.query(
                """
                SELECT reactor, reaction_id, emoji, sent_at FROM reactions
                WHERE conversation_id = ? AND target_message_id = ? ORDER BY sent_at
                """, [conversationId.rawValue, messageId])
            for row in rows {
                guard let reactor = row.blob("reactor").flatMap(AccountId.init(bytes:)),
                    let reactionId = row.blob("reaction_id"), let item = row.blob("emoji")
                else { continue }
                let emoji = String(decoding: try tx.open(item, itemId: reactionId, key: key), as: UTF8.self)
                result[messageId, default: []].append(
                    StoredReaction(
                        targetMessageId: messageId, reactor: reactor, emoji: emoji,
                        sentAt: row.date("sent_at") ?? Date(timeIntervalSince1970: 0)))
            }
        }
        return result
    }

    // MARK: Deletes

    /// Delete-for-everyone from `sender` (§8.5): only the original sender may
    /// delete. A delete for a message not received yet is remembered and
    /// applied when it arrives.
    private func applyDelete(
        target: Data, from sender: AccountId, in conversation: Conversation, at time: Date
    ) throws -> DataMessageOutcome {
        guard
            let row = try tx.connection.queryOne(
                "SELECT sender FROM messages WHERE conversation_id = ? AND message_id = ?",
                [conversation.id.rawValue, target])
        else {
            // Already removed here (expired, deleted for me): nothing to remember.
            if try wasRemoved(target, in: conversation.id) { return .ignored }
            try tx.connection.run(
                """
                INSERT OR REPLACE INTO pending_deletes (conversation_id, message_id, sender, requested_at)
                VALUES (?, ?, ?, ?)
                """, [conversation.id.rawValue, target, sender.bytes, time.storageMilliseconds])
            return .ignored
        }
        guard row.blob("sender") == sender.bytes else { return .ignored }
        try tombstone(target, in: conversation.id, at: time)
        return .deleted(target: target)
    }

    /// Our own delete-for-everyone of an outgoing message: tombstones it
    /// locally. Send the `Delete` through the outbox in the same transaction.
    public func deleteForEveryone(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws {
        try tx.requireWritable()
        guard let message = try get(messageId, in: conversationId, includeExpired: true), message.isOutgoing else {
            throw StorageError.invalidState("only own messages can be deleted for everyone")
        }
        try tombstone(messageId, in: conversationId, at: time)
        try tx.conversations.recordActivity(conversationId, at: time)
    }

    /// Removes body, attachments and reactions; keeps a "deleted" row.
    /// Quote excerpts of it in replies are scrubbed, and the WAL is
    /// checkpointed after the commit (no old page image keeps the text).
    private func tombstone(_ messageId: Data, in conversationId: ConversationId, at time: Date) throws {
        try tx.attachments.deleteAll(for: [messageId], in: conversationId)
        try tx.connection.run(
            "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ?",
            [conversationId.rawValue, messageId])
        try tx.connection.run(
            """
            UPDATE messages SET body = NULL, deleted_at = coalesce(deleted_at, ?)
            WHERE conversation_id = ? AND message_id = ?
            """, [time.storageMilliseconds, conversationId.rawValue, messageId])
        try scrubQuotePreviews(of: [messageId], in: conversationId)
        tx.checkpointAfterCommit()
    }

    /// "Delete for me" (and expired disappearing messages): removes the rows,
    /// attachments and reactions entirely, and pending sends of them (an
    /// outgoing message removed before the relay accepted it is never sent).
    /// The ids are remembered (``removedIdRetention``) so a retransmission is
    /// a duplicate; quote excerpts of them are scrubbed; the WAL is
    /// checkpointed after the commit.
    public func deleteLocally(_ messageIds: [Data], in conversationId: ConversationId, at time: Date = Date())
        throws
    {
        try tx.requireWritable()
        guard !messageIds.isEmpty else { return }
        try tx.attachments.deleteAll(for: messageIds, in: conversationId)
        let day = EnvelopeLog.day(of: time)
        for messageId in messageIds {
            try tx.connection.run(
                "DELETE FROM reactions WHERE conversation_id = ? AND target_message_id = ?",
                [conversationId.rawValue, messageId])
            try tx.connection.run(
                "DELETE FROM messages WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, messageId])
            try tx.connection.run(
                "DELETE FROM outbox WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, messageId])
            try tx.connection.run(
                """
                INSERT OR IGNORE INTO removed_messages (conversation_id, message_id, removed_day)
                VALUES (?, ?, ?)
                """, [conversationId.rawValue, messageId, day])
        }
        try scrubQuotePreviews(of: messageIds, in: conversationId)
        tx.checkpointAfterCommit()
    }

    /// How long removed ids are remembered: a sender retries for up to 14
    /// days (`RetryPolicy.maximumAge`) and the relay keeps a retransmission
    /// up to 14 more.
    public static let removedIdRetention: TimeInterval = 30 * 24 * 3600

    /// Forgets removed ids older than ``removedIdRetention``.
    @discardableResult
    public func pruneRemovedIds(now: Date = Date()) throws -> Int {
        try tx.requireWritable()
        try tx.connection.run(
            "DELETE FROM removed_messages WHERE removed_day < ?",
            [now.addingTimeInterval(-Self.removedIdRetention).storageMilliseconds])
        return tx.connection.changes
    }

    // MARK: Quote previews

    /// A reply may keep (and show) the excerpt of its target only while the
    /// target is here and permanent.
    func mayKeepQuotePreview(of target: Data, in conversationId: ConversationId) throws -> Bool {
        guard
            let row = try tx.connection.queryOne(
                "SELECT expire_timer, view_once, deleted_at FROM messages WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, target])
        else { return false }
        return (row.int64("expire_timer") ?? 0) == 0 && !row.bool("view_once") && row.int64("deleted_at") == nil
    }

    /// Clears `Quote.preview_text` in every reply to `targets` (they were
    /// deleted, expired or consumed), so their text does not live on.
    func scrubQuotePreviews(of targets: [Data], in conversationId: ConversationId) throws {
        var key: Data?
        for target in targets {
            let rows = try tx.connection.query(
                """
                SELECT id, message_id, body FROM messages
                WHERE conversation_id = ? AND quote_target = ? AND body IS NOT NULL
                """, [conversationId.rawValue, target])
            for row in rows {
                guard let localId = row.int64("id"), let messageId = row.blob("message_id"), let body = row.blob("body")
                else { continue }
                let conversationKey = try key ?? tx.conversationKey(conversationId)
                key = conversationKey
                guard var data = try? BRPDataMessage(serializedBytes: try tx.open(body, itemId: messageId, key: conversationKey)),
                    data.hasQuote, !data.quote.previewText.isEmpty
                else { continue }
                data.quote.previewText = ""
                let sealed = try tx.seal(try data.serializedBytes(), itemId: messageId, key: conversationKey)
                try tx.connection.run("UPDATE messages SET body = ? WHERE id = ?", [sealed, localId])
            }
        }
    }

    // MARK: Live location (§8.3)

    /// Our live-location bubbles in every conversation that are neither
    /// deleted nor past their deadline, oldest first. Only rows that carry a
    /// session are read (the `messages_by_live_session` index), so checking
    /// them at every start does not decrypt whole conversations; a row that
    /// cannot be read is skipped instead of hiding the others.
    public func outgoingLiveLocations(now: Date = Date()) throws -> [StoredMessage] {
        let rows = try tx.connection.query(
            """
            SELECT \(Self.columns) FROM messages
            WHERE live_session IS NOT NULL AND outgoing = 1 AND deleted_at IS NULL
                AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id
            """, [now.storageMilliseconds])
        var keys: [Data: Data] = [:]
        return rows.compactMap { row -> StoredMessage? in
            guard let bytes = row.blob("conversation_id"), let conversationId = ConversationId(rawValue: bytes)
            else { return nil }
            do {
                let key = try keys[bytes] ?? tx.conversationKey(conversationId)
                keys[bytes] = key
                return try message(from: row, key: key)
            } catch {
                return nil
            }
        }
    }

    private func liveLocationBubble(session: Data, in conversationId: ConversationId) throws -> StoredMessage? {
        guard session.count == RandomId.byteCount else { return nil }
        return try tx.connection.queryOne(
            "SELECT \(Self.columns) FROM messages WHERE conversation_id = ? AND live_session = ? ORDER BY id LIMIT 1",
            [conversationId.rawValue, session]
        ).map { try message(from: $0, key: try tx.conversationKey(conversationId)) }
    }

    private func updateLiveLocation(
        _ existing: StoredMessage, with data: BRPDataMessage, live: BRPLiveLocation, from sender: AccountId,
        in conversation: Conversation, at time: Date
    ) throws -> DataMessageOutcome {
        guard existing.sender == sender, !existing.isDeleted,
            case .liveLocation(let current)? = existing.content?.payload,
            !current.stopped, live.sequence > current.sequence || live.stopped
        else { return .ignored }
        // Keep the bubble's own message id (the item id) and replace its payload.
        var updated = existing.content ?? data
        updated.payload = .liveLocation(live)
        let key = try tx.conversationKey(slotId: conversation.slotId)
        let body = try tx.seal(try updated.serializedBytes(), itemId: existing.messageId, key: key)
        // With a disappearing timer the deadline follows the end of sharing.
        var expiresAt = existing.expiresAt?.storageMilliseconds
        let start = existing.isOutgoing ? existing.createdAt : existing.timestamps.displayedAt
        if existing.expireTimerSeconds > 0, let start {
            expiresAt = Self.liveLocationDeadline(
                start: start.storageMilliseconds, timer: existing.expireTimerSeconds, live: live,
                createdAt: existing.createdAt.storageMilliseconds, now: time.storageMilliseconds)
        }
        try tx.connection.run(
            "UPDATE messages SET body = ?, expires_at = ? WHERE id = ?", [body, expiresAt, existing.localId])
        // Earlier positions must not survive as older page images in the WAL.
        tx.checkpointAfterCommit()
        guard let stored = try get(existing.messageId, in: conversation.id, now: time) else {
            throw StorageError.notFound
        }
        return .liveLocationUpdated(stored)
    }

    /// An incoming `expires_at` is a sender claim; sharing lasts at most 8 h
    /// (plus an hour for clock skew).
    static let maxLiveSharingMilliseconds: Int64 = 9 * 3600 * 1000

    /// A live-location bubble with timer T disappears like any message, T
    /// after it was sent (ours) or first displayed (theirs) — but never
    /// while the sharing it shows is still running: `max(start + T, end)`,
    /// where `end` is the stop (now) or the session's `expires_at`.
    static func liveLocationDeadline(start: Int64, timer: UInt32, live: BRPLiveLocation, createdAt: Int64, now: Int64)
        -> Int64
    {
        let byTimer = start + Int64(timer) * 1000
        let end = live.stopped ? now : min(Int64(clamping: live.expiresAt), createdAt + maxLiveSharingMilliseconds)
        return max(byTimer, end)
    }

    // MARK: Reading

    public func get(
        _ messageId: Data, in conversationId: ConversationId, now: Date = Date(), includeExpired: Bool = false
    ) throws -> StoredMessage? {
        guard
            let row = try tx.connection.queryOne(
                "SELECT \(Self.columns) FROM messages WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, messageId])
        else { return nil }
        if !includeExpired, let expiresAt = row.int64("expires_at"), expiresAt <= now.storageMilliseconds {
            return nil
        }
        return try message(from: row, key: try tx.conversationKey(conversationId))
    }

    /// Up to `limit` messages before `before` (a ``StoredMessage/localId``;
    /// `nil` = the latest), in chronological order. Expired messages are
    /// never returned (§9: never displayed after the deadline).
    public func page(
        in conversationId: ConversationId, before: Int64? = nil, limit: Int = 50, now: Date = Date(),
        through: Int64? = nil
    ) throws -> [StoredMessage] {
        let key = try tx.conversationKey(conversationId)
        let rows = try tx.connection.query(
            """
            SELECT \(Self.columns) FROM messages
            WHERE conversation_id = ? AND id < ? AND id >= ? AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY id DESC LIMIT ?
            """, [conversationId.rawValue, before ?? Int64.max, through ?? 0, now.storageMilliseconds, limit])
        return try rows.reversed().map { try message(from: $0, key: key) }
    }

    /// The newest visible message (conversation list preview).
    public func latest(in conversationId: ConversationId, now: Date = Date()) throws -> StoredMessage? {
        try page(in: conversationId, limit: 1, now: now).last
    }

    /// The conversation's photos and videos, or its files: up to `limit`
    /// attachment messages before `before` (a ``StoredMessage/localId``;
    /// `nil` = from the newest) whose `Attachment.kind` is in `kinds`,
    /// **newest first**. Never view-once, deleted or expired items, and no
    /// disappearing item at all (§8.1): a list kept outside the conversation
    /// must never show one after its deadline, and they cannot be saved.
    /// Only attachment rows are read (`messages_attachments`), in batches.
    public func mediaPage(
        in conversationId: ConversationId, kinds: Set<BRPAttachment.Kind>, before: Int64? = nil, limit: Int = 50,
        now: Date = Date()
    ) throws -> [StoredMessage] {
        guard limit > 0, !kinds.isEmpty else { return [] }
        let key = try tx.conversationKey(conversationId)
        let batch = max(limit, 64)
        var cursor = before ?? Int64.max
        var result: [StoredMessage] = []
        while result.count < limit {
            let rows = try tx.connection.query(
                """
                SELECT \(Self.columns) FROM messages
                WHERE conversation_id = ? AND kind = \(MessageKind.attachment.rawValue) AND id < ?
                    AND view_once = 0 AND deleted_at IS NULL AND body IS NOT NULL
                    AND expire_timer = 0 AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY id DESC LIMIT ?
                """, [conversationId.rawValue, cursor, now.storageMilliseconds, batch])
            for row in rows {
                let stored = try message(from: row, key: key)
                guard case .attachment(let attachment)? = stored.content?.payload, kinds.contains(attachment.kind)
                else { continue }
                result.append(stored)
                if result.count == limit { break }
            }
            guard rows.count == batch, let last = rows.last?.int64("id") else { break }
            cursor = last
        }
        return result
    }

    /// The local row id (``StoredMessage/localId``) of a message, if it is
    /// still here; nothing is decrypted.
    public func localId(of messageId: Data, in conversationId: ConversationId) throws -> Int64? {
        try tx.connection.queryOne(
            "SELECT id FROM messages WHERE conversation_id = ? AND message_id = ?",
            [conversationId.rawValue, messageId]
        )?.int64("id")
    }

    public func count(in conversationId: ConversationId) throws -> Int {
        Int(
            try tx.connection.queryOne(
                "SELECT count(*) AS n FROM messages WHERE conversation_id = ?", [conversationId.rawValue])?
                .int64("n") ?? 0)
    }

    /// The message a quote points to, if it is still here.
    public func quoted(by message: StoredMessage, now: Date = Date()) throws -> StoredMessage? {
        guard let target = message.quoteTarget else { return nil }
        return try get(target, in: message.conversationId, now: now)
    }

    // MARK: Status (§5.4, §8.4)

    /// The relay answered 202: *Sent*.
    public func markAccepted(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE messages SET status = CASE WHEN status = ? THEN ? ELSE status END,
                accepted_at = coalesce(accepted_at, ?)
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 1
            """,
            [MessageStatus.sending.rawValue, MessageStatus.sent.rawValue, time.storageMilliseconds,
             conversationId.rawValue, messageId])
    }

    /// The relay refused it for good: *Failed* (not retried).
    public func markFailed(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE messages SET status = ?, failed_at = ?
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status = ?
            """,
            [MessageStatus.failed.rawValue, time.storageMilliseconds, conversationId.rawValue, messageId,
             MessageStatus.sending.rawValue])
    }

    /// Applies a `ReceiptMessage` from the conversation's contact to our
    /// outgoing messages. Status only moves forward (a late delivery receipt
    /// also lifts *Not delivered*); VIEWED and SAVED are set once. Returns
    /// the message ids that changed.
    @discardableResult
    public func applyReceipt(
        _ receipt: BRPReceiptMessage, in conversationId: ConversationId, at time: Date = Date()
    ) throws -> [Data] {
        try tx.requireWritable()
        let now = time.storageMilliseconds
        let upgradable = [MessageStatus.sending, .sent, .notDelivered].map(\.rawValue)
        var changed: [Data] = []
        for messageId in receipt.messageIds.prefix(ReceiptPolicy.maxMessageIds)
        where messageId.count == RandomId.byteCount {
            switch receipt.type {
            case .delivery:
                try tx.connection.run(
                    """
                    UPDATE messages SET status = ?, delivered_at = coalesce(delivered_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status IN (?, ?, ?)
                    """,
                    ([MessageStatus.delivered.rawValue, now, conversationId.rawValue, messageId] as [any SQLBindable])
                        + upgradable.map { $0 as any SQLBindable })
            case .read:
                try tx.connection.run(
                    """
                    UPDATE messages SET status = ?, read_at = coalesce(read_at, ?),
                        delivered_at = coalesce(delivered_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND status IN (?, ?, ?, ?)
                    """,
                    ([MessageStatus.read.rawValue, now, now, conversationId.rawValue, messageId] as [any SQLBindable])
                        + (upgradable + [MessageStatus.delivered.rawValue]).map { $0 as any SQLBindable })
            case .viewed:
                try tx.connection.run(
                    """
                    UPDATE messages SET viewed_at = coalesce(viewed_at, ?)
                    WHERE conversation_id = ? AND message_id = ? AND outgoing = 1 AND viewed_at IS NULL
                    """, [now, conversationId.rawValue, messageId])
            case .saved:
                // Only for our own photo, video or file to this contact that
                // can be saved at all (§8.4); ignored for anything else.
                guard let message = try? get(messageId, in: conversationId, now: time, includeExpired: true),
                    message.isOutgoing, !message.isDeleted, !message.isViewOnce, message.expireTimerSeconds == 0,
                    let content = message.content, ReceiptPolicy.isSavable(content)
                else { continue }
                try tx.connection.run(
                    "UPDATE messages SET saved_by_peer_at = ? WHERE id = ? AND saved_by_peer_at IS NULL",
                    [now, message.localId])
            case .unspecified, .UNRECOGNIZED:
                continue
            }
            if tx.connection.changes > 0 { changed.append(messageId) }
        }
        return changed
    }

    /// Incoming messages were displayed: sets `displayedAt` once and starts
    /// their disappearing timers (§8.1). Returns the ids displayed for the
    /// first time (the candidates for an optional read receipt).
    @discardableResult
    public func markDisplayed(_ messageIds: [Data], in conversationId: ConversationId, at time: Date = Date())
        throws -> [Data]
    {
        try tx.requireWritable()
        let now = time.storageMilliseconds
        var displayed: [Data] = []
        for messageId in messageIds {
            try tx.connection.run(
                """
                UPDATE messages SET displayed_at = ?,
                    expires_at = CASE WHEN expire_timer > 0 THEN ? + expire_timer * 1000 ELSE expires_at END
                WHERE conversation_id = ? AND message_id = ? AND outgoing = 0 AND displayed_at IS NULL
                """, [now, now, conversationId.rawValue, messageId])
            if tx.connection.changes > 0 { displayed.append(messageId) }
        }
        // A displayed live-location bubble keeps showing while sharing runs.
        for messageId in displayed {
            guard
                let row = try tx.connection.queryOne(
                    "SELECT kind, expire_timer FROM messages WHERE conversation_id = ? AND message_id = ?",
                    [conversationId.rawValue, messageId]),
                row.int("kind") == MessageKind.liveLocation.rawValue, (row.int64("expire_timer") ?? 0) > 0,
                let message = try get(messageId, in: conversationId, includeExpired: true),
                message.kind == .liveLocation, message.expireTimerSeconds > 0,
                case .liveLocation(let live)? = message.content?.payload
            else { continue }
            let deadline = Self.liveLocationDeadline(
                start: now, timer: message.expireTimerSeconds, live: live,
                createdAt: message.createdAt.storageMilliseconds, now: now)
            try tx.connection.run("UPDATE messages SET expires_at = ? WHERE id = ?", [deadline, message.localId])
        }
        return displayed
    }

    /// An incoming voice note was played on this phone: `viewedAt` is set
    /// once. Returns whether it changed (the first play).
    @discardableResult
    public func markPlayed(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws -> Bool {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE messages SET viewed_at = ?
            WHERE conversation_id = ? AND message_id = ? AND outgoing = 0 AND viewed_at IS NULL AND deleted_at IS NULL
            """, [time.storageMilliseconds, conversationId.rawValue, messageId])
        return tx.connection.changes > 0
    }

    /// A photo, video or file was saved outside the app on this phone:
    /// `savedByMeAt` is set once. Only a message that can be saved at all
    /// (§8.4: not view-once, no disappearing timer, not deleted) — anything
    /// else is refused. Returns whether it changed (the first save).
    @discardableResult
    public func markSavedByMe(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws
        -> Bool
    {
        try tx.requireWritable()
        guard let message = try get(messageId, in: conversationId, now: time), !message.isDeleted,
            !message.isViewOnce, message.expireTimerSeconds == 0, let content = message.content,
            ReceiptPolicy.isSavable(content)
        else { throw StorageError.invalidState("not a savable message") }
        try tx.connection.run(
            "UPDATE messages SET saved_by_me_at = ? WHERE id = ? AND saved_by_me_at IS NULL",
            [time.storageMilliseconds, message.localId])
        return tx.connection.changes > 0
    }

    /// Displays every incoming message of the conversation (opening it).
    @discardableResult
    public func markAllDisplayed(in conversationId: ConversationId, at time: Date = Date()) throws -> [Data] {
        let ids = try tx.connection.query(
            """
            SELECT message_id FROM messages
            WHERE conversation_id = ? AND outgoing = 0 AND displayed_at IS NULL AND deleted_at IS NULL
            """, [conversationId.rawValue]
        ).compactMap { $0.blob("message_id") }
        return try markDisplayed(ids, in: conversationId, at: time)
    }

    /// A view-once message was opened: its attachment files and body are
    /// destroyed; the row stays as "viewed".
    public func consumeViewOnce(_ messageId: Data, in conversationId: ConversationId, at time: Date = Date()) throws {
        try tx.requireWritable()
        guard let message = try get(messageId, in: conversationId, includeExpired: true), message.isViewOnce else {
            throw StorageError.invalidState("not a view-once message")
        }
        try tx.attachments.deleteAll(for: [messageId], in: conversationId)
        try tx.connection.run(
            """
            UPDATE messages SET body = NULL, viewed_at = coalesce(viewed_at, ?), displayed_at = coalesce(displayed_at, ?)
            WHERE conversation_id = ? AND message_id = ?
            """, [time.storageMilliseconds, time.storageMilliseconds, conversationId.rawValue, messageId])
        try scrubQuotePreviews(of: [messageId], in: conversationId)
        // The descriptor (media key, caption) must not survive in the WAL.
        tx.checkpointAfterCommit()
    }

    /// Sent messages without a delivery receipt for 14 days become *Not
    /// delivered* (§5.4). Returns how many changed.
    @discardableResult
    public func markUndelivered(now: Date = Date()) throws -> Int {
        try tx.requireWritable()
        let cutoff = now.addingTimeInterval(-Self.undeliveredAfter).storageMilliseconds
        try tx.connection.run(
            """
            UPDATE messages SET status = ?, not_delivered_at = ?
            WHERE outgoing = 1 AND status = ? AND accepted_at <= ?
            """,
            [MessageStatus.notDelivered.rawValue, now.storageMilliseconds, MessageStatus.sent.rawValue, cutoff])
        return tx.connection.changes
    }

    /// The earliest disappearing deadline still ahead (for an in-app timer).
    public func nextExpiry() throws -> Date? {
        try tx.connection.queryOne("SELECT min(expires_at) AS t FROM messages WHERE expires_at IS NOT NULL")?
            .date("t")
    }

    // MARK: Rows

    func message(from row: Row, key: Data) throws -> StoredMessage {
        guard let localId = row.int64("id"),
            let conversationBytes = row.blob("conversation_id"),
            let conversationId = ConversationId(rawValue: conversationBytes),
            let messageId = row.blob("message_id"),
            let sender = row.blob("sender").flatMap(AccountId.init(bytes:)),
            let status = row.int("status").flatMap(MessageStatus.init(rawValue:))
        else { throw StorageError.integrity }
        var content: BRPDataMessage?
        if let body = row.blob("body") {
            let plaintext = try tx.open(body, itemId: messageId, key: key)
            do {
                content = try BRPDataMessage(serializedBytes: plaintext)
            } catch {
                throw StorageError.integrity
            }
        }
        return StoredMessage(
            localId: localId, conversationId: conversationId, messageId: messageId, isOutgoing: row.bool("outgoing"),
            sender: sender, kind: row.int("kind").flatMap(MessageKind.init(rawValue:)) ?? .unknown,
            content: content,
            sentAt: row.date("sent_at") ?? Date(timeIntervalSince1970: 0),
            createdAt: row.date("created_at") ?? Date(timeIntervalSince1970: 0),
            serverTimestamp: row.date("server_timestamp"), status: status,
            timestamps: MessageTimestamps(
                acceptedAt: row.date("accepted_at"), deliveredAt: row.date("delivered_at"),
                readAt: row.date("read_at"), viewedAt: row.date("viewed_at"),
                notDeliveredAt: row.date("not_delivered_at"), failedAt: row.date("failed_at"),
                displayedAt: row.date("displayed_at"), savedByPeerAt: row.date("saved_by_peer_at"),
                savedByMeAt: row.date("saved_by_me_at")),
            expireTimerSeconds: UInt32(clamping: row.int64("expire_timer") ?? 0),
            expiresAt: row.date("expires_at"), isViewOnce: row.bool("view_once"),
            quoteTarget: row.blob("quote_target"), liveSessionId: row.blob("live_session"),
            deletedAt: row.date("deleted_at"))
    }
}

extension BRPDataMessage {
    /// A view-once item carries nothing that can be seen without opening it:
    /// no inline thumbnail, no caption, no waveform. Applied to what is
    /// sent and to what is stored, in both directions.
    public func strippingViewOncePreview() -> BRPDataMessage {
        guard viewOnce else { return self }
        var data = self
        switch data.payload {
        case .attachment(var attachment):
            attachment.thumbnail = Data()
            attachment.caption = ""
            attachment.waveform = Data()
            data.payload = .attachment(attachment)
        case .sticker(var sticker) where sticker.hasCustom:
            sticker.custom.thumbnail = Data()
            sticker.custom.caption = ""
            sticker.custom.waveform = Data()
            data.payload = .sticker(sticker)
        default:
            break
        }
        return data
    }
}
