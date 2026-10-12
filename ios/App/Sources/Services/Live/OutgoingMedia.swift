import AVFoundation
import Foundation
import ImageIO
import Observation
import UniformTypeIdentifiers
import Us2usKit
import Us2usRuntime

/// A photo or video picked in “+” on its way out, as its bubble shows it
/// (5.2, proposed “Hazırlanıyor”).
struct OutgoingMediaItem: Hashable, Sendable {
    enum Phase: Hashable, Sendable {
        /// Being handed over by the picker, waiting for its turn, or being
        /// prepared (a photo re-encoded at ≤ 2048 px, a video re-exported at
        /// ≤ 720p — no metadata either way) before it is sent.
        case preparing
        /// Preparing (or storing) it failed; nothing left the phone.
        case failed
        /// Stored by the runtime under the bubble's id. The stand-in stays
        /// until the conversation shows the stored message, so the bubble
        /// never misses a frame.
        case sent
    }

    /// The id the runtime stores the message under (16 random bytes).
    let messageData: Data
    let chat: ChatID
    /// When it was picked: the bubble's place in the conversation. The items
    /// of one pick are a millisecond apart, in the order they were selected.
    let sentAt: Date
    let disappearing: DisappearingTimer
    let kind: PickedMediaItem.Kind
    /// A photo's caption (the composer's text, on the first photo of a pick).
    var caption: String? = nil
    /// The picked copy (`SystemPickers`, protected); `nil` while the picker
    /// is still handing it over.
    var source: URL?
    /// A video's first frame, or a photo's preview at the bubble's size —
    /// JPEG, in memory only.
    var poster: Data?
    /// A photo as it was sent (the prepared JPEG, ≤ 2048 px), read just
    /// before it was stored; memory only.
    var sentPhoto: Data?
    var duration: TimeInterval = 0
    var size: MediaDimensions?
    var phase: Phase = .preparing
    var failedAt: Date?

    var id: MessageID { LiveMapping.messageID(messageData) }

    /// What the stored message's bubble may keep showing (sharper than the
    /// inline thumbnail): a video's poster; a photo as it was sent — never
    /// its preview, which the viewer would then open instead of the photo.
    var keptImage: Data? {
        switch kind {
        case .photo: sentPhoto
        case .video: poster
        }
    }

    /// The bubble: the picture (once it is read), the ring and
    /// “Hazırlanıyor” (sending); the warning and “Tekrar gönder” once it
    /// failed. A photo shows its caption from the start.
    var message: Message {
        let content: MessageContent =
            switch kind {
            case .photo: .photo(.memory(sentPhoto ?? poster ?? Data()), caption: caption)
            case .video: .video(.memory(poster ?? Data()), duration: duration, byteCount: 0)
            }
        var message = Message(
            id: id, direction: .outgoing, sentAt: sentAt, content: content,
            status: phase == .failed ? .failed : .sending, disappearing: disappearing)
        message.mediaAvailability = phase == .failed ? .preparationFailed : .preparing
        message.failedAt = failedAt
        message.mediaSize = size
        return message
    }
}

/// What a picked item's bubble shows before it is prepared.
struct OutgoingMediaPreview: Hashable, Sendable {
    var poster: Data?
    var duration: TimeInterval
    var size: MediaDimensions?
}

/// The photos and videos picked in “+” that are on their way out.
///
/// Handing an item over and preparing it take a moment (a long video a few
/// seconds), so every bubble is there at once, in the order the items were
/// selected: the ring and “Hazırlanıyor”, the picture (and a video's length)
/// as soon as it is read from the picked copy. Then the items are prepared
/// and sent — one at a time, in the order picked, so only one photo or video
/// is ever being decoded or re-exported however many were picked (the
/// previews, at the bubble's size, are read one at a time too). Each is
/// stored under the message id its bubble already has, so the stored
/// message takes the bubble's place and nothing moves. An item still being
/// handed over holds back the ones picked after it; one that fails does not:
/// its bubble says so, the rest carry on, and “Tekrar gönder” prepares it
/// again from the same picked copy (after whatever is still waiting);
/// “Benden sil” removes it.
///
/// Nothing new is written: the picked copy stays in its protected folder only
/// while the item is being prepared or waits for “Tekrar gönder”. It goes
/// once the item is stored (the runtime keeps its own copy, encrypted), when
/// the bubble is removed, when a failed item is left behind in the
/// background, and on “Her şeyi sil”. Posters, previews and a sent photo are
/// JPEG in memory.
@Observable
@MainActor
final class OutgoingMedia {
    /// How an item goes out; the live service runs them on the runtime, the
    /// tests on stand-ins.
    struct Steps {
        /// The poster (a video's first frame, a photo's preview), length and
        /// size, read from the picked copy.
        var preview: @Sendable (URL, PickedMediaItem.Kind) async -> OutgoingMediaPreview?
        /// Prepares the picked copy into the runtime's staging folder: a
        /// photo re-encoded with its caption, a video re-exported.
        var prepare: @MainActor (URL, PickedMediaItem.Kind, String?) async throws -> PreparedMedia
        /// Stores and queues the prepared item under the id, in the chat.
        var send: @MainActor (PreparedMedia, Data, ChatID) async throws -> Void
        /// The picked copy is no longer needed.
        var discardSource: @MainActor (URL) -> Void
        /// A prepared file that is not going to be sent.
        var discardPrepared: @MainActor (URL) -> Void
    }

