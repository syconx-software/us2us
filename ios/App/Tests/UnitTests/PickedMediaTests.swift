import Foundation
import ImageIO
import PhotosUI
import Testing
import Us2usKit
import Us2usRuntime

@testable import us2us

// 5.2 · several photos and videos picked at once in “+” › Fotoğraf ve video:
// up to ten, each sent as a message of its own in the order it was selected,
// every bubble at once, prepared one at a time; the composer's text goes with
// the first photo; one item failing never stops the others; view-once stays
// a single item.

@MainActor
private func eventually(_ condition: () -> Bool) async {
    for _ in 0..<300 where !condition() {
        try? await Task.sleep(for: .milliseconds(10))
    }
}

private func photo(_ artwork: DesignArtwork = .nightPier) -> PickedMediaItem {
    PickedMediaItem(kind: .photo, source: .artwork(artwork))
}

private func video() -> PickedMediaItem {
    PickedMediaItem(kind: .video, source: .artwork(.sunsetVideo))
}

private func pickedArtwork(of item: PickedMediaItem) -> DesignArtwork? {
    if case .artwork(let artwork) = item.source { return artwork }
    return nil
}

private func caption(of message: Message) -> String? {
    if case .photo(_, let caption) = message.content { return caption }
    return nil
}

private func shownArtwork(of message: Message) -> DesignArtwork? {
    switch message.content {
    case .photo(.designArtwork(let artwork), _): artwork
    case .video(.designArtwork(let artwork), _, _): artwork
    default: nil
    }
}

// MARK: - The plan

@Suite("Media · several photos at once (the pick)")
@MainActor
struct PickedMediaPlanTests {
    @Test func thePickerTakesTenInTheOrderTheyAreSelected() {
        let configuration = SystemPickers.mediaConfiguration()
        #expect(configuration.selectionLimit == 10)
        #expect(PickedMediaPlan.selectionLimit == 10)
        #expect(configuration.selection == .ordered, "numbered, and handed back in that order")
        #expect(configuration.preferredAssetRepresentationMode == .current)
    }

    @Test func theOrderIsKeptAndTheLimitIsTen() {
        let artworks: [DesignArtwork] = (0..<12).map { $0 % 3 == 1 ? DesignArtwork.sunsetVideo : DesignArtwork.nightPier }
        let items = artworks.enumerated().map { index, artwork in
            index % 4 == 3 ? PickedMediaItem(kind: .video, source: .artwork(artwork)) : photo(artwork)
        }
        let messages = PickedMediaPlan.messages(for: items, caption: nil)
        #expect(messages.count == 10)
        #expect(messages.map { pickedArtwork(of: $0.item) } == artworks.prefix(10).map { Optional($0) })
        #expect(messages.map(\.item.kind) == items.prefix(10).map(\.kind))
        #expect(PickedMediaPlan.messages(for: [], caption: "Merhaba").isEmpty)
    }

    @Test func theCaptionGoesWithTheFirstPhotoOnly() {
        let mixed = PickedMediaPlan.messages(for: [video(), photo(), photo()], caption: "  Moda'dan  ")
        #expect(mixed.map(\.caption) == [nil, "Moda'dan", nil], "a video has no caption: the first photo takes it")

        let photos = PickedMediaPlan.messages(for: [photo(), photo(), photo()], caption: "Dünkü yürüyüş")
        #expect(photos.map(\.caption) == ["Dünkü yürüyüş", nil, nil])

        #expect(PickedMediaPlan.messages(for: [photo(), photo()], caption: nil).allSatisfy { $0.caption == nil })
        #expect(PickedMediaPlan.messages(for: [photo(), photo()], caption: " \n ").allSatisfy { $0.caption == nil })
        #expect(
            PickedMediaPlan.messages(for: [video(), video()], caption: "Yalnız video").allSatisfy { $0.caption == nil })

        // Longer than a caption may be (2 KiB): it stays in the composer.
        let long = String(repeating: "ğ", count: ContentLimits.maxCaptionBytes / 2 + 1)
        #expect(PickedMediaPlan.usableCaption(long) == nil)
        #expect(PickedMediaPlan.messages(for: [photo()], caption: long).first?.caption == nil)
        let fits = String(repeating: "a", count: ContentLimits.maxCaptionBytes)
        #expect(PickedMediaPlan.usableCaption(fits) == fits)
    }

    @Test func viewOnceStaysASingleItemWithoutACaption() {
        #expect(SystemPickers.mediaConfiguration(viewOnce: true).selectionLimit == 1)
        #expect(PickedMediaPlan.limit(viewOnce: true) == 1)
        let messages = PickedMediaPlan.messages(
            for: [photo(.sunsetVideo), photo(), video()], caption: "Görünmemeli", viewOnce: true)
        #expect(messages.count == 1)
        #expect(messages.first.flatMap { pickedArtwork(of: $0.item) } == .sunsetVideo)
        #expect(messages.first?.caption == nil, "nothing of it may be visible before it is opened")
    }
}

