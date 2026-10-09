import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// A square grid of QR modules (`true` = dark).
struct QRModules: Equatable, Sendable {
    let size: Int
    let bits: [Bool]

    subscript(row: Int, column: Int) -> Bool { bits[row * size + column] }

    init(size: Int, bits: [Bool]) {
        self.size = size
        self.bits = bits
    }

    /// The real, scannable QR code of `text` (error correction M), without
    /// the generator's quiet zone — the card's padding is the margin.
    init?(payload text: String) {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let image = filter.outputImage else { return nil }
        let width = Int(image.extent.width)
        let height = Int(image.extent.height)
        guard width > 0, width == height else { return nil }
        var pixels = [UInt8](repeating: 255, count: width * height)
        let context = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
        context.render(image, toBitmap: &pixels, rowBytes: width, bounds: image.extent, format: .L8, colorSpace: nil)
        // Trim the quiet zone (the finder patterns touch the code's edges).
        var minIndex = width
        var maxIndex = -1
        for y in 0..<height {
            for x in 0..<width where pixels[y * width + x] < 128 {
                minIndex = min(minIndex, x, y)
                maxIndex = max(maxIndex, x, y)
            }
        }
        guard maxIndex >= minIndex else { return nil }
        let size = maxIndex - minIndex + 1
        var bits = [Bool](repeating: false, count: size * size)
        for row in 0..<size {
            for column in 0..<size {
                bits[row * size + column] = pixels[(minIndex + row) * width + (minIndex + column)] < 128
            }
        }
        self.size = size
        self.bits = bits
    }

    /// The code of `text`, generated once (Core Image is far too slow to run
    /// on every body evaluation while a sheet or a cover animates).
    @MainActor static func cached(_ text: String) -> QRModules? {
        if let hit = cache[text] { return hit }
        guard let modules = QRModules(payload: text) else { return nil }
        if cache.count > 16 { cache.removeAll() }
        cache[text] = modules
        return modules
    }

    @MainActor private static var cache: [String: QRModules] = [:]
}

/// Draws modules as crisp squares (the design: `shape-rendering: crispEdges`,
/// each module 1.02 wide so neighbours never show a seam), `qrInk`.
struct QRModulesView: View {
    let modules: QRModules

    var body: some View {
        Canvas { context, size in
            let n = CGFloat(modules.size)
            let unit = min(size.width, size.height) / n
            var path = Path()
            for row in 0..<modules.size {
                for column in 0..<modules.size where modules[row, column] {
                    path.addRect(CGRect(x: CGFloat(column) * unit, y: CGFloat(row) * unit, width: unit * 1.02, height: unit * 1.02))
                }
            }
            context.fill(path, with: .color(Color.u2.qrInk), style: FillStyle(antialiased: false))
        }
        .accessibilityHidden(true)
    }
}

/// The 6.1 QR card — every QR the app shows (6.1 safety number, “Kodumu
/// göster” in 4.1 / 4.2 / 6.2, the 7.1 profile code): 220 × 220, `qrPaper`
/// (`#f3f5fe`) in both themes, r26, pad 16, ring `text·10%` + glow
/// `0 20px 50px -20px accent·60%`; modules `qrInk` (`#292b31`). The code
/// fades in once it is ready.
struct QRCodeCard: View {
    let modules: QRModules?
    let label: Text

    init(modules: QRModules?, label: Text) {
        self.modules = modules
        self.label = label
    }

    /// The card for a text payload (`ContactCode`, `SafetyQRCodec`); `nil` draws the empty card.
    init(payload: String?, label: Text) {
        self.init(modules: payload.flatMap { QRModules.cached($0) }, label: label)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.qrCard, style: .circular)
        ZStack {
            if let modules {
                QRModulesView(modules: modules)
                    .transition(.opacity)
            }
        }
        .frame(width: 188, height: 188)
        .padding(16)
        .background(shape.fill(Color.u2.qrPaper))
        .u2OuterRing(Color.u2.strokeCard, width: 1, in: shape)
        .u2BoxShadow(.glowQRCard, in: shape)
        .u2Animation(U2Motion.state, value: modules)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isImage)
    }
}

#Preview("QR card") {
    QRCodeCard(payload: ContactCode.encode(username: "selin.n"), label: Text(verbatim: "QR"))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.u2.bg)
}
