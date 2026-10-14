import CryptoKit
import Foundation
import Testing

@testable import Us2usKit

/// A software stand-in for the Secure Enclave key of vault format v2: the
/// same construction (``VaultCurvePRF``) with a P-256 key held in memory, plus
/// counters and scripted failures. Tests only: a vault sealed with a software
/// key is no stronger than v1.
final class SoftwareVaultKey: VaultHardwareKey {
    private struct State {
        var key: P256.KeyAgreement.PrivateKey?
        var available = true
        var evaluations = 0
        var creations = 0
        var deletions = 0
        /// Outcomes of the next evaluations, in order: an error to throw, or
        /// `nil` to evaluate normally.
        var evaluationScript: [(any Error)?] = []
        var creationFailure: (any Error)?
    }

    private let state = Locked(State())

    init(available: Bool = true) {
        state.withLock { $0.available = available }
    }

    /// A stand-in that already holds `fixedKey`, for known-answer tests.
    init(fixedKey: P256.KeyAgreement.PrivateKey) {
        state.withLock { $0.key = fixedKey }
    }

    var evaluations: Int { state.withLock { $0.evaluations } }
    var creations: Int { state.withLock { $0.creations } }
    var deletions: Int { state.withLock { $0.deletions } }
    var hasKey: Bool { state.withLock { $0.key != nil } }
    /// Identifies the current key.
    var publicKeyBytes: Data? { state.withLock { $0.key?.publicKey.rawRepresentation } }

    func script(_ outcomes: (any Error)?...) { state.withLock { $0.evaluationScript.append(contentsOf: outcomes) } }
    func failNextCreation(_ error: any Error) { state.withLock { $0.creationFailure = error } }

    var isAvailable: Bool { state.withLock { $0.available } }

    func createKey() throws {
        let failure: (any Error)? = state.withLock { state in
            if let failure = state.creationFailure {
                state.creationFailure = nil
                return failure
            }
            state.key = P256.KeyAgreement.PrivateKey()
            state.creations += 1
            return nil
        }
        if let failure { throw failure }
    }

    func evaluate(codeKey: Data) throws -> Data {
        let (key, failure) = state.withLock { state -> (P256.KeyAgreement.PrivateKey?, (any Error)?) in
            state.evaluations += 1
            let failure = state.evaluationScript.isEmpty ? nil : state.evaluationScript.removeFirst()
            return (state.key, failure)
        }
        if let failure { throw failure }
        guard let key else { throw VaultHardwareKeyError.keyMissing }
        guard #available(iOS 16.0, macOS 13.0, *) else { throw VaultHardwareKeyError.unavailable }
        return try VaultCurvePRF.evaluate(codeKey: codeKey) { point in
            try key.sharedSecretFromKeyAgreement(with: point)
        }
    }

    func deleteKey() throws {
        state.withLock {
            $0.key = nil
            $0.deletions += 1
        }
    }
}

/// A Secure Enclave or Keychain failure that is not about the code.
enum HardwareFailure: Error {
    case interactionNotAllowed
}

/// The vault error `body` threw, or `nil`.
private func vaultError(_ body: () throws -> Void) -> VaultError? {
    do {
        try body()
        return nil
    } catch {
        return error as? VaultError
    }
}

extension VaultError {
    fileprivate var isStorage: Bool {
        if case .storage = self { return true }
        return false
    }
}

@Suite("Vault format v2 (§5.2)")
struct VaultFormatV2Tests {
    let dek = Data.pattern(32, seed: 1)
    let deviceSecret = Data.pattern(32, seed: 2)
    let salt = Data.pattern(16, seed: 3)
    let nonce = Data.pattern(12, seed: 4)

