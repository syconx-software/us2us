// Adversarial security / stability review (2026-10-08).
//
// Every test in this file reproduces a concrete defect and FAILS against the
// current production code. Nothing here changes production behaviour; the
// doubles below only freeze or fail calls at the points the review names.

import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

// MARK: - Doubles

/// A Keychain stand-in whose next read can be held: it freezes a code unlock
/// inside `LocalVault.unlock` (the device-secret read sits next to the
/// 600 000-iteration PBKDF2 of §5.2, about a second on a phone).
final class HoldableSecureItemStore: SecureItemStore, @unchecked Sendable {
    private let inner = InMemorySecureItemStore()
    private let state = LockedState<(armed: Bool, entered: Bool)>((false, false))
    private let gate = DispatchSemaphore(value: 0)

    func holdNextRead() { state.withLock { $0 = (true, false) } }
    var isHolding: Bool { state.withLock { $0.entered } }
    func release() { gate.signal() }

    func read(_ name: String) throws -> Data? {
        let hold = state.withLock { state -> Bool in
            guard state.armed else { return false }
            state = (false, true)
            return true
        }
        if hold { gate.wait() }
        return try inner.read(name)
    }

    func write(_ data: Data, for name: String) throws { try inner.write(data, for: name) }
    func delete(_ name: String) throws { try inner.delete(name) }
    func deleteAll() throws { try inner.deleteAll() }
    var count: Int { inner.count }
}

/// Suspends a call until the test opens it.
final class AsyncGate: @unchecked Sendable {
    private let state = LockedState<(entered: Bool, opened: Bool, waiter: CheckedContinuation<Void, Never>?)>(
        (false, false, nil))

    var hasEntered: Bool { state.withLock { $0.entered } }

    func pass() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow = state.withLock { state -> Bool in
                state.entered = true
                if state.opened { return true }
                state.waiter = continuation
                return false
            }
            if resumeNow { continuation.resume() }
        }
    }

    func open() {
        let waiter = state.withLock { state -> CheckedContinuation<Void, Never>? in
            state.opened = true
            defer { state.waiter = nil }
            return state.waiter
        }
        waiter?.resume()
    }
}

/// ``FakeBackend`` behind switches of ``ReviewBackendFactory``: a relay
/// `DELETE /v1/accounts/me` that fails (no network, 5xx, 429 …) and a
/// presence send that hangs (bad network: URLSession waits up to 60 s).
struct ReviewBackend: MessagingBackend {
    let inner: FakeBackend
    let failDeleteAccount: LockedState<Bool>
    let presenceGate: LockedState<AsyncGate?>
    struct RelayUnreachable: Error {}

    var accountId: AccountId { inner.accountId }
    func username() async -> NormalizedUsername { await inner.username() }
    func currentAccessKey() async -> Data { await inner.currentAccessKey() }
    func openStream() async throws -> any RelayStream { try await inner.openStream() }
    func send(
        _ content: BRPContent, to recipient: AccountId, requestState: ConversationRequestState, theirAccessKey: Data?,
        ephemeral: Bool
    ) async throws -> SendOutcome {
        try await inner.send(
            content, to: recipient, requestState: requestState, theirAccessKey: theirAccessKey, ephemeral: ephemeral)
    }
    func sendPresence(_ presence: BRPContent, to contacts: [(account: AccountId, accessKey: Data)]) async throws
        -> [AccountId]
    {
        if let gate = presenceGate.withLock({ $0 }) { await gate.pass() }
        return try await inner.sendPresence(presence, to: contacts)
    }
    func performMaintenance() async throws { try await inner.performMaintenance() }
    func rotateAccessKey() async throws -> Data { try await inner.rotateAccessKey() }
    func announceProfile(_ profile: BRPContent, to contacts: [(account: AccountId, theirAccessKey: Data?)]) async throws
        -> [AccountId]
    { try await inner.announceProfile(profile, to: contacts) }
    func needsProfileAnnouncement() async -> Bool { await inner.needsProfileAnnouncement() }
    func uploadAttachment(fileAt url: URL, kind: BRPAttachment.Kind, contentType: String, fileName: String?)
        async throws -> BRPAttachment
    { try await inner.uploadAttachment(fileAt: url, kind: kind, contentType: contentType, fileName: fileName) }
    func downloadAttachment(_ attachment: BRPAttachment, to destination: URL) async throws {
        try await inner.downloadAttachment(attachment, to: destination)
    }
    func deleteUploadedMedia(_ attachment: BRPAttachment) async throws { try await inner.deleteUploadedMedia(attachment) }
    func findAccount(username: NormalizedUsername) async throws -> AccountId? {
        try await inner.findAccount(username: username)
    }
    func usernameOwner(_ username: NormalizedUsername) async throws -> AccountId? {
        try await inner.usernameOwner(username)
    }
    func changeUsername(to username: NormalizedUsername) async throws { try await inner.changeUsername(to: username) }
    func safetyNumber(with contact: AccountId) async throws -> SafetyNumber? { try await inner.safetyNumber(with: contact) }
    func identityTrust(for contact: AccountId) async throws -> IdentityTrust { try await inner.identityTrust(for: contact) }
    func approveIdentityChange(for contact: AccountId) async throws { try await inner.approveIdentityChange(for: contact) }
    func markVerified(_ verified: Bool, contact: AccountId) async throws {
        try await inner.markVerified(verified, contact: contact)
    }
    func forgetContact(_ contact: AccountId) async throws { try await inner.forgetContact(contact) }
    func makeBackup(displayName: String, contacts: [BRPBackupContact]) async throws -> BRPAccountBackup {
        try await inner.makeBackup(displayName: displayName, contacts: contacts)
    }
    func deleteAccount() async throws {
        if failDeleteAccount.withLock({ $0 }) { throw RelayUnreachable() }
        try await inner.deleteAccount()
    }
}

