import Foundation
import SwiftProtobuf

/// The two photo formats a profile picture may use (PROTOCOL.md §8.9).
public enum AvatarPhotoFormat: String, Sendable, Hashable, CaseIterable {
    case jpeg = "image/jpeg"
    case heic = "image/heic"

    /// The `AvatarPhoto.content_type` value.
    public var contentType: String { rawValue }
}

/// Why photo bytes are not a profile photo (§8.9).
public enum AvatarPhotoError: Error, Equatable, Sendable {
    /// `content_type` is neither `image/jpeg` nor `image/heic`.
    case unsupportedType
    /// The bytes are not a file of the declared type.
    case signatureMismatch
    /// No pixel size could be read from the image header.
    case unreadableHeader
    /// Wider or taller than 256 pixels (or a side of 0).
    case badDimensions(width: Int, height: Int)
    /// The serialized `ProfileAvatar` would exceed 49110 bytes.
    case tooManyBytes
}

/// A profile photo that passed the receiver checks of §8.9: JPEG or HEIC by
/// its signature as well as its declared type, at most 256 × 256 pixels in
/// its header, and small enough for the largest avatar record. Nothing here
/// decodes pixels: these checks run before any decoder sees the bytes.
///
/// A receiver shows any such photo; whether this client may *send* it is
/// ``isSendable`` (square, no metadata), checked on the bytes themselves.
public struct AvatarPhoto: Sendable, Hashable {
    /// The largest width and height (§8.9).
    public static let maxPixels = 256

    public let format: AvatarPhotoFormat
    /// The encoded image, exactly as sent.
    public let data: Data
    /// The pixel size in the image header.
    public let width: Int
    public let height: Int
    /// The file carries something besides the pixels and an ICC colour
    /// profile (§8.9): EXIF, XMP, IPTC, a comment, a thumbnail, auxiliary or
    /// further images, segments between JPEG scans or bytes after the end of
    /// a JPEG. Shown when received; never sent by this client.
    public let hasMetadata: Bool

    public init(format: AvatarPhotoFormat, data: Data) throws(AvatarPhotoError) {
        guard AvatarImageHeader.format(of: data) == format else { throw .signatureMismatch }
        guard let info = AvatarImageHeader.inspect(data, format: format) else { throw .unreadableHeader }
        guard (1...Self.maxPixels).contains(info.width), (1...Self.maxPixels).contains(info.height) else {
            throw .badDimensions(width: info.width, height: info.height)
        }
        self.format = format
        self.data = data
        self.width = info.width
        self.height = info.height
        self.hasMetadata = info.hasMetadata
        guard ProfilePicture.photo(self).serializedSize <= ProfilePicture.maxSerializedBytes else {
            throw .tooManyBytes
        }
    }

    /// `content_type` as received; anything but the two types is refused.
    public init(contentType: String, data: Data) throws(AvatarPhotoError) {
        guard let format = AvatarPhotoFormat(rawValue: contentType) else { throw .unsupportedType }
        try self.init(format: format, data: data)
    }

    /// The photo is square (senders always send square photos; receivers
    /// crop anything else to the centred circle).
    public var isSquare: Bool { width == height }

    /// Whether this client may send the photo, in a `ProfileUpdate` or an
    /// avatar record (§8.9): square and without metadata. Its pixel size and
    /// byte count were checked when it was made.
    public var isSendable: Bool { isSquare && !hasMetadata }
}

/// A user's profile picture (PROTOCOL.md §8.9): the initial letter of the
/// username, a built-in preset, or a photo. On the wire it is a
/// `ProfileAvatar` (none at all for the initial letter), in
/// `ProfileUpdate.profile_avatar` to contacts and as the plaintext of the
/// avatar record for search (§8.10).
///
/// Anything malformed — no kind, preset id 0, another content type, a photo
/// whose bytes do not match its type or whose header is larger than 256 × 256,
/// a serialization over 49110 bytes — reads as ``initials``, never as an
/// error around it. Whether a preset id is one this build can draw is the
/// app's decision (its catalog); an unknown one is drawn as the initial.
public enum ProfilePicture: Sendable, Hashable {
    case initials
    /// A built-in preset of the app's catalog (§8.9); never 0.
    case preset(UInt32)
    case photo(AvatarPhoto)

