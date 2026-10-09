// us2us — the welcome screen's 3D scene ("İki uç. Bir hat."): two cups and
// the line between them, with light packets flowing both ways.
//
// A native Metal port of the design's three.js scene (us2us-scene.js) that
// reproduces its geometry, materials, lights, blending and motion exactly.
// The background is transparent; place it over the screen's own background.
//
//   Us2usScene(theme: colorScheme)                    // design 1.1
//   Us2usScene(theme: colorScheme, state: .welcome)
//   Us2usScene(theme: colorScheme, isActive: false)   // covered: no GPU work
//
// Behaviour
//   • Sways slowly; packets travel along the line in both directions.
//   • Reduce Motion: a single still frame, no animation.
//   • Renders only while on screen, `isActive` and the app is active (paused
//     otherwise; never submits GPU work while the app is in the background),
//     at most 60 fps (30 fps in Low Power Mode or when the device is hot),
//     at 2× scale with 2× MSAA — the same pixel density the web scene caps at.
//   • Fades in softly once its first frame is ready.

import MetalKit
import SwiftUI

public struct Us2usScene: View {
    private let theme: ColorScheme
    private let state: SceneState
    private let pinnedTime: Double?
    private let isActive: Bool

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isOnScreen = false

    /// Time of the still frame shown when Reduce Motion is on: the packets
    /// are spread evenly along the line.
    static let stillFrameTime = 2.0

    /// - Parameter isActive: `false` pauses the animation while the scene is
    ///   in the hierarchy but cannot be seen (e.g. covered by a pushed screen);
    ///   the last frame stays in place and nothing is rendered.
    public init(theme: ColorScheme, state: SceneState = .welcome, isActive: Bool = true) {
        self.init(theme: theme, state: state, pinnedTime: nil, isActive: isActive)
    }

    /// Pins the scene to a fixed time (snapshots / previews / design comparisons).
    init(theme: ColorScheme, state: SceneState = .welcome, pinnedTime: Double?, isActive: Bool = true) {
        self.theme = theme
        self.state = state
        self.pinnedTime = pinnedTime
        self.isActive = isActive
    }

    public var body: some View {
        SceneViewRepresentable(
            palette: ScenePalette.forScheme(theme),
            state: state,
            frozenTime: pinnedTime ?? (reduceMotion ? Self.stillFrameTime : nil),
            isRunning: isActive && isOnScreen && scenePhase == .active
        )
        .onAppear { isOnScreen = true }
        .onDisappear { isOnScreen = false }
        .allowsHitTesting(false)
        .accessibilityElement()
        .accessibilityLabel(Text("scene3d.accessibilityLabel", tableName: "Scene3D", bundle: .scene3D))
        .accessibilityAddTraits(.isImage)
    }
}

extension Bundle {
    /// The bundle holding the scene's strings (Scene3D.xcstrings): the app,
    /// or the framework the Scene3D sources are compiled into.
    static var scene3D: Bundle { Bundle(for: Us2usSceneView.self) }
}

private struct SceneViewRepresentable: UIViewRepresentable {
    let palette: ScenePalette
    let state: SceneState
    let frozenTime: Double?
    let isRunning: Bool

    func makeUIView(context: Context) -> Us2usSceneView {
        Us2usSceneView()
    }

    func updateUIView(_ view: Us2usSceneView, context: Context) {
        view.configure(palette: palette, state: state, frozenTime: frozenTime, isRunning: isRunning)
    }

    static func dismantleUIView(_ view: Us2usSceneView, coordinator: ()) {
        view.stop()
    }
}

/// The Metal-backed view. Drawing is driven by its own display link so the
/// frame rate can be capped and the loop stopped whenever it is not needed.
@MainActor
final class Us2usSceneView: MTKView {
    private let renderer = SceneRenderer()
    private var displayLink: CADisplayLink?
    private var lastTimestamp: CFTimeInterval?
    private var isRunningRequested = false
    private var canvasOpacity: CGFloat = 1
    private var isFirstFrameShown = false
    private var isRedrawScheduled = false
    /// A still frame was requested while the app was in the background; it is
    /// drawn when the app comes back.
    private var isRedrawDeferred = false
    private var observers: [any NSObjectProtocol] = []

