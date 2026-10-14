import AVFoundation
import Foundation
import Testing
import UIKit

@testable import us2us

// 5.1 / 5.2 media: the photo viewer's zoom and swipe arithmetic, the video
// player's memory loading, the protected preview copies and their names,
// what Quick Look may show, the live mapping of where a file is, and which
// presenter a tap opens.

@Suite("Media · viewer geometry")
struct ViewerGeometryTests {
    @Test func aPhotoFitsTheScreenWhole() {
        let landscape = ZoomMath.fittedSize(CGSize(width: 3_000, height: 2_000), in: CGSize(width: 402, height: 874))
        #expect(landscape == CGSize(width: 402, height: 268))
        let portrait = ZoomMath.fittedSize(CGSize(width: 1_000, height: 4_000), in: CGSize(width: 402, height: 874))
        #expect(abs(portrait.height - 874) < 0.001)
        #expect(abs(portrait.width - 218.5) < 0.001)
        #expect(ZoomMath.fittedSize(.zero, in: CGSize(width: 402, height: 874)) == .zero)
    }

    @Test func smallerContentIsCentred() {
        let insets = ZoomMath.centeringInsets(content: CGSize(width: 402, height: 268), bounds: CGSize(width: 402, height: 874))
        #expect(insets == UIEdgeInsets(top: 303, left: 0, bottom: 303, right: 0))
        // Zoomed past the screen: no insets, the scroll view pans.
        let zoomed = ZoomMath.centeringInsets(content: CGSize(width: 1_005, height: 670), bounds: CGSize(width: 402, height: 874))
        #expect(zoomed == UIEdgeInsets(top: 102, left: 0, bottom: 102, right: 0))
        #expect(ZoomMath.centeringInsets(content: CGSize(width: 900, height: 900), bounds: CGSize(width: 402, height: 874)) == .zero)
    }

    @Test func doubleTapZoomsAroundTheTappedPoint() {
        let rect = ZoomMath.zoomRect(
            centeredAt: CGPoint(x: 100, y: 50), scale: ZoomMath.doubleTapScale, bounds: CGSize(width: 400, height: 800))
        #expect(rect.size == CGSize(width: 160, height: 320))
        #expect(rect.midX == 100)
        #expect(rect.midY == 50)
    }

    @Test func zoomGoesAtLeastToFourTimesAndFurtherForDetailedPhotos() {
        // A 2048 px photo fitted 402 pt wide at 3×: 1.7× its pixels → 4×.
        #expect(ZoomMath.maximumZoom(pixelWidth: 2_048, fittedWidth: 402, displayScale: 3) == 4)
        // A 4096 px photo: twice its own pixels (6.8×).
        let detailed = ZoomMath.maximumZoom(pixelWidth: 4_096, fittedWidth: 402, displayScale: 3)
        #expect(abs(detailed - 4_096 / 1_206 * 2) < 0.001)
        #expect(ZoomMath.maximumZoom(pixelWidth: 40_000, fittedWidth: 402, displayScale: 3) == 10)
        #expect(ZoomMath.maximumZoom(pixelWidth: 160, fittedWidth: 0, displayScale: 3) == 4)
    }

    @Test func swipeAwayClosesPastTheDistanceOrOnAFlick() {
        #expect(MediaDismissGesture.shouldDismiss(translation: 120, velocity: 0))
        #expect(MediaDismissGesture.shouldDismiss(translation: -130, velocity: -100), "up works too")
        #expect(!MediaDismissGesture.shouldDismiss(translation: 60, velocity: 300))
        #expect(MediaDismissGesture.shouldDismiss(translation: 40, velocity: 1_200), "a flick")
        #expect(!MediaDismissGesture.shouldDismiss(translation: 150, velocity: -1_500), "flicked back settles")
        #expect(MediaDismissGesture.progress(translation: 0, height: 874) == 0)
        #expect(MediaDismissGesture.progress(translation: 2_000, height: 874) == 1)
        #expect(MediaDismissGesture.scale(progress: 1) == 0.9)
        #expect(abs(MediaDismissGesture.backdropOpacity(progress: 1) - 0.15) < 0.0001)
        #expect(MediaDismissGesture.backdropOpacity(progress: -1) == 1)
    }
}

@Suite("Media · video from memory")
struct MemoryAssetLoaderTests {
    @Test func rangesAreClampedToTheClip() {
        #expect(MemoryAssetLoader.byteRange(offset: 0, length: 2, toEnd: false, total: 10) == 0..<2)
        #expect(MemoryAssetLoader.byteRange(offset: 4, length: 100, toEnd: false, total: 10) == 4..<10)
        #expect(MemoryAssetLoader.byteRange(offset: 3, length: 0, toEnd: true, total: 10) == 3..<10)
        #expect(MemoryAssetLoader.byteRange(offset: 10, length: 1, toEnd: false, total: 10) == nil)
        #expect(MemoryAssetLoader.byteRange(offset: -1, length: 1, toEnd: false, total: 10) == nil)
        #expect(MemoryAssetLoader.byteRange(offset: 2, length: 0, toEnd: false, total: 10) == nil)
    }

