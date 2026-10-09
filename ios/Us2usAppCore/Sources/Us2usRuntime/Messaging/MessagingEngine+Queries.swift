import Foundation
import Us2usKit
import Us2usStorage

extension MessagingEngine {
    /// The chat list: every visible contact with a conversation, most recent first.
    public func chatSummaries() throws -> [ChatSummary] {
        let now = clock.now()
        return try read { tx in
            let contacts = Dictionary(uniqueKeysWithValues: try tx.contacts.all().map { ($0.accountId, $0) })
            return try tx.conversations.all().compactMap { conversation -> ChatSummary? in
                guard let contact = contacts[conversation.contact] else { return nil }
                let accepted = contact.requestState == .accepted
                let last = try tx.messages.latest(in: conversation.id, now: now).map {
                    try item(from: $0, tx: tx, accepted: accepted, reactions: [])
                }
                let lastNotice = notices(of: conversation.id).last.map(MessageItem.notice)
                let latest = [last, lastNotice].compactMap { $0 }.max { $0.storedAt < $1.storedAt }
                return ChatSummary(
                    contact: ContactInfo(contact),
                    conversation: ConversationInfo(conversation, unreadCount: try tx.conversations.unreadCount(conversation.id)),
                    lastMessage: latest)
            }
            .sorted { lhs, rhs in
                let left = max(lhs.conversation.lastActivity, lhs.lastMessage?.storedAt ?? .distantPast)
                let right = max(rhs.conversation.lastActivity, rhs.lastMessage?.storedAt ?? .distantPast)
                return left > right
            }
        }
    }

    /// The newest messages of a conversation and its system lines, oldest first.
    public func messages(of peer: AccountId) throws -> [MessageItem] {
        let now = clock.now()
        let (items, conversationId) = try read { tx -> ([MessageItem], ConversationId?) in
            guard let conversation = try tx.conversations.forContact(peer) else { return ([], nil) }
            let accepted = try tx.contacts.requestState(of: peer) == .accepted
            let page = try tx.messages.page(in: conversation.id, limit: configuration.pageSize, now: now)
            let reactions = try tx.messages.reactions(on: page.map(\.messageId), in: conversation.id)
            let items = try page.map { message in
                try item(from: message, tx: tx, accepted: accepted, reactions: reactions[message.messageId] ?? [])
            }
            return (items, conversation.id)
        }
        guard let conversationId else { return [] }
        let lines = notices(of: conversationId).map(MessageItem.notice)
        guard !lines.isEmpty else { return items }
        let cutoff = items.first?.storedAt ?? .distantPast
        return (items + lines.filter { $0.storedAt >= cutoff || items.count < configuration.pageSize })
            .sorted { $0.storedAt < $1.storedAt }
    }

    public func contactInfo(_ peer: AccountId) throws -> ContactInfo? {
        try read { try $0.contacts.get(peer).map(ContactInfo.init) }
    }

    public func conversationInfo(_ peer: AccountId) throws -> ConversationInfo? {
        try read { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { return nil }
            return ConversationInfo(conversation, unreadCount: try tx.conversations.unreadCount(conversation.id))
        }
    }

    public func identityTrust(for peer: AccountId) async throws -> IdentityTrust {
        try await backend.identityTrust(for: peer)
    }

    // MARK: Mapping

