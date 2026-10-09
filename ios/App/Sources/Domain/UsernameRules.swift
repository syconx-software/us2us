import Foundation

/// Username rules as the design states them (2.1): 3–20 characters; ASCII
/// lower-case letters, digits, dot and underscore; no Turkish characters —
/// so İ/ı case mapping can never break exact matching in any language.
///
/// - Note: `docs/PROTOCOL.md` §3.1 / Us2usKit's `NormalizedUsername` currently
///   allow `^[a-z][a-z0-9_]{2,23}$` (no dot, up to 24, must start with a
///   letter), while the design's own examples use dots (“mert.k”, “selin.n”).
///   The owner decided that the design wins; until the protocol follows, the
///   live service must reject what the kit rejects. See ios/App/README.md.
enum UsernameRules {
    static let minLength = 3
    static let maxLength = 20

    enum Violation: Hashable, Sendable, CaseIterable {
        /// “3–20 karakter”.
        case length
        /// “Küçük harf, rakam, nokta ve alt çizgi”.
        case characters
        /// “Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç”.
        case turkishCharacters
    }

    static let turkishCharacters: Set<Character> = ["ı", "İ", "ş", "Ş", "ğ", "Ğ", "ü", "Ü", "ö", "Ö", "ç", "Ç"]

    /// The first rule `username` breaks, or `nil` when it is valid.
    static func violation(_ username: String) -> Violation? {
        if username.contains(where: { turkishCharacters.contains($0) }) { return .turkishCharacters }
        let allowed = username.unicodeScalars.allSatisfy { scalar in
            ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) || scalar == "." || scalar == "_"
        }
        if !allowed { return .characters }
        if !(minLength...maxLength).contains(username.count) { return .length }
        return nil
    }

    static func isValid(_ username: String) -> Bool { violation(username) == nil }

    /// What the field keeps while typing: lower-cased with no locale (so “I”
    /// becomes “i”, never “ı”), stripped of anything outside the alphabet and
    /// cut to the maximum length. Turkish letters are dropped, not converted.
    static func sanitize(_ input: String) -> String {
        let lowered = input.lowercased(with: Locale(identifier: "en_US_POSIX"))
        let kept = lowered.unicodeScalars.filter { scalar in
            ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) || scalar == "." || scalar == "_"
        }
        return String(String.UnicodeScalarView(kept).prefix(maxLength))
    }
}