    /// The largest serialized `ProfileAvatar` (§8.9, §8.10): what fits the
    /// 48 KiB avatar record.
    public static let maxSerializedBytes = 49_110

    /// Reads a received `ProfileAvatar`; `nil` (absent) and anything
    /// malformed are the initial letter.
    public init(_ avatar: BRPProfileAvatar?) {
        self = avatar.flatMap(Self.validated) ?? .initials
    }

    /// Reads a serialized `ProfileAvatar` (stored or decrypted); `nil`,
    /// unparsable and malformed bytes are the initial letter.
    public init(serialized: Data?) {
        guard let serialized, serialized.count <= Self.maxSerializedBytes,
            let avatar = try? BRPProfileAvatar(serializedBytes: serialized)
        else {
            self = .initials
            return
        }
        self = ProfilePicture(avatar)
    }

    /// The picture a received `ProfileAvatar` stands for, or `nil` when it
    /// fails §8.9.
    public static func validated(_ avatar: BRPProfileAvatar) -> ProfilePicture? {
        let picture: ProfilePicture
        switch avatar.kind {
        case .presetID(let id)?:
            guard id != 0 else { return nil }
            picture = .preset(id)
        case .photo(let photo)?:
            guard let checked = try? AvatarPhoto(contentType: photo.contentType, data: photo.data) else { return nil }
            picture = .photo(checked)
        case nil:
            return nil
        }
        // Unknown (newer) fields count towards the size a receiver checks;
        // without them the photo check above already measured it.
        var unknownFields = !avatar.unknownFields.data.isEmpty
        if case .photo(let photo)? = avatar.kind, !photo.unknownFields.data.isEmpty { unknownFields = true }
        if unknownFields {
            guard ((try? avatar.serializedBytes() as Data)?.count ?? .max) <= maxSerializedBytes else { return nil }
        }
        return picture
    }

    /// The `ProfileAvatar` to send; `nil` for the initial letter.
    public var profileAvatar: BRPProfileAvatar? {
        switch self {
        case .initials:
            return nil
        case .preset(let id):
            var avatar = BRPProfileAvatar()
            avatar.presetID = id
            return avatar
        case .photo(let photo):
            var avatar = BRPProfileAvatar()
            avatar.photo = BRPAvatarPhoto.with {
                $0.contentType = photo.format.contentType
                $0.data = photo.data
            }
            return avatar
        }
    }

    /// The serialized `ProfileAvatar`; `nil` for the initial letter.
    public var serializedProfileAvatar: Data? {
        profileAvatar.flatMap { try? $0.serializedBytes() as Data }
    }

    /// Whether this picture may be sent (§8.9): not preset id 0, and a photo
    /// only when it is square and carries no metadata
    /// (``AvatarPhoto/isSendable``), whoever built it.
    public var isSendable: Bool {
        switch self {
        case .initials: return true
        case .preset(let id): return id != 0
        case .photo(let photo): return photo.isSendable
        }
    }

    /// A photo (as opposed to the initial letter or a preset). A stranger's
    /// photo is fetched only when the user asks for it (§8.9).
    public var isPhoto: Bool {
        if case .photo = self { return true }
        return false
    }

    public var presetId: UInt32? {
        if case .preset(let id) = self { return id }
        return nil
    }

    var serializedSize: Int { serializedProfileAvatar?.count ?? 0 }
}

/// What a profile photo's header says (§8.9), read without decoding pixels:
/// the pixel size (the largest a HEIF file lists) and whether the file
/// carries metadata (``AvatarPhoto/hasMetadata``).
struct AvatarImageInfo: Equatable {
    let width: Int
    let height: Int
    let hasMetadata: Bool
}

