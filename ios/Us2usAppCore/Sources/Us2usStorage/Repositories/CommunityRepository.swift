import Foundation
import Us2usKit

public struct StoredCommunity: Equatable, Sendable, Identifiable {
    public let id: AccountId
    public let name: String
    public let signedState: SignedCommunityState?
    public let ownerKey: Data
    public let isActive: Bool
    public var state: CommunityState? { signedState.flatMap { try? CommunityState(bytes: $0.state) } }

    public init(id: AccountId, name: String, signedState: SignedCommunityState?, ownerKey: Data, isActive: Bool) {
        self.id = id
        self.name = name
        self.signedState = signedState
        self.ownerKey = ownerKey
        self.isActive = isActive
    }
}

/// Where an outgoing community message stands; incoming ones are `received`.
public enum CommunityDeliveryStatus: String, Sendable, Equatable {
    case sending
    /// Accepted by the relay for every member of its revision.
    case sent
    /// Some members had left meanwhile (skipped); the others got it.
    case partial
    /// Stopped (the roster changed or the community is gone), or refused
    /// for some member: “Tekrar gönder” sends it to those who did not get it.
    case failed
    case received
}

/// A stored community message: text or media (Extension v2).
public enum CommunityMessageKind: Int, Sendable, Equatable {
    case text = 0
    case media = 1
    case sticker = 2
}

/// Who deleted a message for everyone: it stays as a placeholder.
public enum CommunityDeletion: Int, Sendable, Equatable {
    case bySender = 1
    case byOwner = 2
}

/// Where a community attachment's file is.
public enum CommunityAttachmentState: Int, Sendable, Equatable {
    /// Own media: the file is here, the blob is not uploaded yet.
    case pendingUpload = 0
    case uploading = 1
    /// Own media: uploaded once; the pointer goes to every member.
    case uploaded = 2
    /// Received: still on the relay.
    case remote = 3
    case downloading = 4
    /// Received and decrypted here.
    case downloaded = 5
    /// The relay no longer has it (404: deleted or older than 14 days).
    case unavailable = 6
    /// The download failed its integrity check, or the upload failed for good.
    case failed = 7

    /// The file is on this phone.
    public var isLocal: Bool { self == .downloaded || self == .pendingUpload || self == .uploading || self == .uploaded }
}

/// A recipient's transport state for an own message.
public enum CommunityRecipientState: Int, Sendable, Equatable {
    /// Sealed and queued; not accepted by the relay yet.
    case pending = 0
    /// The relay accepted the envelope.
    case accepted = 1
    /// No longer a member (404) when it was sent: skipped.
    case skipped = 2
    /// Could not be sent (key mismatch, a refused or stopped send).
    case failed = 3
}

/// A photo, video or file of a community message.
public struct CommunityAttachmentItem: Equatable, Sendable {
    public let itemId: Data
    /// The shared pointer, as every member got it (no delete token).
    public let descriptor: BRPAttachment
    public let state: CommunityAttachmentState
    public let hasFile: Bool

    public init(itemId: Data, descriptor: BRPAttachment, state: CommunityAttachmentState, hasFile: Bool) {
        self.itemId = itemId
        self.descriptor = descriptor
        self.state = state
        self.hasFile = hasFile
    }

    public var kind: BRPAttachment.Kind { descriptor.kind }
    public var contentType: String { descriptor.contentType }
    public var fileName: String? { descriptor.fileName.isEmpty ? nil : descriptor.fileName }
    public var size: UInt64 { descriptor.size }
    public var thumbnail: Data? { descriptor.thumbnail.isEmpty ? nil : descriptor.thumbnail }
}

/// What the receipts of an own message add up to.
public struct CommunityReceiptSummary: Equatable, Sendable {
    /// Recipients the relay accepted the message for.
    public let accepted: Int
    public let delivered: Int
    public let read: Int
    /// Recipients still pending.
    public let pending: Int

    public init(accepted: Int, delivered: Int, read: Int, pending: Int) {
        self.accepted = accepted
        self.delivered = delivered
        self.read = read
        self.pending = pending
    }

