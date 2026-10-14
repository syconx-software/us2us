import Foundation
import Testing

@testable import Us2usKit

/// Minimal image headers for the §8.9 checks (headers only: nothing here
/// decodes pixels).
enum AvatarFixtures {
    /// SOI, JFIF, (optionally EXIF), (optionally a comment), SOF0 with the
    /// size, SOS, a few bytes, EOI. Without EXIF and comment it is what a
    /// sender may send (§8.9).
    static func jpeg(width: Int, height: Int, exif: Bool = false, comment: Bool = true, fill: Int = 0) -> Data {
        var bytes: [UInt8] = [0xFF, 0xD8]
        bytes += [0xFF, 0xE0, 0x00, 0x10] + Array("JFIF\u{0}".utf8) + [1, 1, 0, 0, 1, 0, 1, 0, 0]
        if exif { bytes += [0xFF, 0xE1, 0x00, 0x08] + Array("Exif".utf8) + [0, 0] }
        if comment { bytes += [0xFF, 0xFE, 0x00, 0x04, 0x68, 0x69] }  // a comment before the frame header
        bytes += [
            0xFF, 0xC0, 0x00, 0x11, 0x08, UInt8(height >> 8), UInt8(height & 0xFF), UInt8(width >> 8),
            UInt8(width & 0xFF), 0x03, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
        ]
        bytes += [0xFF, 0xDA, 0x00, 0x0C, 0x03, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0]
        bytes += [UInt8](repeating: 0x5A, count: fill) + [0x12, 0x34, 0xFF, 0xD9]
        return Data(bytes)
    }

    static func be32(_ value: Int) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    static func box(_ type: String, _ payload: [UInt8]) -> [UInt8] {
        be32(8 + payload.count) + Array(type.utf8) + payload
    }

    static func ispe(_ width: Int, _ height: Int) -> [UInt8] {
        box("ispe", [0, 0, 0, 0] + be32(width) + be32(height))
    }

