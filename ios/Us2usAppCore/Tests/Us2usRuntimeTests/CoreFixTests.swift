// Regression tests added while finishing the review fixes (2026-10-08).
// Each one failed against the code as it was before the fix next to it.

import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

@Suite("Core fixes: key lifetime", .serialized)
@MainActor
struct CoreFixKeyLifetimeTests {
    /// §5.4 step 4 on every path to the background: when iOS grants no
    /// background time (`beginBackgroundTask` returns `.invalid`), the app
    /// can be suspended right away. There is then no grace period: the DEK
    /// is zeroed and the store closed before `scenePhaseChanged` returns.
    @Test func noBackgroundTimeMeansNoGracePeriod() async throws {
        let fixture = try ReviewRuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let key = try #require(fixture.runtime.session?.key)
        let storage = try #require(fixture.runtime.session?.storage)

        fixture.tasks.refusesNewTasks = true
        fixture.runtime.scenePhaseChanged(.background)
        #expect(fixture.runtime.lockState == .locked)
        #expect(key.isWiped, "suspended without background time, with the DEK in memory")
        #expect(!storage.isOpen, "suspended with the database open")
        #expect(fixture.runtime.session == nil)

        // The next unlock works as after any lock.
        fixture.tasks.refusesNewTasks = false
        fixture.runtime.scenePhaseChanged(.active)
        try await fixture.runtime.unlock(code: ReviewRuntimeFixture.code)
        #expect(fixture.runtime.lockState == .unlocked)
    }
}

@Suite("Core fixes: media metadata (§7)")
struct CoreFixMediaMetadataTests {
    /// The fail-closed check after re-encoding a photo picked as a document
    /// refused every JPEG and PNG: ImageIO writes an `{Exif}` with the pixel
    /// dimensions for bare pixels too. Only identifying fields may make it
    /// refuse; the result must still carry none of them.
    @Test(arguments: [UTType.jpeg, UTType.png])
    func aPhotoSentAsAFileLosesItsMetadataInItsOwnFormat(_ type: UTType) throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        let name = "IMG_0002.\(type.preferredFilenameExtension ?? "img")"
        let source = temporary.url.appendingPathComponent(name)
        try Self.makeImage(type: type, at: source)
        let original = try Data(contentsOf: source)
        #expect(!MediaPreparation.identifyingMetadata(of: original).isEmpty, "control: the source carries metadata")

        let prepared = try MediaPreparation.prepareFile(
            at: source, directories: directories, fileName: name, contentType: type.preferredMIMEType ?? "")
        let sent = try Data(contentsOf: prepared.file)
        #expect(MediaPreparation.identifyingMetadata(of: sent).isEmpty)
        #expect(!MediaPreparation.metadataKeys(of: sent).contains(kCGImagePropertyGPSDictionary as String))
        #expect(prepared.contentType == type.preferredMIMEType)
        #expect(prepared.width == 48 && prepared.height == 32)
        for marker in ["secret place", "PhoneMaker", "2026:03:04 05:06:07", "Selin"] {
            #expect(sent.range(of: Data(marker.utf8)) == nil, "left in the sent file: \(marker)")
        }
    }

    static func makeImage(type: UTType, at url: URL) throws {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard
            let context = CGContext(
                data: nil, width: 48, height: 32, bitsPerComponent: 8, bytesPerRow: 0, space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { throw TestTimeout() }
        context.setFillColor(CGColor(red: 0.8, green: 0.3, blue: 0.1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 48, height: 32))
        guard let image = context.makeImage(),
            let destination = CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil)
        else { throw TestTimeout() }
        var properties: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 41.0082, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 28.9784, kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifUserComment: "secret place",
                kCGImagePropertyExifDateTimeOriginal: "2026:03:04 05:06:07",
            ],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "PhoneMaker", kCGImagePropertyTIFFModel: "Model X"],
        ]
        if type == .png {
            properties[kCGImagePropertyPNGDictionary] = [kCGImagePropertyPNGAuthor: "Selin"]
        }
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw TestTimeout() }
    }
}
