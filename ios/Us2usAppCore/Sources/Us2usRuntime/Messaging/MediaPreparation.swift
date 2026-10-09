import AVFoundation
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Us2usKit

/// A file ready to be sent: compressed and stripped of metadata, in the
/// staging directory. ``MessagingEngine/send(_:to:quote:viewOnce:)`` moves it
/// into encrypted storage.
public struct PreparedMedia: Sendable {
    public let file: URL
    public let kind: AttachmentItem.Kind
    public let contentType: String
    public let fileName: String?
    public let size: UInt64
    public let width: UInt32
    public let height: UInt32
    public let durationMs: UInt32
    /// Inline JPEG ≤ 10 KiB.
    public let thumbnail: Data?
    public let caption: String?
    /// Voice notes: up to 100 amplitude samples.
    public let waveform: Data?

    public init(
        file: URL, kind: AttachmentItem.Kind, contentType: String, fileName: String? = nil, size: UInt64,
        width: UInt32 = 0, height: UInt32 = 0, durationMs: UInt32 = 0, thumbnail: Data? = nil, caption: String? = nil,
        waveform: Data? = nil
    ) {
        self.file = file
        self.kind = kind
        self.contentType = contentType
        self.fileName = fileName
        self.size = size
        self.width = width
        self.height = height
        self.durationMs = durationMs
        self.thumbnail = thumbnail
        self.caption = caption
        self.waveform = waveform
    }

    /// The `Attachment` without its relay pointer; the outbox uploads the
    /// blob and fills in media id, delete token, key and digest.
    var pendingAttachment: BRPAttachment {
        var attachment = BRPAttachment()
        switch kind {
        case .image: attachment.kind = .image
        case .video: attachment.kind = .video
        case .voice: attachment.kind = .voice
        case .file: attachment.kind = .file
        case .stickerImage: attachment.kind = .stickerImage
        }
        attachment.contentType = contentType
        attachment.fileName = fileName ?? ""
        attachment.size = size
        attachment.width = width
        attachment.height = height
        attachment.durationMs = durationMs
        attachment.thumbnail = thumbnail ?? Data()
        attachment.caption = caption ?? ""
        attachment.waveform = waveform ?? Data()
        return attachment
    }
}

public enum MediaPreparationError: Error, Equatable, Sendable {
    case unreadable
    case encodingFailed
    /// Metadata could not be removed; nothing is sent (§7 is a MUST).
    case metadataNotRemovable
    /// A video picked as a document: use ``MediaPreparation/prepareVideoFile(at:directories:fileName:caption:)``
    /// (asynchronous), which removes its metadata.
    case requiresVideoPreparation
}

/// Phone-side media work of PROTOCOL.md §1.1 and §7: images ≤ 2048 px long
/// edge as JPEG q 0.8, video ≤ 720p, an inline thumbnail ≤ 10 KiB, and — a
/// MUST of §7 — no EXIF/GPS or other metadata unless the user explicitly
/// keeps it. Images are re-encoded from pixels only, so nothing of the
/// original container (EXIF, GPS, TIFF, maker notes, XMP, IPTC) survives.
public enum MediaPreparation {
    public static let maxImageEdge = 2048
    public static let imageQuality = 0.8
    static let thumbnailEdges = [160, 120, 96, 64]

    // MARK: Images

    public static func prepareImage(
        at source: URL, directories: AppDirectories, caption: String? = nil, kind: AttachmentItem.Kind = .image
    ) throws(MediaPreparationError) -> PreparedMedia {
        guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { throw .unreadable }
        guard let image = downscaled(imageSource, maxEdge: maxImageEdge) else { throw .unreadable }
        let output = directories.stagingFile(extension: "jpg")
        guard let jpeg = encodeJPEG(image, quality: imageQuality) else { throw .encodingFailed }
        do {
            try ProtectedFiles.write(jpeg, to: output)
        } catch {
            throw .encodingFailed
        }
        return PreparedMedia(
            file: output, kind: kind, contentType: "image/jpeg", size: UInt64(jpeg.count), width: UInt32(image.width),
            height: UInt32(image.height), thumbnail: thumbnail(of: image), caption: caption)
    }

