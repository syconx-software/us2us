import Foundation

/// The language a sticker's words are drawn in. A sent sticker carries the
/// language it was shown in (`Sticker.lang`, BCP-47 “tr” / “en”) and the
/// recipient draws exactly that language, so both ends see the same sticker.
enum BrandStickerLanguage: String, CaseIterable, Codable, Sendable, Hashable, Identifiable {
    case turkish = "tr"
    case english = "en"

    var id: String { rawValue }

    /// The BCP-47 tag sent in `Sticker.lang`.
    var wireCode: String { rawValue }

    /// The other one — the picker's TR/EN switch.
    var other: BrandStickerLanguage { self == .turkish ? .english : .turkish }

    /// The app's language (`\.locale`, 7.1 › Dil): Turkish for Turkish,
    /// English otherwise — the same rule as `AppLanguage`.
    init(locale: Locale) {
        self = locale.language.languageCode?.identifier == "tr" ? .turkish : .english
    }

    /// A received `Sticker.lang`: the primary subtag of a BCP-47 tag
    /// (“tr”, “tr-TR”, “EN_gb” …). Nil for an empty, unknown or over-long
    /// (> 35 characters) value.
    init?(wireCode: String) {
        guard wireCode.count <= 35 else { return nil }
        let primary = wireCode.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map { $0.lowercased() }
        switch primary {
        case "tr": self = .turkish
        case "en": self = .english
        default: return nil
        }
    }
}

/// A Turkish + English pair (labels, pack titles).
struct BrandStickerText: Sendable, Hashable {
    let turkish: String
    let english: String

    init(tr: String, en: String) {
        turkish = tr
        english = en
    }

    func callAsFunction(_ language: BrandStickerLanguage) -> String {
        language == .turkish ? turkish : english
    }
}

/// The words of a text sticker, as drawn: one string per line. The two
/// languages are written separately — a warm phrase in each, not a
/// translation — and break where the phrase reads best.
struct BrandStickerLines: Sendable, Hashable {
    let turkish: [String]
    let english: [String]

    init(tr: [String], en: [String]) {
        turkish = tr
        english = en
    }

    func callAsFunction(_ language: BrandStickerLanguage) -> [String] {
        language == .turkish ? turkish : english
    }
}

/// A pack of the built-in set (`Sticker.pack_id`).
struct BrandStickerPack: Sendable, Hashable, Identifiable {
    /// The stable wire id (“us2us.cups”). Never renamed.
    let id: String
    /// Its title in the Conversation table (the picker's pack tab).
    let titleKey: String
    /// The sticker drawn on the pack's tab.
    let coverID: UInt32
}

/// One sticker of the built-in set. Stickers ship inside the app: the relay
/// never sees which one was sent, only the E2EE message carrying
/// `Sticker { pack_id, sticker_id, emoji, lang }`.
struct BrandSticker: Sendable, Hashable, Identifiable {
    /// The stable wire id (`Sticker.sticker_id`). Unique across every pack
    /// and never reused, also after a sticker is retired.
    let id: UInt32
    let packID: String
    /// A readable name for code and docs (“cups.hello”). Not sent.
    let key: String
    /// `Sticker.emoji`: what a client that does not know the sticker shows.
    let emoji: String
    /// What VoiceOver reads, in the app's language.
    let label: BrandStickerText
    /// The words drawn on a text sticker; nil for a wordless one.
    let lines: BrandStickerLines?

    var isText: Bool { lines != nil }

    /// The words on one line each (“Seni duyuyorum”).
    func text(in language: BrandStickerLanguage) -> String? {
        lines.map { $0(language).joined(separator: " ") }
    }

    /// The VoiceOver label in the app's language (`ui`). A text sticker
    /// shown in the other language is read with its words as drawn, then
    /// what it means in the app's language: “Merhaba! (Hi!)”.
    func accessibilityLabel(shownIn shown: BrandStickerLanguage, ui: BrandStickerLanguage) -> String {
        guard let text = text(in: shown), shown != ui else { return label(ui) }
        return "\(text) (\(label(ui)))"
    }
}

/// A sticker as chosen in the picker: the sticker and the language its
/// words were shown in (recents, sending, the long-press preview).
struct BrandStickerSelection: Sendable, Hashable, Identifiable {
    let sticker: BrandSticker
    let language: BrandStickerLanguage

    init(_ sticker: BrandSticker, language: BrandStickerLanguage) {
        self.sticker = sticker
        self.language = language
    }

    var id: String { "\(sticker.id).\(sticker.isText ? language.wireCode : "-")" }

    /// `Sticker.lang` to send: only a sticker with words has a language.
    var wireLanguage: String { sticker.isText ? language.wireCode : "" }
}

