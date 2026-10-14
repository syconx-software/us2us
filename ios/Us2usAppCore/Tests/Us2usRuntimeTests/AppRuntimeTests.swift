import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// Registers into the storage it is given and hands out one ``FakeBackend``.
final class FakeBackendFactory: BackendFactory, @unchecked Sendable {
    let backend = FakeBackend(username: "selin_n")
    let registrations = LockedState(0)
    /// What the relay answers to the read-only backup check (`nil`: the token works).
    let backupCheckFailure = LockedState<(any Error)?>(nil)
    let backupChecks = LockedState(0)
    /// Thrown by a restore once the account is saved: the relay refused the rotation.
    let restoreFailure = LockedState<(any Error)?>(nil)
    let restores = LockedState(0)

    func checkBackup(_ backup: BRPAccountBackup) async throws {
        backupChecks.withLock { $0 += 1 }
        if let failure = backupCheckFailure.withLock({ $0 }) { throw failure }
    }

    func register(username: NormalizedUsername, storage: LocalStorage) async throws -> any MessagingBackend {
        registrations.withLock { $0 += 1 }
        let account = LocalAccount(
            accountId: backend.accountId, authToken: Data(repeating: 7, count: 32), username: username,
            accessKey: Data(repeating: 9, count: 16))
        try storage.accountSaver(account)
        backend.accountSaver = storage.accountSaver
        backend.savedAccount = account
        return backend
    }

    func open(account: LocalAccount, storage: LocalStorage) throws -> any MessagingBackend {
        backend.accountSaver = storage.accountSaver
        backend.savedAccount = account
        return backend
    }

    func restore(_ backup: BRPAccountBackup, storage: LocalStorage) async throws -> any MessagingBackend {
        restores.withLock { $0 += 1 }
        _ = try storage.createIdentity(
            try IdentityKeyPair(bytes: backup.identityKeyPair), registrationId: backup.registrationID)
        let account = LocalAccount(
            accountId: AccountId(bytes: backup.accountID)!, authToken: Data(repeating: 8, count: 32),
            username: try NormalizedUsername(backup.username), accessKey: backup.accessKey)
        try storage.accountSaver(account)
        if let failure = restoreFailure.withLock({ $0 }) { throw failure }
        try storage.write { tx in try AccountBackupService.restoreContacts(backup, tx) }
        return try open(account: account, storage: storage)
    }
}

/// A backend whose backups carry a real identity, so restore can be exercised.
extension FakeBackend {
    func fullBackup(displayName: String, contacts: [BRPBackupContact]) -> BRPAccountBackup {
        var backup = BRPAccountBackup()
        backup.version = AccountBackupFile.currentVersion
        backup.accountID = accountId.bytes
        backup.authToken = Data(repeating: 7, count: 32)
        backup.identityKeyPair = IdentityKeyPair.generate().serialize()
        backup.registrationID = 1234
        backup.username = "selin_n"
        backup.displayName = displayName
        backup.accessKey = Data(repeating: 9, count: 16)
        backup.contacts = contacts
        return backup
    }
}

@MainActor
final class RuntimeFixture {
    static let code = "482917"
    let directory: TemporaryDirectory
    let clock = ManualClock()
    let factory = FakeBackendFactory()
    let biometrics = FakeBiometrics()
    let items = InMemorySecureItemStore()
    let tasks = NoBackgroundTasks()
    let capture = ManualCaptureEvents()
    let board = FakePasteboard()
    let environment: RuntimeEnvironment
    var runtime: AppRuntime

    init() throws {
        directory = try TemporaryDirectory()
        environment = RuntimeEnvironment(
            directories: directory.directories, secureItems: items, biometrics: biometrics, backends: factory,
            clock: clock, vault: VaultController.Configuration(iterations: 1000),
            engine: .fast, reconnectBackoff: Backoff(base: 1, cap: 4, floor: 1), gracePeriod: 30,
            watchesNetworkPath: false)
        runtime = AppRuntime(
            environment: environment, backgroundTasks: tasks, captureEvents: capture,
            pasteboard: SecurePasteboard(pasteboard: board), strings: RuntimeStrings(languageCode: "en"))
    }

    var backend: FakeBackend { factory.backend }

