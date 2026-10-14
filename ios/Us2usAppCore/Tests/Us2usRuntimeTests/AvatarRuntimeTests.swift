import CoreGraphics
import Foundation
import ImageIO
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// A JPEG as a camera would write it: EXIF, GPS, TIFF maker fields and an
/// orientation, in memory.
func makeCameraJPEG(width: Int, height: Int, orientation: Int = 1, noise: Bool = false) throws -> Data {
    guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
        let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
    else { throw TestTimeout() }
    if noise, let base = context.data {
        let count = context.bytesPerRow * height
        let pixels = base.bindMemory(to: UInt8.self, capacity: count)
        var generator = SystemRandomNumberGenerator()
        for index in 0..<count { pixels[index] = UInt8.random(in: 0...255, using: &generator) }
    } else {
        context.setFillColor(CGColor(srgbRed: 0.9, green: 0.2, blue: 0.1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        context.setFillColor(CGColor(srgbRed: 0.1, green: 0.2, blue: 0.9, alpha: 1))
        context.fill(CGRect(x: width / 2, y: 0, width: width - width / 2, height: height))
    }
    let data = NSMutableData()
    guard let image = context.makeImage(),
        let destination = CGImageDestinationCreateWithData(data as CFMutableData, "public.jpeg" as CFString, 1, nil)
    else { throw TestTimeout() }
    let properties: [CFString: Any] = [
        kCGImagePropertyOrientation: orientation,
        kCGImagePropertyGPSDictionary: [
            kCGImagePropertyGPSLatitude: 41.0082, kCGImagePropertyGPSLatitudeRef: "N",
            kCGImagePropertyGPSLongitude: 28.9784, kCGImagePropertyGPSLongitudeRef: "E",
        ],
        kCGImagePropertyExifDictionary: [
            kCGImagePropertyExifUserComment: "secret place", kCGImagePropertyExifDateTimeOriginal: "2026:10:11 10:00:00",
        ],
        kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "PhoneMaker", kCGImagePropertyTIFFModel: "Model X"],
    ]
    CGImageDestinationAddImage(destination, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw TestTimeout() }
    return data as Data
}

/// The APPn and COM markers of a JPEG before its scan.
func jpegMarkers(_ data: Data) -> [UInt8] {
    let b = [UInt8](data)
    var markers: [UInt8] = []
    var index = 2
    while index + 4 <= b.count, b[index] == 0xFF {
        let marker = b[index + 1]
        if marker == 0xDA { break }
        markers.append(marker)
        index += 2 + (Int(b[index + 2]) << 8 | Int(b[index + 3]))
    }
    return markers
}

@Suite("Profile photos made on the phone (§8.9)")
struct AvatarPhotoProcessorTests {
    @Test("a large camera photo becomes a square of at most 256 px with no metadata, within the byte limit")
    func cameraPhoto() throws {
        let source = try makeCameraJPEG(width: 3000, height: 2000)
        #expect(!AvatarPhotoProcessor.metadataProblems(of: source).isEmpty)
        let photo = try AvatarPhotoProcessor.prepare(source)
        #expect(photo.width == 256 && photo.height == 256)
        #expect(AvatarPhotoProcessor.metadataProblems(of: photo.data).isEmpty)
        #expect(MediaPreparation.identifyingMetadata(of: photo.data).isEmpty)
        #expect(photo.data.range(of: Data("PhoneMaker".utf8)) == nil)
        #expect(photo.data.range(of: Data("secret place".utf8)) == nil)
        #expect(ProfilePicture.photo(photo).serializedProfileAvatar!.count <= ProfilePicture.maxSerializedBytes)
        // What a receiver checks, and what ImageIO says before drawing.
        #expect(ProfilePicture.validated(ProfilePicture.photo(photo).profileAvatar!) == .photo(photo))
        #expect(AvatarPhotoProcessor.isDisplayable(photo))
        let image = try #require(AvatarPhotoProcessor.image(of: photo))
        #expect(image.width == 256 && image.height == 256)
    }

    @Test("JPEG output keeps only what a decoder needs")
    func jpegScrubbing() throws {
        let source = try makeCameraJPEG(width: 640, height: 640)
        #expect(jpegMarkers(source).contains(0xE1))  // ImageIO wrote EXIF
        let scrubbed = try #require(AvatarPhotoProcessor.scrubJPEG(source))
        #expect(!jpegMarkers(scrubbed).contains(0xE1))
        #expect(jpegMarkers(scrubbed).allSatisfy { $0 == 0xE0 || $0 == 0xE2 || $0 == 0xEE || !(0xE0...0xEF).contains($0) })
        #expect(AvatarPhotoProcessor.metadataProblems(of: scrubbed).isEmpty)
        let decoded = try #require(CGImageSourceCreateWithData(scrubbed as CFData, nil))
        #expect(CGImageSourceCreateImageAtIndex(decoded, 0, nil)?.width == 640)

        let photo = try AvatarPhotoProcessor.prepare(source, formats: [.jpeg])
        #expect(photo.format == .jpeg)
        #expect(!jpegMarkers(photo.data).contains(0xE1))
        #expect(!jpegMarkers(photo.data).contains(0xFE))
        #expect(!jpegMarkers(photo.data).contains(0xE2))  // no ICC profile: the pixels are sRGB
        #expect(photo.isSendable && !photo.hasMetadata)
        #expect(photo.data.suffix(2) == Data([0xFF, 0xD9]))
        #expect(AvatarPhotoProcessor.scrubJPEG(Data("not a jpeg".utf8)) == nil)
        #expect(AvatarPhotoProcessor.scrubJPEG(source.prefix(30)) == nil)
        #expect(AvatarPhotoProcessor.scrubJPEG(source.dropLast(2)) == nil)  // no EOI

        // Between scans and after the end: dropped (a camera's trailer, a comment).
        let comment: [UInt8] = [0xFF, 0xFE, 0x00, 0x06] + Array("note".utf8)
        let tampered = source.dropLast(2) + Data(comment) + Data([0xFF, 0xD9]) + Data("trailer".utf8)
        let cleaned = try #require(AvatarPhotoProcessor.scrubJPEG(tampered))
        #expect(cleaned.range(of: Data("note".utf8)) == nil)
        #expect(cleaned.range(of: Data("trailer".utf8)) == nil)
        #expect(cleaned.suffix(2) == Data([0xFF, 0xD9]))
        let cleanedSource = try #require(CGImageSourceCreateWithData(cleaned as CFData, nil))
        #expect(CGImageSourceCreateImageAtIndex(cleanedSource, 0, nil)?.width == 640)
    }

    @Test("orientation is applied to the pixels; small crops are never enlarged")
    func orientationAndCrop() throws {
        // 300 × 100 pixels shown turned by 90° (orientation 6): 100 × 300 on screen.
        let turned = try makeCameraJPEG(width: 300, height: 100, orientation: 6)
        let centred = try AvatarPhotoProcessor.prepare(turned, formats: [.jpeg])
        #expect(centred.width == 100 && centred.height == 100)
        #expect(AvatarPhotoProcessor.metadataProblems(of: centred.data).isEmpty)

        let source = try makeCameraJPEG(width: 400, height: 400)
        let cropped = try AvatarPhotoProcessor.prepare(
            source, crop: CGRect(x: 10, y: 20, width: 120, height: 200), formats: [.jpeg])
        #expect(cropped.width == 120 && cropped.height == 120)
        #expect(throws: AvatarPhotoProcessingError.emptyCrop) {
            try AvatarPhotoProcessor.prepare(source, crop: CGRect(x: 500, y: 500, width: 50, height: 50))
        }
        #expect(throws: AvatarPhotoProcessingError.unreadable) { try AvatarPhotoProcessor.prepare(Data([1, 2, 3])) }
        #expect(try AvatarPhotoProcessor.squareCrop(nil, in: CGSize(width: 300, height: 100))
            == CGRect(x: 100, y: 0, width: 100, height: 100))
    }

    @Test("a photo that does not compress is made smaller until it fits")
    func incompressible() throws {
        let noise = try makeCameraJPEG(width: 1024, height: 1024, noise: true)
        for formats in [[AvatarPhotoFormat.jpeg], [.heic, .jpeg]] {
            let photo = try AvatarPhotoProcessor.prepare(noise, formats: formats)
            #expect(photo.isSquare && photo.width <= 256)
            #expect(ProfilePicture.photo(photo).serializedProfileAvatar!.count <= ProfilePicture.maxSerializedBytes)
            #expect(AvatarPhotoProcessor.metadataProblems(of: photo.data).isEmpty)
        }
    }

    @Test("HEIC where the phone can encode it, else JPEG; either passes the receiver checks")
    func preferredFormat() throws {
        let photo = try AvatarPhotoProcessor.prepare(try makeCameraJPEG(width: 800, height: 600))
        if AvatarPhotoProcessor.canEncode(.heic), photo.format == .heic {
            #expect(AvatarImageHeaderProbe.isHEIC(photo.data))
        } else {
            #expect(photo.format == .jpeg)
        }
        #expect(AvatarPhotoProcessor.isDisplayable(photo))
    }
}

