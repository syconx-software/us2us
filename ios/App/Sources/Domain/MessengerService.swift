import Foundation
import Us2usKit

/// Everything the screens need from the messenger, and nothing else.
///
/// Screens and feature view models talk only to this protocol. Two
/// implementations:
/// * `DesignFixturesService` (Services/) reproduces exactly the data drawn in
///   the design — names, messages, times, statuses — so every screen can be
///   built and compared with `ios/design/reference/*.png` before the real
///   wiring exists;
/// * `RealMessengerService` (Services/Live/) maps Us2usAppCore's
///   `AppRuntime` (vault, storage, relay) onto the same surface; it is the
///   default, the fixtures run with `-U2Fixtures YES` (DEBUG).
///
/// Implementations are `@Observable` classes on the main actor: views read
/// the state properties directly and SwiftUI re-renders on change (Observation
/// tracks reads through the existential).
@MainActor
protocol MessengerService: AnyObject, Observable {
    // MARK: Account & onboarding (01–02)

    /// `nil` before onboarding finished on this device.
    var account: OwnAccount? { get }

    /// Live availability of a username while typing (2.1). Local rules are
    /// checked first; the relay lookup is exact-match only.
    func usernameAvailability(_ username: String) async -> UsernameAvailability

    /// Why a newly chosen secret code is refused, or `nil` (2.2; PROTOCOL §5.2
    /// rules from Us2usKit: six digits, no repeats, no runs, not common).
    func secretCodeProblem(_ code: String) -> SecretCodeError?

    /// Creates identity keys and the vault bound to the device secret + code,
    /// registers the username with the relay (2.3 “Kurulumu tamamla”).
    func createAccount(username: String, secretCode: String, faceIDEnabled: Bool) async throws

    /// After a restore (7.3, from 1.1 or 3.3): binds the restored vault to
    /// this device with the new secret code chosen in 2.2 (“2 / 2” → 4.1).
    func completeRestore(secretCode: String, faceIDEnabled: Bool) async throws

    // MARK: Lock (03)

    /// Failures so far, the next allowed attempt and what further wrong codes
    /// cost (PROTOCOL §5.2 delays — Us2usKit `UnlockThrottle.standard`).
    var unlockStatus: UnlockStatus { get }

    /// Checks the code (6 digits, auto-submitted) and unlocks the vault.
    func unlock(code: String) async -> UnlockAttemptResult

    /// Face ID (3.1 and the keypad's Face ID key): evaluates biometrics with
    /// `reason` and opens the vault with its biometric-protected key. `false`
    /// on failure or cancel — the code keypad stays. `cancelTitle` labels the
    /// system prompt's cancel button (“Kodla aç”); there is no passcode fallback.
    func unlockWithBiometrics(reason: String, cancelTitle: String) async -> Bool

    /// 3.3 / 7.1 “Her şeyi sil” after the second confirmation: crypto-shreds
    /// all local data. The app returns to onboarding.
    func eraseEverything() async

    /// 7.1 “Her şeyi sil”, first: deletes the account on the relay,
    /// authenticated — its keys, mailbox, uploaded media and username
    /// (PROTOCOL §4.3). Nothing on this phone is erased here; the app calls
    /// `eraseEverything()` once the confirmation has gone. Throws when the
    /// relay did not confirm (offline, a timeout, a server error); an
    /// account the relay no longer knows counts as deleted.
    func deleteAccountOnRelay() async throws

    // MARK: Line & chats (04)

    var connection: ConnectionState { get }
    var chats: [ChatSummary] { get }
    var requests: [MessageRequest] { get }

    /// Exact username lookup (4.2) — no suggestions, no prefix search.
    func lookup(username: String) async -> UserLookup

    func accept(_ request: MessageRequest) async -> ChatID?
    /// 4.3 / 4.4 “Engelle”: the request leaves 4.3. It stays as a blocked
    /// chat (read only) in 4.1, where “Engeli kaldır” makes it a request again.
    func block(_ request: MessageRequest) async
    func delete(_ request: MessageRequest) async

    // MARK: Blocking and reports (App Review 1.2)

    /// The contact screen › Engelle, after its confirmation (PROTOCOL §6.4):
    /// what the person sends is dropped on this phone from now on (they are
    /// not told), nothing more is sent to them, messages still waiting to
    /// go to them are cancelled (they fail; “Tekrar gönder” after an
    /// unblock), a live location shared with them ends, and the access key
    /// they had is replaced.
    /// The chat stays, read only (`Peer.isBlocked`). `false` when the block
    /// could not be stored.
    @discardableResult
    func block(chat: ChatID) async -> Bool

