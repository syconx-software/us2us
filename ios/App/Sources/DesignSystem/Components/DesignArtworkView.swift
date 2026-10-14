import ImageIO
import SwiftUI

/// Paints the design's CSS-gradient stand-ins for photos and video posters,
/// so fixture chats look exactly like the references (5.1, 5.2, 5.6).
struct DesignArtworkView: View {
    let artwork: DesignArtwork

    var body: some View {
        Canvas { context, size in
            switch artwork {
            case .nightPier: Self.paintNightPier(&context, size)
            case .sunsetVideo: Self.paintSunset(&context, size)
            }
        }
        .accessibilityHidden(true)
    }

    /// The artwork as a photo of its own proportions (the 5.1 photo 3 : 2,
    /// the 5.2 video poster 16 : 9), `pixelWidth` wide — what the viewer, the
    /// player and the fixtures' payloads show.
    @MainActor
    static func renderedImage(_ artwork: DesignArtwork, pixelWidth: CGFloat) -> UIImage? {
        let aspect: CGFloat = artwork == .sunsetVideo ? 16.0 / 9.0 : 3.0 / 2.0
        let size = CGSize(width: (pixelWidth / 2).rounded(), height: (pixelWidth / 2 / aspect).rounded())
        let renderer = ImageRenderer(content: DesignArtworkView(artwork: artwork).frame(width: size.width, height: size.height))
        renderer.scale = 2
        renderer.isOpaque = true
        return renderer.uiImage
    }

    private static func rgba(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) -> Color {
        Color(.sRGB, red: r / 255, green: g / 255, blue: b / 255, opacity: a)
    }

    /// `linear-gradient(to bottom, stops)`.
    private static func linear(_ context: inout GraphicsContext, _ size: CGSize, _ stops: [Gradient.Stop]) {
        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(
                Gradient(stops: stops), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
    }

    /// `radial-gradient(rx% ry% at cx% cy%, color, transparent end%)`.
    private static func ellipse(
        _ context: inout GraphicsContext, _ size: CGSize, rx: Double, ry: Double, cx: Double, cy: Double,
        color: Color, end: Double
    ) {
        var c = context
        c.translateBy(x: size.width * cx, y: size.height * cy)
        c.scaleBy(x: size.width * rx, y: size.height * ry)
        let gradient = Gradient(stops: [.init(color: color, location: 0), .init(color: color.opacity(0), location: end)])
        c.fill(
            Path(ellipseIn: CGRect(x: -1, y: -1, width: 2, height: 2)),
            with: .radialGradient(gradient, center: .zero, startRadius: 0, endRadius: 1))
    }

    /// A light: solid to `r`, fading out at `r + 1` (`radial-gradient(circle at x y, C r, transparent r+1)`).
    private static func light(
        _ context: inout GraphicsContext, _ size: CGSize, x: Double, y: Double, r: Double, color: Color
    ) {
        let center = CGPoint(x: size.width * x, y: size.height * y)
        let edge = r + 1
        let gradient = Gradient(stops: [
            .init(color: color, location: 0), .init(color: color, location: r / edge),
            .init(color: color.opacity(0), location: 1),
        ])
        context.fill(
            Path(ellipseIn: CGRect(x: center.x - edge, y: center.y - edge, width: edge * 2, height: edge * 2)),
            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: edge))
    }

    /// 5.1 “Şu yeni yer, Moda'da”.
    static func paintNightPier(_ context: inout GraphicsContext, _ size: CGSize) {
        linear(
            &context, size,
            [
                .init(color: rgba(28, 30, 62), location: 0),
                .init(color: rgba(46, 43, 94), location: 0.44),
                .init(color: rgba(70, 58, 108), location: 0.56),
                .init(color: rgba(21, 22, 42), location: 0.58),
                .init(color: rgba(13, 14, 25), location: 1),
            ])
        ellipse(&context, size, rx: 0.40, ry: 0.30, cx: 0.74, cy: 0.22, color: rgba(220, 214, 255, 0.35), end: 0.7)
        ellipse(&context, size, rx: 1.30, ry: 0.30, cx: 0.50, cy: 0.57, color: rgba(255, 190, 140, 0.22), end: 0.7)
        let lights: [(Double, Double, Double, Color)] = [
            (0.16, 0.57, 1.4, rgba(255, 210, 150, 0.95)),
            (0.24, 0.55, 1.1, rgba(255, 222, 172, 0.9)),
            (0.37, 0.58, 1.6, rgba(255, 200, 140, 0.9)),
            (0.49, 0.56, 1.1, rgba(200, 206, 255, 0.85)),
            (0.63, 0.54, 1.8, rgba(255, 214, 160, 0.95)),
            (0.77, 0.57, 1.2, rgba(255, 226, 182, 0.85)),
            (0.88, 0.55, 1.4, rgba(255, 205, 150, 0.9)),
        ]
        for (x, y, r, color) in lights {
            light(&context, size, x: x, y: y, r: r, color: color)
        }
    }

