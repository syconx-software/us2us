#if DEBUG
    import SwiftUI
    import UIKit

    /// DEBUG `-U2FrameProbe YES`: counts main-thread frame drops for the
    /// performance UI tests, which also run on the simulator (where
    /// XCTest's hitch metrics report nothing).
    ///
    /// A display link at the display's highest rate measures the gap between
    /// consecutive frames; a gap longer than 1.5 frames is a hitch, and the
    /// time beyond one frame is hitch time (Apple's hitch-time-ratio
    /// definition). The cumulative counters are read through an invisible
    /// accessibility element (`debug.frameProbe`) — UI tests read them before
    /// and after an interaction and take the difference. Nothing leaves the
    /// process; nothing is drawn.
    @MainActor
    final class FrameProbe {
        static let shared = FrameProbe()

        static var isRequested: Bool { UserDefaults.standard.bool(forKey: "U2FrameProbe") }

        private var link: CADisplayLink?
        private var last: CFTimeInterval?
        private(set) var frames = 0
        private(set) var hitches = 0
        private(set) var hitchTime: CFTimeInterval = 0
        private(set) var longFrames50 = 0
        private(set) var worstFrame: CFTimeInterval = 0
        /// The same counts, only for frames inside an app animation interval
        /// (`U2Signposts.animation`: pushes, pops, pages, sheets, unlock) —
        /// the transitions themselves, without the UI test's own queries.
        private(set) var animationFrames = 0
        private(set) var animationHitches = 0
        private(set) var animationHitchTime: CFTimeInterval = 0
        private(set) var animationLongFrames50 = 0
        /// Main run-loop work spans (wake-up → sleep): how many would not fit
        /// a 120 Hz frame (8.3 ms) or a 60 Hz frame (16.7 ms), and the longest.
        /// On the 60 Hz simulator this shows what ProMotion would drop.
        private(set) var spansOver120 = 0
        private(set) var spansOver60 = 0
        private(set) var longestSpan: CFTimeInterval = 0
        private var spanStart: CFTimeInterval?
        private var observer: CFRunLoopObserver?
        private var activeAnimations = 0
        private var animationTouched = false

        func animationBegan() {
            activeAnimations += 1
            animationTouched = true
            animationStart = CACurrentMediaTime()
            animationEnd = nil
        }

        private var animationEnd: CFTimeInterval?

        private var animationStart: CFTimeInterval = 0
        private let logs = UserDefaults.standard.bool(forKey: "U2FrameProbeLog")

        func animationEnded() {
            activeAnimations = max(0, activeAnimations - 1)
            animationTouched = true
            if activeAnimations == 0 { animationEnd = CACurrentMediaTime() }
            // `-U2FrameProbeLog YES`: the totals after every transition, on the
            // console (`xcrun simctl launch --console-pty …`).
            if activeAnimations == 0, UserDefaults.standard.bool(forKey: "U2FrameProbeLog") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    print("FRAMEPROBE anim \(FrameProbe.shared.summary)")
                }
            }
        }
        /// Starts the probe (the reader is `FrameProbeReader`, mounted by
        /// `RootView` and `SecurityOverlayView`).
        func installIfRequested() {
            guard Self.isRequested, link == nil else { return }
            let link = CADisplayLink(target: Ticker(probe: self), selector: #selector(Ticker.tick(_:)))
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
            link.add(to: .main, forMode: .common)
            self.link = link
            let activities = CFRunLoopActivity.afterWaiting.rawValue | CFRunLoopActivity.beforeWaiting.rawValue
            observer = CFRunLoopObserverCreateWithHandler(nil, activities, true, 0) { _, activity in
                MainActor.assumeIsolated { FrameProbe.shared.runLoop(activity) }
            }
            CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
        }

        private func runLoop(_ activity: CFRunLoopActivity) {
            let now = CACurrentMediaTime()
            if activity == .afterWaiting {
                spanStart = now
            } else if activity == .beforeWaiting, let start = spanStart {
                let span = now - start
                spanStart = nil
                longestSpan = max(longestSpan, span)
                if span > 1.0 / 120 { spansOver120 += 1 }
                if span > 1.0 / 60 { spansOver60 += 1 }
            }
        }

        fileprivate func tick(_ link: CADisplayLink) {
            defer { last = link.timestamp }
            guard let last else { return }
            let gap = link.timestamp - last
            let frame = max(link.targetTimestamp - link.timestamp, 1.0 / 120)
            frames += 1
            worstFrame = max(worstFrame, gap)
            if gap > frame * 1.5 {
                hitches += 1
                hitchTime += gap - frame
            }
            if gap > 0.05 { longFrames50 += 1 }
            // A frame belongs to a transition when one was running (or began
            // or ended) since the previous frame — so the first frame, which
            // builds the incoming screen, counts.
            if activeAnimations > 0 || animationTouched {
                animationFrames += 1
                // Only the part of the gap after the animation began can
                // show in it: work finished before it started (a sheet
                // parked off screen while its content is built) is not a
                // dropped frame of the animation.
                // Likewise work after it ended (the 4.2 keyboard rising once
                // the sheet is at rest).
                let inside = min(link.timestamp, animationEnd ?? link.timestamp) - max(last, animationStart)
                if inside > frame * 1.5 {
                    animationHitches += 1
                    animationHitchTime += inside - frame
                    if logs {
                        print(String(format: "FRAMEPROBE hitch %.1f ms (%.1f ms of it inside the animation), frame ending %.1f ms after it began", gap * 1000, inside * 1000, (link.timestamp - animationStart) * 1000))
                    }
                }
                if inside > 0.05 { animationLongFrames50 += 1 }
            }
            animationTouched = false
        }

        /// `frames=… hitches=… hitchMs=… long50=… worstMs=… aframes=… ahitches=… ahitchMs=… along50=…`
        var summary: String {
            String(
                format: "frames=%d hitches=%d hitchMs=%.1f long50=%d worstMs=%.1f aframes=%d ahitches=%d ahitchMs=%.1f along50=%d over120=%d over60=%d longestSpanMs=%.1f",
                frames, hitches, hitchTime * 1000, longFrames50, worstFrame * 1000, animationFrames, animationHitches,
                animationHitchTime * 1000, animationLongFrames50, spansOver120, spansOver60, longestSpan * 1000)
        }
    }

    @MainActor
    private final class Ticker: NSObject {
        weak var probe: FrameProbe?
        init(probe: FrameProbe) { self.probe = probe }
        @objc func tick(_ link: CADisplayLink) { probe?.tick(link) }
    }

    /// The invisible reader (1 × 1 pt, no hit testing) — DEBUG builds with
    /// `-U2FrameProbe YES` only.
    struct FrameProbeReader: UIViewRepresentable {
        func makeUIView(context: Context) -> UIView { FrameProbeView(frame: .zero) }
        func updateUIView(_ view: UIView, context: Context) {}
    }

    extension View {
        /// Mounts the frame probe's reader when `-U2FrameProbe YES` is set.
        @ViewBuilder func u2FrameProbe() -> some View {
            if FrameProbe.isRequested {
                overlay(alignment: .topLeading) {
                    FrameProbeReader().frame(width: 1, height: 1).allowsHitTesting(false)
                }
            } else {
                self
            }
        }
    }

    /// Its accessibility value is computed when a UI test asks for it.
    private final class FrameProbeView: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            backgroundColor = .clear
            isAccessibilityElement = true
            accessibilityIdentifier = "debug.frameProbe"
            accessibilityLabel = "frame probe"
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { nil }

        override var accessibilityValue: String? {
            get { MainActor.assumeIsolated { FrameProbe.shared.summary } }
            set {}
        }
    }
#endif
