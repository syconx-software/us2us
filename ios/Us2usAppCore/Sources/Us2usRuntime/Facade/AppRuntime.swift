import Foundation
import Observation
import Us2usKit
import Us2usStorage

/// The one object the SwiftUI app binds to. Main-actor isolated and
/// `@Observable`: every published property is safe to read from views, and
/// every action is `async`, running crypto, storage and network work off the
/// main actor (vault unlock and backup export take about a second).
///
/// Lifecycle (PROTOCOL.md §5.4 with the owner's decisions):
///
/// 1. Launch → ``LockState/locked`` (or ``LockState/needsSetup``). Overdue
///    ghost wipes run without the DEK.
/// 2. Unlock (code or Face ID) → the DEK lives in memory only now; the store
///    opens, overdue wipes run, the WebSocket connects (only while the app is
///    active), backlog until `queue_empty`, then presence and receipts.
/// 3. Background → the UI locks **immediately** (nothing stays in observable
///    state) and the privacy cover stays up; the connection lives on for a
///    30-second grace period to receive what is in flight; then presence
///    OFFLINE, the socket closes, the store closes and the DEK is zeroed.
///    (Exception: while the user shares live location, the store stays open
///    for the location ticks until sharing ends; the socket is closed.)
/// 4. Return → the lock screen again; unlocking within the grace period
///    resumes the same session without reconnecting.
///
/// The vault is never left unlocked in the background: an unlock,
/// registration or restore that finishes after the user left the app closes
/// its session at once and stays locked, and every closing session is
/// covered by a background task whose expiration zeroes the key and closes
/// the store synchronously, so iOS never suspends the app with the DEK in
/// memory. When the system grants no background time at all, there is no
/// grace period: the key and the store go at once.
@MainActor
@Observable
public final class AppRuntime {
    // MARK: Observable state

    public internal(set) var lockState: LockState
    /// Wrong codes so far, the current wait and what further wrong codes cost.
    public internal(set) var unlockStatus: UnlockStatus?
    public internal(set) var quickUnlock: QuickUnlockStatus = .notEnabled
    public internal(set) var biometryKind: BiometryKind = .none
    /// The "hat" line (``ConnectionStatus/line(strings:)``).
    public internal(set) var connection: ConnectionStatus = .offline
    /// Conversations (accepted, outgoing requests, blocked), most recent first.
    public internal(set) var chats: [ChatSummary] = []
    /// Incoming message requests ("Mesaj istekleri").
    public internal(set) var requests: [ChatSummary] = []
    /// The conversation on screen and its messages (oldest first).
    public internal(set) var openPeer: AccountId?
    public internal(set) var openMessages: [MessageItem] = []
    /// "Active" / "Active N min ago" of eligible contacts (§8.6).
    public internal(set) var presence: [AccountId: PresenceStatus] = [:]
    public internal(set) var typing: Set<AccountId> = []
    public internal(set) var settings = UserSettings()
    public internal(set) var ownUsername: String?
    /// Contacts whose safety number changed and was not accepted yet.
    public internal(set) var safetyNumberChanges: Set<AccountId> = []
    public internal(set) var wipeAfterTenFailures = false
    /// Cover the UI (app switcher snapshot, inactive states).
    public internal(set) var isPrivacyCoverVisible = false
    /// The screen is recorded or mirrored: hide conversation content.
    public internal(set) var isScreenCaptured = false
    public internal(set) var banner: MessageBanner?

    // MARK: Dependencies

    @ObservationIgnored public let environment: RuntimeEnvironment
    @ObservationIgnored let vault: VaultController
    @ObservationIgnored private let backgroundTasks: any BackgroundTaskRunning
    @ObservationIgnored private let captureEvents: any CaptureEventSource
    @ObservationIgnored public let pasteboard: SecurePasteboard
    @ObservationIgnored private let strings: RuntimeStrings
    @ObservationIgnored private let networkWatcher: NetworkPathWatcher?

    @ObservationIgnored var session: Session?
    @ObservationIgnored private var phase: AppPhase = .active
    @ObservationIgnored private var graceTask: Task<Void, Never>?
    @ObservationIgnored private var graceToken: Int?
    /// A teardown still closing the previous session (system-expired grace).
    @ObservationIgnored private var teardownTask: Task<Void, Never>?
    @ObservationIgnored private var refreshTask: Task<Void, Never>?
    @ObservationIgnored private var pendingRefresh = PendingRefresh()
    @ObservationIgnored private var tickTask: Task<Void, Never>?
    /// Conversations already told about the current screen recording.
    @ObservationIgnored private var recordingNotified: Set<AccountId> = []
    /// The app went to the background while an unlock, registration or
    /// restore was running: it must not end unlocked.
    @ObservationIgnored private var leftDuringWork = false
    /// Sessions whose teardown has not finished (network steps can take
    /// long): the system's expiration handler zeroes their keys at once.
    @ObservationIgnored private var closingSessions: [Session] = []
    /// Ends a live-location-only session when its sharing runs out, even
    /// though the UI tick is cancelled while locked.
    @ObservationIgnored private var liveLocationWatch: Task<Void, Never>?