    @Test("layout: version 0x02; KEK = HKDF(codeKey ‖ deviceSecret ‖ hw, salt, \"BRP/vault/v2\")")
    func layout() throws {
        let key = SoftwareVaultKey()
        try key.createKey()
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, hardwareKey: key, salt: salt, iterations: 1000,
            nonce: nonce)
        #expect(key.evaluations == 2, "sealed, then checked against a second evaluation")
        #expect(vault.count == 25 + 12 + 32 + 16)
        #expect(vault.prefix(5) == Data("BRPV".utf8) + [0x02])
        #expect(vault[5..<21] == salt)
        #expect(vault[21..<25] == Data([0x00, 0x00, 0x03, 0xE8]))
        #expect(vault[25..<37] == nonce)
        #expect(VaultFormat.version(of: vault) == .v2)

        let codeKey = PBKDF2.sha256(password: Data("472913".utf8), salt: salt, iterations: 1000)
        let hw = try key.evaluate(codeKey: codeKey)
        #expect(hw.count == 32)
        let kek = HKDFSHA256.derive(
            inputKeyMaterial: codeKey + deviceSecret + hw, salt: salt, info: Data("BRP/vault/v2".utf8))
        #expect(try AES256GCM.open(vault[37...], key: kek, nonce: nonce, associatedData: vault.prefix(25)) == dek)
        #expect(try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret, hardwareKey: key) == dek)
    }

    /// Values computed independently of this code (Python `cryptography`:
    /// `from_encoded_point` for H2C, ECDH, HKDF, AES-GCM).
    @Test("known answer: H2C takes counter 2, hw and the whole v2 file match")
    func knownAnswer() throws {
        let scalar = Data(hex: "26456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e7")
        #expect(scalar == Data.pattern(32, seed: 5))
        let key = SoftwareVaultKey(fixedKey: try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar))
        let codeKey = PBKDF2.sha256(password: Data("472913".utf8), salt: salt, iterations: 1000)
        #expect(codeKey == Data(hex: "2ffd0ad759b8ac861ced16a3194bd296677ea38dd2cd29daf96cdb2b438c8435"))
        if #available(iOS 16.0, macOS 13.0, *) {
            #expect(try VaultCurvePRF.hashToCurve(codeKey).counter == 2)
        }
        #expect(
            try key.evaluate(codeKey: codeKey)
                == Data(hex: "da2f8bf6bc2ae5b4d571980accd4b3a28396f9a8d5ca7a39d2720917dc7e25e3"))
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, hardwareKey: key, salt: salt, iterations: 1000,
            nonce: nonce)
        #expect(
            vault
                == Data(
                    hex: "42525056021837567594b3d2f1102f4e6d8cabcae9000003e81f3e5d7c9bbad9f81736557470bb7d7388a0b8"
                        + "ed9af37af2277bd90d965bcf088465425608b01dd0e1abdd3df487720cfc40d3898d81a37998a5f13c"))
        #expect(try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret, hardwareKey: key) == dek)
    }

    @Test("v1 is unchanged and needs no hardware key")
    func v1Unchanged() throws {
        let key = SoftwareVaultKey()
        try key.createKey()
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, salt: salt, iterations: 1000, nonce: nonce)
        #expect(vault.prefix(5) == Data("BRPV".utf8) + [0x01])
        #expect(VaultFormat.version(of: vault) == .v1)
        #expect(try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret, hardwareKey: key) == dek)
        #expect(key.evaluations == 0)
    }

    @Test("a v2 vault needs the code, this device secret and this phone's hardware key")
    func failures() throws {
        let key = SoftwareVaultKey()
        try key.createKey()
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, hardwareKey: key, salt: salt, iterations: 1000,
            nonce: nonce)
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472914", deviceSecret: deviceSecret, hardwareKey: key)
        }
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret.flippingBit(at: 0), hardwareKey: key)
        }
        // Another phone: the right code and device secret, another hardware key.
        let other = SoftwareVaultKey()
        try other.createKey()
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret, hardwareKey: other)
        }
        // The file and the device secret extracted, no hardware: not even a guess.
        #expect(throws: VaultError.deviceSecretMissing) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret)
        }
        // Rewriting the version byte to read it as v1 does not help: the header is authenticated.
        var downgraded = vault
        downgraded[4] = 0x01
        #expect(throws: VaultError.wrongCode) {
            try VaultFormat.open(downgraded, code: "472913", deviceSecret: deviceSecret, hardwareKey: key)
        }
        var unknown = vault
        unknown[4] = 0x03
        #expect(throws: VaultError.corrupted) {
            try VaultFormat.open(unknown, code: "472913", deviceSecret: deviceSecret, hardwareKey: key)
        }
        #expect(VaultFormat.version(of: unknown) == nil)
    }

    @Test("hardware failures are never a wrong code")
    func hardwareErrors() throws {
        let key = SoftwareVaultKey()
        try key.createKey()
        let vault = try VaultFormat.seal(
            dek: dek, code: "472913", deviceSecret: deviceSecret, hardwareKey: key, salt: salt, iterations: 1000,
            nonce: nonce)
        key.script(HardwareFailure.interactionNotAllowed, VaultHardwareKeyError.noCurvePoint)
        for code in ["472913", "472914"] {
            let error = vaultError {
                _ = try VaultFormat.open(vault, code: code, deviceSecret: deviceSecret, hardwareKey: key)
            }
            #expect(error?.isStorage == true)
        }
        try key.deleteKey()
        #expect(throws: VaultError.deviceSecretMissing) {
            try VaultFormat.open(vault, code: "472913", deviceSecret: deviceSecret, hardwareKey: key)
        }
    }

    @Test("a vault is never sealed under a hardware key whose two evaluations disagree")
    func sealChecksTheHardware() throws {
        let key = SoftwareVaultKey()
        try key.createKey()
        key.script(nil, HardwareFailure.interactionNotAllowed)
        let error = vaultError {
            _ = try VaultFormat.seal(
                dek: dek, code: "472913", deviceSecret: deviceSecret, hardwareKey: key, salt: salt, iterations: 1000,
                nonce: nonce)
        }
        #expect(error?.isStorage == true)
    }
}

