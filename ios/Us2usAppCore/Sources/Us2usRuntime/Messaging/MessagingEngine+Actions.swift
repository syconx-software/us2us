import Foundation
import Us2usKit
import Us2usStorage

/// What to send as a bubble.
public enum OutgoingContent: Sendable {
    /// The longest `Sticker.lang` sent: a BCP-47 tag is at most 35 characters (§8.8).
    public static let maxStickerLangLength = 35
    /// The pack of the plain text stickers of builds up to 1.0.5 (§8.8):
    /// still read, never sent.
    public static let legacyTextStickerPack = "us2us.text"
    /// What a resend of such a sticker sends: `words.ok` of the catalog
    /// (“Tamam!” / “OK!”), without a language.
    public static let legacyTextStickerSuccessor = (packId: "us2us.words", stickerId: UInt32(109), emoji: "👌")

    case text(String)
    case location(LocationInfo)
    /// A built-in sticker (PROTOCOL.md §8.8). `lang` is the language its
    /// words were shown in (“tr”, “en”); empty for a wordless sticker.
    case sticker(packId: String, stickerId: UInt32, emoji: String, lang: String = "")
    /// A prepared (compressed, metadata-stripped) file from ``MediaPreparation``;
    /// it is moved into encrypted storage.
    case media(PreparedMedia)
}

extension MessagingEngine {
    // MARK: Requests (§6.4)

    /// Finds `username` (exact match, §4.4) and opens a conversation as an
    /// outgoing request. Nothing is sent until the first message.
    ///
    /// Someone blocked gets their read-only chat back (Ghost may have erased
    /// it, and with it the only place that offers “Engeli kaldır”); nothing
    /// is sent to them until they are unblocked.
    public func startConversation(username: NormalizedUsername) async throws -> AccountId {
        guard let account = try await backend.findAccount(username: username) else {
            throw EngineError.usernameNotFound
        }
        guard account != me else { throw EngineError.ownAccount }
        let now = clock.now()
        try write { tx in
            switch try tx.contacts.requestState(of: account) {
            case nil: try tx.contacts.create(account, state: .outgoingRequest, now: now)
            case .blocked?: try tx.contacts.unblockAsOutgoingRequest(account)
            default: break
            }
            // Our own lookup proves the username belongs to this account (§3.3).
            try tx.contacts.confirmUsername(username, for: account)
            _ = try tx.conversations.ensure(for: account, now: now)
        }
        emit(.conversations)
        return account
    }

    /// Accepts an incoming request (§6.4): `ACCEPT` is sent directly, and the
    /// state changes only after the relay answered `202`, so a failure leaves
    /// the request as it was and accepting can be retried. Then our access
    /// key, the profile once both keys are exchanged, the delivery receipts
    /// the request's messages did not get, and its held media.
    public func acceptRequest(_ peer: AccountId) async throws {
        guard let contact = try read({ try $0.contacts.get(peer) }) else { throw EngineError.unknownContact }
        guard contact.requestState == .incomingRequest else { throw EngineError.notAllowed }
        _ = try await backend.send(
            .messageRequestResponse(.accept), to: peer, requestState: .incomingRequest,
            theirAccessKey: contact.theirAccessKey, ephemeral: false)
        let now = clock.now()
        var outcome = InboundOutcome(peer: peer)
        try write { tx in
            try tx.contacts.acceptIncomingRequest(from: peer)
            try enqueueOurAccessKey(to: peer, tx: tx, now: now)
            try sendProfileIfExchanged(peer, tx: tx, now: now)
            if let conversation = try tx.conversations.forContact(peer) {
                let owed = try tx.messages.page(in: conversation.id, limit: configuration.pageSize, now: now)
                    .filter { !$0.isOutgoing && !$0.isDeleted && Self.wantsReceipt($0.kind) }
                    .map(\.messageId)
                for chunk in stride(from: 0, to: owed.count, by: ReceiptPolicy.maxMessageIds) {
                    let ids = Array(owed[chunk..<min(chunk + ReceiptPolicy.maxMessageIds, owed.count)])
                    try tx.outbox.enqueue(BRPContent(receipt: .delivery, messageIds: ids), to: peer, now: now)
                }
            }
            try shareSettingsAfterAcceptance(peer, tx: tx, now: now, outcome: &outcome)
        }
        for notice in outcome.notices { addNotice(notice, peer: peer, at: now) }
        addNotice(.requestAccepted, peer: peer, at: now)
        outboxWake.signal()
        startDownloads()
        emit(.conversations)
        emit(.messages(peer))
    }

    /// "Sil": deletes the request (crypto-shredded) and the contact. Nothing
    /// is sent, so the requester cannot tell whether it was seen.
    public func declineRequest(_ peer: AccountId) throws {
        try write { tx in
            guard try tx.contacts.requestState(of: peer) == .incomingRequest else { throw EngineError.notAllowed }
            if let conversation = try tx.conversations.forContact(peer) { forgetNotices(of: conversation.id) }
            try tx.contacts.forget(peer)
        }
        typing[peer] = nil
        emit(.wiped(peer))
        emit(.conversations)
    }