    public init(
        environment: RuntimeEnvironment, backgroundTasks: any BackgroundTaskRunning,
        captureEvents: any CaptureEventSource, pasteboard: SecurePasteboard, strings: RuntimeStrings = .current
    ) {
        self.environment = environment
        self.backgroundTasks = backgroundTasks
        self.captureEvents = captureEvents
        self.pasteboard = pasteboard
        self.strings = strings
        self.vault = VaultController(
            directories: environment.directories, secureItems: environment.secureItems,
            biometrics: environment.biometrics, clock: environment.clock, configuration: environment.vault)
        self.networkWatcher = environment.watchesNetworkPath ? NetworkPathWatcher() : nil
        let directories = environment.directories
        try? directories.prepare()
        // A new process: plaintext that a crash, a jetsam kill or a swipe from
        // the app switcher left in either staging directory (prepared media,
        // decrypted view-once and displayed media, downloads) goes now, with
        // no key and before anything else — not at the next unlock.
        directories.purgeStaging()
        LocalStorage.purgeStaging(layout: directories.storage)
        // Overdue ghost wipes need no key (§5.3): run them before anything else.
        _ = Self.runBackgroundWipes(environment: environment)
        self.lockState = FileManager.default.fileExists(atPath: directories.vault.appendingPathComponent("vault.brpv").path)
            ? .locked : .needsSetup
        captureEvents.start(
            screenshot: { [weak self] in self?.screenshotTaken() },
            captureChanged: { [weak self] captured in self?.captureChanged(captured) })
        isScreenCaptured = captureEvents.isCaptured
        networkWatcher?.start { [weak self] available in
            Task { @MainActor in await self?.session?.supervisor.networkChanged(available: available) }
        }
        Task { await refreshLockScreen() }
    }

    #if canImport(UIKit)
        /// The app's runtime with the system hooks (background tasks, screenshot
        /// and capture detection, the general pasteboard).
        public convenience init(environment: RuntimeEnvironment) {
            self.init(
                environment: environment, backgroundTasks: SystemBackgroundTasks(),
                captureEvents: SystemCaptureEvents(), pasteboard: .shared)
        }
    #endif

    /// Overdue ghost wipes from a `BGTaskScheduler` task: no DEK, no
    /// network, only key slots are deleted (§5.3).
    @discardableResult
    public nonisolated static func runBackgroundWipes(environment: RuntimeEnvironment) -> Int {
        let directories = environment.directories
        let now = environment.clock.now()
        let wiped = LocalStorage.runBackgroundWipes(layout: directories.storage, now: now)
        NoticeStore.runBackgroundWipes(
            directory: directories.runtime.appendingPathComponent("notices", isDirectory: true), now: now)
        return wiped
    }

    /// When the next background wipe is due (`BGAppRefreshTaskRequest.earliestBeginDate`).
    public nonisolated static func nextBackgroundWipeDate(environment: RuntimeEnvironment) -> Date? {
        LocalStorage.nextBackgroundWipeDate(layout: environment.directories.storage)
    }

    // MARK: Scene phase

    /// Map SwiftUI's `scenePhase` here.
    public func scenePhaseChanged(_ newPhase: AppPhase) {
        let previous = phase
        phase = newPhase
        switch newPhase {
        case .active:
            isPrivacyCoverVisible = false
            if lockState == .unlocked, let session {
                Task { await session.supervisor.start() }
            }
            if previous == .background { Task { await refreshLockScreen() } }
        case .inactive:
            isPrivacyCoverVisible = true
        case .background:
            isPrivacyCoverVisible = true
            pasteboard.clearIfOwned()
            switch lockState {
            case .unlocked: lockForBackground()
            case .working: leftDuringWork = true
            case .locked, .needsSetup: break
            }
        }
    }

    /// The owner's rule: lock on every return from the background. The UI
    /// state is cleared now; the session receives for the grace period.
    private func lockForBackground() {
        clearUnlockedState()
        lockState = .locked
        guard let session else { return }
        session.inGrace = true
        graceToken = backgroundTasks.begin(name: "us2us.grace") { [weak self] in
            self?.graceExpired()
        }
        guard graceToken != nil else {
            // The system grants no background time: the app can be suspended
            // right after this returns, so there is no grace period — the
            // key and the store go now, never into a suspension (§5.4 step 4).
            self.session = nil
            Self.wipeNow(session)
            _ = close(session, announceOffline: false)
            RuntimeLog.lifecycle.info("no background time, locked at once")
            Task { await refreshLockScreen() }
            return
        }
        let clock = environment.clock
        let grace = environment.gracePeriod
        graceTask?.cancel()
        graceTask = Task { [weak self] in
            do {
                try await clock.sleep(seconds: grace)
            } catch {
                return
            }
            await self?.endGrace()
        }
        Task { await refreshLockScreen() }
    }

