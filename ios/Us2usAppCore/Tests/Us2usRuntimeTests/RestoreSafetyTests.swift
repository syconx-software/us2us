import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// "I forgot my code" → restore from a backup (3.3 › 7.3 › 2.2 › 2.3): the
/// locked vault — with its message history, which no backup holds — is
/// erased only once the backup is fully valid and the relay still accepts
/// its token (§5.5). Anything less leaves the vault as it was, and the old
/// code still opens it.
@Suite("Restore in place of a locked vault")
@MainActor
struct RestoreReplacingLockedVaultTests {
    static let password = "yedek-parolasi-42"
    static let newCode = "730415"
    static let message = "yerinde kalmalı"

    /// A locked phone with an account, one contact and one message, and a
    /// backup file of that account (`breakIt` damages its content before
    /// it is encrypted with the right password).
    private func lockedPhone(breaking breakIt: ((inout BRPAccountBackup) -> Void)? = nil) async throws
        -> (fixture: RuntimeFixture, peer: AccountId, file: URL)
    {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        let peer = try fixture.acceptedContact()
        _ = try await fixture.runtime.sendText(Self.message, to: peer)
        let storage = try #require(fixture.runtime.session?.storage)
        let contacts = try storage.read { try AccountBackupService.backupContacts($0) }
        var backup = fixture.backend.fullBackup(displayName: "Selin", contacts: contacts)
        breakIt?(&backup)
        let file = fixture.directory.url.appendingPathComponent("backup.brpb")
        try AccountBackupFile.export(backup, password: Self.password).write(to: file)
        await fixture.runtime.lockNow()
        #expect(fixture.runtime.lockState == .locked)
        return (fixture, peer, file)
    }

    private func restore(
        _ fixture: RuntimeFixture, _ file: URL, password: String? = nil, confirmation: String? = nil,
        replacing: Bool = true
    ) async throws {
        try await fixture.runtime.restore(
            backupFile: file, password: password ?? Self.password, code: Self.newCode,
            confirmation: confirmation ?? Self.newCode, replacingLockedVault: replacing)
    }

    /// Nothing was erased: still locked, the old code opens the vault, and
    /// the message and the contact are there.
    private func expectUntouched(_ fixture: RuntimeFixture, peer: AccountId) async throws {
        #expect(fixture.runtime.lockState == .locked)
        // The old vault, not a kept new one: the app tells them apart by this.
        #expect(!fixture.runtime.keptAccountAfterFailedSetup)
        #expect(fixture.factory.restores.withLock { $0 } == 0)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
        await fixture.runtime.openConversation(peer)
        #expect(fixture.runtime.openMessages.contains { $0.body == .text(Self.message) })
        #expect(await fixture.runtime.contact(peer)?.displayName == "Deniz")
    }

