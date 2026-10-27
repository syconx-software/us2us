import SwiftUI
import UIKit

/// The photo viewer (5.1 “tap photo → media viewer”, proposed in 5.6's
/// language): the whole photo, fitted to the screen, on `#0d0e19`; pinch to
/// zoom (up to 4× or the photo's own detail), double-tap to zoom in at that
/// point and out again, pan while zoomed, swipe up or down to close.
///
/// 5.6's chrome without its view-once parts: the shades, close · sender ·
/// “Bugün 18:40” · “Kaydet” (to Photos; the sender is told, PROTOCOL §8.4
/// SAVED — never for a disappearing photo), and the caption over the bottom
/// shade. A single tap shows
/// or hides the chrome (0.24 s); zooming in hides it, back at 1× it returns. The bubble's image
/// shows at once; the full photo is decrypted (downloaded first if needed)
/// and cross-fades in (0.24 s). Everything stays in memory.
///
/// `ConversationPhotoViewer` supplies conversation navigation; contact media
/// uses `MediaGalleryViewer`. Only the visible conversation photo loads fully.
struct MediaViewer: View {
    let item: ConversationViewModel.ViewerItem
    let viewModel: any MediaViewerContext
    var themeScheme: ColorScheme = .dark
    let onClose: () -> Void

    var photoPosition = 0
    var photoCount = 1
    var onPrevious: (() -> Void)? = nil
    var onNext: (() -> Void)? = nil

    @State private var image: UIImage?
    @State private var isLoadingFull = true
    @State private var showsLoading = false
    @State private var failed = false
    @State private var chromeVisible = true
    @State private var isZoomed = false
    @State private var dismissProgress: CGFloat = 0
    @State private var saving = ViewerSaveState()
    @Environment(\.locale) private var locale

    /// The longest edge decoded: room to zoom without holding a 48 MP bitmap.
    static let maxPixel = 4_096