    /// The system wants the background time back: zero the key and close the
    /// store at once (the app is suspended right after this returns), then
    /// let the teardown finish what can still be finished.
    private func graceExpired() {
        graceTask?.cancel()
        graceTask = nil
        if let token = graceToken {
            graceToken = nil
            backgroundTasks.end(token)
        }
        if let session, session.inGrace {
            self.session = nil
            Self.wipeNow(session)
            _ = close(session, announceOffline: true)
        }
        // Also sessions whose teardown is still waiting for the network
        // (presence OFFLINE, an outbox send in flight).
        wipeClosingSessions()
    }

    /// Zeroes the DEK and every key derived from it, and closes the store
    /// (SQLCipher key, cached conversation keys), synchronously.
    private static func wipeNow(_ session: Session) {
        session.key.wipe()
        session.engine.wipeDerivedKeys()
        session.storage.close()
    }

    private func wipeClosingSessions() {
        for session in closingSessions { Self.wipeNow(session) }
    }

    /// Tears a session down in a tracked task: opening the storage again (an
    /// unlock right after locking) waits for it, so the one-instance rule of
    /// Us2usStorage can never be hit by a race. Outside the grace period's
    /// own background task, a background task covers the teardown, and its
    /// expiration zeroes the key at once.
    @discardableResult
    private func close(_ session: Session, announceOffline: Bool) -> Task<Void, Never> {
        if self.session === session { self.session = nil }
        liveLocationWatch?.cancel()
        liveLocationWatch = nil
        if !closingSessions.contains(where: { $0 === session }) { closingSessions.append(session) }
        let needsBackgroundTime = graceToken == nil
        let token =
            needsBackgroundTime
            ? backgroundTasks.begin(name: "us2us.teardown") { [weak self] in self?.wipeClosingSessions() } : nil
        if needsBackgroundTime, token == nil, phase != .active {
            // In the background without background time the teardown's
            // network steps may never finish before the suspension.
            Self.wipeNow(session)
        }
        let previous = teardownTask
        let task = Task {
            await previous?.value
            await self.teardown(session, announceOffline: announceOffline)
            self.closingSessions.removeAll { $0 === session }
            if let token { self.backgroundTasks.end(token) }
        }
        teardownTask = task
        return task
    }

    private func awaitPendingTeardown() async {
        while let pending = teardownTask {
            await pending.value
            if teardownTask == pending { teardownTask = nil }
        }
    }

    private func endGrace() async {
        graceTask = nil
        defer {
            if let token = graceToken {
                graceToken = nil
                backgroundTasks.end(token)
            }
        }
        guard let session, session.inGrace, lockState != .unlocked else { return }
        if phase == .background, await !session.engine.activeLiveLocationSessions().isEmpty {
            // Live location keeps sending ticks in the background (§8.3); the
            // socket closes, receiving waits for the next open. The store and
            // the DEK stay only while sharing runs (§5.4 step 4).
            session.liveLocationOnly = true
            await session.engine.goingOffline()
            await session.supervisor.stop()
            await session.engine.connectionClosed()
            RuntimeLog.lifecycle.info("grace over, live location continues")
            startLiveLocationWatch(session)
            // Sharing may have been stopped while the socket closed.
            await endLiveLocationOnlySessionIfIdle()
            return
        }
        await close(session, announceOffline: true).value
        RuntimeLog.lifecycle.info("grace over, locked")
    }

    /// Ends a live-location-only session when sharing is over: called when
    /// sharing is stopped, on every engine change (a session expired) and
    /// by the watch at the sessions' end. Returns only once the key is
    /// zeroed, also when another of these callers started the close first
    /// (stopping sharing emits an engine change, whose handler can get there
    /// before the caller that stopped it).
    func endLiveLocationOnlySessionIfIdle() async {
        if let session, session.liveLocationOnly, lockState != .unlocked,
            await session.engine.activeLiveLocationSessions().isEmpty,
            self.session === session, session.liveLocationOnly, lockState != .unlocked
        {
            _ = close(session, announceOffline: false)
            RuntimeLog.lifecycle.info("live location over, locked")
        }
        if session == nil { await awaitPendingTeardown() }
    }

