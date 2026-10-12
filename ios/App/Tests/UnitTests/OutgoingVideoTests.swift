import Foundation
import Testing
import Us2usRuntime

@testable import us2us

// 5.2 · a video sent from “+”: its bubble is there at once (“Hazırlanıyor”,
// the poster and the length as soon as they are read), it is prepared and
// stored under the bubble's own id, a failure offers “Tekrar gönder”, and
// the picked copy goes as soon as it is no longer needed (`OutgoingMedia`;
// several photos and videos at once: PickedMediaTests).

/// Holds `prepare` until it is opened.
@MainActor
private final class Gate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(open: Bool = false) {
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

private struct StepFailed: Error {}

/// Stand-ins for the runtime's steps, recording what was asked of them.
@MainActor
private final class Probe {
    let gate: Gate
    var failsPreparing = false
    var failsSending = false
    private(set) var prepared: [URL] = []
    private(set) var sent: [(id: Data, chat: ChatID)] = []
    private(set) var discardedSources: [URL] = []
    private(set) var discardedPrepared: [URL] = []
    let preview = OutgoingMediaPreview(
        poster: Data([0xFF, 0xD8, 0xFF]), duration: 18, size: MediaDimensions(width: 1_280, height: 720))

    init(open: Bool = true) {
        gate = Gate(open: open)
    }

    func makeVideos() -> OutgoingMedia {
        let preview = preview
        let steps = OutgoingMedia.Steps(
            preview: { _, _ in preview },
            prepare: { url, _, _ in
                self.prepared.append(url)
                await self.gate.wait()
                if self.failsPreparing { throw StepFailed() }
                return PreparedMedia(
                    file: url.appendingPathExtension("prepared"), kind: .video, contentType: "video/mp4",
                    size: 12_400_000, durationMs: 18_000)
            },
            send: { _, id, chat in
                if self.failsSending { throw StepFailed() }
                self.sent.append((id, chat))
            },
            discardSource: { url in self.discardedSources.append(url) },
            discardPrepared: { url in self.discardedPrepared.append(url) })
        return OutgoingMedia(steps: steps, now: { Date(timeIntervalSince1970: 1_791_000_000) })
    }
}

@MainActor
private func eventually(_ condition: () -> Bool) async {
    for _ in 0..<300 where !condition() {
        try? await Task.sleep(for: .milliseconds(10))
    }
}

private func pickedCopy() -> URL {
    FileManager.default.temporaryDirectory
        .appendingPathComponent("outgoing-\(UUID().uuidString)", isDirectory: true)
        .appendingPathComponent("klip.mov")
}

@Suite("Media · sending a video")
@MainActor
struct OutgoingVideoTests {
    @Test func theBubbleIsThereAtOnceAndTheStoredMessageTakesItsPlace() async throws {
        let probe = Probe(open: false)
        let videos = probe.makeVideos()
        var handedOver: [OutgoingMediaItem] = []
        videos.onSent = { handedOver.append($0) }
        let source = pickedCopy()

        let id = videos.send(source, as: .video, in: "deniz", disappearing: .fiveMinutes)

        // At once: an outgoing video, sending, being prepared.
        let bubble = try #require(videos.item(id)?.message)
        #expect(bubble.isOutgoing)
        #expect(bubble.status == .sending)
        #expect(bubble.mediaAvailability == .preparing)
        #expect(bubble.isPendingMedia)
        #expect(bubble.disappearing == .fiveMinutes)
        #expect(LiveMapping.messageData(id)?.count == 16, "an id the runtime can store the message under")
        // The poster and the length as soon as they are read.
        await eventually { videos.item(id)?.poster != nil }
        let read = try #require(videos.item(id)?.message)
        guard case .video(.memory(let poster), let duration, let byteCount) = read.content else {
            Issue.record("not a video bubble")
            return
        }
        #expect(poster == probe.preview.poster)
        #expect(duration == 18)
        #expect(byteCount == 0, "the size is known once it is prepared")
        #expect(read.mediaSize == MediaDimensions(width: 1_280, height: 720))
        #expect(probe.prepared == [source])
        #expect(probe.sent.isEmpty)

        probe.gate.open()
        await eventually { videos.item(id)?.phase == .sent }
        let storedID = try #require(LiveMapping.messageData(id))
        #expect(probe.sent.map { $0.id } == [storedID])
        #expect(probe.sent.map { $0.chat } == ["deniz"])
        #expect(probe.discardedSources == [source], "the picked copy goes once the video is stored")
        #expect(handedOver.map(\.id) == [id])

        // Until the stored message shows, the stand-in keeps the bubble; then
        // the stored one is the bubble (same id, never two).
        #expect(OutgoingMedia.merge(videos.items(in: "deniz"), into: []).map(\.id) == [id])
        var stored = bubble
        stored.mediaAvailability = .ready
        let shown = OutgoingMedia.merge(videos.items(in: "deniz"), into: [stored])
        #expect(shown.map(\.id) == [id])
        #expect(shown.first?.mediaAvailability == .ready)
        videos.settle(shown: [stored])
        await eventually { videos.items.isEmpty }
        #expect(videos.items.isEmpty)
    }