/// ``FakeBackendFactory`` with switches: hold registration (proof-of-work
/// takes seconds on a phone), fail a restore right after the token rotation
/// was saved, a relay whose account deletion fails, a hanging presence send.
final class ReviewBackendFactory: BackendFactory, @unchecked Sendable {
    let inner = FakeBackendFactory()
    let registerGate = LockedState<AsyncGate?>(nil)
    let failRestoreAfterRotation = LockedState(false)
    let failDeleteAccount = LockedState(false)
    let presenceGate = LockedState<AsyncGate?>(nil)
    static let rotatedToken = Data(repeating: 0xAB, count: 32)
    struct InjectedFailure: Error {}

    var backend: FakeBackend { inner.backend }

    private func wrap(_ backend: any MessagingBackend) -> any MessagingBackend {
        ReviewBackend(inner: inner.backend, failDeleteAccount: failDeleteAccount, presenceGate: presenceGate)
    }

    func register(username: NormalizedUsername, storage: LocalStorage) async throws -> any MessagingBackend {
        if let gate = registerGate.withLock({ $0 }) { await gate.pass() }
        return wrap(try await inner.register(username: username, storage: storage))
    }

    func open(account: LocalAccount, storage: LocalStorage) throws -> any MessagingBackend {
        wrap(try inner.open(account: account, storage: storage))
    }

    func restore(_ backup: BRPAccountBackup, storage: LocalStorage) async throws -> any MessagingBackend {
        guard failRestoreAfterRotation.withLock({ $0 }) else { return wrap(try await inner.restore(backup, storage: storage)) }
        // What `Messenger.restore` has made permanent at this point (§5.5
        // steps 2–3): the identity is stored, the relay rotated the token
        // (the backup's token is dead now) and the new one was saved.
        _ = try storage.createIdentity(
            try IdentityKeyPair(bytes: backup.identityKeyPair), registrationId: backup.registrationID)
        try storage.accountSaver(
            LocalAccount(
                accountId: AccountId(bytes: backup.accountID)!, authToken: Self.rotatedToken,
                username: try NormalizedUsername(backup.username), accessKey: backup.accessKey,
                pendingSetup: [.replacePreKeys, .announceProfile]))
        // Then anything fails: the contacts write (`AccountBackupService.restore`),
        // the runtime state file (complete protection while the phone locks), …
        throw InjectedFailure()
    }
}

@MainActor
final class ReviewRuntimeFixture {
    static let code = "482917"
    let directory: TemporaryDirectory
    let clock = ManualClock()
    let factory = ReviewBackendFactory()
    let biometrics = FakeBiometrics()
    let items = HoldableSecureItemStore()
    let tasks = NoBackgroundTasks()
    let capture = ManualCaptureEvents()
    let board = FakePasteboard()
    let environment: RuntimeEnvironment
    var runtime: AppRuntime