/// Header checks for profile photos (§8.9): the signature of the declared
/// type, the pixel size from the header and, for senders, metadata — the
/// same rules as `android/core`'s `ImageHeaders` — without decoding
/// anything. The parsers are bounded and never index outside the bytes;
/// a file they cannot read a size from is "unreadable", which makes the
/// picture the initial letter. A file damaged after its size only counts as
/// having metadata: a receiver still shows it, a sender never sends it.
enum AvatarImageHeader {
    /// The signature; only the first bytes are looked at (an `ftyp` box
    /// with more brands than fit is not a photo this app made).
    static func format(of data: Data) -> AvatarPhotoFormat? {
        let bytes = [UInt8](data.prefix(256))
        if bytes.count >= 3, bytes[0] == 0xFF, bytes[1] == 0xD8, bytes[2] == 0xFF { return .jpeg }
        if isHEIC(bytes) { return .heic }
        return nil
    }

    static func pixelSize(of data: Data, format: AvatarPhotoFormat) -> (width: Int, height: Int)? {
        inspect(data, format: format).map { (width: $0.width, height: $0.height) }
    }

    /// The size and the metadata finding; `nil` when no size can be read.
    static func inspect(_ data: Data, format: AvatarPhotoFormat) -> AvatarImageInfo? {
        let bytes = [UInt8](data)
        switch format {
        case .jpeg: return jpegInfo(bytes)
        case .heic: return heicInfo(bytes)
        }
    }

    // MARK: JPEG

    /// Start-of-frame markers (baseline, extended, progressive, lossless,
    /// arithmetic); `C4`, `C8` and `CC` are not frames.
    private static let startOfFrame: Set<UInt8> = [
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    ]

    /// What may stand between scans: DHT, DAC, DNL, DQT, DRI and the next
    /// scan's header.
    private static let betweenScans: Set<UInt8> = [0xC4, 0xCC, 0xDC, 0xDB, 0xDD, 0xDA]

    /// Walks the marker segments from SOI to the first scan: the first frame
    /// header (SOFn) gives the size; APP segments other than JFIF without a
    /// thumbnail, an ICC profile and Adobe's colour transform, comments and
    /// a second frame are metadata. The rest of the file is then walked to
    /// EOI (``jpegCleanAfterFirstScan(_:from:)``). No frame header before
    /// the first scan, or the end, is `nil`.
    static func jpegInfo(_ b: [UInt8]) -> AvatarImageInfo? {
        guard b.count >= 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var size: (width: Int, height: Int)?
        var metadata = false
        // Once the size is known, a damaged rest still gives it (unclean).
        func result(clean: Bool) -> AvatarImageInfo? {
            size.map { AvatarImageInfo(width: $0.width, height: $0.height, hasMetadata: metadata || !clean) }
        }
        var index = 2
        while index < b.count {
            guard b[index] == 0xFF else { return result(clean: false) }
            var markerIndex = index + 1
            while markerIndex < b.count, b[markerIndex] == 0xFF { markerIndex += 1 }  // fill bytes
            guard markerIndex < b.count else { return result(clean: false) }
            let marker = b[markerIndex]
            let segment = markerIndex + 1
            switch marker {
            case 0x01, 0xD0...0xD7:
                index = segment  // markers without a length
                continue
            case 0x00, 0xD8, 0xD9:
                return result(clean: false)  // stuffing, a second SOI, or the end before any scan
            default:
                break
            }
            guard segment + 2 <= b.count else { return result(clean: false) }
            let length = Int(b[segment]) << 8 | Int(b[segment + 1])
            guard length >= 2, segment + length <= b.count else { return result(clean: false) }
            if marker == 0xDA {
                // A scan before any frame header has no size (`nil`).
                return result(clean: jpegCleanAfterFirstScan(b, from: segment + length))
            }
            if startOfFrame.contains(marker) {
                guard length >= 7 else { return result(clean: false) }
                if size == nil {
                    let height = Int(b[segment + 3]) << 8 | Int(b[segment + 4])
                    let width = Int(b[segment + 5]) << 8 | Int(b[segment + 6])
                    size = (width, height)
                } else {
                    metadata = true  // a second frame: one image per file
                }
            } else if (0xE0...0xEF).contains(marker) || marker == 0xFE {
                if !isAllowedSegment(marker, b[(segment + 2)..<(segment + length)]) { metadata = true }
            }
            index = segment + length
        }
        return result(clean: false)
    }

