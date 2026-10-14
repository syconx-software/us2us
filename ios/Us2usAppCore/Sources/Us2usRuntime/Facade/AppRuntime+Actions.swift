import Foundation
import Us2usKit
import Us2usStorage

/// The actions the UI calls. Each needs an unlocked vault
/// (``RuntimeError/locked`` otherwise) and returns once the change is stored;
/// sending happens through the durable outbox.
extension AppRuntime {
    // MARK: Sending

    @discardableResult
    public func sendText(_ text: String, to peer: AccountId, quote: QuoteInfo? = nil) async throws -> MessageId {
        try await engine().send(.text(text), to: peer, quote: quote)
    }

    /// Compresses and strips an image (≤ 2048 px, JPEG, no metadata) for sending.
    public func prepareImage(at url: URL, caption: String? = nil) async throws -> PreparedMedia {
        try requireUnlocked()
        let directories = environment.directories
        return try await Task.detached(priority: .userInitiated) {
            try MediaPreparation.prepareImage(at: url, directories: directories, caption: caption)
        }.value
    }

    /// Re-exports a video at ≤ 720p without location or other metadata.
    public func prepareVideo(at url: URL, caption: String? = nil) async throws -> PreparedMedia {
        try requireUnlocked()
        return try await MediaPreparation.prepareVideo(at: url, directories: environment.directories, caption: caption)
    }

    public func prepareVoiceNote(at url: URL, durationMs: UInt32, waveform: Data) throws -> PreparedMedia {
        try requireUnlocked()
        return try MediaPreparation.prepareVoice(
            at: url, directories: environment.directories, durationMs: durationMs, waveform: waveform)
    }

    /// A voice note recorded by the app and held in memory (AAC in M4A, up
    /// to 100 waveform samples): written to a staging file (complete
    /// protection) for ``sendMedia(_:to:viewOnce:quote:messageId:)``, which
    /// encrypts it into storage and deletes the staged copy. Like any
    /// attachment, each upload attempt then decrypts a staging copy of its
    /// own, deleted after the upload (and at the latest at the next lock or
    /// launch, which empty both staging folders).
    public func prepareVoiceNote(_ audio: Data, durationMs: UInt32, waveform: Data) async throws -> PreparedMedia {
        try requireUnlocked()
        let directories = environment.directories
        return try await Task.detached(priority: .userInitiated) {
            try MediaPreparation.prepareVoice(audio, directories: directories, durationMs: durationMs, waveform: waveform)
        }.value
    }

    /// A document as it is — except photos and videos, which lose their
    /// metadata (GPS, camera, capture time) like any other photo or video
    /// (§7): an image keeps its pixels and format, a video is re-muxed
    /// without its metadata.
    public func prepareFile(at url: URL, fileName: String, contentType: String) async throws -> PreparedMedia {
        try requireUnlocked()
        let directories = environment.directories
        if MediaPreparation.isMovie(contentType: contentType, fileName: fileName) {
            return try await MediaPreparation.prepareVideoFile(at: url, directories: directories, fileName: fileName)
        }
        return try await Task.detached(priority: .userInitiated) {
            try MediaPreparation.prepareFile(
                at: url, directories: directories, fileName: fileName, contentType: contentType)
        }.value
    }

    /// Sends prepared media; `viewOnce` for photos and videos that can be
    /// opened once. A view-once item is sent without its thumbnail and
    /// waveform, and a caption is refused (`EngineError.notAllowed`):
    /// nothing of it may be visible before it is opened. `messageId`: the id
    /// the app gave the bubble it showed while the media was being prepared
    /// (``RandomId/make()``), so the stored message takes that bubble's place.
    @discardableResult
    public func sendMedia(
        _ media: PreparedMedia, to peer: AccountId, viewOnce: Bool = false, quote: QuoteInfo? = nil,
        messageId: MessageId? = nil
    ) async throws -> MessageId {
        try await engine().send(.media(media), to: peer, quote: quote, viewOnce: viewOnce, messageId: messageId)
    }