// MARK: - The live pipeline

/// Holds `prepare` until it is opened.
@MainActor
private final class Gate {
    private var isOpen: Bool
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(open: Bool) {
        isOpen = open
    }

    func wait() async {
        guard !isOpen else { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        let waiting = waiters
        waiters = []
        for waiter in waiting { waiter.resume() }
    }
}

private struct PreparingFailed: Error {}

/// Stand-ins for the runtime's steps, recording what was asked of them and
/// how many items were being prepared at the same time.
@MainActor
private final class Probe {
    let gate: Gate
    /// Picked copies whose preparing fails.
    var failing: Set<URL> = []
    /// Written as the prepared file (a photo's bytes as sent).
    var preparedBytes: Data?
    private(set) var prepared: [(url: URL, kind: PickedMediaItem.Kind, caption: String?)] = []
    private(set) var sent: [Data] = []
    private(set) var discardedSources: [URL] = []
    private(set) var inFlight = 0
    private(set) var mostInFlight = 0
    let folder: URL

    init(open: Bool = true) {
        gate = Gate(open: open)
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(
            "picked-media-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    func copy(_ name: String) -> URL {
        folder.appendingPathComponent(name)
    }

    func makeMedia(now: @escaping () -> Date = { Date(timeIntervalSince1970: 1_791_000_000) }) -> OutgoingMedia {
        let steps = OutgoingMedia.Steps(
            preview: { _, kind in
                OutgoingMediaPreview(
                    poster: Data([0xFF, 0xD8, kind == .photo ? 1 : 2]), duration: kind == .photo ? 0 : 18,
                    size: MediaDimensions(width: 1_600, height: 1_200))
            },
            prepare: { url, kind, caption in
                self.prepared.append((url, kind, caption))
                self.inFlight += 1
                self.mostInFlight = max(self.mostInFlight, self.inFlight)
                defer { self.inFlight -= 1 }
                await self.gate.wait()
                if self.failing.contains(url) { throw PreparingFailed() }
                let file = url.appendingPathExtension("prepared")
                if let bytes = self.preparedBytes { try bytes.write(to: file) }
                return PreparedMedia(
                    file: file, kind: kind == .photo ? .image : .video,
                    contentType: kind == .photo ? "image/jpeg" : "video/mp4", size: 1_000)
            },
            send: { _, id, _ in self.sent.append(id) },
            discardSource: { url in self.discardedSources.append(url) },
            discardPrepared: { url in _ = try? FileManager.default.removeItem(at: url) })
        return OutgoingMedia(steps: steps, now: now)
    }
}

@Suite("Media · several photos at once (sending)")
@MainActor
struct PickedMediaSendingTests {
    /// Begins the pick's items as the live service does (plan → bubbles in order).
    private func begin(_ items: [PickedMediaItem], caption: String?, on media: OutgoingMedia) -> [MessageID] {
        PickedMediaPlan.messages(for: items, caption: caption).map { message in
            media.begin(message.item.kind, caption: message.caption, in: "deniz", disappearing: .off)
        }
    }

