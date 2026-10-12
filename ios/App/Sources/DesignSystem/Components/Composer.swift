import SwiftUI

/// C21 — the chat composer, floating over the bottom of the conversation:
/// height 94 (pad 14 12 36 12) on `composerFade`; row gap 8, bottom-aligned:
/// “+” (C4 44, `ph:plus` 22) · field (min 44, r22, `surface`, hairline 0.5 pt
/// `text·14%`, pad 0 12 0 16, placeholder “Mesaj” `text·50%`, `ph:timer` and
/// `ph:sticker` 21 in `text·55%`, gap 12) · mic (44, 1 pt `accent`,
/// `ph:microphone` 21 `accent300`, glow `0 0 20px -6px accent·70%`).
/// The field grows to 5 lines; with text the mic becomes send (proposed).
///
/// Voice messages (proposed — the design draws only the idle mic): with a
/// `voice` recorder, pressing and holding the mic records. The recording
/// strip (`VoiceRecordingStrip`) then takes the place of the field and of
/// “+”; the mic fills with `accent`, grows a touch and follows the finger,
/// the lock floats above it. Locked or in review, “+” becomes “Sil” and the
/// mic “Gönder”. A tap shows “Kaydetmek için basılı tut” above the mic.
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
    /// The voice recorder behind the mic; `nil`: the mic is a plain button
    /// (`onRecord`) — previews, the inert 4.4 composer.
    var voice: VoiceComposer? = nil

    @FocusState private var isFocused: Bool
    @Environment(\.u2SafeArea) private var safeArea

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var voiceMode: VoiceComposer.Mode { voice?.mode ?? .idle }

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
                leadingButton
                field
                ComposerActionButton(hasText: hasText, voice: voice, onSend: onSend, onRecord: onRecord)
            }
            // Behind the row, so the held mic slides over it; the hidden
            // field and “+” let its buttons through.
            .background(alignment: .bottomLeading) {
                if let voice, voiceMode != .idle {
                    // Held: in place of “+” and the field; locked or in review
                    // the strip leaves “+” (now “Sil”) free.
                    VoiceRecordingStrip(voice: voice)
                        .padding(.leading, voiceMode == .holding ? 0 : U2Size.iconButton + 8)
                        .padding(.trailing, U2Size.iconButton + 8)
                        .transition(.u2Fade)
                }
            }
            .overlay(alignment: .topTrailing) {
                if let hint = voice?.hint {
                    VoiceHintBubble(hint: hint)
                        .alignmentGuide(.top) { $0[.bottom] + 10 }
                        .transition(.u2Rise)
                }
            }
        }
        .padding(.top, 14)
        .padding(.horizontal, U2Spacing.chatComposerGutter)
        .padding(.bottom, bottomPadding ?? safeArea.bottom(36))
        .background(Color.u2.composerFade.allowsHitTesting(false))
        .u2Animation(U2Motion.state, value: quote)
        .u2Animation(U2Motion.state, value: voiceMode)
    }

    /// “+”, or — a recording locked or in review — “Sil” (`danger`).
    private var leadingButton: some View {
        ZStack {
            U2GlassIconButton(.plus, glyphSize: 22, label: Text("a11y.composer.attach"), action: onAttach)
                .opacity(voiceMode == .idle ? 1 : 0)
                .allowsHitTesting(voiceMode == .idle)
                .accessibilityHidden(voiceMode != .idle)
            if let voice, voiceMode == .locked || voiceMode == .review {
                U2GlassIconButton(
                    .trash, tint: Color.u2.danger, label: Text("voice.delete", table: .conversation), action: voice.cancel)
                    .transition(.u2Fade)
            }
        }
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
        // Under the recording strip while a voice message is recorded.
        .opacity(voiceMode == .idle ? 1 : 0)
        .allowsHitTesting(voiceMode == .idle)
        .accessibilityHidden(voiceMode != .idle)
    }
}

/// The mic / send button. With text it sends; empty it records — with a
/// voice recorder by press and hold (`VoiceHoldArea` over it), otherwise
/// with a tap (`onRecord`); a recording locked or in review it sends.
/// While held it fills, grows a touch and follows the finger, with the
/// lock above it.
private struct ComposerActionButton: View {
    let hasText: Bool
    let voice: VoiceComposer?
    let onSend: () -> Void
    let onRecord: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let mode = voice?.mode ?? .idle
        let sendsRecording = mode == .locked || mode == .review
        let sends = hasText || sendsRecording
        let holding = mode == .holding
        Button {
            if sendsRecording, let voice {
                voice.send()
            } else if hasText {
                onSend()
            } else {
                onRecord()
            }
        } label: {
            ComposerMicLabel(sends: sends, isRecording: holding)
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(
            sendsRecording
                ? Text("voice.send", table: .conversation)
                : sends ? Text("a11y.composer.send") : Text("a11y.composer.record"))
        // The hold area speaks for the mic.
        .accessibilityHidden(voice != nil && !sends)
        .overlay {
            if let voice, !hasText, mode == .idle || mode == .holding {
                VoiceHoldArea(voice: voice)
            }
        }
        .scaleEffect(holding && !reduceMotion ? 1.3 : 1)
        .offset(voice?.micOffset ?? .zero)
        // Behind the mic, which rises into it as the finger slides up.
        .background(alignment: .top) {
            if let voice, holding {
                VoiceLockHint(voice: voice)
                    .alignmentGuide(.top) { $0[.bottom] + 16 }
                    .transition(.u2Rise)
            }
        }
        .u2Animation(U2Motion.micro, value: sends)
        .u2Animation(U2Motion.micro, value: holding)
    }
}

/// C21's mic (44, 1 pt `accent`, the glyph `accent300`, the mic glow); the
/// paper plane cross-fades in to send. Recording (proposed): filled with
/// `accent`, the glyph in `bg`.
private struct ComposerMicLabel: View {
    let sends: Bool
    let isRecording: Bool

    var body: some View {
        ZStack {
            PhosphorIcon(.microphone, size: 21)
                .opacity(sends ? 0 : 1)
                .scaleEffect(sends ? 0.8 : 1)
            PhosphorIcon(.paperPlaneTilt, size: 21)
                .opacity(sends ? 1 : 0)
                .scaleEffect(sends ? 1 : 0.8)
        }
        .offset(y: 2.0 / 3.0)
        .foregroundStyle(isRecording ? Color.u2.bg : Color.u2.accent300)
        .frame(width: U2Size.iconButton, height: U2Size.iconButton)
        .background(Circle().fill(Color.u2.accent).opacity(isRecording ? 1 : 0))
        .overlay(Circle().strokeBorder(Color.u2.accent, lineWidth: 1))
        .u2BoxShadow(.glowMic, in: Circle())
        .contentShape(Circle())
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
