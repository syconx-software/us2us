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

    /// 3.3 “Her şeyi sil” after the second confirmation: crypto-shreds all
    /// local data. The app returns to onboarding.
    func eraseEverything() async

    // MARK: Line & chats (04)

    var connection: ConnectionState { get }
    var chats: [ChatSummary] { get }
    var requests: [MessageRequest] { get }

    /// Exact username lookup (4.2) — no suggestions, no prefix search.
    func lookup(username: String) async -> UserLookup

    func accept(_ request: MessageRequest) async -> ChatID?
    func block(_ request: MessageRequest) async
    func delete(_ request: MessageRequest) async

    /// 4.2 “Mesaj yaz”: opens the chat with `username` (exact match),
    /// creating an empty, pending-request conversation when there is none
    /// yet. The first message becomes a request on the other side; neither
    /// side sees the other's presence until it is accepted.
    func startChat(username: String) async -> ChatID?

    /// The user's own contact code (`ContactCode`: username + identity-key
    /// fingerprint) — 4.1 / 4.2 / 6.2 “Kodumu göster”, the 7.1 profile QR.
    /// `nil` until the account exists.
    func ownContactCode() async -> String?

    // MARK: Conversation (05)

    func chat(_ id: ChatID) -> ChatSummary?
    func messages(in chat: ChatID) -> [Message]
    /// Sends text, optionally as a reply (the quote travels inside the
    /// encrypted message). Restarts the Ghost timer.
    func send(text: String, replyTo: QuotedMessage?, in chat: ChatID) async
    /// Sends an attachment chosen from “+” (photo, file, place, live location) or a sticker.
    func send(attachment: ConversationAttachment, in chat: ChatID) async
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
    /// The decrypted media of a view-once message, held in memory only while 5.6 is open.
    func viewOnceMedia(_ message: MessageID, in chat: ChatID) async -> MediaSource?
    /// 5.6 closed: deletes the media on both ends and sends the opened receipt.
    func markViewOnceOpened(_ message: MessageID, in chat: ChatID) async
    /// A screenshot was taken while 5.6 showed the media: the sender gets a system line.
    func reportScreenshot(of message: MessageID, in chat: ChatID) async

    // MARK: Verification (06)

    func safetyNumber(for chat: ChatID) async -> SafetyNumberInfo?
    /// 6.3: QR matched (`.faceToFace`) or “Doğrulandı olarak işaretle” (`.manual`).
    func markVerified(_ chat: ChatID, method: VerificationMethod) async
    /// 6.1 verified › “Doğrulamayı kaldır”: the seal leaves 6.1, 5.1 and 4.1.
    func clearVerification(_ chat: ChatID) async
    /// 6.2: compares a scanned QR text with the chat's safety number
    /// (libsignal `ScannableFingerprint.compare` in the live implementation,
    /// which also handles the two sides' ordering).
    func compareScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison
    /// What a contact code scanned from 4.1 / 4.2 “QR kodla ekle” stands for (nothing is added yet).
    func resolveContactCode(_ code: String) async -> ContactCodeResolution
    /// Adds the contact of a scanned contact code (or finds the existing
    /// chat) and marks it verified face to face — the code carries the
    /// identity key, checked against the one the relay serves. `nil` if the
    /// contact could not be added.
    func addContact(fromScannedCode code: String) async -> ChatID?

    // MARK: Settings & backup (07)

    var settings: AppSettings { get }
    func updateSettings(_ settings: AppSettings) async
    func backupEstimate() async -> BackupEstimate

    /// 7.1 “Face ID ile aç”: turning it on asks for Face ID once (so the
    /// biometric key can be created); turning it off deletes that key.
    /// Returns the state actually in effect.
    func setBiometricUnlock(_ enabled: Bool, reason: String) async -> Bool

    /// Checks the current code for a code change. It goes through the lock's
    /// throttle (PROTOCOL §5.2): a wrong code here counts as a wrong unlock
    /// attempt, so this screen is never a way around the delays.
    func verifySecretCode(_ code: String) async -> UnlockAttemptResult

    /// Re-keys the vault from `current` to `new` (both already validated).
    func changeSecretCode(current: String, new: String) async throws(ChangeSecretCodeError)

    /// Owner rule: the backup password must differ from the secret code.
    /// Only ever asked for six-digit passwords (which the strength rule
    /// already refuses), and never counted as an unlock attempt.
    func backupPasswordIsSecretCode(_ password: String) async -> Bool

    /// 7.2: encrypts a backup on the device with `password` (memory-hard KDF
    /// in the live implementation) into a single file named `fileName` inside
    /// the app's protected temporary directory. `progress` gets 0…1.
    func exportBackup(
        _ contents: BackupContents, password: String, fileName: String,
        progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws -> URL

    /// The share / export sheet closed. The temporary file is deleted either
    /// way; `saved` records the backup date (7.1 › Yedek · Son: …).
    func finishExport(at url: URL, saved: Bool) async

    /// 7.3: copies the picked file into the protected container and reads its
    /// unencrypted header (size, date). Security-scoped access is handled here.
    func inspectBackup(at url: URL) async throws(BackupFileError) -> BackupFileInfo

    /// Checks the backup password (decrypts the key block only).
    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult

    /// Restores an opened backup: keys → messages → media. Resumable — the
    /// live implementation persists the job and continues after a relaunch.
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

    /// The scene's phase (PROTOCOL §5.4: connect only while active and
    /// unlocked, the background grace period, lock on return).
    func scenePhaseChanged(_ phase: MessengerScenePhase)

    /// 5.1 is on screen: its messages load, receipts and disappearing timers start.
    func conversationAppeared(_ chat: ChatID) async
    func conversationDisappeared(_ chat: ChatID)

    /// “+” › Konum: the current place (asks for when-in-use location the first time).
    func sendCurrentLocation(in chat: ChatID) async
}

/// The scene phases the messenger cares about.
enum MessengerScenePhase: Sendable, Equatable {
    case active, inactive, background
}

extension MessengerService {
    var hasAccount: Bool { account != nil }
    var sendsDesignContent: Bool { true }
    func scenePhaseChanged(_ phase: MessengerScenePhase) {}
    func conversationAppeared(_ chat: ChatID) async {}
    func conversationDisappeared(_ chat: ChatID) {}
    func sendCurrentLocation(in chat: ChatID) async {}
}

extension MessengerService {
    /// The user's own username (“sen”); empty before onboarding finished.
    var ownUsername: String { account?.username ?? "" }

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
}
