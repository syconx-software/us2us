// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// The old phone's history for a direct transfer (PROTOCOL.md §5.6), as `TransferRecord`s:
/// everything a conversation or community holds except what must not outlive this phone.
/// Disappearing messages, view-once items and the messages of ghost-mode conversations stay
/// behind: their ids go along as removed, so a retransmission does not bring them back.
public struct DeviceTransferHistoryExporter: DeviceTransferHistorySource {
    /// One read's worth of messages.
    static let batch = 200
    /// Removed ids per conversation (at most some 360 KiB of a 1 MiB frame).
    static let maxRemovedIds = 20_000
    /// What may leave this phone at all.
    static let movable = "expire_timer = 0 AND expires_at IS NULL AND view_once = 0"

    private let storage: LocalStorage
    private let settings: BRPTransferSettings
    private let now: @Sendable () -> Date

    public init(storage: LocalStorage, settings: BRPTransferSettings, now: @escaping @Sendable () -> Date = { Date() }) {
        self.storage = storage
        self.settings = settings
        self.now = now
    }

    // MARK: Estimate

    public func estimatedBytes() async throws -> UInt64 {
        try storage.read { tx in
            var total: Int64 = 0
            for conversation in try tx.conversations.all() where !conversation.isGhostModeEnabled {
                total += try Self.sum(
                    tx, "SELECT coalesce(sum(coalesce(length(body), 0) + 96), 0) AS n FROM messages WHERE conversation_id = ? AND \(Self.movable)",
                    conversation.id.rawValue)
                total += try Self.sum(
                    tx,
                    """
                    SELECT coalesce(sum(a.byte_count), 0) AS n FROM attachments a
                    JOIN messages m ON m.conversation_id = a.conversation_id AND m.message_id = a.message_id
                    WHERE a.conversation_id = ? AND a.has_file = 1 AND m.\(Self.movable.replacingOccurrences(of: " AND ", with: " AND m."))
                    """, conversation.id.rawValue)
            }
            for community in try tx.communities.all() {
                total += try Self.sum(
                    tx, "SELECT coalesce(sum(length(body) + 96), 0) AS n FROM community_messages WHERE community_id = ?",
                    community.id.bytes)
                for row in try tx.connection.query(
                    "SELECT item_id FROM community_attachments WHERE community_id = ? AND has_file = 1", [community.id.bytes])
                {
                    guard let item = row.blob("item_id") else { continue }
                    total += tx.storage.files.size(of: item)
                }
            }
            return UInt64(max(0, total))
        }
    }

    private static func sum(_ tx: StorageTransaction, _ sql: String, _ owner: Data) throws -> Int64 {
        try tx.connection.queryOne(sql, [owner])?.int64("n") ?? 0
    }

    // MARK: Records

    public func export(_ emit: @Sendable (Data) async throws -> Void) async throws {
        var first = BRPTransferRecord()
        first.settings = settings
        try await emit(try first.serializedBytes())
        for conversation in try storage.read({ try $0.conversations.all() }) {
            try Task.checkCancellation()
            try await leavingBehind { try await export(conversation, emit) }
        }
        for community in try storage.read({ try $0.communities.all() }) {
            try Task.checkCancellation()
            try await leavingBehind { try await export(community, emit) }
        }
    }

    /// What cannot be read (damaged, or wiped just now) stays behind and the move goes on; a store
    /// that cannot be read at all (the vault locked) ends it. Nothing is read between a record and
    /// its file parts, so none of this can leave a record without them.
    private func leavingBehind(_ body: () async throws -> Void) async throws {
        do {
            try await body()
        } catch StorageError.integrity, StorageError.conversationShredded, StorageError.notFound {
            return
        }
    }

