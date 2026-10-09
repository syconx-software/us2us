import Foundation
import Us2usKit
import Us2usStorage

/// What one inbound message changed, applied after its transaction committed.
struct InboundOutcome {
    var peer: AccountId
    var listChanged = false
    var messagesChanged = false
    var newMessage = false
    var notices: [SystemNotice] = []
    var safetyNumberChanged = false
    var typing: Bool?
    var presence: BRPPresenceMessage?
    var forgetPresence = false
    var downloads = false
    var wiped = false
    var wakeOutbox = false
}

extension MessagingEngine {
    /// Handles one received message in **one** storage transaction, which
    /// also commits the decryption's staged session state (atomic receive).
    /// When this returns, the message is stored or deliberately dropped and
    /// may be acknowledged; a thrown error leaves it on the relay and its
    /// staged decryption is discarded, so the redelivery decrypts again.
    public func receive(_ message: InboundMessage) async throws {
        guard running else { throw EngineError.stopped }
        let now = clock.now()
        let outcome: InboundOutcome
        do {
            outcome = try write { tx in try applyInbound(message, tx: tx, now: now) }
        } catch {
            _ = try? storage.protocolStore?.discardStagedInboundWrites()
            RuntimeLog.messaging.error("inbound not stored: \(error.logLabel, privacy: .public)")
            throw error
        }
        apply(outcome, now: now)
    }

    /// A visible message from an unknown account opens a request; a
    /// stranger's receipts, typing, presence or control messages are ignored.
    static func hasAttachment(_ data: BRPDataMessage) -> Bool {
        switch data.payload {
        case .attachment: return true
        case .sticker(let sticker): return sticker.hasCustom
        default: return false
        }
    }

    /// Whether a received content takes part in relay-id de-duplication.
    static func isRecordedForDedupe(_ content: BRPContent) -> Bool {
        switch content.body {
        case .typing, .presence: return false
        default: return true
        }
    }

    static func opensRequest(_ data: BRPDataMessage) -> Bool {
        switch data.payload {
        case .text, .attachment, .location, .sticker: return true
        case .liveLocation, .reaction, .delete, .none: return false
        }
    }

    private func applyInbound(_ message: InboundMessage, tx: StorageTransaction, now: Date) throws -> InboundOutcome {
        let sender = message.sender
        let content = message.content
        var outcome = InboundOutcome(peer: sender)
        // Re-delivered after a restart (the relay re-sends until acknowledged).
        // Typing and presence are never stored by the relay and harmless to
        // see twice: they leave no entry (no trace of when the app was used).
        if Self.isRecordedForDedupe(content) {
            if try tx.envelopes.contains(message.relayId) { return outcome }
            try tx.envelopes.record(message.relayId, at: now)
        }

        let previous = try tx.contacts.requestState(of: sender)
        // §6.4: messages from a blocked account are dropped locally.
        if previous == .blocked { return outcome }
        if previous == nil {
            guard case .data(let data) = content.body, Self.opensRequest(data) else { return outcome }
            outcome.listChanged = true
        }
        var state = try tx.contacts.noteVerifiedMessage(content, from: sender, now: now)
        if let username = message.senderUsername {
            // Verified against the directory by Us2usKit (§6.3).
            try tx.contacts.confirmUsername(username, for: sender)
        }
        if previous == .outgoingRequest, state == .outgoingRequest, message.senderUsername != nil,
            case .data(let data) = content.body, Self.opensRequest(data)
        {
            // §6.4 crossing requests: we wrote first, and so did they (only an
            // OUTGOING_REQUEST carries `sender_username`). Neither side could
            // ever accept, so both treat this as mutual acceptance; our
            // ACCEPT makes the other phone agree in any case.
            try tx.contacts.acceptCrossingRequest(from: sender)
            state = .accepted
            try tx.outbox.enqueue(.messageRequestResponse(.accept), to: sender, now: now)
            try enqueueOurAccessKey(to: sender, tx: tx, now: now)
            try sendProfileIfExchanged(sender, tx: tx, now: now)
            try shareSettingsAfterAcceptance(sender, tx: tx, now: now, outcome: &outcome)
            outcome.notices.append(.requestAccepted)
            outcome.listChanged = true
            outcome.downloads = true
            outcome.wakeOutbox = true
        }
        if message.identityChanged {
            outcome.notices.append(.safetyNumberChanged)
            outcome.safetyNumberChanged = true
        }

        switch content.body {
        case .data(let data):
            try receiveData(data, message: message, state: state, tx: tx, now: now, outcome: &outcome)
        case .receipt(let receipt):
            try receiveReceipt(receipt, from: sender, tx: tx, now: now, outcome: &outcome)
        case .typing(let typingMessage):
            if MessageRequestPolicy.maySendTypingIndicators(state: state, userEnabled: settings.typingIndicators) {
                outcome.typing = typingMessage.started
            }
        case .presence(let presenceMessage):
            outcome.presence = presenceMessage
        case .control(let control):
            try receiveControl(control, previous: previous, state: state, from: sender, tx: tx, now: now, outcome: &outcome)
        case .none:
            break
        }
        return outcome
    }