    /// A new process on the same files (an app relaunch).
    func relaunch() {
        runtime = AppRuntime(
            environment: environment, backgroundTasks: tasks, captureEvents: capture,
            pasteboard: SecurePasteboard(pasteboard: board), strings: RuntimeStrings(languageCode: "en"))
    }

    func onboard() async throws {
        try await runtime.createAccount(
            username: "selin_n", displayName: "Selin", code: Self.code, confirmation: Self.code)
    }

    /// Delivers the backlog end on the current stream.
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

@Suite("App runtime lifecycle")
@MainActor
struct AppRuntimeTests {
    @Test func onboardingConnectsOnlyWhileActiveAndUnlocked() async throws {
        let fixture = try RuntimeFixture()
        #expect(fixture.runtime.lockState == .needsSetup)
        try await fixture.onboard()
        #expect(fixture.runtime.lockState == .unlocked)
        #expect(fixture.runtime.ownUsername == "selin_n")
        #expect(fixture.runtime.settings.displayName == "Selin")
        try await fixture.connect()
        #expect(fixture.runtime.connection.line(strings: RuntimeStrings(languageCode: "tr")) == "Hat açık · mesajlar geliyor")
    }

    @Test func backgroundLocksAtOnceAndClosesAfterTheGracePeriod() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        fixture.push(.textMessage("selam"), from: peer)
        try await eventually { fixture.runtime.chats.first?.lastMessage?.body == .text("selam") }

        fixture.runtime.scenePhaseChanged(.inactive)
        #expect(fixture.runtime.isPrivacyCoverVisible)
        #expect(fixture.runtime.lockState == .unlocked)

        fixture.runtime.scenePhaseChanged(.background)
        // By design: locked immediately, nothing left in observable state.
        #expect(fixture.runtime.lockState == .locked)
        #expect(fixture.runtime.chats.isEmpty)
        #expect(fixture.runtime.openMessages.isEmpty)
        #expect(fixture.tasks.begun == 1)
        // Still receiving during the grace period (§5.4).
        let stream = try #require(fixture.backend.latestStream)
        #expect(!stream.isClosed)
        let key = try #require(fixture.runtime.session?.key)

        try await eventually("grace over") {
            fixture.clock.advance(by: 1)
            return stream.isClosed
        }
        try await eventually { fixture.runtime.session == nil }
        // close() drops the session at once; the teardown (key, background
        // task, connection line) finishes afterwards: wait for it, not for
        // `session == nil` alone (that raced under load).
        try await eventually("teardown finished") {
            key.isWiped && fixture.tasks.ended == 1 && fixture.runtime.connection == .offline
        }
        #expect(key.isWiped)
        #expect(fixture.tasks.ended == 1)
        #expect(fixture.runtime.connection == .offline)

        fixture.runtime.scenePhaseChanged(.active)
        #expect(!fixture.runtime.isPrivacyCoverVisible)
        #expect(fixture.runtime.lockState == .locked)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
        try await fixture.connect()
        #expect(fixture.backend.streamCount == 2)
        try await eventually { fixture.runtime.chats.first?.lastMessage?.body == .text("selam") }
    }

