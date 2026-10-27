import Foundation
import Us2usKit

/// A conversation row of the chats list: a community with its latest text
/// or media message (receipts never move it; deletes change its preview).
public struct CommunityChatSummary: Equatable, Sendable, Identifiable {
    public let community: StoredCommunity
    /// The latest message (a placeholder too), if any.
    public let last: CommunityTextItem?
    /// Received text and media messages not shown yet (placeholders never count).
    public let unread: Int
    public var id: AccountId { community.id }

    public init(community: StoredCommunity, last: CommunityTextItem?, unread: Int) {
        self.community = community
        self.last = last
        self.unread = unread
    }
    /// When the community last had text or media (ms), for the list's order.
    public var lastActivity: Int64? { last?.receivedAt }
}

/// The receipts owed to one original sender in one community.
public struct CommunityDueReceipts: Equatable, Sendable {
    public let community: AccountId
    public let sender: AccountId
    public let messageIds: [AccountId]
}

/// A blob this phone uploaded and must delete on the relay.
public struct CommunityMediaDelete: Equatable, Sendable {
    public let community: AccountId
    public let mediaId: Data
    public let deleteToken: Data
    public let attempts: Int
}

/// What a delete for everyone did here.
public enum CommunityDeleteOutcome: Equatable, Sendable {
    /// The message became its placeholder.
    case applied
    /// It already was one.
    case alreadyDeleted
    /// Not here (yet): remembered for 30 days.
    case remembered
}

extension CommunityRepository {
    // MARK: Attachments

    public func attachment(_ id: AccountId, sender: AccountId, messageId: AccountId) throws -> CommunityAttachmentItem? {
        guard let row = try tx.connection.queryOne(
            "SELECT item_id,descriptor,state,has_file FROM community_attachments WHERE community_id=? AND sender=? AND message_id=?",
            [id.bytes, sender.bytes, messageId.bytes])
        else { return nil }
        return try attachment(from: row, key: try key(id))
    }

    func attachmentsByMessage(_ id: AccountId, key: Data) throws -> [String: CommunityAttachmentItem] {
        var result: [String: CommunityAttachmentItem] = [:]
        for row in try tx.connection.query(
            "SELECT sender,message_id,item_id,descriptor,state,has_file FROM community_attachments WHERE community_id=?", [id.bytes]) {
            guard let sender = row.blob("sender").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { throw StorageError.integrity }
            result[sender.string + "/" + message.string] = try attachment(from: row, key: key)
        }
        return result
    }

    private func attachment(from row: Row, key: Data) throws -> CommunityAttachmentItem {
        guard let itemId = row.blob("item_id"), let sealed = row.blob("descriptor"),
              let state = row.int("state").flatMap(CommunityAttachmentState.init(rawValue:))
        else { throw StorageError.integrity }
        let descriptor: BRPAttachment
        do {
            descriptor = try BRPAttachment(serializedBytes: try tx.open(sealed, itemId: itemId, key: key))
        } catch {
            throw StorageError.integrity
        }
        return CommunityAttachmentItem(itemId: itemId, descriptor: descriptor, state: state, hasFile: row.bool("has_file"))
    }

    public func setAttachmentState(_ state: CommunityAttachmentState, community id: AccountId, sender: AccountId, messageId: AccountId, now: Date = Date()) throws {
        try tx.requireWritable()
        try tx.connection.run(
            "UPDATE community_attachments SET state=?, updated_at=? WHERE community_id=? AND sender=? AND message_id=?",
            [state.rawValue, now.storageMilliseconds, id.bytes, sender.bytes, messageId.bytes])
    }

    /// Encrypts `plaintext` as the attachment's file (own media before its
    /// upload, or a finished download). `false` when the message is gone.
    @discardableResult
    public func storeAttachmentFile(_ plaintext: Data, community id: AccountId, sender: AccountId, messageId: AccountId, now: Date = Date()) throws -> Bool {
        try tx.requireWritable()
        guard let item = try attachment(id, sender: sender, messageId: messageId) else { return false }
        try tx.storage.files.write(plaintext, itemId: item.itemId, key: try key(id))
        let state: CommunityAttachmentState = item.state.isLocal && item.state != .downloaded ? item.state : .downloaded
        try tx.connection.run(
            "UPDATE community_attachments SET has_file=1, state=?, updated_at=? WHERE community_id=? AND sender=? AND message_id=?",
            [state.rawValue, now.storageMilliseconds, id.bytes, sender.bytes, messageId.bytes])
        return true
    }

