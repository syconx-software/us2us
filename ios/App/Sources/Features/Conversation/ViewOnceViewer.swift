import SwiftUI
import UIKit

/// 5.6 · Tek görüntülemelik — the full-screen viewer for view-once media
/// only (ordinary photos open in `MediaViewer`, videos in `VideoPlayerScreen`).
///
/// Always dark, whatever the theme. The photo fills the screen under a top
/// shade (190, `#05060c@70%` → clear) and a bottom shade (220, clear →
/// `#05060c@80%`). Top bar: close (44, glass over media) · sender + “Bugün
/// 18:40” · the dashed “1 kez” badge. iOS cannot block screenshots: when one is
/// taken the warning card drops in (fade + 8 pt) and the sender is told.
/// While the screen is recorded or mirrored the photo is hidden. Footer:
/// “Kapatınca silinir” + “Bu fotoğraf yeniden açılamaz ve yedeğe girmez.”
/// Only the close button ends it (no swipe — no accidental closing).
struct ViewOnceViewer: View {
    let item: ConversationViewModel.ViewerItem
    /// The app's theme: the chrome that uses theme tokens (warning card, the
    /// close button's hairline) follows it, as the light reference shows.
    var themeScheme: ColorScheme = .dark
    let onScreenshot: () -> Void
    let onClose: () -> Void

    @State private var showsScreenshotWarning: Bool
    @State private var isCaptured: Bool
    @Environment(\.locale) private var locale

    init(
        item: ConversationViewModel.ViewerItem, themeScheme: ColorScheme, onScreenshot: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.item = item
        self.themeScheme = themeScheme
        self.onScreenshot = onScreenshot
        self.onClose = onClose
        _showsScreenshotWarning = State(initialValue: item.showsScreenshotWarning)
        _isCaptured = State(initialValue: item.simulatesCapture)
    }

    private var isViewOnce: Bool { item.kind == .viewOnce }

    var body: some View {
        U2Screen(background: Color.u2.mediaViewerBg) {
            ZStack(alignment: .top) {
                media
                    .ignoresSafeArea()
                MediaViewerShades()
                VStack(spacing: 0) {
                    topBar
                    if showsScreenshotWarning {
                        screenshotWarning
                            .environment(\.colorScheme, themeScheme)
                            .padding(EdgeInsets(top: 16, leading: 12, bottom: 0, trailing: 12))
                            .transition(.u2Drop)
                    }
                    Spacer(minLength: 0)
                    if isViewOnce {
                        footer
                    }
                }
            }
        }
        .background {
            ScreenCaptureProbe { captured in
                withU2Animation(U2Motion.state) { isCaptured = captured || item.simulatesCapture }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
        .background {
            PresentationInterfaceStyle(style: .dark)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .environment(\.colorScheme, .dark)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            guard isViewOnce else { return }
            U2Haptics.error()
            withU2Animation(U2Motion.state) { showsScreenshotWarning = true }
            onScreenshot()
        }
        .accessibilityAction(.escape, onClose)
    }

    // MARK: Media

    @ViewBuilder private var media: some View {
        ZStack {
            Color.u2.mediaViewerBg
            if isCaptured {
                VStack(spacing: 12) {
                    PhosphorIcon(.eyeSlash, size: 28)
                    Text("viewOnce.captured", table: .conversation)
                        .u2Font(.label15Emphasis)
                        .multilineTextAlignment(.center)
                }
                .foregroundStyle(Color.u2.onMedia.opacity(0.72))
                .padding(.horizontal, 40)
                .transition(.u2Fade)
            } else {
                MediaView(source: item.media)
                    .accessibilityLabel(Text(item.isVideo ? "media.video" : "media.photo"))
                    .transition(.opacity)
            }
        }
    }

    // MARK: Top bar

    private var topBar: some View {
        MediaViewerTopBar(
            sender: item.sender, sentAt: item.sentAt, themeScheme: themeScheme, closeIdentifier: "viewer.close",
            onClose: onClose
        ) {
            if isViewOnce {
                Text("viewOnce.badge", table: .conversation)
                    .u2Font(.monoMeta, matchLineBox: false)
                    .foregroundStyle(Color.u2.onMedia)
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                    .background(Capsule().fill(Color.u2.onMediaGlass(0.45)))
                    .overlay(
                        Capsule().strokeBorder(Color.u2.onMedia.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [3, 2.5])))
            }
        }
    }

    // MARK: Screenshot warning

    private var screenshotWarning: some View {
        FloatingBanner(.warning) {
            HStack(alignment: .top, spacing: 12) {
                PhosphorIcon(.camera, size: 22)
                    .foregroundStyle(Color.u2.danger)
                VStack(alignment: .leading, spacing: 3) {
                    Text("viewOnce.screenshot.title", table: .conversation)
                        .u2Font(.noteTitle)
                        .foregroundStyle(Color.u2.text)
                    Text(verbatim: warningBody)
                        .u2Font(.captionTighter)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
    }

    /// “deniz’e bildirildi. …” — the Turkish dative follows the name's vowels.
    private var warningBody: String {
        let isTurkish = locale.language.languageCode?.identifier == "tr"
        let name = isTurkish ? TurkishSuffix.dative(item.sender) : item.sender
        return StringTable.conversation.string("viewOnce.screenshot.body \(name)", locale: locale)
    }

    // MARK: Footer

    private var footer: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                PhosphorIcon(.eyeSlash, size: 18)
                Text("viewOnce.closeDeletes", table: .conversation)
                    .u2Font(.label15Emphasis)
            }
            .foregroundStyle(Color.u2.onMedia)
            Text(item.isVideo ? "viewOnce.footer.video" : "viewOnce.footer.photo", table: .conversation)
                .u2Font(.caption)
                .foregroundStyle(Color.u2.onMedia.opacity(0.72))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity)
        .u2Bottom(50)
        .accessibilityElement(children: .combine)
    }
}
