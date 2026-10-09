import Foundation

/// Why a new backup password was rejected (PROTOCOL.md §5.5). The rules are
/// checked in the order of the cases; the first that applies is reported.
public enum BackupPasswordError: Error, Equatable, Sendable {
    /// Fewer than ``BackupPassword/minimumLength`` code points after trimming and NFKC.
    case tooShort
    /// Listed in `spec/common-backup-passwords.txt`.
    case tooCommon
    /// One character repeated, ignoring case, like `AAAAAaaaaa`.
    case repeatedCharacter
    /// A run like `abcdefghij`, `jihgfedcba` or a keyboard row like `poiuytrewq`.
    case simpleSequence
    /// The confirmation does not match.
    case mismatch
}

/// The password of the account backup file (yedek parolası), separate from
/// the six-digit secret code. A backup file can be attacked offline, without
/// any device secret, so the password must be long (PROTOCOL.md §5.5). With
/// at least 10 characters it can never be the secret code.
public enum BackupPassword {
    /// Minimum length in Unicode scalars, after trimming and NFKC.
    public static let minimumLength = 10

    /// Validates a newly chosen password and its confirmation (the export UI
    /// asks twice). Opening a backup never checks these rules.
    public static func validate(_ password: String, confirmation: String) throws(BackupPasswordError) {
        try validate(password)
        guard keyMaterial(password) == keyMaterial(confirmation) else { throw .mismatch }
    }

    /// Validates a newly chosen password (§5.5 rules). They run on
    /// `NFKC(trim(password))`, compared lowercased with default casing.
    public static func validate(_ password: String) throws(BackupPasswordError) {
        let normalized = password.trimmingUnicodeWhitespace().nfkc
        let scalars = Array(normalized.lowercased().unicodeScalars)
        guard normalized.unicodeScalars.count >= minimumLength else { throw .tooShort }
        guard !commonPasswords.contains(String(String.UnicodeScalarView(scalars))) else { throw .tooCommon }
        guard Set(scalars).count > 1 else { throw .repeatedCharacter }
        guard !isSimpleSequence(scalars) else { throw .simpleSequence }
    }

    /// The PBKDF2 password of the backup key: `UTF-8(NFKC(password))`. Not
    /// trimmed: the password is used exactly as typed (§5.5).
    static func keyMaterial(_ password: String) -> Data {
        Data(password.nfkc.utf8)
    }

    /// Keyboard rows (US and Turkish Q/F layouts).
    private static let keyboardRows: [[Unicode.Scalar]] = [
        "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç",
        "fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb",
    ].map { Array($0.unicodeScalars) }

    /// Consecutive scalar values (ascending or descending), or a contiguous
    /// run of a keyboard row in either direction.
    static func isSimpleSequence(_ scalars: [Unicode.Scalar]) -> Bool {
        guard scalars.count >= 2 else { return true }
        let steps = zip(scalars, scalars.dropFirst()).map { Int($1.value) - Int($0.value) }
        if steps.allSatisfy({ $0 == 1 }) || steps.allSatisfy({ $0 == -1 }) { return true }
        let reversed = Array(scalars.reversed())
        return keyboardRows.contains { row in
            row.containsContiguous(scalars) || row.containsContiguous(reversed)
        }
    }

    /// `spec/common-backup-passwords.txt`, bundled as a resource, NFKC-lowercased.
    static let commonPasswords: Set<String> = Set(
        BundledList.load("common-backup-passwords").map(comparisonForm))

    static func comparisonForm(_ entry: String) -> String {
        entry.nfkc.lowercased()
    }
}

extension Array where Element: Equatable {
    fileprivate func containsContiguous(_ needle: [Element]) -> Bool {
        guard !needle.isEmpty, needle.count <= count else { return needle.isEmpty }
        return (0...(count - needle.count)).contains { start in
            self[start..<start + needle.count].elementsEqual(needle)
        }
    }
}

extension BackupPasswordError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .tooShort: return localizer.string(.backupPasswordTooShort)
        case .tooCommon: return localizer.string(.backupPasswordTooCommon)
        case .repeatedCharacter: return localizer.string(.backupPasswordRepeatedCharacter)
        case .simpleSequence: return localizer.string(.backupPasswordSequence)
        case .mismatch: return localizer.string(.backupPasswordMismatch)
        }
    }
}
