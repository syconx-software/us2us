import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Us2usKit

/// Why a picked photo could not be made into a profile photo.
public enum AvatarPhotoProcessingError: Error, Equatable, Sendable {
    /// ImageIO cannot read the picked file.
    case unreadable
    /// The crop lies outside the photo or is empty.
    case emptyCrop
    /// No encoder produced a photo that passes §8.9 and fits the record.
    case encodingFailed
}

/// Makes a profile photo on the phone (PROTOCOL.md §8.9), in memory only —
/// nothing is written to a file, and the uncropped original is not kept:
///
/// 1. The square the user cropped (the centred one without a crop), in the
///    photo as it is displayed — its orientation applied to the pixels.
/// 2. Scaled to 256 × 256 (smaller only when the crop is smaller; never
///    enlarged), drawn opaque into sRGB, so the encoder gets pixels and
///    nothing else of the source file: no EXIF, GPS, XMP, IPTC, maker notes,
///    thumbnails, depth maps or other auxiliary images.
/// 3. Encoded as HEIC where the phone has an encoder, else JPEG, at quality
///    0.8 down to 0.4, then smaller, until the serialized `ProfileAvatar`
///    fits 49110 bytes. A JPEG is also scrubbed byte by byte: only the
///    frame, tables, the scans and the JFIF header (without a thumbnail)
///    stay — no ICC profile (the pixels are sRGB, which receivers assume
///    without one, and a profile names the platform that wrote it), nothing
///    between scans but tables, nothing after the end. A HEIC that still
///    shows any metadata to ImageIO or the kit is not used; JPEG is then.
/// 4. Checked like a received photo (`AvatarPhoto`: signature, header size,
///    byte limit), for what a sender may send (`AvatarPhoto.isSendable`:
///    square, no metadata in the bytes) and by ImageIO for metadata, before
///    it is returned.
public enum AvatarPhotoProcessor {
    public static let maxPixels = AvatarPhoto.maxPixels
    static let qualities: [Double] = [0.8, 0.7, 0.6, 0.5, 0.4]
    /// Each smaller attempt is this much narrower; below `minimumSide` it stops.
    static let shrinkFactor = 0.8
    static let minimumSide = 32

    /// The profile photo of `source` (any format ImageIO reads).
    ///
    /// - Parameters:
    ///   - crop: the square the user chose, in pixels of the photo as
    ///     displayed (orientation applied, origin top left). A crop that is
    ///     not square is reduced to its centred square; `nil` takes the
    ///     centred square of the whole photo.
    ///   - formats: encoders to try, in order (tests pin one).
    public static func prepare(
        _ source: Data, crop: CGRect? = nil, formats: [AvatarPhotoFormat] = [.heic, .jpeg]
    ) throws(AvatarPhotoProcessingError) -> AvatarPhoto {
        guard
            let imageSource = CGImageSourceCreateWithData(
                source as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
            CGImageSourceGetCount(imageSource) > 0,
            let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any],
            let pixelWidth = properties[kCGImagePropertyPixelWidth] as? Int,
            let pixelHeight = properties[kCGImagePropertyPixelHeight] as? Int, pixelWidth > 0, pixelHeight > 0
        else { throw .unreadable }
        // Orientations 5–8 turn the photo by 90°: width and height swap.
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        let turned = (5...8).contains(orientation)
        let displayed = CGSize(
            width: turned ? pixelHeight : pixelWidth, height: turned ? pixelWidth : pixelHeight)
        let square = try squareCrop(crop, in: displayed)
        let cropSide = Int(square.width)
        var side = min(maxPixels, cropSide)

        while side >= min(minimumSide, cropSide) {
            let pixels = try render(imageSource, displayed: displayed, square: square, side: side)
            encoders: for format in formats where canEncode(format) {
                for quality in qualities {
                    // An encoder that fails, or leaves metadata, is not used at all.
                    guard let encoded = encode(pixels, as: format, quality: quality),
                        metadataProblems(of: encoded).isEmpty
                    else { continue encoders }
                    switch checked(encoded, as: format) {
                    case .success(let photo) where photo.isSendable: return photo
                    case .success: continue encoders  // metadata the kit finds (e.g. a HEIC's Exif item)
                    case .failure(.tooManyBytes): continue  // lower quality next
                    case .failure: continue encoders
                    }
                }
            }
            let next = Int((Double(side) * shrinkFactor).rounded(.down))
            guard next < side, next >= 1 else { break }
            side = next
        }
        throw .encodingFailed
    }

