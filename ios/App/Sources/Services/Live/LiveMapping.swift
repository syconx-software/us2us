import CryptoKit
import Foundation

// The mapping layer of the live service: plain records (`Live*`, built from
// Us2usRuntime's view models in RuntimeSnapshots.swift) → the UI models the
// screens render (Domain/Models.swift). Pure functions, no runtime and no
// I/O, so unit tests drive them with hand-made records.

// MARK: - Records

enum LiveRequestState: Equatable, Sendable {
    case outgoingRequest, incomingRequest, accepted, blocked
}

enum LivePresence: Equatable, Sendable {
    case active, justNow, minutesAgo(Int), none
}

enum LiveOutgoingStatus: Equatable, Sendable {
    case sending, sent, delivered, read, notDelivered, failed
}

struct LiveAttachment: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case image, video, voice, file, stickerImage
    }

    enum Availability: Equatable, Sendable {
        case local, transferring, remote, heldUntilAccepted, unavailable
    }

    var kind: Kind
    var contentType: String
    var fileName: String?
    var size: UInt64
    var durationMs: UInt32
    var thumbnail: Data?
    var caption: String?
    var waveform: Data?
    var availability: Availability
    /// Pixel size as sent (0: not given).
    var width: UInt32 = 0
    var height: UInt32 = 0
}

/// An attachment being fetched: asked for by the user, or reported by the
/// runtime with its progress.
struct LiveDownload: Equatable, Sendable {
    /// 0…1, `nil` until the size is known.
    var fraction: Double?
}

enum LiveNotice: Equatable, Sendable {
    case screenshotByMe
    case screenRecordingByMe
    case screenshotByContact
    case safetyNumberChanged
    case safetyNumberAccepted
    case safetyNumberVerified
    case requestAccepted
    case settingsChanged(
        ghostEnabled: Bool, ghostTimeout: UInt32, expireTimer: UInt32, previousGhostEnabled: Bool,
        previousGhostTimeout: UInt32, previousExpireTimer: UInt32)
}

enum LiveBody: Equatable, Sendable {
    case text(String)
    case attachment(LiveAttachment)
    case location(name: String?, address: String?, latitude: Double, longitude: Double)
    case liveLocation(sessionId: Data, expiresAt: Date, stopped: Bool)
    /// A sticker (PROTOCOL §8.8) as it travelled; `lang` as received.
    case sticker(packID: String, stickerID: UInt32, emoji: String, lang: String)
    case deleted
    case viewOnceOpened
    case notice(LiveNotice)
    case unsupported
}

struct LiveMessageRecord: Equatable, Sendable {
    enum Direction: Equatable, Sendable {
        case incoming, outgoing, system
    }

    var id: Data
    var direction: Direction
    var body: LiveBody
    var sentAt: Date
    var quoteTarget: Data?
    var quoteText: String?
    var expireTimerSeconds: UInt32 = 0
    var viewOnce = false
    /// Emoji per reacting account; `byMe` tells the user's own.
    var reactions: [(byMe: Bool, emoji: String)] = []
    var status: LiveOutgoingStatus?
    var deliveredAt: Date?
    var readAt: Date?
    /// A voice note played — outgoing: by the contact; incoming: here.
    var viewedAt: Date?
    /// Outgoing photo, video or file: the contact saved it (their SAVED, §8.4).
    var savedByPeerAt: Date? = nil
    /// Saved outside the app on this phone.
    var savedByMeAt: Date? = nil

    static func == (lhs: LiveMessageRecord, rhs: LiveMessageRecord) -> Bool {
        lhs.id == rhs.id && lhs.direction == rhs.direction && lhs.body == rhs.body && lhs.sentAt == rhs.sentAt
            && lhs.quoteTarget == rhs.quoteTarget && lhs.quoteText == rhs.quoteText
            && lhs.expireTimerSeconds == rhs.expireTimerSeconds && lhs.viewOnce == rhs.viewOnce
            && lhs.reactions.map(\.emoji) == rhs.reactions.map(\.emoji)
            && lhs.reactions.map(\.byMe) == rhs.reactions.map(\.byMe) && lhs.status == rhs.status
            && lhs.deliveredAt == rhs.deliveredAt && lhs.readAt == rhs.readAt && lhs.viewedAt == rhs.viewedAt
            && lhs.savedByPeerAt == rhs.savedByPeerAt && lhs.savedByMeAt == rhs.savedByMeAt
    }
}

