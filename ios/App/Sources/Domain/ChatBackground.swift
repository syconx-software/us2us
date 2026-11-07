import Foundation
import ImageIO
import UniformTypeIdentifiers

/// What one chat shows behind its messages on this phone. It is never sent:
/// the other person keeps their own, and no backup carries it.
enum ChatBackground: Equatable, Sendable {
    /// The app's own background, following the theme.
    case standard
    case preset(ChatBackgroundPreset)
    /// A JPEG from `ChatBackgroundPhoto`.
    case photo(Data)
}

/// The built-in backgrounds. The raw values are what the database keeps and
/// are the same on Android; a colour may change, an id may not.
enum ChatBackgroundPreset: String, CaseIterable, Identifiable, Sendable {
    case mist, sage, sand, rose, dusk, ocean, moss, peach

    var id: String { rawValue }
}

/// A picked photo as a chat background keeps it: upright, at most `maxSide`,
/// and a JPEG with no properties (no location, no camera, no date).
enum ChatBackgroundPhoto {
    static let maxSide = 1_600
    /// The storage's limit (`ChatBackgroundChoice.maxPhotoBytes`).
    static let maxBytes = 2_500_000

    /// `nil` when the bytes are not an image or stay over the limit.
    nonisolated static func prepare(_ data: Data) -> Data? {
        guard
            let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
            CGImageSourceGetCount(source) > 0,
            let image = CGImageSourceCreateThumbnailAtIndex(
                source, 0,
                [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxSide,
                    kCGImageSourceShouldCacheImmediately: true,
                ] as CFDictionary)
        else { return nil }
        for quality in [0.8, 0.6, 0.4] {
            if let jpeg = encode(image, quality: quality), jpeg.count <= maxBytes { return jpeg }
        }
        return nil
    }

    nonisolated private static func encode(_ image: CGImage, quality: Double) -> Data? {
        let output = NSMutableData()
        guard
            let destination = CGImageDestinationCreateWithData(
                output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(
            destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}
