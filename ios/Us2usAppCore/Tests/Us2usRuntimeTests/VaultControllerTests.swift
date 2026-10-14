import Foundation
import LocalAuthentication
import Testing
import Us2usKit

@testable import Us2usRuntime

/// A Secure Enclave stand-in: wraps by remembering, unwraps by script.
final class FakeBiometrics: BiometricKeyProtecting, @unchecked Sendable {
    private let state = LockedState<State>(State())

    struct State {
        var availability = BiometricAvailability(kind: .faceID, enrollmentState: Data([1]))
        var keys: [Data: Data] = [:]
        var script: [BiometricError?] = []
        var prompts: [(reason: String, cancel: String)] = []
        var deletions = 0
    }

    var availabilityValue: BiometricAvailability {
        get { state.withLock { $0.availability } }
        set { state.withLock { $0.availability = newValue } }
    }

    /// The next unwrap outcomes: `nil` = success.
    func script(_ outcomes: BiometricError?...) { state.withLock { $0.script.append(contentsOf: outcomes) } }
    var prompts: [(reason: String, cancel: String)] { state.withLock { $0.prompts } }
    var deletions: Int { state.withLock { $0.deletions } }
    var hasKey: Bool { state.withLock { !$0.keys.isEmpty } }

    func availability() -> BiometricAvailability { availabilityValue }

    func wrap(_ secret: Data) throws(BiometricError) -> Data {
        let token = RandomId.make()
        state.withLock {
            $0.keys = [token: secret]
        }
        return token
    }

    func unwrap(_ wrapped: Data, reason: String, cancelTitle: String) async throws(BiometricError) -> Data {
        let outcome: Result<Data, BiometricError> = state.withLock { state in
            state.prompts.append((reason, cancelTitle))
            if !state.script.isEmpty, let error = state.script.removeFirst() { return .failure(error) }
            guard let secret = state.keys[wrapped] else { return .failure(.invalidated) }
            return .success(secret)
        }
        return try outcome.get()
    }

    func deleteKey() {
        state.withLock {
            $0.keys = [:]
            $0.deletions += 1
        }
    }
}

/// Uptime the test controls (LocalVault measures delays with it).
final class ManualUptime: @unchecked Sendable {
    private let value = LockedState<TimeInterval>(1000)
    func advance(_ seconds: TimeInterval) { value.withLock { $0 += seconds } }
    var now: TimeInterval { value.withLock { $0 } }
}

struct VaultFixture {
    let directory: TemporaryDirectory
    let items = InMemorySecureItemStore()
    let biometrics = FakeBiometrics()
    let clock = ManualClock()
    let uptime = ManualUptime()
    let vault: VaultController

    /// - Parameters:
    ///   - hardwareKey: off by default, so the tests behave the same on the
    ///     Simulator and on a device.
    ///   - standIn: a software stand-in for the vault's Secure Enclave key.
    init(hardwareKey: VaultHardwareKeyChoice = .disabled, standIn: (any VaultHardwareKey)? = nil) throws {
        directory = try TemporaryDirectory()
        try directory.directories.prepare()
        let uptime = self.uptime
        var configuration = VaultController.Configuration(iterations: 1000, hardwareKey: hardwareKey)
        configuration.hardwareKeyStandIn = standIn
        vault = VaultController(
            directories: directory.directories, secureItems: items, biometrics: biometrics, clock: clock,
            configuration: configuration, uptime: { uptime.now })
    }

    /// Another controller on the same files, Keychain items and biometrics:
    /// the app started again, e.g. after an update.
    func reopened(standIn: (any VaultHardwareKey)?) -> VaultController {
        let uptime = self.uptime
        var configuration = VaultController.Configuration(iterations: 1000, hardwareKey: .disabled)
        configuration.hardwareKeyStandIn = standIn
        return VaultController(
            directories: directory.directories, secureItems: items, biometrics: biometrics, clock: clock,
            configuration: configuration, uptime: { uptime.now })
    }

    /// Byte 4 of the vault file: its format version (§5.2).
    func formatByte() throws -> UInt8 {
        try Data(contentsOf: directory.directories.vault.appendingPathComponent("vault.brpv"))[4]
    }

    func pass(_ seconds: TimeInterval) {
        clock.advance(by: seconds)
        uptime.advance(seconds)
    }
}

@Suite("Vault lifecycle (§5.2)")
struct VaultControllerTests {
    static let code = "482917"
    static let otherCode = "730415"

    @Test func createThenUnlockGivesTheSameKey() async throws {
        let fixture = try VaultFixture()
        #expect(await fixture.vault.hasVault == false)
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        #expect(created.count == 32)
        #expect(await fixture.vault.hasVault)
        let unlocked = try await fixture.vault.unlock(code: Self.code)
        #expect(unlocked.matches(created))
        // The device secret is a Keychain item of our store.
        #expect(fixture.items.count == 1)
    }

