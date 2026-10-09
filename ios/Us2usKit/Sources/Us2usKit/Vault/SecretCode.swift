import Foundation

/// Why a new secret code was rejected (PROTOCOL.md §5.2). The rules are
/// checked in the order of the cases; the first that applies is reported.
public enum SecretCodeError: Error, Equatable, Sendable {
    /// Not exactly six ASCII digits.
    case notSixDigits
    /// Six times the same digit, like `000000`.
    case repeatedDigit
    /// A run up or down the digits, also wrapping from 9 to 0: `123456`,
    /// `890123`, `654321`.
    case sequence
    /// Listed in `spec/common-secret-codes.txt`, like `121212` or `159753`.
    case tooCommon
    /// The confirmation does not match.
    case mismatch
}

/// The user's secret code (gizli kod): exactly six digits, which together with
/// the device secret protect all local data (PROTOCOL.md §5.2). It is never
/// sent anywhere and is independent of the username.
public enum SecretCode {
    /// Number of digits.
    public static let length = 6

    /// Whether `code` is exactly six ASCII digits. Nothing is trimmed or
    /// normalized: fullwidth or other scripts' digits are not a code.
    public static func isWellFormed(_ code: String) -> Bool {
        var count = 0
        for byte in code.utf8 {
            guard (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte) else { return false }
            count += 1
        }
        return count == length
    }

    /// Validates a newly chosen code and its confirmation.
    public static func validate(_ code: String, confirmation: String) throws(SecretCodeError) {
        try validate(code)
        guard Array(code.utf8) == Array(confirmation.utf8) else { throw .mismatch }
    }

    /// Validates a newly chosen code (§5.2 rules, in order).
    public static func validate(_ code: String) throws(SecretCodeError) {
        guard isWellFormed(code) else { throw .notSixDigits }
        let digits = code.utf8.map { Int($0) - Int(UInt8(ascii: "0")) }
        guard Set(digits).count > 1 else { throw .repeatedDigit }
        guard !isSequence(digits) else { throw .sequence }
        guard !commonCodes.contains(code) else { throw .tooCommon }
    }

    /// Every step between neighbours is +1 modulo 10, or every step is −1
    /// modulo 10 (`890123` and `321098` included).
    static func isSequence(_ digits: [Int]) -> Bool {
        let steps = Set(zip(digits, digits.dropFirst()).map { ($1 - $0 + 10) % 10 })
        return steps == [1] || steps == [9]
    }

    /// The PBKDF2 password of the vault key: the six ASCII bytes (§5.2).
    /// Callers check ``isWellFormed(_:)`` first.
    static func keyMaterial(_ code: String) -> Data {
        Data(code.utf8)
    }

    /// `spec/common-secret-codes.txt`, bundled as a resource.
    static let commonCodes: Set<String> = Set(BundledList.load("common-secret-codes"))
}

/// Line lists bundled from `spec/` (one entry per line, `#` comments).
enum BundledList {
    static func load(_ name: String) -> [String] {
        guard let url = Bundle.module.url(forResource: name, withExtension: "txt"),
            let text = try? String(contentsOf: url, encoding: .utf8)
        else {
            assertionFailure("\(name).txt missing from the bundle")
            return []
        }
        return parse(text)
    }

    static func parse(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
    }
}

extension SecretCodeError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .notSixDigits: return localizer.string(.secretCodeNotSixDigits)
        case .repeatedDigit: return localizer.string(.secretCodeRepeatedDigit)
        case .sequence: return localizer.string(.secretCodeSequence)
        case .tooCommon: return localizer.string(.secretCodeTooCommon)
        case .mismatch: return localizer.string(.secretCodeMismatch)
        }
    }
}