    /// The plaintext file at `url` (a staging file) becomes the attachment's
    /// encrypted file; the plaintext is deleted either way.
    @discardableResult
    public func importAttachmentFile(at url: URL, community id: AccountId, sender: AccountId, messageId: AccountId, now: Date = Date()) throws -> Bool {
        defer { try? FileManager.default.removeItem(at: url) }
        let plaintext: Data
        do {
            plaintext = try Data(contentsOf: url, options: .uncached)
        } catch {
            throw StorageError.io("staging file unreadable")
        }
        return try storeAttachmentFile(plaintext, community: id, sender: sender, messageId: messageId, now: now)
    }

    public func readAttachmentFile(community id: AccountId, sender: AccountId, messageId: AccountId) throws -> Data {
        guard let item = try attachment(id, sender: sender, messageId: messageId), item.hasFile else { throw StorageError.notFound }
        return try tx.storage.files.read(item.itemId, key: try key(id))
    }

    /// The decrypted file in a staging URL (an upload); delete it when done.
    public func exportAttachmentToStaging(community id: AccountId, sender: AccountId, messageId: AccountId) throws -> URL {
        let plaintext = try readAttachmentFile(community: id, sender: sender, messageId: messageId)
        let url = tx.storage.files.newStagingURL()
        try FileProtection.writeAtomically(plaintext, to: url, protection: .completeFileProtection)
        return url
    }

    /// The upload finished: the shared pointer (the token is stripped) and
    /// the sender's own delete token, sealed, for 14 days.
    public func setUploaded(_ uploaded: BRPAttachment, community id: AccountId, sender: AccountId, messageId: AccountId, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let item = try attachment(id, sender: sender, messageId: messageId) else { throw StorageError.notFound }
        let key = try key(id)
        let pointer = uploaded.communityPointer
        let token = uploaded.deleteToken.isEmpty ? nil : try tx.seal(uploaded.deleteToken, itemId: uploaded.mediaID, key: key)
        try tx.connection.run(
            """
            UPDATE community_attachments SET descriptor=?, delete_token=?, state=?, uploaded_at=?, updated_at=?
            WHERE community_id=? AND sender=? AND message_id=?
            """,
            [try tx.seal(try pointer.serializedBytes(), itemId: item.itemId, key: key), token, CommunityAttachmentState.uploaded.rawValue,
             now.storageMilliseconds, now.storageMilliseconds, id.bytes, sender.bytes, messageId.bytes])
    }

