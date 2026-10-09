import SwiftUI

/// The navigation path of a `SoftNavigationStack`.
@Observable
@MainActor
final class U2Router<Route: Hashable & Sendable> {
    struct Entry: Identifiable, Hashable {
        let id = UUID()
        let route: Route
        /// Pushed, but not shown yet: the screen is being built in place,
        /// transparent, for one frame before its push animation starts.
        fileprivate(set) var isStaged = false
        /// Replaced (`replaceTop`), still on screen until its successor —
        /// staged above it — starts moving in. No longer on the path.
        fileprivate(set) var isLeaving = false
    }

    private(set) var entries: [Entry] = []

    /// Whether this stack is moving (a push / pop animating, the edge swipe
    /// under the finger) — published to its screens as `\.u2LayerMotion`.
    /// Only the views that ask (the welcome scene) observe it; the stack's
    /// own body does not, so starting and ending a transition costs no
    /// extra pass over the screens.
    @ObservationIgnored let motion = U2LayerMotion()
    @ObservationIgnored private var transitionsInFlight = 0
    @ObservationIgnored fileprivate var isSwiping = false {
        didSet { updateMotion() }
    }

    var path: [Route] { entries.filter { !$0.isLeaving }.map(\.route) }
    var top: Route? { entries.last { !$0.isLeaving }?.route }
    var isAtRoot: Bool { path.isEmpty }

    init(path: [Route] = []) {
        entries = path.map { Entry(route: $0) }
    }

    /// Pushes with the soft `screenPush` motion (cross-fade + 16 pt drift, 0.5 s).
    ///
    /// Like UIKit, the incoming screen is built before it moves: it is added
    /// (practically) transparent and untouchable, its first, heaviest frame
    /// is made while the current screen stays as it is, and the cross-fade
    /// starts on the next display frame — so the animation gets every one of
    /// its frames instead of losing its start to that build. It is on the
    /// path (`path`, `top`) at once.
    func push(_ route: Route) {
        stage(Entry(route: route)) { id in
            self.entries.firstIndex { $0.id == id }.map { self.entries[$0].isStaged = false }
        }
    }

    func pop() {
        guard !entries.isEmpty else { return }
        animate("pop") { _ = self.entries.removeLast() }
    }

    func popToRoot() {
        guard !entries.isEmpty else { return }
        animate("pop") { self.entries.removeAll() }
    }

    /// Replaces the top screen. `animated: false` swaps in one frame — for a
    /// screen that has already morphed into the one replacing it (4.4
    /// “Kabul et” → 5.1).
    func replaceTop(with route: Route, animated: Bool = true) {
        if animated {
            // The replaced screen leaves the path now and the screen at once
            // its successor has been built and starts moving in.
            if let last = entries.indices.last { entries[last].isLeaving = true }
            stage(Entry(route: route)) { id in
                self.entries.removeAll { $0.isLeaving }
                self.entries.firstIndex { $0.id == id }.map { self.entries[$0].isStaged = false }
            }
        } else {
            let replace = {
                if !self.entries.isEmpty { self.entries.removeLast() }
                self.entries.append(Entry(route: route))
            }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction, replace)
        }
    }

    /// Sets the whole path without animation (deep links, state restoration).
    func setPath(_ path: [Route]) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { entries = path.map { Entry(route: $0) } }
    }

    /// Removes the top entry without animation (end of an interactive swipe).
    fileprivate func finishInteractivePop() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { _ = entries.popLast() }
    }

    /// Adds `entry` staged (built, transparent) and, one display frame
    /// later, animates `reveal` (which unstages it) as a `screenPush`.
    private func stage(_ entry: Entry, reveal: @escaping (UUID) -> Void) {
        var staged = entry
        staged.isStaged = true
        let id = staged.id
        transitionsInFlight += 1
        updateMotion()
        let endBuild = U2Signposts.interval("pushBuild")
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { entries.append(staged) }
        AfterFrames.run(1) { [weak self] in
            endBuild()
            guard let self else { return }
            self.animate("push", counted: true) { reveal(id) }
        }
    }

    /// One `screenPush` (0.5 s, the design curve; Reduce Motion: 0.3 s
    /// cross-fade); the stack counts as moving until it has finished.
    private func animate(_ name: StaticString, counted: Bool = false, _ change: @escaping () -> Void) {
        let end = U2Signposts.animation(name)
        if !counted { transitionsInFlight += 1 }
        updateMotion()
        withU2Animation(U2Motion.screen, change) { [weak self] in
            end()
            guard let self else { return }
            self.transitionsInFlight = max(0, self.transitionsInFlight - 1)
            self.updateMotion()
        }
    }

    private func updateMotion() {
        motion.setMoving(transitionsInFlight > 0 || isSwiping)
    }
}

