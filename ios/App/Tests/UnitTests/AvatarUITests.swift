import Foundation
import SwiftUI
import Testing
import UIKit
import Us2usKit
import Us2usRuntime

@testable import us2us

// Profile pictures in the app (PROTOCOL.md §8.9, §8.10): what each screen
// draws (the mapping), the 7.1 editor's model and framing arithmetic, the
// 4.2 result's picture, 4.4's “Profil resmini göster” and the fixtures.

private let reference = DesignFixtures.referenceDate

@MainActor
private func makeService(hasAccount: Bool = true) -> DesignFixturesService {
    DesignFixturesService(hasAccount: hasAccount, clock: .frozen(at: reference), latency: .zero)
}

/// A JPEG photo of `width` × `height` (two colours, so crops differ), in memory.
private func photoJPEG(width: Int, height: Int) -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
        UIColor.systemTeal.setFill()
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        UIColor.systemOrange.setFill()
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
    }
    return image.jpegData(compressionQuality: 0.9) ?? Data()
}

// MARK: - Mapping

@Suite("Avatars · live mapping")
struct AvatarMappingTests {
    private static let photoData = photoJPEG(width: 64, height: 64)

    private func chat(
        _ state: LiveRequestState, avatar: LiveAvatar, username: String? = "deniz", verified: Bool = true
    ) -> LiveChatRecord {
        LiveChatRecord(
            peer: "6f9b1d1e-2c55-4c3a-9a45-0d3c9d2e8f10", username: username, usernameVerified: verified,
            displayName: nil, requestState: state, verified: false, safetyNumberChanged: false, presenceSharing: false,
            ghostEnabled: false, ghostTimeoutSeconds: 0, ghostDeadline: nil, expireTimerSeconds: 0, unreadCount: 0,
            lastActivity: reference, lastMessage: nil, avatar: avatar)
    }

    @Test("an accepted contact shows their preset or photo")
    func acceptedContacts() {
        let preset = LiveMapping.peer(chat(.accepted, avatar: .preset(7)), presence: nil, now: reference, ownSharing: true)
        #expect(preset.avatar == .preset(7))
        let photo = LiveMapping.peer(
            chat(.accepted, avatar: .photo(data: Self.photoData, contentType: "image/jpeg")), presence: nil,
            now: reference, ownSharing: true)
        #expect(photo.avatar.isPhoto)
        #expect(photo.avatar.photo?.data == Self.photoData)
        #expect(photo.avatar.photo?.contentType == "image/jpeg")
    }

    @Test(
        "an incoming request or a blocked contact never shows a picture, whatever the record holds",
        arguments: [LiveRequestState.incomingRequest, .blocked])
    func strangersShowTheInitial(_ state: LiveRequestState) {
        for avatar in [LiveAvatar.preset(3), .photo(data: Self.photoData, contentType: "image/jpeg")] {
            let peer = LiveMapping.peer(chat(state, avatar: avatar), presence: nil, now: reference, ownSharing: true)
            #expect(peer.avatar == .initials)
            #expect(peer.avatar.photo == nil, "no photo bytes reach the UI")
        }
    }

    @Test("the user's own request shows the search copy found this session")
    func ownRequestShowsTheSearchCopy() {
        let peer = LiveMapping.peer(chat(.outgoingRequest, avatar: .preset(14)), presence: nil, now: reference, ownSharing: true)
        #expect(peer.avatar == .preset(14))
        #expect(LiveMapping.peer(chat(.outgoingRequest, avatar: .initials), presence: nil, now: reference, ownSharing: true).avatar == .initials)
    }

    @Test("an unknown preset is kept, and drawn as the initial; preset 0 is the initial")
    func unknownPresets() {
        let newer = LiveMapping.avatar(.preset(999))
        #expect(newer == .preset(999))
        #expect(newer.isInitials)
        #expect(LiveMapping.avatar(.preset(0)) == .initials)
        #expect(!LiveMapping.avatar(.preset(2)).isInitials)
    }

    @Test("“Profil resmini göster” is offered only for an incoming request with a verified username")
    func offersAvatar() {
        #expect(LiveMapping.offersAvatar(of: chat(.incomingRequest, avatar: .initials)))
        #expect(!LiveMapping.offersAvatar(of: chat(.incomingRequest, avatar: .initials, verified: false)))
        #expect(!LiveMapping.offersAvatar(of: chat(.incomingRequest, avatar: .initials, username: nil)))
        #expect(!LiveMapping.offersAvatar(of: chat(.accepted, avatar: .initials)))
        #expect(!LiveMapping.offersAvatar(of: chat(.outgoingRequest, avatar: .initials)))
        let request = LiveMapping.request(chat(.incomingRequest, avatar: .preset(5)), now: reference)
        #expect(request.offersAvatar)
        #expect(request.avatar == .initials, "a request never draws a picture by itself")
    }