    init() throws {
        directory = try TemporaryDirectory()
        environment = RuntimeEnvironment(
            directories: directory.directories, secureItems: items, biometrics: biometrics, backends: factory,
            clock: clock, vault: VaultController.Configuration(iterations: 1000), engine: .fast,
            reconnectBackoff: Backoff(base: 1, cap: 4, floor: 1), gracePeriod: 30, watchesNetworkPath: false)
        runtime = AppRuntime(
            environment: environment, backgroundTasks: tasks, captureEvents: capture,
            pasteboard: SecurePasteboard(pasteboard: board), strings: RuntimeStrings(languageCode: "en"))
    }

    var backend: FakeBackend { factory.backend }
    var vaultFile: URL { environment.directories.vault.appendingPathComponent("vault.brpv") }

    func relaunch() {
        runtime = AppRuntime(
            environment: environment, backgroundTasks: tasks, captureEvents: capture,
            pasteboard: SecurePasteboard(pasteboard: board), strings: RuntimeStrings(languageCode: "en"))
    }

    func onboard() async throws {
        try await runtime.createAccount(username: "selin_n", displayName: "Selin", code: Self.code, confirmation: Self.code)
    }

    func connect() async throws {
        try await eventually { self.backend.latestStream?.isRunning == true }
        backend.latestStream?.push(.queueEmpty)
        try await eventually { self.runtime.connection == .open }
    }

    @discardableResult
    func acceptedContact() throws -> AccountId {
        let peer = AccountId(uuid: UUID())
        let storage = try #require(runtime.session?.storage)
        let now = clock.now()
        try storage.write { tx in
            try tx.contacts.create(peer, state: .accepted, now: now)
            try tx.contacts.setTheirAccessKey(Data(repeating: 2, count: 16), for: peer)
            try tx.contacts.setOurAccessKeySent(Data(repeating: 9, count: 16), to: peer)
            var profile = ContactProfile()
            profile.displayName = "Deniz"
            profile.presenceSharing = true
            try tx.contacts.setProfile(profile, for: peer)
            _ = try tx.conversations.ensure(for: peer, now: now)
        }
        return peer
    }

    func push(_ content: BRPContent, from sender: AccountId) {
        backend.latestStream?.push(
            .message(
                InboundMessage(
                    relayId: UUID().uuidString, serverTimestamp: 0, sender: sender, senderUsername: nil,
                    content: content, identityChanged: false)))
    }
}

// MARK: - Lifecycle, vault and erase (AppRuntime)

