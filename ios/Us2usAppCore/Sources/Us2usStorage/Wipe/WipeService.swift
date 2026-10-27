// us2us — AGPL-3.0-only

import Foundation
import Us2usKit

/// Ghost-mode and disappearing-message wipes and garbage collection
/// (PROTOCOL.md §5.3, §8.1, §8.2).
///
/// A conversation is always destroyed in this order:
/// 1. **crypto-shred**: delete its key slot (irreversible, instant; from here
///    on every item under its `CK` is unreadable),
/// 2. delete its rows (messages, reactions, attachments, pending sends),
/// 3. delete its files after the transaction committed.
///
/// A crash after step 1 leaves only ciphertext, which the next pass collects.
enum WipeService {
    /// Wipes one conversation (inside an open write transaction).
    @discardableResult
    static func wipe(
        _ conversation: Conversation, reason: WipeReason, in tx: StorageTransaction, now: Date = Date(),
        notifyContact: Bool = false
    ) throws -> WipedConversation {
        try tx.requireWritable()
        try tx.storage.keyring.destroy(conversation.slotId)
        var itemIds: [Data] = []
        for row in try tx.connection.query(
            "SELECT id, thumbnail_id FROM attachments WHERE conversation_id = ?", [conversation.id.rawValue])
        {
            if let id = row.blob("id") { itemIds.append(id) }
            if let thumbnail = row.blob("thumbnail_id") { itemIds.append(thumbnail) }
        }
        // Cascades to messages, reactions, attachments, pending deletes,
        // removed-message ids and the conversation's outbox entries.
        try tx.connection.run("DELETE FROM conversations WHERE id = ?", [conversation.id.rawValue])
        tx.attachments.removeFilesAfterCommit(itemIds)
        // Also when called inside an outer write (a received wipe, "delete
        // for both"): the rows' old page images must not stay in the WAL.
        tx.checkpointAfterCommit()

        let ghost = conversation.settings.ghost
        let isGhostWipe = reason == .ghostTimeout || (reason == .shreddedEarlier && ghost.enabled)
        let wipeContact = isGhostWipe && ghost.enabled && ghost.wipeContact
        let contactState = try tx.contacts.requestState(of: conversation.contact)
        if notifyContact, isGhostWipe, let contactState, contactState != .blocked {
            var wipe = BRPConversationWipe()
            wipe.requestedAt = now.millisecondsSince1970
            wipe.reason = .ghostTimeout
            try tx.outbox.enqueue(BRPContent(control: .wipe(wipe)), to: conversation.contact, now: now)
        }
        if wipeContact, let contactState {
            if contactState == .blocked {
                // "Leave no trace" must not lift a block: keep only the block itself.
                try tx.contacts.stripToBlock(conversation.contact)
            } else {
                // Hidden now; forgotten (contact, session, identity) once the
                // ConversationWipe left (OutboxRepository.markSent) — or right
                // away if there is nothing to send.
                try tx.contacts.markPendingForget(conversation.contact)
                let pending = try tx.connection.queryOne(
                    "SELECT 1 FROM outbox WHERE recipient = ? AND state != ? LIMIT 1",
                    [conversation.contact.bytes, OutboxState.failed.rawValue])
                if pending == nil { try tx.contacts.forget(conversation.contact) }
            }
        }
        return WipedConversation(
            conversationId: conversation.id, contact: conversation.contact, reason: reason, wipeContact: wipeContact,
            ghost: isGhostWipe)
    }