    @Test func everyBubbleIsThereAtOnceAndTheyAreSentOneAtATimeInOrder() async throws {
        let probe = Probe(open: false)
        let media = probe.makeMedia()
        let ids = begin([photo(), video(), photo(), photo()], caption: "Moda'dan", on: media)
        let copies = ["1.heic", "2.mov", "3.heic", "4.jpg"].map(probe.copy)

        // At once, in the order selected: outgoing, sending, being prepared.
        let shown = OutgoingMedia.merge(media.items(in: "deniz"), into: [])
        #expect(shown.map(\.id) == ids)
        #expect(shown.allSatisfy { $0.isOutgoing && $0.status == .sending && $0.mediaAvailability == .preparing })
        #expect(shown.map { caption(of: $0) } == ["Moda'dan", nil, nil, nil])
        #expect(Set(shown.map(\.sentAt)).count == 4, "each its own place, even with a clock that stands still")

        // The copies come back in any order: nothing starts before the first one's.
        for index in [3, 2, 1] { media.provide(copies[index], for: ids[index]) }
        try await Task.sleep(for: .milliseconds(60))
        #expect(probe.prepared.isEmpty, "the first item picked goes first")
        media.provide(copies[0], for: ids[0])
        await eventually { !probe.prepared.isEmpty }
        try await Task.sleep(for: .milliseconds(60))
        #expect(probe.prepared.map { $0.url } == [copies[0]], "one at a time")

        probe.gate.open()
        await eventually { probe.sent.count == 4 }
        #expect(probe.sent.map(LiveMapping.messageID) == ids, "stored in the order selected")
        #expect(probe.prepared.map { $0.url } == copies)
        #expect(probe.prepared.map { $0.kind } == [.photo, .video, .photo, .photo])
        #expect(
            probe.prepared.map { $0.caption } == ["Moda'dan", nil, nil, nil], "the caption on the first photo only")
        #expect(probe.mostInFlight == 1, "never two prepared at the same time")
        #expect(Set(probe.discardedSources) == Set(copies), "each copy goes once its item is stored")
        #expect(ids.allSatisfy { media.item($0)?.phase == .sent })
    }

    @Test func aFailingItemDoesNotStopTheRest() async throws {
        let probe = Probe()
        let media = probe.makeMedia()
        let ids = begin([photo(), photo(), photo(), photo()], caption: nil, on: media)
        let copies = ["1.jpg", "2.jpg", "3.jpg", "4.jpg"].map(probe.copy)
        probe.failing = [copies[1]]
        for (id, copy) in zip(ids, copies) { media.provide(copy, for: id) }

        await eventually { probe.sent.count == 3 && media.item(ids[1])?.phase == .failed }
        #expect(probe.sent.map(LiveMapping.messageID) == [ids[0], ids[2], ids[3]])
        let failed = try #require(media.item(ids[1])?.message)
        #expect(failed.status == .failed)
        #expect(failed.mediaAvailability == .preparationFailed)
        #expect(failed.failedAt != nil)
        #expect(
            ConversationMessageRow.detail(for: failed, locale: Locale(identifier: "tr")) == "Fotoğraf hazırlanamadı")
        #expect(
            ConversationMessageRow.detail(for: failed, locale: Locale(identifier: "en")) == "Photo couldn't be prepared"
        )
        #expect(!probe.discardedSources.contains(copies[1]), "its copy stays for “Tekrar gönder”")

