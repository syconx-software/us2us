import Foundation
import Us2usKit

public struct StoredCommunity: Equatable, Sendable, Identifiable {
    public let id: AccountId
    public let name: String
    public let signedState: SignedCommunityState?
    public let ownerKey: Data
    public let isActive: Bool
    public var state: CommunityState? { signedState.flatMap { try? CommunityState(bytes: $0.state) } }
}

/// Where an outgoing community message stands; incoming ones are `received`.
public enum CommunityDeliveryStatus: String, Sendable, Equatable {
    case sending
    /// Accepted by the relay for every member of its revision.
    case sent
    /// Some members could not be reached for good; the others got it.
    case partial
    /// Stopped for everyone (the roster changed or the community is gone).
    case failed
    case received
}

public struct CommunityTextItem: Equatable, Sendable, Identifiable {
    public let communityId: AccountId
    public let sender: AccountId
    public let messageId: AccountId
    public let text: String
    /// The sender's time, clamped to a window around ``receivedAt``.
    public let sentAt: Int64
    /// When this phone stored it (ms); the order of the conversation.
    public let receivedAt: Int64
    public let outgoing: Bool
    public let status: CommunityDeliveryStatus
    public var id: String { sender.string + "/" + messageId.string }
}

public struct CommunityOutboxItem: Sendable, Equatable {
    public let communityId: AccountId
    public let messageId: AccountId
    public let recipient: AccountId
    public let revision: UInt64
    public let envelope: Data
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
    /// The relay no longer lists us as a member (removed, or the community is gone).
    public func deactivate(_ id: AccountId) throws {
        try tx.requireWritable()
        guard try tx.connection.queryOne("SELECT active FROM communities WHERE id=? AND active=1", [id.bytes]) != nil else { return }
        try tx.connection.run("UPDATE communities SET active=0 WHERE id=?", [id.bytes])
        // Nothing queued can reach anyone any more.
        for row in try tx.connection.query("SELECT DISTINCT message_id FROM community_outbox WHERE community_id=?", [id.bytes]) {
            guard let message = row.blob("message_id").flatMap(AccountId.init(bytes:)) else { continue }
            try finish(community: id, message: message, as: .failed)
        }
    }
    /// Forgets a community: its key slot (and with it every message) is destroyed.
    public func forget(_ id: AccountId) throws {
        try tx.requireWritable()
        if let slot = try tx.connection.queryOne("SELECT slot_id FROM communities WHERE id=?", [id.bytes])?.blob("slot_id") { try tx.storage.keyring.destroy(slot) }
        try tx.connection.run("DELETE FROM communities WHERE id=?", [id.bytes])
        tx.checkpointAfterCommit()
    }
    private func key(_ id: AccountId) throws -> Data {
        guard let slot = try tx.connection.queryOne("SELECT slot_id FROM communities WHERE id=?", [id.bytes])?.blob("slot_id") else { throw StorageError.notFound }
        return try tx.conversationKey(slotId: slot)
    }

