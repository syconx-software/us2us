import SwiftUI
import UIKit

/// The video player (5.2 “video → player”; 5.6 “video variant, play
/// controls” proposed), over the conversation like the photo viewer: the
/// clip aspect-fit on `#0d0e19`, 5.6's shades and top bar with “Kaydet” (to
/// Photos; the sender is told, PROTOCOL §8.4 SAVED — never for a
/// disappearing video) and the mute button (44, glass) at its end, the
/// bubble's glass play button grown to 64
/// in the middle, and the scrubber with the times above the bottom shade.
///
/// It opens at once with the poster and the ring while the clip downloads
/// and decrypts, then plays. A tap shows or hides the controls (0.24 s);
/// while playing they hide by themselves after 3 s. Swipe up or down to
/// close. The clip plays from memory and is dropped when the player closes.
struct VideoPlayerScreen: View {
    let item: ConversationViewModel.ViewerItem
    let viewModel: any MediaViewerContext
    var themeScheme: ColorScheme = .dark
    let onClose: () -> Void

    @State private var playback: VideoPlaybackModel
    @State private var poster: UIImage?
    @State private var controlsVisible = true
    @State private var drag: CGSize = .zero
    @State private var dragAxisIsVertical: Bool?
    @State private var isClosing = false
    @State private var hideTask: Task<Void, Never>?
    @State private var saving = ViewerSaveState()
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        item: ConversationViewModel.ViewerItem, viewModel: any MediaViewerContext, themeScheme: ColorScheme,
        onClose: @escaping () -> Void
    ) {
        self.item = item
        self.viewModel = viewModel
        self.themeScheme = themeScheme
        self.onClose = onClose
        _playback = State(initialValue: VideoPlaybackModel(duration: item.duration))
    }

    var body: some View {
        GeometryReader { proxy in
            let progress = MediaDismissGesture.progress(translation: drag.height, height: proxy.size.height)
            U2Screen(background: .clear) {
                ZStack {
                    Color.u2.mediaViewerBg
                        .opacity(MediaDismissGesture.backdropOpacity(progress: progress))
                        .ignoresSafeArea()
                    media
                        .scaleEffect(MediaDismissGesture.scale(progress: progress))
                        .offset(drag)
                        .ignoresSafeArea()
                    chrome
                        .opacity(controlsShown ? Double(max(0, 1 - progress * 3)) : 0)
                        .allowsHitTesting(controlsShown && !isClosing)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { toggleControls() }
            .simultaneousGesture(dismissDrag(height: proxy.size.height))
        }
        .ignoresSafeArea()
        .background {
            PresentationInterfaceStyle(style: .dark)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .environment(\.colorScheme, .dark)
        .u2Toast($saving.toast)
        .accessibilityAction(.escape, close)
        .task { await load() }
        .onDisappear {
            hideTask?.cancel()
            playback.close()
        }
        .onChange(of: playback.isPlaying) { _, playing in
            if playing { scheduleHide() } else { hideTask?.cancel() }
        }
        // The privacy cover comes up (app switcher, Control Center): pause.
        // To the background: stop now, not after the cover's fade.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
            playback.pause()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            closeAtOnce()
        }
        .u2Animation(U2Motion.micro, value: controlsShown)
        .u2Animation(U2Motion.state, value: playback.phase)
        .u2Animation(U2Motion.micro, value: playback.isShowingVideo)
    }

    /// Paused, loading, ended or failed: the controls stay. Closing: as they were.
    private var controlsShown: Bool {
        controlsVisible || (!playback.isPlaying && !isClosing)
    }

    // MARK: Media

    private var media: some View {
        ZStack {
            if let poster, !playback.isShowingVideo {
                Image(uiImage: poster)
                    .resizable()
                    .scaledToFit()
                    .transition(.opacity)
            }
            PlayerLayerView(player: playback.player) { ready in
                playback.isShowingVideo = ready
            }
            .opacity(playback.isShowingVideo ? 1 : 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement()
        .accessibilityLabel(Text("media.video"))
        .accessibilityIdentifier("player.video")
    }

    // MARK: Chrome

    private var chrome: some View {
        ZStack {
            MediaViewerShades()
            VStack(spacing: 0) {
                MediaViewerTopBar(
                    sender: item.sender, sentAt: item.sentAt, themeScheme: themeScheme, closeIdentifier: "player.close",
                    onClose: close
                ) {
                    HStack(spacing: 8) {
                        if let message = savableMessage {
                            MediaSaveButton(phase: saving.phase(for: message), tellsSender: MediaExportPolicy.tellsSender(message)) {
                                saving.save(message, with: viewModel.mediaSaver, locale: locale)
                                scheduleHide()
                            }
                            .transition(.u2Fade)
                        }
                        MediaGlassIconButton(
                            glyph: playback.isMuted ? .speakerX : .speakerHigh,
                            label: playback.isMuted
                                ? Text("player.unmute", table: .conversation) : Text("player.mute", table: .conversation)
                        ) {
                            playback.toggleMute()
                            scheduleHide()
                        }
                        .accessibilityIdentifier("player.mute")
                    }
                    .environment(\.colorScheme, themeScheme)
                }
                Spacer(minLength: 0)
                bottomControls
            }
            centreButton
        }
    }

    @ViewBuilder private var centreButton: some View {
        switch playback.phase {
        case .loading:
            VStack(spacing: 12) {
                MediaGlassCircle(diameter: 64) {
                    MediaStateGlyph(availability: loadingState, diameter: 64, readyGlyph: .none)
                }
                Text("player.preparing", table: .conversation)
                    .u2Font(.caption)
                    .foregroundStyle(Color.u2.onMedia.opacity(0.72))
            }
            .accessibilityElement(children: .combine)
            .transition(.u2Fade)
        case .failed:
            VStack(spacing: 12) {
                MediaGlassCircle(diameter: 64) {
                    PhosphorIcon(.warningCircle, size: 26).opacity(0.72)
                }
                Text("player.failed", table: .conversation)
                    .u2Font(.label15Emphasis)
                    .foregroundStyle(Color.u2.onMedia.opacity(0.72))
            }
            .accessibilityElement(children: .combine)
            .transition(.u2Fade)
        case .ready, .ended:
            Button {
                playback.togglePlay()
                if playback.isPlaying { scheduleHide() }
            } label: {
                MediaGlassCircle(diameter: 64) {
                    StateCrossFade(value: playback.isPlaying) { playing in
                        if playing {
                            MediaGlyphIcon(.pause, size: 26)
                        } else {
                            PhosphorIcon(.play, weight: .fill, size: 26)
                        }
                    }
                }
                .contentShape(Circle())
            }
            .buttonStyle(MediaPressStyle())
            .accessibilityLabel(playLabel)
            .accessibilityIdentifier("player.play")
            .transition(.u2Fade)
        }
    }

    private var playLabel: Text {
        if playback.isPlaying { return Text("player.pause", table: .conversation) }
        if playback.phase == .ended { return Text("player.replay", table: .conversation) }
        return Text("player.play", table: .conversation)
    }

    private var bottomControls: some View {
        VideoScrubber(playback: playback, locale: locale) { scheduleHide() }
            .padding(.horizontal, 16)
            .u2Bottom(50)
            .opacity(playback.phase == .ready || playback.phase == .ended ? 1 : 0.4)
            .allowsHitTesting(playback.phase == .ready || playback.phase == .ended)
    }

    /// The video as it is now, when it may be saved (never disappearing,
    /// view-once or gone).
    private var savableMessage: Message? {
        guard let id = item.messageID, let message = viewModel.message(id),
            MediaExportPolicy.savesToPhotos(message)
        else { return nil }
        return message
    }

    /// While the clip is on its way: the bubble's state (the ring fills while it downloads).
    private var loadingState: MediaAvailability {
        guard let id = item.messageID, let message = viewModel.message(id) else { return .downloading(nil) }
        if case .downloading(let fraction) = message.mediaAvailability { return .downloading(fraction) }
        return .downloading(nil)
    }

    // MARK: Behaviour

    private func load() async {
        poster = await MediaImageDecoder.image(from: item.media, maxPixel: 1_280)
        guard let id = item.messageID else {
            playback.start(nil)
            return
        }
        let payload = await viewModel.messenger.mediaPayload(id, in: viewModel.chatID)
        guard !Task.isCancelled, !isClosing else { return }
        playback.start(payload, startTime: item.startTime, autoplay: item.startTime == nil)
    }

    private func toggleControls() {
        guard !isClosing else { return }
        controlsVisible.toggle()
        if controlsVisible { scheduleHide() }
    }

    /// Playing: the controls fade after 3 s without a touch.
    private func scheduleHide() {
        hideTask?.cancel()
        guard playback.isPlaying else { return }
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled, playback.isPlaying, !playback.isScrubbing else { return }
            controlsVisible = false
        }
    }

    /// To the background: everything decrypted goes now, not after the fade.
    private func closeAtOnce() {
        playback.close()
        close()
    }

    /// The cover fades out on the paused frame (not on an emptied player);
    /// everything decrypted goes once it is gone (`onDisappear`, at the
    /// latest just after the fade).
    private func close() {
        guard !isClosing else { return }
        controlsVisible = controlsShown
        hideTask?.cancel()
        isClosing = true
        playback.pause()
        onClose()
        let playback = playback
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(U2Motion.Duration.state + 0.1))
            playback.close()
        }
    }

    private func dismissDrag(height: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { value in
                guard !isClosing, !playback.isScrubbing else { return }
                if dragAxisIsVertical == nil {
                    dragAxisIsVertical = abs(value.translation.height) > abs(value.translation.width) * 1.2
                }
                guard dragAxisIsVertical == true else { return }
                drag = value.translation
            }
            .onEnded { value in
                defer { dragAxisIsVertical = nil }
                guard dragAxisIsVertical == true, !isClosing else { return }
                if MediaDismissGesture.shouldDismiss(translation: value.translation.height, velocity: value.velocity.height) {
                    let direction: CGFloat = value.velocity.height + value.translation.height >= 0 ? 1 : -1
                    withAnimation(reduceMotion ? nil : U2Motion.out(U2Motion.Duration.state)) {
                        drag = CGSize(
                            width: value.translation.width, height: value.translation.height + 160 * direction)
                    }
                    close()
                } else {
                    let velocity = U2Motion.relativeVelocity(-value.velocity.height, remaining: abs(value.translation.height))
                    withU2Animation(U2Motion.spring(initialVelocity: velocity)) { drag = .zero }
                }
            }
    }
}