struct LiveChatRecord: Equatable, Sendable {
    /// The contact's account id (canonical string) — the app's `ChatID`.
    var peer: String
    var username: String?
    /// The directory confirmed the username (§3.3 / §6.3).
    var usernameVerified = true
    var displayName: String?
    var requestState: LiveRequestState
    var verified: Bool
    var safetyNumberChanged: Bool
    var presenceSharing: Bool
    var ghostEnabled: Bool
    var ghostTimeoutSeconds: UInt32
    var ghostDeadline: Date?
    var expireTimerSeconds: UInt32
    var unreadCount: Int
    var lastActivity: Date
    var lastMessage: LiveMessageRecord?
    /// The contact's current identity key (serialized), `nil` before one is known.
    var identityKey: Data? = nil
    /// The contact's picture as the runtime has it (accepted contacts: their
    /// last profile update); for the user's own request, the search copy
    /// found this session (LiveMapping+Avatars.swift decides what is drawn).
    var avatar: LiveAvatar = .initials
}

/// A blocked contact, with or without a conversation (7.1 › Engellenenler).
struct LiveBlockedRecord: Equatable, Sendable {
    /// The contact's account id (canonical string) — the app's `ChatID`.
    var peer: String
    var username: String?
    var displayName: String?
}

// MARK: - Mapping

enum LiveMapping {
    // MARK: Ids