/// The kit's header check, seen from the app core (`AvatarPhoto` succeeds
/// only for a matching signature).
enum AvatarImageHeaderProbe {
    static func isHEIC(_ data: Data) -> Bool { (try? AvatarPhoto(format: .heic, data: data)) != nil }
}

@Suite("Profile pictures in the messaging engine (§8.7, §8.9, §8.10)")
struct AvatarEngineTests {
    @Test("a new picture goes to the contacts; the search setting alone does not")
    func ownPicture() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let own = try await fixture.engine.setOwnAvatar(.preset(3), showInSearch: true)
        #expect(own.picture == .preset(3) && own.showInSearch)
        #expect(fixture.backend.avatarSyncs == 1)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.isProfile } }
        let sent = try #require(fixture.backend.sent(to: peer).last { $0.isProfile })
        guard case .profile(let update)? = sent.control else { return }
        #expect(ProfilePicture(update.profileAvatar) == .preset(3))
        #expect(!update.hasAvatar)
        #expect(update.username == "tester")
        let account = try #require(try fixture.storage.read { try $0.account.load() })
        #expect(account.profilePicture == .preset(3) && !account.avatarSearchOff)

        let profiles = fixture.backend.sent(to: peer).filter(\.isProfile).count
        let hidden = try await fixture.engine.setOwnAvatar(.preset(3), showInSearch: false)
        #expect(!hidden.showInSearch)
        #expect(fixture.backend.avatarSyncs == 2)
        await settle()
        #expect(fixture.backend.sent(to: peer).filter(\.isProfile).count == profiles)
        #expect(try fixture.storage.read { try $0.account.load() }?.avatarSearchOff == true)

