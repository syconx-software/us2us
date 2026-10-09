import SwiftUI
import UIKit

// Helpers only 06 uses (texts, the camera surface).

// MARK: - Texts

enum VerificationText {
    /// “8 Ekim 2026” / “8 October 2026” (the design writes the day first in both languages).
    static func longDate(_ date: Date, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = .current
        formatter.dateFormat = "d MMMM y"
        return formatter.string(from: date)
    }

    /// “8 Ekim 2026 · 18:52 · yüz yüze” (6.3 pill).
    static func verifiedLine(_ info: SafetyNumberInfo, locale: Locale) -> String? {
        guard let date = info.verifiedAt else { return nil }
        let method: String.LocalizationValue =
            info.method == .manual ? "verified.method.manual" : "verified.method.faceToFace"
        return [longDate(date, locale: locale), U2Format.clockTime(date, locale: locale), StringTable.verification.string(method, locale: locale)]
            .joined(separator: " · ")
    }

    /// The digits read group by group (“08223, 78626, …”).
    static func spokenDigits(_ groups: [String]) -> String {
        groups.map { $0.map(String.init).joined(separator: " ") }.joined(separator: ", ")
    }

    /// The text shared from 6.1 › share: header + the 3 × 4 groups.
    static func shareText(own: String, peer: String, groups: [String], locale: Locale) -> String {
        let header = StringTable.verification.string("safety.share.header \(own) \(peer)", locale: locale)
        let rows = stride(from: 0, to: groups.count, by: 4).map { groups[$0..<min($0 + 4, groups.count)].joined(separator: " ") }
        return ([header, ""] + rows).joined(separator: "\n")
    }
}

// MARK: - Camera-surface pieces (6.2)

/// The 6.2 top bar: 44 high at design y 56, pad 0 16, close and torch as
/// camera-surface C4 buttons, `navTitle` in `#f5f4ff` centred.
struct CameraTopBar<Trailing: View>: View {
    let title: Text
    let onClose: () -> Void
    @ViewBuilder let trailing: Trailing

    var body: some View {
        ZStack {
            title
                .u2Font(.navTitle, matchLineBox: false)
                .foregroundStyle(Color.u2.onMedia)
                .lineLimit(1)
                .frame(maxWidth: 250)
                .accessibilityAddTraits(.isHeader)
            HStack {
                U2GlassIconButton(.x, surface: .camera, label: Text("a11y.close"), action: onClose)
                    .accessibilityIdentifier("scan.close")
                Spacer(minLength: 0)
                trailing
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .frame(height: U2Size.iconButton)
        .u2Top(U2Spacing.headerTopButtons)
    }
}

/// One viewfinder corner: 46 × 46, a 3 pt `#d2cefd` border on two sides with
/// an 18 pt outer radius (CSS border + border-radius), drawn top-left.
struct ScanCornerShape: Shape {
    var lineWidth: CGFloat = 3
    var radius: CGFloat = U2Radius.scanCorner

    func path(in rect: CGRect) -> Path {
        let inset = lineWidth / 2
        var path = Path()
        path.move(to: CGPoint(x: inset, y: rect.maxY))
        path.addLine(to: CGPoint(x: inset, y: radius))
        path.addArc(
            tangent1End: CGPoint(x: inset, y: inset), tangent2End: CGPoint(x: radius, y: inset),
            radius: radius - inset)
        path.addLine(to: CGPoint(x: rect.maxX, y: inset))
        return path
    }
}

/// The 6.2 camera placeholder (design): `#0c0d14` with a radial
/// `#2a2d3e → #0c0d14` vignette (60 % × 45 % at 50 % / 45 %, end at 80 %)
/// and a 6 pt dot grid of `#e9e9ed@8%` dots (r 1, soft to 1.5).
struct CameraPlaceholderBackdrop: View {
    var body: some View {
        ZStack {
            Color.u2.cameraBg
            Canvas { context, size in
                let center = CGPoint(x: size.width * 0.5, y: size.height * 0.45)
                let radiusX = size.width * 0.6
                let radiusY = size.height * 0.45
                var ellipse = context
                ellipse.translateBy(x: center.x, y: center.y)
                ellipse.scaleBy(x: radiusX, y: radiusY)
                let gradient = Gradient(stops: [
                    .init(color: Self.vignetteCenter, location: 0),
                    .init(color: Color.u2.cameraBg, location: 0.8),
                    .init(color: Color.u2.cameraBg, location: 1),
                ])
                let bounds = CGRect(
                    x: -center.x / radiusX, y: -center.y / radiusY, width: size.width / radiusX, height: size.height / radiusY)
                ellipse.fill(
                    Path(bounds), with: .radialGradient(gradient, center: .zero, startRadius: 0, endRadius: 1))
            }
            Rectangle()
                .fill(ImagePaint(image: Image(uiImage: Self.dotTile), scale: 1 / Self.tileScale))
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// `#2a2d3e` (6.2 placeholder only; not a token).
    private static let vignetteCenter = Color(U2ThemedColor(fixed: 0x2a2d3e))

    private static let tileScale: CGFloat = 3

    /// One 6 × 6 pt tile with the dot centred (rendered at 3×).
    private static let dotTile: UIImage = {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let side = 6 * tileScale
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { context in
            let cg = context.cgContext
            let color = UIColor(red: 233 / 255, green: 233 / 255, blue: 237 / 255, alpha: 0.08)
            let colors = [color.cgColor, color.cgColor, color.withAlphaComponent(0).cgColor] as CFArray
            let space = CGColorSpace(name: CGColorSpace.sRGB)
            guard let gradient = CGGradient(colorsSpace: space, colors: colors, locations: [0, 1 / 1.5, 1]) else { return }
            let center = CGPoint(x: side / 2, y: side / 2)
            cg.drawRadialGradient(
                gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: 1.5 * tileScale, options: [])
        }
    }()
}