    init() {
        super.init(frame: .zero, device: MTLCreateSystemDefaultDevice())
        isOpaque = false
        backgroundColor = .clear
        layer.isOpaque = false
        if let metalLayer = layer as? CAMetalLayer {
            metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
        }
        colorPixelFormat = SceneResources.colorFormat
        depthStencilPixelFormat = .invalid
        sampleCount = 1
        framebufferOnly = true
        autoResizeDrawable = true
        isPaused = true
        enableSetNeedsDisplay = false
        isUserInteractionEnabled = false
        contentScaleFactor = 2
        alpha = 0

        delegate = renderer
        renderer.onOpacityChange = { [weak self] opacity in self?.setCanvasOpacity(CGFloat(opacity)) }
        renderer.onFirstFrame = { [weak self] in self?.revealFirstFrame() }

        let center = NotificationCenter.default
        for name in [ProcessInfo.thermalStateDidChangeNotification, Notification.Name.NSProcessInfoPowerStateDidChange] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.applyFrameRate() }
            })
        }

        Task { [weak self] in
            let resources = await SceneResources.load()
            guard let self else { return }
            renderer.resources = resources
            updateLoop()
            setNeedsRender()
        }
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func configure(palette: ScenePalette, state: SceneState, frozenTime: Double?, isRunning: Bool) {
        let changed = renderer.palette != palette || renderer.state != state || renderer.frozenTime != frozenTime
        renderer.palette = palette
        renderer.state = state
        renderer.frozenTime = frozenTime
        isRunningRequested = isRunning
        updateLoop()
        if changed || (isRedrawDeferred && !isInBackground) { setNeedsRender() }
    }

    func stop() {
        isRunningRequested = false
        updateLoop()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            contentScaleFactor = min(2, max(1, traitCollection.displayScale))
        }
        updateLoop()
        setNeedsRender()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        setNeedsRender()
    }

    // MARK: Loop

    private var shouldAnimate: Bool {
        isRunningRequested && window != nil && renderer.resources != nil && renderer.frozenTime == nil
            && !isInBackground
    }

    /// iOS does not allow GPU work from the background. The app-switcher
    /// snapshots (light and dark) change traits and lay views out again while
    /// the app is backgrounded; those must not draw.
    private var isInBackground: Bool {
        guard let scene = window?.windowScene else { return false }
        return scene.activationState == .background
    }

    private func updateLoop() {
        if shouldAnimate {
            guard displayLink == nil else { return }
            let link = CADisplayLink(target: DisplayLinkTarget(owner: self), selector: #selector(DisplayLinkTarget.tick(_:)))
            displayLink = link
            applyFrameRate()
            link.add(to: .main, forMode: .common)
        } else if let link = displayLink {
            link.invalidate()
            displayLink = nil
            lastTimestamp = nil
        }
    }

    private func applyFrameRate() {
        let info = ProcessInfo.processInfo
        let constrained = info.isLowPowerModeEnabled || info.thermalState == .serious || info.thermalState == .critical
        displayLink?.preferredFrameRateRange = constrained
            ? CAFrameRateRange(minimum: 24, maximum: 30, preferred: 30)
            : CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
    }

    fileprivate func tick(_ link: CADisplayLink) {
        guard !isInBackground else {
            updateLoop()
            return
        }
        let now = link.targetTimestamp
        let dt = lastTimestamp.map { now - $0 } ?? 1.0 / 60
        lastTimestamp = now
        renderer.advance(by: dt)
        draw()
    }

    /// One frame on the next run-loop turn when the loop is not running
    /// (still frame, theme or size change).
    private func setNeedsRender() {
        guard displayLink == nil, !isRedrawScheduled else { return }
        isRedrawScheduled = true
        Task { [weak self] in
            guard let self else { return }
            isRedrawScheduled = false
            guard window != nil, displayLink == nil, renderer.resources != nil else { return }
            guard !isInBackground else {
                isRedrawDeferred = true
                return
            }
            isRedrawDeferred = false
            draw()
        }
    }

    // MARK: Opacity

    private func setCanvasOpacity(_ opacity: CGFloat) {
        canvasOpacity = opacity
        guard isFirstFrameShown else { return }
        UIView.animate(withDuration: 0.4, delay: 0, options: [.curveEaseInOut, .beginFromCurrentState]) {
            self.alpha = opacity
        }
    }

    private func revealFirstFrame() {
        isFirstFrameShown = true
        UIView.animate(withDuration: 0.6, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]) {
            self.alpha = self.canvasOpacity
        }
    }
}

/// Breaks the display link → view retain cycle.
@MainActor
private final class DisplayLinkTarget: NSObject {
    weak var owner: Us2usSceneView?

    init(owner: Us2usSceneView) {
        self.owner = owner
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let owner else {
            link.invalidate()
            return
        }
        owner.tick(link)
    }
}

#Preview("Welcome · dark") {
    Us2usScene(theme: .dark)
        .frame(width: 402, height: 330)
        .background(Color(red: 0x16 / 255, green: 0x18 / 255, blue: 0x26 / 255))
}

#Preview("Welcome · light") {
    Us2usScene(theme: .light)
        .frame(width: 402, height: 330)
        .background(Color(red: 0xF3 / 255, green: 0xF5 / 255, blue: 0xFE / 255))
}