    private func export(_ conversation: Conversation, _ emit: @Sendable (Data) async throws -> Void) async throws {
        let (head, photo) = try storage.read { tx -> (BRPTransferConversation, Data?) in
            var head = BRPTransferConversation()
            head.contact = conversation.contact.bytes
            if let contact = try tx.contacts.get(conversation.contact) {
                head.requestState = contact.requestState.rawValue
                head.username = contact.profile.username?.value ?? ""
                head.displayName = contact.profile.displayName ?? ""
                head.requestSentAt = Self.milliseconds(try tx.contacts.requestSentAt(to: conversation.contact))
                head.stateBeforeBlock = try tx.contacts.stateBeforeBlock(of: conversation.contact) ?? ""
            }
            head.settings = conversation.settings
            head.settingsAuthor = conversation.settingsAuthor?.bytes ?? Data()
            head.createdAt = Self.milliseconds(conversation.createdAt)
            head.lastActivityAt = Self.milliseconds(conversation.lastActivityAt)
            // What was typed in a ghost chat goes the way of its messages.
            if !conversation.isGhostModeEnabled { head.draft = (try? tx.conversations.draft(for: conversation.id)) ?? "" }
            head.removedMessageIds = try Self.removedIds(tx, conversation)
            var photo: Data?
            switch try? tx.chatBackgrounds.forContact(conversation.contact) {
            case .preset(let id): head.backgroundPreset = id
            case .photo(let jpeg):
                head.backgroundPhotoBytes = UInt64(jpeg.count)
                photo = jpeg
            case nil: break
            }
            return (head, photo)
        }
        var record = BRPTransferRecord()
        record.conversation = head
        try await emit(try record.serializedBytes())
        if let photo { try await emitFile(photo, emit) }
        guard !conversation.isGhostModeEnabled else { return }

        var after: Int64 = 0
        while true {
            try Task.checkCancellation()
            let page = try storage.read { tx -> (messages: [StoredMessage], last: Int64?) in
                let key = try tx.conversationKey(slotId: conversation.slotId)
                let rows = try tx.connection.query(
                    """
                    SELECT \(MessageRepository.columns) FROM messages
                    WHERE conversation_id = ? AND id > ? AND \(Self.movable) ORDER BY id LIMIT ?
                    """, [conversation.id.rawValue, after, Self.batch])
                // A row that cannot be read is left behind instead of ending the move.
                return (rows.compactMap { try? tx.messages.message(from: $0, key: key) }, rows.last?.int64("id"))
            }
            guard let last = page.last else { return }
            after = last
            for message in page.messages {
                try Task.checkCancellation()
                try await leavingBehind { try await export(message, in: conversation.id, emit) }
            }
        }
    }

    private static func removedIds(_ tx: StorageTransaction, _ conversation: Conversation) throws -> [Data] {
        var ids = try tx.connection.query(
            "SELECT message_id FROM removed_messages WHERE conversation_id = ? LIMIT ?",
            [conversation.id.rawValue, maxRemovedIds]
        ).compactMap { $0.blob("message_id") }
        let left = maxRemovedIds - ids.count
        guard left > 0 else { return ids }
        let filter = conversation.isGhostModeEnabled ? "" : "AND NOT (\(movable))"
        ids += try tx.connection.query(
            "SELECT message_id FROM messages WHERE conversation_id = ? \(filter) ORDER BY id DESC LIMIT ?",
            [conversation.id.rawValue, left]
        ).compactMap { $0.blob("message_id") }
        return ids
    }

    private func export(
        _ message: StoredMessage, in conversationId: ConversationId, _ emit: @Sendable (Data) async throws -> Void
    ) async throws {
        var record = BRPTransferMessage()
        record.messageID = message.messageId
        record.outgoing = message.isOutgoing
        if var content = message.content {
            // Whatever a sharing still running would need stays here: on the new phone it has ended.
            if case .liveLocation(var live) = content.payload, !live.stopped {
                live.stopped = true
                content.liveLocation = live
            }
            record.content = try content.serializedBytes()
        }
        record.kind = UInt32(message.kind.rawValue)
        record.sentAt = Self.milliseconds(message.sentAt)
        record.createdAt = Self.milliseconds(message.createdAt)
        record.serverTimestamp = Self.milliseconds(message.serverTimestamp)
        let stamps = message.timestamps
        // What was still on its way stays on this phone's outbox: the new phone says it failed.
        let unsent = message.status == .sending
        record.status = UInt32((unsent ? MessageStatus.failed : message.status).rawValue)
        record.failedAt = Self.milliseconds(unsent ? now() : stamps.failedAt)
        record.acceptedAt = Self.milliseconds(stamps.acceptedAt)
        record.deliveredAt = Self.milliseconds(stamps.deliveredAt)
        record.readAt = Self.milliseconds(stamps.readAt)
        record.viewedAt = Self.milliseconds(stamps.viewedAt)
        record.notDeliveredAt = Self.milliseconds(stamps.notDeliveredAt)
        record.displayedAt = Self.milliseconds(stamps.displayedAt)
        record.savedByPeerAt = Self.milliseconds(stamps.savedByPeerAt)
        record.savedByMeAt = Self.milliseconds(stamps.savedByMeAt)
        record.deletedAt = Self.milliseconds(message.deletedAt)

        let id = message.messageId
        let (reactions, attachments) = try storage.read { tx in
            ((try? tx.messages.reactions(on: [id], in: conversationId)[id]) ?? [], try tx.attachments.forMessage(id, in: conversationId))
        }
        record.reactions = reactions.map { reaction in
            var item = BRPTransferReaction()
            item.reactor = reaction.reactor.bytes
            item.emoji = reaction.emoji
            item.sentAt = Self.milliseconds(reaction.sentAt)
            return item
        }
        var files: [Data] = []
        for attachment in attachments {
            var item = BRPTransferAttachment()
            if let descriptor = attachment.descriptor { item.descriptor = descriptor }
            item.byteCount = attachment.byteCount ?? 0
            // A file that cannot be read is treated as one that never came.
            let file = attachment.hasFile ? try? storage.read { try $0.attachments.readFile(attachment.id) } : nil
            let thumbnail = (try? storage.read { try $0.attachments.readThumbnail(attachment.id) }) ?? nil
            item.state = UInt32(Self.state(of: attachment, hasFile: file != nil).rawValue)
            item.hasFile_p = file != nil
            item.fileBytes = UInt64(file?.count ?? 0)
            item.thumbnailBytes = UInt64(thumbnail?.count ?? 0)
            record.attachments.append(item)
            if let file { files.append(file) }
            if let thumbnail { files.append(thumbnail) }
        }
        var wrapped = BRPTransferRecord()
        wrapped.message = record
        try await emit(try wrapped.serializedBytes())
        for file in files { try await emitFile(file, emit) }
    }