    @Test func aFailureOffersSendAgainFromTheSameCopy() async throws {
        let probe = Probe()
        probe.failsPreparing = true
        let videos = probe.makeVideos()
        let source = pickedCopy()
        let id = videos.send(source, as: .video, in: "deniz", disappearing: .off)

        await eventually { videos.item(id)?.phase == .failed }
        let failed = try #require(videos.item(id)?.message)
        #expect(failed.status == .failed)
        #expect(failed.mediaAvailability == .preparationFailed)
        #expect(failed.failedAt != nil)
        #expect(probe.discardedSources.isEmpty, "the copy stays for “Tekrar gönder”")
        #expect(probe.sent.isEmpty)

        probe.failsPreparing = false
        #expect(videos.resend(id))
        #expect(videos.item(id)?.phase == .preparing)
        await eventually { videos.item(id)?.phase == .sent }
        #expect(probe.prepared == [source, source])
        #expect(probe.sent.count == 1)
        #expect(probe.discardedSources == [source])
    }

    @Test func aStoringFailureDropsThePreparedFile() async {
        let probe = Probe()
        probe.failsSending = true
        let videos = probe.makeVideos()
        let source = pickedCopy()
        let id = videos.send(source, as: .video, in: "deniz", disappearing: .off)

        await eventually { videos.item(id)?.phase == .failed }
        #expect(videos.item(id)?.phase == .failed)
        #expect(probe.discardedPrepared == [source.appendingPathExtension("prepared")])
        #expect(probe.discardedSources.isEmpty)
    }

    @Test func removingItWhilePreparingSendsNothing() async {
        let probe = Probe(open: false)
        let videos = probe.makeVideos()
        let source = pickedCopy()
        let id = videos.send(source, as: .video, in: "deniz", disappearing: .off)
        await eventually { !probe.prepared.isEmpty }

        #expect(videos.remove(id))
        #expect(videos.items.isEmpty)
        #expect(probe.discardedSources == [source])

        probe.gate.open()
        await eventually { !probe.discardedPrepared.isEmpty }
        #expect(probe.sent.isEmpty, "removed while it was prepared: never sent")
        #expect(probe.discardedPrepared == [source.appendingPathExtension("prepared")])
    }

    @Test func theBubbleWaitsForThePickersCopy() async throws {
        let probe = Probe()
        let videos = probe.makeVideos()

        let id = videos.begin(.video, in: "deniz", disappearing: .off)
        let waiting = try #require(videos.item(id))
        #expect(waiting.source == nil)
        #expect(waiting.message.mediaAvailability == .preparing)
        #expect(probe.prepared.isEmpty)

        let source = pickedCopy()
        videos.provide(source, for: id)
        await eventually { videos.item(id)?.phase == .sent }
        #expect(probe.prepared == [source])

        // The picker could not hand one over: its bubble goes.
        let unreadable = videos.begin(.video, in: "deniz", disappearing: .off)
        videos.provide(nil, for: unreadable)
        #expect(videos.item(unreadable) == nil)

        // Removed before the copy came: the copy is not kept.
        let removed = videos.begin(.video, in: "deniz", disappearing: .off)
        #expect(videos.remove(removed))
        let late = pickedCopy()
        videos.provide(late, for: removed)
        #expect(probe.discardedSources.contains(late))
        #expect(!probe.prepared.contains(late))
    }