    /// “Engeli kaldır”: the person is back in the state before the block —
    /// a contact gets the current access key and the profile again, a
    /// blocked request is a request again. What they sent meanwhile was
    /// never kept.
    @discardableResult
    func unblock(chat: ChatID) async -> Bool

    /// 7.1 › Engellenenler: everyone blocked, by name — with a chat (read
    /// only in 4.1) or without one (Ghost erased it, or it was deleted;
    /// the block stays). Observable, like `chats`.
    var blockedPeople: [BlockedPerson] { get }

    /// “Engeli kaldır” on 7.1 › Engellenenler: exactly `unblock(chat:)`,
    /// also for someone without a chat — back in the state before the
    /// block (a contact gets the current access key and the profile again;
    /// a blocked request is a request again — back in 4.3 while its chat is
    /// still there). Someone with nothing to go back to (no chat, and no
    /// contact before the block — a Ghost wipe with `wipe_contact` kept only
    /// the block) is forgotten: a stranger again, whom 4.2 › “Mesaj yaz”
    /// starts a new chat with. The person leaves `blockedPeople`.
    @discardableResult
    func unblock(_ person: BlockedPerson) async -> Bool

    /// What a report about the chat's person names (“Bildir”): their
    /// username, nothing else. `nil` when there is no such chat.
    func reportSubject(for chat: ChatID) -> ReportSubject?

    /// The same for the sender of a message request (4.3, 4.4).
    func reportSubject(for request: MessageRequest) -> ReportSubject?

    /// 4.2 “Mesaj yaz”: opens the chat with `username` (exact match),
    /// creating an empty, pending-request conversation when there is none
    /// yet. The first message becomes a request on the other side; neither
    /// side sees the other's presence until it is accepted. Someone blocked
    /// gets their read-only chat (with “Engeli kaldır”), also after Ghost
    /// erased it.
    func startChat(username: String) async -> ChatID?

    /// The user's own contact code (`ContactCode`: username + identity-key
    /// fingerprint) — 4.1 / 4.2 / 6.2 “Kodumu göster”, the 7.1 profile QR.
    /// `nil` until the account exists.
    func ownContactCode() async -> String?

    // MARK: Profile pictures (PROTOCOL §8.9, §8.10)

    /// The user's own picture, “Aramada göster” and where the copy for
    /// search stands (7.1). Observable; the initial while locked.
    var ownAvatar: OwnAvatarState { get }

    /// 7.1 › Profil görseli: the initial, a preset or a photo. A photo is
    /// cropped to the square the user framed, scaled to at most 256 × 256,
    /// stripped of all metadata and re-encoded on the phone first. Accepted
    /// contacts get the new picture end to end; while “Aramada göster” is on
    /// it is also published, encrypted under the username, for search.
    /// Throws without changing anything when the photo cannot be used.
    func setAvatar(_ edit: AvatarEdit) async throws(AvatarEditError)

    /// 7.1 › “Aramada göster”: off removes the search copy from the relay
    /// (contacts keep the picture), on publishes it again. Returns the
    /// setting in effect.
    @discardableResult
    func setAvatarShownInSearch(_ shown: Bool) async -> Bool

    /// The picture of a 4.2 result: call it only after `lookup(username:)`
    /// found exactly `username` (never while the user is typing). An
    /// accepted contact's comes from their last profile update with no
    /// request; anyone else's search copy is fetched and decrypted. Every
    /// failure (none published, offline, locked) is the initial.
    func searchAvatar(username: String) async -> AvatarPicture

    /// 4.4 › “Profil resmini göster”, after an explicit tap on the opened request
    /// (never for a list row or anything shown on its own): the sender's
    /// search copy, fetched once under their verified username. The initial
    /// when there is none.
    func revealAvatar(of request: MessageRequest) async -> AvatarPicture

    /// 7.1 appeared: the own picture's state is read again from the vault
    /// (the copy for search may have been written since).
    func refreshOwnAvatar() async

    // MARK: Conversation (05)

