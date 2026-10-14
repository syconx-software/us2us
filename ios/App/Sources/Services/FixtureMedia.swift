import AVFoundation
import CoreGraphics
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// The fixtures' attachment bytes — the design's own content, so the viewer,
/// the player and Quick Look open something real: the 5.1 photo (the
/// night-pier painting as a 1536 × 1024 JPEG), the 5.2 video (18 s of the
/// sunset poster, slowly drifting, H.264 640 × 360, no sound), the 5.2
/// file (a four-page “kira-sozlesmesi.pdf”) and the 5.1 voice message (a
/// soft hum that follows its waveform, WAV). Made on first use and kept in
/// memory; the video is written by AVAssetWriter to a temporary file that is
/// deleted as soon as it has been read.
@MainActor
enum FixtureMedia {
    private static var photos: [DesignArtwork: Data] = [:]
    private static var video: Data?
    private static var videoTask: Task<Data?, Never>?
    private static var documents: [Bool: Data] = [:]

    static func photoJPEG(_ artwork: DesignArtwork) -> Data? {
        if let cached = photos[artwork] { return cached }
        guard let data = DesignArtworkView.renderedImage(artwork, pixelWidth: 1_536)?.jpegData(compressionQuality: 0.9)
        else { return nil }
        photos[artwork] = data
        return data
    }

    /// The 5.2 video, synthesised once (a second or two, off the main thread).
    static func videoMP4() async -> Data? {
        if let video { return video }
        if let videoTask { return await videoTask.value }
        guard let poster = DesignArtworkView.renderedImage(.sunsetVideo, pixelWidth: 1_280)?.pngData() else { return nil }
        let task = Task.detached(priority: .userInitiated) { await VideoSynthesizer.make(poster: poster) }
        videoTask = Task { await task.value }
        let data = await task.value
        video = data
        videoTask = nil
        return data
    }

    /// A voice message's stand-in sound: a soft hum (165 Hz with two
    /// overtones, in syllable-like swells) whose loudness follows `waveform`
    /// (0…255 samples), `duration` long — 8 kHz 16-bit mono WAV, in memory.
    nonisolated static func voiceWAV(duration: TimeInterval, waveform: Data) -> Data {
        let rate = 8_000
        let count = max(1, Int(max(0.5, duration) * Double(rate)))
        let levels = waveform.isEmpty ? [0.5] : waveform.map { Double($0) / 255 }
        var samples = [Int16](repeating: 0, count: count)
        for index in 0..<count {
            let t = Double(index) / Double(rate)
            let level = levels[min(levels.count - 1, index * levels.count / count)]
            let swell = 0.55 + 0.45 * sin(2 * Double.pi * 3.2 * t)
            let hum = sin(2 * Double.pi * 165 * t) + 0.35 * sin(2 * Double.pi * 330 * t) + 0.15 * sin(2 * Double.pi * 495 * t)
            let value = max(-1, min(1, hum / 1.5 * level * swell * 0.6))
            samples[index] = Int16(value * Double(Int16.max))
        }
        var data = Data(capacity: 44 + count * 2)
        func append<Value: FixedWidthInteger>(_ value: Value) {
            withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(36 + count * 2))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(16))
        append(UInt16(1))  // PCM
        append(UInt16(1))  // mono
        append(UInt32(rate))
        append(UInt32(rate * 2))
        append(UInt16(2))
        append(UInt16(16))
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(count * 2))
        for sample in samples { append(sample) }
        return data
    }

    /// The 5.2 PDF: four A4 pages of a sample lease.
    static func leasePDF(english: Bool) async -> Data {
        if let cached = documents[english] { return cached }
        let data = await Task.detached(priority: .userInitiated) { LeaseDocument.render(english: english) }.value
        documents[english] = data
        return data
    }
}

// MARK: - Video

/// Writes the sunset poster as an 18 s clip: a slow push-in while the sun's
/// glow drifts and breathes, 24 fps.
private enum VideoSynthesizer {
    static let size = CGSize(width: 640, height: 360)
    static let seconds = 18
    static let fps: Int32 = 24