@Suite("Security review: app lifecycle", .serialized)
@MainActor
struct SecurityReviewLifecycleTests {
    /// AppRuntime.scenePhaseChanged(.background) only locks when
    /// `lockState == .unlocked`; during an unlock it is `.working`, and
    /// `becomeUnlocked` never looks at the phase. The unlock then completes
    /// in the background: no grace timer, no background task, the DEK stays
    /// in memory for as long as the process lives, and the next return to the
    /// foreground shows the unlocked app without the code (§5.4 step 4,
    /// owner rule "lock at every return from the background").
    @Test func aCodeUnlockFinishingAfterTheUserLeftTheAppStaysLocked() async throws {
        let fixture = try ReviewRuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        await fixture.runtime.lockNow()
        #expect(fixture.runtime.lockState == .locked)

        fixture.items.holdNextRead()
        let unlock = Task { try await fixture.runtime.unlock(code: ReviewRuntimeFixture.code) }
        try await eventually("unlock in progress") { fixture.items.isHolding }
        #expect(fixture.runtime.lockState == .working)
        fixture.runtime.scenePhaseChanged(.background)  // home gesture while the key is derived
        fixture.items.release()
        try await unlock.value

        #expect(
            fixture.runtime.lockState != .unlocked,
            "the vault was unlocked while the app was in the background (no grace period, no lock)")
        fixture.clock.advance(by: 3600)
        await settle()
        #expect(fixture.runtime.session == nil, "an hour in the background later the DEK is still in memory")
        fixture.runtime.scenePhaseChanged(.active)
        #expect(
            fixture.runtime.lockState != .unlocked, "returning from the background shows the unlocked app without the code")
    }

    /// Same window, much wider: registration solves a proof-of-work for
    /// seconds; leaving the app meanwhile leaves a fresh account unlocked in
    /// the background with no grace timer.
    @Test func aRegistrationFinishingInTheBackgroundStaysLocked() async throws {
        let fixture = try ReviewRuntimeFixture()
        let gate = AsyncGate()
        fixture.factory.registerGate.withLock { $0 = gate }
        let onboarding = Task { try await fixture.onboard() }
        try await eventually("proof-of-work running") { gate.hasEntered }
        fixture.runtime.scenePhaseChanged(.background)
        gate.open()
        try await onboarding.value

        fixture.clock.advance(by: 3600)
        await settle()
        #expect(fixture.runtime.session == nil, "the new account's DEK stays in memory in the background")
        fixture.runtime.scenePhaseChanged(.active)
        #expect(fixture.runtime.lockState != .unlocked, "back from the background without the code")
    }

    /// AppRuntime.restore erases *everything* on any error, also after
    /// `POST /v1/accounts/me/token` succeeded. From then on the backup's token
    /// is revoked and the rotated token, saved in the new storage, is the only
    /// credential of the account (§5.5 step 3: "MUST persist the rotated
    /// token"). eraseLocalData() destroys it: the account is lost for good
    /// (the old phone was logged out, and the backup can never be restored
    /// again because its token no longer works).
    @Test func aRestoreFailingAfterTheTokenRotationKeepsTheRotatedToken() async throws {
        let fixture = try ReviewRuntimeFixture()
        fixture.factory.failRestoreAfterRotation.withLock { $0 = true }
        let backup = fixture.backend.fullBackup(displayName: "Selin", contacts: [])
        let file = fixture.directory.url.appendingPathComponent("backup.brpb")
        try AccountBackupFile.export(backup, password: "yedek-parolasi-42").write(to: file)

        await #expect(throws: ReviewBackendFactory.InjectedFailure.self) {
            try await fixture.runtime.restore(
                backupFile: file, password: "yedek-parolasi-42", code: "730415", confirmation: "730415")
        }
        #expect(
            FileManager.default.fileExists(atPath: fixture.vaultFile.path),
            "the vault holding the only valid (rotated) token was erased")
        fixture.relaunch()
        guard fixture.runtime.lockState == .locked else {
            Issue.record("after a relaunch the phone is back at onboarding: the restored account is unrecoverable")
            return
        }
        try await fixture.runtime.unlock(code: "730415")
        let saved = try fixture.runtime.session?.storage.read { try $0.account.load() }
        #expect(saved?.authToken == ReviewBackendFactory.rotatedToken)
    }

    /// While live location is shared the store stays open after the grace
    /// period "until sharing ends". The only caller of
    /// `endLiveLocationOnlySessionIfIdle()` is the tick task, which
    /// `clearUnlockedState()` cancelled when the app locked, so the session
    /// is never closed: the DEK and the open database stay in memory in the
    /// background after sharing ended (§5.4 step 4: "MUST be erased").
    @Test func stoppingLiveLocationInTheBackgroundZeroesTheKey() async throws {
        let fixture = try ReviewRuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        let sharing = try await fixture.runtime.startLiveLocation(
            to: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 900)
        fixture.runtime.scenePhaseChanged(.background)
        let key = try #require(fixture.runtime.session?.key)
        try await eventually("grace over, live location continues") {
            fixture.clock.advance(by: 1)
            return fixture.runtime.session?.liveLocationOnly == true
        }
        try await fixture.runtime.updateLiveLocation(sharing, peer: peer, latitude: 41.001, longitude: 29.001, accuracy: 5)
        try await fixture.runtime.stopLiveLocation(sharing, peer: peer)  // from the OS indicator, still locked

        // Longer than the 15 s tick that would have closed it while unlocked.
        try? await eventually(timeout: 17, "store closed once sharing ended") { fixture.runtime.session == nil }
        #expect(key.isWiped, "the DEK outlives live-location sharing in the background")
    }

    /// close() sets `self.session = nil` before the teardown has run, and the
    /// teardown first awaits network calls (presence OFFLINE, then the
    /// outbox send in flight: up to URLSession's 60 s each on a bad network).
    /// If the system ends the background time meanwhile, graceExpired()
    /// finds no session and returns without wiping: the app is suspended
    /// with the DEK in memory and the database open (§5.4 step 4).
    @Test func theSystemEndingBackgroundTimeDuringASlowTeardownStillZeroesTheKey() async throws {
        let fixture = try ReviewRuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        fixture.push(.presence(.online), from: peer)
        try await eventually("contact seen online") { fixture.runtime.presence[peer] == .active }
        try await eventually("presence reply sent") { !fixture.backend.presenceSends.isEmpty }
        let key = try #require(fixture.runtime.session?.key)

        let slowNetwork = AsyncGate()
        fixture.factory.presenceGate.withLock { $0 = slowNetwork }
        defer { slowNetwork.open() }
        fixture.runtime.scenePhaseChanged(.background)
        try await eventually("grace over, OFFLINE on its way") {
            fixture.clock.advance(by: 1)
            return slowNetwork.hasEntered
        }
        fixture.tasks.expireAll()  // iOS takes the background time back
        #expect(key.isWiped, "the app is suspended with the DEK in memory")
    }

    /// eraseEverything(deleteAccountOnRelay: true) swallows the relay error
    /// (`try?`) and then destroys the credentials. The user asked for the
    /// account to be deleted; instead it stays registered on the relay (180
    /// days, §4.10) with its username taken and its mailbox accepting
    /// contacts' messages, and it can never be deleted any more.
    @Test func deletingTheAccountOnTheRelayDoesNotFailSilently() async throws {
        let fixture = try ReviewRuntimeFixture()
        fixture.factory.failDeleteAccount.withLock { $0 = true }
        try await fixture.onboard()
        await fixture.runtime.eraseEverything(deleteAccountOnRelay: true)
        #expect(
            fixture.runtime.lockState != .needsSetup,
            "the relay refused the deletion, yet the only credentials that could delete the account are gone")
    }

    /// openConversation() checks `lockState` only before its first await. If
    /// the app locks while `engine.messages(of:)` runs, the decrypted
    /// messages are written back into `openMessages` after
    /// `clearUnlockedState()`, and `markDisplayed` then starts disappearing
    /// timers and queues read receipts while the user is gone.
    @Test func openingAConversationAsTheAppLocksMarksNothingDisplayed() async throws {
        let fixture = try ReviewRuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        fixture.push(.textMessage("bunu kimse görmesin", expire: 60), from: peer)
        try await eventually { fixture.runtime.chats.first { $0.id == peer }?.lastMessage != nil }

        let opening = Task { await fixture.runtime.openConversation(peer) }
        await Task.yield()  // openConversation is now awaiting the engine
        fixture.runtime.scenePhaseChanged(.background)
        await opening.value

        #expect(fixture.runtime.lockState == .locked)
        #expect(fixture.runtime.openMessages.isEmpty, "decrypted messages are back in observable state after the lock")
        let storage = try #require(fixture.runtime.session?.storage)
        let now = fixture.clock.now()
        let displayedAt = try storage.read { tx -> Date? in
            guard let conversation = try tx.conversations.forContact(peer) else { return nil }
            return try tx.messages.page(in: conversation.id, now: now).first?.timestamps.displayedAt
        }
        #expect(displayedAt == nil, "the disappearing timer started (and a read receipt was queued) while locked")
    }
}

