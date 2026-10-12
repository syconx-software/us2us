import CryptoKit
import Foundation
import Testing
import Us2usKit

@testable import Us2usRuntime

/// A software stand-in for the vault's Secure Enclave key (format v2): the
/// same construction (``VaultCurvePRF``) with a P-256 key in memory. Tests
/// only; a vault sealed with a software key is no stronger than v1.
final class SoftwareVaultKey: VaultHardwareKey {
    private struct State {
        var key: P256.KeyAgreement.PrivateKey?
        var evaluations = 0
        var creations = 0
    }

    private let state = LockedState<State>(State())

    var evaluations: Int { state.withLock { $0.evaluations } }
    var creations: Int { state.withLock { $0.creations } }
    var hasKey: Bool { state.withLock { $0.key != nil } }

    var isAvailable: Bool { true }

    func createKey() throws {
        state.withLock {
            $0.key = P256.KeyAgreement.PrivateKey()
            $0.creations += 1
        }
    }

    func evaluate(codeKey: Data) throws -> Data {
        let key = state.withLock { state -> P256.KeyAgreement.PrivateKey? in
            state.evaluations += 1
            return state.key
        }
        guard let key else { throw VaultHardwareKeyError.keyMissing }
        return try VaultCurvePRF.evaluate(codeKey: codeKey) { point in
            try key.sharedSecretFromKeyAgreement(with: point)
        }
    }

    func deleteKey() throws {
        state.withLock { $0.key = nil }
    }
}

@Suite("Vault format v2 in the controller (§5.2)")
struct VaultHardwareStepTests {
    static let code = "482917"
    static let otherCode = "730415"

    @Test func newVaultsNeedTheHardwareKeyForEveryAttempt() async throws {
        let key = SoftwareVaultKey()
        let fixture = try VaultFixture(standIn: key)
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        #expect(try fixture.formatByte() == 0x02)
        let afterCreate = key.evaluations
        await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await fixture.vault.unlock(code: Self.otherCode) }
        #expect(key.evaluations == afterCreate + 1)
        let unlocked = try await fixture.vault.unlock(code: Self.code)
        #expect(unlocked.matches(created))
        #expect(key.evaluations == afterCreate + 2)
    }

    @Test func aV1VaultMovesToV2AndQuickUnlockKeepsWorking() async throws {
        let fixture = try VaultFixture()
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        #expect(try fixture.formatByte() == 0x01)

        // The updated app opens the same files with the hardware step.
        let key = SoftwareVaultKey()
        let updated = fixture.reopened(standIn: key)
        await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await updated.unlock(code: Self.otherCode) }
        #expect(try fixture.formatByte() == 0x01)
        let unlocked = try await updated.unlock(code: Self.code)
        #expect(unlocked.matches(created))
        #expect(try fixture.formatByte() == 0x02)
        #expect(key.creations == 1)

        // The DEK did not change: Face ID still opens the same data, and so does the code.
        let quick = try await updated.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        #expect(quick.matches(created))
        let again = try await updated.unlock(code: Self.code)
        #expect(again.matches(created))
        #expect(key.creations == 1)
    }

    @Test func aMissingHardwareKeyIsNeverCountedAndNeverWipes() async throws {
        let key = SoftwareVaultKey()
        let fixture = try VaultFixture(standIn: key)
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        await fixture.vault.setWipeAfterTenFailures(true)
        for _ in 0..<9 {
            fixture.pass(4000)
            await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await fixture.vault.unlock(code: Self.otherCode) }
        }
        #expect(await fixture.vault.unlockStatus().failures == 9)
        fixture.pass(4000)
        try key.deleteKey()
        for code in [Self.code, Self.otherCode, "590326"] {
            await #expect(throws: UnlockError.vault(.deviceSecretMissing)) { _ = try await fixture.vault.unlock(code: code) }
        }
        #expect(await fixture.vault.unlockStatus().failures == 9)
        #expect(await fixture.vault.hasVault)
    }

    @Test func destroyingKeysDeletesTheHardwareKey() async throws {
        let key = SoftwareVaultKey()
        let fixture = try VaultFixture(standIn: key)
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        #expect(key.hasKey)
        await fixture.vault.destroyKeys()
        #expect(!key.hasKey)
        #expect(fixture.items.count == 0)
        #expect(await fixture.vault.hasVault == false)
    }

    @Test func theSecureEnclaveKeyIsAnItemOfTheControllersStore() async throws {
        let fixture = try VaultFixture(hardwareKey: .secureEnclave)
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        if SecureEnclave.isAvailable {
            #expect(try fixture.formatByte() == 0x02)
            #expect(fixture.items.count == 2)
            #expect(try fixture.items.read(SecureEnclaveVaultKey.itemName) != nil)
        } else {
            // No Secure Enclave (some Simulators), so the vault stays v1.
            #expect(try fixture.formatByte() == 0x01)
            #expect(fixture.items.count == 1)
        }
        let unlocked = try await fixture.vault.unlock(code: Self.code)
        #expect(unlocked.matches(created))
        await fixture.vault.destroyKeys()
        #expect(fixture.items.count == 0)
    }
}

@Suite("Secure Enclave vault key (§5.2)")
struct SecureEnclaveVaultKeyTests {
    @Test func withoutAKeyThereIsNoEvaluation() throws {
        let key = SecureEnclaveVaultKey(store: InMemorySecureItemStore())
        #expect(throws: VaultHardwareKeyError.keyMissing) { try key.evaluate(codeKey: Data(repeating: 1, count: 32)) }
        try key.deleteKey()
    }

    /// Runs where there is no Secure Enclave only (some Simulators have one).
    @Test(.enabled(if: !SecureEnclave.isAvailable))
    func withoutASecureEnclaveNoKeyIsCreated() throws {
        let store = InMemorySecureItemStore()
        let key = SecureEnclaveVaultKey(store: store)
        #expect(!key.isAvailable)
        #expect(throws: VaultHardwareKeyError.unavailable) { try key.createKey() }
        #expect(try store.read(SecureEnclaveVaultKey.itemName) == nil)
    }

    /// Runs on a device only.
    @Test(.enabled(if: SecureEnclave.isAvailable))
    func aDeviceKeyIsDeterministicAndBoundToItsBlob() throws {
        let store = InMemorySecureItemStore()
        let key = SecureEnclaveVaultKey(store: store)
        try key.createKey()
        let blob = try #require(try store.read(SecureEnclaveVaultKey.itemName))
        let first = Data(repeating: 1, count: 32)
        let second = Data(repeating: 2, count: 32)
        let hw = try key.evaluate(codeKey: first)
        #expect(hw.count == 32)
        #expect(try key.evaluate(codeKey: first) == hw)
        #expect(try key.evaluate(codeKey: second) != hw)

        try key.createKey()
        #expect(try store.read(SecureEnclaveVaultKey.itemName) != blob)
        #expect(try key.evaluate(codeKey: first) != hw, "a new key gives a new function")
        try key.deleteKey()
        #expect(throws: VaultHardwareKeyError.keyMissing) { try key.evaluate(codeKey: first) }
    }
}