    @Test("the own picture and “Aramada göster”; locked is the initial with the setting on")
    func ownAvatar() {
        #expect(LiveMapping.ownAvatar(nil) == OwnAvatarState())
        let own = LiveMapping.ownAvatar(LiveOwnAvatar(avatar: .preset(4), showInSearch: false, status: .pending))
        #expect(own.picture == .preset(4))
        #expect(!own.showInSearch)
        #expect(own.searchCopy == .pending)
        #expect(!own.isShownInSearch)
        let shown = LiveMapping.ownAvatar(LiveOwnAvatar(avatar: .preset(4), showInSearch: true, status: .unavailable))
        #expect(shown.isShownInSearch)
        #expect(shown.searchCopy == .unavailable)
        #expect(!LiveMapping.ownAvatar(LiveOwnAvatar(avatar: .initials, showInSearch: true, status: .inSync)).isShownInSearch)
    }

    @Test("records from the runtime's pictures")
    func runtimeRecords() throws {
        #expect(LiveAvatar(ProfilePicture.initials) == .initials)
        #expect(LiveAvatar(ProfilePicture.preset(9)) == .preset(9))
        let photo = try AvatarPhoto(contentType: "image/jpeg", data: Self.photoData)
        #expect(LiveAvatar(ProfilePicture.photo(photo)) == .photo(data: Self.photoData, contentType: "image/jpeg"))
        let own = LiveOwnAvatar(OwnProfilePicture(picture: .preset(3), showInSearch: false, searchRecord: .unavailable))
        #expect(own == LiveOwnAvatar(avatar: .preset(3), showInSearch: false, status: .unavailable))
    }

    @Test("photo bytes are compared by their digest")
    func photoEquality() {
        let a = AvatarPhotoData(data: Self.photoData, contentType: "image/jpeg")
        let b = AvatarPhotoData(data: Data(Self.photoData), contentType: "image/jpeg")
        let c = AvatarPhotoData(data: photoJPEG(width: 32, height: 32), contentType: "image/jpeg")
        #expect(a == b)
        #expect(a.hashValue == b.hashValue)
        #expect(a.cacheKey == b.cacheKey)
        #expect(a != c)
        #expect(AvatarPicture(choice: .preset(2), photo: a).photo == nil, "only a photo keeps bytes")
        #expect(AvatarPicture(choice: .photo).isInitials, "a photo without bytes draws the initial")
    }

    @Test("only presets of the catalog are sent; an empty photo or crop is refused")
    func liveSelections() throws {
        #expect(try RealMessengerService.selection(for: .initials) == .initials)
        #expect(try RealMessengerService.selection(for: .preset(2)) == .preset(2))
        #expect(throws: AvatarEditError.failed) { try RealMessengerService.selection(for: .preset(999)) }
        #expect(throws: AvatarEditError.failed) { try RealMessengerService.selection(for: .preset(0)) }
        #expect(throws: AvatarEditError.unusablePhoto) {
            try RealMessengerService.selection(for: .photo(Data(), crop: CGRect(x: 0, y: 0, width: 10, height: 10)))
        }
        #expect(throws: AvatarEditError.unusablePhoto) {
            try RealMessengerService.selection(for: .photo(Self.photoData, crop: .zero))
        }
        let crop = CGRect(x: 4, y: 4, width: 40, height: 40)
        #expect(try RealMessengerService.selection(for: .photo(Self.photoData, crop: crop)) == .photo(Self.photoData, crop: crop))
    }
}

// MARK: - Framing

@Suite("Avatars · framing geometry")
struct AvatarCropGeometryTests {
    private let landscape = AvatarCropGeometry(imageSize: CGSize(width: 2_000, height: 1_000), side: 300)

    @Test("at zoom 1 the shorter side fills the square, centred")
    func centredSquare() {
        #expect(abs(landscape.baseScale - 0.3) < 1e-9)
        let displayed = landscape.displayedSize(zoom: 1)
        #expect(abs(displayed.width - 600) < 1e-6 && abs(displayed.height - 300) < 1e-6)
        #expect(landscape.cropRect(zoom: 1, offset: .zero) == CGRect(x: 500, y: 0, width: 1_000, height: 1_000))
        let portrait = AvatarCropGeometry(imageSize: CGSize(width: 900, height: 1_600), side: 320)
        #expect(portrait.cropRect(zoom: 1, offset: .zero) == CGRect(x: 0, y: 350, width: 900, height: 900))
    }

