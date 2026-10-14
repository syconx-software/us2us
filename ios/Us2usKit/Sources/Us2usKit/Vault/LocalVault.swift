import Foundation

/// Local delays after wrong secret codes (PROTOCOL.md §5.2): the first two
/// wrong codes are free, then 1 min after the 3rd, 5 min after the 4th,
/// 15 min after the 5th, 30 min after the 6th and 1 h after every further one.
public struct UnlockThrottle: Sendable, Equatable {
    /// Delay imposed after the n-th consecutive failure, for n = 1, 2, …;
    /// the last entry repeats.
    public let delays: [TimeInterval]

    public init(delays: [TimeInterval]) {
        precondition(!delays.isEmpty)
        self.delays = delays
    }

    public static let standard = UnlockThrottle(delays: [0, 0, 60, 300, 900, 1800, 3600])

    public func delay(afterFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return 0 }
        return delays[min(failures, delays.count) - 1]
    }

    /// What the next wrong codes cost, for the unlock screen's warning
    /// ("2 yanlış daha olursa 1 dakika beklemen gerekir"): after
    /// `attemptsLeft` more wrong codes the user has to wait `delay`. `nil`
    /// if no further wrong code ever leads to a delay.
    public func upcomingDelay(afterFailures failures: Int) -> UpcomingDelay? {
        let current = max(failures, 0)
        // Past the end of the table the last delay repeats, so one step suffices there.
        for next in (current + 1)...max(current + 1, delays.count) where delay(afterFailures: next) > 0 {
            return UpcomingDelay(attemptsLeft: next - current, delay: delay(afterFailures: next))
        }
        return nil
    }
}

/// The delay the user is heading for (``UnlockThrottle/upcomingDelay(afterFailures:)``).
public struct UpcomingDelay: Sendable, Equatable {
    /// Wrong codes still possible before the delay, at least 1.
    public let attemptsLeft: Int
    /// Seconds to wait after that many wrong codes.
    public let delay: TimeInterval

    public init(attemptsLeft: Int, delay: TimeInterval) {
        self.attemptsLeft = attemptsLeft
        self.delay = delay
    }
}

/// What the unlock screen shows before and after an attempt.
public struct UnlockStatus: Sendable, Equatable {
    /// Consecutive wrong codes so far.
    public let failures: Int
    /// When the next attempt is allowed, or `nil` for now.
    public let nextAttemptAllowedAt: Date?
    /// What further wrong codes will cost.
    public let upcoming: UpcomingDelay?

    public init(failures: Int, nextAttemptAllowedAt: Date?, upcoming: UpcomingDelay?) {
        self.failures = failures
        self.nextAttemptAllowedAt = nextAttemptAllowedAt
        self.upcoming = upcoming
    }
}

/// Persisted counter of consecutive wrong codes and how long the user has
/// waited since the last one (PROTOCOL.md §5.2).
struct UnlockAttempts: Codable, Equatable {
    var failures = 0
    /// Time waited since the last failure: a lower bound of the real time,
    /// measured with ``LocalVault/systemUptime()``, which never decreases.
    var waited: TimeInterval = 0
    /// The uptime reading `waited` was last brought up to date with.
    var uptime: TimeInterval?

    /// Brings `waited` up to `reading`. A reading below the stored one means
    /// the phone restarted, and only the time since boot counts.
    mutating func observe(uptime reading: TimeInterval) {
        if let last = uptime {
            waited += reading >= last ? reading - last : reading
        }
        uptime = reading
    }
}

/// The on-disk vault of one installation: the vault file, the attempt counter,
/// the device secret and, for format v2, the hardware key (PROTOCOL.md §5.2).
/// Unlocking yields the DEK, from which the database key and key-slot keys
/// derive (§5.3).
///
/// With a ``VaultHardwareKey`` that is available, new vaults are v2 and a v1
/// vault moves to v2 at its next successful unlock: the same DEK is sealed
/// under a fresh hardware key, checked against a second evaluation, and only
/// then replaces the v1 file in one atomic write. Any failure leaves the v1
/// file in place for the next unlock to try again.
public final class LocalVault: Sendable {
    public struct Configuration: Sendable {
        /// The "wipe after 10 wrong attempts" option of §5.2.
        public static let wipeOptionFailures = 10