@Suite("Vault hash-to-curve PRF (§5.2)")
struct VaultCurvePRFTests {
    /// `"BRP/vault/h2c/v2" ‖ ctr ‖ codeKey`, the input hashed for counter `ctr`.
    static func h2cInput(_ counter: Int, _ codeKey: Data) -> Data {
        var input = Data("BRP/vault/h2c/v2".utf8)
        input.append(UInt8(counter))
        input.append(codeKey)
        return input
    }

    @Test("H2C: the first counter whose x = SHA-256(label ‖ ctr ‖ codeKey) is a point with even y")
    func hashToCurve() throws {
        guard #available(iOS 16.0, macOS 13.0, *) else { return }
        // Counters for Data.pattern(32, seed: 0…31), computed independently.
        let expected = [2, 3, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 2, 1, 0, 4, 1, 0, 0, 0, 0, 3, 1, 1, 0, 0, 3, 0, 1, 0]
        let counters = try (UInt8(0)..<32).map { try VaultCurvePRF.hashToCurve(Data.pattern(32, seed: $0)).counter }
        #expect(counters == expected)
        for seed in UInt8(0)..<32 {
            let codeKey = Data.pattern(32, seed: seed)
            let first = try VaultCurvePRF.hashToCurve(codeKey)
            let again = try VaultCurvePRF.hashToCurve(codeKey)
            #expect(first.counter == again.counter)
            #expect(first.point.rawRepresentation == again.point.rawRepresentation)
            let x = Data(SHA256.hash(data: Self.h2cInput(first.counter, codeKey)))
            #expect(first.point.compressedRepresentation == Data([0x02]) + x)
            for counter in 0..<first.counter {
                let earlier = Data(SHA256.hash(data: Self.h2cInput(counter, codeKey)))
                let valid =
                    earlier.lexicographicallyPrecedes(VaultCurvePRF.fieldPrime)
                    && (try? P256.KeyAgreement.PublicKey(compressedRepresentation: Data([0x02]) + earlier)) != nil
                #expect(!valid, "counter \(counter) should have been taken")
            }
        }
    }