    /// One maintenance pass. `collectGarbage` also removes orphaned slots and
    /// files (done on every unlock).
    static func performDueWipes(_ storage: LocalStorage, now: Date, collectGarbage: Bool) throws -> WipeReport {
        // Overdue ghost slots first (the same pass the locked background task runs).
        let shreddedNow = Set((try? storage.keyring.destroyExpiredSlots(now: now)) ?? [])
        var report = WipeReport()
        try storage.write { tx in
            let nowMillis = now.storageMilliseconds
            let conversations = try tx.connection.query(
                """
                SELECT id, contact, slot_id, settings, settings_author, ghost_anchor_at, last_activity_at,
                    ghost_deadline_at, created_at
                FROM conversations
                """
            ).map { try tx.conversations.conversation(from: $0) }
            for conversation in conversations {
                let due = conversation.ghostDeadline.map { $0.storageMilliseconds <= nowMillis } ?? false
                let missing = !storage.keyring.slotExists(conversation.slotId)
                guard due || missing else { continue }
                let reason: WipeReason =
                    due || shreddedNow.contains(conversation.slotId) ? .ghostTimeout : .shreddedEarlier
                report.conversations.append(
                    try wipe(conversation, reason: reason, in: tx, now: now, notifyContact: true))
            }

            // Disappearing messages (§8.1).
            let expired = try tx.connection.query(
                "SELECT conversation_id, message_id FROM messages WHERE expires_at <= ?", [nowMillis])
            var byConversation: [ConversationId: [Data]] = [:]
            for row in expired {
                guard let id = row.blob("conversation_id").flatMap(ConversationId.init(rawValue:)),
                    let messageId = row.blob("message_id")
                else { continue }
                byConversation[id, default: []].append(messageId)
            }
            for (conversationId, messageIds) in byConversation {
                try tx.messages.deleteLocally(messageIds, in: conversationId, at: now)
            }
            report.expiredMessages = byConversation

            if collectGarbage {
                let referencedSlots = Set(
                    try tx.connection.query("SELECT slot_id FROM conversations UNION SELECT slot_id FROM communities").compactMap { $0.blob("slot_id") })
                for slotId in storage.keyring.slotIds().subtracting(referencedSlots) {
                    try storage.keyring.destroy(slotId)
                    report.orphanSlotsRemoved += 1
                }
                var referencedItems = Set<Data>()
                for row in try tx.connection.query("SELECT id, thumbnail_id FROM attachments") {
                    if let id = row.blob("id") { referencedItems.insert(id) }
                    if let thumbnail = row.blob("thumbnail_id") { referencedItems.insert(thumbnail) }
                }
                // Community photos, videos and files (Extension v2) are items too.
                referencedItems.formUnion(try tx.communities.attachmentItemIds())
                for itemId in storage.files.allItemIds().subtracting(referencedItems) {
                    try storage.files.remove(itemId)
                    report.orphanFilesRemoved += 1
                }
                for stray in storage.files.strayFiles() {
                    try FileProtection.removeIfPresent(stray)
                    report.orphanFilesRemoved += 1
                }
                try tx.envelopes.prune(now: now)
                try tx.messages.pruneRemovedIds(now: now)
                try tx.communities.prune(now: now)
            }
            try tx.messages.markUndelivered(now: now)
        }
        if !report.isEmpty { try? storage.database.checkpoint() }
        // Repairs schedule drift (failed after-commit writes, a damaged file).
        try? storage.reconcileGhostSchedule()
        return report
    }
}

extension LocalStorage {
    // MARK: Wipes (§5.3, §8.1, §8.2)

    /// Executes every wipe that is due: ghost deadlines (slot first, rows
    /// second, files last), conversations whose slot was already shredded
    /// (e.g. by the background task while locked), and expired disappearing
    /// messages. With `collectGarbage` it also removes orphaned slots and
    /// item files. Call on unlock (``open(layout:dek:now:)`` does), on
    /// foreground and when ``nextDeadline(now:)`` fires.
    ///
    /// For ghost timeouts a `ConversationWipe{GHOST_TIMEOUT}` to the contact
    /// is put in the outbox (§8.2), conversation-less so nothing of the
    /// conversation survives.
    @discardableResult
    public func performDueWipes(now: Date = Date(), collectGarbage: Bool = true) throws -> WipeReport {
        try WipeService.performDueWipes(self, now: now, collectGarbage: collectGarbage)
    }

    /// Wipes a conversation now: `ConversationWipe` received (pass
    /// `.ghostTimeout` for `GHOST_TIMEOUT`, `.requested` for `USER_REQUEST`)
    /// or the user deleted it. A received wipe is never answered with
    /// another, so nothing is enqueued here.
    @discardableResult
    public func wipeConversation(_ id: ConversationId, reason: WipeReason, now: Date = Date()) throws
        -> WipedConversation?
    {
        // WipeService.wipe checkpoints after the commit, also when this runs
        // inside an outer transaction.
        try write { tx -> WipedConversation? in
            guard let conversation = try tx.conversations.get(id) else { return nil }
            return try WipeService.wipe(conversation, reason: reason, in: tx, now: now)
        }
    }