    @Test func theAssetUsesAPrivateSchemeAndTheClipsType() {
        let mp4 = MemoryAssetLoader(data: Data([0, 1, 2]), contentType: "video/mp4")
        #expect(mp4.uniformType == "public.mpeg-4")
        #expect(mp4.url.scheme == MemoryAssetLoader.scheme)
        #expect(mp4.url.isFileURL == false)
        let mov = MemoryAssetLoader(data: Data([0]), contentType: "video/quicktime")
        #expect(mov.uniformType == "com.apple.quicktime-movie")
        let unknown = MemoryAssetLoader(data: Data([0]), contentType: "application/octet-stream")
        #expect(unknown.uniformType == "public.mpeg-4")
        // A slice is re-based, so offsets index the clip from zero.
        let slice = Data([9, 9, 1, 2, 3])[2...]
        #expect(MemoryAssetLoader(data: slice, contentType: "video/mp4").data == Data([1, 2, 3]))
    }

    /// The fixtures' 18 s clip, synthesised, plays from memory: the asset
    /// loads through the resource loader with its length and video track.
    @MainActor
    @Test func theFixtureClipLoadsFromMemory() async throws {
        let data = try #require(await FixtureMedia.videoMP4())
        #expect(data.count > 10_000)
        let loader = MemoryAssetLoader(data: data, contentType: "video/mp4")
        let asset = loader.makeAsset()
        let duration = try await asset.load(.duration)
        #expect(abs(duration.seconds - 18) < 0.2)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        #expect(tracks.count == 1)
        withExtendedLifetime(loader) {}
    }
}

/// Serialised: the tests share the one preview folder.
@Suite("Media · preview copies", .serialized)
struct PreviewFilesTests {
    @Test func namesFromTheOtherSideAreMadeSafe() {
        #expect(FileNameSanitizer.sanitized("../../etc/passwd") == "passwd")
        #expect(FileNameSanitizer.sanitized("..\\..\\boot.ini") == "boot.ini")
        // The right-to-left override that shows “fatura‮fdp.exe” as “faturaexe.pdf”.
        #expect(FileNameSanitizer.sanitized("fatura\u{202E}fdp.exe") == "faturafdp.exe")
        #expect(FileNameSanitizer.sanitized("\u{2066}gizli\u{2069}.pdf") == "gizli.pdf")
        #expect(FileNameSanitizer.sanitized(".hidden.pdf") == "hidden.pdf")
        #expect(FileNameSanitizer.sanitized("a:b.txt") == "ab.txt")
        #expect(FileNameSanitizer.sanitized("rapor\n2026.pdf") == "rapor2026.pdf")
        #expect(FileNameSanitizer.sanitized(nil, contentType: "application/pdf") == "file.pdf")
        #expect(FileNameSanitizer.sanitized("  ", contentType: nil) == "file")
        #expect(FileNameSanitizer.sanitized("kira-sozlesmesi.pdf") == "kira-sozlesmesi.pdf")
        let long = FileNameSanitizer.sanitized(String(repeating: "a", count: 300) + ".docx")
        #expect(long.count == FileNameSanitizer.maxLength)
        #expect(long.hasSuffix(".docx"))
    }