    /// The latest ``pageSize`` messages in the order this phone stored them
    /// (oldest first); a sender's clock cannot move a message.
    public func messages(_ id: AccountId, limit: Int = pageSize) throws -> [CommunityTextItem] {
        let key = try key(id)
        let rows = try tx.connection.query(
            """
            SELECT sender,message_id,body,sent_at,received_at,outgoing,status FROM community_messages
            WHERE community_id=? ORDER BY received_at DESC, sender DESC, message_id DESC LIMIT ?
            """, [id.bytes, Int64(max(0, limit))])
        return try rows.reversed().map { row in
            guard let sender = row.blob("sender").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)), let body = row.blob("body"),
                  let date = row.int64("sent_at"), let received = row.int64("received_at"), let status = row.text("status").flatMap(CommunityDeliveryStatus.init(rawValue:)), let outgoing = row.int64("outgoing"),
                  let text = String(data: try tx.open(body, itemId: message.bytes, key: key), encoding: .utf8) else { throw StorageError.integrity }
            return CommunityTextItem(communityId: id, sender: sender, messageId: message, text: text, sentAt: date, receivedAt: received, outgoing: outgoing != 0, status: status)
        }
    }
    public func store(_ content: BRPCommunityMessage, sender: AccountId, outgoing: Bool, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let group = AccountId(bytes: content.communityID), let message = AccountId(bytes: content.messageID), content.sentAt <= UInt64(Int64.max) else { throw CommunityError.invalidState }
        let received = now.storageMilliseconds
        let claimed = Int64(content.sentAt)
        let sentAt = min(max(claimed, received - Self.maxAge), received + Self.clockSkew)
        let body = try tx.seal(Data(content.text.utf8), itemId: message.bytes, key: key(group))
        try tx.connection.run(
            "INSERT OR IGNORE INTO community_messages (community_id,sender,message_id,body,sent_at,received_at,outgoing,status) VALUES (?,?,?,?,?,?,?,?)",
            [group.bytes, sender.bytes, message.bytes, body, sentAt, received, outgoing ? 1 : 0, (outgoing ? CommunityDeliveryStatus.sending : .received).rawValue])
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
        try tx.connection.run("INSERT OR IGNORE INTO community_outbox (community_id,message_id,recipient,revision,envelope) VALUES (?,?,?,?,?)", [item.communityId.bytes, item.messageId.bytes, item.recipient.bytes, Int64(item.revision), item.envelope])
    }
    public func enqueue(community: AccountId, message: AccountId, recipient: AccountId, revision: UInt64, envelope: Data) throws {
        try enqueue(CommunityOutboxItem(communityId: community, messageId: message, recipient: recipient, revision: revision, envelope: envelope))
    }
    /// Queued deliveries, oldest message first.
    public func pending() throws -> [CommunityOutboxItem] {
        try tx.connection.query(
            """
            SELECT o.community_id,o.message_id,o.recipient,o.revision,o.envelope FROM community_outbox o
            LEFT JOIN community_messages m ON m.community_id=o.community_id AND m.message_id=o.message_id AND m.outgoing=1
            ORDER BY m.received_at, o.message_id, o.recipient LIMIT 4950
            """
        ).map { row in
            guard let group = row.blob("community_id").flatMap(AccountId.init(bytes:)), let message = row.blob("message_id").flatMap(AccountId.init(bytes:)), let recipient = row.blob("recipient").flatMap(AccountId.init(bytes:)), let revision = row.int64("revision"), let envelope = row.blob("envelope") else { throw StorageError.integrity }
            return CommunityOutboxItem(communityId: group, messageId: message, recipient: recipient, revision: UInt64(revision), envelope: envelope)
        }
    }
    /// The relay accepted one recipient's copy.
    public func complete(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        try removeRow(item)
        try settleIfDone(item)
    }
    /// One recipient cannot be reached for good; the others still get it.
    public func dropRecipient(_ item: CommunityOutboxItem) throws {
        try tx.requireWritable()
        try removeRow(item)
        try tx.connection.run(
            "UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND outgoing=1 AND status=?",
            [CommunityDeliveryStatus.partial.rawValue, item.communityId.bytes, item.messageId.bytes, CommunityDeliveryStatus.sending.rawValue])
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
    private func settleIfDone(_ item: CommunityOutboxItem) throws {
        guard try tx.connection.queryOne("SELECT recipient FROM community_outbox WHERE community_id=? AND message_id=? LIMIT 1", [item.communityId.bytes, item.messageId.bytes]) == nil else { return }
        try tx.connection.run(
            "UPDATE community_messages SET status=? WHERE community_id=? AND message_id=? AND outgoing=1 AND status=?",
            [CommunityDeliveryStatus.sent.rawValue, item.communityId.bytes, item.messageId.bytes, CommunityDeliveryStatus.sending.rawValue])
    }
    private func finish(community: AccountId, message: AccountId, as status: CommunityDeliveryStatus) throws {
        try tx.connection.run("DELETE FROM community_outbox WHERE community_id=? AND message_id=?", [community.bytes, message.bytes])
        try setStatus(status, community: community, message: message)
    }
}