    /// The earliest ghost deadline or disappearing deadline, for an in-app
    /// timer while unlocked.
    public func nextDeadline() throws -> Date? {
        try read { tx in
            let ghost = try tx.connection.queryOne(
                "SELECT min(ghost_deadline_at) AS t FROM conversations WHERE ghost_deadline_at IS NOT NULL")?
                .date("t")
            let expiry = try tx.messages.nextExpiry()
            return [ghost, expiry].compactMap { $0 }.min()
        }
    }

    /// Rewrites the background schedule from the database (repairs drift
    /// after a crash between the two writes).
    func reconcileGhostSchedule() throws {
        let wanted = try read { tx in
            try tx.connection.query("SELECT slot_id, ghost_deadline_at FROM conversations").reduce(
                into: [Data: Date]()
            ) { result, row in
                if let slotId = row.blob("slot_id"), let deadline = row.date("ghost_deadline_at") {
                    result[slotId] = deadline
                }
            }
        }
        let scheduled = keyring.deadlines()
        for (slotId, deadline) in wanted
        where scheduled[slotId]?.millisecondsSince1970 != deadline.millisecondsSince1970 {
            try keyring.setDeadline(deadline, for: slotId)
        }
        for slotId in scheduled.keys where wanted[slotId] == nil {
            try keyring.setDeadline(nil, for: slotId)
        }
    }

    // MARK: While locked (BGTaskScheduler, app start)

    /// Crypto-shreds conversations whose ghost deadline passed, without the
    /// DEK: reads only the schedule `{slotId, deadline}` and deletes slot
    /// files. Rows and files are collected at the next unlock. Returns the
    /// number of conversations destroyed.
    ///
    /// If the storage is still open in this process (the vault has not
    /// locked yet), the full unlocked pass runs instead, through the open
    /// instance, so there is one writer of the schedule and no cached key
    /// survives.
    @discardableResult
    public static func runBackgroundWipes(layout: StorageLayout, now: Date = Date()) -> Int {
        // Never concurrently with an open in progress (one schedule writer).
        OpenStorages.serialized {
            var open: LocalStorage?
            OpenStorages.forEach(root: layout.root) { if $0.isOpen { open = $0 } }
            if let open {
                let report = try? open.performDueWipes(now: now, collectGarbage: false)
                return report?.conversations.count ?? 0
            }
            // Nothing of this container is open in this process, so no
            // transfer can be using staging: plaintext left by a process that
            // died (crash, jetsam, swiped away) goes now, without a key.
            purgeStaging(layout: layout)
            let slots = KeySlotStore(directory: layout.slotDirectory)
            return ((try? slots.destroyExpiredSlots(now: now)) ?? []).count
        }
    }

    /// When the next background wipe is due (`BGAppRefreshTaskRequest.earliestBeginDate`).
    public static func nextBackgroundWipeDate(layout: StorageLayout) -> Date? {
        let slots = KeySlotStore(directory: layout.slotDirectory)
        return ((try? slots.deadlines()) ?? [:]).values.min()
    }
}

/// Open storages of this process, so a locked-path wipe can evict cached keys.
enum OpenStorages {
    private final class WeakBox: @unchecked Sendable {
        weak var storage: LocalStorage?
        init(_ storage: LocalStorage) { self.storage = storage }
    }

    private static let lock = NSLock()
    /// Serializes opening, so two concurrent opens of one container cannot both succeed.
    private static let openLock = NSLock()
    nonisolated(unsafe) private static var boxes: [WeakBox] = []

    static func serialized<T>(_ body: () throws -> T) rethrows -> T {
        try openLock.withLock(body)
    }

    static func register(_ storage: LocalStorage) {
        lock.withLock {
            boxes.removeAll { $0.storage == nil }
            boxes.append(WeakBox(storage))
        }
    }

    static func forEach(root: URL, _ body: (LocalStorage) -> Void) {
        let storages = lock.withLock { boxes.compactMap(\.storage) }
        for storage in storages where storage.layout.root.standardizedFileURL == root.standardizedFileURL {
            body(storage)
        }
    }
}