    func chat(_ id: ChatID) -> ChatSummary?
    func messages(in chat: ChatID) -> [Message]
    /// Sends text, optionally as a reply (the quote travels inside the
    /// encrypted message). Restarts the Ghost timer.
    func send(text: String, replyTo: QuotedMessage?, in chat: ChatID) async
    /// Sends an attachment chosen from “+” (photo, file, place, live location).
    func send(attachment: ConversationAttachment, in chat: ChatID) async
    /// Sends a built-in sticker (PROTOCOL §8.8): its ids, its emoji and —
    /// for a sticker with words — the language they were shown in, so the
    /// other side draws exactly that; optionally as a reply, like a voice
    /// message. It goes first in the recents.
    func send(sticker: BrandStickerSelection, replyTo: QuotedMessage?, in chat: ChatID) async
    /// The sticker picker's “Son kullanılanlar”: ids only
    /// (`BrandStickerRecents`), newest first. The live service keeps them in
    /// the vault's encrypted runtime state, the fixtures in memory; never in
    /// UserDefaults. Empty while locked.
    func recentStickers() async -> [String]
    /// Sends a voice message recorded with the mic (5.1), optionally as a
    /// reply. Its bubble shows at once (“Gönderiliyor”) with the usual
    /// states; the recording is in memory only — the live service hands it
    /// to the runtime, which stores it encrypted and uploads it. `false`
    /// when it could not even be stored (no bubble; the recording is gone).
    @discardableResult
    func send(voice: VoiceRecording, replyTo: QuotedMessage?, in chat: ChatID) async -> Bool
    /// A received voice message was played for the first time: it shows as
    /// played, and — only while read receipts are on, like “Okundu” — the
    /// sender learns it (VIEWED receipt, PROTOCOL §8.4).
    func markVoicePlayed(_ message: MessageID, in chat: ChatID) async
    /// “Tekrar gönder” on a message that was not delivered within 14 days:
    /// queues the same content as a new message (5.4 note).
    func resend(_ message: MessageID, in chat: ChatID) async
    func updateGhost(_ ghost: GhostState, disappearing: DisappearingTimer, in chat: ChatID) async
    func deleteForEveryone(_ message: MessageID, in chat: ChatID) async
    func deleteForMe(_ message: MessageID, in chat: ChatID) async
    /// Sets (or with `nil` removes) the user's reaction.
    func setReaction(_ emoji: String?, on message: MessageID, in chat: ChatID) async
    /// “Durdur” in the live-location bubble: ends sharing at once.
    func stopLiveLocation(_ message: MessageID, in chat: ChatID) async
    /// 5.2 › harita: the open chat's live shares (the contact's and the
    /// user's own) with their latest positions. Observable — it changes with
    /// every tick that arrives — and memory only: empty while the chat is
    /// not open and while the app is locked. Never stored or logged here.
    func liveLocations(in chat: ChatID) -> LiveLocationSnapshot
    /// This device's position for the live-location map, while the stream
    /// is consumed (foreground only). Shown on this phone, never sent or
    /// stored; never asks for permission by itself.
    func ownPositionUpdates() -> AsyncStream<OwnPositionState>
    /// The map's “İzin ver”: asks for when-in-use location once (only while
    /// it has not been decided). `true` when it is allowed.
    func requestOwnPositionAccess() async -> Bool
    /// The decrypted media of a view-once message, held in memory only while 5.6 is open.
    func viewOnceMedia(_ message: MessageID, in chat: ChatID) async -> MediaSource?
    /// 5.6 closed: deletes the media on both ends and sends the opened receipt.
    func markViewOnceOpened(_ message: MessageID, in chat: ChatID) async
    /// A screenshot was taken while 5.6 showed the media: the sender gets a system line.
    func reportScreenshot(of message: MessageID, in chat: ChatID) async