/// A navigation stack with the design's *soft* push: screens cross-fade with
/// a 16 pt horizontal drift on the design curve (0.5 s) instead of the system
/// slide, and the edge-swipe back follows the finger and settles on a
/// critically damped spring. Under Reduce Motion pushes become 0.3 s
/// cross-fades.
///
/// Every screen on the path stays in the hierarchy (hidden below the top), so
/// state and scroll positions survive — also while the lock covers the app.
/// The design draws its own headers; there is no navigation bar. VoiceOver's
/// escape gesture pops.
struct SoftNavigationStack<Route: Hashable & Sendable, Root: View, Destination: View>: View {
    @Bindable var router: U2Router<Route>
    let root: Root
    let destination: (Route) -> Destination
    var allowsSwipeBack: (Route) -> Bool

    @State private var swipeProgress: CGFloat = 0
    @State private var isSwiping = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2IsLayerVisible) private var isStackVisible
    @Environment(\.u2LayerMotion) private var enclosingMotion

    init(
        router: U2Router<Route>, allowsSwipeBack: @escaping (Route) -> Bool = { _ in true },
        @ViewBuilder root: () -> Root, @ViewBuilder destination: @escaping (Route) -> Destination
    ) {
        self.router = router
        self.allowsSwipeBack = allowsSwipeBack
        self.root = root()
        self.destination = destination
    }

    var body: some View {
        GeometryReader { proxy in
            let width = max(proxy.size.width, 1)
            // A staged screen (being built for its push) sits above the
            // visible top, transparent; it is not the top yet.
            let count = router.entries.count - (router.entries.last?.isStaged == true ? 1 : 0)
            ZStack {
                layer(level: 0, count: count) { root }
                ForEach(Array(router.entries.enumerated()), id: \.element.id) { index, entry in
                    layer(level: index + 1, count: count) { destination(entry.route) }
                        .transition(.u2Push)
                        .zIndex(Double(index + 1))
                }
            }
            .environment(\.u2LayerMotion, router.motion)
            .simultaneousGesture(swipeBack(width: width), including: canSwipe ? .all : .subviews)
        }
        .ignoresSafeArea(.container)
        .onAppear { router.motion.parent = enclosingMotion }
    }

    private var canSwipe: Bool {
        guard let top = router.top else { return false }
        return allowsSwipeBack(top)
    }

    /// One screen of the stack. `level` 0 is the root; `count` is the path length.
    private func layer<Content: View>(level: Int, count: Int, @ViewBuilder content: () -> Content) -> some View {
        let isTop = level == count
        let isBelowTop = level == count - 1
        // Above the top: the staged screen, where the push will bring it in from.
        let isStaged = level > count
        let progress = isSwiping || swipeProgress > 0 ? swipeProgress : 0
        // The top screen is fully transparent when a swipe completes, so
        // removing it afterwards changes nothing on screen. The staged screen
        // is drawn at 0.1 % — invisible (under half a level of 255 on any
        // pixel) but rendered, so its text and shapes are rasterized in the
        // build frame instead of on the push's first frames.
        let opacity: Double =
            if isStaged { 0.001 } else if isTop { 1 - progress } else if isBelowTop { progress } else { 0 }
        let offset: CGFloat =
            if reduceMotion { 0 } else if isStaged { 16 } else if isTop { progress * 64 } else if isBelowTop { -16 * (1 - progress) } else { -16 }
        // The top screen, the one below it while a swipe reveals it, and a
        // staged screen (so its own clocks are running when it appears).
        let isVisible = isStackVisible && (isTop || isStaged || (isBelowTop && progress > 0))
        return content()
            .environment(\.u2IsLayerVisible, isVisible)
            .opacity(opacity)
            .offset(x: offset)
            .allowsHitTesting(isTop && !isSwiping)
            .accessibilityAction(.escape) {
                if isTop && level > 0 { router.pop() }
            }
            // Last, so nothing below the top screen is exposed to VoiceOver.
            .modifier(HiddenLayerAccessibility(isHidden: !isTop))
    }

    /// The edge swipe back: the screen follows the finger, and on release it
    /// settles at the finger's own speed (critically damped — no stall, no
    /// overshoot) towards the previous screen or back into place.
    private func swipeBack(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { value in
                guard canSwipe else { return }
                if !isSwiping {
                    // Only a mostly horizontal drag starting at the leading edge.
                    guard value.startLocation.x < 28, abs(value.translation.width) > abs(value.translation.height) else { return }
                    isSwiping = true
                    router.isSwiping = true
                    endSwipeSignpost = U2Signposts.animation("swipeBack")
                }
                swipeProgress = min(max(value.translation.width / width, 0), 1)
            }
            .onEnded { value in
                guard isSwiping else { return }
                let projected = value.predictedEndTranslation.width / width
                let velocity = value.velocity.width
                if swipeProgress > 0.35 || projected > 0.6 {
                    let remaining = (1 - swipeProgress) * width
                    settle(to: 1, velocity: U2Motion.relativeVelocity(velocity, remaining: remaining)) {
                        router.finishInteractivePop()
                        swipeProgress = 0
                        isSwiping = false
                        router.isSwiping = false
                    }
                } else {
                    let remaining = swipeProgress * width
                    settle(to: 0, velocity: U2Motion.relativeVelocity(-velocity, remaining: remaining)) {
                        isSwiping = false
                        router.isSwiping = false
                    }
                }
            }
    }

    @State private var endSwipeSignpost: (() -> Void)?

    private func settle(to target: CGFloat, velocity: Double, completion: @escaping () -> Void) {
        let animation = U2Motion.resolve(U2Motion.spring(initialVelocity: velocity), reduceMotion: reduceMotion)
        withAnimation(animation, completionCriteria: .logicallyComplete) {
            swipeProgress = target
        } completion: {
            completion()
            endSwipeSignpost?()
            endSwipeSignpost = nil
        }
    }
}