    @Test func returningWithinTheGracePeriodResumesTheSameSession() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        fixture.runtime.scenePhaseChanged(.background)
        #expect(fixture.runtime.lockState == .locked)
        fixture.runtime.scenePhaseChanged(.active)
        // The lock screen again, even after a few seconds away.
        #expect(fixture.runtime.lockState == .locked)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
        #expect(fixture.backend.streamCount == 1)
        #expect(fixture.backend.latestStream?.isClosed == false)
        #expect(fixture.tasks.ended == 1)
        fixture.clock.advance(by: 60)
        await settle()
        #expect(fixture.backend.latestStream?.isClosed == false)
    }

    @Test func theSystemEndingBackgroundTimeZeroesTheKey() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let key = try #require(fixture.runtime.session?.key)
        fixture.runtime.scenePhaseChanged(.background)
        fixture.tasks.expireAll()
        #expect(key.isWiped)
        // Unlocking at once waits for the old session to close (one storage instance).
        fixture.runtime.scenePhaseChanged(.active)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
        #expect(fixture.backend.streamCount == 2)
        #expect(fixture.backend.state.withLock { $0.streams[0].isClosed })
        await fixture.runtime.lockNow()
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
    }

    @Test func everyLaunchStartsLocked() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        _ = try await fixture.runtime.sendText("kalıcı mı?", to: peer)
        await fixture.runtime.lockNow()
        fixture.relaunch()
        #expect(fixture.runtime.lockState == .locked)
        await #expect(throws: UnlockError.vault(.wrongCode)) { try await fixture.runtime.unlock(code: "730415") }
        #expect(fixture.runtime.lockState == .locked)
        #expect(fixture.runtime.unlockStatus?.failures == 1)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        try await eventually { fixture.runtime.chats.first?.lastMessage?.body == .text("kalıcı mı?") }
    }

    @Test func aWrongCodeWhileTurningOnQuickUnlockIsCountedOnScreen() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        #expect(fixture.runtime.quickUnlockAvailable)
        await #expect(throws: UnlockError.vault(.wrongCode)) {
            try await fixture.runtime.enableQuickUnlock(code: "730415")
        }
        // The same count the lock screen shows: the warning on 7.1 is never stale.
        #expect(fixture.runtime.unlockStatus?.failures == 1)
        try await fixture.runtime.enableQuickUnlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.unlockStatus?.failures == 0)

        fixture.biometrics.availabilityValue = BiometricAvailability(kind: .faceID, enrollmentState: nil)
        #expect(!fixture.runtime.quickUnlockAvailable)
    }

    @Test func eraseEverythingLeavesNothing() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        await fixture.runtime.eraseEverything()
        #expect(fixture.runtime.lockState == .needsSetup)
        #expect(fixture.items.count == 0)
        let storageRoot = fixture.environment.directories.storage.root
        #expect(!FileManager.default.fileExists(atPath: storageRoot.appendingPathComponent("db/main.sqlite").path))
        #expect(
            !FileManager.default.fileExists(
                atPath: fixture.environment.directories.vault.appendingPathComponent("vault.brpv").path))
        // And onboarding works again.
        try await fixture.onboard()
        #expect(fixture.runtime.lockState == .unlocked)
    }

    @Test func aFailedRegistrationLeavesNothingBehind() async throws {
        let fixture = try RuntimeFixture()
        await #expect(throws: RuntimeError.self) {
            try await fixture.runtime.createAccount(
                username: "1nvalid", displayName: "", code: RuntimeFixture.code, confirmation: RuntimeFixture.code)
        }
        await #expect(throws: VaultError.invalidNewCode(.sequence)) {
            try await fixture.runtime.createAccount(
                username: "selin_n", displayName: "", code: "123456", confirmation: "123456")
        }
        #expect(fixture.runtime.lockState == .needsSetup)
        #expect(fixture.items.count == 0)
    }

    @Test func screenshotsAndRecordingAreReported() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        await fixture.runtime.openConversation(peer)
        fixture.capture.takeScreenshot()
        try await eventually { fixture.runtime.openMessages.contains { $0.body == .notice(.screenshotByMe) } }
        fixture.capture.setCaptured(true)
        #expect(fixture.runtime.isScreenCaptured)
        try await eventually { fixture.runtime.openMessages.contains { $0.body == .notice(.screenRecordingByMe) } }
        try await eventually {
            fixture.backend.sent(to: peer).filter { if case .screenshot? = $0.control { return true } else { return false } }
                .count == 2
        }
        fixture.capture.setCaptured(false)
        #expect(!fixture.runtime.isScreenCaptured)
    }

    @Test func aConversationThatIsNotOpenStillReportsThatItsMessagesChanged() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let deniz = try fixture.acceptedContact()
        let other = try fixture.acceptedContact()
        await fixture.runtime.openConversation(other)
        #expect(fixture.runtime.messageRevisions[deniz] == nil)
        // deniz's chat is not open: nothing is loaded or marked displayed,
        // but a screen listing its items learns that they changed.
        fixture.push(.textMessage("kapalıyken"), from: deniz)
        try await eventually { (fixture.runtime.messageRevisions[deniz] ?? 0) > 0 }
        #expect(!fixture.runtime.openMessages.contains { $0.body == .text("kapalıyken") })
        try await eventually { fixture.runtime.chats.first { $0.id == deniz }?.unreadCount == 1 }
        // Gone with the lock, like everything observable.
        fixture.runtime.scenePhaseChanged(.background)
        #expect(fixture.runtime.messageRevisions.isEmpty)
    }

    @Test func bannersForOtherConversationsShowNoContent() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let deniz = try fixture.acceptedContact()
        let other = try fixture.acceptedContact()
        await fixture.runtime.openConversation(deniz)
        fixture.push(.textMessage("gizli içerik"), from: other)
        try await eventually { fixture.runtime.banner != nil }
        #expect(fixture.runtime.banner?.peer == other)
        #expect(fixture.runtime.banner?.text.contains("gizli") == false)
        fixture.push(.textMessage("burada"), from: deniz)
        try await eventually { fixture.runtime.openMessages.contains { $0.body == .text("burada") } }
        // Displayed while open: no unread count for the open conversation.
        try await eventually { fixture.runtime.chats.first { $0.id == deniz }?.unreadCount == 0 }
    }

    @Test func backupExportAndRestoreOnANewPhone() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        let peer = try fixture.acceptedContact()
        await #expect(throws: BackupPasswordError.tooShort) {
            _ = try await fixture.runtime.exportBackup(password: "kisa", confirmation: "kisa")
        }
        // The fake backend's backups carry a real identity.
        let storage = try #require(fixture.runtime.session?.storage)
        let contacts = try storage.read { try AccountBackupService.backupContacts($0) }
        let backup = fixture.backend.fullBackup(displayName: "Selin", contacts: contacts)
        let file = try AccountBackupFile.export(backup, password: "yedek-parolasi-42")
        let exported = try await fixture.runtime.exportBackup(
            password: "yedek-parolasi-42", confirmation: "yedek-parolasi-42")
        #expect(FileManager.default.fileExists(atPath: exported.path))
        #expect(try Data(contentsOf: exported).range(of: Data("Selin".utf8)) == nil)
        await fixture.runtime.lockNow()
        // The staging copy does not outlive the lock.
        #expect(!FileManager.default.fileExists(atPath: exported.path))

        let newPhone = try RuntimeFixture()
        let backupURL = newPhone.directory.url.appendingPathComponent("backup.brpb")
        try file.write(to: backupURL)
        await #expect(throws: AccountBackupError.wrongPassword) {
            try await newPhone.runtime.restore(
                backupFile: backupURL, password: "yanlis-parola-00", code: RuntimeFixture.code,
                confirmation: RuntimeFixture.code)
        }
        #expect(newPhone.runtime.lockState == .needsSetup)
        try await newPhone.runtime.restore(
            backupFile: backupURL, password: "yedek-parolasi-42", code: "730415", confirmation: "730415")
        #expect(newPhone.runtime.lockState == .unlocked)
        #expect(newPhone.runtime.settings.displayName == "Selin")
        let restored = await newPhone.runtime.contact(peer)
        #expect(restored?.requestState == .accepted)
        #expect(restored?.displayName == "Deniz")
        // The restored contact is in the chat list (an empty conversation:
        // messages are never in a backup).
        try await eventually { newPhone.runtime.chats.contains { $0.id == peer } }
        #expect(newPhone.runtime.chats.first { $0.id == peer }?.lastMessage == nil)
    }

    @Test func settingsAndPresenceFlowThroughTheFacade() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        fixture.push(.presence(.online), from: peer)
        try await eventually { fixture.runtime.presence[peer] == .active }
        try await fixture.runtime.setPresenceSharing(false)
        #expect(fixture.runtime.presence.isEmpty)
        #expect(fixture.runtime.settings.presenceSharing == false)
        try await fixture.runtime.setReadReceipts(false)
        #expect(fixture.runtime.settings.readReceipts == false)
    }

    @Test func actionsNeedAnUnlockedVault() async throws {
        let fixture = try RuntimeFixture()
        await #expect(throws: RuntimeError.locked) {
            _ = try await fixture.runtime.sendText("x", to: AccountId(uuid: UUID()))
        }
        await #expect(throws: RuntimeError.locked) { try await fixture.runtime.changeCode(current: "1", new: "2", confirmation: "2") }
    }
}