/// The scrubber: a 44 pt touch strip around a 4 pt track (`#f5f4ff` at 28 %,
/// what is loaded at 45 %, what has played solid), a 14 pt knob that grows
/// to 18 while dragged, and the times in Mono 11 at 75 % (elapsed left,
/// length right). VoiceOver: adjustable by 5 s.
private struct VideoScrubber: View {
    let playback: VideoPlaybackModel
    let locale: Locale
    let onInteraction: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { proxy in
                let width = proxy.size.width
                let knob: CGFloat = playback.isScrubbing ? 18 : 14
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.u2.onMedia.opacity(0.28)).frame(height: 4)
                    Capsule().fill(Color.u2.onMedia.opacity(0.45))
                        .frame(width: width * playback.bufferedFraction, height: 4)
                    Capsule().fill(Color.u2.onMedia)
                        .frame(width: width * playback.fraction, height: 4)
                    Circle().fill(Color.u2.onMedia)
                        .frame(width: knob, height: knob)
                        .shadow(color: Color.u2.shadowBlack(0.35), radius: 4, x: 0, y: 1)
                        .offset(x: width * playback.fraction - knob / 2)
                        .u2Animation(U2Motion.micro, value: playback.isScrubbing)
                }
                .frame(height: 44)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            playback.beginScrub()
                            playback.scrub(to: width > 0 ? value.location.x / width : 0)
                            onInteraction()
                        }
                        .onEnded { _ in
                            playback.endScrub()
                            onInteraction()
                        }
                )
            }
            .frame(height: 44)
            .accessibilityElement()
            .accessibilityLabel(Text("player.position", table: .conversation))
            .accessibilityValue(Text(verbatim: "\(elapsed) / \(total)"))
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: playback.skip(by: 5)
                case .decrement: playback.skip(by: -5)
                @unknown default: break
                }
            }
            .accessibilityIdentifier("player.scrubber")
            HStack {
                Text(verbatim: elapsed)
                Spacer(minLength: 8)
                Text(verbatim: total)
            }
            .u2Font(.monoMeta.tabular)
            .foregroundStyle(Color.u2.onMedia.opacity(0.75))
            .accessibilityHidden(true)
        }
    }

    private var elapsed: String { U2Format.voiceDuration(playback.currentTime) }
    private var total: String { U2Format.voiceDuration(playback.duration) }
}

/// The glass play button's press: 0.97 scale (C1's press feedback).
private struct MediaPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}