    @Test("the photo can only move as far as it still covers the square")
    func clampedOffsets() {
        let clamped = landscape.clampedOffset(CGSize(width: 1_000, height: 50), zoom: 1)
        #expect(abs(clamped.width - 150) < 1e-6 && abs(clamped.height) < 1e-6)
        #expect(landscape.cropRect(zoom: 1, offset: CGSize(width: 1_000, height: 0)).minX == 0)
        #expect(landscape.cropRect(zoom: 1, offset: CGSize(width: -1_000, height: 0)).maxX == 2_000)
        #expect(landscape.clampedOffset(CGSize(width: CGFloat.nan, height: CGFloat.infinity), zoom: 1) == .zero)
    }

    @Test("zooming in frames a smaller square, never below the minimum")
    func zoom() {
        #expect(landscape.maxZoom == 6)
        #expect(landscape.cropRect(zoom: 2, offset: .zero) == CGRect(x: 750, y: 250, width: 500, height: 500))
        let deepest = landscape.cropRect(zoom: 40, offset: .zero)
        #expect(deepest.width == 166)
        #expect(deepest.width == deepest.height)
        #expect(landscape.clampedZoom(0.2) == 1)
        #expect(landscape.clampedZoom(.nan) == 1)
        let small = AvatarCropGeometry(imageSize: CGSize(width: 120, height: 100), side: 300)
        #expect(small.maxZoom >= 1)
        #expect(small.cropRect(zoom: small.maxZoom, offset: .zero).width >= AvatarCropGeometry.minimumCropPixels)
    }

    @Test("every framing stays inside the photo, in whole pixels")
    func insideThePhoto() {
        for zoom in stride(from: 1.0, through: 6.0, by: 0.7) {
            for dx in stride(from: -900.0, through: 900.0, by: 300) {
                for dy in stride(from: -900.0, through: 900.0, by: 300) {
                    let rect = landscape.cropRect(zoom: zoom, offset: CGSize(width: dx, height: dy))
                    #expect(rect.minX >= 0 && rect.minY >= 0)
                    #expect(rect.maxX <= 2_000 && rect.maxY <= 1_000)
                    #expect(rect.width == rect.height)
                    #expect(rect.minX == rect.minX.rounded() && rect.width == rect.width.rounded())
                }
            }
        }
    }

    @Test("VoiceOver steps zoom in and out within the limits")
    func steps() {
        let up = landscape.steppedZoom(1, in: 1)
        #expect(up > 1)
        #expect(landscape.steppedZoom(up, in: -1) == 1)
        #expect(landscape.steppedZoom(1, in: -1) == 1)
        #expect(landscape.steppedZoom(landscape.maxZoom, in: 1) == landscape.maxZoom)
    }
}

// MARK: - 7.1 › Profil görseli

@MainActor
@Suite("Avatars · 7.1 editor")
struct AvatarEditorModelTests {
    private func sources(
        pick: AvatarSourceImage? = nil, camera: AvatarPhotoSourcing.CameraAccess = .available,
        take: AvatarSourceImage? = nil
    ) -> AvatarPhotoSourcing {
        AvatarPhotoSourcing(pick: { pick }, cameraAccess: { camera }, take: { take })
    }

    private func picked(width: Int = 600, height: Int = 400) throws -> AvatarSourceImage {
        try #require(AvatarSourceImage.make(from: photoJPEG(width: width, height: height)))
    }

    @Test("a preset is saved at once and the sheet may close")
    func preset() async {
        let service = makeService()
        let model = AvatarEditorModel(messenger: service, sources: sources())
        #expect(model.current == .initials)
        #expect(await model.choose(.preset(2)))
        #expect(service.ownAvatar.picture == .preset(2))
        #expect(model.current == .preset(2))
        #expect(model.savedChanges == 1)
        #expect(await model.choose(.initials))
        #expect(service.ownAvatar.picture == .initials)
        #expect(model.toast == nil)
    }

    @Test("a preset this build does not list is never sent")
    func unknownPreset() async {
        let service = makeService()
        let model = AvatarEditorModel(messenger: service, sources: sources())
        #expect(!(await model.choose(.preset(999))))
        #expect(service.ownAvatar.picture == .initials)
        #expect(model.toast != nil)
        #expect(model.savedChanges == 0)
    }

