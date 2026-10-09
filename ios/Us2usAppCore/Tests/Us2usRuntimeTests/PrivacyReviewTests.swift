// us2us — AGPL-3.0-only
//
// Privacy review (adversarial): each test states what PROTOCOL.md promises
// and is expected to FAIL against the current runtime until the issue named
// in its comment is fixed. No production code is changed by this file.

import AVFoundation
import CoreVideo
import Foundation
import ImageIO
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

private func liveLocationItems(_ items: [MessageItem]) -> [MessageItem] {
    items.filter { if case .liveLocation = $0.body { return true } else { return false } }
}

@Suite("Privacy review: disappearing content")
struct PrivacyReviewDisappearingTests {
    /// §8.1 / §9: in a conversation with disappearing messages, a live
    /// location must disappear like a static location. `startLiveLocation`
    /// builds its `DataMessage` without `expireTimerSeconds`, so the bubble
    /// with the sharer's last precise position stays on both phones forever.
    @Test func liveLocationFollowsTheDisappearingTimer() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateConversationSettings(peer, ghost: nil, defaultExpireTimerSeconds: 300)

        let staticId = try await fixture.engine.send(
            .location(LocationInfo(latitude: 41.0082, longitude: 28.9784, accuracyMeters: 5)), to: peer)
        let session = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0082, longitude: 28.9784, accuracy: 5, duration: 60)
        try await fixture.engine.updateLiveLocation(session, peer: peer, latitude: 41.0101, longitude: 28.9802, accuracy: 5)
        try await fixture.engine.stopLiveLocation(session, peer: peer)
        try await eventually("live start sent") {
            fixture.backend.sent(to: peer).contains {
                if case .liveLocation(let live)? = $0.data?.payload { return !live.stopped && !$0.ephemeral }
                return false
            }
        }
        let start = try #require(
            fixture.backend.sent(to: peer).first {
                if case .liveLocation? = $0.data?.payload { return !$0.ephemeral }
                return false
            }?.data)
        // The contact's phone gets no timer, so it keeps the position forever too.
        #expect(start.expireTimerSeconds == 300, "live location sent without the conversation's disappearing timer")

        fixture.clock.advance(by: 301)
        await fixture.engine.runDueWipes()
        let items = try await fixture.messages(peer)
        #expect(!items.contains { $0.messageId == staticId }, "control: the static location disappeared")
        #expect(liveLocationItems(items).isEmpty, "the live-location bubble (last precise position) never disappears")
        await fixture.engine.stop()
    }

    /// §9 "never displayed after its deadline": a reply's `Quote.preview_text`
    /// copies the quoted text. The engine takes the preview verbatim from the
    /// caller and shows it from the reply's own body, so the text of a
    /// disappearing message keeps being displayed (and stored, and sent to
    /// the contact) after the message itself disappeared.
    @Test func aQuoteDoesNotKeepADisappearedMessageAlive() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateConversationSettings(peer, ghost: nil, defaultExpireTimerSeconds: 60)

        let secret = "kapı kodu 4417"
        let secretId = RandomId.make()
        try await fixture.receive(.textMessage(secret, id: secretId, expire: 60), from: peer)
        try await fixture.engine.markDisplayed(peer)  // its 60 s start now
        fixture.clock.advance(by: 50)
        _ = try await fixture.engine.send(
            .text("tamam"), to: peer, quote: QuoteInfo(targetMessageId: secretId, previewText: secret))
        fixture.clock.advance(by: 11)  // the quoted message's deadline has passed
        await fixture.engine.runDueWipes()

        let items = try await fixture.messages(peer)
        #expect(!items.contains { $0.messageId == secretId }, "control: the original disappeared")
        let stillShown = items.filter { $0.quote?.previewText.contains("4417") == true }
        #expect(stillShown.isEmpty, "the disappeared text is still displayed inside the reply's quote")
        await fixture.engine.stop()
    }

    /// §8.1: "Attachments and thumbnails are deleted with it." The decrypted
    /// copy that `exportMedia` writes for display stays in the plaintext
    /// staging directory after the message disappeared (until the next lock).
    @Test func aDisappearedPhotoLeavesNoPlaintextCopy() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let mediaId = RandomId.make()
        let id = RandomId.make()
        fixture.backend.setDownload(Data("PRIVATE-PHOTO-BYTES".utf8), for: mediaId)
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = mediaId
        attachment.deleteToken = Data(repeating: 1, count: 32)
        attachment.key = Data(repeating: 2, count: 64)
        attachment.digest = Data(repeating: 3, count: 32)
        attachment.size = 19
        attachment.contentType = "image/jpeg"
        try await fixture.receive(.data(.attachment(attachment), id: id, expire: 60), from: peer)
        try await eventually("downloaded") {
            guard case .attachment(let shown)? = try await fixture.messages(peer).first(where: { $0.messageId == id })?.body
            else { return false }
            return shown.availability == .local
        }
        try await fixture.engine.markDisplayed(peer)
        let shownFile = try await fixture.engine.exportMedia(id, peer: peer)
        #expect(FileManager.default.fileExists(atPath: shownFile.path))

        fixture.clock.advance(by: 61)
        await fixture.engine.runDueWipes()
        #expect(!(try await fixture.messages(peer)).contains { $0.messageId == id }, "control: the message disappeared")
        #expect(
            !FileManager.default.fileExists(atPath: shownFile.path),
            "the decrypted photo outlives its disappearing deadline in staging")
        await fixture.engine.stop()
    }
}