    public var deliveredToAll: Bool { accepted > 0 && pending == 0 && delivered >= accepted }
    public var readByAll: Bool { accepted > 0 && pending == 0 && read >= accepted }
}

/// One recipient of an own message (message info).
public struct CommunityRecipientItem: Equatable, Sendable {
    public let recipient: AccountId
    public let state: CommunityRecipientState
    /// Local times (ms) the receipts were applied.
    public let deliveredAt: Int64?
    public let readAt: Int64?

    public init(recipient: AccountId, state: CommunityRecipientState, deliveredAt: Int64?, readAt: Int64?) {
        self.recipient = recipient
        self.state = state
        self.deliveredAt = deliveredAt
        self.readAt = readAt
    }
}

public struct CommunityHistoryCursor: Equatable, Sendable {
    public let receivedAt: Int64
    public let sender: AccountId
    public let messageId: AccountId
}

public struct CommunityHistory: Sendable {
    public let items: [CommunityTextItem]
    public let oldest: CommunityHistoryCursor?
    public let hasOlder: Bool
}

public struct CommunityTextItem: Equatable, Sendable, Identifiable {
    public let communityId: AccountId
    public let sender: AccountId
    public let messageId: AccountId
    /// The text, or a media message's caption; empty for a placeholder.
    public let text: String
    /// The sender's time, clamped to a window around ``receivedAt``.
    public let sentAt: Int64
    /// When this phone stored it (ms); the order of the conversation.
    public let receivedAt: Int64
    public let outgoing: Bool
    public let status: CommunityDeliveryStatus
    public var kind: CommunityMessageKind = .text
    public var sticker: BRPSticker?
    /// Media messages (none once deleted).
    public var attachment: CommunityAttachmentItem?
    /// Deleted for everyone: a placeholder.
    public var deletion: CommunityDeletion?
    /// Incoming: shown in the open conversation.
    public var isDisplayed = true
    /// Own messages: what their recipients' receipts add up to.
    public var receipts: CommunityReceiptSummary?
    public var id: String { sender.string + "/" + messageId.string }

    public init(
        communityId: AccountId, sender: AccountId, messageId: AccountId, text: String, sentAt: Int64, receivedAt: Int64,
        outgoing: Bool, status: CommunityDeliveryStatus, kind: CommunityMessageKind = .text,
        attachment: CommunityAttachmentItem? = nil, deletion: CommunityDeletion? = nil, isDisplayed: Bool = true,
        receipts: CommunityReceiptSummary? = nil
    ) {
        self.communityId = communityId
        self.sender = sender
        self.messageId = messageId
        self.text = text
        self.sentAt = sentAt
        self.receivedAt = receivedAt
        self.outgoing = outgoing
        self.status = status
        self.kind = kind
        self.attachment = attachment
        self.deletion = deletion
        self.isDisplayed = isDisplayed
        self.receipts = receipts
    }
}

public struct CommunityOutboxItem: Sendable, Equatable {
    public let communityId: AccountId
    public let messageId: AccountId
    public let recipient: AccountId
    public let revision: UInt64
    public let envelope: Data
    /// A receipt's or a delete's own content; `nil` for a text or media
    /// message. Under a newer revision it is sealed again for the same member.
    public var event: BRPCommunityMessage?
}

/// What storing a received (or own) text or media message did.
public enum CommunityStoreOutcome: Equatable, Sendable {
    case stored
    /// Already here, or deleted for me (a retransmission).
    case duplicate
    /// A delete for everyone arrived first: stored as its placeholder.
    case placeholder(CommunityDeletion)
}

/// All metadata is in SQLCipher; text additionally uses its community's
/// independently erasable key slot, as one-to-one content does.
public struct CommunityRepository {
    let tx: StorageTransaction