    /// APP segments an encoder writes for the pixels alone: JFIF without a
    /// thumbnail, an ICC profile and Adobe's colour transform. Everything
    /// else (EXIF and XMP in APP1, IPTC in APP13, MPF, JFXX thumbnails,
    /// maker data) and every comment is metadata.
    private static func isAllowedSegment(_ marker: UInt8, _ payload: ArraySlice<UInt8>) -> Bool {
        func starts(with text: String) -> Bool { payload.starts(with: Array(text.utf8)) }
        switch marker {
        case 0xE0:
            // identifier(5) version(2) units(1) densities(4) thumbnail size(2)
            guard starts(with: "JFIF\u{0}"), payload.count >= 14 else { return false }
            return payload[payload.startIndex + 12] == 0 && payload[payload.startIndex + 13] == 0
        case 0xE2:
            return starts(with: "ICC_PROFILE\u{0}")
        case 0xEE:
            return starts(with: "Adobe")
        default:
            return false
        }
    }

    /// Whether the rest of a JPEG, from the entropy-coded data of its first
    /// scan at `start`, holds only further scans with their tables and
    /// restart markers and ends with EOI as its very last bytes: no APP
    /// segment or comment between scans, no second image, nothing appended
    /// after EOI (camera trailers, other files).
    static func jpegCleanAfterFirstScan(_ b: [UInt8], from start: Int) -> Bool {
        var index = start
        var entropyCoded = true
        // Every round moves past at least one byte.
        while index <= b.count {
            if entropyCoded {
                // In entropy-coded data 0xFF is followed by 0x00 (a stuffed
                // byte), fill bytes, a restart marker or the marker that ends
                // the scan.
                while true {
                    guard index < b.count else { return false }  // no EOI
                    if b[index] != 0xFF {
                        index += 1
                        continue
                    }
                    var next = index + 1
                    while next < b.count, b[next] == 0xFF { next += 1 }
                    guard next < b.count else { return false }
                    if b[next] == 0x00 || (0xD0...0xD7).contains(b[next]) {
                        index = next + 1
                        continue
                    }
                    index = next
                    break
                }
            } else {
                guard index < b.count, b[index] == 0xFF else { return false }
                while index < b.count, b[index] == 0xFF { index += 1 }  // fill bytes
                guard index < b.count else { return false }
            }
            let marker = b[index]
            index += 1
            if marker == 0xD9 { return index == b.count }
            guard betweenScans.contains(marker), index + 2 <= b.count else { return false }
            let length = Int(b[index]) << 8 | Int(b[index + 1])
            guard length >= 2, index + length <= b.count else { return false }
            index += length
            entropyCoded = marker == 0xDA
        }
        return false
    }

    // MARK: HEIC (ISO base media file format)

    private struct Box {
        let type: String
        /// Payload range (after the size and type, and the large size if any).
        let start: Int
        let end: Int
    }

    /// At most this many boxes per level; real files have a handful.
    private static let maxBoxes = 256

    private static func uint32(_ b: [UInt8], _ at: Int) -> UInt32 {
        UInt32(b[at]) << 24 | UInt32(b[at + 1]) << 16 | UInt32(b[at + 2]) << 8 | UInt32(b[at + 3])
    }

    private static func fourCC(_ b: [UInt8], _ at: Int) -> String {
        String(decoding: b[at..<at + 4], as: UTF8.self)
    }

    /// The boxes in `range`; `nil` if any box is malformed or overruns it.
    private static func boxes(_ b: [UInt8], in range: Range<Int>) -> [Box]? {
        var result: [Box] = []
        var index = range.lowerBound
        while index < range.upperBound {
            guard range.upperBound - index >= 8 else { return nil }
            var size = Int(uint32(b, index))
            let type = fourCC(b, index + 4)
            var header = 8
            if size == 1 {
                guard range.upperBound - index >= 16 else { return nil }
                let large = UInt64(uint32(b, index + 8)) << 32 | UInt64(uint32(b, index + 12))
                guard large <= UInt64(range.upperBound - index) else { return nil }
                size = Int(large)
                header = 16
            } else if size == 0 {
                size = range.upperBound - index  // to the end
            }
            guard size >= header, size <= range.upperBound - index else { return nil }
            result.append(Box(type: type, start: index + header, end: index + size))
            guard result.count <= maxBoxes else { return nil }
            index += size
        }
        return result
    }

