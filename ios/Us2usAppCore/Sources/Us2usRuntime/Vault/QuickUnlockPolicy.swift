import Foundation

/// Why the secret code is needed instead of Face ID / Touch ID.
public enum CodeRequiredReason: String, Sendable, Equatable, Codable {
    /// The code was not entered for 7 days (§5.2).
    case sevenDaysWithoutCode
    /// Faces or fingers were added or removed since biometric unlock was set up.
    case enrollmentChanged
    /// 5 biometric attempts failed in a row (§5.2).
    case tooManyFailures
    /// The OS locked biometrics (its own failure limit).
    case systemLockout
    /// Biometrics are off in Settings, nothing is enrolled, or no passcode is set.
    case unavailable
    /// The hardware key could not be used; the next code unlock re-creates it.
    case keyProblem
}

/// Whether the lock screen may offer biometric quick unlock.
public enum QuickUnlockStatus: Sendable, Equatable {
    /// The user has not turned it on (or the device cannot do it).
    case notEnabled
    case available(BiometryKind)
    /// Turned on, but the code is required now.
    case codeRequired(CodeRequiredReason)
}

/// What is stored next to the vault (complete protection) about biometric
/// quick unlock. The wrap is the DEK encrypted to a Secure Enclave key that
/// only a biometric match on this device can use; nothing here is secret on
/// its own.
struct QuickUnlockRecord: Codable, Equatable {
    var wrappedKey: Data
    /// `BiometricAvailability.enrollmentState` when the wrap was made.
    var enrollmentState: Data?
    /// Consecutive failed biometric attempts; an attempt is counted *before*
    /// the prompt and taken back only on success or cancel.
    var failures = 0
    /// The hardware key failed; re-create it at the next code unlock.
    var needsRewrap = false
}

/// The §5.2 rules for biometric quick unlock, as a pure function.
enum QuickUnlockPolicy {
    static let maxFailures = 5
    static let codeInterval: TimeInterval = 7 * 24 * 3600

    static func status(
        record: QuickUnlockRecord?, lastCodeEntry: Date?, availability: BiometricAvailability, now: Date
    ) -> QuickUnlockStatus {
        guard let record else { return .notEnabled }
        if record.needsRewrap { return .codeRequired(.keyProblem) }
        if record.failures >= maxFailures { return .codeRequired(.tooManyFailures) }
        // A clock set backwards does not extend the 7 days: a future entry date counts as stale.
        guard let lastCodeEntry, lastCodeEntry <= now, now.timeIntervalSince(lastCodeEntry) < codeInterval else {
            return .codeRequired(.sevenDaysWithoutCode)
        }
        if availability.lockedOut { return .codeRequired(.systemLockout) }
        guard availability.isUsable else { return .codeRequired(.unavailable) }
        guard availability.enrollmentState == record.enrollmentState else {
            return .codeRequired(.enrollmentChanged)
        }
        return .available(availability.kind)
    }
}

/// Settings and records that must be readable *before* the vault is open,
/// kept as one small JSON file next to the vault with complete protection.
/// Nothing in it reveals content or contacts.
struct VaultSidecar: Codable, Equatable {
    /// The optional "wipe after 10 wrong codes" of §5.2 (default off).
    var wipeAfterTenFailures = false
    /// The last time the user unlocked with the code (for the 7-day rule).
    var lastCodeEntry: Date?
    var quickUnlock: QuickUnlockRecord?
    /// The user wants biometric unlock (kept while the wrap is being re-created).
    var quickUnlockWanted = false
}