    @Test("a backup that decrypts but is malformed changes nothing, and the relay is not asked")
    func malformedBackup() async throws {
        let phone = try await lockedPhone { $0.authToken = Data([1]) }
        await #expect(throws: AccountBackupError.invalidFile) { try await restore(phone.fixture, phone.file) }
        #expect(phone.fixture.factory.backupChecks.withLock { $0 } == 0)
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("a backup whose token the relay refuses changes nothing")
    func revokedToken() async throws {
        let phone = try await lockedPhone()
        phone.fixture.factory.backupCheckFailure.withLock { $0 = BackupRestoreError.backupNoLongerValid }
        await #expect(throws: RuntimeError.backupRevoked) { try await restore(phone.fixture, phone.file) }
        #expect(phone.fixture.factory.backupChecks.withLock { $0 } == 1)
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("a plain 401 from the relay means the same")
    func unauthorized() async throws {
        let phone = try await lockedPhone()
        phone.fixture.factory.backupCheckFailure.withLock {
            $0 = RelayError.server(status: 401, code: .unauthorized, message: nil)
        }
        await #expect(throws: RuntimeError.backupRevoked) { try await restore(phone.fixture, phone.file) }
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("no connection to the relay changes nothing")
    func offline() async throws {
        let phone = try await lockedPhone()
        phone.fixture.factory.backupCheckFailure.withLock { $0 = RelayError.network(.notConnectedToInternet) }
        await #expect(throws: RelayError.network(.notConnectedToInternet)) {
            try await restore(phone.fixture, phone.file)
        }
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("a wrong backup password or a new code that does not match changes nothing")
    func wrongPasswordOrCode() async throws {
        let phone = try await lockedPhone()
        await #expect(throws: AccountBackupError.wrongPassword) {
            try await restore(phone.fixture, phone.file, password: "yanlis-parola-00")
        }
        await #expect(throws: VaultError.invalidNewCode(.mismatch)) {
            try await restore(phone.fixture, phone.file, confirmation: "730416")
        }
        #expect(phone.fixture.factory.backupChecks.withLock { $0 } == 0)
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("without the explicit flag a locked vault is never replaced")
    func needsTheFlag() async throws {
        let phone = try await lockedPhone()
        await #expect(throws: RuntimeError.alreadySetUp) {
            try await restore(phone.fixture, phone.file, replacing: false)
        }
        #expect(phone.fixture.factory.backupChecks.withLock { $0 } == 0)
        try await expectUntouched(phone.fixture, peer: phone.peer)
    }

    @Test("a valid backup replaces the vault: account and contacts come back, this phone's messages do not")
    func replaces() async throws {
        let phone = try await lockedPhone()
        let fixture = phone.fixture
        try await restore(fixture, phone.file)
        #expect(fixture.runtime.lockState == .unlocked)
        #expect(fixture.factory.backupChecks.withLock { $0 } == 1)
        #expect(fixture.factory.restores.withLock { $0 } == 1)
        #expect(fixture.runtime.settings.displayName == "Selin")
        #expect(await fixture.runtime.contact(phone.peer)?.displayName == "Deniz")
        try await eventually { fixture.runtime.chats.contains { $0.id == phone.peer } }
        #expect(fixture.runtime.chats.first { $0.id == phone.peer }?.lastMessage == nil)
        await fixture.runtime.openConversation(phone.peer)
        #expect(!fixture.runtime.openMessages.contains { $0.body == .text(Self.message) })

        // The new code opens the new vault; the forgotten one no longer does.
        await fixture.runtime.lockNow()
        await #expect(throws: UnlockError.vault(.wrongCode)) {
            try await fixture.runtime.unlock(code: RuntimeFixture.code)
        }
        try await fixture.runtime.unlock(code: Self.newCode)
        #expect(fixture.runtime.lockState == .unlocked)
    }

    @Test("the relay refusing the rotation after the check: the new vault goes too, and the error says why")
    func refusedDuringRotation() async throws {
        let phone = try await lockedPhone()
        phone.fixture.factory.restoreFailure.withLock { $0 = BackupRestoreError.backupNoLongerValid }
        await #expect(throws: RuntimeError.backupRevoked) { try await restore(phone.fixture, phone.file) }
        #expect(phone.fixture.runtime.lockState == .needsSetup)
        phone.fixture.relaunch()
        #expect(phone.fixture.runtime.lockState == .needsSetup)
    }

    @Test("7.3's check opens the backup and asks the relay, and changes nothing")
    func checkOnly() async throws {
        let phone = try await lockedPhone()
        let fixture = phone.fixture
        try await fixture.runtime.checkBackup(backupFile: phone.file, password: Self.password)
        #expect(fixture.factory.backupChecks.withLock { $0 } == 1)
        #expect(fixture.runtime.lockState == .locked)
        await #expect(throws: AccountBackupError.wrongPassword) {
            try await fixture.runtime.checkBackup(backupFile: phone.file, password: "yanlis-parola-00")
        }
        fixture.factory.backupCheckFailure.withLock { $0 = BackupRestoreError.backupNoLongerValid }
        await #expect(throws: RuntimeError.backupRevoked) {
            try await fixture.runtime.checkBackup(backupFile: phone.file, password: Self.password)
        }
        try await expectUntouched(fixture, peer: phone.peer)
    }

    @Test("on a new phone a refused backup leaves onboarding as it was")
    func newPhone() async throws {
        let old = try await lockedPhone()
        let newPhone = try RuntimeFixture()
        newPhone.factory.backupCheckFailure.withLock { $0 = BackupRestoreError.backupNoLongerValid }
        await #expect(throws: RuntimeError.backupRevoked) {
            try await restore(newPhone, old.file, replacing: false)
        }
        #expect(newPhone.runtime.lockState == .needsSetup)
        #expect(newPhone.factory.restores.withLock { $0 } == 0)

        newPhone.factory.backupCheckFailure.withLock { $0 = nil }
        try await restore(newPhone, old.file, replacing: false)
        #expect(newPhone.runtime.lockState == .unlocked)
        #expect(old.fixture.runtime.lockState == .locked)
    }
}