    @Test("a picked photo is framed, then saved at ≤ 256 px without metadata")
    func pickedPhoto() async throws {
        let service = makeService()
        let source = try picked()
        #expect(source.pixelSize == CGSize(width: 600, height: 400))
        let model = AvatarEditorModel(messenger: service, sources: sources(pick: source))
        await model.pickPhoto()
        #expect(model.framing == source)
        let crop = AvatarCropGeometry(imageSize: source.pixelSize, side: 300).cropRect(zoom: 1, offset: .zero)
        #expect(crop == CGRect(x: 100, y: 0, width: 400, height: 400))
        #expect(await model.useFramedPhoto(crop: crop))
        #expect(model.framing == nil)
        #expect(model.savedChanges == 1)
        let photo = try #require(service.ownAvatar.picture.photo)
        let checked = try AvatarPhoto(contentType: photo.contentType, data: photo.data)
        #expect(checked.width == 256 && checked.height == 256)
        #expect(checked.isSendable, "square and without metadata")
        #expect(AvatarPhotoDecoder.decode(photo.data, contentType: photo.contentType) != nil)
    }

    @Test("a photo that cannot be used keeps the framing screen and says so")
    func unusablePhoto() async throws {
        let service = makeService()
        let good = try picked()
        let broken = AvatarSourceImage(image: good.image, jpeg: Data([0x00, 0x01, 0x02]))
        let model = AvatarEditorModel(messenger: service, sources: sources(pick: broken))
        await model.pickPhoto()
        #expect(!(await model.useFramedPhoto(crop: CGRect(x: 0, y: 0, width: 100, height: 100))))
        #expect(model.framing == broken)
        #expect(model.toast != nil)
        #expect(service.ownAvatar.picture == .initials)
        model.cancelFraming()
        #expect(model.framing == nil)
    }

    @Test("a cancelled picker changes nothing")
    func cancelledPicker() async {
        let model = AvatarEditorModel(messenger: makeService(), sources: sources(pick: nil))
        await model.pickPhoto()
        #expect(model.framing == nil)
        #expect(model.phase == .idle)
        #expect(model.toast == nil)
    }

    @Test("the camera: denied or missing says so; a photo taken is framed")
    func camera() async throws {
        let denied = AvatarEditorModel(messenger: makeService(), sources: sources(camera: .denied))
        await denied.takePhoto()
        #expect(denied.framing == nil)
        #expect(denied.toast != nil)
        let missing = AvatarEditorModel(messenger: makeService(), sources: sources(camera: .unavailable))
        await missing.takePhoto()
        #expect(missing.toast != nil)
        let restricted = AvatarEditorModel(messenger: makeService(), sources: sources(camera: .restricted))
        await restricted.takePhoto()
        #expect(restricted.toast != nil)
        #expect(restricted.framing == nil)
        let taken = try picked(width: 400, height: 700)
        let camera = AvatarEditorModel(messenger: makeService(), sources: sources(take: taken))
        await camera.takePhoto()
        #expect(camera.framing == taken)
    }

    @Test("the search note follows “Aramada göster”")
    func searchNote() async {
        let service = makeService()
        let model = AvatarEditorModel(messenger: service, sources: sources())
        #expect(model.notesSearch, "on by default (PROTOCOL §8.10)")
        #expect(await service.setAvatarShownInSearch(false) == false)
        #expect(!model.notesSearch)
        #expect(!service.ownAvatar.isShownInSearch)
    }
}

// MARK: - 4.2 · the result's picture

@MainActor
@Suite("Avatars · 4.2 search result")
struct NewChatAvatarTests {
    /// Waits (briefly) until `condition` holds, instead of a fixed sleep.
    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0..<300 where !condition() {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test("a stranger's picture is fetched after the exact hit")
    func fetchedAfterTheHit() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        model.query = "lale"
        #expect(model.resultAvatar == .noResult, "nothing is asked while typing")
        await eventually { model.resultAvatar == .loaded(.preset(14)) }
        #expect(model.resultAvatar == .loaded(.preset(14)))
        #expect(model.resultAvatar.picture == .preset(14))
    }