@Suite("Privacy review: view-once and unsend")
struct PrivacyReviewViewOnceTests {
    /// View-once means the recipient can see the photo exactly once. The
    /// composer's inline JPEG thumbnail (and caption) are sent along and
    /// shown in the bubble before it is opened, and they stay as long as it
    /// is never opened.
    @Test func viewOnceMediaIsSentWithoutAPreview() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let file = fixture.directory.directories.stagingFile(extension: "jpg")
        try Data("jpeg bytes".utf8).write(to: file)
        let preview = Data("THUMBNAIL-OF-THE-PRIVATE-PHOTO".utf8)
        let media = PreparedMedia(
            file: file, kind: .image, contentType: "image/jpeg", size: 10, width: 4, height: 3, thumbnail: preview)
        let id = try await fixture.engine.send(.media(media), to: peer, viewOnce: true)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data?.messageID == id } }
        let sent = try #require(fixture.backend.sent(to: peer).first { $0.data?.messageID == id }?.data)
        #expect(sent.viewOnce)
        guard case .attachment(let attachment) = sent.payload else {
            Issue.record("not an attachment")
            return
        }
        #expect(attachment.thumbnail.isEmpty, "a view-once photo is sent with a viewable inline thumbnail")
        await fixture.engine.stop()
    }

    @Test func aReceivedViewOnceShowsNoPreviewBeforeOpening() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = RandomId.make()
        attachment.deleteToken = Data(repeating: 1, count: 32)
        attachment.key = Data(repeating: 2, count: 64)
        attachment.digest = Data(repeating: 3, count: 32)
        attachment.size = 5
        attachment.contentType = "image/jpeg"
        attachment.thumbnail = Data("THUMBNAIL-OF-THE-PRIVATE-PHOTO".utf8)
        let id = RandomId.make()
        try await fixture.receive(.data(.attachment(attachment), id: id, viewOnce: true), from: peer)
        let item = try #require(try await fixture.messages(peer).first { $0.messageId == id })
        guard case .attachment(let shown) = item.body else {
            Issue.record("not an attachment")
            return
        }
        #expect(item.viewOnce)
        #expect(shown.thumbnail == nil, "an unopened view-once photo exposes its thumbnail to the UI")
        await fixture.engine.stop()
    }

    /// "Exactly once": the item is consumed only in `closeViewOnce`. If the
    /// process dies while the viewer is open (force-quit from the app
    /// switcher during the grace period, a crash, jetsam), nothing was
    /// recorded, so it opens again after the next unlock — as often as the
    /// recipient repeats this — and the sender never gets VIEWED.
    @Test func aViewOnceCannotBeReopenedAfterTheAppDies() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = RandomId.make()
        try await fixture.receive(.attachmentMessage(mediaId: RandomId.make(), id: id, viewOnce: true), from: peer)
        try await eventually("downloaded") {
            guard case .attachment(let shown)? = try await fixture.messages(peer).first(where: { $0.messageId == id })?.body
            else { return false }
            return shown.availability == .local
        }
        _ = try await fixture.engine.openViewOnce(id, peer: peer)

        // The process dies with the viewer open; the next launch unlocks the same files.
        await fixture.engine.stop()
        fixture.storage.close()
        let reopened = try fixture.key.withData { dek in
            try LocalStorage.open(layout: fixture.directory.directories.storage, dek: dek, now: fixture.clock.now())
        }.storage
        defer { reopened.close() }
        let engine = try MessagingEngine(
            storage: reopened, backend: fixture.backend, stateDirectory: fixture.directory.directories.runtime,
            key: fixture.key, clock: fixture.clock, configuration: .fast)
        await engine.start()
        await #expect(throws: EngineError.invalidMessage, "the view-once item opens a second time") {
            _ = try await engine.openViewOnce(id, peer: peer)
        }
        await engine.stop()
    }

    /// Delete for everyone (§8.5) of a message that is still in the outbox
    /// (no network yet) must not send it. Today the original stays queued in
    /// front of the `Delete`, so its content reaches the relay and the
    /// recipient's phone anyway once the network is back.
    @Test func unsendingAQueuedMessageNeverSendsIt() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        fixture.backend.failNextSend(to: peer, with: RelayError.network(.notConnectedToInternet))
        let secret = "bunu göndermemeliydim"
        let id = try await fixture.engine.send(.text(secret), to: peer)
        try await eventually("first attempt failed") { (try fixture.outbox().first?.attempts ?? 0) >= 1 }
        try await fixture.engine.deleteForEveryone(id, peer: peer)

        try await eventually("network back, queue drained") {
            fixture.clock.advance(by: 30)
            return (try fixture.outbox()).isEmpty
        }
        let sentTexts = fixture.backend.sent(to: peer).compactMap { sent -> String? in
            if case .text(let text)? = sent.data?.payload { return text.body }
            return nil
        }
        #expect(!sentTexts.contains(secret), "the unsent message was delivered after delete-for-everyone")
        await fixture.engine.stop()
    }
}