        // “Tekrar gönder” prepares it again from the same copy.
        probe.failing = []
        #expect(media.resend(ids[1]))
        await eventually { probe.sent.count == 4 }
        #expect(probe.sent.map(LiveMapping.messageID) == [ids[0], ids[2], ids[3], ids[1]])
        #expect(probe.prepared.map { $0.url } == [copies[0], copies[1], copies[2], copies[3], copies[1]])
        #expect(probe.mostInFlight == 1)
    }

    @Test func anItemThePickerCouldNotHandOverDoesNotHoldUpTheRest() async {
        let probe = Probe()
        let media = probe.makeMedia()
        let ids = begin([photo(), photo(), photo()], caption: nil, on: media)
        media.provide(probe.copy("3.jpg"), for: ids[2])
        media.provide(nil, for: ids[1])
        #expect(media.item(ids[1]) == nil, "nothing to send again: its bubble goes")
        media.provide(probe.copy("1.jpg"), for: ids[0])
        await eventually { probe.sent.count == 2 }
        #expect(probe.sent.map(LiveMapping.messageID) == [ids[0], ids[2]])
    }

    @Test func removingAWaitingItemSkipsIt() async {
        let probe = Probe(open: false)
        let media = probe.makeMedia()
        let ids = begin([photo(), photo(), photo()], caption: nil, on: media)
        let copies = ["1.jpg", "2.jpg", "3.jpg"].map(probe.copy)
        for (id, copy) in zip(ids, copies) { media.provide(copy, for: id) }
        await eventually { !probe.prepared.isEmpty }

        #expect(media.remove(ids[1]))
        #expect(probe.discardedSources == [copies[1]])
        probe.gate.open()
        await eventually { probe.sent.count == 2 }
        #expect(probe.sent.map(LiveMapping.messageID) == [ids[0], ids[2]])
        #expect(!probe.prepared.map { $0.url }.contains(copies[1]))
    }

    @Test func aLaterItemNeverShowsAboveOneStoredBeforeIt() async throws {
        let probe = Probe()
        let start = Date(timeIntervalSince1970: 1_791_000_000)
        let media = probe.makeMedia(now: { start })
        let ids = begin([photo(), photo()], caption: nil, on: media)
        probe.failing = [probe.copy("2.jpg")]
        media.provide(probe.copy("1.jpg"), for: ids[0])
        media.provide(probe.copy("2.jpg"), for: ids[1])
        await eventually { media.item(ids[1])?.phase == .failed }
        #expect(media.landed[ids[0]] == start)

        // The first is stored now, later than the second was picked (a
        // stored message sits where it was stored).
        let earlier = Message(
            id: "a", direction: .incoming, sentAt: start.addingTimeInterval(-60), content: .text("bir"))
        let stored = Message(
            id: ids[0], direction: .outgoing, sentAt: start.addingTimeInterval(5), content: .text("x"), status: .sending
        )
        let pending = media.items(in: "deniz")
        #expect(
            OutgoingMedia.merge(pending, into: [earlier, stored], landed: media.landed).map(\.id) == [
                "a", ids[0], ids[1],
            ])
        #expect(
            OutgoingMedia.merge(pending, into: [earlier, stored]).map(\.id) == ["a", ids[1], ids[0]],
            "without knowing when the first was picked, the second would jump above it")

        // Nothing waits any more: forgotten.
        #expect(media.remove(ids[1]))
        media.settle(shown: [earlier, stored])
        await eventually { media.items.isEmpty }
        #expect(media.landed.isEmpty)
    }

    @Test func aSentPhotoKeepsThePhotoAsItWasSent() async throws {
        let probe = Probe()
        let bytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 7, 7, 7])
        probe.preparedBytes = bytes
        let media = probe.makeMedia()
        var handedOver: [OutgoingMediaItem] = []
        media.onSent = { handedOver.append($0) }
        let id = media.send(probe.copy("1.heic"), as: .photo, caption: "Moda", in: "deniz", disappearing: .off)

        await eventually { media.item(id)?.phase == .sent }
        let item = try #require(media.item(id))
        #expect(item.sentPhoto == bytes)
        #expect(item.keptImage == bytes, "the stored bubble and the viewer get the photo as sent, not its preview")
        guard case .photo(.memory(let shown), let caption) = item.message.content else {
            Issue.record("not a photo bubble")
            return
        }
        #expect(shown == bytes)
        #expect(caption == "Moda")
        #expect(handedOver.map(\.keptImage) == [bytes])

        // A video keeps its poster; a video carries no caption.
        let clip = media.send(probe.copy("2.mov"), as: .video, caption: "yok", in: "deniz", disappearing: .off)
        await eventually { media.item(clip)?.phase == .sent }
        #expect(media.item(clip)?.caption == nil)
        #expect(media.item(clip)?.keptImage == media.item(clip)?.poster)
    }

    @Test func aPhotoBeingPreparedSaysSo() {
        let message = OutgoingMediaItem(
            messageData: Data(repeating: 4, count: 16), chat: "deniz", sentAt: Date(), disappearing: .off, kind: .photo
        ).message
        guard case .photo = message.content else {
            Issue.record("not a photo bubble")
            return
        }
        #expect(message.isPendingMedia)
        #expect(
            ConversationMessageRow.detail(for: message, locale: Locale(identifier: "tr")) == "Fotoğraf hazırlanıyor")
        #expect(ConversationMessageRow.detail(for: message, locale: Locale(identifier: "en")) == "Preparing the photo")
    }

    /// The real reader: the photo's size from its properties, a preview at
    /// the bubble's size (never the full photo).
    @Test func thePreviewIsReadAtTheBubblesSize() async throws {
        let jpeg = try #require(FixtureMedia.photoJPEG(.nightPier))
        let probe = Probe()
        let url = probe.copy("gece.jpg")
        try jpeg.write(to: url)

        let preview = try #require(await PhotoPreviewReader.read(url))
        #expect(preview.size == MediaDimensions(width: 1_536, height: 1_024))
        let poster = try #require(preview.poster)
        let source = try #require(CGImageSourceCreateWithData(poster as CFData, nil))
        let properties = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        #expect(max(width, height) == PhotoPreviewReader.previewEdge)
        #expect(await PhotoPreviewReader.read(probe.copy("yok.jpg")) == nil)
    }
}

// MARK: - The conversation (fixtures)

