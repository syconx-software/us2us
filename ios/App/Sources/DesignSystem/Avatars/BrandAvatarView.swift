import SwiftUI

/// A preset profile avatar (proposed — C12 draws only the initial): the
/// stickers' cup mascot on a disc, in the same circle as `Avatar` and at any
/// of its sizes (28 pt and up; the chat list's 52, the request preview's 64,
/// a 96 pt profile). Drawn in code like a sticker, so it is crisp at every
/// size; under 40 pt the little extras around the cup are left out
/// (`BrandAvatarDetail`) and the cup, its face and what it wears stay.
///
/// The disc follows the theme (deep in the dark one, pale in the light one,
/// like the initial avatars' palettes); the cup is printed in the sticker
/// inks, the same in both.
///
/// VoiceOver reads the preset's name in the app's language (“Kulaklıklı
/// bardak”) — what the picker needs. Where the avatar stands for a person
/// (`Avatar`), the person's name replaces it.
struct BrandAvatarView: View {
    let preset: BrandAvatarPreset
    let diameter: CGFloat

    @Environment(\.locale) private var locale

    init(preset: BrandAvatarPreset, size diameter: CGFloat) {
        self.preset = preset
        self.diameter = diameter
    }

    /// At one of the design's avatar sizes (C12).
    init(preset: BrandAvatarPreset, size: Avatar.Size) {
        self.init(preset: preset, size: size.diameter)
    }

    var body: some View {
        BrandAvatarCanvas(preset: preset)
            .frame(width: diameter, height: diameter)
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: preset.label(BrandStickerLanguage(locale: locale))))
            .accessibilityAddTraits(.isImage)
    }
}

#Preview("Preset avatars") {
    VStack(spacing: 18) {
        ForEach([ColorScheme.dark, .light], id: \.self) { scheme in
            HStack(spacing: 12) {
                ForEach(BrandAvatarCatalog.all.prefix(6)) { preset in
                    BrandAvatarView(preset: preset, size: .listRow)
                }
            }
            .padding(14)
            .background(Color.u2.bg)
            .environment(\.colorScheme, scheme)
        }
    }
}
