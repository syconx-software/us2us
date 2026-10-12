import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// 7.1 “Yedek · Son: …”: when an exported backup was last saved is kept in
/// the vault's encrypted runtime state, so it survives locking and a
/// relaunch. Erasing the phone (with or without deleting the account) takes
/// it along, and a restored account starts without one: the restore's token
/// rotation (§5.5) makes the restored file unusable.
@Suite("Last backup date")
@MainActor
struct LastBackupTests {
    static let password = "yedek-parolasi-42"
    static let restoredCode = "730415"

    /// The stored time is the recorded one (JSON keeps the full `Double`;
    /// the tolerance only guards against formatting differences).
    private func sameMoment(_ stored: Date?, _ expected: Date) -> Bool {
        guard let stored else { return false }
        return abs(stored.timeIntervalSince(expected)) < 0.001
    }

    /// A backup file of the fixture's account, with its contacts.
    private func backupFile(of fixture: RuntimeFixture) throws -> URL {
        let storage = try #require(fixture.runtime.session?.storage)
        let contacts = try storage.read { try AccountBackupService.backupContacts($0) }
        let backup = fixture.backend.fullBackup(displayName: "Selin", contacts: contacts)
        let file = fixture.directory.url.appendingPathComponent("backup.brpb")
        try AccountBackupFile.export(backup, password: Self.password).write(to: file)
        return file
    }

    @Test("a saved backup's time survives locking and a relaunch")
    func survivesARelaunch() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        #expect(fixture.runtime.lastBackupAt == nil)

