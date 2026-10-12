import Foundation

/// Random 16-byte identifiers (brp.proto: "All 16-byte ids are random").
public enum RandomId {
    public static let byteCount = 16

    public static func make() -> Data { SecureRandom.bytes(byteCount) }
}

extension Date {
    /// Milliseconds since the Unix epoch, the timestamp unit of the protocol (§2).
    public var millisecondsSince1970: UInt64 { UInt64(max(0, timeIntervalSince1970 * 1000)) }
}

extension BRPDataMessage {
    public init(
        payload: OneOf_Payload,
        messageId: Data = RandomId.make(),
        sentAt: Date = Date(),
        expireTimerSeconds: UInt32 = 0,
        viewOnce: Bool = false,
        quote: BRPQuote? = nil
    ) {
        self.init()
        self.payload = payload
        self.messageID = messageId
        self.sentAt = sentAt.millisecondsSince1970
        self.expireTimerSeconds = expireTimerSeconds
        self.viewOnce = viewOnce
        if let quote { self.quote = quote }
    }
}

extension BRPContent {
    public init(data: BRPDataMessage) {
        self.init()
        self.body = .data(data)
    }

    public init(receipt type: BRPReceiptMessage.TypeEnum, messageIds: [Data]) {
        self.init()
        var receipt = BRPReceiptMessage()
        receipt.type = type
        receipt.messageIds = messageIds
        self.body = .receipt(receipt)
    }

    public init(typingStarted: Bool) {
        self.init()
        var typing = BRPTypingMessage()
        typing.started = typingStarted
        self.body = .typing(typing)
    }

    /// Presence is always sent ephemeral (§8.6); see ``PresencePolicy``.
    public init(presence: BRPPresenceMessage) {
        self.init()
        self.body = .presence(presence)
    }

    public init(control action: BRPControlMessage.OneOf_Action) {
        self.init()
        var control = BRPControlMessage()
        control.action = action
        self.body = .control(control)
    }

    /// A text bubble.
    public static func text(
        _ body: String, expireTimerSeconds: UInt32 = 0, quote: BRPQuote? = nil, messageId: Data = RandomId.make()
    ) -> BRPContent {
        var text = BRPText()
        text.body = body
        return BRPContent(
            data: BRPDataMessage(
                payload: .text(text), messageId: messageId, expireTimerSeconds: expireTimerSeconds, quote: quote))
    }

    /// An access key handed to an accepted contact (§4.6, §6.4).
    public static func accessKeyUpdate(_ accessKey: Data) -> BRPContent {
        var update = BRPAccessKeyUpdate()
        update.accessKey = accessKey
        return BRPContent(control: .accessKey(update))
    }

    public static func messageRequestResponse(_ type: BRPMessageRequestResponse.TypeEnum) -> BRPContent {
        var response = BRPMessageRequestResponse()
        response.type = type
        return BRPContent(control: .requestResponse(response))
    }

    /// The complete profile shared with accepted contacts only (§3.3, §8.7):
    /// every field is set, and the receiver replaces its stored profile with
    /// it. Sent once after the access-key exchange and again on every change
    /// (a new profile picture is one, §8.9). The picture goes in
    /// `profile_avatar` (field 6), the initial letter as none; the deprecated
    /// `avatar` (field 2) is never set.
    public static func profileUpdate(
        displayName: String, username: NormalizedUsername, presenceSharing: Bool, picture: ProfilePicture = .initials,
        updatedAt: Date = Date()
    ) -> BRPContent {
        var profile = BRPProfileUpdate()
        profile.displayName = displayName
        profile.username = username.value
        profile.presenceSharing = presenceSharing
        if let avatar = picture.profileAvatar { profile.profileAvatar = avatar }
        profile.updatedAt = updatedAt.millisecondsSince1970
        return BRPContent(control: .profile(profile))
    }
}

/// Limits from the comments in brp.proto.
public enum ContentLimits {
    public static let maxTextBytes = 8192
    public static let maxCaptionBytes = 2048
    public static let maxQuotePreviewCharacters = 200
    public static let maxThumbnailBytes = 10 * 1024
    public static let maxDisplayNameBytes = 64
    public static let maxWaveformSamples = 100
    public static let ghostTimeoutRange: ClosedRange<UInt32> = 60...604_800
    static let attachmentKeyBytes = 64
    static let sha256Bytes = 32
    static let deleteTokenBytes = 32
    static let accessKeyBytes = 16
}

public enum ContentValidationError: Error, Equatable, Sendable {
    /// A binary id or key has the wrong length.
    case malformedField(String)
    /// A text field exceeds its documented limit.
    case tooLarge(String)
}

extension BRPContent {
    /// Structural checks that every received `Content` must pass: fixed-size
    /// ids and keys and the ghost timeout range. Unknown (newer) payloads pass,
    /// so old clients keep working when the schema grows.
    public func validateStructure() throws(ContentValidationError) {
        switch body {
        case .data(let data): try data.validateStructure()
        case .receipt(let receipt):
            guard receipt.messageIds.allSatisfy({ $0.count == RandomId.byteCount }) else {
                throw .malformedField("receipt.message_ids")
            }
        case .control(let control): try control.validateStructure()
        case .typing, .presence, .none: break
        }
    }