    /// Decodes at most `maxEdge` pixels on the long edge, with the EXIF
    /// orientation applied to the pixels (so no orientation tag is needed).
    static func downscaled(_ source: CGImageSource, maxEdge: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxEdge,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// JPEG from pixels only: no properties are passed, so no metadata is written.
    static func encodeJPEG(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(
            destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    /// A small JPEG for the bubble, ≤ 10 KiB (brp.proto `Attachment.thumbnail`).
    static func thumbnail(of image: CGImage) -> Data? {
        guard let encoded = encodeJPEG(image, quality: 0.9),
            let source = CGImageSourceCreateWithData(encoded as CFData, nil)
        else { return nil }
        for edge in thumbnailEdges {
            guard let small = downscaled(source, maxEdge: edge) else { continue }
            for quality in [0.6, 0.45, 0.3] {
                if let data = encodeJPEG(small, quality: quality), data.count <= ContentLimits.maxThumbnailBytes {
                    return data
                }
            }
        }
        return nil
    }

    /// The metadata dictionary keys an encoded image carries (for tests).
    static func metadataKeys(of data: Data) -> Set<String> {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
        else { return [] }
        return Set(properties.keys)
    }

    /// Image metadata that can identify a person, place, device or time —
    /// anything beyond what ImageIO itself writes for bare pixels. Encoding
    /// a pixel-only image still yields an `{Exif}` with the pixel dimensions
    /// (JPEG, PNG) and a `{TIFF}` with orientation and tiling (HEIC, TIFF):
    /// those are allowed; every other Exif / TIFF field, and any GPS, IPTC,
    /// auxiliary-Exif, maker-note, DNG or Photoshop dictionary, is reported
    /// as `"{Dictionary}.Key"` (or the dictionary alone).
    static func identifyingMetadata(of data: Data) -> [String] {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return [] }
        let allowed: [String: Set<String>] = [
            kCGImagePropertyExifDictionary as String: [
                kCGImagePropertyExifPixelXDimension as String, kCGImagePropertyExifPixelYDimension as String,
                kCGImagePropertyExifColorSpace as String,
            ],
            kCGImagePropertyTIFFDictionary as String: [
                kCGImagePropertyTIFFOrientation as String, kCGImagePropertyTIFFTileWidth as String,
                kCGImagePropertyTIFFTileLength as String, kCGImagePropertyTIFFCompression as String,
                kCGImagePropertyTIFFPhotometricInterpretation as String, kCGImagePropertyTIFFResolutionUnit as String,
                kCGImagePropertyTIFFXResolution as String, kCGImagePropertyTIFFYResolution as String,
            ],
        ]
        let forbidden: Set<String> = [
            kCGImagePropertyGPSDictionary as String, kCGImagePropertyIPTCDictionary as String,
            kCGImagePropertyExifAuxDictionary as String, kCGImagePropertyDNGDictionary as String,
            kCGImageProperty8BIMDictionary as String, kCGImagePropertyCIFFDictionary as String,
        ]
        // PNG text chunks (author, times, software, comments).
        let pngText: Set<String> = [
            kCGImagePropertyPNGAuthor as String, kCGImagePropertyPNGComment as String,
            kCGImagePropertyPNGCopyright as String, kCGImagePropertyPNGCreationTime as String,
            kCGImagePropertyPNGDescription as String, kCGImagePropertyPNGModificationTime as String,
            kCGImagePropertyPNGSoftware as String, kCGImagePropertyPNGTitle as String,
            kCGImagePropertyPNGDisclaimer as String, kCGImagePropertyPNGWarning as String,
        ]
        var found: [String] = []
        for index in 0..<CGImageSourceGetCount(source) {
            guard let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [String: Any] else {
                continue
            }
            for (key, value) in properties {
                if forbidden.contains(key) || key.hasPrefix("{Maker") {
                    found.append(key)
                } else if let permitted = allowed[key] {
                    let fields = (value as? [String: Any])?.keys.map { $0 } ?? []
                    found += fields.filter { !permitted.contains($0) }.map { "\(key).\($0)" }
                } else if key == kCGImagePropertyPNGDictionary as String {
                    let fields = (value as? [String: Any])?.keys.map { $0 } ?? []
                    found += fields.filter { pngText.contains($0) }.map { "\(key).\($0)" }
                }
            }
        }
        return found.sorted()
    }

    // MARK: Video

    /// Re-exports a video at ≤ 720p H.264 in MP4, then re-muxes it without
    /// any metadata: the export session's `forSharing()` filter drops the
    /// location, make, model and software but keeps the capture date
    /// (`uiso/date`). The result is checked to carry no metadata item at all
    /// (fail closed: ``MediaPreparationError/metadataNotRemovable``).
    public static func prepareVideo(at source: URL, directories: AppDirectories, caption: String? = nil) async throws
        -> PreparedMedia
    {
        let asset = AVURLAsset(url: source)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) else {
            throw MediaPreparationError.unreadable
        }
        let intermediate = directories.stagingFile(extension: "mp4")
        defer { try? FileManager.default.removeItem(at: intermediate) }
        session.outputURL = intermediate
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true
        session.metadata = []
        session.metadataItemFilter = .forSharing()
        await session.export()
        guard session.status == .completed else {
            throw MediaPreparationError.encodingFailed
        }
        let output = directories.stagingFile(extension: "mp4")
        do {
            try await VideoRemuxer.remuxWithoutMetadata(intermediate, to: output, fileType: .mp4)
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }
        try? ProtectedFiles.protect(output)

        let exported = AVURLAsset(url: output)
        let duration = (try? await exported.load(.duration)).map(CMTimeGetSeconds) ?? 0
        var width: UInt32 = 0
        var height: UInt32 = 0
        if let track = try? await exported.loadTracks(withMediaType: .video).first,
            let (size, transform) = try? await track.load(.naturalSize, .preferredTransform)
        {
            let rect = CGRect(origin: .zero, size: size).applying(transform)
            width = UInt32(abs(rect.width).rounded())
            height = UInt32(abs(rect.height).rounded())
        }
        var thumbnail: Data?
        let generator = AVAssetImageGenerator(asset: exported)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 320, height: 320)
        if let frame = try? await generator.image(at: .zero).image {
            thumbnail = Self.thumbnail(of: frame)
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.uint64Value ?? 0
        return PreparedMedia(
            file: output, kind: .video, contentType: "video/mp4", size: size, width: width, height: height,
            durationMs: UInt32(max(0, duration) * 1000), thumbnail: thumbnail, caption: caption)
    }

    // MARK: Voice notes and files

    /// A voice note recorded by the app (AAC in M4A, no location metadata).
    public static func prepareVoice(at source: URL, directories: AppDirectories, durationMs: UInt32, waveform: Data)
        throws -> PreparedMedia
    {
        let output = directories.stagingFile(extension: "m4a")
        try FileManager.default.moveItem(at: source, to: output)
        try? ProtectedFiles.protect(output)
        let size = (try FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.uint64Value ?? 0
        return PreparedMedia(
            file: output, kind: .voice, contentType: "audio/mp4", size: size, durationMs: durationMs,
            waveform: Data(waveform.prefix(ContentLimits.maxWaveformSamples)))
    }

    /// A document the user picked, sent as it is (its name is shown to the
    /// recipient; documents can carry their own author metadata, which the
    /// app cannot strip for every format) — except photos: picking one as a
    /// document is not the explicit choice §7 requires to keep EXIF/GPS, so
    /// an image is rewritten from its pixels at full size (format kept when
    /// it can be written, else JPEG or PNG). A video throws
    /// ``MediaPreparationError/requiresVideoPreparation``: use
    /// ``prepareVideoFile(at:directories:fileName:caption:)``.
    public static func prepareFile(
        at source: URL, directories: AppDirectories, fileName: String, contentType: String, caption: String? = nil
    ) throws -> PreparedMedia {
        if isMovie(contentType: contentType, fileName: fileName) {
            throw MediaPreparationError.requiresVideoPreparation
        }
        if isImage(contentType: contentType, fileName: fileName),
            let stripped = try stripImageMetadata(at: source, directories: directories, fileName: fileName)
        {
            return PreparedMedia(
                file: stripped.file, kind: .file, contentType: stripped.contentType,
                fileName: String(stripped.fileName.prefix(255)), size: stripped.size, width: stripped.width,
                height: stripped.height, caption: caption)
        }
        let output = directories.stagingFile()
        try FileManager.default.copyItem(at: source, to: output)
        try? ProtectedFiles.protect(output)
        let size = (try FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.uint64Value ?? 0
        return PreparedMedia(
            file: output, kind: .file, contentType: contentType, fileName: String(fileName.prefix(255)), size: size,
            caption: caption)
    }

    /// A video picked as a document: re-muxed (no re-encoding, quality kept)
    /// without any metadata — location, device, software, capture date.
    public static func prepareVideoFile(
        at source: URL, directories: AppDirectories, fileName: String, caption: String? = nil
    ) async throws -> PreparedMedia {
        let name = fileName as NSString
        let isMP4 = ["mp4", "m4v"].contains(name.pathExtension.lowercased())
        let fileType: AVFileType = isMP4 ? .mp4 : .mov
        let pathExtension = isMP4 ? name.pathExtension.lowercased() : "mov"
        let output = directories.stagingFile(extension: pathExtension)
        do {
            try await VideoRemuxer.remuxWithoutMetadata(source, to: output, fileType: fileType)
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }
        try? ProtectedFiles.protect(output)
        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.uint64Value ?? 0
        let base = name.deletingPathExtension.isEmpty ? "video" : name.deletingPathExtension
        return PreparedMedia(
            file: output, kind: .file, contentType: isMP4 ? "video/mp4" : "video/quicktime",
            fileName: String("\(base).\(pathExtension)".prefix(255)), size: size, caption: caption)
    }

    static func type(contentType: String, fileName: String) -> UTType? {
        UTType(mimeType: contentType) ?? UTType(filenameExtension: (fileName as NSString).pathExtension)
    }

    static func isImage(contentType: String, fileName: String) -> Bool {
        let byMime = UTType(mimeType: contentType)?.conforms(to: .image) ?? false
        let byName = UTType(filenameExtension: (fileName as NSString).pathExtension)?.conforms(to: .image) ?? false
        return byMime || byName
    }

    public static func isMovie(contentType: String, fileName: String) -> Bool {
        let byMime = UTType(mimeType: contentType)?.conforms(to: .movie) ?? false
        let byName = UTType(filenameExtension: (fileName as NSString).pathExtension)?.conforms(to: .movie) ?? false
        return byMime || byName
    }

    struct StrippedImage {
        let file: URL
        let contentType: String
        let fileName: String
        let size: UInt64
        let width: UInt32
        let height: UInt32
    }

    /// Rewrites an image from its pixels only (orientation applied), so no
    /// EXIF, GPS, TIFF, IPTC, XMP or maker note survives. Animated images
    /// keep their frames and timing. `nil` when ImageIO cannot read it (then
    /// it carries no metadata ImageIO could strip either).
    static func stripImageMetadata(at source: URL, directories: AppDirectories, fileName: String)
        throws(MediaPreparationError) -> StrippedImage?
    {
        guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
            let sourceType = CGImageSourceGetType(imageSource).map({ $0 as String }), CGImageSourceGetCount(imageSource) > 0
        else { return nil }
        let frameCount = CGImageSourceGetCount(imageSource)
        let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any] ?? [:]
        let hasAlpha = properties[kCGImagePropertyHasAlpha] as? Bool ?? false
        let writable = Set((CGImageDestinationCopyTypeIdentifiers() as? [String]) ?? [])
        let outputType: UTType
        if writable.contains(sourceType), let type = UTType(sourceType) {
            outputType = type
        } else {
            outputType = hasAlpha ? .png : .jpeg
        }
        let pathExtension = outputType.preferredFilenameExtension ?? (hasAlpha ? "png" : "jpg")
        let output = directories.stagingFile(extension: pathExtension)
        guard let destination = CGImageDestinationCreateWithURL(output as CFURL, outputType.identifier as CFString, frameCount, nil)
        else { throw .encodingFailed }
        var width = 0
        var height = 0
        if frameCount == 1 {
            let longEdge = max(
                properties[kCGImagePropertyPixelWidth] as? Int ?? 0, properties[kCGImagePropertyPixelHeight] as? Int ?? 0)
            guard let image = downscaled(imageSource, maxEdge: max(1, longEdge)) else { throw .unreadable }
            width = image.width
            height = image.height
            let options: [CFString: Any] =
                outputType.conforms(to: .jpeg) || outputType.conforms(to: .heic)
                ? [kCGImageDestinationLossyCompressionQuality: 0.92] : [:]
            CGImageDestinationAddImage(destination, image, options as CFDictionary)
        } else {
            // Frames with their timing only (GIF / PNG / HEIF animation dictionaries).
            let timingKeys = [kCGImagePropertyGIFDictionary, kCGImagePropertyPNGDictionary, kCGImagePropertyHEICSDictionary]
            if let container = CGImageSourceCopyProperties(imageSource, nil) as? [CFString: Any] {
                var global: [CFString: Any] = [:]
                if let gif = container[kCGImagePropertyGIFDictionary] as? [CFString: Any],
                    let loops = gif[kCGImagePropertyGIFLoopCount]
                {
                    global[kCGImagePropertyGIFDictionary] = [kCGImagePropertyGIFLoopCount: loops]
                }
                CGImageDestinationSetProperties(destination, global as CFDictionary)
            }
            for index in 0..<frameCount {
                guard let frame = CGImageSourceCreateImageAtIndex(imageSource, index, nil) else { throw .unreadable }
                width = max(width, frame.width)
                height = max(height, frame.height)
                let frameProperties = CGImageSourceCopyPropertiesAtIndex(imageSource, index, nil) as? [CFString: Any] ?? [:]
                var timing: [CFString: Any] = [:]
                for key in timingKeys {
                    guard let dictionary = frameProperties[key] as? [CFString: Any] else { continue }
                    timing[key] = dictionary.filter { entry in
                        [kCGImagePropertyGIFDelayTime, kCGImagePropertyGIFUnclampedDelayTime, kCGImagePropertyAPNGDelayTime,
                         kCGImagePropertyAPNGUnclampedDelayTime, kCGImagePropertyHEICSDelayTime,
                         kCGImagePropertyHEICSUnclampedDelayTime].contains(entry.key)
                    }
                }
                CGImageDestinationAddImage(destination, frame, timing as CFDictionary)
            }
        }
        guard CGImageDestinationFinalize(destination) else {
            try? FileManager.default.removeItem(at: output)
            throw .encodingFailed
        }
        try? ProtectedFiles.protect(output)
        // Fail closed: nothing identifying may be left.
        guard identifyingMetadata(of: (try? Data(contentsOf: output)) ?? Data()).isEmpty else {
            try? FileManager.default.removeItem(at: output)
            throw .metadataNotRemovable
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.uint64Value ?? 0
        let base = (fileName as NSString).deletingPathExtension
        return StrippedImage(
            file: output, contentType: outputType.preferredMIMEType ?? (hasAlpha ? "image/png" : "image/jpeg"),
            fileName: "\(base.isEmpty ? "image" : base).\(pathExtension)", size: size, width: UInt32(width),
            height: UInt32(height))
    }
}

/// Copies the audio and video samples of a movie into a new file without
/// re-encoding and without any metadata (container, `udta`, track level),
/// then checks that none is left.
final class VideoRemuxer: @unchecked Sendable {
    private let reader: AVAssetReader
    private let writer: AVAssetWriter
    private var pairs: [(output: AVAssetReaderTrackOutput, input: AVAssetWriterInput)] = []

    private init(reader: AVAssetReader, writer: AVAssetWriter) {
        self.reader = reader
        self.writer = writer
    }

    static func remuxWithoutMetadata(_ source: URL, to destination: URL, fileType: AVFileType) async throws {
        let asset = AVURLAsset(url: source)
        let tracks: [AVAssetTrack]
        do {
            tracks = try await asset.load(.tracks)
        } catch {
            throw MediaPreparationError.unreadable
        }
        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: asset)
            writer = try AVAssetWriter(outputURL: destination, fileType: fileType)
        } catch {
            throw MediaPreparationError.unreadable
        }
        writer.metadata = []
        writer.shouldOptimizeForNetworkUse = true
        let remuxer = VideoRemuxer(reader: reader, writer: writer)
        for track in tracks where track.mediaType == .video || track.mediaType == .audio {
            let formats = (try? await track.load(.formatDescriptions)) ?? []
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            output.alwaysCopiesSampleData = false
            let input = AVAssetWriterInput(mediaType: track.mediaType, outputSettings: nil, sourceFormatHint: formats.first)
            input.expectsMediaDataInRealTime = false
            input.metadata = []
            if track.mediaType == .video, let transform = try? await track.load(.preferredTransform) {
                input.transform = transform
            }
            guard reader.canAdd(output), writer.canAdd(input) else { throw MediaPreparationError.encodingFailed }
            reader.add(output)
            writer.add(input)
            remuxer.pairs.append((output, input))
        }
        guard !remuxer.pairs.isEmpty else { throw MediaPreparationError.unreadable }
        try await remuxer.run()

        let remaining = (try? await AVURLAsset(url: destination).load(.metadata)) ?? []
        var trackMetadata: [AVMetadataItem] = []
        for track in (try? await AVURLAsset(url: destination).load(.tracks)) ?? [] {
            trackMetadata += (try? await track.load(.metadata)) ?? []
        }
        guard remaining.isEmpty, trackMetadata.isEmpty else { throw MediaPreparationError.metadataNotRemovable }
    }

    private func run() async throws {
        guard reader.startReading(), writer.startWriting() else { throw MediaPreparationError.encodingFailed }
        writer.startSession(atSourceTime: .zero)
        await withTaskGroup(of: Void.self) { group in
            for index in pairs.indices {
                group.addTask { await self.pump(index) }
            }
        }
        if reader.status == .failed {
            writer.cancelWriting()
            throw MediaPreparationError.encodingFailed
        }
        await writer.finishWriting()
        guard writer.status == .completed else { throw MediaPreparationError.encodingFailed }
    }

    /// One track: samples from the reader to the writer on the input's own
    /// queue (AVFoundation serializes the callbacks on it).
    private func pump(_ index: Int) async {
        let input = pairs[index].input
        let queue = DispatchQueue(label: "com.syconx.us2us.remux.\(index)")
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let finished = LockedFlag()
            input.requestMediaDataWhenReady(on: queue) { [self] in
                let (output, input) = pairs[index]
                while input.isReadyForMoreMediaData {
                    guard let sample = output.copyNextSampleBuffer(), input.append(sample) else {
                        input.markAsFinished()
                        if finished.set() { continuation.resume() }
                        return
                    }
                }
            }
        }
    }
}

/// A one-shot flag for resuming a continuation exactly once.
private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    /// Sets the flag; `true` only for the first call.
    func set() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !value else { return false }
        value = true
        return true
    }
}