    @Test("10,000 random code keys all map within 64 counters, about two tries each on average")
    func distribution() throws {
        guard #available(iOS 16.0, macOS 13.0, *) else { return }
        var total = 0
        var largest = 0
        for _ in 0..<10_000 {
            let counter = try VaultCurvePRF.hashToCurve(SecureRandom.bytes(32)).counter
            total += counter
            largest = max(largest, counter)
        }
        let meanTries = Double(total) / 10_000 + 1
        #expect(meanTries > 1.85 && meanTries < 2.15, "mean tries \(meanTries)")
        #expect(largest < VaultCurvePRF.maxCounter)
    }

    @Test("no point within the counter limit fails closed")
    func failsClosed() throws {
        guard #available(iOS 16.0, macOS 13.0, *) else { return }
        #expect(throws: VaultHardwareKeyError.noCurvePoint) {
            try VaultCurvePRF.hashToCurve(Data.pattern(32), maxCounter: 0)
        }
    }

    @Test("hw is 32 bytes, the same for the same key and code key, different otherwise")
    func prf() throws {
        let a = SoftwareVaultKey()
        try a.createKey()
        let b = SoftwareVaultKey()
        try b.createKey()
        let first = Data.pattern(32, seed: 1)
        let second = Data.pattern(32, seed: 2)
        #expect(try a.evaluate(codeKey: first).count == 32)
        #expect(try a.evaluate(codeKey: first) == a.evaluate(codeKey: first))
        #expect(try a.evaluate(codeKey: first) != a.evaluate(codeKey: second))
        #expect(try a.evaluate(codeKey: first) != b.evaluate(codeKey: first))
    }
}

@Suite("Local vault v2: the hardware step and the move from v1 (§5.2)")
struct LocalVaultV2Tests {
    static let code = "472913"
    static let wrong = "000001"

    let directory: TemporaryDirectory
    let secrets = InMemoryDeviceSecretProvider()
    let key = SoftwareVaultKey()
    let clock = LocalVaultTests.Clock()

    init() throws {
        directory = try TemporaryDirectory()
    }

    private func vault(hardwareKey: (any VaultHardwareKey)?, wipeAfter: Int? = nil) -> LocalVault {
        let clock = self.clock
        return LocalVault(
            directory: directory.url, deviceSecrets: secrets, hardwareKey: hardwareKey,
            configuration: .init(iterations: 1000, wipeAfterFailures: wipeAfter), now: { clock.now },
            uptime: { clock.uptime })
    }

    private func vaultFile() throws -> Data {
        try Data(contentsOf: directory.file("vault.brpv"))
    }