    static func make(poster: Data) async -> Data? {
        guard let source = CGImageSourceCreateWithData(poster as CFData, nil),
            let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("fixture-media", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        let url = folder.appendingPathComponent(UUID().uuidString).appendingPathExtension("mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return nil }
        let input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: Int(size.width),
                AVVideoHeightKey: Int(size.height),
            ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Int(size.width), kCVPixelBufferHeightKey as String: Int(size.height),
            ])
        guard writer.canAdd(input) else { return nil }
        writer.add(input)
        guard writer.startWriting() else { return nil }
        writer.startSession(atSourceTime: .zero)
        let frames = seconds * Int(fps)
        for frame in 0..<frames {
            while !input.isReadyForMoreMediaData {
                try? await Task.sleep(for: .milliseconds(2))
            }
            guard let pool = adaptor.pixelBufferPool else { return nil }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { return nil }
            draw(image, progress: Double(frame) / Double(frames), into: buffer)
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps)) else {
                return nil
            }
        }
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else { return nil }
        return try? Data(contentsOf: url)
    }

    private static func draw(_ image: CGImage, progress: Double, into buffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard
            let context = CGContext(
                data: CVPixelBufferGetBaseAddress(buffer), width: Int(size.width), height: Int(size.height),
                bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return }
        // Push-in 1.00 → 1.06 about the sun.
        let scale = 1 + 0.06 * progress
        let width = size.width * scale
        let height = size.height * scale
        let rect = CGRect(x: (size.width - width) * 0.62, y: (size.height - height) * 0.42, width: width, height: height)
        context.interpolationQuality = .high
        context.draw(image, in: rect)
        // The glow drifts a little and breathes twice.
        let breath = 0.5 + 0.5 * sin(progress * 4 * .pi)
        let center = CGPoint(x: size.width * (0.6 + 0.04 * progress), y: size.height * (0.42 - 0.03 * progress))
        let colors = [
            CGColor(srgbRed: 1, green: 0.84, blue: 0.67, alpha: 0.18 + 0.14 * breath),
            CGColor(srgbRed: 1, green: 0.67, blue: 0.47, alpha: 0),
        ]
        if let gradient = CGGradient(colorsSpace: nil, colors: colors as CFArray, locations: [0, 1]) {
            context.drawRadialGradient(
                gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: size.height * 0.45,
                options: [])
        }
    }
}

// MARK: - PDF

/// A plain four-page lease (sample text), drawn with the system font.
private enum LeaseDocument {
    static func render(english: Bool) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        let title = english ? "Lease agreement" : "Kira sözleşmesi"
        let clauses =
            english
            ? [
                "1. The landlord lets the flat at Moda, Kadıköy to the tenant for twelve months.",
                "2. The rent is paid by the fifth day of every month.",
                "3. The deposit is returned within thirty days after the keys are handed back.",
                "4. Either side may end the agreement with two months' written notice.",
            ]
            : [
                "1. Ev sahibi, Moda, Kadıköy'deki daireyi on iki ay süreyle kiracıya kiralar.",
                "2. Kira her ayın beşine kadar ödenir.",
                "3. Depozito, anahtarlar teslim edildikten sonra otuz gün içinde iade edilir.",
                "4. Taraflardan her biri iki ay önceden yazılı bildirimle sözleşmeyi sona erdirebilir.",
            ]
        let titleAttributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 22, weight: .semibold), .foregroundColor: UIColor.black,
        ]
        let bodyAttributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.darkGray,
        ]
        return renderer.pdfData { context in
            for (index, clause) in clauses.enumerated() {
                context.beginPage()
                (title as NSString).draw(at: CGPoint(x: 56, y: 64), withAttributes: titleAttributes)
                let pageLabel = english ? "Page \(index + 1) of 4" : "Sayfa \(index + 1) / 4"
                (pageLabel as NSString).draw(at: CGPoint(x: 56, y: 100), withAttributes: bodyAttributes)
                (clause as NSString).draw(
                    in: CGRect(x: 56, y: 140, width: page.width - 112, height: 200), withAttributes: bodyAttributes)
                UIColor.lightGray.setFill()
                for line in 0..<18 {
                    let width = (page.width - 112) * (line % 5 == 4 ? 0.6 : 1)
                    UIBezierPath(rect: CGRect(x: 56, y: 220 + CGFloat(line) * 26, width: width, height: 6)).fill()
                }
            }
        }
    }
}
