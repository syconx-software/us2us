import Foundation

/// Community content kinds (docs/COMMUNITIES.md, Extensions v2/v3).
public enum CommunityContentKind: Equatable, Sendable {
    /// `text` only.
    case text
    /// `attachment`, with `text` as its optional caption.
    case media
    case sticker
    /// `receipt` only, sent to the original sender of its ids.
    case receipt
    /// `delete_target_message_id` and `delete_target_sender_id` only.
    case delete
}

/// Limits of the community content (Extension v2, Validation).
public enum CommunityContentLimits {
    public static let maxTextBytes = 8192
    public static let maxReceiptIds = 1000
    public static let maxThumbnailBytes = 10 * 1024
    public static let maxNameBytes = 255
    static let maxRevision: UInt64 = 9_007_199_254_740_991
}

extension BRPCommunityMessage {
    /// The structural checks every received (and sent) community message
    /// passes: the v1 fields, then exactly one content kind. The 1:1
    /// `Attachment` rules (a 32-byte delete token, a 2048-byte caption) do
    /// not apply here: a community attachment carries no delete token,
    /// caption. Only voice carries a bounded waveform. Sender, roster and authorization are checked
    /// against local state by the receiver, not here.
    @discardableResult
    public func validatedKind() throws(ContentValidationError) -> CommunityContentKind {
        guard communityID.count == RandomId.byteCount, messageID.count == RandomId.byteCount,
            revision > 0, revision <= CommunityContentLimits.maxRevision
        else { throw .malformedField("community") }
        guard sentAt > 0 else { throw .malformedField("community.sent_at") }
        guard text.utf8.count <= CommunityContentLimits.maxTextBytes else { throw .tooLarge("community.text") }
        let hasDelete = !deleteTargetMessageID.isEmpty || !deleteTargetSenderID.isEmpty
        let hasText = !text.isEmpty
        if hasSticker {
            guard !hasAttachment, !hasReceipt, !hasDelete, !sticker.hasCustom,
                  CommunityCaption.isSendableText(sticker.packID),
                  sticker.packID.utf8.count <= 128, sticker.stickerID > 0,
                  sticker.lang.utf8.count <= 35, sticker.emoji.utf8.count <= 64,
                  CommunityCaption.isSendableText(sticker.emoji), text == sticker.emoji
            else { throw .malformedField("community.sticker") }
            return .sticker
        }
        if hasAttachment, attachment.kind == .voice, hasText { throw .malformedField("community.voice.caption") }
        if hasReceipt {
            guard !hasAttachment, !hasDelete, !hasText else { throw .malformedField("community.receipt") }
            try receipt.validateCommunityReceipt()
            return .receipt
        }
        if hasDelete {
            guard !hasAttachment, !hasText,
                deleteTargetMessageID.count == RandomId.byteCount, deleteTargetSenderID.count == RandomId.byteCount
            else { throw .malformedField("community.delete") }
            return .delete
        }
        if hasAttachment {
            try attachment.validateCommunityAttachment()
            return .media
        }
        guard text.unicodeScalars.contains(where: { !$0.properties.isWhitespace }) else {
            throw .malformedField("community.text")
        }
        return .text
    }

    /// The common fields of every kind.
    init(community: AccountId, revision: UInt64, messageId: Data, sentAt: Date) {
        self.init()
        communityID = community.bytes
        self.revision = revision
        messageID = messageId
        self.sentAt = max(1, sentAt.millisecondsSince1970)
    }

    /// A text message.
    public static func text(
        _ text: String, community: AccountId, revision: UInt64, messageId: Data = RandomId.make(), sentAt: Date = Date()
    ) -> BRPCommunityMessage {
        var message = BRPCommunityMessage(community: community, revision: revision, messageId: messageId, sentAt: sentAt)
        message.text = text
        return message
    }

    /// Media: the shared pointer (no delete token or attachment caption) and the caption as `text`. A whitespace-only caption is
    /// sent empty.
    public static func media(
        _ attachment: BRPAttachment, caption: String, community: AccountId, revision: UInt64,
        messageId: Data = RandomId.make(), sentAt: Date = Date()
    ) -> BRPCommunityMessage {
        var message = BRPCommunityMessage(community: community, revision: revision, messageId: messageId, sentAt: sentAt)
        message.attachment = attachment.communityPointer
        message.text = CommunityCaption.normalized(caption)
        return message
    }

    public static func sticker(
        _ sticker: BRPSticker, community: AccountId, revision: UInt64,
        messageId: Data = RandomId.make(), sentAt: Date = Date()
    ) -> BRPCommunityMessage {
        var message = BRPCommunityMessage(community: community, revision: revision, messageId: messageId, sentAt: sentAt)
        message.sticker = sticker
        message.text = sticker.emoji
        return message
    }