    /// How many messages a conversation shows (the latest ones).
    public static let pageSize = 1000
    /// A sender's clock may differ from ours; its claim is kept within this
    /// window around the local receive time and never decides the order.
    static let clockSkew: Int64 = 60_000
    static let maxAge: Int64 = 15 * 86_400_000
    /// Pending deletes and "delete for me" keys are kept this long.
    public static let tombstoneLifetime: Int64 = 30 * 86_400_000
    /// Pending deletes kept per community (the oldest goes first).
    public static let maxPendingDeletes = 1000
    /// A delete token is kept as long as the relay keeps the blob.
    public static let tokenLifetime: Int64 = 14 * 86_400_000

    public func all() throws -> [StoredCommunity] {
        try tx.connection.query("SELECT id,name,owner_key,signed_state,active FROM communities ORDER BY name").map(decode)
    }
    public func get(_ id: AccountId) throws -> StoredCommunity? {
        try tx.connection.queryOne("SELECT id,name,owner_key,signed_state,active FROM communities WHERE id=?", [id.bytes]).map(decode)
    }
    private func decode(_ row: Row) throws -> StoredCommunity {
        guard let id = row.blob("id").flatMap(AccountId.init(bytes:)), let name = row.text("name"), let ownerKey = row.blob("owner_key") else { throw StorageError.integrity }
        return StoredCommunity(id: id, name: name, signedState: try row.blob("signed_state").map { try JSONDecoder().decode(SignedCommunityState.self, from: $0) }, ownerKey: ownerKey, isActive: row.int64("active") == 1)
    }
    /// Pins the owner identity (creation, a public request or an invitation).
    /// The name is a public name, an invitation's name or empty.
    public func pin(_ id: AccountId, ownerKey: Data, name: String) throws {
        try tx.requireWritable()
        guard ownerKey.count == 33, ownerKey.first == 5, name.isEmpty || CommunityName.isValid(name) else { throw CommunityError.invalidState }
        if let old = try get(id) {
            guard old.ownerKey == ownerKey else { throw CommunityError.identityMismatch }
            return
        }
        let slot = try tx.storage.keyring.createSlot()
        try tx.connection.run("INSERT INTO communities (id,owner_key,name,slot_id) VALUES (?,?,?,?)", [id.bytes, ownerKey, name, slot.slotId])
    }
    /// Stores a verified state: the pinned owner, the last accepted revision
    /// and the members' keys must all agree with it.
    public func save(_ signed: SignedCommunityState, localName: String? = nil) throws {
        try tx.requireWritable()
        let state = try signed.verified()
        let previous = try get(state.id)
        _ = try signed.verified(ownerKey: previous?.ownerKey, previous: previous?.signedState)
        try pin(state.id, ownerKey: state.owner.identityKey, name: state.isPublic ? state.name : (localName ?? previous?.name ?? ""))
        try tx.connection.run("UPDATE communities SET signed_state=?,active=1 WHERE id=?", [try JSONEncoder().encode(signed), state.id.bytes])
    }
    public func markCurrentMemberships(_ ids: Set<AccountId>) throws {
        try tx.requireWritable()
        for group in try all() where group.signedState != nil && !ids.contains(group.id) {
            try deactivate(group.id)
        }
    }
    /// The relay no longer lists us as a member (removed, or the community
    /// is gone). The text history stays readable until the community is
    /// forgotten; media files, delete tokens, receipt state, placeholders'
    /// pending deletes and queued receipts go now.
    public func deactivate(_ id: AccountId) throws {
        try tx.requireWritable()
        guard try tx.connection.queryOne("SELECT active FROM communities WHERE id=? AND active=1", [id.bytes]) != nil else { return }
        try tx.connection.run("UPDATE communities SET active=0 WHERE id=?", [id.bytes])
        // Nothing queued can reach anyone any more.
        for row in try tx.connection.query("SELECT DISTINCT message_id FROM community_outbox WHERE community_id=?", [id.bytes]) {
            guard let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { continue }
            try finish(community: id, message: message, as: .failed)
        }
        try tx.connection.run("DELETE FROM community_outbox WHERE community_id=?", [id.bytes])
        try removeAttachments("community_id=?", [id.bytes])
        try tx.connection.run("DELETE FROM community_recipients WHERE community_id=?", [id.bytes])
        try tx.connection.run("DELETE FROM community_tombstones WHERE community_id=?", [id.bytes])
        try tx.connection.run("DELETE FROM community_media_deletes WHERE community_id=?", [id.bytes])
        try tx.connection.run("UPDATE community_messages SET delivery_due=0, read_due=0 WHERE community_id=?", [id.bytes])
        tx.checkpointAfterCommit()
    }
    /// Forgets a community: its key slot (and with it every message) is
    /// destroyed; its files go after the commit.
    public func forget(_ id: AccountId) throws {
        try tx.requireWritable()
        try removeAttachments("community_id=?", [id.bytes])
        if let slot = try tx.connection.queryOne("SELECT slot_id FROM communities WHERE id=?", [id.bytes])?.blob("slot_id") { try tx.storage.keyring.destroy(slot) }
        try tx.connection.run("DELETE FROM communities WHERE id=?", [id.bytes])
        tx.checkpointAfterCommit()
    }
    func key(_ id: AccountId) throws -> Data {
        guard let slot = try tx.connection.queryOne("SELECT slot_id FROM communities WHERE id=?", [id.bytes])?.blob("slot_id") else { throw StorageError.notFound }
        return try tx.conversationKey(slotId: slot)
    }