    @Test func aCopyIsProtectedNamedAndGoesWhenDiscarded() async throws {
        ProtectedPreviewFiles.removeAll()
        let payload = MediaPayload(data: Data("%PDF-1.7".utf8), contentType: "application/pdf", fileName: "../kira.pdf")
        let url = try #require(await ProtectedPreviewFiles.write(payload))
        #expect(url.lastPathComponent == "kira.pdf")
        #expect(url.deletingLastPathComponent().deletingLastPathComponent().path == ProtectedPreviewFiles.root.path)
        #expect(try Data(contentsOf: url) == payload.data)
        let values = try ProtectedPreviewFiles.root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
        #expect(ProtectedPreviewFiles.existingFiles.map(\.lastPathComponent).contains("kira.pdf"))
        ProtectedPreviewFiles.discard(url)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path), "its folder goes too")
    }

    @MainActor
    @Test func theFixturesPreviewCopyIsNamedAsSent() async throws {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let url = try #require(await service.previewFile("m1", in: "mert_k"))
        #expect(url.lastPathComponent == "kira-sozlesmesi.pdf")
        #expect(FileManager.default.fileExists(atPath: url.path))
        service.discardPreviewFile(url)
        #expect(!FileManager.default.fileExists(atPath: url.path))
    }

    @Test func discardTouchesNothingOutsideThePreviewFolder() throws {
        let outside = FileManager.default.temporaryDirectory.appendingPathComponent("keep-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let file = outside.appendingPathComponent("a.txt")
        try Data("x".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: outside) }
        ProtectedPreviewFiles.discard(file)
        #expect(FileManager.default.fileExists(atPath: file.path))
    }

    @Test func removeAllSweepsEveryCopy() async throws {
        let first = try #require(ProtectedPreviewFiles.writeNow(Data([1]), name: "a.pdf"))
        let second = try #require(ProtectedPreviewFiles.writeNow(Data([2]), name: "b.pdf"))
        ProtectedPreviewFiles.removeAll()
        #expect(!FileManager.default.fileExists(atPath: first.path))
        #expect(!FileManager.default.fileExists(atPath: second.path))
        #expect(!FileManager.default.fileExists(atPath: ProtectedPreviewFiles.root.path))
    }

    @Test func quickLookShowsDocumentsButNeverWebContent() {
        for name in ["kira.pdf", "foto.jpg", "not.txt", "tablo.csv", "rapor.docx", "sunum.key", "ses.m4a", "video.mp4"] {
            #expect(FilePreviewPolicy.allowsPreview(fileName: name), "\(name)")
        }
        for name in ["sayfa.html", "sayfa.htm", "veri.xml", "logo.svg", "arsiv.webarchive", "betik.js", "bilinmeyen.xyz", "uzantisiz"] {
            #expect(!FilePreviewPolicy.allowsPreview(fileName: name), "\(name)")
        }
    }

    @Test func fileKindsFollowNameAndType() {
        #expect(MediaFileKind(fileName: "kira-sozlesmesi.pdf") == .pdf)
        #expect(MediaFileKind(fileName: "video.MOV") == .movie)
        #expect(MediaFileKind(fileName: "klip", contentType: "video/mp4") == .movie)
        #expect(MediaFileKind(fileName: "ses.m4a") == .audio)
        #expect(MediaFileKind(fileName: "foto.heic") == .image)
        #expect(MediaFileKind(fileName: "notlar.txt") == .other)
    }
}

@Suite("Media · live mapping")
@MainActor
struct MediaMappingTests {
    private let now = Date(timeIntervalSince1970: 1_791_000_000)

    private func record(_ id: UInt8, _ direction: LiveMessageRecord.Direction, _ attachment: LiveAttachment, viewOnce: Bool = false)
        -> LiveMessageRecord
    {
        LiveMessageRecord(
            id: Data(repeating: id, count: 16), direction: direction, body: .attachment(attachment), sentAt: now,
            quoteTarget: nil, quoteText: nil, viewOnce: viewOnce, status: direction == .outgoing ? .sent : nil)
    }

    private func file(_ availability: LiveAttachment.Availability, width: UInt32 = 0, height: UInt32 = 0) -> LiveAttachment {
        LiveAttachment(
            kind: .file, contentType: "application/pdf", fileName: "kira.pdf", size: 2_100_000, durationMs: 0,
            thumbnail: nil, caption: nil, waveform: nil, availability: availability, width: width, height: height)
    }

    @Test func whereTheFileIsReachesTheBubble() {
        let records = [
            record(1, .incoming, file(.local)), record(2, .incoming, file(.transferring)),
            record(3, .incoming, file(.remote)), record(4, .incoming, file(.heldUntilAccepted)),
            record(5, .incoming, file(.unavailable)), record(6, .outgoing, file(.transferring)),
        ]
        let messages = LiveMapping.messages(records, ownUsername: "selin", peerUsername: "deniz", now: now, media: [:])
        #expect(messages.map(\.mediaAvailability) == [.ready, .downloading(nil), .remote, .held, .unavailable, .ready])
    }

    @Test func aRequestedDownloadShowsItsRingAtOnce() {
        let remote = record(3, .incoming, file(.remote))
        let id = LiveMapping.messageID(remote.id)
        let requested = LiveMapping.messages(
            [remote], ownUsername: "selin", peerUsername: "deniz", now: now, media: [:],
            downloads: [id: LiveDownload(fraction: nil)])
        #expect(requested.first?.mediaAvailability == .downloading(nil))
        let running = LiveMapping.messages(
            [record(2, .incoming, file(.transferring))], ownUsername: "selin", peerUsername: "deniz", now: now, media: [:],
            downloads: [LiveMapping.messageID(Data(repeating: 2, count: 16)): LiveDownload(fraction: 0.5)])
        #expect(running.first?.mediaAvailability == .downloading(0.5))
    }