    enum Failure: Error, Sendable {
        /// The chat id is not an account.
        case unknownChat
    }

    private(set) var items: [OutgoingMediaItem] = []
    /// An item was stored: its picture can stay for the stored bubble.
    @ObservationIgnored var onSent: ((OutgoingMediaItem) -> Void)?
    /// When the items stored so far were picked, while items picked with
    /// them are still on their way: a later item of the same pick never
    /// shows above one stored before it (a stored message sits where it was
    /// stored). Memory only.
    @ObservationIgnored private(set) var landed: [MessageID: Date] = [:]
    /// The item being prepared and stored now (one at a time).
    @ObservationIgnored private(set) var active: MessageID?

    @ObservationIgnored private let steps: Steps
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var tasks: [MessageID: Task<Void, Never>] = [:]
    /// Items waiting for their turn, in the order they were picked.
    @ObservationIgnored private var queue: [MessageID] = []
    /// The latest preview being read (each waits for the one before).
    @ObservationIgnored private var previews: Task<Void, Never>?
    @ObservationIgnored private var lastPick: Date?
    @ObservationIgnored private var isInBackground = false

    init(steps: Steps, now: @escaping () -> Date = { Date() }) {
        self.steps = steps
        self.now = now
    }

    // MARK: Reading

    func item(_ id: MessageID) -> OutgoingMediaItem? {
        items.first { $0.id == id }
    }

    func items(in chat: ChatID) -> [OutgoingMediaItem] {
        items.filter { $0.chat == chat }
    }

    /// `messages` (a stored conversation, oldest first) with the bubbles of
    /// `pending`, each where it was picked — but never above a message that
    /// was picked before it and stored meanwhile (`landed`: when those were
    /// picked); one already stored is not added again.
    static func merge(
        _ pending: [OutgoingMediaItem], into messages: [Message], landed: [MessageID: Date] = [:]
    ) -> [Message] {
        guard !pending.isEmpty else { return messages }
        let stored = Set(messages.lazy.map(\.id))
        var result = messages
        for item in pending.sorted(by: { $0.sentAt < $1.sentAt }) where !stored.contains(item.id) {
            var index = result.lastIndex { $0.sentAt <= item.sentAt }.map { $0 + 1 } ?? result.startIndex
            if !landed.isEmpty,
                let earlier = result.lastIndex(where: { message in
                    landed[message.id].map { pickedAt in pickedAt < item.sentAt } ?? false
                })
            {
                index = max(index, earlier + 1)
            }
            result.insert(item.message, at: index)
        }
        return result
    }

    // MARK: Sending

    /// Puts the bubble up for an item the picker is still handing over
    /// (`provide` brings the copy). It takes its turn after everything
    /// picked before it; a photo carries `caption`.
    @discardableResult
    func begin(
        _ kind: PickedMediaItem.Kind, caption: String? = nil, in chat: ChatID, disappearing: DisappearingTimer
    ) -> MessageID {
        // The items of one pick keep the order they were selected in, also
        // when the clock reads the same for all of them.
        var pickedAt = now()
        if let lastPick, pickedAt <= lastPick { pickedAt = lastPick.addingTimeInterval(0.001) }
        lastPick = pickedAt
        let item = OutgoingMediaItem(
            messageData: RandomId.make(), chat: chat, sentAt: pickedAt, disappearing: disappearing, kind: kind,
            caption: kind == .photo ? caption : nil)
        items.append(item)
        queue.append(item.id)
        return item.id
    }