    /// Blocks a contact (§6.4): its messages are dropped locally from now on,
    /// pending sends to it are cancelled (their messages become *Failed*,
    /// to be sent again by hand after an unblock), and our access key is
    /// rotated — the new one goes to every other accepted contact, so the
    /// blocked account cannot use the old one.
    public func block(_ peer: AccountId) async throws {
        let now = clock.now()
        try write { tx in
            try tx.contacts.block(peer, now: now)
            for entry in try tx.outbox.all() where entry.recipient == peer {
                if let messageId = entry.messageId, let conversationId = entry.conversationId {
                    try tx.messages.markFailed(messageId, in: conversationId, at: now)
                }
                try tx.outbox.remove(entry.id)
            }
        }
        presence.forget(peer)
        typing[peer] = nil
        held.remove(peer)
        runtime.pendingAccessKeyRotation = true
        saveRuntime()
        emit(.conversations)
        emit(.messages(peer))
        emit(.presence)
        await rotateAccessKeyIfPending()
    }

    /// Unblocks: the contact returns to its state before the block. An
    /// accepted contact gets our *current* access key (the block rotated
    /// it, and the new one went to everyone else) and our profile again, so
    /// it can deliver with `X-Access-Key` instead of proof-of-work (§6.4).
    /// Nothing happens for a contact that is not blocked (a second tap on
    /// “Engeli kaldır” sends nothing twice). One with nothing to go back to
    /// — no conversation, and no contact before the block (7.1 ›
    /// Engellenenler lists bare blocks “leave no trace” kept) — is
    /// forgotten: a stranger again, not an empty request.
    public func unblock(_ peer: AccountId) throws {
        let now = clock.now()
        let unblocked = try write { tx -> Bool in
            if try tx.contacts.unblockLeavingNothing(peer) { return true }
            guard try tx.contacts.unblock(peer) else { return false }
            guard let contact = try tx.contacts.get(peer), contact.requestState == .accepted else { return true }
            try enqueueOurAccessKey(to: peer, tx: tx, now: now)
            if contact.profileSentAt != nil {
                try tx.outbox.enqueue(try ownProfile(tx, now: now), to: peer, now: now)
                try tx.contacts.setProfileSent(at: now, to: peer)
            } else {
                try sendProfileIfExchanged(peer, tx: tx, now: now)
            }
            return true
        }
        guard unblocked else { return }
        outboxWake.signal()
        emit(.conversations)
    }

    /// Finishes the access-key rotation a block asked for; retried after
    /// every connection until it succeeds.
    func rotateAccessKeyIfPending() async {
        guard runtime.pendingAccessKeyRotation else { return }
        do {
            let key = try await backend.rotateAccessKey()
            runtime.pendingAccessKeyRotation = false
            saveRuntime()
            let now = clock.now()
            try write { tx in
                for contact in try tx.contacts.needingAccessKey(current: key) {
                    try tx.outbox.enqueue(.accessKeyUpdate(key), to: contact.accountId, now: now)
                    try tx.contacts.setOurAccessKeySent(key, to: contact.accountId)
                }
            }
            outboxWake.signal()
        } catch {
            RuntimeLog.messaging.info("access key rotation postponed: \(error.logLabel, privacy: .public)")
        }
    }

    /// After a restore (§5.5 step 5): the restored phone has no sessions, so
    /// it writes first, to every accepted contact.
    func finishRestoreAnnouncementIfNeeded() async {
        guard await backend.needsProfileAnnouncement() else { return }
        let now = clock.now()
        guard let (profile, contacts) = try? read({ tx in
            (try ownProfile(tx, now: now), try tx.contacts.accepted().map { ($0.accountId, $0.theirAccessKey) })
        }) else { return }
        guard (try? await backend.announceProfile(profile, to: contacts)) != nil else { return }
        _ = try? write { tx in
            for (account, _) in contacts { try tx.contacts.setProfileSent(at: now, to: account) }
        }
    }

    // MARK: Composing (§8)