    @Test("new vaults are v2, and every attempt, right or wrong, needs one hardware evaluation")
    func everyGuessNeedsTheHardware() throws {
        let vault = vault(hardwareKey: key)
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(vault.formatVersion == .v2)
        #expect(try vaultFile()[4] == 0x02)
        #expect(key.creations == 1)
        let afterCreate = key.evaluations
        #expect(afterCreate == 2)

        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        #expect(key.evaluations == afterCreate + 1)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: "000002") }
        #expect(key.evaluations == afterCreate + 2)
        #expect(try vault.unlock(code: Self.code) == dek)
        #expect(key.evaluations == afterCreate + 3)
        #expect(key.creations == 1, "unlocking a v2 vault never replaces its key")

        // Attempts refused before the check reach neither the KDF nor the hardware.
        for _ in 1...3 { _ = try? vault.unlock(code: Self.wrong) }
        let counted = key.evaluations
        #expect(counted == afterCreate + 6)
        #expect(throws: VaultError.lockedOut(until: clock.now.addingTimeInterval(60))) {
            try vault.unlock(code: Self.code)
        }
        #expect(throws: VaultError.malformedCode) { try vault.unlock(code: "47291") }
        #expect(key.evaluations == counted)
    }

    @Test("a v1 vault moves to v2 at the next successful unlock; a wrong code changes nothing")
    func migration() throws {
        let dek = try vault(hardwareKey: nil).create(code: Self.code, confirmation: Self.code)
        let v1File = try vaultFile()
        #expect(VaultFormat.version(of: v1File) == .v1)

        let upgraded = vault(hardwareKey: key)
        #expect(upgraded.formatVersion == .v1)
        #expect(throws: VaultError.wrongCode) { try upgraded.unlock(code: Self.wrong) }
        #expect(try vaultFile() == v1File)
        #expect(key.creations == 0)
        #expect(key.evaluations == 0, "a v1 vault never asks the hardware")

        #expect(try upgraded.unlock(code: Self.code) == dek)
        let v2File = try vaultFile()
        #expect(v2File[4] == 0x02)
        #expect(v2File[5..<21] != v1File[5..<21], "sealed with a fresh salt")
        #expect(upgraded.formatVersion == .v2)
        #expect(key.creations == 1)
        #expect(upgraded.status().failures == 0)

        // The same DEK, so the database, key slots and the biometric wrap stay valid.
        #expect(try upgraded.unlock(code: Self.code) == dek)
        #expect(throws: VaultError.wrongCode) { try upgraded.unlock(code: Self.wrong) }
        #expect(key.creations == 1)

        // A reader without the hardware key cannot open it, and that is not a guess.
        let withoutHardware = vault(hardwareKey: nil)
        #expect(throws: VaultError.deviceSecretMissing) { try withoutHardware.unlock(code: Self.code) }
        #expect(withoutHardware.status().failures == 1)
    }

    @Test("a migration that fails after the key was created keeps v1 byte for byte and is retried")
    func failedMigrationKeepsV1() throws {
        let dek = try vault(hardwareKey: nil).create(code: Self.code, confirmation: Self.code)
        let v1File = try vaultFile()
        let upgraded = vault(hardwareKey: key)

        // The check evaluation fails after the new key was created: nothing is written.
        key.script(nil, HardwareFailure.interactionNotAllowed)
        #expect(try upgraded.unlock(code: Self.code) == dek)
        #expect(try vaultFile() == v1File)
        #expect(key.creations == 1)
        #expect(!key.hasKey, "the unused key is deleted again")

        // Creating the key fails: the same.
        key.failNextCreation(HardwareFailure.interactionNotAllowed)
        #expect(try upgraded.unlock(code: Self.code) == dek)
        #expect(try vaultFile() == v1File)
        #expect(upgraded.status().failures == 0, "the unlock itself succeeded")

        // The next unlock moves it.
        #expect(try upgraded.unlock(code: Self.code) == dek)
        #expect(upgraded.formatVersion == .v2)
        #expect(try upgraded.unlock(code: Self.code) == dek)
    }

    @Test("a key left behind by an interrupted migration is replaced")
    func orphanKeyIsReplaced() throws {
        let dek = try vault(hardwareKey: nil).create(code: Self.code, confirmation: Self.code)
        try key.createKey()  // the app stopped before the v2 file was written
        let orphan = key.publicKeyBytes
        let upgraded = vault(hardwareKey: key)
        #expect(try upgraded.unlock(code: Self.code) == dek)
        #expect(upgraded.formatVersion == .v2)
        #expect(key.creations == 2)
        #expect(key.publicKeyBytes != orphan)
        #expect(try upgraded.unlock(code: Self.code) == dek)
    }

    @Test("without the hardware (the Simulator) vaults stay v1")
    func noHardware() throws {
        let missing = SoftwareVaultKey(available: false)
        let vault = vault(hardwareKey: missing)
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(vault.formatVersion == .v1)
        #expect(try vault.unlock(code: Self.code) == dek)
        #expect(vault.formatVersion == .v1)
        #expect(missing.creations == 0)
        #expect(missing.evaluations == 0)
    }

    @Test("if the hardware key cannot be made at creation, the vault is v1 and moves to v2 later")
    func creationFallsBackToV1() throws {
        key.failNextCreation(HardwareFailure.interactionNotAllowed)
        let vault = vault(hardwareKey: key)
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(vault.formatVersion == .v1)
        #expect(!key.hasKey)
        #expect(try vault.unlock(code: Self.code) == dek)
        #expect(vault.formatVersion == .v2)
        #expect(try vault.unlock(code: Self.code) == dek)
    }

    @Test("a missing or failing hardware key never counts as a wrong code and never wipes")
    func hardwareErrorsAreNotGuesses() throws {
        let vault = vault(hardwareKey: key, wipeAfter: 3)
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: "000002") }
        #expect(vault.status().failures == 2)

        // Transient (e.g. errSecInteractionNotAllowed).
        key.script(HardwareFailure.interactionNotAllowed, HardwareFailure.interactionNotAllowed)
        #expect(vaultError { _ = try vault.unlock(code: Self.code) }?.isStorage == true)
        #expect(vaultError { _ = try vault.unlock(code: Self.wrong) }?.isStorage == true)
        #expect(vault.status().failures == 2)
        #expect(vault.exists)
        #expect(try vault.unlock(code: Self.code) == dek)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.wrong) }
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: "000002") }

        // Gone for good (the Keychain item is lost).
        try key.deleteKey()
        for code in [Self.code, Self.wrong, "000003", "000004"] {
            #expect(throws: VaultError.deviceSecretMissing) { try vault.unlock(code: code) }
        }
        #expect(vault.status().failures == 2)
        #expect(vault.exists)
        #expect(try secrets.loadDeviceSecret() != nil)
    }

    @Test("changing the code keeps v2 and the same hardware key")
    func changeCode() throws {
        let vault = vault(hardwareKey: key)
        let dek = try vault.create(code: Self.code, confirmation: Self.code)
        let publicKey = key.publicKeyBytes
        try vault.changeCode(current: Self.code, new: "830561", confirmation: "830561")
        #expect(vault.formatVersion == .v2)
        #expect(key.creations == 1)
        #expect(key.publicKeyBytes == publicKey)
        #expect(throws: VaultError.wrongCode) { try vault.unlock(code: Self.code) }
        #expect(try vault.unlock(code: "830561") == dek)
    }

    @Test("changing the code of a v1 vault moves it to v2 first")
    func changeCodeOfV1() throws {
        let dek = try vault(hardwareKey: nil).create(code: Self.code, confirmation: Self.code)
        let upgraded = vault(hardwareKey: key)
        try upgraded.changeCode(current: Self.code, new: "830561", confirmation: "830561")
        #expect(upgraded.formatVersion == .v2)
        #expect(key.creations == 1)
        #expect(throws: VaultError.wrongCode) { try upgraded.unlock(code: Self.code) }
        #expect(try upgraded.unlock(code: "830561") == dek)
    }

    @Test("erase everything and the optional wipe delete the hardware key too")
    func destroy() throws {
        let vault = vault(hardwareKey: key)
        _ = try vault.create(code: Self.code, confirmation: Self.code)
        try vault.destroy()
        #expect(!key.hasKey)
        #expect(!vault.exists)
        #expect(try secrets.loadDeviceSecret() == nil)

        let wiping = self.vault(hardwareKey: key, wipeAfter: 2)
        _ = try wiping.create(code: Self.code, confirmation: Self.code)
        #expect(key.hasKey)
        #expect(throws: VaultError.wrongCode) { try wiping.unlock(code: Self.wrong) }
        #expect(throws: VaultError.wiped) { try wiping.unlock(code: "000002") }
        #expect(!key.hasKey)
        #expect(!wiping.exists)
    }
}