    /// Sleeps until the last running live-location session ends by itself
    /// and closes the session then (independent of the UI tick, which is
    /// cancelled while locked).
    private func startLiveLocationWatch(_ session: Session) {
        liveLocationWatch?.cancel()
        let clock = environment.clock
        liveLocationWatch = Task { [weak self] in
            while !Task.isCancelled {
                guard let end = await session.engine.liveLocationEnd() else { break }
                do {
                    try await clock.sleep(seconds: max(1, end.timeIntervalSince(clock.now())))
                } catch {
                    return
                }
            }
            guard !Task.isCancelled else { return }
            await self?.endLiveLocationOnlySessionIfIdle()
        }
    }

    /// Closes everything of a session and zeroes its key.
    private func teardown(_ session: Session, announceOffline: Bool) async {
        session.stopListening()
        if announceOffline && !session.liveLocationOnly { await session.engine.goingOffline() }
        await session.supervisor.stop()
        await session.engine.stop()
        session.storage.close()
        session.key.wipe()
        environment.directories.purgeStaging()
        if self.session === session { self.session = nil }
        if lockState != .unlocked { connection = .offline }
    }

    // MARK: Lock screen

    public func refreshLockScreen() async {
        let status = await vault.unlockStatus()
        let quick = await vault.quickUnlockStatus()
        let wipe = await vault.wipeAfterTenFailures
        unlockStatus = status
        quickUnlock = quick
        wipeAfterTenFailures = wipe
        biometryKind = environment.biometrics.availability().kind
    }

    /// Unlocks with the six-digit code. Wrong codes are counted before they
    /// are checked; after two, increasing delays apply (`UnlockStatus`).
    /// If the optional "wipe after 10" fires, everything is erased and the
    /// error is `UnlockError.vault(.wiped)`.
    public func unlock(code: String) async throws {
        guard lockState == .locked else { return }
        lockState = .working
        leftDuringWork = false
        do {
            let key = try await vault.unlock(code: code)
            try await openSession(with: key)
        } catch {
            if case UnlockError.vault(.wiped) = error { await eraseLocalData() }
            await settleAfterFailedUnlock()
            throw error
        }
    }

    /// Face ID / Touch ID. `UnlockError.codeRequired` and `.biometric(.failed)`
    /// mean: show the code keypad.
    public func unlockWithBiometrics() async throws {
        guard lockState == .locked else { return }
        lockState = .working
        leftDuringWork = false
        do {
            let key = try await vault.unlockWithBiometrics(
                reason: strings.string(.biometricReason), cancelTitle: strings.string(.biometricUseCode))
            try await openSession(with: key)
        } catch {
            await settleAfterFailedUnlock()
            throw error
        }
    }

    private func settleAfterFailedUnlock() async {
        if lockState == .working {
            lockState = await vault.hasVault ? .locked : .needsSetup
        }
        await refreshLockScreen()
    }

    /// Opens the store with a fresh key, or resumes the session that is still
    /// receiving in the grace period (same key, compared in constant time).
    private func openSession(with key: SecretKeyMaterial) async throws {
        if let session, session.inGrace || session.liveLocationOnly, !session.key.isWiped {
            if session.key.matches(key) {
                key.wipe()
                liveLocationWatch?.cancel()
                liveLocationWatch = nil
                graceTask?.cancel()
                graceTask = nil
                if let token = graceToken {
                    graceToken = nil
                    backgroundTasks.end(token)
                }
                session.inGrace = false
                session.liveLocationOnly = false
                await becomeUnlocked(session)
                return
            }
            _ = close(session, announceOffline: false)
        }
        await awaitPendingTeardown()
        let opened = try await Self.openStorage(key: key, environment: environment)
        let storage = opened.storage
        guard let account = try storage.read({ try $0.account.load() }), storage.protocolStore != nil else {
            // A vault without an account: registration never finished. Start over.
            storage.close()
            key.wipe()
            await eraseLocalData()
            throw RuntimeError.locked
        }
        let backend = try environment.backends.open(account: account, storage: storage)
        let session = try makeSession(key: key, storage: storage, backend: backend)
        await session.engine.start(openReport: opened.wipeReport)
        await becomeUnlocked(session)
    }

    /// Opens (or creates) the encrypted storage off the main actor.
    nonisolated static func openStorage(key: SecretKeyMaterial, environment: RuntimeEnvironment) async throws
        -> LocalStorage.Opened
    {
        let layout = environment.directories.storage
        let now = environment.clock.now()
        return try await Task.detached(priority: .userInitiated) {
            try key.withData { dek in try LocalStorage.open(layout: layout, dek: dek, now: now) }
        }.value
    }