    /// Stores a bubble (status *Sending*) and queues it; the outbox sends it
    /// when it can, so it survives no network, locking and restarts. Media
    /// is moved into encrypted storage and uploaded first.
    ///
    /// `messageId`: an id the app made with ``RandomId/make()`` beforehand —
    /// a video's bubble is on screen while it is still being prepared, and
    /// keeps its place when it is stored under that id. It must be new to
    /// the conversation (``EngineError/invalidMessage`` otherwise).
    @discardableResult
    public func send(
        _ content: OutgoingContent, to peer: AccountId, quote: QuoteInfo? = nil, viewOnce: Bool = false,
        messageId: MessageId? = nil
    ) throws -> MessageId {
        let now = clock.now()
        if let messageId, messageId.count != RandomId.byteCount { throw EngineError.invalidMessage }
        let id = messageId ?? RandomId.make()
        let payload: BRPDataMessage.OneOf_Payload
        var media: PreparedMedia?
        switch content {
        case .text(let text):
            guard !text.isEmpty, text.utf8.count <= ContentLimits.maxTextBytes else { throw EngineError.tooLarge }
            var proto = BRPText()
            proto.body = text
            payload = .text(proto)
        case .location(let location):
            var proto = BRPLocation()
            proto.latitude = location.latitude
            proto.longitude = location.longitude
            proto.accuracyMeters = location.accuracyMeters
            proto.name = location.name ?? ""
            proto.address = location.address ?? ""
            payload = .location(proto)
        case .sticker(let packId, let stickerId, let emoji, let lang):
            guard lang.count <= OutgoingContent.maxStickerLangLength else { throw EngineError.tooLarge }
            var proto = BRPSticker()
            proto.packID = packId
            proto.stickerID = stickerId
            proto.emoji = emoji
            proto.lang = lang
            payload = .sticker(proto)
        case .media(let prepared):
            guard (prepared.caption?.utf8.count ?? 0) <= ContentLimits.maxCaptionBytes,
                (prepared.thumbnail?.count ?? 0) <= ContentLimits.maxThumbnailBytes
            else { throw EngineError.tooLarge }
            // View-once means nothing of it is visible before it is opened:
            // a caption would be (and would stay while it is never opened).
            if viewOnce, !(prepared.caption ?? "").isEmpty { throw EngineError.notAllowed }
            payload = .attachment(prepared.pendingAttachment)
            media = prepared
        }
        try write { tx in
            // §6.4: sending to an account without state makes it an outgoing
            // request; answering a request means accepting it first.
            let state = try tx.contacts.prepareToSend(to: peer, now: now)
            guard state != .incomingRequest else { throw EngineError.notAllowed }
            let conversation = try tx.conversations.ensure(for: peer, now: now)
            var quoteProto: BRPQuote?
            if let quote {
                var proto = BRPQuote()
                proto.targetMessageID = quote.targetMessageId
                // The excerpt comes from the stored target, never from the
                // caller, and only from one that stays (§9).
                proto.previewText = try Self.quotePreview(
                    of: quote.targetMessageId, in: conversation.id, tx: tx, now: now) ?? ""
                quoteProto = proto
            }
            let data = BRPDataMessage(
                payload: payload, messageId: id, sentAt: now,
                expireTimerSeconds: conversation.settings.defaultExpireTimerSeconds,
                viewOnce: viewOnce && media != nil, quote: quoteProto
            ).strippingViewOncePreview()
            // A chosen id that is already taken stores nothing: refused, not queued twice.
            guard try tx.messages.storeOutgoing(data, in: conversation.id, at: now) != .duplicate else {
                throw EngineError.invalidMessage
            }
            if let media {
                for attachment in try tx.attachments.forMessage(id, in: conversation.id) {
                    try tx.attachments.setState(.pendingUpload, of: attachment.id, now: now)
                    try tx.attachments.importFile(at: media.file, for: attachment.id, now: now)
                }
            }
            try tx.outbox.enqueue(
                BRPContent(data: data), to: peer, conversation: conversation.id, messageId: id,
                waitingForUpload: media != nil, now: now)
        }
        afterLocalChange(peer)
        return id
    }

    /// "Tekrar gönder": a failed message goes back into the queue; a message
    /// the relay dropped after 14 days (*Not delivered*) is sent again as new.
    @discardableResult
    public func resend(_ messageId: MessageId, peer: AccountId) throws -> MessageId {
        let now = clock.now()
        let retried = try write { tx -> Bool in
            guard let conversation = try tx.conversations.forContact(peer),
                let message = try tx.messages.get(messageId, in: conversation.id, now: now), message.isOutgoing
            else { throw EngineError.invalidMessage }
            if let entry = try tx.outbox.all(state: .failed).first(where: { $0.messageId == messageId }) {
                try tx.outbox.retry(entry.id, now: now)
                return true
            }
            return false
        }
        if retried {
            afterLocalChange(peer)
            return messageId
        }
        guard let message = try read({ tx -> StoredMessage? in
            guard let conversation = try tx.conversations.forContact(peer) else { return nil }
            return try tx.messages.get(messageId, in: conversation.id, now: now)
        }), let data = message.content else { throw EngineError.invalidMessage }
        switch data.payload {
        case .text(let text): return try send(.text(text.body), to: peer)
        case .location(let location):
            return try send(
                .location(
                    LocationInfo(
                        latitude: location.latitude, longitude: location.longitude,
                        accuracyMeters: location.accuracyMeters)), to: peer)
        case .sticker(let sticker) where !sticker.hasCustom && sticker.packID == OutgoingContent.legacyTextStickerPack:
            // The plain text sticker of builds up to 1.0.5 is not sent any
            // more (§8.8): its catalog successor “Tamam!” / “OK!” goes
            // instead, in the reader's language.
            let ok = OutgoingContent.legacyTextStickerSuccessor
            return try send(.sticker(packId: ok.packId, stickerId: ok.stickerId, emoji: ok.emoji), to: peer)
        case .sticker(let sticker) where !sticker.hasCustom:
            return try send(
                .sticker(packId: sticker.packID, stickerId: sticker.stickerID, emoji: sticker.emoji, lang: sticker.lang),
                to: peer)
        default:
            throw EngineError.invalidMessage
        }
    }