    /// Transitional states do not move: nothing runs on the new phone yet. A file that never
    /// reached the relay stays ready to go up, when “Tekrar gönder” asks for it.
    private static func state(of attachment: StoredAttachment, hasFile: Bool) -> AttachmentState {
        let pointed = attachment.descriptor?.mediaID.count == RandomId.byteCount
        switch attachment.state {
        case .pendingUpload, .uploading: return hasFile ? .pendingUpload : .failed
        case .uploaded: return pointed ? .uploaded : .failed
        case .pendingDownload, .downloading: return hasFile ? .downloaded : .pendingDownload
        case .downloaded: return hasFile ? .downloaded : (pointed ? .pendingDownload : .unavailable)
        case .failed, .unavailable: return attachment.state
        }
    }

    private func export(_ community: StoredCommunity, _ emit: @Sendable (Data) async throws -> Void) async throws {
        let (head, photo) = try storage.read { tx -> (BRPTransferCommunity, Data?) in
            var head = BRPTransferCommunity()
            head.id = community.id.bytes
            head.ownerKey = community.ownerKey
            head.name = community.name
            if let signed = community.signedState {
                head.state = signed.state
                head.signature = signed.signature
            }
            head.active = community.isActive
            head.removed = try tx.connection.query(
                "SELECT sender, message_id, created_at FROM community_tombstones WHERE community_id = ? AND role = 0 LIMIT ?",
                [community.id.bytes, Self.maxRemovedIds]
            ).compactMap { row in
                guard let sender = row.blob("sender"), let message = row.blob("message_id") else { return nil }
                var key = BRPTransferCommunityKey()
                key.sender = sender
                key.messageID = message
                key.removedAt = row.uint64("created_at") ?? 0
                return key
            }
            var photo: Data?
            switch try? tx.chatBackgrounds.forCommunity(community.id) {
            case .preset(let id): head.backgroundPreset = id
            case .photo(let jpeg):
                head.backgroundPhotoBytes = UInt64(jpeg.count)
                photo = jpeg
            case nil: break
            }
            return (head, photo)
        }
        var record = BRPTransferRecord()
        record.community = head
        try await emit(try record.serializedBytes())
        if let photo { try await emitFile(photo, emit) }

        var before: CommunityHistoryCursor?
        var pages: [[CommunityTextItem]] = []
        while true {
            let page = try storage.read {
                try $0.communities.history(community.id, before: before, limit: Self.batch, skippingUnreadable: true)
            }
            if !page.items.isEmpty { pages.append(page.items) }
            guard page.hasOlder, let oldest = page.oldest else { break }
            before = oldest
        }
        // Oldest first: the order the new phone stores them in.
        for page in pages.reversed() {
            for item in page {
                try Task.checkCancellation()
                try await leavingBehind { try await export(item, in: community.id, emit) }
            }
        }
    }