    @Test func theSentSizeComesAlongButNotForViewOnce() {
        let photo = LiveAttachment(
            kind: .image, contentType: "image/jpeg", fileName: nil, size: 120_000, durationMs: 0, thumbnail: Data([1]),
            caption: nil, waveform: nil, availability: .remote, width: 2_048, height: 1_365)
        let messages = LiveMapping.messages(
            [record(1, .incoming, photo), record(2, .incoming, photo, viewOnce: true)], ownUsername: "selin",
            peerUsername: "deniz", now: now, media: [:])
        #expect(messages[0].mediaSize == MediaDimensions(width: 2_048, height: 1_365))
        #expect(messages[0].mediaAvailability == .remote)
        #expect(messages[1].mediaSize == nil)
        #expect(messages[1].mediaAvailability == .ready, "view-once shows no file state")
    }
}

@Suite("Media · presenters")
@MainActor
struct MediaPresenterTests {
    private func makeModel(_ chat: ChatID) -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: chat, messenger: service), service)
    }

    @Test func aPhotoOpensTheViewerNotTheViewOnceViewer() throws {
        let (model, _) = makeModel("deniz")
        model.openMedia(try #require(model.message("d2")))
        let viewer = try #require(model.viewer)
        #expect(viewer.kind == .photo)
        #expect(viewer.caption == "Şu yeni yer, Moda’da")
        #expect(viewer.sender == "deniz")
    }

    @Test func aVideoOpensThePlayer() throws {
        let (model, _) = makeModel("mert_k")
        model.openMedia(try #require(model.message("m2")))
        let viewer = try #require(model.viewer)
        #expect(viewer.kind == .video)
        #expect(viewer.duration == 18)
    }

    @Test func viewOnceStillOpensOnlyIn5_6() async throws {
        let (model, _) = makeModel("mert_k")
        let viewOnce = try #require(model.message("m6"))
        model.openMedia(viewOnce)
        #expect(model.viewer == nil, "a tap on view-once never reaches the ordinary viewer")
        model.openViewOnce(viewOnce)
        for _ in 0..<50 where model.viewer == nil { try await Task.sleep(for: .milliseconds(20)) }
        #expect(model.viewer?.kind == .viewOnce)
        model.closeMedia()
        #expect(model.viewer?.kind == .viewOnce, "backgrounding does not close (and so delete) view-once media")
    }

    @Test func ordinaryMediaClosesWithTheAppOrWhenItsMessageGoes() async throws {
        let (model, service) = makeModel("deniz")
        model.openMedia(try #require(model.message("d2")))
        #expect(!model.presentedMediaIsGone)
        await service.deleteForMe("d2", in: "deniz")
        #expect(model.presentedMediaIsGone)
        model.closeMedia()
        #expect(model.viewer == nil)
    }

    @Test func aFileStillOnTheRelayDownloadsAndThenOpens() async throws {
        let (model, service) = makeModel("mert_k")
        let id = service.addIncomingFile(in: "mert_k", availability: .remote)
        service.simulateDownload(of: id, in: "mert_k", duration: .milliseconds(120))
        #expect(model.message(id)?.mediaAvailability.isDownloading == true)
        model.openMedia(try #require(model.message(id)))
        #expect(model.viewer == nil)
        #expect(model.pendingFileOpen == id)
        for _ in 0..<50 where !model.pendingFileIsReady { try await Task.sleep(for: .milliseconds(20)) }
        #expect(model.pendingFileIsReady)
        model.closeMedia()
        #expect(model.pendingFileOpen == nil)
    }

    @Test func fixturesServeRealBytes() async throws {
        let (_, service) = makeModel("mert_k")
        let pdf = try #require(await service.mediaPayload("m1", in: "mert_k"))
        #expect(pdf.contentType == "application/pdf")
        #expect(pdf.fileName == "kira-sozlesmesi.pdf")
        #expect(pdf.data.starts(with: Data("%PDF".utf8)))
        let photo = try #require(await service.mediaPayload("d2", in: "deniz"))
        let image = try #require(UIImage(data: photo.data))
        #expect(abs(image.size.width / image.size.height - 1.5) < 0.01)
        service.setMediaAvailability(.unavailable, of: "m2", in: "mert_k")
        #expect(await service.mediaPayload("m2", in: "mert_k") == nil)
    }

    @Test func aHeldPayloadWaitsUntilCancelled() async {
        let (_, service) = makeModel("mert_k")
        service.holdsMediaPayloads = true
        let task = Task { await service.mediaPayload("m2", in: "mert_k") }
        try? await Task.sleep(for: .milliseconds(100))
        task.cancel()
        #expect(await task.value == nil)
    }
}
