import SwiftUI

/// A built-in sticker (proposed — the design draws one text sticker, 5.2
/// “tamam!”): the cup mascot, the string and Doto words, drawn in code on
/// die-cut paper. It fills the square it is given (96–160 pt in the chat,
/// 72 pt in the picker) and is crisp at any size.
///
/// A text sticker is drawn in `language` — the language it was shown in on
/// the sender's side (`Sticker.lang`), never the reader's — so both ends see
/// the same words. VoiceOver reads it in the app's language
/// (`BrandSticker.accessibilityLabel(shownIn:ui:)`).
///
/// `animatesEntrance`: the sticker settles in once when it appears (fade +
/// scale 0.86 → 1 + a −5° lean straightening, 0.5 s ease-out, no overshoot) —
/// for a sticker just sent or just received, not for history scrolling back
/// into view. Under Reduce Motion it is simply there.
struct BrandStickerView: View {
    let sticker: BrandSticker
    let language: BrandStickerLanguage
    var animatesEntrance = false

    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hasEntered = false

    init(sticker: BrandSticker, language: BrandStickerLanguage, animatesEntrance: Bool = false) {
        self.sticker = sticker
        self.language = language
        self.animatesEntrance = animatesEntrance
    }

    init(_ selection: BrandStickerSelection, animatesEntrance: Bool = false) {
        self.init(sticker: selection.sticker, language: selection.language, animatesEntrance: animatesEntrance)
    }

    private var isSettled: Bool { !animatesEntrance || reduceMotion || hasEntered }

    var body: some View {
        BrandStickerCanvas(sticker: sticker, language: language)
            .scaleEffect(isSettled ? 1 : 0.86, anchor: .bottom)
            .rotationEffect(.degrees(isSettled ? 0 : -5), anchor: .bottom)
            .opacity(isSettled ? 1 : 0)
            .accessibilityElement()
            .accessibilityLabel(
                Text(verbatim: sticker.accessibilityLabel(shownIn: language, ui: BrandStickerLanguage(locale: locale)))
            )
            .accessibilityAddTraits(.isImage)
            .onAppear {
                guard animatesEntrance, !reduceMotion, !hasEntered else { return }
                withAnimation(U2Motion.out(U2Motion.Duration.screen)) { hasEntered = true }
            }
    }
}

/// A received sticker this build cannot draw — a newer pack or id: its
/// `emoji`, large, the way a jumbo emoji is shown (PROTOCOL.md › Stickers).
struct StickerEmojiFallback: View {
    let emoji: String
    var size: CGFloat = 96

    var body: some View {
        Text(verbatim: emoji)
            // Apple Color Emoji at a fixed size: the sticker does not follow
            // Dynamic Type, like the drawn ones.
            .font(.custom("AppleColorEmoji", fixedSize: size * 0.62))
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .frame(width: size, height: size)
            .accessibilityElement()
            .accessibilityLabel(Text("a11y.sticker \(emoji)"))
    }
}

#Preview("Brand sticker") {
    VStack(spacing: 24) {
        HStack(spacing: 16) {
            if let hello = BrandStickerCatalog.sticker(id: 1), let words = BrandStickerCatalog.sticker(id: 103) {
                BrandStickerView(sticker: hello, language: .turkish, animatesEntrance: true)
                    .frame(width: 132, height: 132)
                BrandStickerView(sticker: words, language: .english, animatesEntrance: true)
                    .frame(width: 132, height: 132)
            }
        }
        StickerEmojiFallback(emoji: "🫶")
    }
    .padding(32)
    .background(Color.u2.bg)
}