    @discardableResult
    public func sendLocation(_ location: LocationInfo, to peer: AccountId) async throws -> MessageId {
        try await engine().send(.location(location), to: peer)
    }

    /// Sends a built-in sticker (PROTOCOL.md §8.8). `lang` is the language
    /// its words were shown in (“tr”, “en”); empty for a wordless sticker.
    /// `quote`: the message it answers, as for a text.
    @discardableResult
    public func sendSticker(
        packId: String, stickerId: UInt32, emoji: String, lang: String = "", quote: QuoteInfo? = nil,
        to peer: AccountId
    ) async throws -> MessageId {
        try await engine().send(
            .sticker(packId: packId, stickerId: stickerId, emoji: emoji, lang: lang), to: peer, quote: quote)
    }

    /// The sticker picker's recents (ids the app chose, newest first), from
    /// the vault's encrypted runtime state. ``RuntimeError/locked`` while locked.
    public func recentStickers() async throws -> [String] {
        try await engine().recentStickers()
    }

    /// Replaces the sticker picker's recents (at most 12 short ids) in the
    /// vault's encrypted runtime state — never in UserDefaults.
    public func setRecentStickers(_ ids: [String]) async throws {
        try await engine().setRecentStickers(ids)
    }

    /// Puts one sticker id at the front of the recents, in one step inside
    /// the engine, so sends close together never lose an entry.
    public func noteRecentSticker(_ id: String) async throws {
        try await engine().noteRecentSticker(id)
    }

    /// Starts live location (§8.3); returns the session id for updates.
    /// The app runs location updates (with the OS indicator) and calls
    /// ``updateLiveLocation(_:peer:latitude:longitude:accuracy:heading:speed:)``
    /// every 15–30 s, also in the background while sharing.
    public func startLiveLocation(
        to peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, duration: TimeInterval
    ) async throws -> Data {
        try await engine().startLiveLocation(
            peer: peer, latitude: latitude, longitude: longitude, accuracy: accuracy, duration: duration)
    }

    /// Works while locked too, as long as sharing is active (§8.3).
    public func updateLiveLocation(
        _ sessionId: Data, peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, heading: Float = -1,
        speed: Float = -1
    ) async throws {
        guard let engine = session?.engine else { throw RuntimeError.locked }
        try await engine.updateLiveLocation(
            sessionId, peer: peer, latitude: latitude, longitude: longitude, accuracy: accuracy, heading: heading,
            speed: speed)
    }

    /// Works while locked too. When the last sharing ends while the app is
    /// in the background, the store closes and the DEK is zeroed (§5.4).
    public func stopLiveLocation(_ sessionId: Data, peer: AccountId) async throws {
        guard let engine = session?.engine else { throw RuntimeError.locked }
        try await engine.stopLiveLocation(sessionId, peer: peer)
        await endLiveLocationOnlySessionIfIdle()
    }

    public func react(to message: MessageId, peer: AccountId, emoji: String?) async throws {
        try await engine().react(to: message, peer: peer, emoji: emoji)
    }

    public func deleteForEveryone(_ message: MessageId, peer: AccountId) async throws {
        try await engine().deleteForEveryone(message, peer: peer)
    }

    public func deleteForMe(_ message: MessageId, peer: AccountId) async throws {
        try await engine().deleteForMe(message, peer: peer)
    }

    /// "Tekrar gönder" for a failed or not delivered message.
    @discardableResult
    public func resend(_ message: MessageId, peer: AccountId) async throws -> MessageId {
        try await engine().resend(message, peer: peer)
    }

    public func setTyping(_ started: Bool, in peer: AccountId) async {
        guard let engine = try? engine() else { return }
        await engine.setTyping(started, peer: peer)
    }

    // MARK: Media