// MARK: - Vault

@Suite("Security review: vault")
struct SecurityReviewVaultTests {
    /// The optional "wipe after 10 wrong codes" also fires inside
    /// `changeCode` (LocalVault.changeCode → unlock), but
    /// VaultController.changeCode does not call `removeLeftovers()` like
    /// `unlock(code:)` does (and AppRuntime.changeCode does not erase the
    /// local data). The Secure Enclave key and the biometric wrap of the DEK
    /// survive the "crypto-shred everything" of §5.2: Face ID still returns
    /// the DEK, which still opens the database left on disk.
    @Test func theOptionalWipeFiringDuringACodeChangeAlsoShredsQuickUnlock() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: VaultControllerTests.code, confirmation: VaultControllerTests.code)
        try await fixture.vault.enableQuickUnlock(code: VaultControllerTests.code)
        await fixture.vault.setWipeAfterTenFailures(true)
        var wiped = false
        for _ in 0..<10 {
            fixture.pass(4000)  // past every delay (≤ 1 h), well inside the 7-day window
            do {
                try await fixture.vault.changeCode(
                    current: VaultControllerTests.otherCode, new: "590183", confirmation: "590183")
            } catch VaultError.wiped {
                wiped = true
            } catch {}
        }
        #expect(wiped)
        #expect(await fixture.vault.hasVault == false)
        #expect(!fixture.biometrics.hasKey, "the Secure Enclave key survived the wipe")
        let survivor = try? await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        #expect(survivor == nil, "after the wipe fired, Face ID still yields the DEK")
    }
}

