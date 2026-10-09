// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// Conversations (one per contact), their LWW settings and ghost-mode
/// deadlines (PROTOCOL.md §8.2).
///
/// **Ghost deadline**: with ghost mode on and timeout G,
/// `deadline = max(lastActivity, ghostAnchor) + G` exactly (milliseconds),
/// where `lastActivity` is the local time of the latest sent or received
/// `DataMessage` (reactions and deletes count, live-location ticks do not)
/// and `ghostAnchor` the local time ghost mode was enabled or its timeout
/// changed — so enabling it in an old conversation starts a full timeout
/// instead of wiping at once.
///
/// **Schedule ordering**: the deadline is stored in the database and in the
/// background-readable schedule file. When it moves later the schedule is
/// written *before* the transaction commits; when it moves earlier, *after*.
/// So whatever crashes or fails, the background task never wipes earlier than
/// the database says (no premature loss), and the unlocked app — which wipes
/// by the database — never wipes later. ``LocalStorage/open(layout:dek:now:)``
/// rewrites the schedule from the database to repair any drift.
public struct ConversationRepository {
    let tx: StorageTransaction

    private static let columns = """
        id, contact, slot_id, settings, settings_author, ghost_anchor_at, last_activity_at,
        ghost_deadline_at, created_at
        """

    public func get(_ id: ConversationId) throws -> Conversation? {
        try tx.connection.queryOne("SELECT \(Self.columns) FROM conversations WHERE id = ?", [id.rawValue])
            .map(conversation(from:))
    }

    public func forContact(_ account: AccountId) throws -> Conversation? {
        try tx.connection.queryOne("SELECT \(Self.columns) FROM conversations WHERE contact = ?", [account.bytes])
            .map(conversation(from:))
    }

    /// All conversations of visible contacts, most recent activity first.
    public func all() throws -> [Conversation] {
        try tx.connection.query(
            """
            SELECT c.id, c.contact, c.slot_id, c.settings, c.settings_author, c.ghost_anchor_at,
                   c.last_activity_at, c.ghost_deadline_at, c.created_at
            FROM conversations c JOIN contacts k ON k.account = c.contact
            WHERE k.pending_forget = 0
            ORDER BY coalesce(c.last_activity_at, c.created_at) DESC
            """
        ).map(conversation(from:))
    }

