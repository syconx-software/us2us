import os

/// Animation intervals for the XCUITest performance metrics
/// (`FluidityPerformanceUITests`: `XCTOSSignpostMetric` reports each
/// interval's duration, frame rate and hitches).
///
/// DEBUG builds only — release builds emit nothing. The intervals carry no
/// content and no identifiers: only which kind of transition ran.
enum U2Signposts: Sendable {
    nonisolated static let subsystem = "com.syconx.us2us"

    /// Begins an animation interval in `category`; call the returned closure
    /// when the animation has finished.
    nonisolated static func animation(_ name: StaticString, category: Category = .navigation) -> () -> Void {
        #if DEBUG
            let signposter = category.signposter
            let state = signposter.beginAnimationInterval(name, id: signposter.makeSignpostID())
            MainActor.assumeIsolated { FrameProbe.shared.animationBegan() }
            return {
                signposter.endInterval(name, state)
                MainActor.assumeIsolated { FrameProbe.shared.animationEnded() }
            }
        #else
            return {}
        #endif
    }

    /// Begins a plain interval (no frame-probe attribution): work before an
    /// animation, such as building a pushed screen.
    nonisolated static func interval(_ name: StaticString, category: Category = .navigation) -> () -> Void {
        #if DEBUG
            let signposter = category.signposter
            let state = signposter.beginInterval(name, id: signposter.makeSignpostID())
            return { signposter.endInterval(name, state) }
        #else
            return {}
        #endif
    }

    enum Category: Sendable {
        /// Stack pushes / pops / the edge swipe, pager pages.
        case navigation
        /// Sheets, the lock and the privacy cover.
        case presentation

        #if DEBUG
            fileprivate nonisolated var signposter: OSSignposter {
                switch self {
                case .navigation: U2Signposts.navigation
                case .presentation: U2Signposts.presentation
                }
            }
        #endif
    }

    #if DEBUG
        fileprivate nonisolated static let navigation = OSSignposter(subsystem: subsystem, category: "navigation")
        fileprivate nonisolated static let presentation = OSSignposter(subsystem: subsystem, category: "presentation")
    #endif
}
