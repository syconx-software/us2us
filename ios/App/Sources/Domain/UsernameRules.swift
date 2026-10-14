import Foundation
import Us2usKit

/// Username rules, the same on every screen and on every platform:
/// `docs/PROTOCOL.md` §3.1 (Us2usKit's `NormalizedUsername`). A name is valid
/// when its normalized form matches `^[a-z][a-z0-9_]{2,23}$` — 3–24
/// characters, a letter a–z first, then a–z, 0–9 and the underscore; no dot.
/// The cases both apps test against are `spec/username-rules.json`.
///
/// What was typed is never turned into another name: nothing is dropped,
/// cut or converted (“Kağan” stays “kağan” and is refused, it does not
/// become “kaan”). An invalid name is shown as typed with the rules it
/// breaks, and is never looked up, hashed or registered.
enum UsernameRules {
    static let minLength = NormalizedUsername.minLength
    static let maxLength = NormalizedUsername.maxLength

    /// A rule a name breaks, in the order they are reported (`allCases`).
    enum Violation: String, Hashable, Sendable, CaseIterable {
        /// “Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç”.
        case turkishCharacters
        /// “Küçük harf, rakam ve alt çizgi”: any other character.
        case characters
        /// “Harfle başlar”.
        case firstLetter
        /// “3–24 karakter”.
        case length
    }

    /// The Turkish letters as normalization leaves them (lower case; “İ”
    /// becomes “i” + U+0307 COMBINING DOT ABOVE, see `violations`).
    static let turkishLetters: Set<Unicode.Scalar> = ["ı", "ş", "ğ", "ü", "ö", "ç"]

    /// What typed text stands for: Unicode white space around it and one
    /// leading “@” dropped, then the protocol's own normalization (§3.1:
    /// white space, NFKC, lower case without a locale, so “I” is “i”, never
    /// “ı”). Byte for byte what Us2usKit hashes for a valid name.
    static func normalize(_ input: String) -> String {
        let scalars = input.unicodeScalars
        guard var start = scalars.firstIndex(where: { !$0.properties.isWhitespace }) else { return "" }
        if scalars[start] == "@" { start = scalars.index(after: start) }
        return NormalizedUsername.normalize(String(scalars[start...]))
    }

    /// How the 2.1 field shows what is typed: NFKC and lower case, as the
    /// name will be. Nothing is trimmed, dropped or cut. When that would
    /// stand for another name (a fullwidth “＠” becomes an “@”, which is
    /// then dropped; a spacing accent becomes a space and an accent) the
    /// text is shown as typed.
    static func displayForm(_ typed: String) -> String {
        let shown = typed.precomposedStringWithCompatibilityMapping.lowercased()
        return normalize(shown).unicodeScalars.elementsEqual(normalize(typed).unicodeScalars) ? shown : typed
    }

    /// The length the rules count (and the 2.1 counter shows): Unicode
    /// scalars of the normalized name.
    static func length(_ name: String) -> Int { name.unicodeScalars.count }

    /// Every rule `name` (already normalized, see `normalize`) breaks, in
    /// the order of `Violation.allCases`; empty when it is valid.
    static func violations(_ name: String) -> [Violation] {
        let scalars = Array(name.unicodeScalars)
        var turkish = false
        var other = false
        for (index, scalar) in scalars.enumerated() {
            if turkishLetters.contains(scalar) || (scalar == "\u{0307}" && index > 0 && scalars[index - 1] == "i") {
                turkish = true
            } else if !isAllowed(scalar) {
                other = true
            }
        }
        var found: [Violation] = []
        if turkish { found.append(.turkishCharacters) }
        if other { found.append(.characters) }
        if let first = scalars.first, !isLetter(first), !turkishLetters.contains(first) { found.append(.firstLetter) }
        if !(minLength...maxLength).contains(scalars.count) { found.append(.length) }
        return found
    }

    /// The first rule `name` breaks, or `nil` when it is valid.
    static func violation(_ name: String) -> Violation? { violations(name).first }

    /// Whether `name`, exactly as given, is a valid normalized username.
    static func isValid(_ name: String) -> Bool { violations(name).isEmpty }

    /// Whether `name` is too short — said only once typing pauses.
    static func isTooShort(_ name: String) -> Bool { length(name) < minLength }

    private static func isLetter(_ scalar: Unicode.Scalar) -> Bool { ("a"..."z").contains(scalar) }

    private static func isAllowed(_ scalar: Unicode.Scalar) -> Bool {
        isLetter(scalar) || ("0"..."9").contains(scalar) || scalar == "_"
    }
}
