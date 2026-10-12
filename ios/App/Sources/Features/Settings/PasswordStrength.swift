import Foundation
import Us2usKit

/// A local estimate of how many guesses a backup password takes (7.2).
/// Nothing leaves the device; nothing is stored.
///
/// A backup file can be attacked offline without limit (PROTOCOL.md §5.5),
/// so what counts is what a guesser tries first, not which kinds of
/// characters appear. The password is split into the pieces that are
/// cheapest to guess, and their costs add up:
///
/// * a listed word (`PasswordStrength+Words.swift`: first names, surnames,
///   places, clubs and everyday words, Turkish and English): 11 bits, or 6
///   for the very first guesses (`parola`, `sifre`, `qwerty`…), plus about a
///   bit for capitals and one per leet substitution (`s1fr3`). Turkish
///   letters are folded to ASCII first, so `şifre` is `sifre`;
/// * a year from 1900 to 2039: 7.1 bits; a six- or eight-digit date: about
///   15.5;
/// * a run (`abcd`, `4321`) or a keyboard walk (`qwer`, `1q2w3e`, `zaq1`);
/// * a repeat of something earlier (the second `2026` in `kedi20262026`),
///   or a separator symbol used before (the second `.` in `Elif.Kaya.1990`);
/// * any other character: `log2` of the character pool actually used
///   (26 lower-case, 26 upper-case, 10 digits, 33 symbols, 40 for other
///   letters such as `ğ`).
///
/// So “name + year + symbol” stays weak whatever its length, while random
/// characters and passphrases of several words count in full. Digits alone
/// count at most `log2(10)` per digit, so a six-digit code is always weak,
/// which also keeps the secret code itself out of the backup password. A
/// password with the generator's shape never counts more than the
/// generator's 99.1 bits; the one this screen generated counts exactly that
/// (``generated``).
struct PasswordStrength: Equatable, Sendable {
    enum Level: Int, Comparable, Sendable, CaseIterable {
        /// Zayıf — lit dots `danger`; export is refused.
        case weak
        /// Orta — `accent400`; the minimum for exporting.
        case fair
        /// Güçlü — `online`.
        case strong
        /// Çok güçlü — `online`.
        case veryStrong

        static func < (lhs: Level, rhs: Level) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    /// Estimated entropy in bits: `log2` of the guesses needed.
    let bits: Double

    static let empty = PasswordStrength(bits: 0)

    /// A password from `BackupPassword.generate()`: exactly 20 × log2(31) bits.
    static let generated = PasswordStrength(bits: BackupPassword.generatedEntropyBits)

    var level: Level {
        switch bits {
        case ..<50: .weak
        case ..<60: .fair
        case ..<80: .strong
        default: .veryStrong
        }
    }

    /// The lit share of the dotted meter: 88.3 bits fill it, so 14 random
    /// lower-case letters and digits (72.4 bits) light 82 % — exactly the
    /// 32 dots drawn in 7.2.
    var meterFraction: Double { min(1, max(0, bits / 88.3)) }

    /// The minimum for exporting: 50 bits (“Orta”). Even at the slowest
    /// guessing a backup file allows (PBKDF2, 1,000,000 iterations), that is
    /// decades of GPU time; names and years are far below it.
    var isAcceptable: Bool { level >= .fair }

    static func estimate(_ password: String) -> PasswordStrength {
        guard !password.isEmpty else { return .empty }
        let scalars = Array(password.precomposedStringWithCanonicalMapping.unicodeScalars)
        let classes = CharacterClasses(scalars)
        let characterBits = log2(Double(max(classes.pool, 2)))
        let analysed = Array(scalars.prefix(maxAnalysedLength))
        var bits = cheapestCover(analysed, characterBits: characterBits)
        bits += Double(scalars.count - analysed.count) * characterBits
        if classes.isDigitsOnly {
            bits = min(bits, Double(scalars.count) * log2(10.0))
        }
        if BackupPassword.isGeneratedFormat(password) {
            bits = min(bits, BackupPassword.generatedEntropyBits)
        }
        return PasswordStrength(bits: max(0, bits))
    }
}

// MARK: - Character classes

private struct CharacterClasses {
    var lower = false
    var upper = false
    var digit = false
    var symbol = false
    var other = false