    // MARK: Messages

    static let messageColumns = "sender,message_id,body,sent_at,received_at,outgoing,status,kind,deleted_by,displayed_at,sticker"

    /// The latest ``pageSize`` messages in the order this phone stored them
    /// (oldest first); a sender's clock cannot move a message.
    public func messages(_ id: AccountId, limit: Int = pageSize) throws -> [CommunityTextItem] {
        try history(id, limit: limit).items
    }

    public func history(_ id: AccountId, before: CommunityHistoryCursor? = nil,
        through: CommunityHistoryCursor? = nil, limit: Int = 100) throws -> CommunityHistory {
        let key = try key(id)
        var predicate = "community_id=?"
        var arguments: [any SQLBindable] = [id.bytes]
        if let before {
            predicate += " AND (received_at,sender,message_id)<(?,?,?)"
            arguments += [before.receivedAt, before.sender.bytes, before.messageId.bytes]
        }
        if let through {
            predicate += " AND (received_at,sender,message_id)>=(?,?,?)"
            arguments += [through.receivedAt, through.sender.bytes, through.messageId.bytes]
        }
        arguments.append(Int64(through == nil ? max(0, limit) : Int.max))
        let rows = try tx.connection.query(
            "SELECT \(Self.messageColumns) FROM community_messages WHERE \(predicate) ORDER BY received_at DESC,sender DESC,message_id DESC LIMIT ?", arguments)
        let attachments = try attachmentsByMessage(id, key: key)
        let receipts = try receiptSummaries(id)
        let items = try rows.reversed().map { row in
            var item = try message(from: row, community: id, key: key)
            if item.deletion == nil { item.attachment = attachments[item.id] }
            if item.outgoing, item.deletion == nil { item.receipts = receipts[item.messageId] }
            return item
        }
        let oldest = items.first.map { CommunityHistoryCursor(receivedAt: $0.receivedAt, sender: $0.sender, messageId: $0.messageId) }
        let hasOlder: Bool
        if let oldest {
            hasOlder = try tx.connection.queryOne(
                "SELECT 1 FROM community_messages WHERE community_id=? AND (received_at,sender,message_id)<(?,?,?) LIMIT 1",
                [id.bytes, oldest.receivedAt, oldest.sender.bytes, oldest.messageId.bytes]) != nil
        } else { hasOlder = false }
        return CommunityHistory(items: items, oldest: oldest, hasOlder: hasOlder)
    }