        let exported = try await fixture.runtime.exportBackup(password: Self.password, confirmation: Self.password)
        // Exporting alone (the share sheet still open, or cancelled) records nothing.
        #expect(fixture.runtime.lastBackupAt == nil)
        try? FileManager.default.removeItem(at: exported)
        fixture.clock.advance(by: 5)
        let saved = fixture.clock.now()
        try await fixture.runtime.recordBackupSaved()
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))

        await fixture.runtime.lockNow()
        // Nothing read from the vault stays observable while it is locked.
        #expect(fixture.runtime.lastBackupAt == nil)

        fixture.clock.advance(by: 3_600)
        fixture.relaunch()
        #expect(fixture.runtime.lockState == .locked)
        #expect(fixture.runtime.lastBackupAt == nil)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))

        // Only the time is kept, encrypted with the rest of the runtime state.
        let state = fixture.environment.directories.runtime.appendingPathComponent(RuntimeStateFile.fileName)
        let bytes = try Data(contentsOf: state)
        #expect(bytes.range(of: Data("lastBackupAt".utf8)) == nil)

        // A later backup replaces it.
        fixture.clock.advance(by: 86_400)
        let later = fixture.clock.now()
        try await fixture.runtime.recordBackupSaved()
        await fixture.runtime.lockNow()
        fixture.relaunch()
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(sameMoment(fixture.runtime.lastBackupAt, later))
    }

    @Test("a share that finishes after the app left is recorded while the store is open, never after")
    func afterLeavingTheApp() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        fixture.runtime.scenePhaseChanged(.background)
        #expect(fixture.runtime.lockState == .locked)
        #expect(fixture.runtime.session?.inGrace == true)

        let saved = fixture.clock.now()
        try await fixture.runtime.recordBackupSaved()
        // Locked: nothing observable changes until the next unlock.
        #expect(fixture.runtime.lastBackupAt == nil)
        fixture.runtime.scenePhaseChanged(.active)
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))

        // Once the store is closed nothing can be written.
        await fixture.runtime.lockNow()
        await #expect(throws: RuntimeError.locked) { try await fixture.runtime.recordBackupSaved() }
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))
    }

    @Test("erasing everything removes it, with or without deleting the account", arguments: [false, true])
    func erased(deletingTheAccount: Bool) async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.runtime.recordBackupSaved()
        #expect(fixture.runtime.lastBackupAt != nil)

        #expect(await fixture.runtime.eraseEverything(deleteAccountOnRelay: deletingTheAccount))
        #expect(fixture.runtime.lockState == .needsSetup)
        #expect(fixture.runtime.lastBackupAt == nil)
        let state = fixture.environment.directories.runtime.appendingPathComponent(RuntimeStateFile.fileName)
        #expect(!FileManager.default.fileExists(atPath: state.path))

        // A new account on this phone starts without one, also after a relaunch.
        try await fixture.onboard()
        #expect(fixture.runtime.lastBackupAt == nil)
        await fixture.runtime.lockNow()
        fixture.relaunch()
        try await fixture.runtime.unlock(code: RuntimeFixture.code)
        #expect(fixture.runtime.lastBackupAt == nil)
    }

    @Test("a restore in place of a locked vault starts without one; the next saved backup is kept")
    func restoredInPlace() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        _ = try fixture.acceptedContact()
        let file = try backupFile(of: fixture)
        try await fixture.runtime.recordBackupSaved()
        #expect(fixture.runtime.lastBackupAt != nil)
        await fixture.runtime.lockNow()

        // 3.3 › 7.3: the code was forgotten; the backup replaces the vault.
        try await fixture.runtime.restore(
            backupFile: file, password: Self.password, code: Self.restoredCode, confirmation: Self.restoredCode,
            replacingLockedVault: true)
        #expect(fixture.runtime.lockState == .unlocked)
        // The token rotation made every earlier file of this account
        // unusable, the restored one included: 7.1 says “Hiç alınmadı”.
        #expect(fixture.runtime.lastBackupAt == nil)

        fixture.clock.advance(by: 60)
        let saved = fixture.clock.now()
        try await fixture.runtime.recordBackupSaved()
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))
        await fixture.runtime.lockNow()
        fixture.relaunch()
        try await fixture.runtime.unlock(code: Self.restoredCode)
        #expect(sameMoment(fixture.runtime.lastBackupAt, saved))
    }

    @Test("a restore on a new phone starts without one")
    func restoredOnANewPhone() async throws {
        let old = try RuntimeFixture()
        try await old.onboard()
        _ = try old.acceptedContact()
        let file = try backupFile(of: old)
        try await old.runtime.recordBackupSaved()

        let newPhone = try RuntimeFixture()
        try await newPhone.runtime.restore(
            backupFile: file, password: Self.password, code: Self.restoredCode, confirmation: Self.restoredCode)
        #expect(newPhone.runtime.lockState == .unlocked)
        #expect(newPhone.runtime.lastBackupAt == nil)
        await newPhone.runtime.lockNow()
        newPhone.relaunch()
        try await newPhone.runtime.unlock(code: Self.restoredCode)
        #expect(newPhone.runtime.lastBackupAt == nil)
    }

    @Test("a write that fails changes nothing, on disk or in memory")
    func failedWrite() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let first = Date(timeIntervalSince1970: 1_791_000_000)
        try await fixture.engine.recordBackupSaved(at: first)
        await fixture.engine.stop()
        // After stop the derived key is zeros: never written under it.
        await #expect(throws: SecretKeyMaterialError.wiped) {
            try await fixture.engine.recordBackupSaved(at: first.addingTimeInterval(60))
        }
        #expect(await fixture.engine.lastBackupAt() == first)
        let reopened = try RuntimeStateFile(directory: fixture.directory.directories.runtime, dek: fixture.key)
        #expect(reopened.load().lastBackupAt == first)
    }

    /// TestFlight builds 4–6 wrote the runtime state without the date: it
    /// must load with every setting intact (a decoding failure would reset
    /// them all), and writing the date keeps the rest.
    @Test("a runtime state from earlier builds loads with its settings and gains the date")
    func earlierStateFile() throws {
        let directory = try TemporaryDirectory()
        let runtimeDirectory = directory.directories.runtime
        try FileManager.default.createDirectory(at: runtimeDirectory, withIntermediateDirectories: true)
        let dek = randomKey()
        let key = SecretKeyMaterial(copying: dek)
        // The shape builds 4–6 wrote: no `lastBackupAt` (nor `defaultGhost`, nil).
        let json = Data(
            """
            {"settings":{"displayName":"Selin","presenceSharing":false,"readReceipts":false,\
            "typingIndicators":true,"defaultExpireTimerSeconds":300,"autoDownloadMedia":true},\
            "pendingAccessKeyRotation":true,"noticeSlots":{}}
            """.utf8)
        let stateKey = RuntimeStateFile.deriveKey(dek: dek, purpose: "us2us/runtime-state/v1")
        let sealed = try ItemCipher.seal(json, itemId: RuntimeStateFile.itemId, conversationKey: stateKey)
        try sealed.write(to: runtimeDirectory.appendingPathComponent(RuntimeStateFile.fileName))

        let file = try RuntimeStateFile(directory: runtimeDirectory, dek: key)
        let state = file.load()
        #expect(state.settings.displayName == "Selin")
        #expect(state.settings.presenceSharing == false)
        #expect(state.settings.readReceipts == false)
        #expect(state.settings.defaultExpireTimerSeconds == 300)
        #expect(state.pendingAccessKeyRotation)
        #expect(state.lastBackupAt == nil)

        var updated = state
        updated.lastBackupAt = Date(timeIntervalSince1970: 1_791_000_000)
        try file.save(updated)
        let reopened = try RuntimeStateFile(directory: runtimeDirectory, dek: key)
        #expect(reopened.load() == updated)
    }
}
