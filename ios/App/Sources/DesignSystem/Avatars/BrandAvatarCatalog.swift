import SwiftUI

// The preset profile avatars: the sticker set's cup mascot in a circle, for
// people who would rather not upload a photo. Every preset is drawn in code
// (`BrandAvatarView`) from the same mascot, inks and pen as the stickers.

/// What the cup's face shows — the sticker set's own expressions.
enum BrandAvatarExpression: String, Sendable, Hashable, CaseIterable {
    /// Round eyes, a small smile: the mascot as on the app icon.
    case smile
    /// Round eyes, an open grin.
    case grin
    /// Closed smiling eyes (^ ^).
    case happy
    /// Closed smiling eyes and a grin.
    case joy
    /// Squeezed shut, laughing.
    case laugh
    /// A wink and the tip of the tongue.
    case wink
    /// Asleep.
    case sleepy
    /// Sunglasses and a smirk.
    case cool
    /// Wide eyes looking up, brows raised.
    case curious
    /// Heart eyes.
    case love
    /// Round eyes looking aside, a smirk.
    case smirk
    /// The ghost's hollow eyes and “o”.
    case ghost

    var face: StickerFace {
        switch self {
        case .smile: StickerFace(eyes: .dots(), mouth: .smile)
        case .grin: StickerFace(eyes: .dots(), mouth: .grin)
        case .happy: StickerFace(eyes: .happy, mouth: .smile)
        case .joy: StickerFace(eyes: .happy, mouth: .grin)
        case .laugh: StickerFace(eyes: .squeezed, mouth: .laugh)
        case .wink: StickerFace(eyes: .wink, mouth: .tongue)
        case .sleepy: StickerFace(eyes: .sleepy, mouth: .smallO)
        case .cool: StickerFace(eyes: .sunglasses, mouth: .smirk, blush: false)
        case .curious: StickerFace(eyes: .wide(look: CGVector(dx: 0.9, dy: -1.1)), mouth: .smallO, brows: .raised)
        case .love: StickerFace(eyes: .hearts, mouth: .grin)
        case .smirk: StickerFace(eyes: .dots(look: CGVector(dx: 1.3, dy: 0)), mouth: .smirk)
        case .ghost: StickerFace(eyes: .hollow, mouth: .o)
        }
    }
}

/// What the cup wears or holds. Each one changes the silhouette, so presets
/// stay apart at 28 pt.
enum BrandAvatarAccessory: String, Sendable, Hashable, CaseIterable {
    case none
    case headphones
    case beanie
    /// Round glasses: two rings and a bar — the line mark of the app icon.
    case glasses
    case flower
    case cap
    case scarf
    /// A sheet over the cup, hem and all.
    case ghostSheet
    case partyHat
    case nightcap
    /// The string tied into a heart beside the cup.
    case stringHeart
    case crown
    case bow
    /// A coffee sleeve and steam.
    case coffee
    case catEars
    case chefHat
    case antennae
}

/// The disc behind the cup: Nocturne's accent and neutral ramps, plus four
/// quiet accents in the same lightness (deep in the dark theme, pale in the
/// light one), so a lilac cup with an ink outline reads on every one.
enum BrandAvatarBackdrop: String, Sendable, Hashable, CaseIterable {
    /// `accent800` — the initials avatar's palette A.
    case violet
    /// `accent700` — palette B.
    case plum
    /// `accent900` — palette D, the user's own.
    case ink
    /// `neutral800` — palette C.
    case slate
    case rose
    case mint
    case sky
    case sand

    var token: U2ThemedColor {
        switch self {
        case .violet: U2ColorTokens.accent800
        case .plum: U2ColorTokens.accent700
        case .ink: U2ColorTokens.accent900
        case .slate: U2ColorTokens.neutral800
        // Deep: oklch ≈ 0.39 · 0.07 at the hue; pale: ≈ 0.93 · 0.03.
        case .rose: U2ThemedColor(dark: 0x6b3a50, light: 0xf6d9dd)
        case .mint: U2ThemedColor(dark: 0x24564d, light: 0xd1eee1)
        case .sky: U2ThemedColor(dark: 0x2e4a76, light: 0xd5e3fa)
        case .sand: U2ThemedColor(dark: 0x5d4d34, light: 0xf1e3c6)
        }
    }

    /// The disc's colour in a theme.
    func color(for scheme: ColorScheme) -> Color {
        let light = scheme == .light
        return BrandAvatarInk.color(
            hex: light ? token.light : token.dark, alpha: light ? token.lightAlpha : token.darkAlpha)
    }

    /// Pale enough in the light theme to need a hairline on `bg`.
    var needsLightHairline: Bool { self == .ink || self == .violet || self == .slate }
}

/// Which of the two cups of the line: the mascot, or its lighter twin.
enum BrandAvatarCup: String, Sendable, Hashable, CaseIterable {
    case mascot
    case twin

    var style: CupStyle { self == .mascot ? .mid : .light }
}