    /// The contact's conversation, created with a fresh key slot if needed.
    /// The contact must exist.
    @discardableResult
    public func ensure(for contact: AccountId, now: Date = Date()) throws -> Conversation {
        try tx.requireWritable()
        if let existing = try forContact(contact) { return existing }
        guard try tx.contacts.requestState(of: contact) != nil else { throw StorageError.notFound }
        let id = ConversationId.make()
        // The slot is written first; if the transaction rolls back, the slot
        // is an orphan and garbage-collected at the next open.
        let slot = try tx.storage.keyring.createSlot()
        let settings = BRPConversationSettings()
        try tx.connection.run(
            """
            INSERT INTO conversations (id, contact, slot_id, settings, draft_item_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [id.rawValue, contact.bytes, slot.slotId, try settings.serializedBytes() as Data, RandomId.make(),
             now.storageMilliseconds])
        guard let created = try get(id) else { throw StorageError.notFound }
        return created
    }

    // MARK: Settings (LWW)

    /// How far ahead of our clock a contact's `updated_at` may be. Anything
    /// later is clamped: a far-future stamp (up to `UInt64.max`) would
    /// otherwise win last-writer-wins forever, so the user could never change
    /// the conversation's privacy settings again.
    public static let maxClockSkew: TimeInterval = 24 * 3600

    /// Applies settings received from `author` (last writer wins, brp.proto).
    /// Returns the settings now in effect. An `updated_at` more than
    /// ``maxClockSkew`` ahead of `now` is clamped to that bound.
    @discardableResult
    public func applySettings(
        _ incoming: BRPConversationSettings, author: AccountId, to id: ConversationId, now: Date = Date()
    ) throws -> BRPConversationSettings {
        try tx.requireWritable()
        var incoming = incoming
        let ceiling = now.addingTimeInterval(Self.maxClockSkew).millisecondsSince1970
        if incoming.updatedAt > ceiling { incoming.updatedAt = ceiling }
        guard let conversation = try get(id) else { throw StorageError.notFound }
        let current = conversation.settings
        let resolved: BRPConversationSettings
        if let currentAuthor = conversation.settingsAuthor {
            resolved = BRPConversationSettings.resolve((current, currentAuthor), (incoming, author))
        } else {
            // Nobody wrote settings yet; the defaults lose to anything written.
            resolved = incoming
        }
        guard resolved != current || conversation.settingsAuthor == nil else { return current }
        let winnerAuthor = resolved == incoming ? author : (conversation.settingsAuthor ?? author)
        try store(resolved, author: winnerAuthor, previous: conversation, now: now)
        return resolved
    }

    /// Our own change: stamped `updated_at = now`, authored by us. Returns the
    /// `ConversationSettings` to send to the contact.
    @discardableResult
    public func updateSettings(
        of id: ConversationId, now: Date = Date(), _ change: (inout BRPConversationSettings) -> Void
    ) throws -> BRPConversationSettings {
        try tx.requireWritable()
        guard let conversation = try get(id) else { throw StorageError.notFound }
        var settings = conversation.settings
        change(&settings)
        // Saturating: a stored stamp at the maximum must never trap.
        let previous = conversation.settings.updatedAt
        settings.updatedAt = max(now.millisecondsSince1970, previous == .max ? .max : previous + 1)
        if settings.ghost.enabled, !ContentLimits.ghostTimeoutRange.contains(settings.ghost.timeoutSeconds) {
            throw StorageError.invalidState("ghost timeout out of range")
        }
        try store(settings, author: try tx.ownAccountId(), previous: conversation, now: now)
        return settings
    }

    private func store(
        _ settings: BRPConversationSettings, author: AccountId, previous: Conversation, now: Date
    ) throws {
        let old = previous.settings.ghost
        let new = settings.ghost
        var anchor = previous.ghostAnchorAt
        if new.enabled, !old.enabled || old.timeoutSeconds != new.timeoutSeconds {
            anchor = now
        }
        if !new.enabled { anchor = nil }
        try tx.connection.run(
            "UPDATE conversations SET settings = ?, settings_author = ?, ghost_anchor_at = ? WHERE id = ?",
            [try settings.serializedBytes() as Data, author.bytes, anchor?.storageMilliseconds,
             previous.id.rawValue])
        try updateDeadline(
            of: previous, settings: settings, lastActivity: previous.lastActivityAt, anchor: anchor)
    }

    // MARK: Activity and ghost deadlines

    /// Records a sent or received `DataMessage` at `time` (§8.2) and moves
    /// the ghost deadline. Live-location ticks must not be recorded.
    public func recordActivity(_ id: ConversationId, at time: Date) throws {
        try tx.requireWritable()
        guard let conversation = try get(id) else { throw StorageError.notFound }
        let latest = max(conversation.lastActivityAt ?? time, time)
        try tx.connection.run(
            "UPDATE conversations SET last_activity_at = ? WHERE id = ?", [latest.storageMilliseconds, id.rawValue])
        try updateDeadline(
            of: conversation, settings: conversation.settings, lastActivity: latest, anchor: conversation.ghostAnchorAt)
    }

    /// The exact ghost deadline, or `nil` when ghost mode is off.
    public static func ghostDeadline(
        settings: BRPConversationSettings, lastActivity: Date?, anchor: Date?
    ) -> Date? {
        guard settings.ghost.enabled, ContentLimits.ghostTimeoutRange.contains(settings.ghost.timeoutSeconds) else {
            return nil
        }
        let starts = [lastActivity, anchor].compactMap { $0?.storageMilliseconds }
        guard let start = starts.max() else { return nil }
        return Date(millisecondsSince1970: start + Int64(settings.ghost.timeoutSeconds) * 1000)
    }

    private func updateDeadline(
        of conversation: Conversation, settings: BRPConversationSettings, lastActivity: Date?, anchor: Date?
    ) throws {
        let deadline = Self.ghostDeadline(settings: settings, lastActivity: lastActivity, anchor: anchor)
        try tx.connection.run(
            "UPDATE conversations SET ghost_deadline_at = ? WHERE id = ?",
            [deadline?.storageMilliseconds, conversation.id.rawValue])
        guard deadline != conversation.ghostDeadline else { return }
        let keyring = tx.storage.keyring
        let slotId = conversation.slotId
        if Self.isLater(deadline, than: conversation.ghostDeadline) {
            try keyring.setDeadline(deadline, for: slotId)
        } else {
            tx.afterCommit { try? keyring.setDeadline(deadline, for: slotId) }
        }
    }

    /// `nil` counts as "never" (later than any date).
    static func isLater(_ lhs: Date?, than rhs: Date?) -> Bool {
        switch (lhs, rhs) {
        case (nil, _): return true
        case (_, nil): return false
        case (let lhs?, let rhs?): return lhs >= rhs
        }
    }

    // MARK: Drafts

    /// The unsent draft, encrypted under `CK` like a message body (§8.2
    /// wipes drafts with the conversation).
    public func setDraft(_ text: String?, for id: ConversationId) throws {
        try tx.requireWritable()
        guard
            let row = try tx.connection.queryOne(
                "SELECT slot_id, draft_item_id FROM conversations WHERE id = ?", [id.rawValue]),
            let slotId = row.blob("slot_id"), let itemId = row.blob("draft_item_id")
        else { throw StorageError.notFound }
        var item: Data?
        if let text, !text.isEmpty {
            item = try tx.seal(Data(text.utf8), itemId: itemId, key: try tx.conversationKey(slotId: slotId))
        }
        try tx.connection.run("UPDATE conversations SET draft = ? WHERE id = ?", [item, id.rawValue])
    }

    public func draft(for id: ConversationId) throws -> String? {
        guard
            let row = try tx.connection.queryOne(
                "SELECT slot_id, draft, draft_item_id FROM conversations WHERE id = ?", [id.rawValue])
        else { throw StorageError.notFound }
        guard let item = row.blob("draft"), let itemId = row.blob("draft_item_id"), let slotId = row.blob("slot_id")
        else { return nil }
        let plaintext = try tx.open(item, itemId: itemId, key: try tx.conversationKey(slotId: slotId))
        return String(decoding: plaintext, as: UTF8.self)
    }

    // MARK: Counts

    /// Incoming messages not displayed yet.
    public func unreadCount(_ id: ConversationId) throws -> Int {
        Int(
            try tx.connection.queryOne(
                """
                SELECT count(*) AS n FROM messages
                WHERE conversation_id = ? AND outgoing = 0 AND displayed_at IS NULL AND deleted_at IS NULL
                """, [id.rawValue])?.int64("n") ?? 0)
    }

    // MARK: Rows

    func conversation(from row: Row) throws -> Conversation {
        guard let idBytes = row.blob("id"), let id = ConversationId(rawValue: idBytes),
            let contactBytes = row.blob("contact"), let contact = AccountId(bytes: contactBytes),
            let slotId = row.blob("slot_id"), let settingsBytes = row.blob("settings"),
            let settings = try? BRPConversationSettings(serializedBytes: settingsBytes)
        else { throw StorageError.integrity }
        return Conversation(
            id: id, contact: contact, slotId: slotId, settings: settings,
            settingsAuthor: row.blob("settings_author").flatMap(AccountId.init(bytes:)),
            lastActivityAt: row.date("last_activity_at"), ghostAnchorAt: row.date("ghost_anchor_at"),
            ghostDeadline: row.date("ghost_deadline_at"),
            createdAt: row.date("created_at") ?? Date(timeIntervalSince1970: 0))
    }
}