    private func makeSession(key: SecretKeyMaterial, storage: LocalStorage, backend: any MessagingBackend) throws
        -> Session
    {
        let engine = try MessagingEngine(
            storage: storage, backend: backend, stateDirectory: environment.directories.runtime, key: key,
            clock: environment.clock, configuration: environment.engine)
        let supervisor = ConnectionSupervisor(
            backend: backend, clock: environment.clock, backoff: environment.reconnectBackoff,
            events: ConnectionEvents(
                deliver: { message in try await engine.receive(message) },
                queueEmpty: { await engine.connectionReachedQueueEmpty() }))
        let session = Session(key: key, storage: storage, backend: backend, engine: engine, supervisor: supervisor)
        session.listen(
            engineChanges: { [weak self] change in self?.engineChanged(change) },
            statusChanges: { [weak self] status in self?.connectionChanged(status) })
        return session
    }

    private func becomeUnlocked(_ session: Session) async {
        if leftDuringWork || phase == .background {
            // The user left while the code was derived, the proof-of-work
            // solved or the backup restored: never unlock in the background
            // (no grace period runs, the DEK would stay in memory) and never
            // show the app again without the code (lock at every return).
            leftDuringWork = false
            clearUnlockedState()
            lockState = .locked
            if self.session === session { self.session = nil }
            _ = close(session, announceOffline: true)
            RuntimeLog.lifecycle.info("unlocked after leaving the app, locked again")
            await refreshLockScreen()
            return
        }
        self.session = session
        lockState = .unlocked
        recordingNotified = []
        ownUsername = await session.backend.username().value
        pendingRefresh = PendingRefresh(all: true)
        await performRefresh()
        startTicking()
        if phase == .active { await session.supervisor.start() }
        connection = await session.supervisor.status
        await refreshLockScreen()
    }

    /// Locks now (a "lock" button): same as going to the background, without the grace period.
    public func lockNow() async {
        guard lockState == .unlocked else { return }
        clearUnlockedState()
        lockState = .locked
        if let session { await close(session, announceOffline: true).value }
        await refreshLockScreen()
    }

    private func clearUnlockedState() {
        refreshTask?.cancel()
        refreshTask = nil
        tickTask?.cancel()
        tickTask = nil
        chats = []
        requests = []
        openPeer = nil
        openMessages = []
        presence = [:]
        typing = []
        banner = nil
        safetyNumberChanges = []
        settings = UserSettings()
        ownUsername = nil
        pasteboard.clearIfOwned()
    }

    // MARK: Onboarding

    /// Creates the vault (the code entered twice), the encrypted store and
    /// the relay account (proof-of-work: a few seconds). A failure before the
    /// account exists on the relay leaves nothing behind, so the UI can simply
    /// retry, e.g. with another username after `RelayError.server(409, …)`.
    /// Once the account is registered and saved it is never erased again:
    /// a later failure leaves the vault locked, and the code opens it.
    public func createAccount(username rawUsername: String, displayName: String, code: String, confirmation: String)
        async throws
    {
        guard lockState == .needsSetup else { throw RuntimeError.alreadySetUp }
        let username: NormalizedUsername
        do {
            username = try NormalizedUsername(rawUsername)
        } catch {
            throw RuntimeError.invalidUsername(error)
        }
        lockState = .working
        leftDuringWork = false
        var key: SecretKeyMaterial?
        var storage: LocalStorage?
        do {
            await eraseLocalData()  // leftovers of an earlier installation
            lockState = .working
            let created = try await vault.create(code: code, confirmation: confirmation)
            key = created
            let opened = try await Self.openStorage(key: created, environment: environment).storage
            storage = opened
            _ = try opened.createIdentity(
                LocalIdentity.generateKeyPair(), registrationId: LocalIdentity.generateRegistrationId())
            try saveDisplayName(String(displayName.utf8Prefix(ContentLimits.maxDisplayNameBytes)), key: created)
            let backend = try await environment.backends.register(username: username, storage: opened)
            let session = try makeSession(key: created, storage: opened, backend: backend)
            await session.engine.start()
            await becomeUnlocked(session)
        } catch {
            try await settleAfterFailedSetup(error, key: key, storage: storage)
        }
    }