    func item(from message: StoredMessage, tx: StorageTransaction, accepted: Bool, reactions: [StoredReaction]) throws
        -> MessageItem
    {
        let attachments = try tx.attachments.forMessage(message.messageId, in: message.conversationId)
        let body: MessageBody
        if message.isDeleted {
            body = .deleted
        } else if let data = message.content {
            body = Self.body(
                of: data, attachments: attachments, incoming: !message.isOutgoing, accepted: accepted,
                viewOnce: message.isViewOnce)
        } else if message.isViewOnce {
            body = .viewOnceOpened
        } else {
            body = .unsupported
        }
        var quote: QuoteInfo?
        if let data = message.content, data.hasQuote {
            // The excerpt is shown only while the quoted message itself is
            // here and stays (§9: never displayed after its deadline); the
            // reply's own copy of the text is never trusted for that.
            let preview = try Self.quotePreview(
                of: data.quote.targetMessageID, in: message.conversationId, tx: tx, now: clock.now())
            quote = QuoteInfo(targetMessageId: data.quote.targetMessageID, previewText: preview ?? "")
        }
        return MessageItem(
            messageId: message.messageId, direction: message.isOutgoing ? .outgoing : .incoming, body: body,
            sentAt: message.sentAt, storedAt: message.createdAt, quote: quote,
            expireTimerSeconds: message.expireTimerSeconds, expiresAt: message.expiresAt,
            viewOnce: message.isViewOnce,
            reactions: Dictionary(reactions.map { ($0.reactor, $0.emoji) }, uniquingKeysWith: { _, last in last }),
            status: message.isOutgoing ? OutgoingStatus(message.status) : nil,
            deliveredAt: message.timestamps.deliveredAt, readAt: message.timestamps.readAt,
            displayedAt: message.timestamps.displayedAt)
    }

    static func body(
        of data: BRPDataMessage, attachments: [StoredAttachment], incoming: Bool, accepted: Bool, viewOnce: Bool = false
    ) -> MessageBody {
        switch data.payload {
        case .text(let text):
            return .text(text.body)
        case .attachment(let attachment):
            return .attachment(
                attachmentItem(
                    attachment, stored: attachments.first, incoming: incoming, accepted: accepted, viewOnce: viewOnce))
        case .location(let location):
            return .location(
                LocationInfo(
                    latitude: location.latitude, longitude: location.longitude, accuracyMeters: location.accuracyMeters,
                    name: location.name.isEmpty ? nil : location.name,
                    address: location.address.isEmpty ? nil : location.address))
        case .liveLocation(let live):
            return .liveLocation(LiveLocationInfo(live))
        case .sticker(let sticker):
            return .sticker(
                StickerInfo(
                    packId: sticker.packID, stickerId: sticker.stickerID, emoji: sticker.emoji,
                    custom: sticker.hasCustom
                        ? attachmentItem(sticker.custom, stored: attachments.first, incoming: incoming, accepted: accepted)
                        : nil))
        case .reaction, .delete, .none:
            return .unsupported
        }
    }

    /// A view-once item never shows a preview (thumbnail, caption, waveform)
    /// before it is opened, whatever the sender put in.
    static func attachmentItem(
        _ attachment: BRPAttachment, stored: StoredAttachment?, incoming: Bool, accepted: Bool, viewOnce: Bool = false
    ) -> AttachmentItem {
        let kind: AttachmentItem.Kind
        switch attachment.kind {
        case .image: kind = .image
        case .video: kind = .video
        case .voice: kind = .voice
        case .stickerImage: kind = .stickerImage
        default: kind = .file
        }
        let availability: AttachmentItem.Availability
        switch stored?.state {
        case nil: availability = .unavailable
        case .downloaded?, .uploaded?, .pendingUpload?:
            availability = stored?.hasFile == true ? .local : .unavailable
        case .uploading?, .downloading?: availability = .transferring
        case .pendingDownload?: availability = incoming && !accepted ? .heldUntilAccepted : .remote
        case .failed?, .unavailable?: availability = .unavailable
        }
        var source = stored?.descriptor ?? attachment
        if viewOnce {
            source.thumbnail = Data()
            source.caption = ""
            source.waveform = Data()
        }
        return AttachmentItem(
            id: stored?.id ?? Data(), kind: kind, contentType: source.contentType,
            fileName: source.fileName.isEmpty ? nil : source.fileName, size: source.size, width: source.width,
            height: source.height, durationMs: source.durationMs,
            thumbnail: source.thumbnail.isEmpty ? nil : source.thumbnail,
            caption: source.caption.isEmpty ? nil : source.caption,
            waveform: source.waveform.isEmpty ? nil : source.waveform, availability: availability)
    }
}