    init(_ scalars: [Unicode.Scalar]) {
        for scalar in scalars {
            switch scalar.value {
            case 0x61...0x7A: lower = true
            case 0x41...0x5A: upper = true
            case 0x30...0x39: digit = true
            case 0x20...0x2F, 0x3A...0x40, 0x5B...0x60, 0x7B...0x7E: symbol = true
            default: other = true
            }
        }
    }

    var pool: Int {
        (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 40 : 0)
    }

    var isDigitsOnly: Bool { digit && !lower && !upper && !symbol && !other }

    static func isSymbol(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x20...0x2F, 0x3A...0x40, 0x5B...0x60, 0x7B...0x7E: true
        default: false
        }
    }
}

// MARK: - Cheapest split

extension PasswordStrength {
    /// Characters past this point are counted one by one (such a password
    /// is far beyond every threshold anyway).
    fileprivate static let maxAnalysedLength = 128
    private static let minPatternLength = 3
    private static let maxPatternLength = 32

    private static let topWordBits = 6.0
    private static let commonWordBits = 11.0
    /// 1900–2039.
    private static let yearBits = log2(140.0)
    /// Day, month and a two-digit year.
    private static let shortDateBits = log2(31.0 * 12 * 100)
    /// Day, month and a year from 1900 to 2039.
    private static let longDateBits = log2(31.0 * 12 * 140)
    private static let separatorRepeatBits = 2.0
    private static let keyboardKeys = 47.0

    /// The cheapest split of `scalars` into pieces, in bits (shortest path
    /// over the positions: each piece is a pattern or one character).
    fileprivate static func cheapestCover(_ scalars: [Unicode.Scalar], characterBits: Double) -> Double {
        let count = scalars.count
        guard count > 0 else { return 0 }
        let lower = scalars.map { lowered($0) }
        let folded = lower.map { asciiFolding[$0] ?? $0 }
        let repeats = earlierRepeatLengths(lower)
        var best = [Double](repeating: .infinity, count: count + 1)
        best[0] = 0
        for end in 1...count {
            let last = end - 1
            var cost = best[last] + characterBits
            if CharacterClasses.isSymbol(scalars[last]), scalars[..<last].contains(scalars[last]) {
                cost = min(cost, best[last] + separatorRepeatBits)
            }
            let earliest = max(0, end - maxPatternLength)
            if end - earliest >= minPatternLength {
                for start in earliest...(end - minPatternLength) {
                    let range = start..<end
                    let pattern = patternBits(
                        scalars[range], lower: lower[range], folded: folded[range], repeatLength: repeats[start])
                    if let pattern {
                        cost = min(cost, best[start] + pattern)
                    }
                }
            }
            best[end] = cost
        }
        return best[count]
    }

    /// The cheapest reading of one piece as a pattern, if it is one.
    private static func patternBits(
        _ original: ArraySlice<Unicode.Scalar>, lower: ArraySlice<Unicode.Scalar>,
        folded: ArraySlice<Unicode.Scalar>, repeatLength: Int
    ) -> Double? {
        var candidates: [Double?] = [
            listedWordBits(original, folded: folded), numberBits(original), runBits(lower), keyboardBits(lower),
        ]
        if repeatLength >= original.count {
            candidates.append(1 + log2(Double(original.count)))
        }
        return candidates.compactMap { $0 }.min()
    }

    // MARK: Words

    private static let longestListedWord = topWords.union(commonWords).map(\.count).max() ?? 0