    /// One message, as ``messages(_:limit:)`` shows it.
    public func message(_ id: AccountId, sender: AccountId, messageId: AccountId) throws -> CommunityTextItem? {
        let key = try key(id)
        guard let row = try tx.connection.queryOne(
            "SELECT \(Self.messageColumns) FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            [id.bytes, sender.bytes, messageId.bytes])
        else { return nil }
        var item = try message(from: row, community: id, key: key)
        if item.deletion == nil {
            item.attachment = try attachment(id, sender: sender, messageId: messageId)
            if item.outgoing { item.receipts = try receiptSummaries(id, message: messageId)[messageId] }
        }
        return item
    }

    func message(from row: Row, community id: AccountId, key: Data) throws -> CommunityTextItem {
        guard let sender = row.blob("sender").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)),
              let date = row.int64("sent_at"), let received = row.int64("received_at"), let status = row.text("status").flatMap(CommunityDeliveryStatus.init(rawValue:)),
              let outgoing = row.int64("outgoing")
        else { throw StorageError.integrity }
        let deletion = row.int("deleted_by").flatMap(CommunityDeletion.init(rawValue:))
        var text = ""
        if deletion == nil {
            guard let body = row.blob("body"), let opened = String(data: try tx.open(body, itemId: message.bytes, key: key), encoding: .utf8) else { throw StorageError.integrity }
            text = opened
        }
        var item = CommunityTextItem(communityId: id, sender: sender, messageId: message, text: text, sentAt: date, receivedAt: received, outgoing: outgoing != 0, status: status)
        item.kind = row.int("kind").flatMap(CommunityMessageKind.init(rawValue:)) ?? .text
        if deletion == nil, let sealed = row.blob("sticker") {
            item.sticker = try BRPSticker(serializedBytes: tx.open(sealed, itemId: message.bytes, key: key))
        }
        item.deletion = deletion
        item.isDisplayed = outgoing != 0 || row.int64("displayed_at") != nil
        return item
    }

    /// Stores a text or media message (own or received). A delete for
    /// everyone that came first turns it into its placeholder at once; a
    /// message deleted for me (or already here) is a duplicate. A received
    /// message owes its sender a DELIVERY receipt (also as a placeholder).
    @discardableResult
    public func store(_ content: BRPCommunityMessage, sender: AccountId, outgoing: Bool, now: Date = Date()) throws -> CommunityStoreOutcome {
        try tx.requireWritable()
        guard let group = AccountId(bytes: content.communityID), let message = AccountId(bytes: content.messageID), content.sentAt <= UInt64(Int64.max) else { throw CommunityError.invalidState }
        let received = now.storageMilliseconds
        let claimed = Int64(content.sentAt)
        let sentAt = min(max(claimed, received - Self.maxAge), received + Self.clockSkew)
        let kind: CommunityMessageKind = content.hasSticker ? .sticker : (content.hasAttachment ? .media : .text)
        let key = try key(group)
        var deletion: CommunityDeletion?
        if let tombstone = try tx.connection.queryOne(
            "SELECT role FROM community_tombstones WHERE community_id=? AND sender=? AND message_id=?",
            [group.bytes, sender.bytes, message.bytes])?.int("role") {
            guard let role = CommunityDeletion(rawValue: tombstone) else { return .duplicate }
            deletion = role
        }
        if try tx.connection.queryOne(
            "SELECT 1 AS x FROM community_messages WHERE community_id=? AND sender=? AND message_id=?",
            [group.bytes, sender.bytes, message.bytes]) != nil {
            return .duplicate
        }
        let body = deletion == nil ? try tx.seal(Data(content.text.utf8), itemId: message.bytes, key: key) : Data()
        try tx.connection.run(
            """
            INSERT INTO community_messages
                (community_id,sender,message_id,body,sent_at,received_at,outgoing,status,kind,deleted_by,displayed_at,delivery_due)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            [group.bytes, sender.bytes, message.bytes, body, sentAt, received, outgoing ? 1 : 0,
             (outgoing ? CommunityDeliveryStatus.sending : .received).rawValue, kind.rawValue, deletion?.rawValue,
             outgoing || deletion != nil ? received : nil, outgoing ? 0 : 1])
        if let deletion {
            try tx.connection.run(
                "DELETE FROM community_tombstones WHERE community_id=? AND sender=? AND message_id=?",
                [group.bytes, sender.bytes, message.bytes])
            return .placeholder(deletion)
        }
        if content.hasSticker {
            try tx.connection.run(
                "UPDATE community_messages SET sticker=? WHERE community_id=? AND sender=? AND message_id=?",
                [try tx.seal(try content.sticker.serializedBytes(), itemId: message.bytes, key: key), group.bytes, sender.bytes, message.bytes])
        }
        if content.hasAttachment {
            let itemId = RandomId.make()
            let pointer = content.attachment.communityPointer
            try tx.connection.run(
                "INSERT INTO community_attachments (community_id,sender,message_id,item_id,descriptor,state,updated_at) VALUES (?,?,?,?,?,?,?)",
                [group.bytes, sender.bytes, message.bytes, itemId, try tx.seal(try pointer.serializedBytes(), itemId: itemId, key: key),
                 (outgoing ? CommunityAttachmentState.pendingUpload : .remote).rawValue, received])
        }
        return .stored
    }

    public func recordEnvelope(_ id: String) throws {
        try tx.requireWritable()
        try tx.connection.run("INSERT OR IGNORE INTO community_envelopes (id,received_at) VALUES (?,?)", [id, Date().storageMilliseconds])
        try tx.connection.run("DELETE FROM community_envelopes WHERE received_at < ?", [Date().addingTimeInterval(-15 * 86400).storageMilliseconds])
    }
    public func sawEnvelope(_ id: String) throws -> Bool { try tx.connection.queryOne("SELECT id FROM community_envelopes WHERE id=?", [id]) != nil }

    // MARK: Outbox

    public func enqueue(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        let event = try item.event.map { try tx.seal(try $0.serializedBytes(), itemId: item.messageId.bytes, key: try key(item.communityId)) }
        try tx.connection.run(
            "INSERT OR IGNORE INTO community_outbox (community_id,message_id,recipient,revision,envelope,event) VALUES (?,?,?,?,?,?)",
            [item.communityId.bytes, item.messageId.bytes, item.recipient.bytes, Int64(item.revision), item.envelope, event])
    }
    /// Queues one sealed copy; a receipt or delete keeps its `event` content.
    public func enqueue(
        community: AccountId, message: AccountId, recipient: AccountId, revision: UInt64, envelope: Data, event: BRPCommunityMessage? = nil
    ) throws {
        try enqueue(CommunityOutboxItem(
            communityId: community, messageId: message, recipient: recipient, revision: revision, envelope: envelope, event: event))
    }
    /// Queued deliveries: receipts and deletes first, then messages, oldest first.
    public func pending() throws -> [CommunityOutboxItem] {
        var keys: [AccountId: Data] = [:]
        return try tx.connection.query(
            """
            SELECT o.community_id,o.message_id,o.recipient,o.revision,o.envelope,o.event FROM community_outbox o
            LEFT JOIN community_messages m ON m.community_id=o.community_id AND m.message_id=o.message_id AND m.outgoing=1
            ORDER BY m.received_at, o.message_id, o.recipient LIMIT 4950
            """
        ).map { row in
            guard let group = row.blob("community_id").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)), let recipient = row.blob("recipient").flatMap(AccountId.init(bytes:)), let revision = row.int64("revision"), let envelope = row.blob("envelope") else { throw StorageError.integrity }
            var event: BRPCommunityMessage?
            if let sealed = row.blob("event") {
                let key = try keys[group] ?? key(group)
                keys[group] = key
                do {
                    event = try BRPCommunityMessage(serializedBytes: try tx.open(sealed, itemId: message.bytes, key: key))
                } catch {
                    throw StorageError.integrity
                }
            }
            return CommunityOutboxItem(
                communityId: group, messageId: message, recipient: recipient, revision: UInt64(revision), envelope: envelope, event: event)
        }
    }
    /// A receipt or delete sealed again for its member under a newer revision.
    public func reseal(_ item: CommunityOutboxItem, revision: UInt64, envelope: Data) throws -> CommunityOutboxItem {
        try tx.requireWritable()
        try tx.connection.run(
            "UPDATE community_outbox SET revision=?, envelope=? WHERE community_id=? AND message_id=? AND recipient=?",
            [Int64(revision), envelope, item.communityId.bytes, item.messageId.bytes, item.recipient.bytes])
        return CommunityOutboxItem(
            communityId: item.communityId, messageId: item.messageId, recipient: item.recipient, revision: revision, envelope: envelope,
            event: item.event)
    }
    /// A receipt or delete that can no longer reach its member: it just goes.
    public func discard(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        try removeRow(item)
    }
    /// Every copy of an own message still queued goes (it is sealed again).
    public func unqueue(community: AccountId, message: AccountId) throws {
        try tx.requireWritable()
        try tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", [community.bytes, message.bytes])
    }
    /// The relay accepted one recipient's copy.
    public func complete(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        try removeRow(item)
        try setRecipient(item, .accepted, from: [.pending])
        try settleIfDone(item)
    }
    /// One recipient cannot be reached for good; the others still get it.
    /// A member who left (`skipped`) leaves the message `partial`; one the
    /// relay refused (`failed`) fails it, so it can be sent again to them.
    public func dropRecipient(_ item: CommunityOutboxItem, as state: CommunityRecipientState = .skipped) throws {
        try tx.requireWritable()
        try removeRow(item)
        try setRecipient(item, state, from: [.pending])
        let status: CommunityDeliveryStatus = state == .failed ? .failed : .partial
        try tx.connection.run(
            "UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND outgoing=1 AND status IN (?,?)",
            [status.rawValue, item.communityId.bytes, item.messageId.bytes, CommunityDeliveryStatus.sending.rawValue,
             CommunityDeliveryStatus.partial.rawValue])
        try settleIfDone(item)
    }
    /// Stops the whole message (its revision is stale or the community is gone).
    public func cancel(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        try finish(community: item.communityId, message: item.messageId, as: .failed)
    }
    /// Stops every message still queued for `revision` (the relay answered 409).
    public func cancel(community: AccountId, revision: UInt64) throws {
        try tx.requireWritable()
        for row in try tx.connection.query("SELECT DISTINCT message_id FROM community_outbox WHERE community_id=? AND revision=?", [community.bytes, Int64(revision)]) {
            guard let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { continue }
            try finish(community: community, message: message, as: .failed)
        }
    }
    public func setStatus(_ status: CommunityDeliveryStatus, community: AccountId, message: AccountId) throws {
        try tx.requireWritable()
        try tx.connection.run("UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND outgoing=1", [status.rawValue, community.bytes, message.bytes])
    }

    private func removeRow(_ item: CommunityOutboxItem) throws {
        try tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=? AND recipient=?", [item.communityId.bytes, item.messageId.bytes, item.recipient.bytes])
    }
    private func setRecipient(_ item: CommunityOutboxItem, _ state: CommunityRecipientState, from: [CommunityRecipientState]) throws {
        let states = from.map { String($0.rawValue) }.joined(separator: ",")
        try tx.connection.run(
            "UPDATE community_recipients SET state=? WHERE community_id=? AND message_id=? AND recipient=? AND state IN (\(states))",
            [state.rawValue, item.communityId.bytes, item.messageId.bytes, item.recipient.bytes])
    }
    private func settleIfDone(_ item: CommunityOutboxItem) throws {
        guard try tx.connection.queryOne("SELECT recipient FROM community_outbox WHERE community_id=? AND message_id=? LIMIT 1", [item.communityId.bytes, item.messageId.bytes]) == nil else { return }
        try tx.connection.run(
            "UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND outgoing=1 AND status=?",
            [CommunityDeliveryStatus.sent.rawValue, item.communityId.bytes, item.messageId.bytes, CommunityDeliveryStatus.sending.rawValue])
    }
    func finish(community: AccountId, message: AccountId, as status: CommunityDeliveryStatus) throws {
        try tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", [community.bytes, message.bytes])
        try tx.connection.run(
            "UPDATE community_recipients SET state=? WHERE community_id=? AND message_id=? AND state=?",
            [CommunityRecipientState.failed.rawValue, community.bytes, message.bytes, CommunityRecipientState.pending.rawValue])
        try setStatus(status, community: community, message: message)
    }
}
