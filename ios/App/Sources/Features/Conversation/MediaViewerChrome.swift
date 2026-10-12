import SwiftUI
import UIKit

// The 5.6 viewer chrome, shared by the view-once viewer (5.6), the photo
// viewer and the video player (both proposed, in 5.6's language): the two
// shades and the top bar. Always over media, always dark.

/// Top shade 190 (`#05060c@70%` → clear) and bottom shade 220 (clear →
/// `#05060c@80%`), edge to edge, not hit-testable.
struct MediaViewerShades: View {
    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: 190).background(Color.u2.mediaTopShade)
            Spacer(minLength: 0)
            Color.clear.frame(height: 220).background(Color.u2.mediaBottomShade)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// The top bar (pad 56 16 0, gap 12): close (44, glass over media, `ph:x`
/// 21) · sender (`monoUsername`) over “Bugün 18:40” (`meta` at 75 %) · an
/// optional trailing control (5.6's “1 kez” badge, the player's mute).
struct MediaViewerTopBar<Trailing: View>: View {
    let sender: String
    let sentAt: Date
    /// The app's theme: the close button's hairline follows it (5.6 light reference).
    let themeScheme: ColorScheme
    let closeIdentifier: String
    let onClose: () -> Void
    @ViewBuilder let trailing: Trailing

    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            U2GlassIconButton(.x, glyphSize: 21, surface: .media, label: Text("a11y.close"), action: onClose)
                .environment(\.colorScheme, themeScheme)
                .accessibilityIdentifier(closeIdentifier)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: sender)
                    .u2Font(.monoUsername)
                    .foregroundStyle(Color.u2.onMedia)
                    .lineLimit(1)
                Text(verbatim: MediaViewerTime.line(sentAt, now: clock.now, locale: locale))
                    .u2Font(.meta)
                    .foregroundStyle(Color.u2.onMedia.opacity(0.75))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            trailing
        }
        .padding(.horizontal, 16)
        .u2Top(56)
    }
}

extension MediaViewerTopBar where Trailing == EmptyView {
    init(sender: String, sentAt: Date, themeScheme: ColorScheme, closeIdentifier: String, onClose: @escaping () -> Void) {
        self.init(
            sender: sender, sentAt: sentAt, themeScheme: themeScheme, closeIdentifier: closeIdentifier, onClose: onClose
        ) {
            EmptyView()
        }
    }
}

enum MediaViewerTime {
    /// “Bugün 18:40” today, otherwise “8 Eki 18:40”.
    static func line(_ date: Date, now: Date, locale: Locale) -> String {
        let time = U2Format.clockTime(date, locale: locale)
        if Calendar.current.isDate(date, inSameDayAs: now) {
            return StringTable.conversation.string("viewOnce.today \(time)", locale: locale)
        }
        return "\(U2Format.dayMonth(date, locale: locale)) \(time)"
    }
}

/// A 44 pt glass button over media (the player's mute) with one of the
/// drawn media glyphs — `U2GlassIconButton(surface: .media)`'s look.
struct MediaGlassIconButton: View {
    let glyph: MediaGlyph
    var glyphSize: CGFloat = 21
    let label: Text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MediaGlyphIcon(glyph, size: glyphSize)
        }
        .buttonStyle(MediaGlassButtonStyle())
        .accessibilityLabel(label)
    }
}

/// “Kaydet” over media (proposed): the same 44 pt glass button with the
/// download glyph (Photos, add-only); a spinner while the bytes are read and
/// written, a check once saved (“Kaydedildi”). VoiceOver says that the sender
/// will see it.
struct MediaSaveButton: View {
    enum Phase: Equatable {
        case idle, working, saved
    }

    let phase: Phase
    /// Something received: the hint says the sender will see it.
    var tellsSender = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                switch phase {
                case .idle: MediaGlyphIcon(.download, size: 21)
                case .working: SpinningGlyph(size: 20)
                case .saved: PhosphorIcon(.check, size: 21)
                }
            }
            .transition(.opacity)
        }
        .buttonStyle(MediaGlassButtonStyle())
        .allowsHitTesting(phase != .working)
        .u2Animation(U2Motion.micro, value: phase)
        .accessibilityLabel(phase == .saved ? Text("viewer.saved", table: .conversation) : Text("viewer.save", table: .conversation))
        .accessibilityHint(tellsSender ? Text("viewer.save.hint", table: .conversation) : Text(verbatim: ""))
        .accessibilityIdentifier("viewer.save")
    }
}

/// The viewers' “Kaydet”: what the button shows and the toast after it
/// (“Fotoğraflar'a kaydedildi”, or that it could not be saved).
@Observable
@MainActor
final class ViewerSaveState {
    private(set) var phase: MediaSaveButton.Phase = .idle
    var toast: Text?

    /// Saved here before (`savedByMe`): the check, until it is saved again.
    func phase(for message: Message) -> MediaSaveButton.Phase {
        if phase == .idle, message.savedByMe { return .saved }
        return phase
    }

    func save(_ message: Message, with saver: MediaSaver, locale: Locale) {
        guard phase != .working else { return }
        phase = .working
        Task {
            switch await saver.saveToPhotos(message) {
            case .saved:
                phase = .saved
                U2Haptics.success()
                toast = Text("viewer.saved.toast", table: .conversation)
                UIAccessibility.post(
                    notification: .announcement,
                    argument: StringTable.conversation.string("viewer.saved.toast", locale: locale))
            case .failed:
                phase = .idle
                U2Haptics.error()
                toast = Text("viewer.save.failed", table: .conversation)
            case .cancelled, .denied, .notAllowed, .presented:
                phase = .idle
            }
        }
    }
}

private struct MediaGlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Color.u2.onMedia)
            .frame(width: U2Size.iconButton, height: U2Size.iconButton)
            .background(Circle().fill(Color.u2.onMediaGlass(configuration.isPressed ? 0.55 : 0.4)))
            .u2InnerStroke(Color.u2.strokeGlass, width: 0.5, in: Circle())
            .contentShape(Circle())
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

// MARK: - Drag to dismiss

/// The photo viewer's and the player's swipe away (proposed; 5.6 itself
/// never closes by swipe): the media follows the finger, shrinks to 0.9 and
/// the backdrop thins to 15 % with the distance; let go past 110 pt, or
/// flicked faster than 800 pt/s the same way, and it closes — otherwise it
/// settles back on the critically damped spring.
enum MediaDismissGesture {
    static let distance: CGFloat = 110
    static let velocity: CGFloat = 800

    /// 0 at rest … 1 at a third of the screen.
    static func progress(translation: CGFloat, height: CGFloat) -> CGFloat {
        guard height > 0 else { return 0 }
        return min(1, abs(translation) / (height / 3))
    }

    static func scale(progress: CGFloat) -> CGFloat { 1 - 0.1 * max(0, min(1, progress)) }

    static func backdropOpacity(progress: CGFloat) -> CGFloat { 1 - 0.85 * max(0, min(1, progress)) }

    /// Released with `translation` and `velocity` (points, points per second,
    /// same axis): close, or settle back. A fast flick back towards the rest
    /// position always settles back.
    static func shouldDismiss(translation: CGFloat, velocity: CGFloat) -> Bool {
        let flickedBack =
            translation != 0 && (velocity > 0) != (translation > 0) && abs(velocity) > Self.velocity
        if flickedBack { return false }
        return abs(translation) > distance || abs(velocity) > Self.velocity
    }
}