    /// Adds (`emoji`) or removes (`nil`) our reaction (§8.5). Not to an
    /// unaccepted request: nothing goes back before the user accepts.
    public func react(to target: MessageId, peer: AccountId, emoji: String?) throws {
        let now = clock.now()
        try write { tx in
            guard let state = try tx.contacts.requestState(of: peer) else { throw EngineError.unknownContact }
            guard state == .accepted || state == .outgoingRequest else { throw EngineError.notAllowed }
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            guard try tx.messages.get(target, in: conversation.id, now: now) != nil else {
                throw EngineError.invalidMessage
            }
            var reaction = BRPReaction()
            reaction.targetMessageID = target
            reaction.emoji = emoji ?? ""
            reaction.remove = emoji == nil
            let data = BRPDataMessage(payload: .reaction(reaction), sentAt: now)
            _ = try tx.messages.storeOutgoing(data, in: conversation.id, at: now)
            try tx.outbox.enqueue(BRPContent(data: data), to: peer, conversation: conversation.id, now: now)
        }
        afterLocalChange(peer)
    }

    /// Delete for everyone (§8.5), our own messages only: leaves a tombstone,
    /// deletes the local media and unsends the uploaded blob. A message the
    /// relay has not accepted yet (still queued, retrying, uploading or
    /// failed) and that is not being handed over right now never left this
    /// phone: its queue entry is removed and no `Delete` is sent, so its
    /// content never reaches the relay or the contact.
    public func deleteForEveryone(_ messageId: MessageId, peer: AccountId) async throws {
        let now = clock.now()
        let uploaded = try write { tx -> [BRPAttachment] in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            let blobs = try tx.attachments.forMessage(messageId, in: conversation.id).compactMap { attachment in
                attachment.descriptor.flatMap { $0.mediaID.isEmpty ? nil : $0 }
            }
            let queued = try tx.outbox.entries(for: messageId, in: conversation.id)
            let neverLeft = !queued.isEmpty && queued.allSatisfy { !inFlightOutbox.contains($0.id) }
            do {
                try tx.messages.deleteForEveryone(messageId, in: conversation.id, at: now)
            } catch {
                throw EngineError.invalidMessage
            }
            if neverLeft {
                for entry in queued { try tx.outbox.remove(entry.id) }
            } else {
                var delete = BRPDelete()
                delete.targetMessageID = messageId
                try tx.outbox.enqueue(
                    BRPContent(data: BRPDataMessage(payload: .delete(delete), sentAt: now)), to: peer,
                    conversation: conversation.id, now: now)
            }
            return blobs
        }
        afterLocalChange(peer)
        for blob in uploaded { try? await backend.deleteUploadedMedia(blob) }
    }

    /// Delete for me: removes the message on this phone only (an outgoing
    /// one that was not handed to the relay yet is not sent any more).
    public func deleteForMe(_ messageId: MessageId, peer: AccountId) throws {
        let now = clock.now()
        try write { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            try tx.messages.deleteLocally([messageId], in: conversation.id, at: now)
        }
        emit(.messages(peer))
        emit(.conversations)
    }

    /// The excerpt a reply may carry: only of a target that is here and
    /// stays — no timer, not view-once, not deleted (§9: never displayed
    /// after its deadline, also not inside a reply). `nil` otherwise.
    static func quotePreview(of target: MessageId, in conversationId: ConversationId, tx: StorageTransaction, now: Date)
        throws -> String?
    {
        guard let message = try tx.messages.get(target, in: conversationId, now: now), !message.isDeleted,
            !message.isViewOnce, message.expireTimerSeconds == 0, let data = message.content
        else { return nil }
        let text: String
        switch data.payload {
        case .text(let body): text = body.body
        case .attachment(let attachment): text = attachment.caption
        case .location(let location): text = location.name
        default: text = ""
        }
        return text.isEmpty ? nil : String(text.prefix(ContentLimits.maxQuotePreviewCharacters))
    }

    func afterLocalChange(_ peer: AccountId) {
        outboxWake.signal()
        schedulerWake.signal()
        mirrorNoticeDeadline(peer)
        emit(.messages(peer))
        emit(.conversations)
    }

    static func wantsReceipt(_ kind: MessageKind) -> Bool {
        switch kind {
        case .text, .attachment, .location, .sticker: return true
        case .liveLocation, .unknown: return false
        }
    }

    // MARK: Reading (§8.1, §8.4)

