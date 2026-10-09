import Foundation
import SwiftUI

/// The in-app language setting (7.1 › Dil). Turkish first, English second.
enum AppLanguage: String, CaseIterable, Codable, Sendable, Identifiable {
    /// Follow the device: Turkish or English, whichever the user prefers
    /// first; English for any other device language.
    case system
    case turkish = "tr"
    case english = "en"

    var id: String { rawValue }

    /// The locale the whole UI uses (`\.locale`): strings, upper-casing,
    /// dates and numbers. Keeps the device's region for formatting.
    func resolvedLocale(
        preferredLanguages: [String] = Locale.preferredLanguages,
        current: Locale = .current
    ) -> Locale {
        let code: String
        switch self {
        case .turkish: code = "tr"
        case .english: code = "en"
        case .system:
            code =
                preferredLanguages
                .compactMap { Locale(identifier: $0).language.languageCode?.identifier }
                .first { $0 == "tr" || $0 == "en" } ?? "en"
        }
        if let region = current.region?.identifier {
            return Locale(identifier: "\(code)_\(region)")
        }
        return Locale(identifier: code)
    }
}

/// String lookup in an explicit language — for strings built outside a
/// `Text` (view models, UIKit, accessibility). `Text("key")` already resolves
/// through the environment's `\.locale`, which the root view sets.
enum L10n {
    /// The localized value of `key` (Localizable.xcstrings) in `locale`'s language.
    static func string(_ key: String.LocalizationValue, locale: Locale, table: String? = nil) -> String {
        String(localized: LocalizedStringResource(key, table: table, locale: locale))
    }

    /// Upper-cases with an explicit language — never without one (inventory
    /// 9.3: “istekler” → “İSTEKLER” in Turkish, not “ISTEKLER”).
    static func uppercased(_ text: String, locale: Locale) -> String {
        text.uppercased(with: locale)
    }

    /// “deniz’in” / “deniz’s” — the possessive of a username in a sentence
    /// (6.2 “deniz’in telefonundaki kodu okut”).
    static func possessive(_ name: String, locale: Locale) -> String {
        locale.language.languageCode?.identifier == "tr" ? TurkishSuffix.genitive(name) : name + "’s"
    }
}

/// Turkish case suffixes for usernames inside sentences, with the
/// typographic apostrophe the design uses. Vowel harmony follows the last
/// vowel *sound*: a final digit is read as its Turkish number word
/// (“kaan2” → “iki”), and a buffer consonant follows a final vowel.
enum TurkishSuffix {
    private static let vowels: Set<Character> = ["a", "e", "ı", "i", "o", "ö", "u", "ü"]
    /// Digits as spoken: (last vowel, ends with a vowel).
    private static let digits: [Character: (Character, Bool)] = [
        "0": ("ı", false), "1": ("i", false), "2": ("i", true), "3": ("ü", false), "4": ("ü", false),
        "5": ("i", false), "6": ("ı", true), "7": ("i", true), "8": ("i", false), "9": ("u", false),
    ]

    /// The last vowel heard and whether the name ends in a vowel sound.
    private static func sound(of name: String) -> (lastVowel: Character, endsWithVowel: Bool) {
        let lower = name.lowercased(with: Locale(identifier: "tr_TR"))
        let lastSound = lower.last { $0.isLetter || $0.isNumber }
        if let lastSound, let spoken = digits[lastSound] { return spoken }
        let lastVowel = lower.last(where: vowels.contains) ?? "e"
        return (lastVowel, lastSound.map(vowels.contains) ?? false)
    }

    /// The dative (`-e/-a`, `-ye/-ya` after a vowel): “deniz’e”, “ayla_’ya”, “kaan’a”.
    static func dative(_ name: String) -> String {
        let (vowel, endsWithVowel) = sound(of: name)
        let front = ["e", "i", "ö", "ü"].contains(vowel)
        return name + "’" + (endsWithVowel ? "y" : "") + (front ? "e" : "a")
    }

    /// The genitive (`-in/-ın/-un/-ün`, `-nin…` after a vowel): “deniz’in”,
    /// “ayla_’nın”, “mert.k’in”, “kaan2’nin”.
    static func genitive(_ name: String) -> String {
        let (vowel, endsWithVowel) = sound(of: name)
        let harmony: Character =
            switch vowel {
            case "a", "ı": "ı"
            case "o", "u": "u"
            case "ö", "ü": "ü"
            default: "i"
            }
        return name + "’" + (endsWithVowel ? "n" : "") + String(harmony) + "n"
    }
}