    /// The decrypted bytes of a photo, video, voice or file message, in
    /// memory only (the viewer, the players and file previews). A file still on the relay
    /// is downloaded first and this waits until it is here; cancelling the
    /// calling task stops the wait (not the download). `nil`: gone, held
    /// until a request is accepted, failed, or view-once (5.6 has its own path).
    func mediaPayload(_ message: MessageID, in chat: ChatID) async -> MediaPayload?
    /// Starts (or retries) the download of an attachment still on the relay.
    func downloadMedia(_ message: MessageID, in chat: ChatID) async
    /// A decrypted copy of a file message, named as sent, for Quick Look and
    /// “Dosyalara kaydet” — in the protected preview folder
    /// (`ProtectedPreviewFiles`). `nil` when the file is not available.
    func previewFile(_ message: MessageID, in chat: ChatID) async -> URL?
    /// The preview or the export ended: the copy is deleted now.
    func discardPreviewFile(_ url: URL)
    /// A photo, video or file was saved or handed on from one of our
    /// viewers (“Kaydet”, “Dosyalara kaydet”, the share sheet): the sender is
    /// told — a SAVED receipt (PROTOCOL §8.4), always, independent of read
    /// receipts; the user was told before the first save — and the message
    /// shows as saved here. Never for view-once or disappearing media,
    /// which cannot be exported.
    func markSaved(_ id: MessageID, in chat: ChatID) async
    /// The contact screen's media tabs: this chat's photos and videos
    /// (`.visual`) or files (`.documents`), newest first, at most `limit`,
    /// older than `before` when set (paging). Never view-once, deleted or
    /// expired messages.
    func media(in chat: ChatID, kind: ChatMediaKind, before: MessageID?, limit: Int) async -> [Message]
    /// Grows whenever the chat's stored messages change, also while the
    /// chat is not open (a download finished, an item was saved, deleted
    /// or wiped): the media tabs read their pages again. Observable.
    func mediaRevision(in chat: ChatID) -> Int

    // MARK: Verification (06)

    func safetyNumber(for chat: ChatID) async -> SafetyNumberInfo?
    // Every verification is bound to the key it is about (PROTOCOL.md §5):
    // the live implementation compares and stores in one step, so a key that
    // changes while 6.1 is open is never sealed by a decision about another.
    /// 6.1 “Doğrulandı olarak işaretle” (`.manual`): only if the chat's
    /// safety number is still `number` — the `scannablePayload` the user
    /// compared. `false` when it changed since (6.1 then shows the new one).
    func markVerified(_ chat: ChatID, comparing number: Data) async -> Bool
    /// 6.1 verified › “Doğrulamayı kaldır”: the seal leaves 6.1, 5.1 and 4.1.
    func clearVerification(_ chat: ChatID) async
    /// 6.2: compares a scanned QR text with the chat's safety number
    /// (libsignal `ScannableFingerprint.compare` in the live implementation,
    /// which also handles the two sides' ordering) and, on a match, marks
    /// the chat verified face to face — in the same step.
    func verifyScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison
    /// 6.1 after a change › “Doğrulamadan devam et”: accepts the changed
    /// number without verifying it (sending resumes, no seal) — only if it is
    /// still `number`, the `scannablePayload` on screen. `false` otherwise.
    func acceptSafetyNumberChange(_ chat: ChatID, showing number: Data) async -> Bool
    /// What a contact code scanned from 4.1 / 4.2 “QR kodla ekle” stands for (nothing is added yet).
    func resolveContactCode(_ code: String) async -> ContactCodeResolution
    /// Adds the contact of a scanned contact code (or finds the existing
    /// chat). It is verified face to face only when the code carries the
    /// identity key's fingerprint, the relay serves that key and this phone
    /// holds no other one for the contact; the key is then pinned, so a
    /// different key later is a safety-number change.
    func addContact(fromScannedCode code: String) async -> ContactCodeAddition

    // MARK: Settings & backup (07)

    var settings: AppSettings { get }
    func updateSettings(_ settings: AppSettings) async
    func backupEstimate() async -> BackupEstimate

    /// 7.1 “Face ID ile aç”: turning it on asks for Face ID once (so the
    /// biometric key can be created); turning it off deletes that key.
    /// Returns the state actually in effect.
    func setBiometricUnlock(_ enabled: Bool, reason: String) async -> Bool

    /// 7.1 “Face ID ile aç” turned on while quick unlock is off and this
    /// phone can use biometrics: the secret code is needed once (the
    /// biometric key wraps the vault key, PROTOCOL §5.2), so 7.1 asks for it
    /// on a page of its own (`enableBiometricUnlock(code:)`).
    var biometricUnlockNeedsCode: Bool { get }

    /// Turns quick unlock on with the code — a counted attempt, like the
    /// code change's current code. `.unlocked`: on now; `.vaultUnavailable`
    /// also when biometrics cannot be used on this phone right now.
    func enableBiometricUnlock(code: String) async -> UnlockAttemptResult

    /// Checks the current code for a code change. It goes through the lock's
    /// throttle (PROTOCOL §5.2): a wrong code here counts as a wrong unlock
    /// attempt, so this screen is never a way around the delays.
    func verifySecretCode(_ code: String) async -> UnlockAttemptResult

    /// Re-keys the vault from `current` to `new` (both already validated).
    func changeSecretCode(current: String, new: String) async throws(ChangeSecretCodeError)