    @Test func aFailedVideoIsGivenUpInTheBackground() async {
        let probe = Probe()
        probe.failsPreparing = true
        let videos = probe.makeVideos()
        let failedSource = pickedCopy()
        let failed = videos.send(failedSource, as: .video, in: "deniz", disappearing: .off)
        await eventually { videos.item(failed)?.phase == .failed }

        videos.didEnterBackground()
        #expect(videos.item(failed) == nil)
        #expect(probe.discardedSources == [failedSource])

        // Failing while the app is away: given up at once.
        let awaySource = pickedCopy()
        let away = videos.send(awaySource, as: .video, in: "deniz", disappearing: .off)
        await eventually { probe.discardedSources.contains(awaySource) }
        #expect(videos.item(away) == nil)

        videos.didBecomeActive()
        let back = videos.send(pickedCopy(), as: .video, in: "deniz", disappearing: .off)
        await eventually { videos.item(back)?.phase == .failed }
        #expect(videos.item(back)?.phase == .failed, "in the foreground it waits for “Tekrar gönder”")
    }

    @Test func eraseStopsEverythingAndTheCopiesGo() async {
        let probe = Probe(open: false)
        let videos = probe.makeVideos()
        let first = pickedCopy()
        let second = pickedCopy()
        videos.send(first, as: .video, in: "deniz", disappearing: .off)
        videos.send(second, as: .video, in: "mert_k", disappearing: .off)
        await eventually { !probe.prepared.isEmpty }
        #expect(probe.prepared == [first], "one at a time: the second waits for its turn")
        videos.removeAll()
        #expect(videos.items.isEmpty)
        #expect(Set(probe.discardedSources) == [first, second])
        probe.gate.open()
        await eventually { probe.discardedPrepared.count == 1 }
        #expect(probe.discardedPrepared == [first.appendingPathExtension("prepared")])
        #expect(probe.prepared == [first], "the second never started")
        #expect(probe.sent.isEmpty)
    }

    @Test func messagesOfTheRuntimeAreLeftToTheRuntime() async {
        let probe = Probe()
        let videos = probe.makeVideos()
        #expect(!videos.resend("00112233445566778899aabbccddeeff"))
        #expect(!videos.remove("00112233445566778899aabbccddeeff"))
        let id = videos.send(pickedCopy(), as: .video, in: "deniz", disappearing: .off)
        await eventually { videos.item(id)?.phase == .sent }
        #expect(!videos.resend(id), "stored: “Tekrar gönder” is the runtime's")
        #expect(!videos.remove(id), "stored: the runtime deletes it")
        #expect(videos.item(id) == nil)
    }

    @Test func eachBubbleSitsWherePickedAndOnlyOnce() {
        let start = Date(timeIntervalSince1970: 1_791_000_000)
        let early = Message(id: "a", direction: .incoming, sentAt: start, content: .text("bir"))
        let late = Message(id: "b", direction: .incoming, sentAt: start.addingTimeInterval(120), content: .text("iki"))
        let picked = OutgoingMediaItem(
            messageData: Data(repeating: 7, count: 16), chat: "deniz", sentAt: start.addingTimeInterval(60),
            disappearing: .off, kind: .video)
        #expect(OutgoingMedia.merge([picked], into: [early, late]).map(\.id) == ["a", picked.id, "b"])
        #expect(OutgoingMedia.merge([picked], into: []).map(\.id) == [picked.id])
        let stored = Message(id: picked.id, direction: .outgoing, sentAt: start.addingTimeInterval(90), content: .text("x"))
        #expect(OutgoingMedia.merge([picked], into: [early, stored]).map(\.id) == ["a", picked.id])
        #expect(OutgoingMedia.merge([], into: [early]) == [early])
    }

    @Test func theSentenceSaysWhatTheVideoIsDoing() {
        var message = OutgoingMediaItem(
            messageData: Data(repeating: 3, count: 16), chat: "deniz", sentAt: Date(), disappearing: .off, kind: .video
        ).message
        let tr = Locale(identifier: "tr")
        let en = Locale(identifier: "en")
        #expect(ConversationMessageRow.detail(for: message, locale: tr) == "Video hazırlanıyor")
        #expect(ConversationMessageRow.detail(for: message, locale: en) == "Preparing the video")
        message.mediaAvailability = .preparationFailed
        #expect(ConversationMessageRow.detail(for: message, locale: tr) == "Video hazırlanamadı")
        #expect(ConversationMessageRow.detail(for: message, locale: en) == "Video couldn't be prepared")
        message.mediaAvailability = .ready
        #expect(ConversationMessageRow.detail(for: message, locale: tr) == nil, "sent: the usual sentence")
    }