    /// The picked copy of `id`'s item: its preview is read, and it is
    /// prepared once its turn comes. `nil`: the picker could not hand it
    /// over — there is nothing to send again, the bubble goes (and the items
    /// after it carry on).
    func provide(_ source: URL?, for id: MessageID) {
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].source == nil,
            items[index].phase == .preparing
        else {
            // Removed in the meantime: the copy is not needed.
            if let source { steps.discardSource(source) }
            return
        }
        guard let source else {
            items.remove(at: index)
            queue.removeAll { $0 == id }
            pump()
            return
        }
        items[index].source = source
        readPreview(of: id, kind: items[index].kind, from: source)
        pump()
    }

    /// Puts the bubble up and queues `source` (a picked copy).
    @discardableResult
    func send(
        _ source: URL, as kind: PickedMediaItem.Kind, caption: String? = nil, in chat: ChatID,
        disappearing: DisappearingTimer
    ) -> MessageID {
        let id = begin(kind, caption: caption, in: chat, disappearing: disappearing)
        provide(source, for: id)
        return id
    }

    /// “Tekrar gönder” on an item that failed: it waits for its turn again.
    /// `false`: not an item of this list (a message of the runtime).
    @discardableResult
    func resend(_ id: MessageID) -> Bool {
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].phase != .sent else { return false }
        guard items[index].phase == .failed else { return true }
        items[index].phase = .preparing
        items[index].failedAt = nil
        queue.append(id)
        pump()
        return true
    }

    /// “Benden sil”: the bubble goes, preparing stops, nothing is sent, the
    /// picked copy goes. `false`: not an item of this list (or already
    /// stored — then the runtime deletes it).
    @discardableResult
    func remove(_ id: MessageID) -> Bool {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return false }
        let item = items.remove(at: index)
        queue.removeAll { $0 == id }
        tasks.removeValue(forKey: id)?.cancel()
        guard item.phase != .sent else { return false }
        if let source = item.source { steps.discardSource(source) }
        pump()
        return true
    }

    // MARK: Lifecycle

    /// The conversation shows `messages`: stand-ins of items stored among
    /// them are no longer needed.
    func settle(shown messages: [Message]) {
        guard items.contains(where: { $0.phase == .sent }) else { return }
        let shown = Set(messages.lazy.map(\.id))
        let done = Set(items.lazy.filter { $0.phase == .sent && shown.contains($0.id) }.map(\.id))
        guard !done.isEmpty else { return }
        // Not in this pass: the caller is reading the conversation.
        Task { [weak self] in
            self?.items.removeAll { $0.phase == .sent && done.contains($0.id) }
            self?.forgetLandedWhenIdle()
        }
    }

    /// The conversation closed: stored items need no stand-ins any more
    /// (one being prepared carries on, a failed one waits for its chat).
    func conversationClosed(_ chat: ChatID) {
        items.removeAll { $0.chat == chat && $0.phase == .sent }
        forgetLandedWhenIdle()
    }

    /// The app left the foreground: failed items are given up and their
    /// copies go; the ones being prepared or waiting carry on (if one fails
    /// while the app is away, it is given up too).
    func didEnterBackground() {
        isInBackground = true
        for item in items where item.phase == .failed {
            if let source = item.source { steps.discardSource(source) }
        }
        items.removeAll { $0.phase != .preparing }
        forgetLandedWhenIdle()
    }

    func didBecomeActive() {
        isInBackground = false
    }

    /// “Her şeyi sil”: everything stops and every picked copy goes.
    func removeAll() {
        for task in tasks.values { task.cancel() }
        tasks = [:]
        previews?.cancel()
        previews = nil
        queue = []
        active = nil
        for item in items where item.phase != .sent {
            if let source = item.source { steps.discardSource(source) }
        }
        items = []
        landed = [:]
    }

    // MARK: Steps

    /// The previews are read one at a time, in the order the copies came.
    private func readPreview(of id: MessageID, kind: PickedMediaItem.Kind, from source: URL) {
        let previous = previews
        let read = steps.preview
        previews = Task { [weak self] in
            await previous?.value
            // Gone meanwhile: nothing to read.
            guard !Task.isCancelled, self?.item(id) != nil else { return }
            guard let preview = await read(source, kind) else { return }
            self?.apply(preview, to: id)
        }
    }

    private func apply(_ preview: OutgoingMediaPreview, to id: MessageID) {
        // Already stored: a photo shows itself as sent by now.
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].phase != .sent else { return }
        items[index].poster = preview.poster
        items[index].duration = preview.duration
        items[index].size = preview.size
    }

    /// The next item takes its turn: one at a time, in the order picked. An
    /// item still being handed over holds back the ones picked after it; an
    /// item removed (or no longer waiting) is skipped.
    private func pump() {
        while active == nil, let id = queue.first {
            guard let item = item(id), item.phase == .preparing else {
                queue.removeFirst()
                continue
            }
            guard let source = item.source else { return }
            queue.removeFirst()
            start(item, from: source)
        }
    }

    private enum Outcome {
        /// Stored; for a photo, the photo as it was sent.
        case sent(Data?)
        case failed
    }

    private func start(_ item: OutgoingMediaItem, from source: URL) {
        let id = item.id
        active = id
        let steps = steps
        tasks[id] = Task { [weak self] in
            var outcome = Outcome.failed
            do {
                let prepared = try await steps.prepare(source, item.kind, item.caption)
                // The bubble keeps the photo as it is sent: read before
                // storing it moves the file into encrypted storage.
                var photo: Data?
                if item.kind == .photo { photo = await OutgoingMedia.contents(of: prepared.file) }
                // Removed while it was being prepared: nothing is sent.
                guard !Task.isCancelled, self?.item(id)?.phase == .preparing else {
                    steps.discardPrepared(prepared.file)
                    self?.finish(id, .failed)
                    return
                }
                do {
                    try await steps.send(prepared, item.messageData, item.chat)
                    outcome = .sent(photo)
                } catch {
                    steps.discardPrepared(prepared.file)
                }
            } catch {
                outcome = .failed
            }
            self?.finish(id, outcome)
        }
    }

    private func finish(_ id: MessageID, _ outcome: Outcome) {
        tasks[id] = nil
        if active == id { active = nil }
        defer { pump() }
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].phase == .preparing else { return }
        switch outcome {
        case .sent(let photo):
            if let source = items[index].source { steps.discardSource(source) }
            items[index].sentPhoto = photo
            items[index].phase = .sent
            landed[id] = items[index].sentAt
            onSent?(items[index])
        case .failed where isInBackground:
            // Nobody is looking: the picked copy does not wait in the background.
            if let source = items[index].source { steps.discardSource(source) }
            items.remove(at: index)
        case .failed:
            items[index].phase = .failed
            items[index].failedAt = now()
        }
    }

    /// Nothing is waiting any more: when the stored ones were picked no
    /// longer matters.
    private func forgetLandedWhenIdle() {
        if !items.contains(where: { $0.phase != .sent }) { landed = [:] }
    }

    /// A prepared file's bytes, read off the main thread.
    nonisolated private static func contents(of url: URL) async -> Data? {
        try? Data(contentsOf: url)
    }
}

