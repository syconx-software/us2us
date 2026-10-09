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

/// The on-disk vault of one installation: the vault file, the attempt counter
/// and the device secret (PROTOCOL.md §5.2). Unlocking yields the DEK, from
/// which the database key and key-slot keys derive (§5.3).
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
    private let configuration: Configuration
    private let now: @Sendable () -> Date
    private let uptime: @Sendable () -> TimeInterval
    /// Serializes file access within this process.
    private let lock = Locked(())

    /// - Parameters:
    ///   - now: the wall clock, used only to tell the user when the next
    ///     attempt is allowed.
    ///   - uptime: time since boot including sleep, which the user cannot
    ///     set; the delays are measured with it (§5.2).
    public init(
        directory: URL, deviceSecrets: any DeviceSecretProvider, configuration: Configuration = Configuration(),
        now: @escaping @Sendable () -> Date = { Date() },
        uptime: @escaping @Sendable () -> TimeInterval = { LocalVault.systemUptime() }
    ) {
        self.vaultURL = directory.appendingPathComponent("vault.brpv")
        self.attemptsURL = directory.appendingPathComponent("vault-attempts.json")
        self.deviceSecrets = deviceSecrets
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

    /// Creates the vault for a new installation and returns the fresh DEK.
    public func create(code: String, confirmation: String) throws(VaultError) -> Data {
        do {
            try SecretCode.validate(code, confirmation: confirmation)
        } catch {
            throw .invalidNewCode(error)
        }
        return try lock.withLock { _ throws(VaultError) in
            guard !exists else { throw .alreadyExists }
            let dek = SecureRandom.bytes(VaultFormat.keyLength)
            let deviceSecret = SecureRandom.bytes(VaultFormat.keyLength)
            do {
                try deviceSecrets.storeDeviceSecret(deviceSecret)
            } catch {
                throw .storage("device secret: \(error)")
            }
            try write(dek: dek, code: code, deviceSecret: deviceSecret)
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
    /// Input that is not six digits is refused first and not counted.
    public func unlock(code: String) throws(VaultError) -> Data {
        guard SecretCode.isWellFormed(code) else { throw .malformedCode }
        return try lock.withLock { _ throws(VaultError) in
            guard let vault = try? Data(contentsOf: vaultURL) else { throw .notFound }
            let previous = observedAttempts()
            if let allowed = allowedAt(previous) { throw .lockedOut(until: allowed) }
            let attempt = UnlockAttempts(failures: previous.failures + 1, waited: 0, uptime: uptime())
            try saveAttempts(attempt)
            do throws(VaultError) {
                let dek = try VaultFormat.open(vault, code: code, deviceSecret: try loadDeviceSecret())
                try saveAttempts(UnlockAttempts())
                return dek
            } catch .wrongCode {
                if let limit = configuration.wipeAfterFailures, attempt.failures >= limit {
                    try destroyFiles()
                    throw .wiped
                }
                throw .wrongCode
            } catch {
                // Not a guess at the code (missing device secret, corrupt file):
                // it must not count towards the delays or the optional wipe.
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
    public func changeCode(current: String, new: String, confirmation: String) throws(VaultError) {
        do {
            try SecretCode.validate(new, confirmation: confirmation)
        } catch {
            throw .invalidNewCode(error)
        }
        let dek = try unlock(code: current)
        try lock.withLock { _ throws(VaultError) in
            try write(dek: dek, code: new, deviceSecret: try loadDeviceSecret())
        }
    }

    /// Crypto-shreds the vault: without the device secret and the vault file
    /// the DEK, and everything encrypted under it, is unrecoverable.
    public func destroy() throws(VaultError) {
        try lock.withLock { _ throws(VaultError) in try destroyFiles() }
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

    private func write(dek: Data, code: String, deviceSecret: Data) throws(VaultError) {
        let vault = try VaultFormat.seal(
            dek: dek, code: code, deviceSecret: deviceSecret, salt: SecureRandom.bytes(FileHeader.saltLength),
            iterations: configuration.iterations, nonce: SecureRandom.bytes(AES256GCM.nonceLength))
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
            for url in [vaultURL, attemptsURL] where FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
        } catch {
            throw .storage("destroy: \(error.localizedDescription)")
        }
    }
}