@Suite("Privacy review: plaintext at rest")
@MainActor
struct PrivacyReviewAtRestTests {
    /// ProtectedFiles documents staging as "emptied on every lock and at every
    /// launch", but `AppRuntime.init` purges only `<root>/staging`. Decrypted
    /// view-once, displayed and downloading media live in Us2usStorage's own
    /// `<root>/storage/staging`, which is cleared only by `LocalStorage.open`
    /// / `close`. After a crash or jetsam kill the plaintext stays on disk
    /// behind iOS Data Protection only (not the secret code) until the next
    /// successful unlock — indefinitely if the user never unlocks again.
    @Test func launchPurgesEveryPlaintextStagingFile() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        let mediaId = RandomId.make()
        let id = RandomId.make()
        fixture.backend.setDownload(Data("PRIVATE-VIEW-ONCE-PHOTO".utf8), for: mediaId)
        fixture.push(.attachmentMessage(mediaId: mediaId, id: id, viewOnce: true), from: peer)
        let engine = try #require(fixture.runtime.session?.engine)
        try await eventually("downloaded") {
            guard case .attachment(let shown)? = try await engine.messages(of: peer).first(where: { $0.messageId == id })?.body
            else { return false }
            return shown.availability == .local
        }
        let onScreen = try await fixture.runtime.openViewOnce(id, peer: peer)
        #expect(try Data(contentsOf: onScreen) == Data("PRIVATE-VIEW-ONCE-PHOTO".utf8))

        // The process dies while the photo is on screen: no lock, no close.
        let orphanedStorage = try #require(fixture.runtime.session?.storage)
        defer { orphanedStorage.close() }
        fixture.relaunch()
        #expect(fixture.runtime.lockState == .locked)
        #expect(
            !FileManager.default.fileExists(atPath: onScreen.path),
            "decrypted view-once plaintext survives a relaunch while the app sits locked")
    }
}

@Suite("Privacy review: key lifetime")
@MainActor
struct PrivacyReviewKeyLifetimeTests {
    /// §5.4: the DEK "MUST be erased from memory" at the end of the grace
    /// period; the runtime keeps it (and the store open) only while live
    /// location is shared. But `endLiveLocationOnlySessionIfIdle` is called
    /// only from the tick task, which `lockForBackground` cancelled before
    /// the session entered live-location-only mode. So once sharing stops or
    /// expires in the background, nothing ever closes the store or zeroes
    /// the key: the suspended process keeps the DEK in memory indefinitely.
    @Test func theKeyIsErasedWhenBackgroundSharingEnds() async throws {
        let fixture = try RuntimeFixture()
        try await fixture.onboard()
        try await fixture.connect()
        let peer = try fixture.acceptedContact()
        let sharing = try await fixture.runtime.startLiveLocation(
            to: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 600)
        let key = try #require(fixture.runtime.session?.key)
        let stream = try #require(fixture.backend.latestStream)

        fixture.runtime.scenePhaseChanged(.background)
        try await eventually("grace over, live location continues") {
            fixture.clock.advance(by: 1)
            return stream.isClosed
        }
        try await fixture.runtime.updateLiveLocation(sharing, peer: peer, latitude: 41.001, longitude: 29.001, accuracy: 5)
        try await fixture.runtime.stopLiveLocation(sharing, peer: peer)

        // Sharing is over and the app stays in the background.
        try await eventually(timeout: 3, "DEK zeroed after background sharing ended") {
            fixture.clock.advance(by: 60)
            return key.isWiped
        }
    }
}