/// One preset avatar. Its id is what an `AvatarChoice` stores (and carries
/// wherever the choice goes), so it is a stable value: append new presets
/// with new ids, and move a removed preset's id to `retiredIDs` instead of
/// reusing it.
struct BrandAvatarPreset: Sendable, Identifiable {
    typealias ID = UInt32

    let id: ID
    /// A readable name for code and docs (“cup.headphones”). Not stored.
    let key: String
    /// What VoiceOver reads in the picker, in the app's language.
    let label: BrandStickerText
    let backdrop: BrandAvatarBackdrop
    let expression: BrandAvatarExpression
    let accessory: BrandAvatarAccessory
    var cup: BrandAvatarCup = .mascot
}

extension BrandAvatarPreset: Hashable {
    static func == (lhs: BrandAvatarPreset, rhs: BrandAvatarPreset) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

enum BrandAvatarCatalog {
    /// Ids that were used once and must never be handed out again.
    static let retiredIDs: Set<BrandAvatarPreset.ID> = []

    /// In the picker's order.
    static let all: [BrandAvatarPreset] = [
        preset(1, "cup.classic", .violet, .smile, .none, tr: "Klasik bardak", en: "The classic cup"),
        preset(2, "cup.headphones", .mint, .happy, .headphones, tr: "Kulaklıklı bardak", en: "Cup with headphones"),
        preset(3, "cup.beanie", .sky, .grin, .beanie, tr: "Bereli bardak", en: "Cup in a beanie"),
        preset(4, "cup.glasses", .plum, .smile, .glasses, tr: "Gözlüklü bardak", en: "Cup with round glasses"),
        preset(5, "cup.flower", .rose, .happy, .flower, tr: "Çiçekli bardak", en: "Cup with a flower"),
        preset(6, "cup.cap", .sand, .smirk, .cap, tr: "Şapkalı bardak", en: "Cup in a cap"),
        preset(7, "cup.scarf", .slate, .joy, .scarf, tr: "Atkılı bardak", en: "Cup in a scarf"),
        preset(8, "cup.ghost", .ink, .ghost, .ghostSheet, tr: "Hayalet bardak", en: "Ghost cup"),
        preset(9, "cup.cool", .sky, .cool, .none, tr: "Güneş gözlüklü bardak", en: "Cup in sunglasses"),
        preset(10, "cup.party", .violet, .laugh, .partyHat, tr: "Parti şapkalı bardak", en: "Cup in a party hat"),
        preset(
            11, "cup.stringHeart", .rose, .love, .stringHeart, tr: "İpi kalp olmuş bardak",
            en: "Cup with a string heart"),
        preset(12, "cup.sleepy", .sky, .sleepy, .nightcap, tr: "Uykucu bardak", en: "Sleepy cup in a nightcap"),
        preset(13, "cup.wink", .plum, .wink, .none, tr: "Göz kırpan bardak", en: "Winking cup"),
        preset(14, "cup.crown", .mint, .grin, .crown, tr: "Taçlı bardak", en: "Cup with a crown"),
        preset(15, "cup.bow", .plum, .happy, .bow, tr: "Fiyonklu bardak", en: "Cup with a bow"),
        preset(16, "cup.coffee", .sand, .happy, .coffee, tr: "Kahve bardağı", en: "Coffee cup"),
        preset(17, "cup.curious", .ink, .curious, .none, tr: "Meraklı bardak", en: "Curious cup"),
        preset(18, "cup.twin", .violet, .joy, .none, cup: .twin, tr: "Açık renk ikiz bardak", en: "The light twin cup"),
        preset(19, "cup.catEars", .rose, .smile, .catEars, tr: "Kedi kulaklı bardak", en: "Cup with cat ears"),
        preset(20, "cup.chef", .slate, .grin, .chefHat, tr: "Aşçı bardak", en: "Chef cup"),
        preset(21, "cup.antennae", .mint, .curious, .antennae, tr: "Uzaylı bardak", en: "Alien cup with antennae"),
        preset(22, "cup.laugh", .sand, .laugh, .none, tr: "Kahkaha atan bardak", en: "Laughing cup"),
    ]

    private static let byID: [BrandAvatarPreset.ID: BrandAvatarPreset] = Dictionary(
        uniqueKeysWithValues: all.map { ($0.id, $0) })

    /// The preset for a stored id; nil for one this build does not know
    /// (a newer client's) — the avatar then falls back to the initials.
    static func preset(id: BrandAvatarPreset.ID) -> BrandAvatarPreset? { byID[id] }

    private static func preset(
        _ id: BrandAvatarPreset.ID, _ key: String, _ backdrop: BrandAvatarBackdrop,
        _ expression: BrandAvatarExpression, _ accessory: BrandAvatarAccessory, cup: BrandAvatarCup = .mascot,
        tr: String, en: String
    ) -> BrandAvatarPreset {
        BrandAvatarPreset(
            id: id, key: key, label: BrandStickerText(tr: tr, en: en), backdrop: backdrop, expression: expression,
            accessory: accessory, cup: cup)
    }
}