    @Test func creationRulesComeFromUs2usKit() async throws {
        let fixture = try VaultFixture()
        await #expect(throws: VaultError.invalidNewCode(.sequence)) {
            _ = try await fixture.vault.create(code: "123456", confirmation: "123456")
        }
        await #expect(throws: VaultError.invalidNewCode(.mismatch)) {
            _ = try await fixture.vault.create(code: Self.code, confirmation: Self.otherCode)
        }
        await #expect(throws: VaultError.invalidNewCode(.notSixDigits)) {
            _ = try await fixture.vault.create(code: "48291", confirmation: "48291")
        }
        #expect(await fixture.vault.hasVault == false)
    }

    @Test func wrongCodesAreThrottledAfterTwo() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        for _ in 0..<2 {
            await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await fixture.vault.unlock(code: Self.otherCode) }
        }
        let status = await fixture.vault.unlockStatus()
        #expect(status.failures == 2)
        #expect(status.nextAttemptAllowedAt == nil)
        #expect(status.upcoming == UpcomingDelay(attemptsLeft: 1, delay: 60))
        await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await fixture.vault.unlock(code: Self.otherCode) }
        // Now even the right code waits.
        do {
            _ = try await fixture.vault.unlock(code: Self.code)
            Issue.record("unlock during a delay")
        } catch UnlockError.vault(.lockedOut) {
        }
        fixture.pass(61)
        _ = try await fixture.vault.unlock(code: Self.code)
        #expect(await fixture.vault.unlockStatus().failures == 0)
    }

    @Test func malformedInputIsNotCounted() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        await #expect(throws: UnlockError.vault(.malformedCode)) { _ = try await fixture.vault.unlock(code: "12ab") }
        #expect(await fixture.vault.unlockStatus().failures == 0)
    }

    @Test func optionalWipeAfterTenDestroysEverything() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        await fixture.vault.setWipeAfterTenFailures(true)
        var wiped = false
        for _ in 0..<10 {
            fixture.pass(4000)
            do {
                _ = try await fixture.vault.unlock(code: Self.otherCode)
            } catch UnlockError.vault(.wiped) {
                wiped = true
            } catch {}
        }
        #expect(wiped)
        #expect(await fixture.vault.hasVault == false)
        #expect(fixture.items.count == 0)
    }

    @Test func quickUnlockNeedsTheCodeAndThenWorks() async throws {
        let fixture = try VaultFixture()
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        #expect(await fixture.vault.quickUnlockStatus() == .notEnabled)
        await #expect(throws: UnlockError.vault(.wrongCode)) {
            try await fixture.vault.enableQuickUnlock(code: Self.otherCode)
        }
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
        let key = try await fixture.vault.unlockWithBiometrics(reason: "Unlock us2us", cancelTitle: "Use code")
        #expect(key.matches(created))
        #expect(fixture.biometrics.prompts.last?.reason == "Unlock us2us")
        #expect(fixture.biometrics.prompts.last?.cancel == "Use code")
    }

    @Test func cancelDoesNotCountButFiveFailuresRequireTheCode() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        fixture.biometrics.script(.cancelled, .cancelled, .cancelled)
        for _ in 0..<3 {
            await #expect(throws: UnlockError.biometric(.cancelled)) {
                _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
            }
        }
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
        fixture.biometrics.script(.failed, .failed, .failed, .failed, .failed)
        for _ in 0..<4 {
            await #expect(throws: UnlockError.biometric(.failed)) {
                _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
            }
        }
        await #expect(throws: UnlockError.codeRequired(.tooManyFailures)) {
            _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        }
        #expect(await fixture.vault.quickUnlockStatus() == .codeRequired(.tooManyFailures))
        _ = try await fixture.vault.unlock(code: Self.code)
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
    }

    @Test func codeIsRequiredAfterSevenDays() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        fixture.pass(6 * 24 * 3600)
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
        fixture.pass(24 * 3600)
        #expect(await fixture.vault.quickUnlockStatus() == .codeRequired(.sevenDaysWithoutCode))
        await #expect(throws: UnlockError.codeRequired(.sevenDaysWithoutCode)) {
            _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        }
        _ = try await fixture.vault.unlock(code: Self.code)
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
    }

    @Test func enrollmentChangeRequiresTheCodeAndRearms() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        fixture.biometrics.availabilityValue = BiometricAvailability(kind: .faceID, enrollmentState: Data([2]))
        #expect(await fixture.vault.quickUnlockStatus() == .codeRequired(.enrollmentChanged))
        _ = try await fixture.vault.unlock(code: Self.code)
        // Entering the code re-created the key for the new enrollment.
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
        _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
    }

    @Test func invalidatedKeyIsDiscarded() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        fixture.biometrics.script(.invalidated)
        let deletionsBefore = fixture.biometrics.deletions
        await #expect(throws: UnlockError.codeRequired(.enrollmentChanged)) {
            _ = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        }
        #expect(fixture.biometrics.deletions == deletionsBefore + 1)
        #expect(await fixture.vault.quickUnlockStatus() == .codeRequired(.keyProblem))
        _ = try await fixture.vault.unlock(code: Self.code)
        #expect(await fixture.vault.quickUnlockStatus() == .available(.faceID))
    }

    @Test func systemLockoutAndUnavailableHardware() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        fixture.biometrics.availabilityValue = BiometricAvailability(kind: .none, enrollmentState: nil)
        await #expect(throws: UnlockError.codeRequired(.unavailable)) {
            try await fixture.vault.enableQuickUnlock(code: Self.code)
        }
        fixture.biometrics.availabilityValue = BiometricAvailability(kind: .faceID, enrollmentState: Data([1]))
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        fixture.biometrics.availabilityValue = BiometricAvailability(kind: .faceID, enrollmentState: nil, lockedOut: true)
        #expect(await fixture.vault.quickUnlockStatus() == .codeRequired(.systemLockout))
    }

    @Test func changingTheCodeKeepsQuickUnlock() async throws {
        let fixture = try VaultFixture()
        let created = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        try await fixture.vault.changeCode(current: Self.code, new: Self.otherCode, confirmation: Self.otherCode)
        await #expect(throws: UnlockError.vault(.wrongCode)) { _ = try await fixture.vault.unlock(code: Self.code) }
        fixture.pass(1)
        let key = try await fixture.vault.unlock(code: Self.otherCode)
        #expect(key.matches(created))
        let quick = try await fixture.vault.unlockWithBiometrics(reason: "r", cancelTitle: "c")
        #expect(quick.matches(created))
    }

    @Test func destroyingKeysShredsTheVault() async throws {
        let fixture = try VaultFixture()
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        try await fixture.vault.enableQuickUnlock(code: Self.code)
        await fixture.vault.destroyKeys()
        #expect(await fixture.vault.hasVault == false)
        #expect(fixture.items.count == 0)
        #expect(fixture.biometrics.hasKey == false)
        #expect(await fixture.vault.quickUnlockStatus() == .notEnabled)
        await #expect(throws: UnlockError.vault(.notFound)) { _ = try await fixture.vault.unlock(code: Self.code) }
    }

    @Test func leftoversOfAnEarlierInstallationAreRemoved() async throws {
        let fixture = try VaultFixture()
        try fixture.items.write(Data([1, 2, 3]), for: "stale")
        _ = try await fixture.vault.create(code: Self.code, confirmation: Self.code)
        #expect(try fixture.items.read("stale") == nil)
        #expect(fixture.biometrics.deletions >= 1)
    }
}