/// Removes a covered screen from the accessibility tree entirely (its
/// subtree collapses into one hidden element). No conditional branches, so the
/// screen keeps its identity and state while covered.
private struct HiddenLayerAccessibility: ViewModifier {
    let isHidden: Bool

    func body(content: Content) -> some View {
        content
            .accessibilityElement(children: isHidden ? .ignore : .contain)
            .accessibilityHidden(isHidden)
    }
}

// MARK: - Layer visibility

private struct U2IsLayerVisibleKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    /// Whether the screen this view is on can currently be seen: `false` on
    /// screens kept alive under the top of a `SoftNavigationStack`. Views
    /// with continuous work (the 3D scene) pause while it is `false`.
    var u2IsLayerVisible: Bool {
        get { self[U2IsLayerVisibleKey.self] }
        set { self[U2IsLayerVisibleKey.self] = newValue }
    }
}

/// Whether a layer is moving, as a chain: a screen is settled when its own
/// stack (or pager) is not moving and neither is any enclosing one. Kept out
/// of the environment's values (an object, not a `Bool`), so a transition
/// starting or ending re-renders only the views that read `isSettled` — not
/// every screen on the stack.
@Observable
@MainActor
final class U2LayerMotion {
    /// The enclosing stack's or pager's motion.
    var parent: U2LayerMotion?
    private(set) var isMoving = false

    init(parent: U2LayerMotion? = nil) {
        self.parent = parent
    }

    /// Nothing this layer is on is moving.
    var isSettled: Bool { !isMoving && (parent?.isSettled ?? true) }

    func setMoving(_ moving: Bool) {
        if moving != isMoving { isMoving = moving }
    }
}

extension EnvironmentValues {
    /// The motion of the stack / pager this view is on: continuous GPU work
    /// (the welcome scene) holds its last frame until `isSettled`, so a
    /// transition has every frame to itself.
    @Entry var u2LayerMotion: U2LayerMotion? = nil
}
