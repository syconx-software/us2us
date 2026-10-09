import SwiftUI
import UIKit

/// The root of every screen.
///
/// The design measures everything from the *physical* top of a 402 × 874
/// screen with a 62 pt status bar and a 34 pt home indicator (headers sit
/// inside the status-bar band, e.g. icon buttons at y 56). `U2Screen` lays its
/// content out edge to edge on the `bg` colour and publishes the device's
/// safe-area insets as `\.u2SafeArea`, so components place themselves at
/// `safeTop + (designY − 62)` — identical on the iPhone 17 Pro and
/// proportionally right on every other iPhone.
///
/// Keyboard avoidance is unaffected (only the container safe area is ignored).
struct U2Screen<Content: View>: View {
    private let background: Color
    private let content: Content
    @State private var safeArea = U2SafeArea.design

    init(background: Color = Color.u2.bg, @ViewBuilder content: () -> Content) {
        self.background = background
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .environment(\.u2SafeArea, safeArea)
            .background {
                WindowSafeAreaReader { insets in
                    let new = U2SafeArea(top: insets.top, bottom: insets.bottom)
                    if new != safeArea { safeArea = new }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .background(background.ignoresSafeArea())
            .ignoresSafeArea(.container, edges: .vertical)
    }
}

/// Reports the *window's* safe-area insets (the physical status bar and home
/// indicator), whatever the SwiftUI ancestors ignore — paged `TabView`s,
/// full-screen covers and the security window all report the same values.
private struct WindowSafeAreaReader: UIViewRepresentable {
    let onChange: (UIEdgeInsets) -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onChange = onChange
    }

    final class ProbeView: UIView {
        var onChange: ((UIEdgeInsets) -> Void)?
        private var lastReported: UIEdgeInsets?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            report()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            report()
        }

        private func report() {
            guard let window else { return }
            let insets = window.safeAreaInsets
            guard insets != lastReported else { return }
            lastReported = insets
            // Not during SwiftUI's layout pass: publish on the next turn.
            Task { @MainActor [weak self] in self?.onChange?(insets) }
        }
    }
}

extension View {
    /// Pads the top so the view starts at design y `designY` (e.g. 56 for header buttons).
    func u2Top(_ designY: CGFloat) -> some View {
        modifier(DesignTopPadding(designY: designY))
    }

    /// Pads the bottom by a design bottom padding (e.g. 44 under a CTA), adjusted
    /// for this device's home-indicator inset.
    func u2Bottom(_ designPadding: CGFloat) -> some View {
        modifier(DesignBottomPadding(designPadding: designPadding))
    }
}

private struct DesignTopPadding: ViewModifier {
    let designY: CGFloat
    @Environment(\.u2SafeArea) private var safeArea
    func body(content: Content) -> some View {
        content.padding(.top, safeArea.top(designY))
    }
}

private struct DesignBottomPadding: ViewModifier {
    let designPadding: CGFloat
    @Environment(\.u2SafeArea) private var safeArea
    func body(content: Content) -> some View {
        content.padding(.bottom, max(designPadding - U2Size.designHomeIndicator, safeArea.bottom(designPadding)))
    }
}
