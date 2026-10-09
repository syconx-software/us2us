import SwiftUI

/// C24 — the dot-grid backdrop: dots r 1 (soft edge to 1.6) every 18 pt,
/// faded out by an elliptical radial mask (opaque centre → transparent at 75 %).
struct DotGridBackdrop: View {
    struct Mask: Sendable {
        /// Ellipse radii as fractions of the width / height.
        var radiusX: CGFloat
        var radiusY: CGFloat
        /// Centre as fractions of the width / height.
        var centerX: CGFloat
        var centerY: CGFloat

        /// 1.1 welcome: 85 % × 42 % at 50 % / 28 %.
        static let welcome = Mask(radiusX: 0.85, radiusY: 0.42, centerX: 0.5, centerY: 0.28)
        /// 3.1 Face ID: 70 % × 38 % at 50 % / 46 %.
        static let lock = Mask(radiusX: 0.70, radiusY: 0.38, centerX: 0.5, centerY: 0.46)
        /// 6.3 verified: 70 % × 36 % at 50 % / 40 %.
        static let verified = Mask(radiusX: 0.70, radiusY: 0.36, centerX: 0.5, centerY: 0.40)
    }

    var mask: Mask = .welcome
    /// `dotGrid` (16 %) on 1.1; `dotGridSoft` (14 %) on 3.1 / 6.3.
    var color: Color = Color.u2.dotGrid
    var pitch: CGFloat = 18

    var body: some View {
        Canvas { context, size in
            let gradient = Gradient(stops: [
                .init(color: color, location: 0),
                .init(color: color, location: 1 / 1.6),
                .init(color: color.opacity(0), location: 1),
            ])
            let cx = size.width * mask.centerX
            let cy = size.height * mask.centerY
            let rx = size.width * mask.radiusX
            let ry = size.height * mask.radiusY
            var y = pitch / 2
            while y < size.height {
                var x = pitch / 2
                while x < size.width {
                    let d = (pow((x - cx) / rx, 2) + pow((y - cy) / ry, 2)).squareRoot()
                    let alpha = max(0, 1 - d / 0.75)
                    if alpha > 0.01 {
                        var dot = context
                        dot.opacity = alpha
                        let center = CGPoint(x: x, y: y)
                        dot.fill(
                            Path(ellipseIn: CGRect(x: x - 1.6, y: y - 1.6, width: 3.2, height: 3.2)),
                            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: 1.6))
                    }
                    x += pitch
                }
                y += pitch
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

#Preview("Dot grid") {
    ZStack {
        Color.u2.bg
        DotGridBackdrop()
    }
    .ignoresSafeArea()
}