    // MARK: Data messages (§8.1–§8.5)

    private func receiveData(
        _ data: BRPDataMessage, message: InboundMessage, state: ConversationRequestState, tx: StorageTransaction,
        now: Date, outcome: inout InboundOutcome
    ) throws {
        let conversation = try tx.conversations.ensure(for: message.sender, now: now)
        let stored = try tx.messages.storeIncoming(
            data, in: conversation.id, serverTimestamp: message.serverTimestamp, receivedAt: now)
        switch stored {
        case .stored(let record):
            outcome.messagesChanged = true
            outcome.listChanged = true
            outcome.newMessage = true
            if state == .accepted && Self.hasAttachment(data) { outcome.downloads = true }
            // §8.4: delivery receipts always, for accepted contacts' visible messages.
            if ReceiptPolicy.wantsDeliveryReceipt(for: message.content, state: state) {
                try tx.outbox.enqueue(
                    BRPContent(receipt: .delivery, messageIds: [record.messageId]), to: message.sender, now: now)
                outcome.wakeOutbox = true
            }
        case .reaction, .deleted, .liveLocationUpdated:
            outcome.messagesChanged = true
            outcome.listChanged = true
        case .duplicate, .ignored:
            break
        }
    }

    // MARK: Receipts

    private func receiveReceipt(
        _ receipt: BRPReceiptMessage, from sender: AccountId, tx: StorageTransaction, now: Date,
        outcome: inout InboundOutcome
    ) throws {
        guard let conversation = try tx.conversations.forContact(sender) else { return }
        let changed = try tx.messages.applyReceipt(receipt, in: conversation.id, at: now)
        if receipt.type == .viewed {
            // A view-once item is gone from both phones once opened.
            for id in changed {
                if let message = try tx.messages.get(id, in: conversation.id, now: now, includeExpired: true),
                    message.isOutgoing, message.isViewOnce
                {
                    try tx.messages.consumeViewOnce(id, in: conversation.id, at: now)
                }
            }
        }
        outcome.messagesChanged = !changed.isEmpty
    }

    // MARK: Control messages

    private func receiveControl(
        _ control: BRPControlMessage, previous: ConversationRequestState?, state: ConversationRequestState,
        from sender: AccountId, tx: StorageTransaction, now: Date, outcome: inout InboundOutcome
    ) throws {
        switch control.action {
        case .requestResponse:
            // ACCEPT ended our outgoing request (Us2usStorage applied §6.4).
            guard previous == .outgoingRequest, state == .accepted else { return }
            outcome.notices.append(.requestAccepted)
            outcome.listChanged = true
            outcome.downloads = true
            // The requester sends its access key at once (§6.4); the profile
            // follows when both keys are exchanged (§8.7).
            try enqueueOurAccessKey(to: sender, tx: tx, now: now)
            try sendProfileIfExchanged(sender, tx: tx, now: now)
            try shareSettingsAfterAcceptance(sender, tx: tx, now: now, outcome: &outcome)
            outcome.wakeOutbox = true

        case .accessKey(let update):
            try tx.contacts.setTheirAccessKey(update.accessKey, for: sender)
            if state == .accepted { try sendProfileIfExchanged(sender, tx: tx, now: now) }
            outcome.wakeOutbox = true

        case .profile(let update):
            // Profiles are shared between accepted contacts only (§8.7).
            guard state == .accepted else { return }
            let profile = try tx.contacts.applyProfileUpdate(update, from: sender, now: now)
            outcome.forgetPresence = !profile.presenceSharing
            outcome.listChanged = true
            outcome.messagesChanged = true

        case .settings(let incoming):
            // Only between accepted contacts: a stranger's request cannot set
            // ghost mode or timers here (nor lock them with a far-future
            // `updated_at`, which Us2usStorage also clamps).
            guard state == .accepted else { return }
            let conversation = try tx.conversations.ensure(for: sender, now: now)
            let before = conversation.settings
            let after = try tx.conversations.applySettings(incoming, author: sender, to: conversation.id, now: now)
            guard after != before else { return }
            outcome.notices.append(Self.settingsNotice(before: before, after: after))
            outcome.listChanged = true
            outcome.messagesChanged = true

        case .wipe(let wipe):
            // "Delete this conversation on both phones now"; never answered.
            guard let conversation = try tx.conversations.forContact(sender) else { return }
            _ = try storage.wipeConversation(
                conversation.id, reason: wipe.reason == .ghostTimeout ? .ghostTimeout : .requested, now: now)
            forgetNotices(of: conversation.id)
            outcome.wiped = true
            outcome.listChanged = true

        case .screenshot:
            outcome.notices.append(.screenshotByContact)
            outcome.messagesChanged = true

        case .none:
            break
        }
    }

    static func settingsNotice(before: BRPConversationSettings, after: BRPConversationSettings) -> SystemNotice {
        .settingsChanged(
            SystemNotice.SettingsChange(
                ghost: GhostSettings(after.ghost), expireTimerSeconds: after.defaultExpireTimerSeconds,
                previousGhost: GhostSettings(before.ghost), previousExpireTimerSeconds: before.defaultExpireTimerSeconds))
    }