@Suite("Media · several photos at once (conversation)")
@MainActor
struct PickedMediaConversationTests {
    private func makeModel(_ chat: ChatID = "mert_k") -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(
            hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: chat, messenger: service), service)
    }

    @Test func theDemoPickShowsEveryPhotoAtOnceThenSendsThemInOrder() async throws {
        let (model, service) = makeModel()
        let before = model.messages.count
        let messages = PickedMediaPlan.messages(for: DesignFixtures.pickedPhotos, caption: "Dünkü yürüyüşten")
        await service.send(pickedMedia: messages, in: "mert_k")

        let added = Array(model.messages.dropFirst(before))
        #expect(added.count == 3)
        #expect(added.map { shownArtwork(of: $0) } == [.nightPier, .sunsetVideo, .nightPier])
        #expect(added.map { caption(of: $0) } == ["Dünkü yürüyüşten", nil, nil])
        #expect(added.allSatisfy { $0.status == .sending && $0.mediaAvailability == .preparing })
        // Still being prepared: it does not open yet.
        model.openMedia(try #require(added.first))
        #expect(model.viewer == nil)

        await eventually { added.allSatisfy { model.message($0.id)?.status == .sent } }
        let sent = Array(model.messages.dropFirst(before))
        #expect(sent.map(\.id) == added.map(\.id), "the same bubbles, in the same order")
        #expect(sent.allSatisfy { $0.mediaAvailability == .ready && $0.status == .sent })
        model.openMedia(try #require(sent.first))
        #expect(model.viewer?.kind == .photo)
        #expect(model.viewer?.caption == "Dünkü yürüyüşten")
    }

    @Test func theComposersTextBecomesTheFirstPhotosCaptionAndTheFieldClears() async throws {
        let (model, _) = makeModel()
        let before = model.messages.count
        model.draft = "Moda'dan  "
        model.sendDemoAttachment(.photo)
        #expect(model.draft.isEmpty)
        await eventually { model.messages.count == before + 3 }
        #expect(model.messages.dropFirst(before).map { caption(of: $0) } == ["Moda'dan", nil, nil])
    }

    @Test func aPickWithoutAPhotoLeavesTheTextInTheComposer() async {
        let (model, _) = makeModel()
        let before = model.messages.count
        model.draft = "Sonra yazarım"
        model.send(picked: [video()])
        #expect(model.draft == "Sonra yazarım")
        await eventually { model.messages.count == before + 1 }
        guard case .video? = model.messages.last?.content else {
            Issue.record("not a video")
            return
        }
    }

    @Test func voiceOverHearsHowManyAreOnTheirWay() {
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        let five = [PickedMediaItem.Kind](repeating: .photo, count: 5)
        #expect(ConversationViewModel.sendingAnnouncement(for: five, locale: tr) == "5 fotoğraf gönderiliyor")
        #expect(ConversationViewModel.sendingAnnouncement(for: five, locale: en) == "Sending 5 photos")
        #expect(ConversationViewModel.sendingAnnouncement(for: [.photo], locale: tr) == "1 fotoğraf gönderiliyor")
        #expect(ConversationViewModel.sendingAnnouncement(for: [.photo], locale: en) == "Sending 1 photo")
        #expect(ConversationViewModel.sendingAnnouncement(for: [.video, .video], locale: tr) == "2 video gönderiliyor")
        #expect(ConversationViewModel.sendingAnnouncement(for: [.video, .video], locale: en) == "Sending 2 videos")
        #expect(
            ConversationViewModel.sendingAnnouncement(for: [.photo, .video, .photo], locale: tr)
                == "3 fotoğraf ve video gönderiliyor")
        #expect(
            ConversationViewModel.sendingAnnouncement(for: [.photo, .video, .photo], locale: en)
                == "Sending 3 photos and videos")
    }

    @Test func theNewStringsAreInTheConversationTableInBothLanguages() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/Features/Conversation/Conversation.xcstrings")
        let data = try Data(contentsOf: url)
        let catalog = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try #require(catalog["strings"] as? [String: [String: Any]])
        let keys = [
            "photo.preparing.detail", "photo.preparationFailed.detail", "a11y.media.sending.photos %lld",
            "a11y.media.sending.videos %lld", "a11y.media.sending.mixed %lld",
        ]
        for key in keys {
            let localizations = strings[key]?["localizations"] as? [String: Any]
            #expect(localizations?["tr"] != nil, "\(key) has no Turkish")
            #expect(localizations?["en"] != nil, "\(key) has no English")
        }
    }
}