    var body: some View {
        U2Screen(background: .clear) {
            ZStack {
                Color.u2.mediaViewerBg
                    .opacity(MediaDismissGesture.backdropOpacity(progress: dismissProgress))
                    .ignoresSafeArea()
                photo
                    .ignoresSafeArea()
                if showsLoading {
                    MediaGlassCircle {
                        MediaStateGlyph(availability: loadingState, readyGlyph: .none)
                    }
                    .transition(.u2Fade)
                    .allowsHitTesting(false)
                }
                chrome
                    .opacity(chromeOpacity)
                    .allowsHitTesting(chromeOpacity > 0.5)
            }
        }
        .background {
            PresentationInterfaceStyle(style: .dark)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .environment(\.colorScheme, .dark)
        .u2Toast($saving.toast)
        .accessibilityAction(.escape, onClose)
        .task { await load() }
        .u2Animation(U2Motion.micro, value: chromeVisible)
        .u2Animation(U2Motion.micro, value: isZoomed)
        .u2Animation(U2Motion.state, value: showsLoading)
        .u2Animation(U2Motion.state, value: failed)
    }

    private var chromeOpacity: Double {
        guard chromeVisible else { return 0 }
        return Double(max(0, 1 - dismissProgress * 3))
    }

    // MARK: Photo

    private var photo: some View {
        ZoomableImageView(
            image: image,
            startsZoomed: item.startsZoomed,
            locale: locale,
            accessibilityLabel: StringTable.conversation.string("viewer.photo.a11y", locale: locale),
            accessibilityHint: StringTable.conversation.string("viewer.zoomHint", locale: locale),
            onSingleTap: { chromeVisible.toggle() },
            // Zooming in hides the chrome, back at 1× it returns; a tap shows it either way.
            onZoomChange: { zoomed in
                isZoomed = zoomed
                chromeVisible = !zoomed
            },
            onDismissProgress: { progress, animated in
                if animated {
                    withU2Animation(U2Motion.spring) { dismissProgress = progress }
                } else {
                    dismissProgress = progress
                }
            },
            onDismiss: onClose,
            onNavigate: photoCount > 1 ? { forward in
                if forward { onNext?() } else { onPrevious?() }
            } : nil
        )
    }

    // MARK: Chrome

    private var chrome: some View {
        ZStack {
            MediaViewerShades()
            VStack(spacing: 0) {
                MediaViewerTopBar(
                    sender: item.sender, sentAt: item.sentAt, themeScheme: themeScheme, closeIdentifier: "mediaViewer.close",
                    onClose: onClose
                ) {
                    if let message = savableMessage {
                        MediaSaveButton(phase: saving.phase(for: message), tellsSender: MediaExportPolicy.tellsSender(message)) {
                            saving.save(message, with: viewModel.mediaSaver, locale: locale)
                        }
                        .environment(\.colorScheme, themeScheme)
                        .transition(.u2Fade)
                    }
                }
                Spacer(minLength: 0)
                VStack(spacing: 10) {
                    if photoCount > 1 {
                        HStack(spacing: 20) {
                            U2GlassIconButton(.caretLeft, glyphSize: 21, surface: .media,
                                label: Text("viewer.previous", table: .conversation), action: { onPrevious?() })
                                .disabled(onPrevious == nil).opacity(onPrevious == nil ? 0.35 : 1)
                                .accessibilityIdentifier("mediaViewer.previous")
                            Text("album.photo \(photoPosition + 1) \(photoCount)", table: .conversation)
                                .u2Font(.label15Emphasis).foregroundStyle(Color.u2.onMedia)
                                .accessibilityIdentifier("mediaViewer.position")
                            U2GlassIconButton(.caretRight, glyphSize: 21, surface: .media,
                                label: Text("viewer.next", table: .conversation), action: { onNext?() })
                                .disabled(onNext == nil).opacity(onNext == nil ? 0.35 : 1)
                                .accessibilityIdentifier("mediaViewer.next")
                        }
                    }
                    if failed {
                        HStack(spacing: 8) {
                            PhosphorIcon(.warningCircle, size: 18)
                            Text("viewer.photo.failed", table: .conversation)
                                .u2Font(.label15Emphasis)
                        }
                        .foregroundStyle(Color.u2.onMedia.opacity(0.72))
                        .transition(.u2Fade)
                    }
                    if let caption = item.caption, !caption.isEmpty {
                        Text(verbatim: caption)
                            .u2Font(.bubble)
                            .foregroundStyle(Color.u2.onMedia)
                            .multilineTextAlignment(.center)
                            .lineLimit(4)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .u2Bottom(50)
            }
        }
    }

    /// The photo as it is now, when it may be saved (never disappearing,
    /// view-once or gone).
    private var savableMessage: Message? {
        guard let id = item.messageID, let message = viewModel.message(id),
            MediaExportPolicy.savesToPhotos(message)
        else { return nil }
        return message
    }

    // MARK: Loading

    /// While the full photo is on its way: the bubble's state (the ring
    /// fills while it downloads).
    private var loadingState: MediaAvailability {
        guard let id = item.messageID, let message = viewModel.message(id) else { return .downloading(nil) }
        switch message.mediaAvailability {
        case .downloading(let fraction): return .downloading(fraction)
        case .unavailable, .held: return .unavailable
        default: return .downloading(nil)
        }
    }

    private func load() async {
        // 1. What the bubble had (the inline thumbnail, or already the full photo).
        let initialData: Data? = if case .memory(let data) = item.media { data } else { nil }
        if image == nil {
            let initial = await MediaImageDecoder.image(from: item.media, maxPixel: Self.maxPixel)
            if !Task.isCancelled { image = initial }
        }
        // 2. The full photo, decrypted (downloaded first when still on the
        // relay). The design's painting is already drawn at full size.
        if case .designArtwork = item.media { isLoadingFull = false }
        guard isLoadingFull, let id = item.messageID else {
            isLoadingFull = false
            return
        }
        let indicator = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            if !Task.isCancelled, isLoadingFull { showsLoading = true }
        }
        defer {
            indicator.cancel()
            isLoadingFull = false
            showsLoading = false
        }
        guard let payload = await viewModel.messenger.mediaPayload(id, in: viewModel.chatID) else {
            if !Task.isCancelled { failed = true }
            return
        }
        guard !Task.isCancelled else { return }
        if payload.data == initialData, image != nil { return }
        if let full = await MediaImageDecoder.decode(payload.data, maxPixel: Self.maxPixel), !Task.isCancelled {
            image = full
        } else if image == nil {
            failed = true
        }
    }
}

/// Decodes a `MediaSource` (or image bytes) into a bitmap off the main
/// thread, at most `maxPixel` on its longest edge.
enum MediaImageDecoder {
    @MainActor
    static func image(from source: MediaSource, maxPixel: Int) async -> UIImage? {
        switch source {
        case .designArtwork(let artwork):
            // The design's painting, large enough to zoom into.
            return DesignArtworkView.renderedImage(artwork, pixelWidth: CGFloat(min(maxPixel, 2_048)))
        case .memory(let data):
            return await decode(data, maxPixel: maxPixel)
        case .file(let url):
            return await Task.detached(priority: .userInitiated) { FileImageView.decode(url, maxPixel: maxPixel) }.value
        }
    }

    static func decode(_ data: Data, maxPixel: Int) async -> UIImage? {
        guard !data.isEmpty else { return nil }
        return await Task.detached(priority: .userInitiated) { MemoryImageView.decode(data, maxPixel: maxPixel) }.value
    }
}

// MARK: - Zoom geometry

/// The viewer's zoom arithmetic (pure, so it is tested on its own).
enum ZoomMath {
    /// `image` scaled to fit inside `bounds`, keeping its proportions.
    static func fittedSize(_ image: CGSize, in bounds: CGSize) -> CGSize {
        guard image.width > 0, image.height > 0, bounds.width > 0, bounds.height > 0 else { return .zero }
        let scale = min(bounds.width / image.width, bounds.height / image.height)
        return CGSize(width: image.width * scale, height: image.height * scale)
    }

    /// Insets that keep content smaller than the bounds centred.
    static func centeringInsets(content: CGSize, bounds: CGSize) -> UIEdgeInsets {
        let horizontal = max(0, (bounds.width - content.width) / 2)
        let vertical = max(0, (bounds.height - content.height) / 2)
        return UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
    }

    /// The deepest zoom: at least 4×, more for a detailed photo (twice its
    /// own pixels, at most 10×).
    static func maximumZoom(pixelWidth: CGFloat, fittedWidth: CGFloat, displayScale: CGFloat) -> CGFloat {
        guard fittedWidth > 0, displayScale > 0 else { return 4 }
        let native = pixelWidth / (fittedWidth * displayScale)
        return max(4, min(10, native * 2))
    }

    /// The double-tap zoom.
    static let doubleTapScale: CGFloat = 2.5

    /// The rect (in the unzoomed photo's coordinates) to zoom to so `point`
    /// ends up in the middle at `scale`.
    static func zoomRect(centeredAt point: CGPoint, scale: CGFloat, bounds: CGSize) -> CGRect {
        let size = CGSize(width: bounds.width / max(scale, 0.01), height: bounds.height / max(scale, 0.01))
        return CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height)
    }
}

// MARK: - The zooming view

/// A `UIScrollView` zooming a `UIImageView`: the photo fitted at 1×, centred
/// by the content insets, double-tap and pinch zoom, and the swipe away at
/// 1× (the photo follows the finger; the settle carries the finger's speed).
struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage?
    var startsZoomed = false
    var locale: Locale = .current
    let accessibilityLabel: String
    let accessibilityHint: String
    let onSingleTap: () -> Void
    let onZoomChange: (Bool) -> Void
    /// The swipe's progress (0…1); `true`: animate to it (the settle).
    let onDismissProgress: (CGFloat, Bool) -> Void
    let onDismiss: () -> Void
    var onNavigate: ((Bool) -> Void)? = nil