    /// Owner rule: the backup password must differ from the secret code.
    /// Never counted as an unlock attempt. 7.2 no longer asks: the §5.5
    /// minimum of 10 characters already keeps a six-digit code out.
    func backupPasswordIsSecretCode(_ password: String) async -> Bool

    /// 7.2: encrypts a backup on the device with `password` (PBKDF2-HMAC-SHA256,
    /// 1,000,000 iterations, then AES-256-GCM in the live implementation;
    /// PROTOCOL.md §5.5) into a single file named `fileName` inside the app's
    /// protected temporary directory. `progress` gets 0…1. The live account
    /// backup holds the account, identity key and contacts, never messages or
    /// media, whatever `contents` says. A password that breaks the §5.5 rules
    /// throws `BackupPasswordError`.
    func exportBackup(
        _ contents: BackupContents, password: String, fileName: String,
        progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws -> URL

    /// The share / export sheet closed. The temporary file is deleted either
    /// way; `saved` records the backup date (7.1 › Yedek · Son: …), which the
    /// live service keeps in the vault, so it survives relaunches.
    func finishExport(at url: URL, saved: Bool) async

    /// 7.3: copies the picked file into the protected container and reads its
    /// unencrypted header (size, date). Security-scoped access is handled here.
    func inspectBackup(at url: URL) async throws(BackupFileError) -> BackupFileInfo

    /// Checks the backup password. The live implementation also verifies
    /// every field and asks the server, read-only, whether the backup's
    /// token still works, so `.opened` means a restore will accept it;
    /// nothing changes on the phone (the relay only notes the account's
    /// last-seen day, as for any authenticated request).
    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult

    /// Restores an opened backup: keys → messages → media
    /// (``backupsIncludeHistory``), or only the verified keys of a live
    /// account backup, whose account is restored with the new code at 2.3.
    func restoreBackup(_ file: BackupFileInfo) -> AsyncThrowingStream<RestoreProgress, any Error>

    /// Stops a restore and removes what was written so far.
    func cancelRestore() async

    // MARK: Live wiring (defaults below: the fixtures need none of it)

    /// An account exists on this device (also while locked, when `account`
    /// is `nil` in the live service: nothing decrypted stays in memory).
    var hasAccount: Bool { get }

    /// “+” sends the design's sample content (fixtures) instead of opening
    /// the system pickers and the location.
    var sendsDesignContent: Bool { get }

    /// A restored backup brings message history and media back (the
    /// design's planned backup, fixtures). The live account backup never
    /// does (PROTOCOL §5.5): 7.3 then lists only the keys step and says so.
    var backupsIncludeHistory: Bool { get }

    /// The scene's phase (PROTOCOL §5.4: connect only while active and
    /// unlocked, the background grace period, lock on return).
    func scenePhaseChanged(_ phase: MessengerScenePhase)

    /// 5.1 is on screen: its messages load, receipts and disappearing timers start.
    func conversationAppeared(_ chat: ChatID) async
    func conversationDisappeared(_ chat: ChatID)

    /// “+” › Konum: the current place (asks for when-in-use location the first time).
    func sendCurrentLocation(in chat: ChatID) async

    /// A restore's token rotation got no answer yet (PROTOCOL §5.5 step
    /// 3.4): until the server confirms it, the old phone may still have
    /// access to the account, and this phone does not connect. 4.1 and 7.1
    /// say so; the live service asks again with every connection attempt.
    var restoreAccessPending: Bool { get }

    /// “Şimdi kontrol et”: asks the server now (§5.5 step 3.4).
    func checkRestoreAccess() async -> RestoreAccessCheck

    /// “+” › Fotoğraf ve video: the photos and videos of one pick (at most
    /// `PickedMediaPlan.selectionLimit`), each sent as a message of its own
    /// in the order it was selected, the composer's text as the first
    /// photo's caption (`PickedMediaPlan`). Every bubble shows at once
    /// (“Hazırlanıyor”) while the picker still hands the files over; they
    /// are prepared one at a time (memory stays bounded however many were
    /// picked) and one that fails (“Tekrar gönder”) never holds up the rest.
    func send(pickedMedia messages: [PickedMediaMessage], in chat: ChatID) async
}

/// The scene phases the messenger cares about.
enum MessengerScenePhase: Sendable, Equatable {
    case active, inactive, background
}

extension MessengerService {
    var hasAccount: Bool { account != nil }
    var sendsDesignContent: Bool { true }
    var backupsIncludeHistory: Bool { true }
    func scenePhaseChanged(_ phase: MessengerScenePhase) {}
    var restoreAccessPending: Bool { false }
    /// Never a false all-clear: a service that cannot ask says it got no answer.
    func checkRestoreAccess() async -> RestoreAccessCheck { .unanswered }
    var biometricUnlockNeedsCode: Bool { false }
    func enableBiometricUnlock(code: String) async -> UnlockAttemptResult { .vaultUnavailable }
    func conversationAppeared(_ chat: ChatID) async {}
    func conversationDisappeared(_ chat: ChatID) {}
    func sendCurrentLocation(in chat: ChatID) async {}
}

extension MessengerService {
    func liveLocations(in chat: ChatID) -> LiveLocationSnapshot { .empty }

