import Foundation
import Testing
import UIKit

@testable import us2us

/// The built-in sticker set (DesignSystem/Stickers): stable wire ids, both
/// languages everywhere, an emoji fallback for every sticker, art for every
/// id, and how a received `Sticker` is drawn (PROTOCOL.md › Stickers).
@Suite("Brand sticker catalog")
struct StickerCatalogTests {
    /// The published wire table. Rows may be appended; a row is never
    /// changed or removed — a retired sticker moves to `retiredIDs`.
    private static let wireTable: [(id: UInt32, pack: String, key: String)] = [
        (1, "us2us.cups", "cups.hello"), (2, "us2us.cups", "cups.together"), (3, "us2us.cups", "cups.laugh"),
        (4, "us2us.cups", "cups.cry"), (5, "us2us.cups", "cups.wow"), (6, "us2us.cups", "cups.love"),
        (7, "us2us.cups", "cups.wink"), (8, "us2us.cups", "cups.think"), (9, "us2us.cups", "cups.hug"),
        (10, "us2us.cups", "cups.tangled"), (11, "us2us.cups", "cups.listen"),
        (101, "us2us.words", "words.hello"), (102, "us2us.words", "words.lineOpen"),
        (103, "us2us.words", "words.hearYou"), (104, "us2us.words", "words.betweenUs"),
        (105, "us2us.words", "words.talkLater"), (106, "us2us.words", "words.missYou"),
        (107, "us2us.words", "words.goodMorning"), (108, "us2us.words", "words.goodNight"),
        (109, "us2us.words", "words.ok"), (110, "us2us.words", "words.thanks"), (111, "us2us.words", "words.haha"),
        (112, "us2us.words", "words.hello.phone"),
        (201, "us2us.privacy", "privacy.lock"), (202, "us2us.privacy", "privacy.ghost"),
        (203, "us2us.privacy", "privacy.key"), (204, "us2us.privacy", "privacy.notLooking"),
        (205, "us2us.privacy", "privacy.noTrace"), (206, "us2us.privacy", "privacy.shh"),
        (207, "us2us.privacy", "privacy.endToEnd"), (208, "us2us.privacy", "privacy.verified"),
        (301, "us2us.mood", "mood.coffee"), (302, "us2us.mood", "mood.onMyWay"), (303, "us2us.mood", "mood.busy"),
        (304, "us2us.mood", "mood.party"), (305, "us2us.mood", "mood.sleepy"), (306, "us2us.mood", "mood.chill"),
        (307, "us2us.mood", "mood.cheers"),
    ]

    private static let all = BrandStickerCatalog.all

    // MARK: Ids

    @Test("ids are unique across every pack and match the published wire table")
    func idsAreUniqueAndStable() {
        #expect(Set(Self.all.map(\.id)).count == Self.all.count)
        #expect(Set(Self.all.map(\.key)).count == Self.all.count)
        #expect(Self.all.count == Self.wireTable.count)
        for (sticker, row) in zip(Self.all, Self.wireTable) {
            #expect(sticker.id == row.id, "\(row.key)")
            #expect(sticker.packID == row.pack, "\(row.key)")
            #expect(sticker.key == row.key, "\(row.id)")
        }
        #expect((32...40).contains(Self.all.count))
    }

    @Test("retired ids are never handed out again, and 0 is never an id")
    func retiredIDsStayRetired() {
        for sticker in Self.all {
            #expect(!BrandStickerCatalog.retiredIDs.contains(sticker.id))
            #expect(sticker.id != 0, "0 is the legacy text sticker's id")
        }
    }

