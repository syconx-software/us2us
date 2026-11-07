import ImageIO
import SwiftUI
import Testing
import UIKit
import UniformTypeIdentifiers

@testable import us2us

/// “Sohbet arka planı”: each chat keeps its own, and a picked photo is kept
/// small, upright and without its properties.
@MainActor
@Suite("Chat background")
struct ChatBackgroundTests {
    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    private func jpeg(width: Int, height: Int) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).jpegData(
            withCompressionQuality: 0.9
        ) { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    @Test("each chat keeps its own, and every change moves the revision")
    func perChat() async {
        let service = makeService()
        #expect(await service.chatBackground(of: "deniz") == .standard)
        #expect(await service.setChatBackground(.preset(.sage), for: "deniz"))
        #expect(service.chatBackgroundRevision == 1)
        #expect(await service.chatBackground(of: "deniz") == .preset(.sage))
        #expect(await service.chatBackground(of: "kaan") == .standard)
        #expect(await service.setChatBackground(.standard, for: "deniz"))
        #expect(service.chatBackgroundRevision == 2)
        #expect(await service.chatBackground(of: "deniz") == .standard)
    }

    @Test("the preset ids are the database's and Android's")
    func presetIds() {
        #expect(ChatBackgroundPreset.allCases.map(\.rawValue) == ["mist", "sage", "sand", "rose", "dusk", "ocean", "moss", "peach"])
    }

    @Test("every preset has a VoiceOver name in both languages")
    func presetNames() {
        for language in ["tr", "en"] {
            for preset in ChatBackgroundPreset.allCases {
                let name = StringTable.contact.string(preset.nameKey, locale: Locale(identifier: language))
                #expect(!name.isEmpty && !name.hasPrefix("background."), "\(preset) in \(language)")
            }
        }
    }

    @Test("a large photo is reduced to the longest side and loses its location, date and camera")
    func preparedPhoto() throws {
        let picture = try #require(UIImage(data: jpeg(width: 3_200, height: 2_000))?.cgImage)
        let tagged = NSMutableData()
        let destination = try #require(
            CGImageDestinationCreateWithData(tagged as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(
            destination, picture,
            [
                kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 41.0, kCGImagePropertyGPSLongitude: 29.0],
                kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:11:06 12:00:00"],
                kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Apple"],
            ] as CFDictionary)
        #expect(CGImageDestinationFinalize(destination))
        func properties(_ data: Data) throws -> [CFString: Any] {
            let source = try #require(CGImageSourceCreateWithData(data as CFData, nil))
            return try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        }
        #expect(try properties(tagged as Data)[kCGImagePropertyGPSDictionary] != nil)

        let prepared = try #require(ChatBackgroundPhoto.prepare(tagged as Data))
        #expect(prepared.count <= ChatBackgroundPhoto.maxBytes)
        let kept = try properties(prepared)
        #expect(kept[kCGImagePropertyPixelWidth] as? Int == ChatBackgroundPhoto.maxSide)
        #expect(kept[kCGImagePropertyPixelHeight] as? Int == 1_000)
        #expect(kept[kCGImagePropertyGPSDictionary] == nil)
        #expect((kept[kCGImagePropertyExifDictionary] as? [CFString: Any])?[kCGImagePropertyExifDateTimeOriginal] == nil)
        #expect((kept[kCGImagePropertyTIFFDictionary] as? [CFString: Any])?[kCGImagePropertyTIFFMake] == nil)
    }

    @Test("what is not an image is refused")
    func notAnImage() {
        #expect(ChatBackgroundPhoto.prepare(Data("not an image".utf8)) == nil)
    }

    @Test("a photo decodes to a look; one that no longer decodes reads as the app's own")
    func looks() async {
        #expect(await ChatBackgroundLook.make(.standard) == .standard)
        #expect(await ChatBackgroundLook.make(.preset(.dusk)) == .preset(.dusk))
        #expect(await ChatBackgroundLook.make(.photo(Data("broken".utf8))) == .standard)
        if case .photo = await ChatBackgroundLook.make(.photo(jpeg(width: 40, height: 40))) {
        } else {
            Issue.record("a valid JPEG should decode")
        }
    }
}