        // Every later profile (a new display name) carries the picture too.
        try await fixture.engine.updateUserSettings { $0.displayName = "Selin" }
        try await eventually { fixture.backend.sent(to: peer).filter(\.isProfile).count == profiles + 1 }
        guard case .profile(let renamed)? = fixture.backend.sent(to: peer).last(where: { $0.isProfile })?.control
        else { return }
        #expect(ProfilePicture(renamed.profileAvatar) == .preset(3))
        await fixture.engine.stop()
    }

    @Test("a contact's picture is stored with its profile; a malformed one is the initial letter")
    func contactPicture() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let username = try NormalizedUsername("ayse_k")
        try await fixture.receive(
            .profileUpdate(
                displayName: "Ayşe", username: username, presenceSharing: true, picture: .preset(12),
                updatedAt: fixture.clock.now()),
            from: peer)
        #expect(try fixture.contact(peer)?.profile.avatar == .preset(12))
        let chats = try await fixture.engine.chatSummaries()
        #expect(chats.first { $0.id == peer }?.contact.avatar == .preset(12))
        #expect(await fixture.engine.avatar(of: peer) == AvatarLookup(picture: .preset(12), source: .contact))
        #expect(await fixture.engine.avatar(forUsername: username, account: peer)
            == AvatarLookup(picture: .preset(12), source: .contact))
        #expect(fixture.backend.avatarSearches.isEmpty)  // accepted contacts cost no request

        fixture.clock.advance(by: 1)
        var broken = BRPProfileUpdate()
        broken.displayName = "Ayşe K"
        broken.username = username.value
        broken.presenceSharing = true
        broken.updatedAt = fixture.clock.now().millisecondsSince1970
        broken.profileAvatar = BRPProfileAvatar.with {
            $0.photo = BRPAvatarPhoto.with {
                $0.contentType = "image/jpeg"
                $0.data = Data("no image".utf8)
            }
        }
        try await fixture.receive(BRPContent(control: .profile(broken)), from: peer)
        let contact = try #require(try fixture.contact(peer))
        #expect(contact.profile.avatar == .initials)
        #expect(contact.profile.displayName == "Ayşe K")
        await fixture.engine.stop()
    }

    @Test("an incoming request's picture is fetched only when the user taps for it")
    func requestPicture() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("merhaba"), from: stranger, username: "kuzey_dev")
        let chats = try await fixture.engine.chatSummaries()
        #expect(chats.first?.contact.avatar == .initials)
        #expect(fixture.backend.avatarSearches.isEmpty)

        let photo = try AvatarPhotoProcessor.prepare(try makeCameraJPEG(width: 300, height: 300), formats: [.jpeg])
        fixture.backend.publishSearchPicture(.photo(photo), for: stranger)
        // A list row, or the opened request before a tap: nothing is fetched or decoded (§8.9).
        let row = await fixture.engine.avatar(of: stranger)
        #expect(row == AvatarLookup(picture: .initials, source: .initialLetter, availableOnRequest: true))
        #expect(await fixture.engine.requestAvatar(of: stranger, userConfirmed: false) == row)
        #expect(fixture.backend.avatarSearches.isEmpty)
        // The user taps "Show picture".
        #expect(await fixture.engine.requestAvatar(of: stranger, userConfirmed: true)
            == AvatarLookup(picture: .photo(photo), source: .search))
        #expect(fixture.backend.avatarSearches.map { $0.username } == ["kuzey_dev"])
        fixture.backend.publishSearchPicture(.preset(2), for: stranger)
        #expect(await fixture.engine.requestAvatar(of: stranger, userConfirmed: true)
            == AvatarLookup(picture: .preset(2), source: .search))

        // A request whose sender name was not verified: nothing to search with.
        let unnamed = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("selam"), from: unnamed)
        #expect(await fixture.engine.avatar(of: unnamed) == .initials)
        #expect(await fixture.engine.requestAvatar(of: unnamed, userConfirmed: true) == .initials)
        // Blocked: nothing is fetched.
        try await fixture.engine.block(stranger)
        let searches = fixture.backend.avatarSearches.count
        #expect(await fixture.engine.avatar(of: stranger) == .initials)
        #expect(await fixture.engine.requestAvatar(of: stranger, userConfirmed: true) == .initials)
        #expect(await fixture.engine.avatar(forUsername: try NormalizedUsername("kuzey_dev"), account: stranger)
            == .initials)
        #expect(fixture.backend.avatarSearches.count == searches)
        await fixture.engine.stop()
    }

    @Test("search: our own account shows our choice, strangers their record")
    func search() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        // An accepted contact without a ProfileUpdate yet: its record.
        fixture.backend.publishSearchPicture(.preset(5), for: peer)
        #expect(await fixture.engine.avatar(forUsername: try NormalizedUsername("deniz_a"), account: peer)
            == AvatarLookup(picture: .preset(5), source: .search))
        _ = try await fixture.engine.setOwnAvatar(.preset(1), showInSearch: true)
        #expect(await fixture.engine.avatar(forUsername: try NormalizedUsername("tester"), account: fixture.backend.accountId)
            == AvatarLookup(picture: .preset(1), source: .own))
        #expect(await fixture.engine.avatar(forUsername: try NormalizedUsername("someone"), account: AccountId(uuid: UUID()))
            == .initials)
        await fixture.engine.stop()
    }

    @Test("backups carry the contacts' pictures, and restoring brings them back")
    func backupContacts() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.receive(
            .profileUpdate(
                displayName: "Deniz", username: try NormalizedUsername("deniz_a"), presenceSharing: true,
                picture: .preset(8), updatedAt: fixture.clock.now()),
            from: peer)
        let contacts = try fixture.storage.read { try AccountBackupService.backupContacts($0) }
        let entry = try #require(contacts.first { $0.accountID == peer.bytes })
        #expect(ProfilePicture(entry.avatar) == .preset(8))
        await fixture.engine.stop()

        let other = try EngineFixture()
        var backup = BRPAccountBackup()
        backup.accessKey = Data(repeating: 9, count: 16)
        backup.contacts = contacts
        try other.storage.write { try AccountBackupService.restoreContacts(backup, $0) }
        #expect(try other.contact(peer)?.profile.avatar == .preset(8))
    }
}