    /// Restores an account from a backup file on a new phone (§5.5): decrypts
    /// and fully verifies it first, then creates a new vault with the new
    /// code, rotates the token (the old phone loses access) and uploads new
    /// keys. Contacts see no safety-number change.
    ///
    /// Once the rotated token is saved it is the account's only credential
    /// (the backup's token is revoked): whatever fails after that, nothing is
    /// erased. The vault stays, locked under the new code; the next unlock
    /// finishes what is missing (pre-key upload, profile announcement).
    public func restore(backupFile: URL, password: String, code: String, confirmation: String) async throws {
        guard lockState == .needsSetup else { throw RuntimeError.alreadySetUp }
        lockState = .working
        leftDuringWork = false
        var key: SecretKeyMaterial?
        var storage: LocalStorage?
        do {
            let backup = try await Task.detached(priority: .userInitiated) {
                guard let data = try? Data(contentsOf: backupFile) else { throw RuntimeError.unreadableBackup }
                return try AccountBackupFile.import(data, password: password)
            }.value
            await eraseLocalData()
            lockState = .working
            let created = try await vault.create(code: code, confirmation: confirmation)
            key = created
            let opened = try await Self.openStorage(key: created, environment: environment).storage
            storage = opened
            // Kept before the irreversible token rotation.
            try saveDisplayName(backup.displayName, key: created)
            let backend = try await environment.backends.restore(backup, storage: opened)
            let session = try makeSession(key: created, storage: opened, backend: backend)
            await session.engine.start()
            await becomeUnlocked(session)
        } catch {
            try await settleAfterFailedSetup(error, key: key, storage: storage)
        }
    }

    /// The display name of a new or restored account, into the encrypted
    /// runtime state the engine loads.
    private func saveDisplayName(_ name: String, key: SecretKeyMaterial) throws {
        let file = try RuntimeStateFile(directory: environment.directories.runtime, dek: key)
        defer { file.close() }
        var state = file.load()
        state.settings.displayName = name
        try file.save(state)
    }

    /// After a failed registration or restore: a saved account (its token
    /// is the only credential now) is kept, locked; anything earlier is
    /// erased so onboarding can start over. Rethrows `error`.
    private func settleAfterFailedSetup(_ error: Error, key: SecretKeyMaterial?, storage: LocalStorage?) async throws
        -> Never
    {
        if let storage, Self.holdsAccount(storage) {
            if session?.storage === storage { session = nil }
            storage.close()
            key?.wipe()
            clearUnlockedState()
            lockState = await vault.hasVault ? .locked : .needsSetup
            RuntimeLog.lifecycle.notice("setup interrupted after the account was saved; kept, locked")
            await refreshLockScreen()
            throw error
        }
        key?.wipe()
        storage?.close()
        await eraseLocalData()
        lockState = .needsSetup
        throw error
    }

    nonisolated private static func holdsAccount(_ storage: LocalStorage) -> Bool {
        guard storage.isOpen else { return false }
        return (try? storage.read { try $0.account.load() }) != nil
    }

    // MARK: Erase ("forgot code", "erase everything")

    /// Crypto-shreds everything on this phone: keys first (device secret,
    /// vault, Secure Enclave key), then every file. The relay account is
    /// left alone (see ``deleteAccountAndEraseEverything()``). The UI asks
    /// for a second confirmation before calling this.
    public func eraseEverything() async {
        clearUnlockedState()
        await eraseLocalData()
        lockState = .needsSetup
        await refreshLockScreen()
    }

    /// "Delete my account": `DELETE /v1/accounts/me` first, then
    /// ``eraseEverything()``. Only when the relay confirms (or answers that
    /// the account is gone already, 401/404) is anything erased; otherwise
    /// the error is thrown and everything stays, so the user can retry or
    /// choose to erase this phone only — erasing first would destroy the only
    /// credentials that can delete the account, which would then stay
    /// registered for 180 days (§4.10) with its username taken.
    public func deleteAccountAndEraseEverything() async throws {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        do {
            try await session.backend.deleteAccount()
        } catch let error as RelayError where Self.meansAccountGone(error) {
            RuntimeLog.lifecycle.info("account already gone on the relay")
        }
        await eraseEverything()
    }

    /// ``deleteAccountAndEraseEverything()`` or ``eraseEverything()``.
    /// Returns `false` when the relay did not confirm the deletion: then
    /// nothing was erased.
    @discardableResult
    public func eraseEverything(deleteAccountOnRelay: Bool) async -> Bool {
        guard deleteAccountOnRelay else {
            await eraseEverything()
            return true
        }
        do {
            try await deleteAccountAndEraseEverything()
            return true
        } catch {
            RuntimeLog.lifecycle.notice("account deletion failed: \(error.logLabel, privacy: .public)")
            return false
        }
    }

    nonisolated static func meansAccountGone(_ error: RelayError) -> Bool {
        switch error {
        case .server(status: 401, _, _), .server(status: 404, _, _), .notAuthenticated: return true
        default: return false
        }
    }

    private func eraseLocalData() async {
        graceTask?.cancel()
        graceTask = nil
        if let token = graceToken {
            graceToken = nil
            backgroundTasks.end(token)
        }
        if let session { _ = close(session, announceOffline: false) }
        // Keys out of memory and the store closed now; the teardown's
        // network steps may still take a while.
        wipeClosingSessions()
        await vault.destroyKeys()
        try? LocalStorage.destroyAll(layout: environment.directories.storage)
        await awaitPendingTeardown()
        try? environment.directories.eraseAll()
        try? environment.directories.prepare()
        pasteboard.clearIfOwned()
        connection = .offline
        RuntimeLog.lifecycle.info("local data erased")
    }

