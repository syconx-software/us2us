import SwiftUI

/// A built-in sticker in the conversation (PROTOCOL §8.8). No bubble: the
/// sticker on its die-cut paper, 132 pt, with the time (and, sent, the
/// state and the timer) after it on the trailing side, bottom-aligned — like
/// the text sticker of 5.2. The reaction chip (`MessageBubbleView`) sits
/// under the sticker's leading edge, so it never covers the time or the
/// state. What it draws is `BrandStickerCatalog.resolve`:
///
/// * a sticker of the catalog, its words in the language they were sent in;
/// * the plain text sticker of earlier builds: the Doto card (`TextStickerMessage`);
/// * a sticker this build does not know: its emoji, large;
/// * nothing known and no emoji: a neutral placeholder.
///
/// `animatesEntrance`: a sticker just sent or just received settles in once
/// (`BrandStickerView`); history scrolling into view does not.
struct BrandStickerMessage: View {
    /// The sticker's side in the conversation.
    static let side: CGFloat = 132
    /// An unknown sticker's emoji, and the placeholder.
    static let fallbackSide: CGFloat = 96

    let message: Message
    let packID: String
    let stickerID: UInt32
    let emoji: String
    let lang: String
    var animatesEntrance = false

    @Environment(\.locale) private var locale

    var body: some View {
        let ui = BrandStickerLanguage(locale: locale)
        switch BrandStickerCatalog.resolve(packID: packID, stickerID: stickerID, emoji: emoji, lang: lang, fallback: ui) {
        case .sticker(let selection):
            let label = selection.sticker.accessibilityLabel(shownIn: selection.language, ui: ui)
            row(label: Text("a11y.sticker \(label)")) {
                BrandStickerView(selection, animatesEntrance: animatesEntrance)
                    .frame(width: Self.side, height: Self.side)
            }
        case .legacyText(let text):
            TextStickerMessage(message: message, text: text)
        case .emoji(let emoji):
            row(label: Text("a11y.sticker \(emoji)")) {
                StickerEmojiFallback(emoji: emoji, size: Self.fallbackSide)
            }
        case .unavailable:
            row(label: Text("chats.preview.sticker")) {
                StickerPlaceholder(side: Self.fallbackSide)
            }
        }
    }

    /// The art and its meta, read by VoiceOver as one image: “Sticker: …”,
    /// then the time, the state and the timer.
    private func row<Art: View>(label: Text, @ViewBuilder art: () -> Art) -> some View {
        HStack(alignment: .bottom, spacing: 10) {
            art()
            meta
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(metaValue)
        .accessibilityAddTraits(.isImage)
    }

    private var meta: some View {
        BubbleMeta(time: message.sentAt, status: message.status, disappearing: message.disappearing)
            .padding(.bottom, 6)
    }

    private var metaValue: Text {
        let time = Text(verbatim: BubbleTimeFormat.string(message.sentAt, locale: locale))
        let separator = Text(verbatim: ", ")
        let state = message.status.map { time + separator + Text($0.titleKey) } ?? time
        guard message.disappearing != .off else { return state }
        return state + separator + Text(message.disappearing.shortKey)
    }
}

/// A received sticker with nothing to draw — no sticker this build knows and
/// no emoji: a neutral tile with the sticker glyph.
struct StickerPlaceholder: View {
    var side: CGFloat = 96

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.sticker, style: .continuous)
        PhosphorIcon(.sticker, size: 30)
            .foregroundStyle(Color.u2.textTertiary)
            .frame(width: side, height: side)
            .background(shape.fill(Color.u2.fillSubtle))
            .overlay(shape.strokeBorder(Color.u2.divider, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            .accessibilityElement()
            .accessibilityLabel(Text("chats.preview.sticker"))
    }
}

#Preview("Sticker messages") {
    let date = DesignFixtures.referenceDate
    let contents: [(MessageDirection, MessageContent)] = [
        (.incoming, .sticker(packID: "us2us.cups", stickerID: 1, emoji: "👋", lang: "")),
        (.outgoing, .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "tr")),
        (.incoming, .sticker(packID: "us2us.words", stickerID: 101, emoji: "👋", lang: "en")),
        (.incoming, .sticker(packID: "us2us.future", stickerID: 9001, emoji: "🫶", lang: "")),
        (.incoming, .sticker(packID: "us2us.future", stickerID: 9002, emoji: "", lang: "")),
        (.incoming, .textSticker("tamam!")),
    ]
    return ScrollView {
        VStack(spacing: U2Spacing.bubbleGap) {
            ForEach(contents.indices, id: \.self) { index in
                let (direction, content) = contents[index]
                MessageBubbleView(
                    message: Message(
                        id: "preview.sticker.\(index)", direction: direction, sentAt: date, content: content,
                        status: direction == .outgoing ? .read : nil),
                    isTail: true, animatesEntrance: true
                )
                .frame(maxWidth: .infinity, alignment: direction == .outgoing ? .trailing : .leading)
            }
        }
        .padding(.horizontal, U2Spacing.chatGutter)
        .padding(.vertical, 20)
    }
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
}