@Suite("Quick unlock policy")
struct QuickUnlockPolicyTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let available = BiometricAvailability(kind: .faceID, enrollmentState: Data([7]))

    func record(failures: Int = 0) -> QuickUnlockRecord {
        QuickUnlockRecord(wrappedKey: Data([1]), enrollmentState: Data([7]), failures: failures)
    }

    @Test func rules() {
        #expect(QuickUnlockPolicy.status(record: nil, lastCodeEntry: now, availability: available, now: now) == .notEnabled)
        #expect(
            QuickUnlockPolicy.status(record: record(), lastCodeEntry: now, availability: available, now: now)
                == .available(.faceID))
        #expect(
            QuickUnlockPolicy.status(record: record(failures: 5), lastCodeEntry: now, availability: available, now: now)
                == .codeRequired(.tooManyFailures))
        #expect(
            QuickUnlockPolicy.status(record: record(), lastCodeEntry: nil, availability: available, now: now)
                == .codeRequired(.sevenDaysWithoutCode))
        // A clock set backwards does not extend the 7 days.
        #expect(
            QuickUnlockPolicy.status(
                record: record(), lastCodeEntry: now.addingTimeInterval(3600), availability: available, now: now)
                == .codeRequired(.sevenDaysWithoutCode))
        #expect(
            QuickUnlockPolicy.status(
                record: record(), lastCodeEntry: now.addingTimeInterval(-7 * 24 * 3600), availability: available,
                now: now) == .codeRequired(.sevenDaysWithoutCode))
        #expect(
            QuickUnlockPolicy.status(
                record: record(), lastCodeEntry: now,
                availability: BiometricAvailability(kind: .faceID, enrollmentState: Data([8])), now: now)
                == .codeRequired(.enrollmentChanged))
    }

    @Test func laErrorsMapToTheRightOutcome() {
        #expect(SecureEnclaveBiometricProtector.map(.userCancel) == .cancelled)
        #expect(SecureEnclaveBiometricProtector.map(.systemCancel) == .cancelled)
        #expect(SecureEnclaveBiometricProtector.map(.userFallback) == .cancelled)
        #expect(SecureEnclaveBiometricProtector.map(.authenticationFailed) == .failed)
        #expect(SecureEnclaveBiometricProtector.map(.biometryLockout) == .lockout)
        #expect(SecureEnclaveBiometricProtector.map(.biometryNotEnrolled) == .unavailable)
    }

    @Test func simulatorNeverOffersQuickUnlock() {
        // On purpose, even where the Simulator reports a Secure Enclave:
        // see `SecureEnclaveBiometricProtector.offersQuickUnlock`.
        #if targetEnvironment(simulator)
            #expect(SecureEnclaveBiometricProtector().availability().isUsable == false)
        #endif
    }
}