    static func be16(_ value: Int) -> [UInt8] {
        [UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    /// `ftyp` (major brand `brand`), `meta` with `hdlr`, an `iinf` listing
    /// `items` by type (none at all for `nil`), `iprp/ipco` holding the
    /// given `ispe` boxes and an `iref` if `reference` is set, and a small
    /// `mdat`. Exactly one `hvc1` item and no `iref` is what a sender may
    /// send (§8.9).
    static func heic(
        sizes: [(Int, Int)], brand: String = "heic", items: [String]? = nil, reference: Bool = false
    ) -> Data {
        let ftyp = box("ftyp", Array(brand.utf8) + [0, 0, 0, 0] + Array("mif1".utf8) + Array(brand.utf8))
        let hdlr = box("hdlr", [0, 0, 0, 0, 0, 0, 0, 0] + Array("pict".utf8) + [UInt8](repeating: 0, count: 13))
        var metaPayload: [UInt8] = [0, 0, 0, 0]
        metaPayload += hdlr
        if let items {
            var iinf: [UInt8] = [0, 0, 0, 0]  // version 0: a 16-bit entry count
            iinf += be16(items.count)
            for (index, type) in items.enumerated() {
                var infe: [UInt8] = [2, 0, 0, 0]  // version 2
                infe += be16(index + 1)  // item_ID
                infe += be16(0)  // item_protection_index
                infe += Array(type.utf8)
                infe.append(0)  // empty item_name
                iinf += box("infe", infe)
            }
            metaPayload += box("iinf", iinf)
        }
        let ipco = box("ipco", sizes.flatMap { ispe($0.0, $0.1) })
        metaPayload += box("iprp", ipco)
        if reference {
            var thumbnail: [UInt8] = be16(2)
            thumbnail += be16(1)
            thumbnail += be16(1)
            metaPayload += box("iref", [0, 0, 0, 0] + box("thmb", thumbnail))
        }
        let meta = box("meta", metaPayload)
        return Data(ftyp + meta + box("mdat", [1, 2, 3, 4]))
    }

    static func photo(_ contentType: String, _ data: Data) -> BRPProfileAvatar {
        BRPProfileAvatar.with {
            $0.photo = BRPAvatarPhoto.with {
                $0.contentType = contentType
                $0.data = data
            }
        }
    }
}

@Suite("Profile pictures (§8.9)")
struct ProfilePictureTests {
    /// The header size as an array, for comparisons.
    private func size(_ data: Data, _ format: AvatarPhotoFormat) -> [Int]? {
        AvatarImageHeader.pixelSize(of: data, format: format).map { [$0.width, $0.height] }
    }
    @Test("image headers: JPEG frame size, HEIC ispe, signatures")
    func headers() throws {
        let jpeg = AvatarFixtures.jpeg(width: 200, height: 180, exif: true)
        #expect(AvatarImageHeader.format(of: jpeg) == .jpeg)
        #expect(size(jpeg, .jpeg) == [200, 180])
        let heic = AvatarFixtures.heic(sizes: [(256, 256), (64, 64)])
        #expect(AvatarImageHeader.format(of: heic) == .heic)
        #expect(size(heic, .heic) == [256, 256])
        // `mif1` alone (no HEVC brand) is not image/heic.
        #expect(AvatarImageHeader.format(of: AvatarFixtures.heic(sizes: [(10, 10)], brand: "avif")) == nil)
        #expect(AvatarImageHeader.format(of: Data("not an image".utf8)) == nil)
        // Truncated anywhere: unreadable, never a crash.
        for cut in [3, 10, 25, 30, 40] {
            #expect(size(jpeg.prefix(cut), .jpeg) == nil)
        }
        for cut in [16, 24, 30, 50, heic.count - 20] {
            #expect(size(heic.prefix(cut), .heic) == nil)
        }
        // A scan before any frame header has no size.
        var scanFirst: [UInt8] = [0xFF, 0xD8, 0xFF, 0xDA, 0x00, 0x02]
        scanFirst += [0xFF, 0xD9]
        #expect(size(Data(scanFirst), .jpeg) == nil)
        // A box that claims more than the file holds.
        var lying = [UInt8](heic)
        lying[24 + 3] = 0xFF
        #expect(size(Data(lying), .heic) == nil)
    }

    @Test("what a sender may send: square, and no metadata anywhere in the file (§8.9)")
    func senderRules() throws {
        let plain = AvatarFixtures.jpeg(width: 64, height: 64, comment: false)
        let clean = try AvatarPhoto(format: .jpeg, data: plain)
        #expect(clean.isSendable && !clean.hasMetadata)
        #expect(ProfilePicture.photo(clean).isSendable)

        // Received with any of these, a photo is shown; this client never sends it.
        let commented = try AvatarPhoto(format: .jpeg, data: AvatarFixtures.jpeg(width: 64, height: 64))
        #expect(commented.hasMetadata && !commented.isSendable)
        #expect(!ProfilePicture.photo(commented).isSendable)
        let exif = try AvatarPhoto(format: .jpeg, data: AvatarFixtures.jpeg(width: 64, height: 64, exif: true, comment: false))
        #expect(exif.hasMetadata)
        let trailer = try AvatarPhoto(format: .jpeg, data: plain + Data("trailer".utf8))
        #expect(trailer.hasMetadata && trailer.width == 64)
        let noEnd = try AvatarPhoto(format: .jpeg, data: plain.dropLast(2))
        #expect(noEnd.hasMetadata)
        let exifSegment: [UInt8] = [0xFF, 0xE1, 0x00, 0x08, 0x45, 0x78, 0x69, 0x66, 0, 0, 0xFF, 0xD9]
        let betweenScans = try AvatarPhoto(format: .jpeg, data: plain.dropLast(2) + Data(exifSegment))
        #expect(betweenScans.hasMetadata)
        let wide = try AvatarPhoto(format: .jpeg, data: AvatarFixtures.jpeg(width: 64, height: 32, comment: false))
        #expect(!wide.hasMetadata && !wide.isSendable)

        // Tables between scans, stuffed bytes, restart markers and an ICC
        // profile are not metadata.
        let restartInterval: [UInt8] = [0xFF, 0xDD, 0x00, 0x04, 0x00, 0x10, 0xFF, 0xD9]
        let tables = try AvatarPhoto(format: .jpeg, data: plain.dropLast(2) + Data(restartInterval))
        #expect(!tables.hasMetadata)
        let entropy: [UInt8] = [0xFF, 0x00, 0x12, 0xFF, 0xD3, 0x34, 0xFF, 0xD9]
        let stuffed = try AvatarPhoto(format: .jpeg, data: plain.dropLast(2) + Data(entropy))
        #expect(!stuffed.hasMetadata)
        var iccSegment: [UInt8] = [0xFF, 0xE2, 0x00, 0x10]
        iccSegment += Array("ICC_PROFILE\u{0}".utf8)
        iccSegment += [1, 1]
        let icc = try AvatarPhoto(format: .jpeg, data: plain.prefix(2) + Data(iccSegment) + plain.dropFirst(2))
        #expect(icc.isSendable)

        // HEIC: exactly one item, `hvc1`, and no `iref`.
        let heic = try AvatarPhoto(format: .heic, data: AvatarFixtures.heic(sizes: [(64, 64)], items: ["hvc1"]))
        #expect(heic.isSendable)
        for items in [["hvc1", "Exif"], ["hvc1", "mime"], ["grid"], []] {
            let photo = try AvatarPhoto(format: .heic, data: AvatarFixtures.heic(sizes: [(64, 64)], items: items))
            #expect(photo.hasMetadata, "items \(items)")
        }
        let noItems = try AvatarPhoto(format: .heic, data: AvatarFixtures.heic(sizes: [(64, 64)]))
        #expect(noItems.hasMetadata)
        let thumbnail = try AvatarPhoto(
            format: .heic, data: AvatarFixtures.heic(sizes: [(64, 64)], items: ["hvc1", "hvc1"], reference: true))
        #expect(thumbnail.hasMetadata)
    }

    @Test("receivers accept presets and checked photos, nothing else")
    func validation() throws {
        #expect(ProfilePicture.validated(BRPProfileAvatar.with { $0.presetID = 7 }) == .preset(7))
        #expect(ProfilePicture.validated(BRPProfileAvatar.with { $0.presetID = 0 }) == nil)
        #expect(ProfilePicture.validated(BRPProfileAvatar()) == nil)  // no kind

        let jpeg = AvatarFixtures.jpeg(width: 256, height: 256)
        let picture = try #require(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", jpeg)))
        guard case .photo(let photo) = picture else {
            Issue.record("not a photo")
            return
        }
        #expect(photo.format == .jpeg && photo.width == 256 && photo.height == 256 && photo.isSquare)
        #expect(picture.isPhoto)
        let heic = AvatarFixtures.heic(sizes: [(120, 120)])
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/heic", heic))?.isPhoto == true)

        // The declared type must match the bytes.
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/heic", jpeg)) == nil)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", heic)) == nil)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/png", jpeg)) == nil)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("IMAGE/JPEG", jpeg)) == nil)
        // At most 256 pixels a side, and not 0.
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", AvatarFixtures.jpeg(width: 257, height: 10)))
            == nil)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", AvatarFixtures.jpeg(width: 10, height: 0)))
            == nil)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/heic", AvatarFixtures.heic(sizes: [(256, 256), (4032, 3024)])))
            == nil)  // an auxiliary image larger than allowed
        // Non-square is accepted (drawn cropped to the circle).
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", AvatarFixtures.jpeg(width: 200, height: 100)))
            != nil)
        // 49110 bytes serialized at most.
        let big = AvatarFixtures.jpeg(width: 256, height: 256, fill: 49_100)
        #expect(ProfilePicture.validated(AvatarFixtures.photo("image/jpeg", big)) == nil)
        #expect(throws: AvatarPhotoError.tooManyBytes) { try AvatarPhoto(format: .jpeg, data: big) }
        let fits = AvatarFixtures.jpeg(width: 256, height: 256, fill: 49_000 - 40)
        let fitting = try AvatarPhoto(format: .jpeg, data: fits)
        #expect(ProfilePicture.photo(fitting).serializedProfileAvatar!.count <= ProfilePicture.maxSerializedBytes)
    }

    @Test("stored and received bytes: anything unreadable is the initial letter")
    func serializedForms() throws {
        #expect(ProfilePicture(serialized: nil) == .initials)
        #expect(ProfilePicture(serialized: Data([0xFF, 0xFF])) == .initials)
        #expect(ProfilePicture(serialized: Data()) == .initials)
        let preset = ProfilePicture.preset(3)
        #expect(ProfilePicture(serialized: preset.serializedProfileAvatar) == preset)
        #expect(ProfilePicture.initials.profileAvatar == nil)
        #expect(ProfilePicture.initials.serializedProfileAvatar == nil)
        #expect(!ProfilePicture.preset(0).isSendable)
        #expect(ProfilePicture(nil) == .initials)
    }

    @Test("ProfileUpdate: field 6 carries the picture; field 2 is never sent and always ignored (§8.7)")
    func profileUpdate() throws {
        let username = try NormalizedUsername("kagan_y")
        let content = BRPContent.profileUpdate(
            displayName: "Kağan", username: username, presenceSharing: true, picture: .preset(1),
            updatedAt: Date(timeIntervalSince1970: 1_760_000_000.010))
        guard case .control(let control) = content.body, case .profile(let profile) = control.action else {
            Issue.record("not a profile")
            return
        }
        #expect(profile.profileAvatar.presetID == 1)
        #expect(!profile.hasAvatar)
        try content.validateForSending()
        let none = BRPContent.profileUpdate(displayName: "", username: username, presenceSharing: false)
        guard case .control(let plain) = none.body, case .profile(let plainProfile) = plain.action else { return }
        #expect(!plainProfile.hasProfileAvatar)

        // Receiving: the picture applies with the rest; a malformed one is
        // the initial letter and the other fields still apply.
        var update = profile
        update.username = "kagan_y"
        var stored = ContactProfile().applying(update)
        #expect(stored.avatar == .preset(1))
        #expect(stored.displayName == "Kağan")
        update.profileAvatar = AvatarFixtures.photo("image/jpeg", Data("junk".utf8))
        update.displayName = "Yeni"
        stored = stored.applying(update)
        #expect(stored.avatar == .initials)
        #expect(stored.displayName == "Yeni")
        update.clearProfileAvatar()
        #expect(stored.applying(update).avatar == .initials)

        // A deprecated attachment in field 2, even a malformed one, changes
        // nothing and never makes the content invalid.
        var legacy = update
        legacy.avatar = BRPAttachment.with { $0.mediaID = Data([1, 2, 3]) }
        legacy.profileAvatar = BRPProfileAvatar.with { $0.presetID = 4 }
        let received = BRPContent(control: .profile(legacy))
        try received.validateStructure()
        #expect(ContactProfile().applying(legacy).avatar == .preset(4))
        #expect(throws: ContentValidationError.malformedField("profile.avatar")) { try received.validateForSending() }

        // A sender never sends a picture that fails §8.9.
        var bad = profile
        bad.profileAvatar = BRPProfileAvatar.with { $0.presetID = 0 }
        let badContent = BRPContent(control: .profile(bad))
        try badContent.validateStructure()
        #expect(throws: ContentValidationError.malformedField("profile.profile_avatar")) {
            try badContent.validateForSending()
        }
        // Nor a photo with metadata, whoever built it.
        var tagged = profile
        tagged.profileAvatar = AvatarFixtures.photo("image/jpeg", AvatarFixtures.jpeg(width: 64, height: 64, exif: true))
        let taggedContent = BRPContent(control: .profile(tagged))
        #expect(throws: ContentValidationError.malformedField("profile.profile_avatar")) {
            try taggedContent.validateForSending()
        }
    }

    @Test("accounts saved before profile pictures load with the defaults; the picture round-trips")
    func localAccountCoding() throws {
        let account = LocalAccount(
            accountId: AccountId(uuid: UUID()), authToken: .pattern(32), username: try NormalizedUsername("kagan_y"),
            accessKey: .pattern(16))
        var json = try #require(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(account)) as? [String: Any])
        for key in ["profileAvatar", "avatarSearchOff", "avatarVersion", "avatarRecordPending"] {
            json[key] = nil
        }
        let old = try JSONDecoder().decode(LocalAccount.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(old == account)
        #expect(old.profilePicture == .initials && !old.avatarSearchOff && old.avatarVersion == 0)
        #expect(!old.avatarRecordPending)

        var changed = account
        changed.profileAvatar = ProfilePicture.preset(9).serializedProfileAvatar
        changed.avatarSearchOff = true
        changed.avatarVersion = UInt64(Int64.max)
        changed.avatarRecordPending = true
        let decoded = try JSONDecoder().decode(LocalAccount.self, from: JSONEncoder().encode(changed))
        #expect(decoded == changed)
        #expect(decoded.profilePicture == .preset(9))
    }
}