/// What to draw for a received `Sticker` (PROTOCOL.md › Stickers).
enum BrandStickerResolution: Sendable, Hashable {
    /// A sticker of this catalog, in the language to draw it in.
    case sticker(BrandStickerSelection)
    /// The text sticker of builds before the catalog (`pack_id`
    /// “us2us.text”, the words in `emoji`): the Doto card (`TextStickerMessage`).
    case legacyText(String)
    /// A pack or id this build does not know: its emoji, drawn large.
    case emoji(String)
    /// Nothing to draw (no known sticker and no emoji).
    case unavailable
}

/// The built-in us2us sticker set: every sticker is drawn in code
/// (`BrandStickerView`), so nothing is downloaded and the relay never sees a
/// sticker. Ids are stable wire values: append new stickers with new ids,
/// and move a removed sticker's id to `retiredIDs` instead of reusing it.
enum BrandStickerCatalog {
    /// The pack id of the plain text stickers sent before the catalog.
    static let legacyTextPackID = "us2us.text"

    static let cupsPackID = "us2us.cups"
    static let wordsPackID = "us2us.words"
    static let privacyPackID = "us2us.privacy"
    static let moodPackID = "us2us.mood"

    static let packs: [BrandStickerPack] = [
        BrandStickerPack(id: cupsPackID, titleKey: "sticker.pack.cups", coverID: 1),
        BrandStickerPack(id: wordsPackID, titleKey: "sticker.pack.words", coverID: 101),
        BrandStickerPack(id: privacyPackID, titleKey: "sticker.pack.privacy", coverID: 202),
        BrandStickerPack(id: moodPackID, titleKey: "sticker.pack.mood", coverID: 301),
    ]

    /// Ids that were used once and must never be handed out again.
    static let retiredIDs: Set<UInt32> = []

    static let all: [BrandSticker] = [
        // MARK: Cups — the two-cup mascot
        art(1, cupsPackID, "cups.hello", "👋", tr: "El sallayan bardak", en: "Cup waving hello"),
        art(
            2, cupsPackID, "cups.together", "🥰", tr: "İpi kalp olmuş iki bardak",
            en: "Two cups, one heart-shaped string"),
        art(3, cupsPackID, "cups.laugh", "😂", tr: "Kahkaha atan bardak", en: "Cup laughing out loud"),
        art(4, cupsPackID, "cups.cry", "😭", tr: "Hüngür hüngür ağlayan bardak", en: "Cup crying its eyes out"),
        art(5, cupsPackID, "cups.wow", "😮", tr: "Şaşkın bardak", en: "Astonished cup"),
        art(6, cupsPackID, "cups.love", "😍", tr: "Kalp gözlü bardak", en: "Cup with heart eyes"),
        art(7, cupsPackID, "cups.wink", "😉", tr: "Göz kırpan bardak", en: "Winking cup"),
        art(8, cupsPackID, "cups.think", "🤔", tr: "Düşünceli bardak", en: "Cup deep in thought"),
        art(9, cupsPackID, "cups.hug", "🤗", tr: "Sarılan iki bardak", en: "Two cups hugging"),
        art(10, cupsPackID, "cups.tangled", "😵‍💫", tr: "İpine dolanmış bardak", en: "Cup tangled in its string"),
        art(11, cupsPackID, "cups.listen", "👂", tr: "Kulak kesilmiş bardak", en: "Cup all ears"),

        // MARK: Words — Doto on brand shapes
        words(101, "words.hello", "👋", tr: ["Merhaba!"], en: ["Hi!"]),
        words(102, "words.lineOpen", "🟢", tr: ["Hat açık"], en: ["Line's", "open"]),
        words(103, "words.hearYou", "🫂", tr: ["Seni", "duyuyorum"], en: ["I hear", "you"]),
        words(104, "words.betweenUs", "🤐", tr: ["Aramızda", "kalsın"], en: ["Just", "between us"]),
        words(105, "words.talkLater", "🕒", tr: ["Sonra", "konuşuruz"], en: ["Talk", "later"]),
        words(106, "words.missYou", "🥺", tr: ["Özledim"], en: ["Miss you"]),
        words(107, "words.goodMorning", "☀️", tr: ["Günaydın"], en: ["Good", "morning"]),
        words(108, "words.goodNight", "🌙", tr: ["İyi", "geceler"], en: ["Good", "night"]),
        words(109, "words.ok", "👌", tr: ["Tamam!"], en: ["OK!"]),
        words(110, "words.thanks", "🙏", tr: ["Teşekkürler"], en: ["Thank you"]),
        words(111, "words.haha", "😆", tr: ["Haha"], en: ["Haha"]),
        words(112, "words.hello.phone", "📞", tr: ["Alo?"], en: ["Hello?"]),

        // MARK: Privacy — locks, ghosts, keys, no trace
        art(201, privacyPackID, "privacy.lock", "🔒", tr: "Gülümseyen asma kilit", en: "Smiling padlock"),
        art(202, privacyPackID, "privacy.ghost", "👻", tr: "Hayalet bardak", en: "Ghost cup"),
        art(203, privacyPackID, "privacy.key", "🔑", tr: "Anahtar sende", en: "You hold the key"),
        art(204, privacyPackID, "privacy.notLooking", "🙈", tr: "Gözlerini kapatan bardak", en: "Cup covering its eyes"),
        BrandSticker(
            id: 205, packID: privacyPackID, key: "privacy.noTrace", emoji: "🫥",
            label: BrandStickerText(tr: "İz yok", en: "No trace"),
            lines: BrandStickerLines(tr: ["İz yok"], en: ["No", "trace"])),
        BrandSticker(
            id: 206, packID: privacyPackID, key: "privacy.shh", emoji: "🤫",
            label: BrandStickerText(tr: "Parmağı dudağında bardak: Şşş", en: "Cup with a finger to its lips: Shh"),
            lines: BrandStickerLines(tr: ["Şşş"], en: ["Shh"])),
        art(207, privacyPackID, "privacy.endToEnd", "🔐", tr: "Uçtan uca şifreli hat", en: "End-to-end encrypted line"),
        art(208, privacyPackID, "privacy.verified", "✅", tr: "Doğrulama mührü", en: "Verified seal"),

        // MARK: Mood — what the cup is up to
        art(301, moodPackID, "mood.coffee", "☕", tr: "Kahve molasındaki bardak", en: "Cup on a coffee break"),
        BrandSticker(
            id: 302, packID: moodPackID, key: "mood.onMyWay", emoji: "🏃",
            label: BrandStickerText(tr: "Koşan bardak: Yoldayım!", en: "Running cup: On my way!"),
            lines: BrandStickerLines(tr: ["Yoldayım!"], en: ["On my way!"])),
        BrandSticker(
            id: 303, packID: moodPackID, key: "mood.busy", emoji: "⏳",
            label: BrandStickerText(tr: "“Meşgul” tabelalı bardak", en: "Cup with a “Busy” sign"),
            lines: BrandStickerLines(tr: ["Meşgul"], en: ["Busy"])),
        art(304, moodPackID, "mood.party", "🎉", tr: "Parti şapkalı bardak", en: "Cup in a party hat"),
        art(305, moodPackID, "mood.sleepy", "😴", tr: "Uykulu bardak", en: "Sleepy cup"),
        art(306, moodPackID, "mood.chill", "😎", tr: "Güneş gözlüklü havalı bardak", en: "Cool cup in sunglasses"),
        art(307, moodPackID, "mood.cheers", "🥂", tr: "Kadeh tokuşturan iki bardak", en: "Two cups clinking, cheers"),
    ]