@Suite("Privacy review: media metadata (§7)")
struct PrivacyReviewVideoMetadataTests {
    /// Writes a short H.264 movie with the metadata an iPhone camera adds.
    static func makeVideo(at url: URL, metadata: [AVMetadataItem]) async throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        writer.metadata = metadata
        let input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 320, AVVideoHeightKey: 240])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: 320, kCVPixelBufferHeightKey as String: 240,
            ])
        writer.add(input)
        #expect(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for frame in 0..<15 {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 5_000_000) }
            var buffer: CVPixelBuffer?
            CVPixelBufferCreate(nil, 320, 240, kCVPixelFormatType_32BGRA, nil, &buffer)
            let pixels = try #require(buffer)
            CVPixelBufferLockBaseAddress(pixels, [])
            memset(CVPixelBufferGetBaseAddress(pixels), Int32(frame * 10), CVPixelBufferGetDataSize(pixels))
            CVPixelBufferUnlockBaseAddress(pixels, [])
            adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 15))
        }
        input.markAsFinished()
        await writer.finishWriting()
        #expect(writer.status == .completed)
    }

    static func item(_ identifier: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value as NSString
        item.dataType = kCMMetadataBaseDataType_UTF8 as String
        return item
    }

    /// §7 "EXIF/GPS metadata MUST be stripped from images and videos". A
    /// photo picked through the file / document path (contentType
    /// `image/jpeg`) is copied byte for byte, GPS position included.
    @Test func aPhotoSentAsAFileLosesItsGPS() throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        let source = temporary.url.appendingPathComponent("IMG_0001.JPG")
        try makeJPEGWithMetadata(width: 64, height: 64, at: source)
        #expect(MediaPreparation.metadataKeys(of: try Data(contentsOf: source)).contains(kCGImagePropertyGPSDictionary as String))
        let prepared = try MediaPreparation.prepareFile(
            at: source, directories: directories, fileName: "IMG_0001.JPG", contentType: "image/jpeg")
        let sent = try Data(contentsOf: prepared.file)
        #expect(
            !MediaPreparation.metadataKeys(of: sent).contains(kCGImagePropertyGPSDictionary as String),
            "an image sent as a file keeps its GPS position")
    }

    @Test func videosLoseLocationDeviceAndCaptureTime() async throws {
        let temporary = try TemporaryDirectory()
        let directories = temporary.directories
        try directories.prepare()
        let source = temporary.url.appendingPathComponent("camera.mov")
        try await Self.makeVideo(
            at: source,
            metadata: [
                Self.item(.quickTimeMetadataLocationISO6709, "+41.0082+028.9784+012.000/"),
                Self.item(.quickTimeMetadataMake, "PhoneMaker"),
                Self.item(.quickTimeMetadataModel, "Model X Pro"),
                Self.item(.quickTimeMetadataSoftware, "OS 26.1.7"),
                Self.item(.quickTimeMetadataCreationDate, "2026-03-04T05:06:07+0300"),
            ])
        let original = try Data(contentsOf: source)
        #expect(original.range(of: Data("PhoneMaker".utf8)) != nil, "control: the source carries the metadata")

        let prepared = try await MediaPreparation.prepareVideo(at: source, directories: directories)
        let output = try Data(contentsOf: prepared.file)
        for marker in ["+41.0082", "PhoneMaker", "Model X Pro", "OS 26.1.7", "2026-03-04T05:06:07"] {
            #expect(output.range(of: Data(marker.utf8)) == nil, "video metadata survives preparation: \(marker)")
        }
        let asset = AVURLAsset(url: prepared.file)
        let remaining = try await asset.load(.metadata).compactMap(\.identifier?.rawValue)
        #expect(remaining.isEmpty, "metadata items left in the prepared video: \(remaining)")
    }
}