    /// 5.2 video poster.
    static func paintSunset(_ context: inout GraphicsContext, _ size: CGSize) {
        linear(
            &context, size,
            [
                .init(color: rgba(44, 39, 82), location: 0),
                .init(color: rgba(107, 74, 124), location: 0.38),
                .init(color: rgba(199, 122, 114), location: 0.57),
                .init(color: rgba(35, 32, 61), location: 0.59),
                .init(color: rgba(18, 18, 34), location: 1),
            ])
        // circle at 62 % 58 %, farthest-corner: stops at 8 / 12 / 30 % of the ray.
        let center = CGPoint(x: size.width * 0.62, y: size.height * 0.58)
        let corners = [CGPoint.zero, CGPoint(x: size.width, y: 0), CGPoint(x: 0, y: size.height), CGPoint(x: size.width, y: size.height)]
        let ray = corners.map { hypot($0.x - center.x, $0.y - center.y) }.max() ?? 1
        let gradient = Gradient(stops: [
            .init(color: rgba(255, 214, 170, 0.95), location: 0),
            .init(color: rgba(255, 214, 170, 0.95), location: 0.08),
            .init(color: rgba(255, 170, 120, 0.5), location: 0.12),
            .init(color: rgba(255, 170, 120, 0), location: 0.30),
        ])
        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: ray))
    }
}

/// Media from a `MediaSource`: painted design artwork, or a file.
struct MediaView: View {
    let source: MediaSource

    var body: some View {
        switch source {
        case .designArtwork(let artwork):
            DesignArtworkView(artwork: artwork)
        case .file(let url):
            FileImageView(url: url)
        case .memory(let data):
            MemoryImageView(data: data)
        }
    }
}

/// An image file, decoded off the main thread (never in `body`) and shown
/// over `surface` like `MemoryImageView`.
struct FileImageView: View {
    let url: URL
    var maxPixel: Int = 2_048
    @State private var decoded: UIImage?

    var body: some View {
        ZStack {
            Color.u2.surface
            if let decoded {
                Image(uiImage: decoded).resizable().scaledToFill().transition(.opacity)
            }
        }
        .task(id: url) {
            let source = url
            let limit = maxPixel
            let image = await Task.detached(priority: .userInitiated) { FileImageView.decode(source, maxPixel: limit) }.value
            withAnimation(U2Motion.micro) { decoded = image }
        }
    }

    nonisolated static func decode(_ url: URL, maxPixel: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixel),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// In-memory image bytes (live media), decoded off the main thread and
/// shown over `surface` — the same frame as a file image, nothing moves.
struct MemoryImageView: View {
    let data: Data
    var maxPixel: Int = 2_048
    @State private var decoded: UIImage?

    var body: some View {
        ZStack {
            Color.u2.surface
            if let decoded {
                Image(uiImage: decoded).resizable().scaledToFill().transition(.opacity)
            }
        }
        .task(id: MemoryImageKey(count: data.count, hash: data.hashValue, maxPixel: maxPixel)) {
            let bytes = data
            let limit = maxPixel
            let image = await Task.detached(priority: .userInitiated) { MemoryImageView.decode(bytes, maxPixel: limit) }.value
            withAnimation(U2Motion.micro) { decoded = image }
        }
    }

    nonisolated static func decode(_ data: Data, maxPixel: Int) -> UIImage? {
        guard !data.isEmpty, let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixel),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct MemoryImageKey: Hashable {
    let count: Int
    let hash: Int
    let maxPixel: Int
}

#Preview("Design artwork") {
    VStack(spacing: 16) {
        DesignArtworkView(artwork: .nightPier).frame(width: 258, height: 176).clipShape(RoundedRectangle(cornerRadius: 15))
        DesignArtworkView(artwork: .sunsetVideo).frame(width: 236, height: 124).clipShape(RoundedRectangle(cornerRadius: 15))
    }
    .padding(30)
    .background(Color.u2.bg)
}