    /// The receiver's checks (§8.9), as a value.
    static func checked(_ encoded: Data, as format: AvatarPhotoFormat) -> Result<AvatarPhoto, AvatarPhotoError> {
        do {
            return .success(try AvatarPhoto(format: format, data: encoded))
        } catch {
            // `AvatarPhoto.init` throws only `AvatarPhotoError` (typed).
            return .failure(error)
        }
    }

    /// The centred square of `crop` (or of the whole photo), inside it, in
    /// whole pixels.
    static func squareCrop(_ crop: CGRect?, in size: CGSize) throws(AvatarPhotoProcessingError) -> CGRect {
        let bounds = CGRect(origin: .zero, size: size)
        let area = (crop?.standardized ?? bounds).intersection(bounds)
        guard !area.isNull, area.width >= 1, area.height >= 1 else { throw .emptyCrop }
        let side = min(area.width, area.height).rounded(.down)
        let square = CGRect(x: area.midX - side / 2, y: area.midY - side / 2, width: side, height: side).integral
        let clamped = square.intersection(bounds)
        let finalSide = min(clamped.width, clamped.height).rounded(.down)
        guard finalSide >= 1 else { throw .emptyCrop }
        return CGRect(x: clamped.minX, y: clamped.minY, width: finalSide, height: finalSide)
    }

    /// Decodes only as many pixels as the crop needs (the system's
    /// downsampling decoder, orientation applied), crops and scales to
    /// `side` × `side`, opaque sRGB.
    static func render(_ source: CGImageSource, displayed: CGSize, square: CGRect, side: Int)
        throws(AvatarPhotoProcessingError) -> CGImage
    {
        let longEdge = max(displayed.width, displayed.height)
        let needed = (longEdge * CGFloat(side) / square.width).rounded(.up)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(min(longEdge, max(needed, CGFloat(side)))),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw .unreadable
        }
        let scaleX = CGFloat(decoded.width) / displayed.width
        let scaleY = CGFloat(decoded.height) / displayed.height
        let region = CGRect(
            x: square.minX * scaleX, y: square.minY * scaleY, width: square.width * scaleX,
            height: square.height * scaleY
        ).integral.intersection(CGRect(x: 0, y: 0, width: decoded.width, height: decoded.height))
        guard !region.isNull, region.width >= 1, region.height >= 1, let cropped = decoded.cropping(to: region),
            let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
            let context = CGContext(
                data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { throw .unreadable }
        let canvas = CGRect(x: 0, y: 0, width: side, height: side)
        // Transparent areas become white instead of undefined.
        context.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
        context.fill(canvas)
        context.interpolationQuality = .high
        context.draw(cropped, in: canvas)
        guard let image = context.makeImage() else { throw .unreadable }
        return image
    }

    static func uniformType(_ format: AvatarPhotoFormat) -> UTType {
        switch format {
        case .jpeg: return .jpeg
        case .heic: return .heic
        }
    }

    /// Whether this phone has an encoder for `format` (HEIC needs a hardware
    /// HEVC encoder; JPEG always works).
    static func canEncode(_ format: AvatarPhotoFormat) -> Bool {
        let writable = (CGImageDestinationCopyTypeIdentifiers() as? [String]) ?? []
        return writable.contains(uniformType(format).identifier)
    }

    /// The encoder is given the pixels and the quality, never properties of
    /// the source; a JPEG is scrubbed afterwards (``scrubJPEG(_:)``).
    static func encode(_ image: CGImage, as format: AvatarPhotoFormat, quality: Double) -> Data? {
        let data = NSMutableData()
        guard
            let destination = CGImageDestinationCreateWithData(
                data as CFMutableData, uniformType(format).identifier as CFString, 1, nil)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality,
            kCGImageDestinationEmbedThumbnail: false,
        ]
        CGImageDestinationAddImage(destination, image, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        let encoded = data as Data
        switch format {
        case .jpeg: return scrubJPEG(encoded)
        case .heic: return encoded
        }
    }

    // MARK: JPEG scrubbing