    // MARK: Security settings

    public func enableQuickUnlock(code: String) async throws {
        try requireUnlocked()
        do {
            try await vault.enableQuickUnlock(code: code)
        } catch UnlockError.vault(.wiped) {
            await erasedByWrongCodes()
            throw UnlockError.vault(.wiped)
        }
        await refreshLockScreen()
    }

    public func disableQuickUnlock() async {
        await vault.disableQuickUnlock()
        await refreshLockScreen()
    }

    /// The current code is a counted attempt: if the optional "wipe after
    /// 10" fires, everything is erased as on the lock screen and the error
    /// is `VaultError.wiped`.
    public func changeCode(current: String, new: String, confirmation: String) async throws {
        try requireUnlocked()
        do {
            try await vault.changeCode(current: current, new: new, confirmation: confirmation)
        } catch VaultError.wiped {
            await erasedByWrongCodes()
            throw VaultError.wiped
        }
        await refreshLockScreen()
    }

    /// The vault crypto-shredded itself (10th wrong code, §5.2): the rest
    /// goes too, and onboarding starts over.
    private func erasedByWrongCodes() async {
        clearUnlockedState()
        await eraseLocalData()
        lockState = .needsSetup
        await refreshLockScreen()
    }

    public func setWipeAfterTenFailures(_ enabled: Bool) async throws {
        try requireUnlocked()
        await vault.setWipeAfterTenFailures(enabled)
        wipeAfterTenFailures = enabled
    }

    func requireUnlocked() throws {
        guard lockState == .unlocked, session != nil else { throw RuntimeError.locked }
    }

    func engine() throws -> MessagingEngine {
        guard lockState == .unlocked, let session else { throw RuntimeError.locked }
        return session.engine
    }

    // MARK: Refresh

    private func engineChanged(_ change: EngineChange) {
        if let session, session.liveLocationOnly, lockState != .unlocked {
            // Sharing may just have ended (stopped or expired).
            Task { await self.endLiveLocationOnlySessionIfIdle() }
        }
        switch change {
        case .conversations: pendingRefresh.chats = true
        case .messages(let peer): if peer == openPeer { pendingRefresh.messages = true }
        case .presence: pendingRefresh.presence = true
        case .typing: pendingRefresh.typing = true
        case .newMessage(let peer):
            pendingRefresh.chats = true
            if peer != openPeer { pendingRefresh.bannerPeer = peer }
        case .wiped(let peer):
            pendingRefresh.chats = true
            if peer == openPeer { pendingRefresh.messages = true }
        case .safetyNumberChanged(let peer):
            safetyNumberChanges.insert(peer)
        }
        scheduleRefresh()
    }

    private func connectionChanged(_ status: ConnectionStatus) {
        guard lockState == .unlocked || session?.inGrace == true else { return }
        connection = status
    }