// MARK: - Messaging semantics (MessagingEngine)

@Suite("Security review: messaging")
struct SecurityReviewMessagingTests {
    /// A view-once photo goes out with its own preview: MediaPreparation puts
    /// a ≤ 160 px JPEG thumbnail into every image, `send(…, viewOnce: true)`
    /// keeps it, and the receiver's MessageItem exposes it before the item
    /// is opened (and for as long as it is never opened).
    @Test func viewOnceMediaCarriesNoPreview() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let preview = Data("160px-jpeg-of-the-photo".utf8)
        let file = fixture.directory.directories.stagingFile(extension: "jpg")
        try Data("full photo".utf8).write(to: file)
        let media = PreparedMedia(
            file: file, kind: .image, contentType: "image/jpeg", size: 10, width: 4, height: 3, thumbnail: preview)
        let id = try await fixture.engine.send(.media(media), to: peer, viewOnce: true)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data?.messageID == id } }
        let sent = try #require(fixture.backend.sent(to: peer).first { $0.data?.messageID == id }?.data)
        #expect(sent.viewOnce)
        if case .attachment(let attachment) = sent.payload {
            #expect(attachment.thumbnail.isEmpty, "the view-once photo is sent with a preview of itself")
        }

        var incoming = BRPAttachment()
        incoming.kind = .image
        incoming.mediaID = RandomId.make()
        incoming.deleteToken = Data(repeating: 1, count: 32)
        incoming.key = Data(repeating: 2, count: 64)
        incoming.digest = Data(repeating: 3, count: 32)
        incoming.size = 5
        incoming.contentType = "image/jpeg"
        incoming.thumbnail = preview
        let received = RandomId.make()
        try await fixture.receive(.data(.attachment(incoming), id: received, viewOnce: true), from: peer)
        let item = try #require(try await fixture.messages(peer).first { $0.messageId == received })
        if case .attachment(let shown) = item.body {
            #expect(shown.thumbnail == nil, "an unopened view-once item shows its picture in the bubble")
        }
        await fixture.engine.stop()
    }

    /// §6.4 / owner rule: nothing goes back to a requester before the user
    /// accepts ("declining sends nothing back", the requester cannot tell the
    /// request was seen). `send` refuses `.incomingRequest`, but `react` does
    /// not check the request state at all and `updateConversationSettings`
    /// only excludes `.blocked`: both are queued and sent to the stranger.
    @Test func nothingReachesARequesterBeforeAcceptance() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let requester = AccountId(uuid: UUID())
        let id = RandomId.make()
        try await fixture.receive(.textMessage("selam", id: id), from: requester, username: "mert_k")
        try? await fixture.engine.react(to: id, peer: requester, emoji: "👍")
        try? await fixture.engine.updateConversationSettings(requester, ghost: nil, defaultExpireTimerSeconds: 300)
        try? await fixture.engine.markDisplayed(requester)
        // Not `eventually`: it records its timeout as an issue, and here the
        // timeout (nothing was sent) is the expected outcome.
        _ = await happens(within: 2) { !fixture.backend.sent(to: requester).isEmpty }
        #expect(
            fixture.backend.sent(to: requester).isEmpty,
            "sent to an unaccepted requester: \(fixture.backend.sent(to: requester).map(\.content.body))")
        #expect(try fixture.contact(requester)?.requestState == .incomingRequest)
        await fixture.engine.stop()
    }

    /// Two people who exchanged usernames both write first (common: "add
    /// me"). Each phone is OUTGOING_REQUEST, the other's first message does
    /// not change that (MessageRequestPolicy: only ACCEPT ends a request), and
    /// `acceptRequest` requires INCOMING_REQUEST. Neither side can ever reach
    /// ACCEPTED: no access keys, no receipts, no presence, proof-of-work and
    /// `sender_username` on every message, forever.
    @Test func crossingFirstMessagesCanStillBecomeContacts() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        _ = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        try await fixture.engine.send(.text("selam bob, ben selin"), to: bob)
        // Bob looked us up and wrote first as well (his own outgoing request).
        try await fixture.receive(.textMessage("selam selin!"), from: bob, username: "bob_99")
        var state = try fixture.contact(bob)?.requestState
        if state != .accepted {
            try? await fixture.engine.acceptRequest(bob)
            state = try fixture.contact(bob)?.requestState
        }
        #expect(state == .accepted, "crossing requests deadlock in OUTGOING_REQUEST on both phones")
        await fixture.engine.stop()
    }

    /// Blocking rotates our access key and sends the new one to every *other*
    /// accepted contact. Unblocking restores ACCEPTED but never hands the
    /// unblocked contact the current key, so every message it sends us is
    /// refused (403) and falls back to proof-of-work for good.
    @Test func unblockingHandsTheContactOurCurrentAccessKey() async throws {
        let fixture = try EngineFixture()
        let friend = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.block(friend)
        try await eventually { fixture.backend.state.withLock { $0.rotations } == 1 }
        try await fixture.engine.unblock(friend)
        #expect(try fixture.contact(friend)?.requestState == .accepted)
        let current = fixture.backend.state.withLock { $0.accessKey }
        try? await eventually(timeout: 2, "current access key sent") {
            fixture.backend.sent(to: friend).contains {
                if case .accessKey(let update)? = $0.control { return update.accessKey == current }
                return false
            }
        }
        #expect(
            try fixture.contact(friend)?.ourAccessKeySent == current,
            "the unblocked contact still holds the access key that was revoked by the block")
        await fixture.engine.stop()
    }

    /// Duplicates are recognised only while the message row exists. A
    /// disappearing message that expired (or was deleted for me) leaves no
    /// trace, so the sender's retransmission of the same `message_id` (the
    /// relay's 202 was lost, or the sender crashed before recording it, and
    /// its outbox retried at its next unlock) stores it again with a fresh
    /// timer: the message comes back.
    @Test func aRetransmittedDisappearingMessageDoesNotComeBack() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = RandomId.make()
        try await fixture.receive(.textMessage("kendini imha eder", id: id, expire: 60), from: peer, relayId: "r1")
        try await fixture.engine.markDisplayed(peer)
        let conversation = try #require(try fixture.storage.read { try $0.conversations.forContact(peer) })
        try await eventually("expired and deleted") {
            fixture.clock.advance(by: 5)
            return try fixture.storage.read { try $0.messages.count(in: conversation.id) } == 0
        }
        try await fixture.receive(.textMessage("kendini imha eder", id: id, expire: 60), from: peer, relayId: "r2")
        #expect(try await fixture.messages(peer).isEmpty, "the expired disappearing message is shown again")
        await fixture.engine.stop()
    }
}

