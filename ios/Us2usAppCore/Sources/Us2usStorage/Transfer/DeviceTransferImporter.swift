// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// The new phone's side of the history of a direct transfer (PROTOCOL.md §5.6): takes the
/// `TransferRecord`s kept in the spool into a vault that has just been created, under keys of
/// this phone. Blocking: run it off the main thread. A record that makes no sense is left out;
/// a storage that cannot take what is good ends the import, and with it the restore.
public final class DeviceTransferHistoryImporter {
    /// Messages per transaction, when none of them brings a file.
    private static let batch = 200

    private struct PendingFile {
        let file: Data?
        let thumbnail: Data?
    }

    private struct Pending {
        let message: BRPTransferMessage
        let files: [PendingFile]
    }

    private let storage: LocalStorage
    private let ownAccount: AccountId
    private let reader: DeviceTransferSpoolReader
    private let applySettings: (BRPTransferSettings) throws -> Void
    private let now: Date

    private var conversation: Conversation?
    private var community: AccountId?
    private var pending: [Pending] = []
    private var pendingInCommunity: [(message: BRPTransferCommunityMessage, file: Data?)] = []

    public init(
        storage: LocalStorage, ownAccount: AccountId, reader: DeviceTransferSpoolReader,
        applySettings: @escaping (BRPTransferSettings) throws -> Void, now: Date = Date()
    ) {
        self.storage = storage
        self.ownAccount = ownAccount
        self.reader = reader
        self.applySettings = applySettings
        self.now = now
    }

    public func run(progress: (Double) -> Void = { _ in }) throws {
        var reported = -1
        while let bytes = try reader.next() {
            let record = try BRPTransferRecord(serializedBytes: bytes)
            switch record.record {
            case .settings(let settings): try applySettings(settings)
            case .conversation(let head): try begin(head)
            case .message(let message): try add(message)
            case .community(let head): try begin(head)
            case .communityMessage(let message): try add(message)
            case .filePart, nil: throw DeviceTransferError.badMessage
            }
            // Whole percents: there can be hundreds of thousands of records.
            let percent = Int(reader.fraction * 100)
            if percent != reported {
                reported = percent
                progress(reader.fraction)
            }
        }
        try flush()
        try storage.write { $0.checkpointAfterCommit() }
    }

    // MARK: Conversations

    private func begin(_ head: BRPTransferConversation) throws {
        try flush()
        conversation = nil
        community = nil
        let photo = try readFile(head.backgroundPhotoBytes)
        guard let contact = AccountId(bytes: head.contact) else { return }
        conversation = try storage.write { tx -> Conversation? in
            guard contact != ownAccount else { return nil }
            if try tx.contacts.get(contact) == nil {
                // A message request: no backup holds those.
                guard let state = ConversationRequestState(rawValue: head.requestState) else { return nil }
                try tx.contacts.create(contact, state: state, now: now)
                try tx.contacts.setProfile(
                    ContactProfile(
                        username: head.username.isEmpty ? nil : try? NormalizedUsername(head.username),
                        usernameStatus: .unchecked, displayName: head.displayName.isEmpty ? nil : head.displayName,
                        presenceSharing: false, avatar: ProfilePicture(nil)),
                    for: contact)
                // Else its answer would not count as an answer to a request of ours (§6.4).
                if let sent = Self.date(head.requestSentAt) { try tx.contacts.markRequestSent(to: contact, now: sent) }
            }
            if let before = ConversationRequestState(rawValue: head.stateBeforeBlock) {
                try tx.contacts.adoptStateBeforeBlock(before, of: contact)
            }
            let conversation = try tx.conversations.ensure(for: contact, now: now)
            try tx.conversations.adopt(
                settings: head.settings, author: AccountId(bytes: head.settingsAuthor),
                createdAt: Self.date(head.createdAt), lastActivityAt: Self.date(head.lastActivityAt), of: conversation, now: now)
            if !head.draft.isEmpty, !head.settings.ghost.enabled { try tx.conversations.setDraft(head.draft, for: conversation.id) }
            let day = EnvelopeLog.day(of: now)
            for id in head.removedMessageIds where id.count == RandomId.byteCount {
                try tx.connection.run(
                    "INSERT OR IGNORE INTO removed_messages (conversation_id, message_id, removed_day) VALUES (?, ?, ?)",
                    [conversation.id.rawValue, id, day])
            }
            if let background = Self.background(preset: head.backgroundPreset, photo: photo) {
                try tx.chatBackgrounds.setForContact(background, contact, now: now)
            }
            return try tx.conversations.get(conversation.id) ?? conversation
        }
    }