@Suite("Profile pictures in the app runtime")
@MainActor
struct AvatarAppRuntimeTests {
    @Test("the own picture is observable, set in one step, and gone while locked")
    func ownAvatar() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        #expect(fixture.runtime.ownAvatar == OwnProfilePicture(picture: .initials, showInSearch: true, searchRecord: .inSync))
        try await fixture.runtime.setAvatar(.preset(4), showInSearch: true)
        #expect(fixture.runtime.ownAvatar?.picture == .preset(4))
        try await fixture.runtime.setAvatarShownInSearch(false)
        #expect(fixture.runtime.ownAvatar?.picture == .preset(4))
        #expect(fixture.runtime.ownAvatar?.showInSearch == false)

        let source = try makeCameraJPEG(width: 1200, height: 900)
        try await fixture.runtime.setAvatar(.photo(source, crop: CGRect(x: 0, y: 0, width: 900, height: 900)), showInSearch: true)
        guard case .photo(let photo)? = fixture.runtime.ownAvatar?.picture else {
            Issue.record("no photo")
            return
        }
        #expect(photo.width == 256 && photo.height == 256)
        #expect(await fixture.runtime.currentAvatar()?.picture == .photo(photo))

        await #expect(throws: AvatarPhotoProcessingError.unreadable) {
            try await fixture.runtime.setAvatar(.photo(Data("nope".utf8)), showInSearch: true)
        }
        await fixture.runtime.lockNow()
        #expect(fixture.runtime.ownAvatar == nil)
        #expect(await fixture.runtime.currentAvatar() == nil)
        #expect(await fixture.runtime.avatar(forUsername: "selin_n", accountId: fixture.backend.accountId) == .initials)
        await #expect(throws: RuntimeError.locked) { try await fixture.runtime.setAvatar(.initials, showInSearch: true) }
    }

    @Test("changing the picture keeps “Show in search” as stored, never assumed on")
    func pictureKeepsTheStoredSearchSetting() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.runtime.setAvatarShownInSearch(false)
        try await fixture.runtime.setAvatar(.preset(5))
        #expect(fixture.runtime.ownAvatar?.picture == .preset(5))
        #expect(fixture.runtime.ownAvatar?.showInSearch == false)
        #expect(await fixture.runtime.currentAvatar()?.showInSearch == false)
        try await fixture.runtime.setAvatarShownInSearch(true)
        try await fixture.runtime.setAvatar(.preset(6))
        #expect(fixture.runtime.ownAvatar?.showInSearch == true)
        await fixture.runtime.lockNow()
        await #expect(throws: RuntimeError.locked) { try await fixture.runtime.setAvatar(.initials) }
    }

    @Test("a search result's picture; invalid input is the initial letter")
    func searchResult() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        let stranger = AccountId(uuid: UUID())
        fixture.backend.publishSearchPicture(.preset(9), for: stranger)
        #expect(await fixture.runtime.avatar(forUsername: "Kuzey_Dev", accountId: stranger)
            == AvatarLookup(picture: .preset(9), source: .search))
        #expect(fixture.backend.avatarSearches.last?.username == "kuzey_dev")
        #expect(await fixture.runtime.avatar(forUsername: "no", accountId: stranger) == .initials)
    }
}