    func makeUIView(context: Context) -> ZoomingImageView {
        let view = ZoomingImageView()
        update(view)
        return view
    }

    func updateUIView(_ view: ZoomingImageView, context: Context) {
        update(view)
    }

    private func update(_ view: ZoomingImageView) {
        view.onSingleTap = onSingleTap
        view.onZoomChange = onZoomChange
        view.onDismissProgress = onDismissProgress
        view.onDismiss = onDismiss
        view.onNavigate = onNavigate
        view.accessibilityLabel = accessibilityLabel
        view.accessibilityHint = accessibilityHint
        view.startsZoomed = startsZoomed
        view.locale = locale
        view.setImage(image)
    }
}

final class ZoomingImageView: UIView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var onSingleTap: () -> Void = {}
    var onZoomChange: (Bool) -> Void = { _ in }
    var onDismissProgress: (CGFloat, Bool) -> Void = { _, _ in }
    var onDismiss: () -> Void = {}
    var onNavigate: ((Bool) -> Void)?
    var startsZoomed = false
    /// The UI language, for VoiceOver's zoom value (“%250” / “250%”).
    var locale: Locale = .current

    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private let dismissPan = UIPanGestureRecognizer()
    private let navigationPan = UIPanGestureRecognizer()
    private var laidOutBounds: CGSize = .zero
    private var reportedZoomed = false
    private var didApplyStartZoom = false
    private var isDismissing = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        scrollView.delegate = self
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.decelerationRate = .fast
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.bouncesZoom = true
        scrollView.backgroundColor = .clear
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.isAccessibilityElement = false
        addSubview(scrollView)
        scrollView.addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        singleTap.require(toFail: doubleTap)
        addGestureRecognizer(singleTap)
        dismissPan.addTarget(self, action: #selector(handleDismissPan(_:)))
        dismissPan.maximumNumberOfTouches = 1
        dismissPan.delegate = self
        addGestureRecognizer(dismissPan)
        navigationPan.addTarget(self, action: #selector(handleNavigationPan(_:)))
        navigationPan.maximumNumberOfTouches = 1
        navigationPan.delegate = self
        addGestureRecognizer(navigationPan)
        scrollView.panGestureRecognizer.require(toFail: navigationPan)
        // At 1× a vertical drag belongs to the swipe away; zoomed, to panning.
        scrollView.panGestureRecognizer.require(toFail: dismissPan)

        isAccessibilityElement = true
        accessibilityTraits = [.image, .adjustable]
        accessibilityIdentifier = "mediaViewer.photo"
    }

    required init?(coder: NSCoder) { nil }

    // MARK: Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        guard !isDismissing else { return }
        // Bounds and centre, not the frame: the swipe away transforms the
        // scroll view, and a frame set under a transform is undefined.
        scrollView.bounds.size = bounds.size
        scrollView.center = CGPoint(x: bounds.midX, y: bounds.midY)
        if bounds.size != laidOutBounds {
            laidOutBounds = bounds.size
            layoutImage()
        }
        centerContent()
    }

    func setImage(_ image: UIImage?) {
        guard image !== imageView.image else { return }
        let previous = imageView.image
        guard let image, let previous, abs(aspect(of: image) - aspect(of: previous)) < 0.02 else {
            imageView.image = image
            layoutImage()
            return
        }
        // The full photo replaces the thumbnail in place (same proportions): a cross-fade.
        UIView.transition(
            with: imageView, duration: U2Motion.Duration.micro, options: [.transitionCrossDissolve, .allowUserInteraction]
        ) {
            self.imageView.image = image
        }
        updateMaximumZoom()
    }

    private func aspect(of image: UIImage) -> CGFloat {
        image.size.height > 0 ? image.size.width / image.size.height : 1
    }

    private func layoutImage() {
        guard let image = imageView.image, bounds.width > 0, bounds.height > 0 else { return }
        scrollView.zoomScale = 1
        let fitted = ZoomMath.fittedSize(image.size, in: bounds.size)
        imageView.frame = CGRect(origin: .zero, size: fitted)
        scrollView.contentSize = fitted
        updateMaximumZoom()
        centerContent()
        reportZoom()
        if startsZoomed, !didApplyStartZoom {
            didApplyStartZoom = true
            let center = CGPoint(x: fitted.width / 2, y: fitted.height / 2)
            scrollView.zoom(
                to: ZoomMath.zoomRect(centeredAt: center, scale: ZoomMath.doubleTapScale, bounds: bounds.size), animated: false)
        }
    }

    private func updateMaximumZoom() {
        guard let image = imageView.image else { return }
        let fittedWidth = imageView.bounds.width
        scrollView.maximumZoomScale = ZoomMath.maximumZoom(
            pixelWidth: image.size.width * image.scale, fittedWidth: fittedWidth, displayScale: traitCollection.displayScale)
    }

    private func centerContent() {
        scrollView.contentInset = ZoomMath.centeringInsets(content: scrollView.contentSize, bounds: scrollView.bounds.size)
    }

    private var isZoomedIn: Bool { scrollView.zoomScale > scrollView.minimumZoomScale + 0.01 }

    /// Tells SwiftUI after the current pass (this can run inside its update).
    private func reportZoom() {
        let zoomed = isZoomedIn
        guard zoomed != reportedZoomed else { return }
        reportedZoomed = zoomed
        Task { @MainActor [weak self] in self?.onZoomChange(zoomed) }
    }

    // MARK: UIScrollViewDelegate

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerContent()
        reportZoom()
    }

    // MARK: Taps

    @objc private func handleSingleTap(_ recognizer: UITapGestureRecognizer) {
        onSingleTap()
    }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if isZoomedIn {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let point = recognizer.location(in: imageView)
            let scale = min(scrollView.maximumZoomScale, ZoomMath.doubleTapScale)
            scrollView.zoom(to: ZoomMath.zoomRect(centeredAt: point, scale: scale, bounds: bounds.size), animated: true)
        }
    }

    // MARK: Swipe away

    override func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        if recognizer === navigationPan {
            guard onNavigate != nil, !isZoomedIn, !isDismissing else { return false }
            let velocity = navigationPan.velocity(in: self)
            return abs(velocity.x) > abs(velocity.y) * 1.2
        }
        guard recognizer === dismissPan else { return super.gestureRecognizerShouldBegin(recognizer) }
        guard !isZoomedIn, !isDismissing, imageView.image != nil else { return false }
        let velocity = dismissPan.velocity(in: self)
        return abs(velocity.y) > abs(velocity.x) * 1.2
    }

    @objc private func handleNavigationPan(_ recognizer: UIPanGestureRecognizer) {
        guard recognizer.state == .ended else { return }
        let distance = recognizer.translation(in: self).x
        if abs(distance) >= 48 { onNavigate?(distance < 0) }
    }

    @objc private func handleDismissPan(_ recognizer: UIPanGestureRecognizer) {
        let translation = recognizer.translation(in: self)
        let progress = MediaDismissGesture.progress(translation: translation.y, height: bounds.height)
        switch recognizer.state {
        case .began, .changed:
            let scale = MediaDismissGesture.scale(progress: progress)
            scrollView.transform = CGAffineTransform(translationX: translation.x, y: translation.y).scaledBy(x: scale, y: scale)
            onDismissProgress(progress, false)
        case .ended, .cancelled, .failed:
            let velocity = recognizer.velocity(in: self)
            if recognizer.state == .ended,
                MediaDismissGesture.shouldDismiss(translation: translation.y, velocity: velocity.y)
            {
                finishDismiss(translation: translation, velocity: velocity)
            } else {
                settleBack(translation: translation, velocity: velocity)
            }
        default:
            break
        }
    }

    /// The photo carries on the way it was thrown while the cover fades out.
    private func finishDismiss(translation: CGPoint, velocity: CGPoint) {
        isDismissing = true
        onDismiss()
        let direction: CGFloat = (velocity.y == 0 ? translation.y : velocity.y) >= 0 ? 1 : -1
        let travel = max(abs(translation.y) + 160, min(bounds.height * 0.6, abs(velocity.y) * 0.25)) * direction
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        UIView.animate(
            withDuration: U2Motion.Duration.state, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]
        ) {
            guard !reduceMotion else { return }
            self.scrollView.transform = CGAffineTransform(translationX: translation.x + velocity.x * 0.08, y: travel)
                .scaledBy(x: 0.86, y: 0.86)
        }
    }

    /// Back to rest on the critically damped spring, at the finger's speed.
    private func settleBack(translation: CGPoint, velocity: CGPoint) {
        onDismissProgress(0, true)
        let distance = hypot(translation.x, translation.y)
        let speed = distance > 0.5 ? (translation.x * -velocity.x + translation.y * -velocity.y) / distance : 0
        let relative = CGFloat(U2Motion.relativeVelocity(speed, remaining: distance))
        let parameters = UISpringTimingParameters(dampingRatio: 1, initialVelocity: CGVector(dx: relative, dy: relative))
        let duration = UIAccessibility.isReduceMotionEnabled ? 0.3 : 0.55
        let animator = UIViewPropertyAnimator(duration: duration, timingParameters: parameters)
        animator.addAnimations { self.scrollView.transform = .identity }
        animator.startAnimation()
    }

    // MARK: VoiceOver

    override var accessibilityValue: String? {
        get { Double(scrollView.zoomScale).formatted(.percent.precision(.fractionLength(0)).locale(locale)) }
        set {}
    }

    override func accessibilityIncrement() {
        let target = min(scrollView.maximumZoomScale, max(ZoomMath.doubleTapScale, scrollView.zoomScale * 1.6))
        scrollView.setZoomScale(target, animated: false)
    }

    override func accessibilityDecrement() {
        let target = scrollView.zoomScale / 1.6
        scrollView.setZoomScale(target < 1.2 ? scrollView.minimumZoomScale : target, animated: false)
    }

    override func accessibilityActivate() -> Bool {
        onSingleTap()
        return true
    }
}