    /// Coalesces bursts (a backlog of hundreds of messages) into one refresh.
    private func scheduleRefresh() {
        guard refreshTask == nil else { return }
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 50_000_000)
            await self?.performRefresh()
        }
    }

    /// Every `await` can let the app lock; decrypted state is assigned and
    /// messages marked displayed only while the same session is still unlocked.
    private func performRefresh() async {
        refreshTask = nil
        guard lockState == .unlocked, let session else { return }
        let engine = session.engine
        let work = pendingRefresh
        pendingRefresh = PendingRefresh()
        let now = environment.clock.now()
        if work.chats || work.all {
            let summaries = try? await engine.chatSummaries()
            let current = await engine.currentSettings()
            guard isCurrent(session) else { return }
            if let summaries {
                chats = summaries.filter { !$0.isRequest }
                requests = summaries.filter(\.isRequest)
            }
            settings = current
        }
        if work.messages || work.all, let peer = openPeer {
            let messages = (try? await engine.messages(of: peer)) ?? []
            guard isCurrent(session), openPeer == peer else { return }
            openMessages = messages
            try? await engine.markDisplayed(peer)
        }
        if work.presence || work.all {
            let status = await engine.presenceStatus(at: now)
            guard isCurrent(session) else { return }
            presence = status
        }
        if work.typing || work.all {
            let contacts = await engine.typingContacts(at: now)
            guard isCurrent(session) else { return }
            typing = contacts
        }
        if let peer = work.bannerPeer, peer != openPeer, isCurrent(session) {
            let title = chats.first { $0.id == peer }?.contact.displayName ?? requests.first { $0.id == peer }?.contact.displayName
            banner = MessageBanner(
                id: UUID(), peer: peer, title: title ?? "", text: Localizer.current.string(.newMessage))
        }
    }

    /// Presence labels ("N min ago") and typing indicators age; recompute them.
    private func startTicking() {
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard let self, !Task.isCancelled else { return }
                self.pendingRefresh.presence = true
                self.pendingRefresh.typing = true
                await self.performRefresh()
                await self.endLiveLocationOnlySessionIfIdle()
            }
        }
    }

    public func dismissBanner() {
        banner = nil
    }

    /// Still unlocked, with the same session as before an `await`.
    func isCurrent(_ session: Session) -> Bool {
        lockState == .unlocked && self.session === session
    }

    // MARK: Screenshots and screen recording

    private func screenshotTaken() {
        guard lockState == .unlocked, let peer = openPeer, let engine = session?.engine else { return }
        Task { try? await engine.screenCaptured(in: peer, recording: false) }
    }

    private func captureChanged(_ captured: Bool) {
        isScreenCaptured = captured
        if !captured {
            recordingNotified = []
            return
        }
        notifyRecordingIfNeeded()
    }

    private func notifyRecordingIfNeeded() {
        guard isScreenCaptured, lockState == .unlocked, let peer = openPeer, let engine = session?.engine,
            !recordingNotified.contains(peer)
        else { return }
        recordingNotified.insert(peer)
        Task { try? await engine.screenCaptured(in: peer, recording: true) }
    }

    // MARK: Conversations

    /// The conversation is on screen: loads its messages, starts
    /// disappearing timers of what is now displayed, sends read receipts (if
    /// enabled), and checks the contact's username claim once (§3.3).
    /// If the app locks meanwhile, nothing is shown and nothing is marked
    /// displayed (no disappearing timer starts, no read receipt is queued).
    public func openConversation(_ peer: AccountId) async {
        guard lockState == .unlocked, let session else { return }
        let engine = session.engine
        openPeer = peer
        if banner?.peer == peer { banner = nil }
        let messages = (try? await engine.messages(of: peer)) ?? []
        guard isCurrent(session), openPeer == peer else { return }
        openMessages = messages
        try? await engine.markDisplayed(peer)
        notifyRecordingIfNeeded()
        Task { await engine.verifyUsernameIfNeeded(peer) }
        let trust = try? await engine.identityTrust(for: peer)
        guard isCurrent(session) else { return }
        if case .changed? = trust { safetyNumberChanges.insert(peer) } else { safetyNumberChanges.remove(peer) }
    }

    public func closeConversation() {
        openPeer = nil
        openMessages = []
    }

    /// Opens a conversation with `username` (exact match only, §4.4).
    public func startConversation(username rawUsername: String) async throws -> AccountId {
        let username: NormalizedUsername
        do {
            username = try NormalizedUsername(rawUsername)
        } catch {
            throw RuntimeError.invalidUsername(error)
        }
        return try await engine().startConversation(username: username)
    }

    public func contact(_ peer: AccountId) async -> ContactInfo? {
        try? await session?.engine.contactInfo(peer)
    }

    public func conversation(_ peer: AccountId) async -> ConversationInfo? {
        try? await session?.engine.conversationInfo(peer)
    }
}

/// What the next refresh must reload.
struct PendingRefresh {
    var all = false
    var chats = false
    var messages = false
    var presence = false
    var typing = false
    var bannerPeer: AccountId?
}

/// An open vault: its key, store, backend, engine and connection.
@MainActor
final class Session {
    let key: SecretKeyMaterial
    let storage: LocalStorage
    let backend: any MessagingBackend
    let engine: MessagingEngine
    let supervisor: ConnectionSupervisor
    /// In the background grace period (UI locked, still receiving).
    var inGrace = false
    /// Grace over, but live location is being shared: store open, socket closed.
    var liveLocationOnly = false
    private var listeners: [Task<Void, Never>] = []

    init(
        key: SecretKeyMaterial, storage: LocalStorage, backend: any MessagingBackend, engine: MessagingEngine,
        supervisor: ConnectionSupervisor
    ) {
        self.key = key
        self.storage = storage
        self.backend = backend
        self.engine = engine
        self.supervisor = supervisor
    }

    func listen(
        engineChanges: @escaping @MainActor (EngineChange) -> Void,
        statusChanges: @escaping @MainActor (ConnectionStatus) -> Void
    ) {
        let changes = engine.changes
        let statuses = supervisor.statusUpdates
        listeners.append(
            Task { @MainActor in
                for await change in changes { engineChanges(change) }
            })
        listeners.append(
            Task { @MainActor [engine] in
                for await status in statuses {
                    statusChanges(status)
                    if !status.isConnected { await engine.connectionClosed() }
                }
            })
    }

    func stopListening() {
        for listener in listeners { listener.cancel() }
        listeners = []
    }
}