    /// Own media messages still being sent whose blob is not uploaded or
    /// whose fanout was never prepared (after a crash or a failed upload).
    public func unsentMedia(sender: AccountId) throws -> [(community: AccountId, messageId: AccountId, state: CommunityAttachmentState)] {
        try tx.connection.query(
            """
            SELECT a.community_id, a.message_id, a.state FROM community_attachments a
            JOIN community_messages m ON m.community_id=a.community_id AND m.sender=a.sender AND m.message_id=a.message_id
            JOIN communities c ON c.id=a.community_id AND c.active=1
            WHERE a.sender=? AND m.outgoing=1 AND m.status=? AND m.deleted_by IS NULL
              AND NOT EXISTS (SELECT 1 FROM community_recipients r WHERE r.community_id=a.community_id AND r.message_id=a.message_id)
            ORDER BY m.received_at
            """, [sender.bytes, CommunityDeliveryStatus.sending.rawValue]
        ).compactMap { row in
            guard let group = row.blob("community_id").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)),
                  let state = row.int("state").flatMap(CommunityAttachmentState.init(rawValue:)) else { return nil }
            return (group, message, state)
        }
    }

    /// Attachment rows and their files go (files after the commit).
    func removeAttachments(_ condition: String, _ values: [any SQLBindable]) throws {
        let rows = try tx.connection.query("SELECT item_id FROM community_attachments WHERE \(condition)", values)
        let items = rows.compactMap { $0.blob("item_id") }
        try tx.connection.run("DELETE FROM community_attachments WHERE \(condition)", values)
        guard !items.isEmpty else { return }
        let files = tx.storage.files
        tx.afterCommit { for item in items { try? files.remove(item) } }
    }

    // MARK: Recipients and receipts

    /// The recipient set of an own message, fixed when it is sent.
    public func setRecipients(_ recipients: [AccountId: CommunityRecipientState], community id: AccountId, messageId: AccountId) throws {
        try tx.requireWritable()
        for (recipient, state) in recipients {
            try tx.connection.run(
                "INSERT OR REPLACE INTO community_recipients (community_id,message_id,recipient,state) VALUES (?,?,?,?)",
                [id.bytes, messageId.bytes, recipient.bytes, state.rawValue])
        }
    }

    /// Every recipient of an own message with its transport state and receipt times.
    public func recipients(community id: AccountId, messageId: AccountId) throws -> [CommunityRecipientItem] {
        try tx.connection.query(
            "SELECT recipient,state,delivered_at,read_at FROM community_recipients WHERE community_id=? AND message_id=? ORDER BY recipient",
            [id.bytes, messageId.bytes]
        ).map { row in
            guard let recipient = row.blob("recipient").flatMap(AccountId.init(bytes:)),
                  let state = row.int("state").flatMap(CommunityRecipientState.init(rawValue:)) else { throw StorageError.integrity }
            return CommunityRecipientItem(recipient: recipient, state: state, deliveredAt: row.int64("delivered_at"), readAt: row.int64("read_at"))
        }
    }

    func receiptSummaries(_ id: AccountId, message: AccountId? = nil) throws -> [AccountId: CommunityReceiptSummary] {
        var sql = """
            SELECT message_id,
                SUM(state=1) AS accepted, SUM(state=0) AS pending,
                SUM(state=1 AND delivered_at IS NOT NULL) AS delivered, SUM(state=1 AND read_at IS NOT NULL) AS read
            FROM community_recipients WHERE community_id=?
            """
        var values: [any SQLBindable] = [id.bytes]
        if let message {
            sql += " AND message_id=?"
            values.append(message.bytes)
        }
        sql += " GROUP BY message_id"
        var result: [AccountId: CommunityReceiptSummary] = [:]
        for row in try tx.connection.query(sql, values) {
            guard let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { continue }
            result[message] = CommunityReceiptSummary(
                accepted: row.int("accepted") ?? 0, delivered: row.int("delivered") ?? 0, read: row.int("read") ?? 0,
                pending: row.int("pending") ?? 0)
        }
        return result
    }

    /// Applies a receipt from `member`: only to text and media messages this
    /// phone (`own`) sent in the community, not deleted, whose recipient set
    /// holds `member`. Times are set once and never move back. Returns how
    /// many ids changed something.
    @discardableResult
    public func applyReceipt(
        _ type: BRPCommunityReceipt.TypeEnum, ids: [Data], from member: AccountId, own: AccountId, community id: AccountId, now: Date = Date()
    ) throws -> Int {
        try tx.requireWritable()
        guard type == .delivery || type == .read else { return 0 }
        let time = now.storageMilliseconds
        var changed = 0
        for messageId in Set(ids) where messageId.count == RandomId.byteCount {
            guard try tx.connection.queryOne(
                "SELECT 1 AS x FROM community_messages WHERE community_id=? AND sender=? AND message_id=? AND outgoing=1 AND deleted_by IS NULL",
                [id.bytes, own.bytes, messageId]) != nil
            else { continue }
            // A receipt shows the relay accepted it, even if this phone did not hear so.
            let sql = type == .read
                ? """
                  UPDATE community_recipients SET read_at=coalesce(read_at,?), delivered_at=coalesce(delivered_at,?),
                      state=CASE WHEN state=0 THEN 1 ELSE state END
                  WHERE community_id=? AND message_id=? AND recipient=? AND (read_at IS NULL OR delivered_at IS NULL)
                  """
                : """
                  UPDATE community_recipients SET delivered_at=coalesce(delivered_at,?), state=CASE WHEN state=0 THEN 1 ELSE state END
                  WHERE community_id=? AND message_id=? AND recipient=? AND delivered_at IS NULL
                  """
            let values: [any SQLBindable] = type == .read
                ? [time, time, id.bytes, messageId, member.bytes] : [time, id.bytes, messageId, member.bytes]
            try tx.connection.run(sql, values)
            changed += tx.connection.changes > 0 ? 1 : 0
        }
        return changed
    }

    /// Received messages shown in the open conversation. With read receipts
    /// on, each one not deleted owes its sender a READ (once); messages read
    /// while they are off never get one later. Returns how many were new.
    @discardableResult
    public func markDisplayed(community id: AccountId, readReceipts: Bool, now: Date = Date()) throws -> Int {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE community_messages SET displayed_at=?, read_due=CASE WHEN ? AND deleted_by IS NULL THEN 1 ELSE 0 END
            WHERE community_id=? AND outgoing=0 AND displayed_at IS NULL
            """, [now.storageMilliseconds, readReceipts, id.bytes])
        return tx.connection.changes
    }

    /// The DELIVERY (or READ) receipts owed, per community and original sender.
    public func dueReceipts(_ type: BRPCommunityReceipt.TypeEnum) throws -> [CommunityDueReceipts] {
        let column = type == .read ? "read_due" : "delivery_due"
        var groups: [String: (AccountId, AccountId, [AccountId])] = [:]
        var order: [String] = []
        for row in try tx.connection.query(
            "SELECT community_id,sender,message_id FROM community_messages WHERE \(column)=1 AND outgoing=0 ORDER BY received_at, sender, message_id") {
            guard let group = row.blob("community_id").flatMap(AccountId.init(bytes:)), let sender = row.blob("sender").flatMap(AccountId.init(bytes:)),
                  let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { continue }
            let key = group.string + "/" + sender.string
            if groups[key] == nil { order.append(key); groups[key] = (group, sender, []) }
            groups[key]?.2.append(message)
        }
        return order.compactMap { groups[$0] }.map { CommunityDueReceipts(community: $0.0, sender: $0.1, messageIds: $0.2) }
    }

    /// The receipts for these messages are queued (or dropped): no longer owed.
    public func clearDue(_ type: BRPCommunityReceipt.TypeEnum, community id: AccountId, sender: AccountId, messageIds: [AccountId]) throws {
        try tx.requireWritable()
        let column = type == .read ? "read_due" : "delivery_due"
        for message in messageIds {
            try tx.connection.run(
                "UPDATE community_messages SET \(column)=0 WHERE community_id=? AND sender=? AND message_id=?",
                [id.bytes, sender.bytes, message.bytes])
        }
    }

    // MARK: Deletes

    /// Delete for everyone of (`targetSender`, `targetId`): a text or media
    /// message becomes its placeholder (text, pointer, files, receipt state
    /// and envelopes not accepted yet all go); a placeholder stays as it is;
    /// a message not here yet is remembered for 30 days (at most 1000 per
    /// community). When `own` is the target's sender, its blob is queued for
    /// deletion with its token.
    @discardableResult
    public func applyDelete(
        community id: AccountId, targetSender: AccountId, targetId: AccountId, by role: CommunityDeletion, own: AccountId, now: Date = Date()
    ) throws -> CommunityDeleteOutcome {
        try tx.requireWritable()
        let key: [any SQLBindable] = [id.bytes, targetSender.bytes, targetId.bytes]
        guard let row = try tx.connection.queryOne(
            "SELECT deleted_by FROM community_messages WHERE community_id=? AND sender=? AND message_id=?", key)
        else {
            try remember(community: id, sender: targetSender, messageId: targetId, role: role.rawValue, now: now)
            return .remembered
        }
        if row.int64("deleted_by") != nil { return .alreadyDeleted }
        if targetSender == own { try queueBlobDelete(community: id, sender: targetSender, messageId: targetId, now: now) }
        try removeAttachments("community_id=? AND sender=? AND message_id=?", key)
        try tx.connection.run(
            """
            UPDATE community_messages SET sticker=NULL, body=?, deleted_by=?, read_due=0, displayed_at=coalesce(displayed_at,?)
            WHERE community_id=? AND sender=? AND message_id=?
            """, [Data(), role.rawValue, now.storageMilliseconds] + key)
        if targetSender == own {
            // Its envelopes not accepted yet are not sent any more.
            try tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", [id.bytes, targetId.bytes])
            try tx.connection.run("DELETE FROM community_recipients WHERE community_id=? AND message_id=?", [id.bytes, targetId.bytes])
        }
        tx.checkpointAfterCommit()
        return .applied
    }

    /// "Delete for me": the message, its file and receipt state go from this
    /// phone; its key stays 30 days so a retransmission is a duplicate.
    /// Nothing is sent and no blob is deleted.
    public func deleteForMe(community id: AccountId, sender: AccountId, messageId: AccountId, now: Date = Date()) throws {
        try tx.requireWritable()
        let key: [any SQLBindable] = [id.bytes, sender.bytes, messageId.bytes]
        let outgoing = try tx.connection.queryOne(
            "SELECT outgoing FROM community_messages WHERE community_id=? AND sender=? AND message_id=?", key)?.bool("outgoing") ?? false
        try removeAttachments("community_id=? AND sender=? AND message_id=?", key)
        try tx.connection.run("DELETE FROM community_messages WHERE community_id=? AND sender=? AND message_id=?", key)
        if outgoing {
            try tx.connection.run("DELETE FROM community_recipients WHERE community_id=? AND message_id=?", [id.bytes, messageId.bytes])
        }
        try tx.connection.run(
            "INSERT OR REPLACE INTO community_tombstones (community_id,sender,message_id,role,created_at) VALUES (?,?,?,0,?)",
            key + [now.storageMilliseconds])
        tx.checkpointAfterCommit()
    }

    /// Whether a delete for everyone of this message is waiting for it.
    public func pendingDelete(community id: AccountId, sender: AccountId, messageId: AccountId) throws -> CommunityDeletion? {
        try tx.connection.queryOne(
            "SELECT role FROM community_tombstones WHERE community_id=? AND sender=? AND message_id=?",
            [id.bytes, sender.bytes, messageId.bytes])?.int("role").flatMap(CommunityDeletion.init(rawValue:))
    }

    private func remember(community id: AccountId, sender: AccountId, messageId: AccountId, role: Int, now: Date) throws {
        try tx.connection.run(
            "INSERT OR IGNORE INTO community_tombstones (community_id,sender,message_id,role,created_at) VALUES (?,?,?,?,?)",
            [id.bytes, sender.bytes, messageId.bytes, role, now.storageMilliseconds])
        // At most 1000 pending deletes per community: the oldest go first.
        let count = try tx.connection.queryOne(
            "SELECT COUNT(*) AS n FROM community_tombstones WHERE community_id=? AND role>0", [id.bytes])?.int("n") ?? 0
        if count > Self.maxPendingDeletes {
            for row in try tx.connection.query(
                "SELECT sender,message_id FROM community_tombstones WHERE community_id=? AND role>0 ORDER BY created_at, sender, message_id LIMIT ?",
                [id.bytes, count - Self.maxPendingDeletes]) {
                guard let sender = row.blob("sender"), let message = row.blob("message_id") else { continue }
                try tx.connection.run(
                    "DELETE FROM community_tombstones WHERE community_id=? AND sender=? AND message_id=?", [id.bytes, sender, message])
            }
        }
    }

    /// Own media deleted for everyone: its blob goes with the kept token.
    private func queueBlobDelete(community id: AccountId, sender: AccountId, messageId: AccountId, now: Date) throws {
        guard let row = try tx.connection.queryOne(
            "SELECT delete_token, descriptor, item_id FROM community_attachments WHERE community_id=? AND sender=? AND message_id=?",
            [id.bytes, sender.bytes, messageId.bytes]),
              let sealedToken = row.blob("delete_token"), let itemId = row.blob("item_id"), let sealed = row.blob("descriptor")
        else { return }
        let key = try key(id)
        guard let pointer = try? BRPAttachment(serializedBytes: try tx.open(sealed, itemId: itemId, key: key)),
              pointer.mediaID.count == RandomId.byteCount
        else { return }
        let token = try tx.open(sealedToken, itemId: pointer.mediaID, key: key)
        try tx.connection.run(
            "INSERT OR REPLACE INTO community_media_deletes (media_id,community_id,token,created_at) VALUES (?,?,?,?)",
            [pointer.mediaID, id.bytes, try tx.seal(token, itemId: pointer.mediaID, key: key), now.storageMilliseconds])
    }

    /// Blobs waiting to be deleted on the relay.
    public func pendingMediaDeletes() throws -> [CommunityMediaDelete] {
        try tx.connection.query("SELECT media_id,community_id,token,attempts FROM community_media_deletes ORDER BY created_at").compactMap { row in
            guard let media = row.blob("media_id"), let group = row.blob("community_id").flatMap(AccountId.init(bytes:)), let sealed = row.blob("token"),
                  let token = try? tx.open(sealed, itemId: media, key: try key(group)) else { return nil }
            return CommunityMediaDelete(community: group, mediaId: media, deleteToken: token, attempts: row.int("attempts") ?? 0)
        }
    }

    /// The relay answered (204 or 404): the token is forgotten.
    public func finishMediaDelete(_ mediaId: Data) throws {
        try tx.requireWritable()
        try tx.connection.run("DELETE FROM community_media_deletes WHERE media_id=?", [mediaId])
    }

    /// A network failure: tried again later, given up after `limit` attempts.
    public func retryMediaDelete(_ mediaId: Data, limit: Int = 10) throws {
        try tx.requireWritable()
        try tx.connection.run("UPDATE community_media_deletes SET attempts=attempts+1 WHERE media_id=?", [mediaId])
        try tx.connection.run("DELETE FROM community_media_deletes WHERE media_id=? AND attempts>=?", [mediaId, limit])
    }

    // MARK: Chats list

    /// Every community with its latest message and unread count.
    public func summaries() throws -> [CommunityChatSummary] {
        try all().map { group in
            guard let key = try? key(group.id) else { return CommunityChatSummary(community: group, last: nil, unread: 0) }
            var last: CommunityTextItem?
            if let row = try tx.connection.queryOne(
                """
                SELECT \(Self.messageColumns) FROM community_messages WHERE community_id=?
                ORDER BY received_at DESC, sender DESC, message_id DESC LIMIT 1
                """, [group.id.bytes]) {
                var item = try message(from: row, community: group.id, key: key)
                if item.deletion == nil { item.attachment = try attachment(group.id, sender: item.sender, messageId: item.messageId) }
                if item.outgoing, item.deletion == nil { item.receipts = try receiptSummaries(group.id, message: item.messageId)[item.messageId] }
                last = item
            }
            let unread = try tx.connection.queryOne(
                "SELECT COUNT(*) AS n FROM community_messages WHERE community_id=? AND outgoing=0 AND displayed_at IS NULL AND deleted_by IS NULL",
                [group.id.bytes])?.int("n") ?? 0
            return CommunityChatSummary(community: group, last: last, unread: unread)
        }
    }

    // MARK: Retention

    /// Tombstones after 30 days, delete tokens after 14 (the blob is gone by then).
    public func prune(now: Date = Date()) throws {
        try tx.requireWritable()
        let time = now.storageMilliseconds
        try tx.connection.run("DELETE FROM community_tombstones WHERE created_at < ?", [time - Self.tombstoneLifetime])
        try tx.connection.run(
            "UPDATE community_attachments SET delete_token=NULL WHERE delete_token IS NOT NULL AND uploaded_at < ?",
            [time - Self.tokenLifetime])
        try tx.connection.run("DELETE FROM community_media_deletes WHERE created_at < ?", [time - Self.tokenLifetime])
    }

    /// Item ids of every community attachment file (garbage collection keeps them).
    func attachmentItemIds() throws -> Set<Data> {
        Set(try tx.connection.query("SELECT item_id FROM community_attachments").compactMap { $0.blob("item_id") })
    }
}