    /// Rebuilds a JPEG from the segments a decoder needs: SOI, quantisation
    /// and Huffman tables, the frame header, restart interval, then the
    /// scans with the tables between them, up to and including EOI. Kept
    /// besides: the JFIF header when it carries no thumbnail and an `Adobe`
    /// colour-transform marker. Dropped: ICC profiles (the encoder is given
    /// sRGB pixels, which receivers assume without a profile), every other
    /// APPn (EXIF and XMP in APP1, IPTC in APP13, maker data, JFXX
    /// thumbnails, MPF extra images) and comments, also between scans, and
    /// every byte after EOI. `nil` for anything that is not a well-formed
    /// JPEG.
    static func scrubJPEG(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count >= 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var output = Data([0xFF, 0xD8])
        var index = 2
        while index < b.count {
            guard b[index] == 0xFF else { return nil }
            var markerIndex = index + 1
            while markerIndex < b.count, b[markerIndex] == 0xFF { markerIndex += 1 }
            guard markerIndex < b.count else { return nil }
            let marker = b[markerIndex]
            let segment = markerIndex + 1
            switch marker {
            case 0x00, 0x01, 0xD0...0xD9: return nil  // nothing of these belongs before the scan
            default: break
            }
            guard segment + 2 <= b.count else { return nil }
            let length = Int(b[segment]) << 8 | Int(b[segment + 1])
            guard length >= 2, segment + length <= b.count else { return nil }
            if marker == 0xDA {
                output.append(0xFF)
                output.append(contentsOf: b[markerIndex..<(segment + length)])
                return scrubScans(b, from: segment + length, into: output)
            }
            let payload = b[(segment + 2)..<(segment + length)]
            if keepsSegment(marker, payload: payload) {
                output.append(0xFF)
                output.append(contentsOf: b[markerIndex..<(segment + length)])
            }
            index = segment + length
        }
        return nil
    }

    /// The scans from the entropy-coded data of the first one at `start`:
    /// entropy-coded data, restart markers, the tables, restart intervals and
    /// headers of further scans are kept; APP segments and comments between
    /// scans are dropped; the output ends with EOI, and whatever followed it
    /// (camera trailers, appended files) is dropped. `nil` without an EOI or
    /// for anything else between scans (a second frame).
    private static func scrubScans(_ b: [UInt8], from start: Int, into initial: Data) -> Data? {
        var output = initial
        var index = start
        var entropyCoded = true
        // Every round moves past at least one byte.
        while index <= b.count {
            if entropyCoded {
                // In entropy-coded data 0xFF is followed by 0x00 (a stuffed
                // byte), fill bytes, a restart marker or the marker that ends
                // the scan.
                var end = index
                while true {
                    guard end < b.count else { return nil }  // no EOI
                    if b[end] != 0xFF {
                        end += 1
                        continue
                    }
                    var next = end + 1
                    while next < b.count, b[next] == 0xFF { next += 1 }
                    guard next < b.count else { return nil }
                    if b[next] == 0x00 || (0xD0...0xD7).contains(b[next]) {
                        end = next + 1
                        continue
                    }
                    break
                }
                output.append(contentsOf: b[index..<end])
                index = end
            } else {
                guard index < b.count, b[index] == 0xFF else { return nil }
            }
            var markerIndex = index
            while markerIndex < b.count, b[markerIndex] == 0xFF { markerIndex += 1 }  // fill bytes
            guard markerIndex < b.count else { return nil }
            let marker = b[markerIndex]
            if marker == 0xD9 {
                output.append(contentsOf: [0xFF, 0xD9])
                return output
            }
            let segment = markerIndex + 1
            guard segment + 2 <= b.count else { return nil }
            let length = Int(b[segment]) << 8 | Int(b[segment + 1])
            guard length >= 2, segment + length <= b.count else { return nil }
            switch marker {
            case 0xC4, 0xCC, 0xDA, 0xDB, 0xDC, 0xDD:  // DHT, DAC, SOS, DQT, DNL, DRI
                output.append(0xFF)
                output.append(contentsOf: b[markerIndex..<(segment + length)])
            case 0xE0...0xEF, 0xFE:
                break  // metadata between scans: dropped
            default:
                return nil
            }
            index = segment + length
            entropyCoded = marker == 0xDA
        }
        return nil
    }