    /// A decrypted copy of an attachment for players and the share sheet
    /// (staging): deleted when its message disappears, is deleted or wiped,
    /// when the app locks and at the next launch. Not for view-once items.
    public func mediaFile(for message: MessageId, peer: AccountId) async throws -> URL {
        try await engine().exportMedia(message, peer: peer)
    }

    /// The decrypted bytes of an attachment, in memory only (prefer this for
    /// images: no plaintext file at all). Not for view-once items.
    public func mediaData(for message: MessageId, peer: AccountId) async throws -> Data {
        try await engine().mediaData(message, peer: peer)
    }

    public func download(_ message: MessageId, peer: AccountId) async throws {
        try await engine().download(message, peer: peer)
    }

    /// Opens a received view-once item exactly once: it is destroyed here
    /// and the VIEWED receipt queued as it opens, so it can never be opened
    /// again (also not after a crash or a force-quit while shown). For a
    /// player that needs a file; call ``closeViewOnce(_:peer:file:)`` when
    /// the viewer closes (the file also goes at the next lock or launch).
    public func openViewOnce(_ message: MessageId, peer: AccountId) async throws -> URL {
        try await engine().openViewOnce(message, peer: peer)
    }

    /// ``openViewOnce(_:peer:)`` into memory only (photos): no file exists.
    public func openViewOnceData(_ message: MessageId, peer: AccountId) async throws -> Data {
        try await engine().openViewOnceData(message, peer: peer)
    }

    public func closeViewOnce(_ message: MessageId, peer: AccountId, file: URL?) async throws {
        try await engine().closeViewOnce(message, peer: peer, displayedFile: file)
    }

    /// A received voice note was played for the first time: it is marked
    /// played here, and — like read receipts, only while they are on and
    /// never for a request — VIEWED goes to the sender (§8.4).
    public func markVoiceNotePlayed(_ message: MessageId, peer: AccountId) async throws {
        try await engine().markVoiceNotePlayed(message, peer: peer)
    }

    /// A photo, video or file was saved outside the app (to Photos or
    /// Files): recorded once here, and for a received one SAVED goes to the
    /// sender (§8.4) — always, whatever the read-receipt setting, so the UI
    /// tells the user before saving. View-once, disappearing and deleted
    /// items cannot be saved (`EngineError.invalidMessage`).
    public func markMediaSaved(_ message: MessageId, peer: AccountId) async throws {
        try await engine().markMediaSaved(message, peer: peer)
    }

    /// The conversation's photos and videos (`.visual`) or files
    /// (`.documents`), newest first, `limit` at a time: pass the last item's
    /// id as `before` for the next page. Never view-once, disappearing,
    /// deleted or expired items. An empty page when `before` is gone
    /// meanwhile; ``messageRevisions`` says when to read the list again.
    public func mediaMessages(with peer: AccountId, kind: MediaKind, before: MessageId?, limit: Int) async throws
        -> [MessageItem]
    {
        try await engine().mediaMessages(with: peer, kind: kind, before: before, limit: limit)
    }

    /// One message as it is now — for an item of ``mediaMessages(with:kind:before:limit:)``
    /// whose conversation is not open (its download state); `nil` once gone.
    public func messageItem(_ message: MessageId, peer: AccountId) async throws -> MessageItem? {
        try await engine().messageItem(message, peer: peer)
    }

    public func unblock(_ peer: AccountId) async throws {
        try await engine().unblock(peer)
    }

    // MARK: Requests and blocking (§6.4)

    public func acceptRequest(_ peer: AccountId) async throws {
        try await engine().acceptRequest(peer)
    }

    /// "Sil": deletes the request locally; the requester is not told.
    public func declineRequest(_ peer: AccountId) async throws {
        try await engine().declineRequest(peer)
    }

    public func block(_ peer: AccountId) async throws {
        try await engine().block(peer)
    }

