import SwiftUI

/// C21 — the chat composer, floating over the bottom of the conversation:
/// height 94 (pad 14 12 36 12) on `composerFade`; row gap 8, bottom-aligned:
/// “+” (C4 44, `ph:plus` 22) · field (min 44, r22, `surface`, hairline 0.5 pt
/// `text·14%`, pad 0 12 0 16, placeholder “Mesaj” `text·50%`, `ph:timer` and
/// `ph:sticker` 21 in `text·55%`, gap 12) · mic (44, 1 pt `accent`,
/// `ph:microphone` 21 `accent300`, glow `0 0 20px -6px accent·70%`).
/// The field grows to 5 lines; with text the mic becomes send (proposed).
struct Composer: View {
    @Binding var text: String
    var quote: QuotedMessage? = nil
    var onCancelQuote: () -> Void = {}
    let onSend: () -> Void
    var onAttach: () -> Void = {}
    var onTimer: () -> Void = {}
    var onSticker: () -> Void = {}
    var onRecord: () -> Void = {}
    /// Bottom padding; `nil` = the design's 36 pt above the physical bottom.
    var bottomPadding: CGFloat? = nil

    @FocusState private var isFocused: Bool
    @Environment(\.u2SafeArea) private var safeArea

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(spacing: 8) {
            if let quote {
                HStack(spacing: 8) {
                    ReplyQuote(quote: quote)
                    U2GlassIconButton(.x, diameter: U2Size.iconButtonSheet, glyphSize: 17, label: Text("a11y.reply.cancel"), action: onCancelQuote)
                }
                .padding(.leading, 52)
                .transition(.u2Rise)
            }
            HStack(alignment: .bottom, spacing: 8) {
                U2GlassIconButton(.plus, glyphSize: 22, label: Text("a11y.composer.attach"), action: onAttach)
                field
                sendOrRecord
            }
        }
        .padding(.top, 14)
        .padding(.horizontal, U2Spacing.chatComposerGutter)
        .padding(.bottom, bottomPadding ?? safeArea.bottom(36))
        .background(Color.u2.composerFade.allowsHitTesting(false))
        .u2Animation(U2Motion.state, value: quote)
    }

    private var field: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.composerField, style: .continuous)
        return HStack(alignment: .bottom, spacing: 12) {
            TextField(text: $text, axis: .vertical) {
                Text("composer.placeholder").foregroundStyle(Color.u2.textPlaceholder)
            }
            .u2Font(.rowTitle)
            .foregroundStyle(Color.u2.text)
            .tint(Color.u2.accent400)
            .lineLimit(1...5)
            .focused($isFocused)
            .padding(.vertical, (U2Size.composer - 24.8) / 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 12) {
                // 2/3 pt lower than centred, like the design's icon font.
                Button(action: onTimer) { PhosphorIcon(.timer, size: 21).offset(y: 2.0 / 3.0) }
                    .accessibilityLabel(Text("a11y.composer.timer"))
                Button(action: onSticker) { PhosphorIcon(.sticker, size: 21).offset(y: 2.0 / 3.0) }
                    .accessibilityLabel(Text("a11y.composer.sticker"))
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.u2.textComposerIcon)
            .frame(height: U2Size.composer)
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .frame(minHeight: U2Size.composer)
        .background(shape.fill(Color.u2.surface))
        .u2InnerStroke(Color.u2.strokeField, width: 0.5, in: shape)
    }

    private var sendOrRecord: some View {
        Button {
            if hasText { onSend() } else { onRecord() }
        } label: {
            ZStack {
                PhosphorIcon(.microphone, size: 21)
                    .opacity(hasText ? 0 : 1)
                    .scaleEffect(hasText ? 0.8 : 1)
                PhosphorIcon(.paperPlaneTilt, size: 21)
                    .opacity(hasText ? 1 : 0)
                    .scaleEffect(hasText ? 1 : 0.8)
            }
            .offset(y: 2.0 / 3.0)
            .foregroundStyle(Color.u2.accent300)
            .frame(width: U2Size.iconButton, height: U2Size.iconButton)
            .overlay(Circle().strokeBorder(Color.u2.accent, lineWidth: 1))
            .u2BoxShadow(.glowMic, in: Circle())
            .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .u2Animation(U2Motion.micro, value: hasText)
        .accessibilityLabel(hasText ? Text("a11y.composer.send") : Text("a11y.composer.record"))
    }
}

#Preview("Composer") {
    struct Demo: View {
        @State private var text = ""
        var body: some View {
            U2Screen {
                VStack {
                    Spacer()
                    Composer(text: $text, onSend: { text = "" })
                }
            }
        }
    }
    return Demo()
}