    private static let byID: [UInt32: BrandSticker] = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })

    static func sticker(id: UInt32) -> BrandSticker? { byID[id] }

    /// The sticker for a received `pack_id` / `sticker_id` pair.
    static func sticker(packID: String, stickerID: UInt32) -> BrandSticker? {
        guard let sticker = byID[stickerID], sticker.packID == packID else { return nil }
        return sticker
    }

    static func pack(id: String) -> BrandStickerPack? { packs.first { $0.id == id } }

    static func stickers(in packID: String) -> [BrandSticker] { all.filter { $0.packID == packID } }

    /// What to draw for a received `Sticker` (PROTOCOL.md › Stickers):
    ///
    /// * a known sticker in a known `lang` → that sticker in that language;
    /// * a known sticker without `lang` (older senders) or in a language this
    ///   build has no words for → the reader's own language (`fallback`), the
    ///   other of the two;
    /// * `pack_id` “us2us.text” → the legacy Doto card with `emoji` as its words;
    /// * an unknown pack or id → the emoji, drawn large;
    /// * nothing known and no emoji → `.unavailable`.
    static func resolve(
        packID: String, stickerID: UInt32, emoji: String, lang: String, fallback: BrandStickerLanguage
    ) -> BrandStickerResolution {
        if let sticker = sticker(packID: packID, stickerID: stickerID) {
            let language = BrandStickerLanguage(wireCode: lang) ?? fallback
            return .sticker(BrandStickerSelection(sticker, language: language))
        }
        let trimmed = emoji.trimmingCharacters(in: .whitespacesAndNewlines)
        if packID == legacyTextPackID, !trimmed.isEmpty { return .legacyText(trimmed) }
        return trimmed.isEmpty ? .unavailable : .emoji(trimmed)
    }

    // MARK: Builders

    private static func art(
        _ id: UInt32, _ packID: String, _ key: String, _ emoji: String, tr: String, en: String
    ) -> BrandSticker {
        BrandSticker(
            id: id, packID: packID, key: key, emoji: emoji, label: BrandStickerText(tr: tr, en: en), lines: nil)
    }

    /// A Words sticker: its label is its phrase.
    private static func words(_ id: UInt32, _ key: String, _ emoji: String, tr: [String], en: [String]) -> BrandSticker
    {
        BrandSticker(
            id: id, packID: wordsPackID, key: key, emoji: emoji,
            label: BrandStickerText(tr: tr.joined(separator: " "), en: en.joined(separator: " ")),
            lines: BrandStickerLines(tr: tr, en: en))
    }
}