    /// Structural checks plus the size limits a sender must respect.
    public func validateForSending() throws(ContentValidationError) {
        try validateStructure()
        if case .data(let data) = body { try data.validateSizes() }
        if case .receipt(let receipt) = body, receipt.messageIds.count > ReceiptPolicy.maxMessageIds {
            throw .tooLarge("receipt.message_ids")
        }
        if case .control(let control) = body, case .profile(let profile) = control.action {
            guard profile.displayName.utf8.count <= ContentLimits.maxDisplayNameBytes else {
                throw .tooLarge("profile.display_name")
            }
            // §8.7: field 2 is never sent; a picture must pass §8.9 as sent.
            guard !profile.hasAvatar else { throw .malformedField("profile.avatar") }
            if profile.hasProfileAvatar {
                guard let picture = ProfilePicture.validated(profile.profileAvatar), picture.isSendable else {
                    throw .malformedField("profile.profile_avatar")
                }
            }
        }
    }
}

extension BRPDataMessage {
    fileprivate func validateStructure() throws(ContentValidationError) {
        try requireId(messageID, "message_id")
        if hasQuote { try requireId(quote.targetMessageID, "quote.target_message_id") }
        switch payload {
        case .attachment(let attachment): try attachment.validateStructure()
        case .liveLocation(let live): try requireId(live.sessionID, "live_location.session_id")
        case .sticker(let sticker): if sticker.hasCustom { try sticker.custom.validateStructure() }
        case .reaction(let reaction): try requireId(reaction.targetMessageID, "reaction.target_message_id")
        case .delete(let delete): try requireId(delete.targetMessageID, "delete.target_message_id")
        case .text, .location, .none: break
        }
    }

    fileprivate func validateSizes() throws(ContentValidationError) {
        if case .text(let text) = payload, text.body.utf8.count > ContentLimits.maxTextBytes {
            throw .tooLarge("text.body")
        }
        if hasQuote, quote.previewText.count > ContentLimits.maxQuotePreviewCharacters {
            throw .tooLarge("quote.preview_text")
        }
        if case .attachment(let attachment) = payload { try attachment.validateSizes() }
    }
}

extension BRPAttachment {
    fileprivate func validateStructure() throws(ContentValidationError) {
        try requireId(mediaID, "attachment.media_id")
        guard deleteToken.count == ContentLimits.deleteTokenBytes else {
            throw .malformedField("attachment.delete_token")
        }
        guard key.count == ContentLimits.attachmentKeyBytes else { throw .malformedField("attachment.key") }
        guard digest.count == ContentLimits.sha256Bytes else { throw .malformedField("attachment.digest") }
    }

    fileprivate func validateSizes() throws(ContentValidationError) {
        guard caption.utf8.count <= ContentLimits.maxCaptionBytes else { throw .tooLarge("attachment.caption") }
        guard thumbnail.count <= ContentLimits.maxThumbnailBytes else { throw .tooLarge("attachment.thumbnail") }
        guard waveform.count <= ContentLimits.maxWaveformSamples else { throw .tooLarge("attachment.waveform") }
    }
}

extension BRPControlMessage {
    fileprivate func validateStructure() throws(ContentValidationError) {
        switch action {
        case .accessKey(let update):
            guard update.accessKey.count == ContentLimits.accessKeyBytes else {
                throw .malformedField("access_key.access_key")
            }
        case .settings(let settings):
            if settings.ghost.enabled, !ContentLimits.ghostTimeoutRange.contains(settings.ghost.timeoutSeconds) {
                throw .malformedField("settings.ghost.timeout_seconds")
            }
        case .profile:
            // Nothing here can make a ProfileUpdate unusable (§8.7): the
            // deprecated `avatar` (field 2) is ignored unvalidated, and a
            // malformed `profile_avatar` reads as the initial letter
            // (``ContactProfile/applying(_:)``).
            break
        case .requestResponse, .wipe, .screenshot, .none: break
        }
    }
}

private func requireId(_ id: Data, _ field: String) throws(ContentValidationError) {
    guard id.count == RandomId.byteCount else { throw .malformedField(field) }
}

extension BRPConversationSettings {
    /// Last-writer-wins merge (brp.proto): the later `updated_at` wins; on a tie
    /// the settings written by the lexicographically smaller account id win.
    public static func resolve(
        _ lhs: (settings: BRPConversationSettings, author: AccountId),
        _ rhs: (settings: BRPConversationSettings, author: AccountId)
    ) -> BRPConversationSettings {
        if lhs.settings.updatedAt != rhs.settings.updatedAt {
            return lhs.settings.updatedAt > rhs.settings.updatedAt ? lhs.settings : rhs.settings
        }
        return lhs.author <= rhs.author ? lhs.settings : rhs.settings
    }
}