    private static func keepsSegment(_ marker: UInt8, payload: ArraySlice<UInt8>) -> Bool {
        func starts(with text: String) -> Bool { payload.starts(with: Array(text.utf8)) }
        switch marker {
        case 0xE0:
            // JFIF: identifier(5) version(2) units(1) densities(4) thumbnail size(2) — without a thumbnail.
            guard starts(with: "JFIF\u{0}"), payload.count >= 14 else { return false }
            let base = payload.startIndex
            return payload[base + 12] == 0 && payload[base + 13] == 0
        case 0xEE:
            return starts(with: "Adobe")
        case 0xE1...0xED, 0xEF, 0xFE:
            return false  // also ICC profiles (APP2): the pixels are sRGB
        default:
            return true  // tables, frame header, restart interval
        }
    }

    // MARK: Checks

    /// What ImageIO still finds besides bare pixels; empty for a photo that
    /// may be sent. Reports EXIF, GPS, IPTC, maker, Photoshop and DNG
    /// dictionaries, TIFF fields other than layout ones, a rotation, more
    /// than one image, and depth, disparity or matte data.
    static func metadataProblems(of data: Data) -> [String] {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return ["unreadable"] }
        var problems: [String] = []
        if CGImageSourceGetCount(source) != 1 { problems.append("image count") }
        let forbidden: Set<String> = [
            kCGImagePropertyExifDictionary as String, kCGImagePropertyGPSDictionary as String,
            kCGImagePropertyIPTCDictionary as String, kCGImagePropertyExifAuxDictionary as String,
            kCGImageProperty8BIMDictionary as String, kCGImagePropertyDNGDictionary as String,
            kCGImagePropertyCIFFDictionary as String,
        ]
        let layoutTIFF: Set<String> = [
            kCGImagePropertyTIFFOrientation as String, kCGImagePropertyTIFFTileWidth as String,
            kCGImagePropertyTIFFTileLength as String, kCGImagePropertyTIFFResolutionUnit as String,
            kCGImagePropertyTIFFXResolution as String, kCGImagePropertyTIFFYResolution as String,
            kCGImagePropertyTIFFCompression as String, kCGImagePropertyTIFFPhotometricInterpretation as String,
        ]
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] ?? [:]
        for (key, value) in properties {
            if forbidden.contains(key) || key.hasPrefix("{Maker") {
                problems.append(key)
            } else if key == kCGImagePropertyTIFFDictionary as String {
                let fields = (value as? [String: Any]) ?? [:]
                problems += fields.keys.filter { !layoutTIFF.contains($0) }.map { "\(key).\($0)" }
            }
        }
        if let orientation = (properties[kCGImagePropertyOrientation as String] as? NSNumber)?.intValue, orientation != 1 {
            problems.append("orientation")
        }
        let auxiliary: [CFString] = [
            kCGImageAuxiliaryDataTypeDepth, kCGImageAuxiliaryDataTypeDisparity,
            kCGImageAuxiliaryDataTypePortraitEffectsMatte,
        ]
        for type in auxiliary where CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0, type) != nil {
            problems.append(type as String)
        }
        return problems.sorted()
    }

    /// A received photo as ImageIO sees its header, without decoding pixels:
    /// one image of the declared type, at most 256 × 256 (§8.9). The kit's
    /// own header checks (``AvatarPhoto``) ran when it arrived; this is the
    /// decoder's view, asked right before drawing.
    public static func isDisplayable(_ photo: AvatarPhoto) -> Bool {
        guard
            let source = CGImageSourceCreateWithData(
                photo.data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
            CGImageSourceGetCount(source) >= 1, let type = CGImageSourceGetType(source).map({ $0 as String }),
            UTType(type)?.conforms(to: uniformType(photo.format)) == true,
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let width = properties[kCGImagePropertyPixelWidth] as? Int,
            let height = properties[kCGImagePropertyPixelHeight] as? Int
        else { return false }
        return (1...maxPixels).contains(width) && (1...maxPixels).contains(height)
    }

    /// Decodes a profile photo for drawing, in memory, after
    /// ``isDisplayable(_:)``; `nil` for anything else. The caller crops it
    /// to the centred circle.
    public static func image(of photo: AvatarPhoto) -> CGImage? {
        guard isDisplayable(photo),
            let source = CGImageSourceCreateWithData(
                photo.data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        return CGImageSourceCreateImageAtIndex(
            source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }
}