// MARK: - Connection

@Suite("Security review: connection")
struct SecurityReviewConnectionTests {
    /// `consecutiveFailures` is reset at every `queue_empty`. A connection
    /// that keeps dying right after the backlog (relay crash loop, proxy
    /// timeout, flapping network) is retried after `base` seconds forever,
    /// and every reconnect makes the engine fan ONLINE presence out to all
    /// contacts again (§8.6), which they answer: a reconnect storm with
    /// amplification on the relay.
    @Test func aConnectionDroppingRightAfterTheBacklogBacksOff() async throws {
        let clock = ManualClock()
        let backend = FakeBackend()
        let supervisor = ConnectionSupervisor(
            backend: backend, clock: clock, backoff: Backoff(base: 1, cap: 60, floor: 0.5, random: { $0.upperBound }),
            events: ConnectionEvents(deliver: { _ in }, queueEmpty: {}))
        await supervisor.start()
        var delays: [TimeInterval] = []
        for round in 1...6 {
            try await eventually("connection \(round)") {
                backend.streamCount == round && backend.latestStream?.isRunning == true
            }
            backend.latestStream?.push(.queueEmpty)
            try await eventually { await supervisor.status == .open }
            backend.latestStream?.push(.fail(.connectionClosed(code: 1006)))
            try await eventually { if case .retrying = await supervisor.status { return true } else { return false } }
            try await eventually { clock.sleeperCount > 0 }
            guard case .retrying(let at) = await supervisor.status else { return }
            let delay = at.timeIntervalSince(clock.now())
            delays.append(delay)
            clock.advance(by: delay + 0.01)
        }
        await supervisor.stop()
        #expect((delays.last ?? 0) >= 8, "six short-lived connections in a row, still retrying after \(delays) s")
    }
}