    @Test func aStoredVideoKeepsThePosterItWasSentWith() {
        let id = Data(repeating: 5, count: 16)
        let attachment = LiveAttachment(
            kind: .video, contentType: "video/mp4", fileName: nil, size: 12_400_000, durationMs: 18_000,
            thumbnail: Data([1]), caption: nil, waveform: nil, availability: .transferring, width: 1_280, height: 720)
        let record = LiveMessageRecord(
            id: id, direction: .outgoing, body: .attachment(attachment), sentAt: Date(), quoteTarget: nil,
            quoteText: nil, viewOnce: false, status: .sent)
        let poster = Data([0xFF, 0xD8, 0xFF, 0xE0])
        let kept = LiveMapping.messages(
            [record], ownUsername: "selin", peerUsername: "deniz", now: Date(), media: [LiveMapping.messageID(id): poster])
        guard case .video(.memory(let shown), _, _) = kept.first?.content else {
            Issue.record("not a video")
            return
        }
        #expect(shown == poster)
        let plain = LiveMapping.messages([record], ownUsername: "selin", peerUsername: "deniz", now: Date(), media: [:])
        guard case .video(.memory(let thumbnail), _, _) = plain.first?.content else {
            Issue.record("not a video")
            return
        }
        #expect(thumbnail == Data([1]))
    }

    /// The real reader on the fixtures' 18 s clip: the length, the size and
    /// a JPEG poster, from a file, in memory.
    @Test func thePreviewIsReadFromThePickedCopy() async throws {
        let clip = try #require(await FixtureMedia.videoMP4())
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(
            "outgoing-preview-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("klip.mp4")
        try clip.write(to: url)

        let preview = try #require(await VideoPreviewReader.read(url))
        #expect(abs(preview.duration - 18) < 0.2)
        #expect(preview.size == MediaDimensions(width: 640, height: 360))
        let poster = try #require(preview.poster)
        #expect(poster.starts(with: [0xFF, 0xD8]), "a JPEG")
        #expect(await VideoPreviewReader.read(folder.appendingPathComponent("yok.mp4")) == nil)
    }
}

@Suite("Media · sending a video (fixtures)")
@MainActor
struct OutgoingVideoFixtureTests {
    private func makeModel() -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: "mert_k", messenger: service), service)
    }

    @Test func aVideoBeingPreparedDoesNotOpenThePlayer() throws {
        let (model, service) = makeModel()
        let id = service.addOutgoingVideo(in: "mert_k")
        let message = try #require(model.message(id))
        #expect(message.isPendingMedia)
        model.openMedia(message)
        #expect(model.viewer == nil)
        service.addOutgoingVideo(in: "mert_k", failed: true)
        model.openMedia(try #require(model.message(id)))
        #expect(model.viewer == nil)
        #expect(model.message(id)?.status == .failed)
    }

    @Test func thePreparedVideoTakesTheSameBubble() async throws {
        let (model, service) = makeModel()
        service.simulateVideoSend(in: "mert_k", duration: .milliseconds(60))
        let id = try #require(model.messages.last?.id)
        #expect(model.message(id)?.mediaAvailability == .preparing)
        await eventually { model.message(id)?.mediaAvailability == .ready }
        let sent = try #require(model.message(id))
        #expect(sent.status == .sent)
        guard case .video(_, let duration, let byteCount) = sent.content else {
            Issue.record("not a video")
            return
        }
        #expect(duration == 18)
        #expect(byteCount == 12_400_000)
    }

    @Test func aMessageTakenBackClosesItsViewer() async throws {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let model = ConversationViewModel(chatID: "deniz", messenger: service)
        model.openMedia(try #require(model.message("d2")))
        #expect(model.viewer?.kind == .photo)
        #expect(!model.presentedMediaIsGone)
        await service.deleteForEveryone("d2", in: "deniz")
        #expect(model.message("d2")?.isDeleted == true, "only the trace is left")
        #expect(model.presentedMediaIsGone)
    }

    @Test func sendAgainPreparesTheFailedVideoInItsBubble() async throws {
        let (model, service) = makeModel()
        let id = service.addOutgoingVideo(in: "mert_k", failed: true)
        let count = model.messages.count
        model.resend(id)
        await eventually { model.message(id)?.mediaAvailability == .preparing }
        #expect(model.message(id)?.status == .sending)
        #expect(model.messages.count == count, "no second bubble")
    }
}
