import Foundation
import Us2usKit
import Us2usRuntime

// Us2usRuntime's view models → the mapping layer's plain records. Field by
// field, nothing decided here (LiveMapping decides).

extension LiveChatRecord {
    init(_ summary: Us2usRuntime.ChatSummary, ownAccount: AccountId?) {
        let contact = summary.contact
        self.init(
            peer: contact.account.string,
            username: contact.username,
            usernameVerified: contact.usernameVerified,
            displayName: contact.displayName,
            requestState: LiveRequestState(contact.requestState),
            verified: contact.verified,
            safetyNumberChanged: contact.safetyNumberChanged,
            presenceSharing: contact.presenceSharing,
            ghostEnabled: summary.conversation.ghost?.enabled ?? false,
            ghostTimeoutSeconds: summary.conversation.ghost?.timeoutSeconds ?? 0,
            ghostDeadline: summary.conversation.ghostDeadline,
            expireTimerSeconds: summary.conversation.defaultExpireTimerSeconds,
            unreadCount: summary.unreadCount,
            lastActivity: summary.conversation.lastActivity,
            lastMessage: summary.lastMessage.map { LiveMessageRecord($0, ownAccount: ownAccount) },
            identityKey: contact.identityKey,
            avatar: LiveAvatar(contact.avatar))
    }
}

extension LiveBlockedRecord {
    init(_ contact: ContactInfo) {
        self.init(peer: contact.account.string, username: contact.username, displayName: contact.displayName)
    }
}

extension LiveRequestState {
    init(_ state: ConversationRequestState) {
        switch state {
        case .outgoingRequest: self = .outgoingRequest
        case .incomingRequest: self = .incomingRequest
        case .accepted: self = .accepted
        case .blocked: self = .blocked
        }
    }
}

extension LivePresence {
    init(_ status: PresenceStatus) {
        switch status {
        case .active: self = .active
        case .activeJustNow: self = .justNow
        case .activeMinutesAgo(let minutes): self = .minutesAgo(minutes)
        case .none: self = .none
        }
    }
}

extension LiveMessageRecord {
    init(_ item: MessageItem, ownAccount: AccountId?) {
        let direction: Direction =
            switch item.direction {
            case .incoming: .incoming
            case .outgoing: .outgoing
            case .system: .system
            }
        self.init(
            id: item.messageId, direction: direction, body: LiveBody(item.body), sentAt: item.sentAt,
            quoteTarget: item.quote?.targetMessageId, quoteText: item.quote?.previewText,
            expireTimerSeconds: item.expireTimerSeconds, viewOnce: item.viewOnce,
            reactions: item.reactions.sorted { $0.key < $1.key }.map { (byMe: $0.key == ownAccount, emoji: $0.value) },
            status: item.status.map(LiveOutgoingStatus.init), deliveredAt: item.deliveredAt, readAt: item.readAt,
            viewedAt: item.viewedAt, savedByPeerAt: item.savedByPeerAt, savedByMeAt: item.savedByMeAt)
    }
}

extension Us2usRuntime.MediaKind {
    init(_ kind: ChatMediaKind) {
        switch kind {
        case .visual: self = .visual
        case .documents: self = .documents
        }
    }
}

extension LiveOutgoingStatus {
    init(_ status: OutgoingStatus) {
        switch status {
        case .sending: self = .sending
        case .sent: self = .sent
        case .delivered: self = .delivered
        case .read: self = .read
        case .notDelivered: self = .notDelivered
        case .failed: self = .failed
        }
    }
}

extension LiveBody {
    init(_ body: MessageBody) {
        switch body {
        case .text(let text): self = .text(text)
        case .attachment(let attachment): self = .attachment(LiveAttachment(attachment))
        case .location(let location):
            self = .location(
                name: location.name, address: location.address, latitude: location.latitude,
                longitude: location.longitude)
        case .liveLocation(let live):
            self = .liveLocation(sessionId: live.sessionId, expiresAt: live.expiresAt, stopped: live.stopped)
        case .sticker(let sticker):
            self = .sticker(
                packID: sticker.packId, stickerID: sticker.stickerId, emoji: sticker.emoji, lang: sticker.lang)
        case .deleted: self = .deleted
        case .viewOnceOpened: self = .viewOnceOpened
        case .notice(let notice): self = .notice(LiveNotice(notice))
        case .unsupported: self = .unsupported
        }
    }
}

extension LiveAttachment {
    init(_ item: AttachmentItem) {
        let kind: Kind =
            switch item.kind {
            case .image: .image
            case .video: .video
            case .voice: .voice
            case .file: .file
            case .stickerImage: .stickerImage
            }
        let availability: Availability =
            switch item.availability {
            case .local: .local
            case .transferring: .transferring
            case .remote: .remote
            case .heldUntilAccepted: .heldUntilAccepted
            case .unavailable: .unavailable
            }
        self.init(
            kind: kind, contentType: item.contentType, fileName: item.fileName, size: item.size,
            durationMs: item.durationMs, thumbnail: item.thumbnail, caption: item.caption, waveform: item.waveform,
            availability: availability, width: item.width, height: item.height)
    }
}

extension LiveNotice {
    init(_ notice: SystemNotice) {
        switch notice {
        case .screenshotByMe: self = .screenshotByMe
        case .screenRecordingByMe: self = .screenRecordingByMe
        case .screenshotByContact: self = .screenshotByContact
        case .safetyNumberChanged: self = .safetyNumberChanged
        // Builds 4–6 wrote `.safetyNumberAccepted` only when verifying.
        case .safetyNumberAccepted, .safetyNumberVerified: self = .safetyNumberVerified
        case .safetyNumberAcceptedUnverified: self = .safetyNumberAccepted
        case .requestAccepted: self = .requestAccepted
        case .settingsChanged(let change):
            self = .settingsChanged(
                ghostEnabled: change.ghost.enabled, ghostTimeout: change.ghost.timeoutSeconds,
                expireTimer: change.expireTimerSeconds, previousGhostEnabled: change.previousGhost.enabled,
                previousGhostTimeout: change.previousGhost.timeoutSeconds,
                previousExpireTimer: change.previousExpireTimerSeconds)
        }
    }
}

extension LiveMapping.LiveConnection {
    init(_ status: ConnectionStatus) {
        switch status {
        case .offline: self = .offline
        case .connecting: self = .connecting
        case .syncing: self = .syncing
        case .open: self = .open
        case .waitingForNetwork: self = .waitingForNetwork
        case .retrying: self = .retrying
        case .revoked: self = .revoked
        }
    }
}