    // MARK: After the commit

    private func apply(_ outcome: InboundOutcome, now: Date) {
        let peer = outcome.peer
        for notice in outcome.notices { addNotice(notice, peer: peer, at: now) }
        if outcome.safetyNumberChanged {
            held.insert(peer)
            emit(.safetyNumberChanged(peer))
        }
        if let started = outcome.typing {
            typing[peer] = started ? now.addingTimeInterval(configuration.typingTimeout) : nil
            emit(.typing)
        }
        if let presenceMessage = outcome.presence { receivePresence(presenceMessage, from: peer, now: now) }
        if outcome.forgetPresence {
            presence.forget(peer)
            emit(.presence)
        }
        if outcome.wiped {
            typing[peer] = nil
            emit(.wiped(peer))
        }
        if outcome.messagesChanged || !outcome.notices.isEmpty { emit(.messages(peer)) }
        if outcome.listChanged { emit(.conversations) }
        if outcome.newMessage { emit(.newMessage(peer)) }
        if outcome.downloads { startDownloads() }
        if outcome.wakeOutbox { outboxWake.signal() }
        mirrorNoticeDeadline(peer)
        schedulerWake.signal()
    }

    private func receivePresence(_ message: BRPPresenceMessage, from peer: AccountId, now: Date) {
        // Reciprocal (§8.6): only eligible contacts are recorded or answered.
        guard let contact = try? read({ try $0.contacts.get(peer) }), isPresenceEligible(contact) else { return }
        presence.record(message, from: peer, at: now)
        emit(.presence)
        if PresencePolicy.shouldReply(to: message, eligible: true) { queuePresenceReply(to: peer) }
    }

    // MARK: Access keys and profiles (§6.4, §8.7)

    /// Our current access key to an accepted contact, once per key.
    func enqueueOurAccessKey(to account: AccountId, tx: StorageTransaction, now: Date) throws {
        let key = try ourAccessKey(tx)
        guard let contact = try tx.contacts.get(account), contact.requestState == .accepted,
            contact.ourAccessKeySent != key
        else { return }
        try tx.outbox.enqueue(.accessKeyUpdate(key), to: account, now: now)
        try tx.contacts.setOurAccessKeySent(key, to: account)
    }

    /// The complete `ProfileUpdate`, once the access keys are exchanged.
    func sendProfileIfExchanged(_ account: AccountId, tx: StorageTransaction, now: Date) throws {
        let key = try ourAccessKey(tx)
        guard let contact = try tx.contacts.get(account), contact.requestState == .accepted,
            contact.theirAccessKey != nil, contact.ourAccessKeySent == key, contact.profileSentAt == nil
        else { return }
        try tx.outbox.enqueue(try ownProfile(tx, now: now), to: account, now: now)
        try tx.contacts.setProfileSent(at: now, to: account)
    }

    func ownProfile(_ tx: StorageTransaction, now: Date) throws -> BRPContent {
        guard let account = try tx.account.load() else { throw EngineError.stopped }
        return .profileUpdate(
            displayName: String(settings.displayName.utf8Prefix(ContentLimits.maxDisplayNameBytes)),
            username: account.username, presenceSharing: settings.presenceSharing, updatedAt: now)
    }

    /// A conversation just became accepted. Settings we wrote while it was
    /// our outgoing request go out again (the contact dropped them, settings
    /// count only between accepted contacts); a conversation that never had
    /// settings gets the user's defaults.
    func shareSettingsAfterAcceptance(
        _ peer: AccountId, tx: StorageTransaction, now: Date, outcome: inout InboundOutcome
    ) throws {
        let conversation = try tx.conversations.ensure(for: peer, now: now)
        if let author = conversation.settingsAuthor {
            guard author == me else { return }
            try tx.outbox.enqueue(
                BRPContent(control: .settings(conversation.settings)), to: peer, conversation: conversation.id, now: now)
            outcome.wakeOutbox = true
            return
        }
        try applyDefaultSettingsIfUnset(peer, tx: tx, now: now, outcome: &outcome)
    }

    /// The user's default ghost / disappearing settings for a conversation
    /// that just became accepted and never had settings written.
    func applyDefaultSettingsIfUnset(
        _ peer: AccountId, tx: StorageTransaction, now: Date, outcome: inout InboundOutcome
    ) throws {
        let ghost = settings.defaultGhost.flatMap { $0.enabled ? $0 : nil }
        let timer = settings.defaultExpireTimerSeconds
        guard ghost != nil || timer > 0 else { return }
        let conversation = try tx.conversations.ensure(for: peer, now: now)
        guard conversation.settingsAuthor == nil else { return }
        let before = conversation.settings
        let after = try tx.conversations.updateSettings(of: conversation.id, now: now) { settings in
            settings.defaultExpireTimerSeconds = timer
            if let ghost { settings.ghost = ghost.proto }
        }
        try tx.outbox.enqueue(BRPContent(control: .settings(after)), to: peer, conversation: conversation.id, now: now)
        outcome.notices.append(Self.settingsNotice(before: before, after: after))
        outcome.wakeOutbox = true
    }
}
