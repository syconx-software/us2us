import Foundation
import Us2usKit

/// Why an unlock attempt did not open the vault.
public enum UnlockError: Error, Equatable, Sendable {
    /// From the code path: wrong code, malformed input, lockout (`lockedOut(until:)`),
    /// the optional wipe (`wiped`), a missing device secret, …
    case vault(VaultError)
    /// From the biometric path; `.cancelled` and `.failed` leave the code
    /// keypad as the fallback.
    case biometric(BiometricError)
    /// Biometric unlock is not possible right now; ask for the code.
    case codeRequired(CodeRequiredReason)
    /// Quick unlock was never turned on.
    case quickUnlockNotEnabled
    /// Another unlock is already in progress.
    case busy
}

/// The vault lifecycle of PROTOCOL.md §5.2 with these product rules: a six-digit
/// code entered twice at creation, attempts recorded as failures *before* the
/// code is checked (Us2usKit `LocalVault`), increasing delays after two wrong
/// codes, an optional wipe after 10, biometric quick unlock through a Secure
/// Enclave key bound to the current biometric enrollment, re-wrapping on a code
/// change, and crypto-shredding for "forgot code" / "erase everything".
/// Vaults are format v2 where the phone has a Secure Enclave: every code
/// attempt also needs a Secure Enclave key of this phone
/// (``SecureEnclaveVaultKey``), and v1 vaults move to v2 at their next unlock.
///
/// The controller never keeps the DEK: every successful unlock returns a fresh
/// ``SecretKeyMaterial`` that the caller wipes when the app locks.
public actor VaultController {
    public struct Configuration: Sendable {
        public var iterations: Int
        /// The per-guess hardware step of vault format v2 (§5.2).
        public var hardwareKey: VaultHardwareKeyChoice
        /// A software stand-in for the Secure Enclave, for this package's
        /// tests only (internal on purpose: a vault sealed with a software
        /// key is no stronger than v1). Takes precedence over `hardwareKey`.
        var hardwareKeyStandIn: (any VaultHardwareKey)?

        public init(
            iterations: Int = VaultFormat.defaultIterations, hardwareKey: VaultHardwareKeyChoice = .secureEnclave
        ) {
            self.iterations = iterations
            self.hardwareKey = hardwareKey
        }
    }

    private let directories: AppDirectories
    private let secureItems: any SecureItemStore
    private let biometrics: any BiometricKeyProtecting
    private let clock: any RuntimeClock
    private let uptime: @Sendable () -> TimeInterval
    private let configuration: Configuration
    private var sidecar: VaultSidecar
    private var unlockInFlight = false

    public init(
        directories: AppDirectories, secureItems: any SecureItemStore, biometrics: any BiometricKeyProtecting,
        clock: any RuntimeClock = SystemClock(), configuration: Configuration = Configuration(),
        uptime: @escaping @Sendable () -> TimeInterval = { LocalVault.systemUptime() }
    ) {
        self.directories = directories
        self.secureItems = secureItems
        self.biometrics = biometrics
        self.clock = clock
        self.uptime = uptime
        self.configuration = configuration
        self.sidecar = Self.loadSidecar(at: directories.vault.appendingPathComponent(Self.sidecarName))
    }

    static let sidecarName = "vault-settings.json"
    private var sidecarURL: URL { directories.vault.appendingPathComponent(Self.sidecarName) }

    /// The hardware key the vault uses, `nil` when v2 is turned off.
    private var hardwareKey: (any VaultHardwareKey)? {
        if let standIn = configuration.hardwareKeyStandIn { return standIn }
        switch configuration.hardwareKey {
        case .secureEnclave: return SecureEnclaveVaultKey(store: secureItems)
        case .disabled: return nil
        }
    }

    private func vault() -> LocalVault {
        let clock = self.clock
        return LocalVault(
            directory: directories.vault, deviceSecrets: DeviceSecretItem(store: secureItems),
            hardwareKey: hardwareKey,
            configuration: LocalVault.Configuration(
                iterations: configuration.iterations,
                wipeAfterFailures: sidecar.wipeAfterTenFailures ? LocalVault.Configuration.wipeOptionFailures : nil),
            now: { clock.now() }, uptime: uptime)
    }

    public var hasVault: Bool { vault().exists }

    // MARK: Create

    /// Creates the vault for a new installation (the code entered twice) and
    /// returns the fresh DEK. Leftovers of an earlier installation (Keychain
    /// items survive deleting the app) are removed first.
    public func create(code: String, confirmation: String) throws(VaultError) -> SecretKeyMaterial {
        let vault = vault()
        guard !vault.exists else { throw .alreadyExists }
        removeLeftovers()
        var dek = try vault.create(code: code, confirmation: confirmation)
        defer { SecretKeyMaterial.zeroize(&dek) }
        sidecar = VaultSidecar(lastCodeEntry: clock.now())
        saveSidecar()
        if vault.formatVersion == .v2 {
            RuntimeLog.vault.info("vault created, format 2")
        } else {
            RuntimeLog.vault.info("vault created, format 1")
            logHardwareStepMissing()
        }
        return SecretKeyMaterial(copying: dek)
    }

    /// A device with a Secure Enclave whose vault is still v1: creating or
    /// using the hardware key failed; the next unlock tries again (§5.2).
    private func logHardwareStepMissing() {
        guard hardwareKey?.isAvailable == true else { return }
        RuntimeLog.vault.notice("hardware key failed, vault stays on format 1")
    }

    // MARK: Code unlock

    /// Wrong codes so far, the current wait and what further wrong codes cost.
    public func unlockStatus() -> UnlockStatus {
        vault().status()
    }

    /// Opens the vault with the code. Throws `UnlockError.vault(.wiped)` when
    /// the optional wipe fired: the caller must then erase everything else.
    /// A v1 vault moves to format v2 here where the phone has a Secure
    /// Enclave (§5.2); the DEK, and so the biometric wrap, stays the same.
    public func unlock(code: String) throws(UnlockError) -> SecretKeyMaterial {
        guard !unlockInFlight else { throw .busy }
        unlockInFlight = true
        defer { unlockInFlight = false }
        let vault = vault()
        let formatBefore = vault.formatVersion
        var dek: Data
        do throws(VaultError) {
            dek = try vault.unlock(code: code)
        } catch {
            if error == .wiped { removeLeftovers() }
            RuntimeLog.vault.notice("code unlock refused")
            throw .vault(error)
        }
        defer { SecretKeyMaterial.zeroize(&dek) }
        if formatBefore == .v1 {
            if vault.formatVersion == .v2 {
                RuntimeLog.vault.info("vault moved to format 2")
            } else {
                logHardwareStepMissing()
            }
        }
        sidecar.lastCodeEntry = clock.now()
        sidecar.quickUnlock?.failures = 0
        refreshQuickUnlockWrap(dek: dek)
        saveSidecar()
        return SecretKeyMaterial(copying: dek)
    }

    /// Re-creates the biometric wrap after a code unlock when the user wants
    /// quick unlock but the wrap is missing, broken, or bound to an old
    /// enrollment (§5.2: "the code is still required … after the enrolled
    /// biometrics change"; entering it re-arms quick unlock).
    private func refreshQuickUnlockWrap(dek: Data) {
        guard sidecar.quickUnlockWanted else { return }
        let availability = biometrics.availability()
        guard availability.isUsable else { return }
        if let record = sidecar.quickUnlock, !record.needsRewrap, record.enrollmentState == availability.enrollmentState {
            return
        }
        do {
            let wrapped = try biometrics.wrap(dek)
            sidecar.quickUnlock = QuickUnlockRecord(wrappedKey: wrapped, enrollmentState: availability.enrollmentState)
        } catch {
            sidecar.quickUnlock = nil
        }
    }

    // MARK: Biometric quick unlock

    public func quickUnlockStatus() -> QuickUnlockStatus {
        if sidecar.quickUnlockWanted, sidecar.quickUnlock == nil { return .codeRequired(.keyProblem) }
        return QuickUnlockPolicy.status(
            record: sidecar.quickUnlock, lastCodeEntry: sidecar.lastCodeEntry,
            availability: biometrics.availability(), now: clock.now())
    }

    /// Turns quick unlock on. The code is required to set it up (§5.2), and
    /// entering it is a normal, counted unlock attempt.
    public func enableQuickUnlock(code: String) throws(UnlockError) {
        let availability = biometrics.availability()
        guard availability.isUsable else { throw .codeRequired(.unavailable) }
        let key = try unlock(code: code)
        defer { key.wipe() }
        do {
            let wrapped = try key.withData { dek in try biometrics.wrap(dek) }
            sidecar.quickUnlock = QuickUnlockRecord(wrappedKey: wrapped, enrollmentState: availability.enrollmentState)
            sidecar.quickUnlockWanted = true
            saveSidecar()
        } catch let error as BiometricError {
            throw .biometric(error)
        } catch {
            throw .biometric(.keyFailure)
        }
    }

    public func disableQuickUnlock() {
        biometrics.deleteKey()
        sidecar.quickUnlock = nil
        sidecar.quickUnlockWanted = false
        saveSidecar()
    }

    /// Shows the biometric prompt and returns the DEK. A failed match is
    /// counted *before* the prompt appears (killing the app during the prompt
    /// gains nothing) and taken back on success or cancel; after 5 the code
    /// is required.
    public func unlockWithBiometrics(reason: String, cancelTitle: String) async throws(UnlockError) -> SecretKeyMaterial {
        switch quickUnlockStatus() {
        case .notEnabled: throw .quickUnlockNotEnabled
        case .codeRequired(let reason): throw .codeRequired(reason)
        case .available: break
        }
        guard !unlockInFlight, var record = sidecar.quickUnlock else { throw .busy }
        unlockInFlight = true
        defer { unlockInFlight = false }
        let previousFailures = record.failures
        record.failures += 1
        sidecar.quickUnlock = record
        saveSidecar()

        do throws(BiometricError) {
            var dek = try await biometrics.unwrap(record.wrappedKey, reason: reason, cancelTitle: cancelTitle)
            defer { SecretKeyMaterial.zeroize(&dek) }
            guard dek.count == 32 else { throw BiometricError.keyFailure }
            sidecar.quickUnlock?.failures = 0
            saveSidecar()
            return SecretKeyMaterial(copying: dek)
        } catch {
            switch error {
            case .failed:
                break  // counted
            case .cancelled, .lockout, .unavailable:
                sidecar.quickUnlock?.failures = previousFailures
            case .invalidated:
                biometrics.deleteKey()
                sidecar.quickUnlock = nil
            case .keyFailure:
                sidecar.quickUnlock?.failures = previousFailures
                sidecar.quickUnlock?.needsRewrap = true
            }
            saveSidecar()
            RuntimeLog.vault.notice("biometric unlock refused")
            switch error {
            case .invalidated: throw .codeRequired(.enrollmentChanged)
            case .keyFailure: throw .codeRequired(.keyProblem)
            case .lockout: throw .codeRequired(.systemLockout)
            case .unavailable: throw .codeRequired(.unavailable)
            case .failed where (sidecar.quickUnlock?.failures ?? 0) >= QuickUnlockPolicy.maxFailures:
                throw .codeRequired(.tooManyFailures)
            default: throw .biometric(error)
            }
        }
    }

    // MARK: Settings and code change

    public var wipeAfterTenFailures: Bool { sidecar.wipeAfterTenFailures }

    /// The optional "wipe after 10 wrong codes" (default off). The UI offers
    /// it only while unlocked.
    public func setWipeAfterTenFailures(_ enabled: Bool) {
        sidecar.wipeAfterTenFailures = enabled
        saveSidecar()
    }

    /// Re-wraps the DEK under a new code (instant; no data is re-encrypted).
    /// The current code is a counted attempt. Biometric quick unlock keeps
    /// working: it wraps the DEK, which does not change. If the optional
    /// "wipe after 10" fires here, everything is shredded as on the lock
    /// screen — the Secure Enclave key and its wrap of the DEK included —
    /// and the error is `.wiped`: the caller must erase the local data.
    public func changeCode(current: String, new: String, confirmation: String) throws(VaultError) {
        do throws(VaultError) {
            try vault().changeCode(current: current, new: new, confirmation: confirmation)
        } catch {
            if error == .wiped { removeLeftovers() }
            throw error
        }
        sidecar.lastCodeEntry = clock.now()
        saveSidecar()
        RuntimeLog.vault.info("code changed")
    }

    // MARK: Crypto-shredding

    /// Destroys every key that protects local data: the device secret and the
    /// vault's Secure Enclave key of format v2 (Keychain items), the vault file
    /// and attempt counter, the biometric Secure Enclave key and its wrap.
    /// Without them the database, key slots and media are
    /// unreadable ciphertext; ``AppDirectories/eraseAll()`` then removes it.
    public func destroyKeys() {
        try? vault().destroy()
        removeLeftovers()
        RuntimeLog.vault.info("keys destroyed")
    }

    private func removeLeftovers() {
        // The device secret and the vault's Secure Enclave key (format v2)
        // are items of this store; a test stand-in is deleted on its own.
        try? secureItems.deleteAll()
        try? hardwareKey?.deleteKey()
        biometrics.deleteKey()
        sidecar = VaultSidecar()
        try? FileManager.default.removeItem(at: sidecarURL)
    }

    // MARK: Sidecar file

    private static func loadSidecar(at url: URL) -> VaultSidecar {
        guard let data = try? Data(contentsOf: url), let sidecar = try? JSONDecoder().decode(VaultSidecar.self, from: data)
        else { return VaultSidecar() }
        return sidecar
    }

    private func saveSidecar() {
        guard let data = try? JSONEncoder().encode(sidecar) else { return }
        do {
            try ProtectedFiles.write(data, to: sidecarURL)
        } catch {
            RuntimeLog.vault.error("settings not saved: \(error.logLabel, privacy: .public)")
        }
    }
}