    @Test("a pause in typing finds the result but fetches no picture until it settles")
    func settlesBeforeFetching() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10), avatarSettle: .seconds(30))
        model.query = "lale"
        await eventually { if case .found = model.phase { true } else { false } }
        guard case .found = model.phase else {
            Issue.record("the lookup found lale")
            return
        }
        try await Task.sleep(for: .milliseconds(100))
        #expect(model.resultAvatar == .loading, "the initial and the ring, nothing fetched yet")
        model.query = "lale_x"
        #expect(model.resultAvatar == .noResult)
        // Return does not wait.
        model.query = "lale"
        model.submit()
        await eventually { model.resultAvatar == .loaded(.preset(14)) }
        #expect(model.resultAvatar == .loaded(.preset(14)))
    }

    @Test("no picture is the initial; editing the name drops the result's picture")
    func initialAndEditing() async throws {
        let model = NewChatModel(messenger: makeService(), debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        model.query = "deniz"
        await eventually { model.resultAvatar == .loaded(.initials) }
        #expect(model.resultAvatar == .loaded(.initials))
        model.query = "deniz_k"
        #expect(model.resultAvatar == .noResult)
    }

    @Test("an accepted contact's picture and the own one are there at once")
    func knownPictures() async throws {
        let service = makeService()
        let model = NewChatModel(messenger: service, debounce: .milliseconds(10), avatarSettle: .milliseconds(10))
        model.query = "oya_t"
        await eventually { model.resultAvatar.picture == DesignFixtures.photoAvatar }
        #expect(model.resultAvatar.picture == DesignFixtures.photoAvatar)
        service.ownAvatar.picture = .preset(9)
        model.query = "selin_n"
        #expect(model.phase == .ownName)
        #expect(model.resultAvatar == .loaded(.preset(9)))
        #expect(NewChatModel.resultAvatar(for: .searching, own: .preset(9)) == .noResult)
        #expect(NewChatModel.resultAvatar(for: .notFound, own: .preset(9)) == .noResult)
    }
}

// MARK: - Fixtures, 4.4 and drawing

@MainActor
@Suite("Avatars · fixtures and requests")
struct AvatarFixtureTests {
    @Test("4.1 shows three presets and one photo below the design's first rows")
    func chatList() {
        let chats = makeService().chats
        let pictures = chats.map(\.peer.avatar)
        let firstThreeAreInitials = pictures.prefix(3).allSatisfy { $0.isInitials }
        #expect(firstThreeAreInitials, "deniz, mert_k and ayla_ as drawn")
        #expect(pictures.filter { if case .preset = $0.choice { true } else { false } }.count == 3)
        #expect(pictures.filter(\.isPhoto).count == 1)
        for picture in pictures {
            if case .preset(let id) = picture.choice { #expect(BrandAvatarCatalog.preset(id: id) != nil) }
        }
    }

    @Test("the demo photo is a real profile photo")
    func demoPhoto() throws {
        let photo = try #require(DesignFixtures.photoAvatar.photo)
        let checked = try AvatarPhoto(contentType: photo.contentType, data: photo.data)
        #expect(checked.width <= 256 && checked.height <= 256)
        #expect(checked.isSendable)
        let decoded = try #require(AvatarPhotoDecoder.decode(photo.data, contentType: photo.contentType))
        #expect(decoded.image.width == checked.width)
    }

    @Test("a stranger's picture only after “Profil resmini göster”")
    func requests() async throws {
        let service = makeService()
        let kuzey = try #require(service.requests.first { $0.sender == "kuzey_dev" })
        let noPicture = try #require(service.requests.first { $0.sender == "n_aksoy" })
        let suspicious = try #require(service.requests.first { $0.sender == "hediye_kazan" })
        for request in service.requests { #expect(request.avatar == .initials) }
        #expect(kuzey.offersAvatar && noPicture.offersAvatar)
        #expect(!suspicious.offersAvatar, "an unverified name is never looked up")
        #expect(await service.revealAvatar(of: kuzey) == .preset(21))
        #expect(await service.revealAvatar(of: noPicture) == .initials)
        #expect(await service.revealAvatar(of: suspicious) == .initials)
        let chat = await service.accept(kuzey)
        #expect(service.chats.first { $0.id == chat }?.peer.avatar == .preset(21))
    }

    @Test("the user's own request keeps the search copy")
    func ownRequest() async {
        let service = makeService()
        let chat = await service.startChat(username: "lale")
        #expect(service.chats.first { $0.id == chat }?.peer.avatar == .preset(14))
    }

    @Test("erasing everything forgets the own picture")
    func erase() async throws {
        let service = makeService()
        try await service.setAvatar(.preset(3))
        await service.setAvatarShownInSearch(false)
        await service.eraseEverything()
        #expect(service.ownAvatar == OwnAvatarState())
    }

    @Test("an avatar keeps C12's size whatever it draws")
    func sizes() {
        for picture in [AvatarPicture.initials, .preset(2), .preset(999), DesignFixtures.photoAvatar] {
            #expect(layoutSize(Avatar(username: "deniz", palette: .a, picture: picture)) == CGSize(width: 52, height: 52))
            #expect(
                layoutSize(Avatar(username: "deniz", palette: .a, size: .header, picture: picture))
                    == CGSize(width: 38, height: 38))
        }
    }
}