    /// Message ids are 16 random bytes; the UI keys them as lower-case hex.
    static func messageID(_ data: Data) -> MessageID {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func messageData(_ id: MessageID) -> Data? {
        let base = id.split(separator: "#").first.map(String.init) ?? id
        guard base.count % 2 == 0, !base.isEmpty else { return nil }
        var data = Data(capacity: base.count / 2)
        var index = base.startIndex
        while index < base.endIndex {
            let next = base.index(index, offsetBy: 2)
            guard let byte = UInt8(base[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }

    // MARK: Settings values

    static func ghostPeriod(seconds: UInt32) -> GhostPeriod {
        let value = TimeInterval(seconds)
        return GhostPeriod.allCases.min { abs($0.duration - value) < abs($1.duration - value) } ?? .oneDay
    }

    static func seconds(_ period: GhostPeriod) -> UInt32 { UInt32(period.duration) }

    static func disappearing(seconds: UInt32) -> DisappearingTimer {
        guard seconds > 0 else { return .off }
        let value = TimeInterval(seconds)
        let timed = DisappearingTimer.allCases.filter { $0.duration != nil }
        return timed.min { abs(($0.duration ?? 0) - value) < abs(($1.duration ?? 0) - value) } ?? .off
    }

    static func seconds(_ timer: DisappearingTimer) -> UInt32 { UInt32(timer.duration ?? 0) }

    static func status(_ status: LiveOutgoingStatus) -> MessageStatus {
        switch status {
        case .sending: .sending
        case .sent: .sent
        case .delivered: .delivered
        case .read: .read
        case .notDelivered, .failed: .failed
        }
    }

    /// Reciprocal (§8.6): with presence off on this device nobody's shows.
    static func presence(_ presence: LivePresence?, now: Date, ownSharing: Bool) -> Presence {
        guard ownSharing, let presence else { return .hidden }
        switch presence {
        case .active: return .online
        case .justNow: return .justNow
        case .minutesAgo(let minutes): return .lastSeen(now.addingTimeInterval(-TimeInterval(minutes) * 60))
        case .none: return .hidden
        }
    }

    // MARK: Chats

    /// The name the UI shows: the username (the design's only name), else the
    /// display name, else a short form of the account id.
    static func username(of chat: LiveChatRecord) -> String {
        shownName(username: chat.username, displayName: chat.displayName, peer: chat.peer)
    }

    static func shownName(username: String?, displayName: String?, peer: String) -> String {
        if let username, !username.isEmpty { return username }
        if let displayName, !displayName.isEmpty { return displayName }
        return String(peer.prefix(8))
    }

    static func peer(_ chat: LiveChatRecord, presence: LivePresence?, now: Date, ownSharing: Bool) -> Peer {
        let name = username(of: chat)
        let accepted = chat.requestState == .accepted
        return Peer(
            username: name,
            palette: chat.requestState == .incomingRequest ? .c : AvatarPalette.forUsername(name),
            isVerified: chat.verified && !chat.safetyNumberChanged,
            isAcceptedContact: accepted,
            presence: accepted && chat.presenceSharing ? Self.presence(presence, now: now, ownSharing: ownSharing) : .hidden,
            safetyNumberChanged: chat.safetyNumberChanged,
            identityFingerprint: chat.identityKey.map { Self.identityFingerprint($0) },
            avatar: avatar(of: chat), isBlocked: chat.requestState == .blocked)
    }

    /// Who a report names: the username alone — never the display name or
    /// the account id `username(of:)` falls back to for showing.
    static func reportSubject(_ chat: LiveChatRecord) -> ReportSubject {
        guard let username = chat.username, !username.isEmpty else {
            return ReportSubject(username: nil, isUsernameVerified: false)
        }
        return ReportSubject(username: username, isUsernameVerified: chat.usernameVerified)
    }

    // MARK: Blocked people (7.1 › Engellenenler)

    /// Someone blocked, as 4.1 would name them (a chat may be gone); the
    /// initial in the palette of that name, never a picture.
    static func blockedPerson(_ record: LiveBlockedRecord) -> BlockedPerson {
        let name = shownName(username: record.username, displayName: record.displayName, peer: record.peer)
        return BlockedPerson(
            id: record.peer, name: name, isUsernameKnown: !(record.username ?? "").isEmpty,
            palette: .forUsername(name))
    }

    static func blockedPeople(_ records: [LiveBlockedRecord]) -> [BlockedPerson] {
        BlockedPerson.listed(records.map(blockedPerson))
    }

    static func ghost(_ chat: LiveChatRecord) -> GhostState {
        guard chat.ghostEnabled else { return .off }
        return GhostState(isEnabled: true, period: ghostPeriod(seconds: chat.ghostTimeoutSeconds), deadline: chat.ghostDeadline)
    }

    /// Someone blocked has no unread count: a blocked request's messages
    /// were never displayed, and a badge would only invite the user back
    /// into the chat of a person they just blocked.
    static func chatSummary(_ chat: LiveChatRecord, presence: LivePresence?, now: Date, ownSharing: Bool) -> ChatSummary {
        ChatSummary(
            id: chat.peer, peer: peer(chat, presence: presence, now: now, ownSharing: ownSharing),
            lastMessage: chat.lastMessage.flatMap { preview(of: $0, now: now) },
            unreadCount: chat.requestState == .blocked ? 0 : chat.unreadCount,
            ghost: ghost(chat), disappearing: disappearing(seconds: chat.expireTimerSeconds))
    }

    static func previewKind(of record: LiveMessageRecord, now: Date) -> MessagePreviewKind? {
        if record.expireTimerSeconds > 0, record.direction == .incoming { return .disappearing }
        switch record.body {
        case .text(let text): return .text(text)
        case .attachment(let attachment):
            if record.viewOnce { return .photoViewOnce }
            switch attachment.kind {
            case .image: return .photo
            case .video: return .video
            case .voice: return .voice
            case .file: return .file(attachment.fileName ?? "")
            case .stickerImage: return .sticker
            }
        case .location: return .location
        case .liveLocation(_, let expiresAt, let stopped):
            return !stopped && now < expiresAt ? .liveLocation : .liveLocationEnded
        case .sticker: return .sticker
        case .deleted: return .deleted
        case .viewOnceOpened: return .photoViewOnce
        case .notice, .unsupported: return nil
        }
    }

    static func preview(of record: LiveMessageRecord, now: Date) -> MessagePreview? {
        guard record.direction != .system, let kind = previewKind(of: record, now: now) else { return nil }
        return MessagePreview(
            kind: kind, direction: record.direction == .outgoing ? .outgoing : .incoming,
            status: record.status.map(status), sentAt: record.sentAt)
    }

    // MARK: Requests (4.3 / 4.4)

    static func request(_ chat: LiveChatRecord, now: Date) -> MessageRequest {
        var flags: MessageRequest.Flags = []
        var messages: [Message] = []
        let sender = username(of: chat)
        if let last = chat.lastMessage {
            if case .attachment = last.body { flags.insert(.hasMedia) }
            if case .text(let text) = last.body, containsLink(text) { flags.insert(.hasLink) }
            messages = message(last, ownUsername: "", peerUsername: sender, lookup: [:], now: now, media: [:])
        }
        // The directory did not confirm the claimed name (§3.3).
        if chat.username == nil || !chat.usernameVerified { flags.insert(.suspicious) }
        return MessageRequest(
            id: chat.peer, sender: sender, receivedAt: chat.lastMessage?.sentAt ?? chat.lastActivity,
            preview: chat.lastMessage.flatMap { previewKind(of: $0, now: now) } ?? .text(""), flags: flags,
            messages: messages, avatar: .initials, offersAvatar: offersAvatar(of: chat))
    }

    static func containsLink(_ text: String) -> Bool {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return false
        }
        return detector.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    // MARK: Messages

    /// A whole conversation, oldest first. `media` holds decrypted full images
    /// (by message id) loaded so far; until then a bubble shows the inline
    /// thumbnail. `downloads` are the attachments being fetched right now,
    /// with their progress when it is known. `showsPeerPlayback`: whether an
    /// outgoing voice message may show that the other side played it (read
    /// receipts on — they are mutual).
    static func messages(
        _ records: [LiveMessageRecord], ownUsername: String, peerUsername: String, now: Date, media: [MessageID: Data],
        downloads: [MessageID: LiveDownload] = [:], showsPeerPlayback: Bool = true
    ) -> [Message] {
        var lookup: [Data: LiveMessageRecord] = [:]
        for record in records { lookup[record.id] = record }
        return records.flatMap {
            message(
                $0, ownUsername: ownUsername, peerUsername: peerUsername, lookup: lookup, now: now, media: media,
                downloads: downloads, showsPeerPlayback: showsPeerPlayback)
        }
    }

    /// One record → zero (unsupported), one, or — for a settings notice that
    /// changed both Ghost and the timer — two rows.
    static func message(
        _ record: LiveMessageRecord, ownUsername: String, peerUsername: String, lookup: [Data: LiveMessageRecord],
        now: Date, media: [MessageID: Data], downloads: [MessageID: LiveDownload] = [:], showsPeerPlayback: Bool = true
    ) -> [Message] {
        let id = messageID(record.id)
        let direction: MessageDirection = record.direction == .outgoing ? .outgoing : .incoming
        if case .notice(let notice) = record.body {
            return events(of: notice, ownUsername: ownUsername, peerUsername: peerUsername).enumerated().map {
                index, event in
                Message(
                    id: index == 0 ? id : "\(id)#\(index)", direction: .incoming, sentAt: record.sentAt,
                    content: .system(event))
            }
        }
        guard let content = content(of: record, id: id, now: now, media: media) else { return [] }
        var message = Message(
            id: id, direction: direction, sentAt: record.sentAt, content: content,
            status: direction == .outgoing ? record.status.map(status) ?? .sending : nil)
        if let text = record.quoteText {
            let target = record.quoteTarget.flatMap { lookup[$0] }
            let author = target?.direction == .outgoing ? ownUsername : peerUsername
            message.replyTo = QuotedMessage(
                author: author, kind: .text(text), thumbnail: nil, messageID: record.quoteTarget.map(messageID))
        }
        if case .attachment(let attachment) = record.body, !record.viewOnce {
            message.mediaAvailability = availability(
                attachment, outgoing: direction == .outgoing, download: downloads[id])
            if attachment.width > 0, attachment.height > 0 {
                message.mediaSize = MediaDimensions(width: Int(attachment.width), height: Int(attachment.height))
            }
            if attachment.kind == .voice {
                message.isVoicePlayed = record.viewedAt != nil && (direction == .incoming || showsPeerPlayback)
            }
            // Saved outside the app (§8.4): told whatever the read-receipt setting.
            let savableKinds: [LiveAttachment.Kind] = [.image, .video, .file]
            let savable = savableKinds.contains(attachment.kind) && record.expireTimerSeconds == 0
            message.savedByPeer = savable && direction == .outgoing && record.savedByPeerAt != nil
            message.savedByPeerAt = message.savedByPeer ? record.savedByPeerAt : nil
            message.savedByMe = savable && record.savedByMeAt != nil
        }
        message.reactions = reactions(record.reactions)
        message.disappearing = disappearing(seconds: record.expireTimerSeconds)
        message.deliveredAt = record.deliveredAt
        message.readAt = record.readAt
        if record.status == .notDelivered || record.status == .failed { message.failedAt = record.sentAt }
        return [message]
    }

    static func content(of record: LiveMessageRecord, id: MessageID, now: Date, media: [MessageID: Data])
        -> MessageContent?
    {
        switch record.body {
        case .text(let text):
            return .text(text)
        case .attachment(let attachment):
            if record.viewOnce {
                return .viewOnce(isVideo: attachment.kind == .video, state: .unopened)
            }
            let source = MediaSource.memory(media[id] ?? attachment.thumbnail ?? Data())
            switch attachment.kind {
            case .image:
                return .photo(source, caption: attachment.caption)
            case .video:
                // A video sent from here this session keeps the poster its
                // bubble showed while it was prepared (sharper than the
                // inline thumbnail).
                return .video(
                    .memory(media[id] ?? attachment.thumbnail ?? Data()),
                    duration: TimeInterval(attachment.durationMs) / 1000, byteCount: Int64(attachment.size))
            case .voice:
                return .voice(
                    duration: TimeInterval(attachment.durationMs) / 1000, waveform: waveform(attachment.waveform),
                    playedFraction: 0)
            case .file:
                let name = attachment.fileName ?? ""
                return .file(
                    name: name, byteCount: Int64(attachment.size), typeLabel: typeLabel(fileName: name),
                    pageCount: nil)
            case .stickerImage:
                return .photo(source, caption: nil)
            }
        case .location(let name, let address, let latitude, let longitude):
            return .location(name: name ?? "", locality: address ?? "", latitude: latitude, longitude: longitude)
        case .liveLocation(_, let expiresAt, let stopped):
            return .liveLocation(expiresAt: expiresAt, isActive: !stopped && now < expiresAt)
        case .sticker(let packID, let stickerID, let emoji, let lang):
            // The plain text stickers of earlier builds keep their card;
            // every other sticker is resolved by the bubble, in the reader's
            // language only where the sender's is missing or unknown.
            if packID == BrandStickerCatalog.legacyTextPackID,
                case .legacyText(let text) = BrandStickerCatalog.resolve(
                    packID: packID, stickerID: stickerID, emoji: emoji, lang: lang, fallback: .turkish)
            {
                return .textSticker(text)
            }
            return .sticker(packID: packID, stickerID: stickerID, emoji: emoji, lang: lang)
        case .deleted:
            return .deleted
        case .viewOnceOpened:
            return .viewOnce(isVideo: false, state: .opened)
        case .notice, .unsupported:
            return nil
        }
    }

    /// Where the attachment's file is, as its bubble shows it. What this
    /// phone sent is here (also while it uploads); what it received is
    /// downloading while the runtime transfers it or the user asked for it
    /// (`download`: the fraction when known).
    static func availability(_ attachment: LiveAttachment, outgoing: Bool, download: LiveDownload? = nil)
        -> MediaAvailability
    {
        switch attachment.availability {
        case .local: return .ready
        case .transferring: return outgoing ? .ready : .downloading(download?.fraction)
        case .remote: return download.map { .downloading($0.fraction) } ?? .remote
        case .heldUntilAccepted: return .held
        case .unavailable: return .unavailable
        }
    }

    static func events(of notice: LiveNotice, ownUsername: String, peerUsername: String) -> [SystemEvent] {
        switch notice {
        case .screenshotByMe, .screenRecordingByMe: return [.screenshotTaken(by: ownUsername)]
        case .screenshotByContact: return [.screenshotTaken(by: peerUsername)]
        case .safetyNumberChanged: return [.safetyNumberChanged]
        case .safetyNumberAccepted: return [.safetyNumberAccepted]
        case .safetyNumberVerified: return [.safetyNumberVerified]
        case .requestAccepted: return []
        case .settingsChanged(
            let ghostEnabled, let ghostTimeout, let expireTimer, let previousGhostEnabled, let previousGhostTimeout,
            let previousExpireTimer):
            var events: [SystemEvent] = []
            if ghostEnabled != previousGhostEnabled || (ghostEnabled && ghostTimeout != previousGhostTimeout) {
                events.append(ghostEnabled ? .ghostEnabled(ghostPeriod(seconds: ghostTimeout)) : .ghostDisabled)
            }
            if expireTimer != previousExpireTimer {
                events.append(.disappearingChanged(disappearing(seconds: expireTimer)))
            }
            return events
        }
    }

    /// One reaction pill per emoji, in first-seen order.
    static func reactions(_ entries: [(byMe: Bool, emoji: String)]) -> [Reaction] {
        var order: [String] = []
        var byEmoji: [String: Reaction] = [:]
        for entry in entries {
            if var existing = byEmoji[entry.emoji] {
                existing.count += 1
                existing.includesMe = existing.includesMe || entry.byMe
                byEmoji[entry.emoji] = existing
            } else {
                order.append(entry.emoji)
                byEmoji[entry.emoji] = Reaction(emoji: entry.emoji, count: 1, includesMe: entry.byMe)
            }
        }
        return order.compactMap { byEmoji[$0] }
    }

    /// The voice note's waveform bytes (up to 100, 0…255) → the design's 30
    /// bars (0…1), each the mean of an equal stretch.
    static func waveform(_ data: Data?, bars: Int = 30) -> [Double] {
        VoiceWaveform.bars(data, count: bars)
    }

    static func typeLabel(fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension
        return ext.isEmpty ? "FILE" : ext.uppercased()
    }

    // MARK: Verification

    /// The contact code's fingerprint: SHA-256 of the identity key the relay
    /// serves, first 16 bytes, lower-case hex.
    static func identityFingerprint(_ identityKey: Data) -> String {
        Data(SHA256.hash(data: identityKey)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Constant-time comparison of a scanned fingerprint with the served key's.
    static func fingerprintMatches(_ scanned: String, identityKey: Data) -> Bool {
        let expected = Data(identityFingerprint(identityKey).utf8)
        let given = Data(scanned.lowercased().utf8)
        return SafetyQRCodec.constantTimeEquals(expected, given)
    }

    // MARK: Backup

    /// The backup file header (`"BRPB"` ‖ version 1, Us2usKit `AccountBackupFile`).
    static func looksLikeBackup(_ header: Data) -> Bool {
        header.count >= 5 && header.prefix(4) == Data("BRPB".utf8) && header[header.startIndex + 4] == 1
    }

    // MARK: Connection

    enum LiveConnection: Equatable, Sendable {
        case offline, connecting, syncing, open, waitingForNetwork, retrying, revoked
    }

    static func connection(_ status: LiveConnection) -> ConnectionState {
        switch status {
        case .open, .syncing: .open
        case .connecting, .retrying: .connecting
        case .offline, .waitingForNetwork: .closed
        case .revoked: .revoked
        }
    }
}
