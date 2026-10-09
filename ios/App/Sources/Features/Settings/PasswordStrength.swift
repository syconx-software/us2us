import Foundation

/// A local, zxcvbn-like estimate of a backup password's strength (7.2).
/// Nothing leaves the device; nothing is stored.
///
/// The estimate is the entropy of the characters actually used, reduced for
/// repeats (`aaaa`), runs (`abcd`, `4321`), keyboard rows and well-known
/// fragments (`parola`, `qwerty`, `1234`…). Digits-only passwords are
/// counted at log2(10) per digit, so a six-digit code is always weak — which
/// also keeps the secret code itself out of the backup password.
struct PasswordStrength: Equatable, Sendable {
    enum Level: Int, Comparable, Sendable, CaseIterable {
        /// Zayıf — lit dots `danger`; export is refused.
        case weak
        /// Orta — `accent400`.
        case fair
        /// Güçlü — `online`.
        case strong
        /// Çok güçlü — `online`.
        case veryStrong

        static func < (lhs: Level, rhs: Level) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    /// Estimated entropy in bits.
    let bits: Double

    static let empty = PasswordStrength(bits: 0)

    var level: Level {
        switch bits {
        case ..<45: .weak
        case ..<60: .fair
        case ..<80: .strong
        default: .veryStrong
        }
    }

    /// The lit share of the dotted meter: 88.3 bits fill it, so a
    /// 14-character lower-case + digit password (72.4 bits) lights 82 % —
    /// exactly the 32 dots drawn in 7.2.
    var meterFraction: Double { min(1, max(0, bits / 88.3)) }

    /// The minimum for exporting.
    var isAcceptable: Bool { level >= .fair }

    static func estimate(_ password: String) -> PasswordStrength {
        guard !password.isEmpty else { return .empty }
        var hasLower = false, hasUpper = false, hasDigit = false, hasSymbol = false, hasOther = false
        for scalar in password.unicodeScalars {
            switch scalar.value {
            case 0x61...0x7A: hasLower = true
            case 0x41...0x5A: hasUpper = true
            case 0x30...0x39: hasDigit = true
            case 0x21...0x2F, 0x3A...0x40, 0x5B...0x60, 0x7B...0x7E, 0x20: hasSymbol = true
            default: hasOther = true
            }
        }
        var pool = 0
        if hasLower { pool += 26 }
        if hasUpper { pool += 26 }
        if hasDigit { pool += 10 }
        if hasSymbol { pool += 33 }
        if hasOther { pool += 40 }

        let characters = Array(password.lowercased().unicodeScalars.map(\.value))
        var effectiveLength = 0.0
        for index in characters.indices {
            if index > 0, characters[index] == characters[index - 1] {
                effectiveLength += 0.25  // repeat
                continue
            }
            if index > 1 {
                let a = Int(characters[index - 2]), b = Int(characters[index - 1]), c = Int(characters[index])
                if b - a == c - b, abs(c - b) == 1 {
                    effectiveLength += 0.3  // run
                    continue
                }
            }
            effectiveLength += 1
        }
        var bits = effectiveLength * log2(Double(max(pool, 2)))
        let lowered = password.lowercased()
        for fragment in weakFragments where lowered.contains(fragment) {
            // A known fragment is worth about one guess per character, not a full character each.
            bits -= Double(fragment.count) * (log2(Double(max(pool, 2))) - 1)
        }
        if hasDigit && !hasLower && !hasUpper && !hasSymbol && !hasOther {
            bits = min(bits, Double(password.count) * log2(10))
        }
        return PasswordStrength(bits: max(0, bits))
    }

    /// Fragments every guesser tries first (Turkish and English).
    private static let weakFragments: [String] = [
        "password", "parola", "sifre", "şifre", "qwerty", "asdf", "zxcv", "123456", "1234", "abcd",
        "admin", "iloveyou", "seviyorum", "sevgilim", "us2us", "letmein", "welcome", "hosgeldin",
        "galatasaray", "fenerbahce", "besiktas", "trabzon", "istanbul", "ankara", "izmir", "turkiye",
    ]
}