        public var iterations: Int
        public var throttle: UnlockThrottle
        /// Optional "wipe after N wrong attempts" (default off; the app
        /// offers ``wipeOptionFailures``).
        public var wipeAfterFailures: Int?

        public init(
            iterations: Int = VaultFormat.defaultIterations, throttle: UnlockThrottle = .standard,
            wipeAfterFailures: Int? = nil
        ) {
            self.iterations = iterations
            self.throttle = throttle
            self.wipeAfterFailures = wipeAfterFailures
        }
    }

    private let vaultURL: URL
    private let attemptsURL: URL
    private let deviceSecrets: any DeviceSecretProvider
    private let hardwareKey: (any VaultHardwareKey)?
    private let configuration: Configuration
    private let now: @Sendable () -> Date
    private let uptime: @Sendable () -> TimeInterval
    /// Serializes file access within this process.
    private let lock = Locked(())

    /// - Parameters:
    ///   - hardwareKey: the per-guess hardware step of format v2 (the Secure
    ///     Enclave on iOS). `nil` keeps writing v1 and cannot open a v2 vault.
    ///   - now: the wall clock, used only to tell the user when the next
    ///     attempt is allowed.
    ///   - uptime: time since boot including sleep, which the user cannot
    ///     set; the delays are measured with it (§5.2).
    public init(
        directory: URL, deviceSecrets: any DeviceSecretProvider, hardwareKey: (any VaultHardwareKey)? = nil,
        configuration: Configuration = Configuration(),
        now: @escaping @Sendable () -> Date = { Date() },
        uptime: @escaping @Sendable () -> TimeInterval = { LocalVault.systemUptime() }
    ) {
        self.vaultURL = directory.appendingPathComponent("vault.brpv")
        self.attemptsURL = directory.appendingPathComponent("vault-attempts.json")
        self.deviceSecrets = deviceSecrets
        self.hardwareKey = hardwareKey
        self.configuration = configuration
        self.now = now
        self.uptime = uptime
    }