    /// The conversation is on screen: received messages are displayed now,
    /// which starts their disappearing timers (T after first display), and
    /// read receipts go out if enabled (never for requests, never for view-once).
    public func markDisplayed(_ peer: AccountId) throws {
        let now = clock.now()
        let changed = try write { tx -> Bool in
            guard let conversation = try tx.conversations.forContact(peer) else { return false }
            let displayed = try tx.messages.markAllDisplayed(in: conversation.id, at: now)
            guard !displayed.isEmpty else { return false }
            let state = try tx.contacts.requestState(of: peer)
            if MessageRequestPolicy.maySendReadReceipts(state: state ?? .blocked, userEnabled: settings.readReceipts) {
                let ids = try displayed.filter { id in
                    guard let message = try tx.messages.get(id, in: conversation.id, now: now) else { return false }
                    return !message.isViewOnce && Self.wantsReceipt(message.kind)
                }
                for chunk in stride(from: 0, to: ids.count, by: ReceiptPolicy.maxMessageIds) {
                    let slice = Array(ids[chunk..<min(chunk + ReceiptPolicy.maxMessageIds, ids.count)])
                    try tx.outbox.enqueue(BRPContent(receipt: .read, messageIds: slice), to: peer, now: now)
                }
            }
            return true
        }
        guard changed else { return }
        outboxWake.signal()
        schedulerWake.signal()
        emit(.messages(peer))
        emit(.conversations)
    }

    // MARK: Media

    /// A received voice note was played for the first time (§8.4): its
    /// `viewedAt` is set once, and VIEWED goes to the sender only where a
    /// read receipt would (an accepted contact, read receipts on). Anything
    /// else — not a voice note, view-once, deleted, played before — changes
    /// nothing.
    public func markVoiceNotePlayed(_ messageId: MessageId, peer: AccountId) throws {
        let now = clock.now()
        let changed = try write { tx -> Bool in
            guard let conversation = try tx.conversations.forContact(peer),
                let message = try tx.messages.get(messageId, in: conversation.id, now: now),
                !message.isOutgoing, !message.isViewOnce, !message.isDeleted,
                case .attachment(let attachment)? = message.content?.payload, attachment.kind == .voice
            else { return false }
            guard try tx.messages.markPlayed(messageId, in: conversation.id, at: now) else { return false }
            let state = try tx.contacts.requestState(of: peer)
            if MessageRequestPolicy.maySendReadReceipts(state: state ?? .blocked, userEnabled: settings.readReceipts) {
                try tx.outbox.enqueue(BRPContent(receipt: .viewed, messageIds: [messageId]), to: peer, now: now)
            }
            return true
        }
        guard changed else { return }
        outboxWake.signal()
        emit(.messages(peer))
    }

    /// A photo, video or file was saved outside the app (§8.4): `savedByMeAt`
    /// is set once and, for a received one from an accepted contact, SAVED
    /// goes to the sender in the same transaction — whatever the read-receipt
    /// setting, because saving is an explicit act the user is told about
    /// first. Saving again sends nothing more; our own messages are only
    /// marked here. View-once, disappearing and deleted items cannot be
    /// saved: `invalidMessage`.
    public func markMediaSaved(_ messageId: MessageId, peer: AccountId) throws {
        let now = clock.now()
        let changed = try write { tx -> Bool in
            guard let conversation = try tx.conversations.forContact(peer),
                let message = try tx.messages.get(messageId, in: conversation.id, now: now), !message.isDeleted,
                !message.isViewOnce, message.expireTimerSeconds == 0,
                let content = message.content, ReceiptPolicy.isSavable(content)
            else { throw EngineError.invalidMessage }
            guard try tx.messages.markSavedByMe(messageId, in: conversation.id, at: now) else { return false }
            let state = try tx.contacts.requestState(of: peer)
            if !message.isOutgoing, MessageRequestPolicy.maySendSavedReceipts(state: state ?? .blocked) {
                try tx.outbox.enqueue(BRPContent(receipt: .saved, messageIds: [messageId]), to: peer, now: now)
            }
            return true
        }
        guard changed else { return }
        outboxWake.signal()
        emit(.messages(peer))
    }

    /// Opens a received view-once item **once**: in one transaction its
    /// bytes are read into memory, the item is destroyed here (body, file,
    /// descriptor) and the VIEWED receipt is queued, so the sender destroys
    /// its copy. Nothing can open it again, also not after a crash or a
    /// force-quit while the viewer is up. Returns the bytes (prefer this for
    /// images: no plaintext file at all).
    public func openViewOnceData(_ messageId: MessageId, peer: AccountId) throws -> Data {
        let now = clock.now()
        let plaintext = try write { tx in try consumeViewOnceForViewing(messageId, peer: peer, tx: tx, now: now) }
        outboxWake.signal()
        emit(.messages(peer))
        return plaintext
    }

