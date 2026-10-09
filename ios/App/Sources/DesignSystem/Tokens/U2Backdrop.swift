import SwiftUI
import UIKit

/// Backdrop blurs of the design (tokens.md › Blur & translucency).
///
/// | name | CSS |
/// |---|---|
/// | header | blur(20px) saturate(140 %) over bg·80 % |
/// | menu | blur(20px) over surface·94 % |
/// | banner | blur(16px) over surface·92 % |
/// | warning | blur(20px) over surface·92 % |
/// | mediaButton | blur(8px) over #0c0d16@45 % |
///
/// iOS has no public backdrop filter with an arbitrary radius, so the blur
/// comes from a system material (`UIVisualEffectView` + `UIBlurEffect`, public
/// API only) and the design's own tint is drawn on top at its token opacity.
/// Every tint is ≥ 80 % opaque except the media button's, so the material only
/// shows through as softness — not as a hue shift (the reason tokens.md warns
/// against system materials).
///
/// The material also lifts what it blurs (a luminance curve and a brightness
/// lift), and that lift shows through the tint: over a plain `bg` the header
/// would be a grey band instead of the design's `bg·80 %` over `bg`. The
/// header's tint is therefore drawn in a *material-compensated* colour
/// (`compensatedTint`): the same opacity, a colour chosen so that tint over
/// material-over-`bg` lands exactly on `bg` — the design at rest — while
/// content scrolling underneath still blurs through.
enum U2BackdropStyle: Sendable {
    case header
    case menu
    case banner
    case warning
    case mediaButton

    @MainActor var tint: Color {
        switch self {
        case .header: Color.u2.headerFill
        case .menu: Color.u2.menuFill
        case .banner, .warning: Color.u2.floatingFill
        case .mediaButton: Color.u2.onMediaGlass(0.45)
        }
    }

    /// The tint actually drawn over the material (see the type comment).
    @MainActor var compensatedTint: Color {
        switch self {
        case .header: Self.headerOverMaterial
        default: tint
        }
    }

    /// `headerFill` (bg·80 %) corrected for the ultra-thin material's lift.
    /// Measured over a plain `bg` (iPhone 17 Pro simulator, iOS 26) with the
    /// uncorrected tint: dark #1a1c29 (lift +4 +4 +3), light #f2f4fc
    /// (−1 −1 −2). Corrected colour = bg − 1.25 · lift (the material shows
    /// through at 20 %), same 80 % opacity — at rest the header is `bg`.
    private static let headerOverMaterial = Color(
        U2ThemedColor(dark: 0x111322, darkAlpha: 0.8, light: 0xf4f6ff, lightAlpha: 0.8))
}

/// The blur layer behind a translucent surface (place it in `.background`).
struct U2Backdrop: View {
    let style: U2BackdropStyle
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            SystemBlurView(style: blurStyle)
            style.compensatedTint
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var blurStyle: UIBlurEffect.Style {
        switch style {
        case .mediaButton: .systemUltraThinMaterialDark
        default: colorScheme == .light ? .systemUltraThinMaterialLight : .systemUltraThinMaterialDark
        }
    }
}

/// A plain system blur (public `UIVisualEffectView` / `UIBlurEffect` only).
private struct SystemBlurView: UIViewRepresentable {
    let style: UIBlurEffect.Style

    func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: UIBlurEffect(style: style))
    }

    func updateUIView(_ view: UIVisualEffectView, context: Context) {
        view.effect = UIBlurEffect(style: style)
    }
}

extension View {
    /// Fills the view's background with a design backdrop (blur + token tint),
    /// clipped to `shape`.
    func u2Backdrop<S: Shape>(_ style: U2BackdropStyle, in shape: S) -> some View {
        background(U2Backdrop(style: style).clipShape(shape))
    }
}
