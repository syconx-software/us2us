import Foundation

/// Why a username was rejected (PROTOCOL.md §3.1).
public enum UsernameError: Error, Equatable, Sendable {
    case tooShort
    case tooLong
    case mustStartWithLetter
    case invalidCharacters
}

/// A normalized, valid username (PROTOCOL.md §3).
///
/// A username is only a findable label for an account id; no local data and
/// no key is derived from it except the key of the account's avatar record
/// for search, which is made again after every change (§3.3, §8.10), so it
/// can change at any time.
public struct NormalizedUsername: Hashable, Sendable, CustomStringConvertible {
    public static let minLength = 3
    public static let maxLength = 24

    /// The normalized form, matching `^[a-z][a-z0-9_]{2,23}$`.
    public let value: String

    /// Normalizes and validates user input.
    public init(_ input: String) throws(UsernameError) {
        let normalized = Self.normalize(input)
        try Self.validate(normalized)
        self.value = normalized
    }

    public var description: String { value }

    /// §3.1 normalization: trim Unicode whitespace, NFKC, then lowercase.
    /// Lowercasing uses the locale-independent Unicode mapping, so Turkish
    /// `I`→`ı` never happens; non-ASCII results are rejected by validation.
    public static func normalize(_ input: String) -> String {
        input.trimmingUnicodeWhitespace().nfkc.lowercased()
    }

    /// Checks `^[a-z][a-z0-9_]{2,23}$` on an already normalized string.
    static func validate(_ normalized: String) throws(UsernameError) {
        let bytes = Array(normalized.utf8)
        let isLetter: (UInt8) -> Bool = { (UInt8(ascii: "a")...UInt8(ascii: "z")).contains($0) }
        let isAllowed: (UInt8) -> Bool = {
            isLetter($0) || (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) || $0 == UInt8(ascii: "_")
        }
        // Character checks come first so that "ş" reports invalid characters
        // rather than a misleading length.
        guard bytes.allSatisfy(isAllowed) else { throw .invalidCharacters }
        guard bytes.count >= minLength else { throw .tooShort }
        guard bytes.count <= maxLength else { throw .tooLong }
        guard isLetter(bytes[0]) else { throw .mustStartWithLetter }
    }

    static let hashSalt = Data("BRP/username/v1".utf8)
    static let hashIterations = 100_000

    /// §3.2 `PBKDF2-HMAC-SHA256(UTF-8(username), "BRP/username/v1", 100000, 32)`.
    /// Deliberately slow (tens of milliseconds); call it off the main thread.
    public func hash() -> Data {
        PBKDF2.sha256(password: Data(value.utf8), salt: Self.hashSalt, iterations: Self.hashIterations)
    }
}

extension UsernameError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .tooShort: return localizer.string(.usernameTooShort)
        case .tooLong: return localizer.string(.usernameTooLong)
        case .mustStartWithLetter: return localizer.string(.usernameMustStartWithLetter)
        case .invalidCharacters: return localizer.string(.usernameInvalidCharacters)
        }
    }
}