    public func deleteConversation(_ peer: AccountId, forBoth: Bool) async throws {
        let engine = try engine()
        if forBoth {
            try await engine.deleteConversationForBoth(peer)
        } else {
            try await engine.deleteConversationLocally(peer)
        }
        if openPeer == peer { closeConversation() }
    }

    // MARK: Ghost mode and disappearing messages (§8.1, §8.2)

    public func setConversationSettings(_ peer: AccountId, ghost: GhostSettings?, defaultExpireTimerSeconds: UInt32)
        async throws
    {
        try await engine().updateConversationSettings(
            peer, ghost: ghost, defaultExpireTimerSeconds: defaultExpireTimerSeconds)
    }

    // MARK: Safety numbers (§5)

    public func safetyNumber(with peer: AccountId) async throws -> SafetyNumber? {
        try requireUnlocked()
        return try await session?.backend.safetyNumber(with: peer)
    }

    // Every verification is bound to the key it was made about: the
    // comparison and the stored decision happen in one step (Us2usKit
    // `Messenger`), so a key that changes meanwhile is never sealed.

    /// 6.2: compares a safety-number QR payload scanned from the contact's
    /// phone with the current number and, on a match, marks the contact
    /// verified (“yüz yüze”). Returns whether it matched and was verified.
    public func verifySafetyNumber(_ peer: AccountId, scanned: Data) async throws -> Bool {
        let verified = try await engine().verifySafetyNumber(peer, scanned: scanned)
        if verified { safetyNumberChanges.remove(peer) }
        return verified
    }

    /// 6.1 “Doğrulandı olarak işaretle” (“elle işaretlendi”): verifies only if
    /// the safety number is still `comparedNumber` — the
    /// `SafetyNumber.scannable` of the number the user compared. `false`
    /// when the key changed since; show the new number.
    public func markVerified(_ peer: AccountId, comparedNumber: Data) async throws -> Bool {
        let verified = try await engine().markVerified(peer, comparedNumber: comparedNumber)
        if verified { safetyNumberChanges.remove(peer) }
        return verified
    }

    /// “QR kodla ekle”: pins `identityKey` — the key the directory serves
    /// for `peer`, whose fingerprint the caller found equal to the one in
    /// the contact code scanned side by side — as verified (PROTOCOL.md §5).
    /// Call it once the conversation exists. `false` when this phone holds
    /// another key for the contact: nothing is verified.
    public func verifyContactCode(_ peer: AccountId, identityKey: Data) async throws -> Bool {
        let verified = try await engine().verifyContactCode(peer, identityKey: identityKey)
        if verified { safetyNumberChanges.remove(peer) }
        return verified
    }

    /// “Doğrulamayı kaldır”.
    public func clearVerification(_ peer: AccountId) async throws {
        try await engine().clearVerification(peer)
    }

    /// When and how the contact's current key was verified (`nil`: not
    /// verified, or verified without a record — restored from a backup or
    /// verified by an earlier version).
    public func identityVerification(for peer: AccountId) async throws -> IdentityVerification? {
        try await engine().identityVerification(for: peer)
    }

    public func identityTrust(for peer: AccountId) async throws -> IdentityTrust {
        try await engine().identityTrust(for: peer)
    }

    /// Accepts a changed safety number without verifying it, which unblocks
    /// sending — only for the key whose number the user was shown
    /// (`shownNumber`, the `SafetyNumber.scannable` on screen): refused —
    /// `false` — when the key changed since or no change is pending.
    @discardableResult
    public func acceptSafetyNumberChange(_ peer: AccountId, shownNumber: Data) async throws -> Bool {
        let accepted = try await engine().acceptSafetyNumberChange(peer, shownNumber: shownNumber)
        if accepted { safetyNumberChanges.remove(peer) }
        return accepted
    }

    // MARK: Settings

    public func setDisplayName(_ name: String) async throws {
        let trimmed = String(name.utf8Prefix(ContentLimits.maxDisplayNameBytes))
        try await updateSettings { $0.displayName = trimmed }
    }