    private static func listedWordBits(
        _ original: ArraySlice<Unicode.Scalar>, folded: ArraySlice<Unicode.Scalar>
    ) -> Double? {
        guard folded.count <= longestListedWord else { return nil }
        var cheapest: Double?
        for spelling in spellings(folded) {
            let base: Double
            if topWords.contains(spelling.word) {
                base = topWordBits
            } else if commonWords.contains(spelling.word) {
                base = commonWordBits
            } else {
                continue
            }
            let bits = base + capitalizationBits(original) + Double(spelling.substitutions)
            cheapest = min(cheapest ?? bits, bits)
        }
        return cheapest
    }

    /// The piece as written and, if it has any, with its leet characters
    /// read as letters (`1` once as `i` and once as `l`).
    private static func spellings(_ folded: ArraySlice<Unicode.Scalar>) -> [(word: String, substitutions: Int)] {
        var spellings = [(word: String(String.UnicodeScalarView(folded)), substitutions: 0)]
        guard folded.contains(where: { leet[$0] != nil }) else { return spellings }
        for one: Unicode.Scalar in ["i", "l"] {
            var word = String.UnicodeScalarView()
            var substitutions = 0
            for scalar in folded {
                if let letter = leet[scalar] {
                    word.append(scalar == "1" ? one : letter)
                    substitutions += 1
                } else {
                    word.append(scalar)
                }
            }
            spellings.append((word: String(word), substitutions: substitutions))
        }
        return spellings
    }

    private static let leet: [Unicode.Scalar: Unicode.Scalar] = [
        "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s", "!": "i",
    ]

    /// Capitalised or all capitals: 1 bit; any other mix: the ways to place
    /// that many capitals.
    private static func capitalizationBits(_ scalars: ArraySlice<Unicode.Scalar>) -> Double {
        let letters = scalars.filter { $0.properties.isAlphabetic }
        let upper = letters.filter { $0.properties.isUppercase }.count
        let lower = letters.count - upper
        guard upper > 0 else { return 0 }
        if lower == 0 || (upper == 1 && letters.first?.properties.isUppercase == true) { return 1 }
        var ways = 0.0
        for capitals in 1...min(upper, lower) {
            ways += binomial(letters.count, capitals)
        }
        return log2(max(ways, 2))
    }

    private static func binomial(_ n: Int, _ k: Int) -> Double {
        var result = 1.0
        for index in 0..<k {
            result = result * Double(n - index) / Double(index + 1)
        }
        return result
    }

    // MARK: Years and dates

    private static func numberBits(_ scalars: ArraySlice<Unicode.Scalar>) -> Double? {
        guard [4, 6, 8].contains(scalars.count), scalars.allSatisfy({ (0x30...0x39).contains($0.value) }) else {
            return nil
        }
        let digits = scalars.map { Int($0.value) - 0x30 }
        func number(_ range: Range<Int>) -> Int { digits[range].reduce(0) { $0 * 10 + $1 } }
        func isDate(day: Int, month: Int) -> Bool { (1...31).contains(day) && (1...12).contains(month) }
        let years = 1900...2039
        switch digits.count {
        case 4:
            return years.contains(number(0..<4)) ? yearBits : nil
        case 6:
            // ddmmyy, yymmdd, mmddyy
            let isDateLike =
                isDate(day: number(0..<2), month: number(2..<4)) || isDate(day: number(4..<6), month: number(2..<4))
                || isDate(day: number(2..<4), month: number(0..<2))
            return isDateLike ? shortDateBits : nil
        default:
            // ddmmyyyy, yyyymmdd, mmddyyyy
            let isDateLike =
                (isDate(day: number(0..<2), month: number(2..<4)) && years.contains(number(4..<8)))
                || (isDate(day: number(6..<8), month: number(4..<6)) && years.contains(number(0..<4)))
                || (isDate(day: number(2..<4), month: number(0..<2)) && years.contains(number(4..<8)))
            return isDateLike ? longDateBits : nil
        }
    }