    @Test("pack ids are stable, every sticker is in a pack and every pack is used")
    func packs() {
        #expect(BrandStickerCatalog.packs.map(\.id) == ["us2us.cups", "us2us.words", "us2us.privacy", "us2us.mood"])
        let packIDs = Set(BrandStickerCatalog.packs.map(\.id))
        #expect(!packIDs.contains(BrandStickerCatalog.legacyTextPackID))
        for sticker in Self.all { #expect(packIDs.contains(sticker.packID), "\(sticker.key)") }
        for pack in BrandStickerCatalog.packs {
            #expect(!BrandStickerCatalog.stickers(in: pack.id).isEmpty, "\(pack.id)")
            #expect(BrandStickerCatalog.sticker(id: pack.coverID)?.packID == pack.id, "\(pack.id) cover")
            #expect(pack.titleKey.hasPrefix("sticker.pack."))
        }
    }

    @Test("lookups by id and by pack + id")
    func lookups() {
        #expect(BrandStickerCatalog.sticker(id: 109)?.key == "words.ok")
        #expect(BrandStickerCatalog.sticker(packID: "us2us.words", stickerID: 109)?.key == "words.ok")
        #expect(BrandStickerCatalog.sticker(packID: "us2us.cups", stickerID: 109) == nil)
        #expect(BrandStickerCatalog.sticker(id: 999) == nil)
    }

    // MARK: Strings

    @Test("every sticker has a Turkish and an English label")
    func labels() {
        for sticker in Self.all {
            #expect(!sticker.label.turkish.isEmpty, "\(sticker.key)")
            #expect(!sticker.label.english.isEmpty, "\(sticker.key)")
            #expect(sticker.label(.turkish) == sticker.label.turkish)
            #expect(sticker.label(.english) == sticker.label.english)
        }
    }

    @Test("text stickers have Turkish and English words; wordless ones have none")
    func words() throws {
        let text = Self.all.filter(\.isText)
        #expect(text.count >= 12)
        let wordsHaveWords = BrandStickerCatalog.stickers(in: BrandStickerCatalog.wordsPackID).allSatisfy { $0.isText }
        let cupsHaveNone = BrandStickerCatalog.stickers(in: BrandStickerCatalog.cupsPackID).allSatisfy { !$0.isText }
        #expect(wordsHaveWords)
        #expect(cupsHaveNone)
        for sticker in text {
            let lines = try #require(sticker.lines)
            for language in BrandStickerLanguage.allCases {
                // One or two short lines: legible on a 72 pt picker cell.
                let shown = lines(language)
                let fits = shown.allSatisfy { !$0.isEmpty && $0.count <= 12 }
                #expect((1...2).contains(shown.count), "\(sticker.key) \(language)")
                #expect(fits, "\(sticker.key) \(language)")
                #expect(sticker.text(in: language) == lines(language).joined(separator: " "))
            }
        }
        #expect(BrandStickerCatalog.sticker(id: 101)?.text(in: .turkish) == "Merhaba!")
        #expect(BrandStickerCatalog.sticker(id: 101)?.text(in: .english) == "Hi!")
        #expect(BrandStickerCatalog.sticker(id: 103)?.text(in: .turkish) == "Seni duyuyorum")
        #expect(BrandStickerCatalog.sticker(id: 103)?.text(in: .english) == "I hear you")
    }

    @Test("every sticker has an emoji fallback")
    func emojiFallback() {
        for sticker in Self.all {
            let scalars = sticker.emoji.unicodeScalars
            #expect(!scalars.isEmpty, "\(sticker.key)")
            let isEmoji = scalars.contains { $0.properties.isEmojiPresentation } || scalars.contains("\u{FE0F}")
            let hasLetters = sticker.emoji.contains { $0.isLetter }
            #expect(isEmoji, "\(sticker.key): “\(sticker.emoji)”")
            #expect(!hasLetters, "\(sticker.key)")
        }
    }

    @Test("no empty or padded string anywhere")
    func noEmptyStrings() {
        func check(_ string: String, _ what: String) {
            #expect(!string.isEmpty, "\(what)")
            #expect(string == string.trimmingCharacters(in: .whitespacesAndNewlines), "\(what): “\(string)”")
            #expect(!string.contains("  "), "\(what): “\(string)”")
        }
        for sticker in Self.all {
            check(sticker.packID, "\(sticker.id) pack")
            check(sticker.key, "\(sticker.id) key")
            check(sticker.emoji, "\(sticker.key) emoji")
            check(sticker.label.turkish, "\(sticker.key) label tr")
            check(sticker.label.english, "\(sticker.key) label en")
            for language in BrandStickerLanguage.allCases {
                for line in sticker.lines?(language) ?? [] { check(line, "\(sticker.key) line \(language)") }
            }
        }
        for pack in BrandStickerCatalog.packs {
            check(pack.id, "pack")
            check(pack.titleKey, "\(pack.id) title key")
        }
    }

    // MARK: Art

    @Test("every sticker has art")
    func artwork() {
        for sticker in Self.all {
            #expect(BrandStickerArtwork.artwork(for: sticker.id) != nil, "\(sticker.key)")
        }
        #expect(BrandStickerArtwork.artwork(for: 999) == nil)
    }

    /// The words are fitted by arithmetic: every Doto glyph advances 0.6 em.
    @Test("Doto 900 is monospaced at 0.6 em, as the word fitting assumes")
    func dotoAdvance() {
        let font = U2Fonts.font(.doto, weight: 900, size: 100)
        for word in ["Merhaba!", "Teşekkürler", "İyi geceler", "Şşş"] {
            let width = (word as NSString).size(withAttributes: [.font: font]).width
            #expect(abs(width - CGFloat(word.count) * 60) < 0.5, "\(word)")
        }
        #expect(
            BrandStickerType.fittedSize(
                ["Teşekkürler"], in: CGSize(width: 84, height: 25), maxSize: 22, tracking: -0.04) > 12)
    }

    // MARK: Language

    @Test(
        "Sticker.lang codes",
        arguments: [
            ("tr", BrandStickerLanguage.turkish), ("tr-TR", .turkish), ("TR", .turkish), ("en", .english),
            ("en-GB", .english), ("en_US", .english),
        ])
    func wireCodes(_ code: String, _ language: BrandStickerLanguage) {
        #expect(BrandStickerLanguage(wireCode: code) == language)
    }