    func ownPositionUpdates() -> AsyncStream<OwnPositionState> {
        AsyncStream { continuation in
            continuation.yield(.unavailable)
            continuation.finish()
        }
    }

    func requestOwnPositionAccess() async -> Bool { false }
}

extension MessengerService {
    // Profile pictures: a service without them shows the initial everywhere.
    var ownAvatar: OwnAvatarState { OwnAvatarState() }
    func setAvatar(_ edit: AvatarEdit) async throws(AvatarEditError) { throw .failed }
    @discardableResult
    func setAvatarShownInSearch(_ shown: Bool) async -> Bool { ownAvatar.showInSearch }
    func searchAvatar(username: String) async -> AvatarPicture { .initials }
    func revealAvatar(of request: MessageRequest) async -> AvatarPicture { .initials }
    func refreshOwnAvatar() async {}
}

extension MessengerService {
    /// A service whose open chats hold every item (the design's data): the
    /// media tabs follow `messages(in:)` instead.
    func mediaRevision(in chat: ChatID) -> Int { 0 }
}

extension MessengerService {
    /// The user's own username (“sen”); empty before onboarding finished.
    var ownUsername: String { account?.username ?? "" }

    /// `mediaPayload` written to a named copy in the protected preview folder.
    func previewFile(_ message: MessageID, in chat: ChatID) async -> URL? {
        guard let payload = await mediaPayload(message, in: chat) else { return nil }
        return await ProtectedPreviewFiles.write(payload)
    }

    func discardPreviewFile(_ url: URL) {
        ProtectedPreviewFiles.discard(url)
    }

    /// Read receipts are mutual: off on this device → no “Okundu” anywhere (5.4).
    var readReceiptsEnabled: Bool { settings.readReceipts }

    /// The peer of a chat (username, avatar palette, verified flag).
    func peer(for chat: ChatID) -> Peer? { self.chat(chat)?.peer }
}

/// Outcome of a secret-code attempt on the lock screen.
enum UnlockAttemptResult: Equatable, Sendable {
    case unlocked
    /// Wrong code; the status says what the next wrong codes cost.
    case wrongCode(UnlockStatus)
    /// Too many wrong codes: wait until the date.
    case throttled(until: Date)
    /// Not six digits — refused before counting (PROTOCOL §5.2).
    case malformed
    /// The vault could not be read right now (the Secure Enclave or the
    /// Keychain refused, the store did not open, another unlock was
    /// running). Not a wrong code and not counted: the code may be right,
    /// and trying again a little later can work (PROTOCOL §5.2 › Errors).
    case vaultUnavailable
    /// The vault on this phone can never be opened here: its device secret
    /// or hardware key is gone, or the file is damaged. Not a wrong code and
    /// not counted; only a backup brings the account back.
    case vaultUnusable
    /// The app left the foreground while the code was checked: it stays
    /// locked, nothing is said, and the code is entered again.
    case interrupted
    /// The optional “wipe after 10 wrong codes” fired (PROTOCOL §5.2): this
    /// was the 10th wrong code in a row, and everything on this phone has
    /// been erased. Not “Kod yanlış”: the lock says what happened and offers
    /// to restore a backup or start over.
    case wiped
}

/// What “Şimdi kontrol et” found out about a restore's unanswered token
/// rotation (PROTOCOL §5.5 step 3.4).
enum RestoreAccessCheck: Equatable, Sendable {
    /// Confirmed: the old phone has no access any more.
    case closed
    /// Still no answer from the server; tried again with every connection.
    case unanswered
    /// The server refused both tokens: the same backup was restored on
    /// another phone first, or the account was deleted. This phone is
    /// signed out.
    case refused
}