    /// "Show when I'm active" (§8.6). Reciprocal: off also hides others.
    public func setPresenceSharing(_ enabled: Bool) async throws {
        try await updateSettings { $0.presenceSharing = enabled }
    }

    public func setReadReceipts(_ enabled: Bool) async throws {
        try await updateSettings { $0.readReceipts = enabled }
    }

    public func setTypingIndicators(_ enabled: Bool) async throws {
        try await updateSettings { $0.typingIndicators = enabled }
    }

    public func setAutoDownloadMedia(_ enabled: Bool) async throws {
        try await updateSettings { $0.autoDownloadMedia = enabled }
    }

    public func setDefaults(ghost: GhostSettings?, expireTimerSeconds: UInt32) async throws {
        try await updateSettings {
            $0.defaultGhost = ghost
            $0.defaultExpireTimerSeconds = expireTimerSeconds
        }
    }

    private func updateSettings(_ change: @escaping @Sendable (inout UserSettings) -> Void) async throws {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        let engine = session.engine
        try await engine.updateUserSettings(change)
        let current = await engine.currentSettings()
        let status = await engine.presenceStatus(at: environment.clock.now())
        guard isCurrent(session) else { return }
        settings = current
        presence = status
    }

    /// Changes the username (§3.3); contacts get a new profile.
    public func changeUsername(_ raw: String) async throws {
        let username: NormalizedUsername
        do {
            username = try NormalizedUsername(raw)
        } catch {
            throw RuntimeError.invalidUsername(error)
        }
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        try await session.engine.changeUsername(to: username)
        if isCurrent(session) { ownUsername = username.value }
    }

    // MARK: Backup (§5.5)

    /// Exports the account backup, encrypted with the separate backup
    /// password (≥ 10 characters, entered twice), to a staging file for the
    /// share sheet. It holds the identity and contacts, never messages; the
    /// staging copy is deleted when the app locks.
    public func exportBackup(password: String, confirmation: String) async throws -> URL {
        try BackupPassword.validate(password, confirmation: confirmation)
        guard let session, lockState == .unlocked else { throw RuntimeError.locked }
        let backup = try await session.engine.makeBackup()
        let data = try await Task.detached(priority: .userInitiated) {
            try AccountBackupFile.export(backup, password: password)
        }.value
        return try AccountBackupService.writeToStaging(data, storage: session.storage, now: environment.clock.now())
    }

    /// The exported file was saved (the share sheet completed): records
    /// `date` (default: now) in the vault's encrypted runtime state, so 7.1
    /// shows it after every relaunch. Call it only for a file that was
    /// really saved; a cancelled share records nothing.
    ///
    /// Also allowed while the locked app's store is still open (the grace
    /// period, §5.4): a share to another app often finishes after the app
    /// has left the foreground. Nothing observable changes then; the next
    /// unlock reads it. Once the store is closed: ``RuntimeError/locked``.
    public func recordBackupSaved(at date: Date? = nil) async throws {
        guard let session, lockState == .unlocked || session.inGrace || session.liveLocationOnly else {
            throw RuntimeError.locked
        }
        let saved = date ?? environment.clock.now()
        try await session.engine.recordBackupSaved(at: saved)
        if isCurrent(session) { lastBackupAt = saved }
    }

    // MARK: Pasteboard

    /// Copies text with the pasteboard rules (local only, expiring). Text of
    /// disappearing and view-once messages cannot be copied.
    @discardableResult
    public func copy(_ message: MessageItem) -> Bool {
        guard message.isCopyable, case .text(let text) = message.body else { return false }
        return pasteboard.copy(text, sensitivity: .ordinary)
    }

    /// Copies a safety number or similar value (expires after a minute).
    @discardableResult
    public func copySensitive(_ text: String) -> Bool {
        pasteboard.copy(text, sensitivity: .sensitive)
    }
}