    /// ``openViewOnceData(_:peer:)`` for players that need a file (video):
    /// the bytes are written to a staging file (complete protection), which
    /// ``closeViewOnce(_:peer:displayedFile:)`` deletes; staging is also
    /// emptied on every lock and at every launch.
    public func openViewOnce(_ messageId: MessageId, peer: AccountId) throws -> URL {
        let now = clock.now()
        let url = storage.makeStagingURL()
        do {
            try write { tx in
                var plaintext = try consumeViewOnceForViewing(messageId, peer: peer, tx: tx, now: now)
                defer { SecretKeyMaterial.zeroize(&plaintext) }
                try plaintext.write(to: url, options: [.atomic, .completeFileProtection])
            }
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
        outboxWake.signal()
        emit(.messages(peer))
        return url
    }

    private func consumeViewOnceForViewing(_ messageId: MessageId, peer: AccountId, tx: StorageTransaction, now: Date)
        throws -> Data
    {
        guard let conversation = try tx.conversations.forContact(peer),
            let message = try tx.messages.get(messageId, in: conversation.id, now: now),
            message.isViewOnce, !message.isOutgoing, message.content != nil
        else { throw EngineError.invalidMessage }
        guard let attachment = try tx.attachments.forMessage(messageId, in: conversation.id).first,
            attachment.hasFile
        else { throw EngineError.mediaUnavailable }
        let plaintext = try tx.attachments.readFile(attachment.id)
        try tx.messages.consumeViewOnce(messageId, in: conversation.id, at: now)
        if try tx.contacts.requestState(of: peer) == .accepted {
            try tx.outbox.enqueue(BRPContent(receipt: .viewed, messageIds: [messageId]), to: peer, now: now)
        }
        return plaintext
    }

    /// The viewer closed: deletes its file. The item was destroyed when it
    /// was opened; an item still intact (opened some other way) is consumed now.
    public func closeViewOnce(_ messageId: MessageId, peer: AccountId, displayedFile: URL?) throws {
        if let displayedFile { try? FileManager.default.removeItem(at: displayedFile) }
        let now = clock.now()
        let consumed = try write { tx -> Bool in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            guard let message = try tx.messages.get(messageId, in: conversation.id, now: now, includeExpired: true),
                message.isViewOnce, !message.isOutgoing, message.content != nil
            else { return false }
            try tx.messages.consumeViewOnce(messageId, in: conversation.id, at: now)
            if try tx.contacts.requestState(of: peer) == .accepted {
                try tx.outbox.enqueue(BRPContent(receipt: .viewed, messageIds: [messageId]), to: peer, now: now)
            }
            return true
        }
        guard consumed else { return }
        outboxWake.signal()
        emit(.messages(peer))
    }

    /// A stored attachment (not view-once), decrypted into memory — for
    /// images prefer this to ``exportMedia(_:peer:)``: no plaintext file.
    public func mediaData(_ messageId: MessageId, peer: AccountId) throws -> Data {
        try read { tx in
            let attachment = try displayableAttachment(messageId, peer: peer, tx: tx)
            return try tx.attachments.readFile(attachment.id)
        }
    }

    /// Decrypts a stored attachment (not view-once) into a staging file for
    /// players and the share sheet. The copy is deleted as soon as its
    /// message disappears, is deleted or wiped, and when the app locks.
    public func exportMedia(_ messageId: MessageId, peer: AccountId) throws -> URL {
        let (url, conversationId) = try read { tx -> (URL, ConversationId) in
            let attachment = try displayableAttachment(messageId, peer: peer, tx: tx)
            return (try tx.attachments.exportToStaging(attachment.id), attachment.conversationId)
        }
        exportedMedia[url] = (conversationId, messageId)
        return url
    }

    private func displayableAttachment(_ messageId: MessageId, peer: AccountId, tx: StorageTransaction) throws
        -> StoredAttachment
    {
        guard let conversation = try tx.conversations.forContact(peer),
            let message = try tx.messages.get(messageId, in: conversation.id, now: clock.now()), !message.isViewOnce,
            !message.isDeleted
        else { throw EngineError.invalidMessage }
        guard let attachment = try tx.attachments.forMessage(messageId, in: conversation.id).first,
            attachment.hasFile
        else { throw EngineError.mediaUnavailable }
        return attachment
    }

    // MARK: Typing (§8.4)

    /// Our typing indicator, if enabled: ephemeral, accepted contacts with a
    /// key only, "started" at most every few seconds.
    public func setTyping(_ started: Bool, peer: AccountId) async {
        guard settings.typingIndicators,
            let contact = try? read({ try $0.contacts.get(peer) }), let key = contact.theirAccessKey,
            MessageRequestPolicy.maySendTypingIndicators(state: contact.requestState, userEnabled: true),
            !contact.identityChanged
        else { return }
        let now = clock.now()
        if started, let last = lastTypingSent[peer], now.timeIntervalSince(last) < configuration.typingResendInterval {
            return
        }
        lastTypingSent[peer] = started ? now : nil
        _ = try? await backend.send(
            BRPContent(typingStarted: started), to: peer, requestState: contact.requestState, theirAccessKey: key,
            ephemeral: true)
    }

    // MARK: Conversation settings (§8.1, §8.2)

    /// Sets ghost mode and the default disappearing timer for both sides
    /// (LWW; a system line on each phone).
    public func updateConversationSettings(
        _ peer: AccountId, ghost: GhostSettings?, defaultExpireTimerSeconds: UInt32
    ) throws {
        if let ghost, ghost.enabled, !ContentLimits.ghostTimeoutRange.contains(ghost.timeoutSeconds) {
            throw EngineError.tooLarge
        }
        let now = clock.now()
        let notice = try write { tx -> SystemNotice in
            guard let state = try tx.contacts.requestState(of: peer), state != .blocked else {
                throw EngineError.unknownContact
            }
            // Nothing goes to an unaccepted requester (§6.4): accept first.
            guard state == .accepted || state == .outgoingRequest else { throw EngineError.notAllowed }
            let conversation = try tx.conversations.ensure(for: peer, now: now)
            let before = conversation.settings
            let after = try tx.conversations.updateSettings(of: conversation.id, now: now) { settings in
                settings.defaultExpireTimerSeconds = defaultExpireTimerSeconds
                settings.ghost = (ghost ?? GhostSettings(enabled: false, timeoutSeconds: 0)).proto
            }
            try tx.outbox.enqueue(
                BRPContent(control: .settings(after)), to: peer, conversation: conversation.id, now: now)
            return Self.settingsNotice(before: before, after: after)
        }
        addNotice(notice, peer: peer, at: now)
        afterLocalChange(peer)
    }

    // MARK: Screenshots (§9: iOS can only detect them)

    /// A screenshot or screen recording while `peer`'s conversation was on
    /// screen: a local line, and a `ScreenshotNotice` to accepted contacts.
    public func screenCaptured(in peer: AccountId, recording: Bool) throws {
        let now = clock.now()
        let accepted = try write { tx -> Bool in
            guard let state = try tx.contacts.requestState(of: peer), state != .blocked else { return false }
            guard state == .accepted, let conversation = try tx.conversations.forContact(peer) else { return false }
            var notice = BRPScreenshotNotice()
            notice.takenAt = now.millisecondsSince1970
            try tx.outbox.enqueue(
                BRPContent(control: .screenshot(notice)), to: peer, conversation: conversation.id, now: now)
            return true
        }
        addNotice(recording ? .screenRecordingByMe : .screenshotByMe, peer: peer, at: now)
        if accepted { outboxWake.signal() }
    }

    // MARK: Safety numbers (§5)

    /// Accepts a changed safety number without verifying it: sending
    /// resumes, the contact stays unverified. Bound to the key whose number
    /// the user was shown (`shownNumber`, the `SafetyNumber.scannable`):
    /// `false`, and nothing changes — no line, no wake — when the key
    /// changed since or no change was pending.
    @discardableResult
    public func acceptSafetyNumberChange(_ peer: AccountId, shownNumber: Data) async throws -> Bool {
        guard try await backend.approveIdentityChange(for: peer, shownNumber: shownNumber) else { return false }
        held.remove(peer)
        addNotice(.safetyNumberAcceptedUnverified, peer: peer, at: clock.now())
        outboxWake.signal()
        emit(.conversations)
        return true
    }

    /// “Doğrulandı olarak işaretle”: verifies the contact's key if its safety
    /// number is still `comparedNumber`, the one the user compared (a
    /// pending change of that key is accepted with it). `false`, and
    /// nothing changes, when the key changed meanwhile.
    public func markVerified(_ peer: AccountId, comparedNumber: Data) async throws -> Bool {
        let verification = IdentityVerification(method: .manual, verifiedAt: clock.now())
        let wasVerified = await isIdentityVerified(peer)
        guard try await backend.markVerified(contact: peer, comparedNumber: comparedNumber, verification: verification)
        else { return false }
        didVerify(peer, wasVerified: wasVerified)
        return true
    }

    /// 6.2: compares a safety-number QR payload scanned from the contact's
    /// phone and, on a match, verifies the key it matched — one step.
    public func verifySafetyNumber(_ peer: AccountId, scanned: Data) async throws -> Bool {
        let verification = IdentityVerification(method: .qrCode, verifiedAt: clock.now())
        let wasVerified = await isIdentityVerified(peer)
        guard try await backend.verifySafetyNumber(contact: peer, scanned: scanned, verification: verification)
        else { return false }
        didVerify(peer, wasVerified: wasVerified)
        return true
    }

    /// “QR kodla ekle”: pins the key a contact code scanned side by side
    /// committed to (`identityKey`, served by the directory and found equal
    /// to the code's fingerprint). Call it once the contact exists. `false`
    /// when another key is stored for the contact: nothing is verified.
    public func verifyContactCode(_ peer: AccountId, identityKey: Data) async throws -> Bool {
        let verification = IdentityVerification(method: .qrCode, verifiedAt: clock.now())
        let wasVerified = await isIdentityVerified(peer)
        guard try await backend.pinVerifiedIdentity(identityKey, for: peer, verification: verification) else {
            return false
        }
        didVerify(peer, wasVerified: wasVerified)
        return true
    }

    /// “Doğrulamayı kaldır”: the seal goes; the key stays accepted.
    public func clearVerification(_ peer: AccountId) async throws {
        try await backend.clearVerification(contact: peer)
        emit(.conversations)
    }

    public func identityVerification(for peer: AccountId) async throws -> IdentityVerification? {
        try await backend.identityVerification(for: peer)
    }

    private func isIdentityVerified(_ peer: AccountId) async -> Bool {
        guard case .trusted(_, verified: true)? = try? await backend.identityTrust(for: peer) else { return false }
        return true
    }

    /// A verification was stored: a held queue goes out, and a line says so
    /// (once — verifying a verified key again adds none).
    private func didVerify(_ peer: AccountId, wasVerified: Bool) {
        held.remove(peer)
        if !wasVerified { addNotice(.safetyNumberVerified, peer: peer, at: clock.now()) }
        outboxWake.signal()
        emit(.conversations)
    }

    // MARK: Username claims (§3.3)

    /// Checks a contact's username claim against the directory, once, when
    /// the conversation is opened (a contact cannot use up our quota).
    public func verifyUsernameIfNeeded(_ peer: AccountId) async {
        guard let contact = try? read({ try $0.contacts.get(peer) }), contact.requestState == .accepted,
            contact.profile.needsUsernameCheck, let username = contact.profile.username,
            let owner = try? await backend.usernameOwner(username)
        else { return }
        _ = try? write { try $0.contacts.recordUsernameCheck(username, owner: owner, for: peer) }
        emit(.conversations)
    }

    // MARK: User settings

    public func currentSettings() -> UserSettings { settings }

    public func updateUserSettings(_ change: @Sendable (inout UserSettings) -> Void) throws {
        let before = settings
        change(&runtime.settings)
        try stateFile.save(runtime)
        if before.presenceSharing != settings.presenceSharing {
            if !settings.presenceSharing { presence = PresenceTracker() }
            emit(.presence)
        }
        if !settings.typingIndicators && !typing.isEmpty {
            typing = [:]
            emit(.typing)
        }
        if before.autoDownloadMedia != settings.autoDownloadMedia { startDownloads() }
        if before.displayName != settings.displayName || before.presenceSharing != settings.presenceSharing {
            try announceProfileChange()
        }
    }

    /// The complete profile again to every contact that has one (§8.7).
    func announceProfileChange() throws {
        let now = clock.now()
        try write { tx in
            let profile = try ownProfile(tx, now: now)
            for contact in try tx.contacts.accepted() where contact.profileSentAt != nil {
                try tx.outbox.enqueue(profile, to: contact.accountId, now: now)
                try tx.contacts.setProfileSent(at: now, to: contact.accountId)
            }
        }
        outboxWake.signal()
    }

    /// Changes our username on the relay (§3.3) and tells the contacts (§8.7).
    public func changeUsername(to username: NormalizedUsername) async throws {
        try await backend.changeUsername(to: username)
        try announceProfileChange()
    }

    // MARK: Backup (§5.5)

    /// The `AccountBackup`: identity and accepted / blocked contacts, never messages.
    public func makeBackup() async throws -> BRPAccountBackup {
        let contacts = try read { try AccountBackupService.backupContacts($0) }
        return try await backend.makeBackup(displayName: settings.displayName, contacts: contacts)
    }

    /// When an exported backup was last saved; `nil` before the first one.
    public func lastBackupAt() -> Date? { runtime.lastBackupAt }

    // MARK: Sticker recents (§8.8)

    /// The most recents the runtime state keeps, and the longest id.
    public static let maxRecentStickers = 12
    public static let maxRecentStickerIdLength = 32

    /// The picker's recents, newest first, as the app stored them.
    public func recentStickers() -> [String] { runtime.recentStickers }

    /// Replaces the recents in the encrypted runtime state: empty and
    /// over-long ids are dropped, repeats kept once, at most
    /// ``maxRecentStickers``. When the state cannot be written nothing
    /// changes.
    public func setRecentStickers(_ ids: [String]) throws {
        var seen = Set<String>()
        let kept = ids.filter { id in
            !id.isEmpty && id.count <= Self.maxRecentStickerIdLength && seen.insert(id).inserted
        }
        let previous = runtime.recentStickers
        runtime.recentStickers = Array(kept.prefix(Self.maxRecentStickers))
        guard runtime.recentStickers != previous else { return }
        do {
            try stateFile.save(runtime)
        } catch {
            runtime.recentStickers = previous
            throw error
        }
    }

    /// Puts `id` at the front of the recents in one step: a repeat moves to
    /// the front, the list keeps at most ``maxRecentStickers``. Two sends in
    /// a row never lose an entry, since the actor runs this whole.
    public func noteRecentSticker(_ id: String) throws {
        try setRecentStickers([id] + runtime.recentStickers)
    }

    /// Records that an exported backup was saved, in the encrypted runtime
    /// state. When the state cannot be written (the vault is locking)
    /// nothing changes, neither the file nor what ``lastBackupAt()`` says.
    public func recordBackupSaved(at date: Date) throws {
        let previous = runtime.lastBackupAt
        runtime.lastBackupAt = date
        do {
            try stateFile.save(runtime)
        } catch {
            runtime.lastBackupAt = previous
            throw error
        }
    }
}