    @Test(
        "an empty or unknown Sticker.lang is no language",
        arguments: ["", "de", "t", "-", "turkish", "tr-" + String(repeating: "x", count: 40)])
    func unknownWireCodes(_ code: String) {
        #expect(BrandStickerLanguage(wireCode: code) == nil)
    }

    @Test("the picker's language follows the app's: Turkish for Turkish, English otherwise")
    func appLanguage() {
        #expect(BrandStickerLanguage(locale: Locale(identifier: "tr_TR")) == .turkish)
        #expect(BrandStickerLanguage(locale: Locale(identifier: "en_TR")) == .english)
        #expect(BrandStickerLanguage(locale: Locale(identifier: "de_DE")) == .english)
        #expect(BrandStickerLanguage.turkish.other == .english)
        #expect(BrandStickerLanguage.english.wireCode == "en")
    }

    @Test("a sent sticker carries its language only when it has words")
    func selectionLanguage() throws {
        let words = try #require(BrandStickerCatalog.sticker(id: 101))
        let cup = try #require(BrandStickerCatalog.sticker(id: 1))
        #expect(BrandStickerSelection(words, language: .english).wireLanguage == "en")
        #expect(BrandStickerSelection(words, language: .turkish).wireLanguage == "tr")
        #expect(BrandStickerSelection(cup, language: .english).wireLanguage == "")
        #expect(BrandStickerSelection(cup, language: .english).id == BrandStickerSelection(cup, language: .turkish).id)
        #expect(
            BrandStickerSelection(words, language: .english).id != BrandStickerSelection(words, language: .turkish).id)
    }

    @Test("VoiceOver reads the app's language, and the words as drawn when they are in the other one")
    func accessibilityLabels() throws {
        let hello = try #require(BrandStickerCatalog.sticker(id: 101))
        #expect(hello.accessibilityLabel(shownIn: .turkish, ui: .turkish) == "Merhaba!")
        #expect(hello.accessibilityLabel(shownIn: .turkish, ui: .english) == "Merhaba! (Hi!)")
        let cup = try #require(BrandStickerCatalog.sticker(id: 1))
        #expect(cup.accessibilityLabel(shownIn: .english, ui: .turkish) == "El sallayan bardak")
    }

    // MARK: Receiving

    @Test("a received sticker is drawn in the language it was sent in")
    func resolveKnown() throws {
        let resolution = BrandStickerCatalog.resolve(
            packID: "us2us.words", stickerID: 106, emoji: "🥺", lang: "en", fallback: .turkish)
        let sticker = try #require(BrandStickerCatalog.sticker(id: 106))
        #expect(resolution == .sticker(BrandStickerSelection(sticker, language: .english)))
    }

    @Test("no lang (older senders) or an unknown one: the reader's language")
    func resolveLanguageFallback() throws {
        let sticker = try #require(BrandStickerCatalog.sticker(id: 109))
        #expect(
            BrandStickerCatalog.resolve(packID: "us2us.words", stickerID: 109, emoji: "👌", lang: "", fallback: .turkish)
                == .sticker(BrandStickerSelection(sticker, language: .turkish)))
        #expect(
            BrandStickerCatalog.resolve(
                packID: "us2us.words", stickerID: 109, emoji: "👌", lang: "de", fallback: .english)
                == .sticker(BrandStickerSelection(sticker, language: .english)))
    }

    @Test("an unknown pack or id falls back to its emoji; nothing at all is unavailable")
    func resolveUnknown() {
        #expect(
            BrandStickerCatalog.resolve(
                packID: "us2us.cups", stickerID: 999, emoji: "🫶", lang: "tr", fallback: .turkish) == .emoji("🫶"))
        #expect(
            BrandStickerCatalog.resolve(packID: "us2us.future", stickerID: 1, emoji: "🫶", lang: "", fallback: .turkish)
                == .emoji("🫶"))
        #expect(
            BrandStickerCatalog.resolve(packID: "us2us.words", stickerID: 1, emoji: "👋", lang: "", fallback: .turkish)
                == .emoji("👋"))
        #expect(
            BrandStickerCatalog.resolve(packID: "cb.basic", stickerID: 3, emoji: " ", lang: "", fallback: .turkish)
                == .unavailable)
    }

    @Test("the text stickers of earlier builds keep their Doto card")
    func resolveLegacy() {
        #expect(
            BrandStickerCatalog.resolve(
                packID: "us2us.text", stickerID: 0, emoji: "tamam!", lang: "", fallback: .english)
                == .legacyText("tamam!"))
        #expect(
            BrandStickerCatalog.resolve(packID: "us2us.text", stickerID: 0, emoji: "", lang: "", fallback: .english)
                == .unavailable)
    }
}
