import SwiftUI
import UIKit

// 5.1 › mic — the recorder's views (proposed: the design draws the idle
// mic, not the recording). They extend the composer's own language: the
// field's capsule (`surface`, r22, hairline) holds the recording, the mic
// keeps its ring and glow, the menu material floats the lock and the hint,
// `danger` marks what records or stops, `accent300` what plays.

/// The held mic's touch: press and hold, slide left to cancel, up to lock.
/// Lies over the mic button while the draft is empty. VoiceOver's double
/// tap starts a hands-free recording instead (its own stop, delete and
/// send buttons follow).
struct VoiceHoldArea: View {
    let voice: VoiceComposer
    @GestureState private var isTouching = false

    var body: some View {
        Color.clear
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .updating($isTouching) { _, touching, _ in touching = true }
                    .onChanged { value in voice.touchChanged(value.translation) }
                    .onEnded { _ in voice.touchEnded() }
            )
            // A touch the system takes away never ends: its state resets.
            .onChange(of: isTouching) { _, touching in
                if !touching { voice.touchFinished() }
            }
            .accessibilityElement()
            .accessibilityLabel(Text("a11y.composer.record"))
            .accessibilityHint(Text("voice.a11y.recordHint", table: .conversation))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { voice.startHandsFree() }
    }
}

/// While a voice message is recorded, this strip takes the field's place
/// (and, while held, the “+” button's): the pulsing dot, the time, the live
/// waveform and “‹ İptal için kaydır”, which slides and fades with the
/// finger; locked, the stop button at its end; in review, play / pause, the
/// whole recording with what was listened to, and its length.
///
/// VoiceOver: a hands-free recording moves the focus to its stop button
/// (the mic it was started from is gone); stopping says so with the length
/// — never while the microphone is open, which would record the speech.
struct VoiceRecordingStrip: View {
    let voice: VoiceComposer
    @AccessibilityFocusState private var focusesStop: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.composerField, style: .continuous)
        HStack(spacing: 10) {
            switch voice.mode {
            case .idle:
                EmptyView()
            case .holding:
                VoiceRecordingDot(isLive: voice.isCapturing)
                elapsedLabel
                VoiceLevelBars(levels: voice.levels)
                    .frame(maxWidth: .infinity, maxHeight: 24)
                slideToCancel
            case .locked:
                VoiceRecordingDot(isLive: voice.isCapturing)
                elapsedLabel
                VoiceLevelBars(levels: voice.levels)
                    .frame(maxWidth: .infinity, maxHeight: 24)
                stopButton
            case .review:
                previewButton
                VoiceLevelBars(levels: voice.levels, style: .whole(played: previewFraction))
                    .frame(maxWidth: .infinity, maxHeight: 24)
                reviewTime
            }
        }
        .padding(.leading, voice.mode == .review ? 6 : 16)
        .padding(.trailing, voice.mode == .holding ? 14 : 6)
        .frame(maxWidth: .infinity, minHeight: U2Size.composer, maxHeight: U2Size.composer)
        .background(shape.fill(Color.u2.surface))
        .u2InnerStroke(Color.u2.strokeField, width: 0.5, in: shape)
        .accessibilityElement(children: .contain)
        .onChange(of: voice.mode, initial: true) { _, mode in
            switch mode {
            case .locked:
                focusesStop = true
            case .review:
                let length = U2Format.voiceDuration(voice.recording?.duration ?? voice.elapsed)
                AccessibilityNotification.Announcement(
                    StringTable.conversation.string("voice.a11y.stopped \(length)", locale: locale)
                ).post()
            case .idle, .holding:
                break
            }
        }
    }

    private var elapsedLabel: some View {
        let time = U2Format.voiceDuration(floor(voice.elapsed))
        return Text(verbatim: time)
            .u2Font(.monoCountdownChip)
            .foregroundStyle(Color.u2.text)
            .fixedSize()
            .accessibilityLabel(Text("voice.a11y.recording \(time)", table: .conversation))
    }

    private var slideToCancel: some View {
        HStack(spacing: 4) {
            PhosphorIcon(.caretLeft, size: 14)
            Text("voice.slideToCancel", table: .conversation)
                .u2Font(.preview)
                .lineLimit(1)
        }
        .foregroundStyle(Color.u2.textSecondary)
        .fixedSize()
        .offset(x: max(-48, voice.gesture.translation.width * 0.5))
        .opacity(1 - 0.85 * voice.gesture.cancelProgress)
        .accessibilityElement(children: .combine)
    }

    private var stopButton: some View {
        Button(action: voice.stop) {
            MediaGlyphIcon(.stop, size: 13)
                .foregroundStyle(Color.u2.danger)
                .frame(width: 32, height: 32)
                .overlay(Circle().strokeBorder(Color.u2.dangerStrokeSoft, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(Text("voice.stop", table: .conversation))
        .accessibilityFocused($focusesStop)
    }

    private var isPreviewPlaying: Bool {
        voice.player.activeID == VoiceComposer.previewID && voice.player.isPlaying
    }

    private var previewFraction: Double {
        voice.player.shownFraction(of: VoiceComposer.previewID, fallback: 0)
    }

    private var previewButton: some View {
        let playing = isPreviewPlaying
        return Button(action: voice.togglePreview) {
            StateCrossFade(value: playing) { playing in
                if playing {
                    MediaGlyphIcon(.pause, size: 13)
                } else {
                    PhosphorIcon(.play, weight: .fill, size: 13)
                }
            }
            .foregroundStyle(Color.u2.accent300)
            .frame(width: 32, height: 32)
            .overlay(Circle().strokeBorder(Color.u2.accent, lineWidth: 1))
            .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(
            playing ? Text("player.pause", table: .conversation) : Text("voice.preview.play", table: .conversation))
    }

    /// The length; while it is listened to, where it is.
    private var reviewTime: some View {
        let duration = voice.recording?.duration ?? voice.elapsed
        let isActive = voice.player.activeID == VoiceComposer.previewID
        let shown = isActive ? duration * previewFraction : duration
        return Text(verbatim: U2Format.voiceDuration(shown))
            .u2Font(.monoCountdownChip)
            .foregroundStyle(Color.u2.textSecondary)
            .fixedSize()
            .padding(.trailing, 8)
    }
}

/// The red recording dot (8 pt `danger`), breathing (1.6 s) while the
/// microphone records; still under Reduce Motion and on a frozen clock,
/// dimmed once recording stopped (the five-minute limit).
struct VoiceRecordingDot: View {
    let isLive: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        if isLive, !reduceMotion, !clock.isFrozen {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: scenePhase != .active)) { context in
                let phase = context.date.timeIntervalSinceReferenceDate / 1.6 * 2 * Double.pi
                dot.opacity(0.4 + 0.6 * (0.5 + 0.5 * cos(phase)))
            }
        } else {
            dot.opacity(isLive ? 1 : 0.5)
        }
    }

    private var dot: some View {
        Circle()
            .fill(Color.u2.danger)
            .frame(width: 8, height: 8)
            .accessibilityHidden(true)
    }
}

