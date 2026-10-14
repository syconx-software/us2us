import Foundation

// The built-in stickers as messages: the content a sent sticker becomes, what
// a received one resolves to, a one-line summary for quotes, and the
// picker's recents (ids only).

extension BrandStickerSelection {
    /// The sticker as it is sent: `Sticker { pack_id, sticker_id, emoji,
    /// lang }` — `lang` only for a sticker with words.
    var messageContent: MessageContent {
        .sticker(packID: sticker.packID, stickerID: sticker.id, emoji: sticker.emoji, lang: wireLanguage)
    }
}

extension MessageContent {
    /// What a sticker message draws for a reader whose app language is
    /// `ui` (PROTOCOL §8.8); `nil` for anything that is not a sticker.
    func stickerResolution(ui: BrandStickerLanguage) -> BrandStickerResolution? {
        switch self {
        case .sticker(let packID, let stickerID, let emoji, let lang):
            BrandStickerCatalog.resolve(packID: packID, stickerID: stickerID, emoji: emoji, lang: lang, fallback: ui)
        case .textSticker(let text):
            .legacyText(text)
        default:
            nil
        }
    }
}

extension BrandStickerResolution {
    /// One line for a reply's quote: the words as drawn (a sticker with
    /// words), else the emoji and what the sticker shows in the app's
    /// language; `nil` when there is nothing to say (the caller says
    /// “Sticker”).
    func summary(ui: BrandStickerLanguage) -> String? {
        switch self {
        case .sticker(let selection):
            let sticker = selection.sticker
            let words = sticker.text(in: selection.language) ?? sticker.label(ui)
            return "\(sticker.emoji) \(words)"
        case .legacyText(let text):
            return text
        case .emoji(let emoji):
            return emoji
        case .unavailable:
            return nil
        }
    }
}

/// The picker's “Son kullanılanlar”: the built-in stickers last sent, newest
/// first, without repeats, at most `limit`. Kept as ids only
/// (`BrandStickerSelection.id`: “101.tr”, “1.-”) — which sticker, in which
/// language, never who it went to or when. The live service keeps the list
/// in the vault's encrypted runtime state (never in UserDefaults); the
/// fixtures keep it in memory.
enum BrandStickerRecents {
    static let limit = 12

    /// `selection` first, an earlier copy of it removed, cut to `limit`.
    static func adding(_ selection: BrandStickerSelection, to ids: [String]) -> [String] {
        Array(([selection.id] + ids.filter { $0 != selection.id }).prefix(limit))
    }

    /// The stickers behind stored ids, in order; ids this build does not
    /// know (a retired sticker, a damaged list) and repeats are skipped.
    /// A wordless sticker takes `ui`, which changes nothing it shows.
    static func selections(from ids: [String], ui: BrandStickerLanguage) -> [BrandStickerSelection] {
        var seen = Set<String>()
        var selections: [BrandStickerSelection] = []
        for id in ids {
            guard let selection = selection(id: id, ui: ui), seen.insert(selection.id).inserted else { continue }
            selections.append(selection)
            if selections.count == limit { break }
        }
        return selections
    }

    /// “101.tr” → words.hello in Turkish; “1.-” → cups.hello.
    static func selection(id: String, ui: BrandStickerLanguage) -> BrandStickerSelection? {
        let parts = id.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2, let number = UInt32(parts[0]), let sticker = BrandStickerCatalog.sticker(id: number)
        else { return nil }
        let language = BrandStickerLanguage(wireCode: String(parts[1])) ?? ui
        return BrandStickerSelection(sticker, language: language)
    }
}