    /// HEVC-coded HEIF brands (`image/heic`).
    private static let heicBrands: Set<String> = ["heic", "heix"]

    /// A first box `ftyp` whose major or a compatible brand is `heic`/`heix`
    /// (`b` may be just the start of the file).
    static func isHEIC(_ b: [UInt8]) -> Bool {
        guard b.count >= 16, fourCC(b, 4) == "ftyp" else { return false }
        let size = Int(uint32(b, 0))
        guard size >= 16, size <= b.count else { return false }
        var brands = [fourCC(b, 8)]
        var offset = 16
        while offset + 4 <= size {
            brands.append(fourCC(b, offset))
            offset += 4
        }
        return brands.contains(where: heicBrands.contains)
    }

    /// The largest `ispe` (image spatial extents) in `meta/iprp/ipco`: the
    /// primary image and every other image the file carries (thumbnails,
    /// tiles, auxiliary images) must stay within 256 pixels, so the largest
    /// one is what is checked. `nil` without any `ispe`. Metadata unless
    /// `meta` lists exactly one item, of type `hvc1`, and no `iref`: an Exif
    /// or XMP item, a thumbnail, a depth or other auxiliary image, a grid.
    static func heicInfo(_ b: [UInt8]) -> AvatarImageInfo? {
        guard isHEIC(b), let top = boxes(b, in: 0..<b.count),
            let meta = top.first(where: { $0.type == "meta" }), meta.end - meta.start >= 4,
            let metaChildren = boxes(b, in: meta.start + 4..<meta.end),  // full box: version and flags
            let iprp = metaChildren.first(where: { $0.type == "iprp" }),
            let iprpChildren = boxes(b, in: iprp.start..<iprp.end),
            let ipco = iprpChildren.first(where: { $0.type == "ipco" }),
            let properties = boxes(b, in: ipco.start..<ipco.end)
        else { return nil }
        var width = 0
        var height = 0
        var found = false
        for property in properties where property.type == "ispe" {
            guard property.end - property.start >= 12 else { return nil }
            let w = Int(uint32(b, property.start + 4))
            let h = Int(uint32(b, property.start + 8))
            guard w > 0, h > 0 else { return AvatarImageInfo(width: w, height: h, hasMetadata: true) }
            width = max(width, w)
            height = max(height, h)
            found = true
        }
        guard found else { return nil }
        let infos = metaChildren.filter { $0.type == "iinf" }
        let items = infos.count == 1 ? itemTypes(b, infos[0]) : nil
        let metadata =
            items != ["hvc1"] || metaChildren.contains { $0.type == "iref" }
            || top.filter({ $0.type == "meta" }).count != 1
        return AvatarImageInfo(width: width, height: height, hasMetadata: metadata)
    }

    /// The `item_type` of every `infe` (version 2 or 3) in `iinf`; `nil` if
    /// anything is malformed.
    private static func itemTypes(_ b: [UInt8], _ iinf: Box) -> [String]? {
        guard iinf.end - iinf.start >= 6 else { return nil }
        let countLength = b[iinf.start] == 0 ? 2 : 4  // full box: version, flags, entry count
        let first = iinf.start + 4 + countLength
        guard first <= iinf.end, let entries = boxes(b, in: first..<iinf.end) else { return nil }
        var types: [String] = []
        for infe in entries {
            guard infe.type == "infe", infe.end - infe.start >= 4 else { return nil }
            let idLength: Int
            switch b[infe.start] {
            case 2: idLength = 2
            case 3: idLength = 4
            default: return nil  // versions 0 and 1 carry no item type
            }
            let typeOffset = infe.start + 4 + idLength + 2  // version, flags, id, protection index
            guard typeOffset + 4 <= infe.end else { return nil }
            types.append(fourCC(b, typeOffset))
        }
        return types
    }
}