    /// A DELIVERY or READ receipt for `ids` (1...1000, the original sender's own messages).
    public static func receipt(
        _ type: BRPCommunityReceipt.TypeEnum, for ids: [Data], community: AccountId, revision: UInt64,
        messageId: Data = RandomId.make(), sentAt: Date = Date()
    ) -> BRPCommunityMessage {
        var message = BRPCommunityMessage(community: community, revision: revision, messageId: messageId, sentAt: sentAt)
        var receipt = BRPCommunityReceipt()
        receipt.type = type
        receipt.messageIds = ids
        message.receipt = receipt
        return message
    }

    /// Delete for everyone of `target`, sent by `targetSender` (or, as moderation, by the owner).
    public static func delete(
        target: Data, sender targetSender: AccountId, community: AccountId, revision: UInt64,
        messageId: Data = RandomId.make(), sentAt: Date = Date()
    ) -> BRPCommunityMessage {
        var message = BRPCommunityMessage(community: community, revision: revision, messageId: messageId, sentAt: sentAt)
        message.deleteTargetMessageID = target
        message.deleteTargetSenderID = targetSender.bytes
        return message
    }

    /// The receipt's ids without repeats, in the order sent.
    public var distinctReceiptIds: [Data] {
        var seen = Set<Data>()
        return receipt.messageIds.filter { seen.insert($0).inserted }
    }
}

extension BRPCommunityReceipt {
    fileprivate func validateCommunityReceipt() throws(ContentValidationError) {
        switch type {
        case .delivery, .read: break
        case .unspecified, .UNRECOGNIZED: throw .malformedField("community.receipt.type")
        }
        guard !messageIds.isEmpty, messageIds.allSatisfy({ $0.count == RandomId.byteCount }) else {
            throw .malformedField("community.receipt.message_ids")
        }
        // A repeated id counts once.
        guard Set(messageIds).count <= CommunityContentLimits.maxReceiptIds else {
            throw .tooLarge("community.receipt.message_ids")
        }
    }
}

extension BRPAttachment {
    /// Community attachment rules: IMAGE, VIDEO, FILE or VOICE,
    /// fixed-size pointer fields, no delete token or attachment caption,
    /// a thumbnail of at most 10 KiB and names of at most 255 bytes.
    fileprivate func validateCommunityAttachment() throws(ContentValidationError) {
        switch kind {
        case .image, .video, .file, .voice: break
        case .unspecified, .stickerImage, .UNRECOGNIZED: throw .malformedField("community.attachment.kind")
        }
        guard mediaID.count == RandomId.byteCount else { throw .malformedField("community.attachment.media_id") }
        guard key.count == ContentLimits.attachmentKeyBytes else { throw .malformedField("community.attachment.key") }
        guard digest.count == ContentLimits.sha256Bytes else { throw .malformedField("community.attachment.digest") }
        guard deleteToken.isEmpty, caption.isEmpty else {
            throw .malformedField("community.attachment")
        }
        if kind == .voice {
            guard contentType == "audio/mp4", (1...16 * 1024 * 1024).contains(size),
                  (1000...300000).contains(durationMs), waveform.count <= 100,
                  thumbnail.isEmpty, fileName.isEmpty, width == 0, height == 0
            else { throw .malformedField("community.voice") }
        } else if !waveform.isEmpty { throw .malformedField("community.attachment.waveform") }
        guard thumbnail.count <= CommunityContentLimits.maxThumbnailBytes else {
            throw .tooLarge("community.attachment.thumbnail")
        }
        guard contentType.utf8.count <= CommunityContentLimits.maxNameBytes,
            fileName.utf8.count <= CommunityContentLimits.maxNameBytes
        else { throw .tooLarge("community.attachment.name") }
    }

    /// The pointer every member gets: the upload's delete token stays with
    /// the sender. Caption travels as the message's `text`; only VOICE
    /// retains waveform samples.
    public var communityPointer: BRPAttachment {
        var pointer = self
        pointer.deleteToken = Data()
        pointer.caption = ""
        if kind != .voice { pointer.waveform = Data() }
        return pointer
    }
}

/// A community caption as senders send it: trimmed, and whitespace-only as empty.
public enum CommunityCaption {
    public static func normalized(_ caption: String) -> String {
        let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.unicodeScalars.contains(where: { !$0.properties.isWhitespace }) ? trimmed : ""
    }

    /// Whether `text` can be sent as a text message (not only white space, ≤ 8192 bytes).
    public static func isSendableText(_ text: String) -> Bool {
        text.utf8.count <= CommunityContentLimits.maxTextBytes
            && text.unicodeScalars.contains(where: { !$0.properties.isWhitespace })
    }
}