    /// Seconds since boot, including time asleep (`CLOCK_MONOTONIC` on
    /// Darwin). Unlike the wall clock it cannot be set, and it restarts from
    /// zero when the phone restarts.
    public static func systemUptime() -> TimeInterval {
        TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC)) / 1_000_000_000
    }

    public var exists: Bool {
        FileManager.default.fileExists(atPath: vaultURL.path)
    }

    /// The format of the vault file on disk (v2 = with the hardware step), or
    /// `nil` when there is no readable vault file.
    public var formatVersion: VaultFormatVersion? {
        lock.withLock { _ in formatVersionOnDisk() }
    }

    /// Creates the vault for a new installation and returns the fresh DEK.
    /// The vault is v2 when the hardware key is available and can be created,
    /// otherwise v1 (§5.2).
    public func create(code: String, confirmation: String) throws(VaultError) -> Data {
        do {
            try SecretCode.validate(code, confirmation: confirmation)
        } catch {
            throw .invalidNewCode(error)
        }
        return try lock.withLock { _ throws(VaultError) in
            guard !exists else { throw .alreadyExists }
            let dek = SecureRandom.bytes(VaultFormat.keyLength)
            var deviceSecret = SecureRandom.bytes(VaultFormat.keyLength)
            defer { deviceSecret.zeroize() }
            do {
                try deviceSecrets.storeDeviceSecret(deviceSecret)
            } catch {
                throw .storage("device secret: \(error)")
            }
            if !writeWithHardwareStep(dek: dek, code: code, deviceSecret: deviceSecret) {
                try write(dek: dek, code: code, deviceSecret: deviceSecret, hardwareKey: nil)
            }
            try saveAttempts(UnlockAttempts())
            return dek
        }
    }

    /// When the next unlock attempt is allowed (`nil` = now).
    public func nextAttemptAllowedAt() -> Date? {
        status().nextAttemptAllowedAt
    }

    /// Wrong codes so far, the current wait and what further wrong codes
    /// cost, for the unlock screen.
    public func status() -> UnlockStatus {
        lock.withLock { _ in
            let attempts = observedAttempts()
            return UnlockStatus(
                failures: attempts.failures, nextAttemptAllowedAt: allowedAt(attempts),
                upcoming: configuration.throttle.upcomingDelay(afterFailures: attempts.failures))
        }
    }

    /// Opens the vault and returns the DEK, enforcing the attempt delays.
    ///
    /// The attempt is written to disk as a failure *before* the code is
    /// checked and cleared only on success. Otherwise killing the app during
    /// the slow key derivation would allow unlimited guesses without delays.
    /// Input that is not six digits is refused first and not counted. A v2
    /// vault evaluates the hardware key once per attempt, right or wrong; a
    /// v1 vault moves to v2 after a successful attempt where the hardware
    /// key is available.
    public func unlock(code: String) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        return try lock.withLock { _ throws(VaultError) in
            guard let vault = try? Data(contentsOf: vaultURL) else { throw .notFound }
            let previous = observedAttempts()
            if let allowed = allowedAt(previous) { throw .lockedOut(until: allowed) }
            let attempt = UnlockAttempts(failures: previous.failures + 1, waited: 0, uptime: uptime())
            try saveAttempts(attempt)
            do throws(VaultError) {
                var deviceSecret = try loadDeviceSecret()
                defer { deviceSecret.zeroize() }
                let dek = try VaultFormat.open(
                    vault, code: code, deviceSecret: deviceSecret, hardwareKey: hardwareKey)
                try saveAttempts(UnlockAttempts())
                if VaultFormat.version(of: vault) == .v1 {
                    _ = writeWithHardwareStep(dek: dek, code: code, deviceSecret: deviceSecret)
                }
                return dek
            } catch .wrongCode {
                if let limit = configuration.wipeAfterFailures, attempt.failures >= limit {
                    try destroyFiles()
                    throw .wiped
                }
                throw .wrongCode
            } catch {
                // Not a guess at the code (missing device secret or hardware
                // key, a hardware or Keychain error, a corrupt file): it must
                // not count towards the delays or the optional wipe.
                try saveAttempts(previous)
                throw error
            }
        }
    }

    /// The stored attempts with `waited` brought up to date (and saved, so the
    /// time waited never goes backwards). Changing the date or restarting the
    /// phone therefore never shortens a delay (§5.2).
    private func observedAttempts() -> UnlockAttempts {
        var attempts = loadAttempts()
        guard attempts.failures > 0 else { return attempts }
        attempts.observe(uptime: uptime())
        // Losing this update only means waiting longer, never shorter.
        try? saveAttempts(attempts)
        return attempts
    }

    /// The end of the current delay, or `nil` if an attempt is allowed now.
    private func allowedAt(_ attempts: UnlockAttempts) -> Date? {
        let remaining = configuration.throttle.delay(afterFailures: attempts.failures) - attempts.waited
        return remaining > 0 ? now().addingTimeInterval(remaining) : nil
    }

    /// Re-wraps the DEK under a new code (instant; no data is re-encrypted).
    /// The format stays: a v2 vault is sealed again with the same hardware key.
    public func changeCode(current: String, new: String, confirmation: String) throws(VaultError) {
        do {
            try SecretCode.validate(new, confirmation: confirmation)
        } catch {
            throw .invalidNewCode(error)
        }
        var dek = try unlock(code: current)
        defer { dek.zeroize() }
        try lock.withLock { _ throws(VaultError) in
            var deviceSecret = try loadDeviceSecret()
            defer { deviceSecret.zeroize() }
            let keepsHardwareStep = formatVersionOnDisk() == .v2
            if keepsHardwareStep, hardwareKey == nil { throw .deviceSecretMissing }
            try write(
                dek: dek, code: new, deviceSecret: deviceSecret, hardwareKey: keepsHardwareStep ? hardwareKey : nil)
        }
    }

    /// Crypto-shreds the vault: without the device secret (and the hardware
    /// key) and the vault file the DEK, and everything encrypted under it, is
    /// unrecoverable.
    public func destroy() throws(VaultError) {
        try lock.withLock { _ throws(VaultError) in try destroyFiles() }
    }

    // MARK: Hardware step (format v2)

    /// Seals the DEK as v2 under a new hardware key and writes it, if the
    /// hardware is available; returns whether it did. On any failure the new
    /// key is deleted again (unless the file on disk is v2 after all) and the
    /// vault file is as before, so a new vault is written as v1 and a v1
    /// vault stays v1 until the next unlock tries again. Replacing the key is
    /// safe here: only a v2 file depends on it, and the file is not v2.
    private func writeWithHardwareStep(dek: Data, code: String, deviceSecret: Data) -> Bool {
        guard let hardwareKey, hardwareKey.isAvailable else { return false }
        do {
            try hardwareKey.createKey()
            try write(dek: dek, code: code, deviceSecret: deviceSecret, hardwareKey: hardwareKey)
            return true
        } catch {
            if formatVersionOnDisk() != .v2 { try? hardwareKey.deleteKey() }
            return false
        }
    }

    private func formatVersionOnDisk() -> VaultFormatVersion? {
        guard let vault = try? Data(contentsOf: vaultURL) else { return nil }
        return VaultFormat.version(of: vault)
    }

    // MARK: Files

    private func loadDeviceSecret() throws(VaultError) -> Data {
        let secret: Data?
        do {
            secret = try deviceSecrets.loadDeviceSecret()
        } catch {
            throw .storage("device secret: \(error)")
        }
        guard let secret else { throw .deviceSecretMissing }
        return secret
    }

    /// Seals (v2 with `hardwareKey`, else v1) and replaces the vault file in
    /// one atomic write, so a crash leaves either the old file or the new one.
    private func write(
        dek: Data, code: String, deviceSecret: Data, hardwareKey: (any VaultHardwareKey)?
    ) throws(VaultError) {
        let vault = try VaultFormat.seal(
            dek: dek, code: code, deviceSecret: deviceSecret, hardwareKey: hardwareKey,
            salt: SecureRandom.bytes(FileHeader.saltLength), iterations: configuration.iterations,
            nonce: SecureRandom.bytes(AES256GCM.nonceLength))
        try writeProtected(vault, to: vaultURL)
    }

    private func loadAttempts() -> UnlockAttempts {
        guard let data = try? Data(contentsOf: attemptsURL),
            let attempts = try? JSONDecoder().decode(UnlockAttempts.self, from: data)
        else { return UnlockAttempts() }
        return attempts
    }

    private func saveAttempts(_ attempts: UnlockAttempts) throws(VaultError) {
        do {
            try writeProtected(try JSONEncoder().encode(attempts), to: attemptsURL)
        } catch let error as VaultError {
            throw error
        } catch {
            throw .storage("attempts: \(error)")
        }
    }

    private func writeProtected(_ data: Data, to url: URL) throws(VaultError) {
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
        } catch {
            throw .storage("\(url.lastPathComponent): \(error.localizedDescription)")
        }
    }

    private func destroyFiles() throws(VaultError) {
        do {
            try deviceSecrets.deleteDeviceSecret()
            // Best effort: without the device secret the vault is already
            // unopenable, and the app deletes all its Keychain items as well.
            try? hardwareKey?.deleteKey()
            for url in [vaultURL, attemptsURL] where FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
        } catch {
            throw .storage("destroy: \(error.localizedDescription)")
        }
    }
}