    // MARK: Runs and keyboard walks

    /// `abcd`, `dcba`, `1234`, `4321`.
    private static func runBits(_ scalars: ArraySlice<Unicode.Scalar>) -> Double? {
        let values = scalars.map { Int($0.value) }
        let isDigits = values.allSatisfy { (0x30...0x39).contains($0) }
        let isLetters = values.allSatisfy { (0x61...0x7A).contains($0) }
        guard isDigits || isLetters else { return nil }
        let steps = zip(values, values.dropFirst()).map { $1 - $0 }
        let isDescending: Bool
        if steps.allSatisfy({ $0 == 1 }) {
            isDescending = false
        } else if steps.allSatisfy({ $0 == -1 }) {
            isDescending = true
        } else {
            return nil
        }
        return log2(isDigits ? 10.0 : 26.0) + log2(Double(values.count)) + (isDescending ? 1 : 0)
    }

    /// A contiguous stretch of a keyboard row or walk, in either direction.
    private static func keyboardBits(_ scalars: ArraySlice<Unicode.Scalar>) -> Double? {
        guard scalars.allSatisfy({ keyboardKeySet.contains($0) }) else { return nil }
        let forward = Array(scalars)
        let backward = Array(forward.reversed())
        let isWalk = keyboardWalks.contains { walk in
            isStretch(forward, of: walk) || isStretch(backward, of: walk)
        }
        return isWalk ? log2(keyboardKeys) + log2(Double(forward.count)) + 1 : nil
    }

    private static func isStretch(_ needle: [Unicode.Scalar], of walk: [Unicode.Scalar]) -> Bool {
        guard needle.count <= walk.count else { return false }
        return (0...(walk.count - needle.count)).contains { start in
            walk[start..<start + needle.count].elementsEqual(needle)
        }
    }

    /// The rows of the Q (US and Turkish) and Turkish F layouts, and the
    /// usual walks across them.
    private static let keyboardWalks: [[Unicode.Scalar]] = [
        "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç", "fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb",
        "1q2w3e4r5t6y7u8i9o0p", "q1w2e3r4t5y6u7i8o9p0", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
        "zaq1xsw2cde3vfr4bgt5nhy6mju7", "qazwsxedcrfvtgbyhnujmikolp",
    ].map { Array($0.unicodeScalars) }

    private static let keyboardKeySet = Set(keyboardWalks.joined())

    // MARK: Repeats

    /// For each position, the longest stretch starting there that also
    /// starts at an earlier position.
    private static func earlierRepeatLengths(_ scalars: [Unicode.Scalar]) -> [Int] {
        let count = scalars.count
        let width = count + 1
        // common[a * width + i]: the common prefix of the stretches at a and i.
        var common = [Int](repeating: 0, count: width * width)
        var longest = [Int](repeating: 0, count: count)
        for a in stride(from: count - 1, through: 0, by: -1) {
            for i in stride(from: count - 1, to: a, by: -1) where scalars[a] == scalars[i] {
                let length = common[(a + 1) * width + i + 1] + 1
                common[a * width + i] = length
                longest[i] = max(longest[i], length)
            }
        }
        return longest
    }

    // MARK: Case and Turkish letters

    private static func lowered(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        switch scalar.value {
        case 0x41...0x5A:
            return Unicode.Scalar(UInt8(scalar.value + 0x20))
        case 0x130:
            return "i"  // İ
        default:
            let mapping = scalar.properties.lowercaseMapping.unicodeScalars
            if mapping.count == 1, let lower = mapping.first { return lower }
            return scalar
        }
    }

    private static let asciiFolding: [Unicode.Scalar: Unicode.Scalar] = [
        "ç": "c", "ğ": "g", "ı": "i", "ö": "o", "ş": "s", "ü": "u", "â": "a", "î": "i", "û": "u",
    ]
}