    private func export(
        _ item: CommunityTextItem, in community: AccountId, _ emit: @Sendable (Data) async throws -> Void
    ) async throws {
        var record = BRPTransferCommunityMessage()
        record.sender = item.sender.bytes
        record.messageID = item.messageId.bytes
        record.sentAt = UInt64(max(0, item.sentAt))
        record.receivedAt = UInt64(max(0, item.receivedAt))
        record.text = item.text
        if let sticker = item.sticker { record.sticker = sticker }
        record.deletedBy = UInt32(item.deletion?.rawValue ?? 0)
        var file: Data?
        let extras = try storage.read { tx -> (displayed: Int64?, deletedAt: Int64?, token: Data?, uploaded: Int64?, recipients: [CommunityRecipientItem]) in
            let row = try tx.connection.queryOne(
                "SELECT displayed_at FROM community_messages WHERE community_id = ? AND sender = ? AND message_id = ?",
                [community.bytes, item.sender.bytes, item.messageId.bytes])
            let token = try? tx.communities.deleteToken(community: community, sender: item.sender, messageId: item.messageId)
            let recipients = item.outgoing ? try tx.communities.recipients(community: community, messageId: item.messageId) : []
            // This client keeps no time for a delete: placeholders are kept for a while from now.
            return (row?.int64("displayed_at"), item.deletion == nil ? nil : now().storageMilliseconds, token?.token, token?.uploadedAt, recipients)
        }
        record.status = Self.communityStatus(item.status, waiting: extras.recipients.contains { $0.state == .pending || $0.state == .failed })
        record.displayedAt = UInt64(max(0, extras.displayed ?? 0))
        record.deletedAt = UInt64(max(0, extras.deletedAt ?? 0))
        if let token = extras.token, let uploaded = extras.uploaded {
            record.deleteToken = token
            record.uploadedAt = UInt64(max(0, uploaded))
        }
        record.recipients = extras.recipients.map { recipient in
            var entry = BRPTransferCommunityRecipient()
            entry.member = recipient.recipient.bytes
            // Pending never moves: the old phone's queue stays behind.
            entry.state = UInt32(recipient.state == .pending ? CommunityRecipientState.failed.rawValue : recipient.state.rawValue)
            entry.deliveredAt = UInt64(max(0, recipient.deliveredAt ?? 0))
            entry.readAt = UInt64(max(0, recipient.readAt ?? 0))
            return entry
        }
        if let attachment = item.attachment {
            record.attachment = attachment.descriptor
            if attachment.hasFile {
                file = try? storage.read {
                    try $0.communities.readAttachmentFile(community: community, sender: item.sender, messageId: item.messageId)
                }
            }
            switch attachment.state {
            case .remote, .downloading: record.mediaState = file == nil ? 0 : 1
            case .pendingUpload, .uploading, .uploaded, .downloaded: record.mediaState = file == nil ? 2 : 1
            // An own upload that failed can still go up, from the file it has.
            case .failed: record.mediaState = file == nil ? 2 : 1
            case .unavailable: record.mediaState = 2
            }
            record.hasFile_p = file != nil
            record.fileBytes = UInt64(file?.count ?? 0)
        }
        var wrapped = BRPTransferRecord()
        wrapped.communityMessage = record
        try await emit(try wrapped.serializedBytes())
        if let file { try await emitFile(file, emit) }
    }

    private static func communityStatus(_ status: CommunityDeliveryStatus, waiting: Bool) -> UInt32 {
        switch status {
        case .sent: 0
        // Done for all but those who left, unless someone still waits: this phone's queue stays behind.
        case .partial: waiting ? 2 : 1
        case .failed, .sending: 2
        case .received: 3
        }
    }

    private func emitFile(_ file: Data, _ emit: @Sendable (Data) async throws -> Void) async throws {
        var start = file.startIndex
        while start < file.endIndex {
            try Task.checkCancellation()
            let end = file.index(start, offsetBy: DeviceTransferLimits.chunkBytes, limitedBy: file.endIndex) ?? file.endIndex
            var part = BRPTransferFilePart()
            part.data = Data(file[start..<end])
            var record = BRPTransferRecord()
            record.filePart = part
            try await emit(try record.serializedBytes())
            start = end
        }
    }

    private static func milliseconds(_ date: Date?) -> UInt64 {
        guard let date else { return 0 }
        return UInt64(max(0, date.storageMilliseconds))
    }
}