/// The recorder's waveform, drawn like the bubble's (bars 2.4 wide, round
/// caps, a 4.4 pitch). Live: the newest levels in `accent300`, filling from
/// the leading edge, then moving left as recording goes on. Whole: the
/// recording fitted to the width, the listened part `accent300`, the rest
/// `text·38%`.
struct VoiceLevelBars: View {
    enum Style: Equatable {
        case live
        case whole(played: Double)
    }

    let levels: [Double]
    var style: Style = .live

    var body: some View {
        Canvas { context, size in
            let pitch: CGFloat = 4.4
            let capacity = max(1, Int((size.width - 2.4) / pitch) + 1)
            let shown: [Double]
            let played: Int
            switch style {
            case .live:
                shown = Array(levels.suffix(capacity))
                played = shown.count
            case .whole(let fraction):
                shown = VoiceWaveform.resample(levels, count: capacity)
                played = Int((Double(shown.count) * fraction).rounded())
            }
            for (index, level) in shown.enumerated() {
                let x = 1.2 + pitch * CGFloat(index)
                let height = max(2.4, CGFloat(max(0.08, min(1, level))) * size.height)
                var path = Path()
                path.move(to: CGPoint(x: x, y: (size.height - height) / 2))
                path.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(
                    path, with: .color(index < played ? Color.u2.accent300 : Color.u2.text.opacity(0.38)),
                    style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Above the held mic: the lock to slide up to — a 40 pt capsule in the
/// menu material holding `ph:lock-simple` and an up caret — shrinking as
/// the finger rises; the lock turns `accent300` just before it locks.
struct VoiceLockHint: View {
    let voice: VoiceComposer

    var body: some View {
        let progress = voice.gesture.lockProgress
        VStack(spacing: 8) {
            PhosphorIcon(.lockSimple, size: 18)
                .foregroundStyle(progress > 0.85 ? Color.u2.accent300 : Color.u2.textSecondary)
            MediaGlyphIcon(.caretUp, size: 14)
                .foregroundStyle(Color.u2.textTertiary)
                .opacity(1 - progress)
        }
        .frame(width: 40, height: CGFloat(88 - 32 * progress))
        .u2Backdrop(.menu, in: Capsule())
        .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Capsule())
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// “Kaydetmek için basılı tut” — or “Şu anda kayıt yapılamıyor”, “Bu sesli
/// mesaj oynatılamıyor”, “Sesli mesaj gönderilemedi” — floating above the
/// mic for two seconds: a capsule in the menu material, `caption` in
/// `text`. VoiceOver says it as it appears (the microphone is closed then).
struct VoiceHintBubble: View {
    let hint: VoiceComposer.Hint
    @Environment(\.locale) private var locale

    var body: some View {
        Text(LocalizedStringKey(key), table: .conversation)
            .u2Font(.caption)
            .foregroundStyle(Color.u2.text)
            .padding(.vertical, 8)
            .padding(.horizontal, 14)
            .u2Backdrop(.menu, in: Capsule())
            .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Capsule())
            .fixedSize()
            .allowsHitTesting(false)
            .onChange(of: hint, initial: true) { _, _ in
                AccessibilityNotification.Announcement(
                    StringTable.conversation.string(String.LocalizationValue(key), locale: locale)
                ).post()
            }
    }

    private var key: String {
        switch hint {
        case .hold: "voice.hint.hold"
        case .unavailable: "voice.hint.unavailable"
        case .unplayable: "voice.hint.unplayable"
        case .sendFailed: "voice.hint.sendFailed"
        }
    }
}

/// The microphone is refused (the floating sheet of 5.2's notices): why it
/// is needed — only while recording, end-to-end encrypted — and “Ayarları aç”.
struct VoicePermissionSheet: View {
    let onClose: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                U2DisplayText(
                    StringTable.conversation.resource("voice.permission.title"), style: .displaySheetTitle.balanced)
                Text("voice.permission.body", table: .conversation)
                    .u2Font(.sheetBody)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
            VStack(spacing: 8) {
                Button {
                    onClose()
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                } label: {
                    U2ButtonLabel(Text("voice.permission.settings", table: .conversation), glyph: .gearSix)
                }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("voice.permission.settings")
                Button(action: onClose) {
                    U2ButtonLabel(Text("common.cancel"))
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                .accessibilityIdentifier("voice.permission.close")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
    }
}