    private func add(_ message: BRPTransferMessage) throws {
        var files: [PendingFile] = []
        for attachment in message.attachments {
            files.append(
                PendingFile(
                    file: attachment.hasFile_p ? try readFile(attachment.fileBytes) ?? Data() : nil,
                    thumbnail: try readFile(attachment.thumbnailBytes)))
        }
        pending.append(Pending(message: message, files: files))
        if pending.count >= Self.batch || files.contains(where: { $0.file != nil || $0.thumbnail != nil }) { try flush() }
    }

    private func flush() throws {
        let items = pending
        pending = []
        if let conversation, !items.isEmpty {
            try storage.write { tx in
                for item in items { try store(item, in: conversation, tx) }
            }
        }
        let messages = pendingInCommunity
        pendingInCommunity = []
        if let community, !messages.isEmpty {
            try storage.write { tx in
                for item in messages { try store(item.message, file: item.file, in: community, tx) }
            }
        }
    }

    private func store(_ item: Pending, in conversation: Conversation, _ tx: StorageTransaction) throws {
        let message = item.message
        guard !conversation.isGhostModeEnabled, message.messageID.count == RandomId.byteCount, Self.isValid(message)
        else { return }
        var content: BRPDataMessage?
        if !message.content.isEmpty {
            guard let data = try? BRPDataMessage(serializedBytes: message.content), data.messageID == message.messageID,
                data.expireTimerSeconds == 0, !data.viewOnce, MessageKind(data.payload).rawValue == Int(message.kind),
                MessageKind(data.payload) != .unknown
            else { return }
            content = data
        } else if message.deletedAt == 0 {
            return
        }
        let key = try tx.conversationKey(slotId: conversation.slotId)
        let body = content == nil ? nil : try tx.seal(message.content, itemId: message.messageID, key: key)
        var liveSession: Data?
        if case .liveLocation(let live)? = content?.payload { liveSession = live.sessionID }
        try tx.connection.run(
            """
            INSERT OR IGNORE INTO messages (conversation_id, message_id, outgoing, sender, kind, body, sent_at, created_at,
                server_timestamp, status, accepted_at, delivered_at, read_at, viewed_at, not_delivered_at, failed_at,
                displayed_at, expire_timer, view_once, quote_target, live_session, deleted_at, saved_by_peer_at, saved_by_me_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?)
            """,
            [
                conversation.id.rawValue, message.messageID, message.outgoing,
                message.outgoing ? ownAccount.bytes : conversation.contact.bytes, Int(message.kind), body,
                Self.stamp(message.sentAt) ?? 0, Self.stamp(message.createdAt) ?? now.storageMilliseconds,
                Self.stamp(message.serverTimestamp), Int(message.status), Self.stamp(message.acceptedAt),
                Self.stamp(message.deliveredAt), Self.stamp(message.readAt), Self.stamp(message.viewedAt),
                Self.stamp(message.notDeliveredAt), Self.stamp(message.failedAt), Self.stamp(message.displayedAt),
                content?.hasQuote == true ? content?.quote.targetMessageID : nil, liveSession, Self.stamp(message.deletedAt),
                Self.stamp(message.savedByPeerAt), Self.stamp(message.savedByMeAt),
            ])
        guard tx.connection.changes > 0 else { return }
        for reaction in message.reactions {
            guard reaction.reactor.count == AccountId.byteCount, !reaction.emoji.isEmpty else { continue }
            let reactionId = RandomId.make()
            try tx.connection.run(
                """
                INSERT OR REPLACE INTO reactions (conversation_id, target_message_id, reactor, reaction_id, emoji, sent_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                [conversation.id.rawValue, message.messageID, reaction.reactor, reactionId,
                 try tx.seal(Data(reaction.emoji.utf8), itemId: reactionId, key: key), Self.stamp(reaction.sentAt) ?? 0])
        }
        guard content != nil else { return }
        for (attachment, files) in zip(message.attachments, item.files) {
            let state = AttachmentState(rawValue: Int(attachment.state)) ?? .unavailable
            let created = try tx.attachments.create(
                for: message.messageID, in: conversation.id, descriptor: attachment.hasDescriptor ? attachment.descriptor : nil,
                state: state, now: now)
            if let file = files.file {
                try tx.storage.files.write(file, itemId: created.id, key: key)
                try tx.connection.run(
                    "UPDATE attachments SET has_file = 1, byte_count = coalesce(byte_count, ?) WHERE id = ?",
                    [UInt64(file.count), created.id])
            }
            if attachment.byteCount > 0 {
                try tx.connection.run("UPDATE attachments SET byte_count = ? WHERE id = ?", [attachment.byteCount, created.id])
            }
            if let thumbnail = files.thumbnail { try tx.attachments.storeThumbnail(thumbnail, for: created.id) }
        }
        // Its place in the outbox did not come along: “Tekrar gönder” needs one.
        guard message.outgoing, MessageStatus(rawValue: Int(message.status)) == .failed, var data = content else { return }
        for attachment in message.attachments
        where attachment.hasDescriptor && attachment.descriptor.mediaID.count == RandomId.byteCount {
            switch data.payload {
            case .attachment: data.payload = .attachment(attachment.descriptor)
            case .sticker(var sticker):
                sticker.custom = attachment.descriptor
                data.payload = .sticker(sticker)
            default: break
            }
        }
        try tx.outbox.adoptFailed(
            BRPContent(data: data), to: conversation.contact, conversation: conversation.id, messageId: message.messageID,
            now: now)
    }

    private static func isValid(_ message: BRPTransferMessage) -> Bool {
        guard let status = MessageStatus(rawValue: Int(message.status)), status != .sending else { return false }
        return message.outgoing != (status == .received)
    }

    // MARK: Communities

    private func begin(_ head: BRPTransferCommunity) throws {
        try flush()
        conversation = nil
        community = nil
        let photo = try readFile(head.backgroundPhotoBytes)
        guard let id = AccountId(bytes: head.id) else { return }
        community = try storage.write { tx -> AccountId? in
            do {
                try tx.communities.pin(id, ownerKey: head.ownerKey, name: CommunityName.isValid(head.name) ? head.name : "")
                if !head.state.isEmpty {
                    try tx.communities.save(
                        SignedCommunityState(state: head.state, signature: head.signature),
                        localName: CommunityName.isValid(head.name) ? head.name : "")
                }
            } catch is CommunityError {
                // Not a state this client accepts: the relay tells again what it is a member of.
                return nil
            }
            try tx.connection.run("UPDATE communities SET active = ? WHERE id = ?", [head.active && !head.state.isEmpty ? 1 : 0, id.bytes])
            for key in head.removed where key.sender.count == AccountId.byteCount && key.messageID.count == AccountId.byteCount {
                try tx.connection.run(
                    "INSERT OR IGNORE INTO community_tombstones (community_id, sender, message_id, role, created_at) VALUES (?, ?, ?, 0, ?)",
                    [id.bytes, key.sender, key.messageID, Self.stamp(key.removedAt) ?? now.storageMilliseconds])
            }
            if let background = Self.background(preset: head.backgroundPreset, photo: photo) {
                try tx.chatBackgrounds.setForCommunity(background, id)
            }
            return id
        }
    }

    private func add(_ message: BRPTransferCommunityMessage) throws {
        let file = message.hasFile_p ? try readFile(message.fileBytes) ?? Data() : nil
        guard community != nil else { return }
        pendingInCommunity.append((message, file))
        if pendingInCommunity.count >= Self.batch || file != nil { try flush() }
    }

    private func store(
        _ message: BRPTransferCommunityMessage, file: Data?, in id: AccountId, _ tx: StorageTransaction
    ) throws {
        guard let sender = AccountId(bytes: message.sender), let messageId = AccountId(bytes: message.messageID) else { return }
        let outgoing = sender == ownAccount
        let deletion = CommunityDeletion(rawValue: Int(message.deletedBy))
        let kind: CommunityMessageKind = message.hasSticker ? .sticker : (message.hasAttachment ? .media : .text)
        let key = try tx.communities.key(id)
        let status: CommunityDeliveryStatus =
            outgoing ? (message.status == 0 ? .sent : message.status == 1 ? .partial : .failed) : .received
        let received = Self.stamp(message.receivedAt) ?? now.storageMilliseconds
        try tx.connection.run(
            """
            INSERT OR IGNORE INTO community_messages
                (community_id,sender,message_id,body,sent_at,received_at,outgoing,status,kind,deleted_by,displayed_at,delivery_due)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,0)
            """,
            [id.bytes, sender.bytes, messageId.bytes,
             deletion == nil ? try tx.seal(Data(message.text.utf8), itemId: messageId.bytes, key: key) : Data(),
             Self.stamp(message.sentAt) ?? received, received, outgoing ? 1 : 0, status.rawValue, kind.rawValue, deletion?.rawValue,
             outgoing || deletion != nil ? received : Self.stamp(message.displayedAt)])
        guard tx.connection.changes > 0 else { return }
        if outgoing {
            for recipient in message.recipients {
                guard let member = AccountId(bytes: recipient.member) else { continue }
                try tx.connection.run(
                    """
                    INSERT OR IGNORE INTO community_recipients (community_id, message_id, recipient, state, delivered_at, read_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    [id.bytes, messageId.bytes, member.bytes, Int(recipient.state), Self.stamp(recipient.deliveredAt), Self.stamp(recipient.readAt)])
            }
        }
        guard deletion == nil else { return }
        if message.hasSticker {
            try tx.connection.run(
                "UPDATE community_messages SET sticker=? WHERE community_id=? AND sender=? AND message_id=?",
                [try tx.seal(try message.sticker.serializedBytes(), itemId: messageId.bytes, key: key), id.bytes, sender.bytes, messageId.bytes])
        }
        guard message.hasAttachment else { return }
        let itemId = RandomId.make()
        let pointer = message.attachment.communityPointer
        let state: CommunityAttachmentState =
            switch message.mediaState {
            case 0: .remote
            case 1: outgoing ? .uploaded : .downloaded
            default: .unavailable
            }
        var token: Data?
        if message.deleteToken.count == 32 {
            token = try tx.seal(message.deleteToken, itemId: pointer.mediaID, key: key)
        }
        try tx.connection.run(
            """
            INSERT INTO community_attachments (community_id,sender,message_id,item_id,descriptor,delete_token,state,has_file,uploaded_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,
            [id.bytes, sender.bytes, messageId.bytes, itemId, try tx.seal(try pointer.serializedBytes(), itemId: itemId, key: key),
             token, state.rawValue, file == nil ? 0 : 1, Self.stamp(message.uploadedAt), now.storageMilliseconds])
        if let file { try tx.storage.files.write(file, itemId: itemId, key: key) }
    }

    // MARK: Support

    /// The next `count` bytes, from the file parts that follow; `nil` for none.
    private func readFile(_ count: UInt64) throws -> Data? {
        guard count > 0 else { return nil }
        var file = Data()
        file.reserveCapacity(Int(min(count, 64 * 1024 * 1024)))
        while UInt64(file.count) < count {
            guard let bytes = try reader.next() else { throw DeviceTransferError.closed }
            guard case .filePart(let part)? = try BRPTransferRecord(serializedBytes: bytes).record,
                UInt64(file.count) + UInt64(part.data.count) <= count
            else { throw DeviceTransferError.badMessage }
            file.append(part.data)
        }
        return file
    }

    private static func background(preset: String, photo: Data?) -> ChatBackgroundChoice? {
        let choice: ChatBackgroundChoice? = photo.map(ChatBackgroundChoice.photo) ?? (preset.isEmpty ? nil : .preset(preset))
        return choice?.isValid == true ? choice : nil
    }

    private static func stamp(_ value: UInt64) -> Int64? {
        value == 0 ? nil : Int64(clamping: value)
    }

    private static func date(_ value: UInt64) -> Date? {
        stamp(value).map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
    }
}