/// Reads what a picked video's bubble shows before it is prepared — its
/// length, size and a poster frame — from the picked copy. The frame is
/// encoded to a JPEG in memory; nothing is written.
enum VideoPreviewReader {
    /// The poster's longest edge: the 236 × 124 bubble at 3×, with room.
    static let posterEdge: CGFloat = 720

    /// `nil`: not a readable video.
    static func read(_ url: URL) async -> OutgoingMediaPreview? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first else { return nil }
        let seconds = (try? await asset.load(.duration))?.seconds ?? 0
        var size: MediaDimensions?
        if let (natural, transform) = try? await track.load(.naturalSize, .preferredTransform) {
            let rect = CGRect(origin: .zero, size: natural).applying(transform)
            size = MediaDimensions(width: Int(abs(rect.width).rounded()), height: Int(abs(rect.height).rounded()))
        }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: posterEdge, height: posterEdge)
        var poster: Data?
        if let frame = try? await generator.image(at: .zero).image {
            poster = jpeg(frame)
        }
        return OutgoingMediaPreview(poster: poster, duration: seconds.isFinite ? max(0, seconds) : 0, size: size)
    }

    /// Pixels only: no properties are passed, so no metadata is written.
    static func jpeg(_ image: CGImage, quality: Double = 0.8) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(
            destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}

/// Reads what a picked photo's bubble shows before it is prepared — its size
/// and a preview — from the picked copy. Only the preview's pixels are
/// decoded (ImageIO's thumbnail path, at the bubble's size), never the full
/// photo; the preview is a JPEG in memory and nothing is written.
enum PhotoPreviewReader {
    /// The preview's longest edge: the 258 × 176 bubble at 3×, with room.
    static let previewEdge = 800

    /// `nil`: not a readable image.
    static func read(_ url: URL) async -> OutgoingMediaPreview? {
        guard
            let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: previewEdge,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let size = pixelSize(of: source) ?? MediaDimensions(width: image.width, height: image.height)
        return OutgoingMediaPreview(poster: VideoPreviewReader.jpeg(image), duration: 0, size: size)
    }

    /// The photo's size as it is seen (orientation applied), from its
    /// properties — nothing is decoded for it.
    private static func pixelSize(of source: CGImageSource) -> MediaDimensions? {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
            let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
            width > 0, height > 0
        else { return nil }
        // EXIF orientations 5–8 turn the picture a quarter.
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        return orientation >= 5
            ? MediaDimensions(width: height, height: width) : MediaDimensions(width: width, height: height)
    }
}
