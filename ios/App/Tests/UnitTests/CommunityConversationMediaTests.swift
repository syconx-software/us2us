import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime
@testable import us2us

// A community conversation on the shared 05 screen, driven end to end: the
// runtime's `CommunityService` on an encrypted store, the live bridge
// (`RealCommunitiesService`, its `OutgoingMedia`), the adapter
// (`CommunityChatMessenger`) and the view model. A text, then a photo from
// “+”: what the screen reads (`messages`) keeps both at every moment.

/// A relay that answers only what the open conversation needs — the
/// community's signed state and the owner's pending requests (none) — and
/// refuses everything else (uploads, fanout) as unavailable.
private final class StubCommunityRelay: URLProtocol, @unchecked Sendable {
    static let host = "community-conversation-\(UUID().uuidString.lowercased()).test"
    /// The signed state, as the relay serves it (`Data` as base64url).
    private static let lock = NSLock()
    nonisolated(unsafe) private static var state: Data?
    nonisolated(unsafe) private static var requestsUnavailable = false

    static func failRequests(_ enabled: Bool) {
        lock.withLock { requestsUnavailable = enabled }
    }

    static func serve(_ signed: SignedCommunityState) throws {
        let encoder = JSONEncoder()
        encoder.dataEncodingStrategy = .custom { data, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(data.base64URLEncodedString())
        }
        let json = try encoder.encode(signed)
        lock.withLock { state = json; requestsUnavailable = false }
    }

    private static var served: Data? { lock.withLock { state } }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url else { return }
        let parts = url.path.split(separator: "/").map(String.init)
        let (status, body): (Int, Data)
        if request.httpMethod == "GET", parts.count == 4, parts[1] == "communities", parts[3] == "requests" {
            if Self.lock.withLock({ Self.requestsUnavailable }) {
                (status, body) = (503, Data(#"{"error":{"code":"unavailable","message":"test"}}"#.utf8))
            } else {
                (status, body) = (200, Data(#"{"requests":[]}"#.utf8))
            }
        } else if request.httpMethod == "GET", parts.count == 3, parts[1] == "communities", let signed = Self.served {
            (status, body) = (200, signed)
        } else {
            (status, body) = (503, Data(#"{"error":{"code":"unavailable","message":"test"}}"#.utf8))
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// This phone: its store, its own community (it is the owner), the runtime's
/// communities service and the screens' stack on top of it.
@MainActor
private final class CommunityScreenFixture {
    let root: URL
    let storage: LocalStorage
    let service: CommunityService
    let bridge: RealCommunitiesService
    let communityID: CommunityID
    let messenger: CommunityChatMessenger
    let viewModel: ConversationViewModel
    let pickerActivity = SystemPickerActivity()
    static let photo = Data((0..<3000).map { UInt8($0 % 239) })

    /// Everything but the community itself, which the messenger (an actor) signs in ``prepare()``.
    init() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("us2us-app-tests-\(UUID().uuidString)", isDirectory: true)
        let directories = AppDirectories(root: root.appendingPathComponent("app", isDirectory: true))
        try directories.prepare()
        storage = try LocalStorage.open(layout: directories.storage, dek: Data((0..<32).map { _ in UInt8.random(in: 0...255) })).storage
        let store = try storage.createIdentity(LocalIdentity.generateKeyPair(), registrationId: LocalIdentity.generateRegistrationId())
        let account = LocalAccount(
            accountId: AccountId(uuid: UUID()), authToken: Data(repeating: 7, count: 32),
            username: try NormalizedUsername("community_tester"), accessKey: Data(repeating: 9, count: 16))
        try storage.accountSaver(account)
        let configuration = RelayClient.defaultConfiguration()
        configuration.protocolClasses = [StubCommunityRelay.self]
        let relay = RelayClient(baseURL: URL(string: "https://\(StubCommunityRelay.host)")!, configuration: configuration)
        let messenger = Messenger(account: account, store: store, relay: relay)
        let backend = MessengerBackend(messenger: messenger, accountId: account.accountId, protocolStore: store)
        // The community this phone owns, pinned as after its creation was confirmed.
        let owner = try await messenger.communityIdentity()
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: 1, isPublic: true, owner: owner, name: "Bizim", members: [owner])
        let signed = try await messenger.signCommunityState(state)
        try storage.write { try $0.communities.save(signed) }
        try StubCommunityRelay.serve(signed)
        communityID = state.id.string

        service = CommunityService(backend: backend, storage: storage, pollInterval: .seconds(3600))
        // Photos are “prepared” by copying the picked bytes into the staging folder.
        let staging = storage
        let preparation = CommunityMediaPreparation(
            image: { url, caption in
                let file = staging.makeStagingURL()
                try FileManager.default.copyItem(at: url, to: file)
                let size = UInt64((try? Data(contentsOf: file).count) ?? 0)
                return PreparedMedia(
                    file: file, kind: .image, contentType: "image/jpeg", size: size, width: 40, height: 30,
                    thumbnail: Data([0xFF, 0xD8, 0xFF, 0xD9]), caption: caption)
            },
            video: { _ in throw CommunityMediaPreparation.Unavailable() },
            file: { url, name, type in
                let file = staging.makeStagingURL()
                try FileManager.default.copyItem(at: url, to: file)
                return PreparedMedia(file: file, kind: .file, contentType: type,
                    fileName: name, size: UInt64(try Data(contentsOf: file).count))
            },
            voice: { recording in
                let file = staging.makeStagingURL()
                try recording.audio.write(to: file)
                return PreparedMedia(file: file, kind: .voice, contentType: "audio/mp4", size: UInt64(recording.audio.count),
                                     durationMs: recording.durationMilliseconds, waveform: recording.waveform)
            })
        bridge = RealCommunitiesService(service: service, preparation: preparation)
        let base = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        self.messenger = CommunityChatMessenger(base: base, communityID: communityID, service: bridge, pickerActivity: pickerActivity)
        viewModel = ConversationViewModel(chatID: communityID, messenger: self.messenger)
    }

    /// A picked photo's copy, as the picker leaves it.
    func pickedPhoto() throws -> URL {
        let directory = root.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("IMG_0001.jpeg")
        try Self.photo.write(to: url)
        return url
    }

    func tearDown() {
        service.stop()
        storage.close()
        try? FileManager.default.removeItem(at: root)
    }
}

@Suite("Communities v2: the conversation screen across a text and a photo", .serialized)
struct CommunityConversationMediaTests {
    @Test("v3 shared conversation sends sticker and voice, retains history and reloads both kinds")
    @MainActor
    func stickerAndVoiceStayVisibleAcrossReopen() async throws {
        let fixture = try await CommunityScreenFixture()
        defer { fixture.tearDown() }
        let id = fixture.communityID
        await fixture.service.refresh()
        await fixture.messenger.conversationAppeared(id)
        #expect(await fixture.messenger.sendText("Önceki mesaj"))
        let sticker = try #require(BrandStickerCatalog.all.first)
        await fixture.messenger.send(sticker: BrandStickerSelection(sticker, language: .turkish), replyTo: nil, in: id)
        let recording = VoiceRecording(audio: Data(repeating: 1, count: 2048), duration: 2, waveform: Data([0, 128, 255]))
        #expect(await fixture.messenger.send(voice: recording, replyTo: nil, in: id))
        #expect(fixture.viewModel.messages.count == 3)
        #expect(fixture.viewModel.messages.first?.content == .text("Önceki mesaj"))
        #expect(fixture.viewModel.messages.contains { if case .sticker = $0.content { true } else { false } })
        #expect(fixture.viewModel.messages.contains { if case .voice(let duration, let waveform, _) = $0.content { duration == 2 && !waveform.isEmpty } else { false } })
        let voice = try #require(fixture.viewModel.messages.last)
        #expect(await fixture.messenger.mediaPayload(voice.id, in: id)?.data == recording.audio)
        fixture.messenger.conversationDisappeared(id)
        await fixture.messenger.conversationAppeared(id)
        #expect(fixture.viewModel.messages.count == 3)
        #expect(fixture.viewModel.messages.contains { if case .sticker = $0.content { true } else { false } })
        #expect(fixture.viewModel.messages.contains { if case .voice = $0.content { true } else { false } })
    }

    @Test("covering the community with a picker preserves history, then stores the photo without reentering")
    @MainActor
    func pickerCoverageKeepsConversationAndPhotoVisible() async throws {
        let fixture = try await CommunityScreenFixture()
        defer { fixture.tearDown() }
        let id = fixture.communityID
        await fixture.service.refresh()
        await fixture.messenger.conversationAppeared(id)
        #expect(await fixture.messenger.sendText("Seçimden önce"))
        let token = fixture.pickerActivity.begin(cancel: {})
        fixture.messenger.conversationDisappeared(id)
        #expect(fixture.service.selected?.string == id)
        #expect(!fixture.service.isConversationVisible)
        #expect(fixture.viewModel.messages.map(\.content) == [.text("Seçimden önce")])
        // SwiftUI appears again while the picker finishes dismissing.
        await fixture.messenger.conversationAppeared(id)
        #expect(fixture.service.isConversationVisible)
        fixture.pickerActivity.end(token)
        await fixture.messenger.send(attachment: .photo(.file(try fixture.pickedPhoto()), caption: "Fotoğraf"), in: id)
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            #expect(fixture.viewModel.messages.count == 2)
            #expect(fixture.viewModel.messages.first?.content == .text("Seçimden önce"))
            if fixture.service.messages.count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(fixture.service.messages.count == 2)
        #expect(fixture.viewModel.messages.count == 2)
        // Actual navigation away still clears the selected conversation.
        fixture.messenger.conversationDisappeared(id)
        #expect(fixture.service.selected == nil)
        #expect(fixture.viewModel.messages.isEmpty)
    }

    @Test("a text, then a photo from “+”: the screen keeps both at every moment, until the photo is stored")
    @MainActor
    func textThenPhotoStayOnScreen() async throws {
        let fixture = try await CommunityScreenFixture()
        defer { fixture.tearDown() }
        let viewModel = fixture.viewModel
        let id = fixture.communityID

        await fixture.service.refresh()
        await fixture.messenger.conversationAppeared(id)
        #expect(fixture.service.selected?.string == id)
        #expect(viewModel.chat != nil && viewModel.community != nil)
        #expect(viewModel.messages.isEmpty)

        #expect(await fixture.messenger.sendText("Selam"))
        #expect(viewModel.messages.map(\.content) == [.text("Selam")])
        #expect(viewModel.rows.count == 1)

        // “+” › a photo with the composer's text as its caption.
        await fixture.messenger.send(attachment: .photo(.file(try fixture.pickedPhoto()), caption: "Gün batımı"), in: id)
        // Its bubble is there at once, after the text.
        #expect(viewModel.messages.count == 2)
        #expect(viewModel.messages.first?.content == .text("Selam"))
        guard case .photo(_, let caption)? = viewModel.messages.last?.content else {
            Issue.record("no photo bubble: \(viewModel.messages)")
            return
        }
        #expect(caption == "Gün batımı")
        let bubble = try #require(viewModel.messages.last?.id)

        // Prepared and stored under the bubble's id: the stored message takes
        // its place, and at no moment is the screen short of a message.
        var samples: [[MessageID]] = []
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            let shown = viewModel.messages
            samples.append(shown.map(\.id))
            if shown.count != 2 || shown.first?.content != .text("Selam") || shown.last?.id != bubble {
                Issue.record("the screen lost a message: \(shown.map { ($0.id, $0.content) })")
                break
            }
            // Stored: the runtime's item under the bubble's id, no stand-in left.
            if fixture.service.messages.count == 2, fixture.bridge.outgoingMedia.items.isEmpty { break }
            await Task.yield()
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(fixture.service.messages.count == 2, "the photo is stored")
        #expect(fixture.service.messages.last.map { LiveMapping.messageID($0.messageId.bytes) } == bubble)
        #expect(fixture.bridge.outgoingMedia.items.isEmpty, "the stand-in left once the stored message showed")
        #expect(fixture.service.selected?.string == id)
        #expect(viewModel.messages.map(\.id) == samples.last)
        #expect(viewModel.messages.count == 2)
        #expect(viewModel.messages.last?.mediaAvailability == .ready)
        #expect(viewModel.rows.count == 2)
    }

    @Test("local text and files remain visible when the owner's requests endpoint returns 503")
    @MainActor
    func requestFailureKeepsLocalHistoryVisible() async throws {
        let fixture = try await CommunityScreenFixture()
        defer {
            StubCommunityRelay.failRequests(false)
            fixture.tearDown()
        }
        let id = fixture.communityID
        let account = try #require(AccountId(string: id))
        await fixture.service.refresh()
        await fixture.messenger.conversationAppeared(id)
        #expect(await fixture.messenger.sendText("Önceki mesaj"))

        StubCommunityRelay.failRequests(true)
        #expect(await fixture.messenger.sendText("Bağlantı kesilince"))
        await fixture.messenger.send(attachment: .document(try fixture.pickedPhoto()), in: id)

        let stored = try fixture.storage.read { try $0.communities.messages(account) }
        #expect(stored.count == 3)
        #expect(fixture.service.messages.count == 3)
        #expect(fixture.viewModel.messages.count == 3)
        #expect(fixture.viewModel.messages.prefix(2).map(\.content) ==
            [.text("Önceki mesaj"), .text("Bağlantı kesilince")])
        #expect(fixture.viewModel.rows.count == 3)

        // The independent network error is still reported to the caller.
        await #expect(throws: (any Error).self) { try await fixture.service.reload(account) }
        #expect(fixture.viewModel.messages.count == 3)
        StubCommunityRelay.failRequests(false)
        try await fixture.service.reload(account)
        #expect(fixture.viewModel.messages.count == 3)
    }

    @Test("a stored photo replaces its temporary bubble even when pending requests cannot be loaded")
    @MainActor
    func requestFailureStillSettlesPhoto() async throws {
        let fixture = try await CommunityScreenFixture()
        defer {
            StubCommunityRelay.failRequests(false)
            fixture.tearDown()
        }
        let id = fixture.communityID
        await fixture.service.refresh()
        await fixture.messenger.conversationAppeared(id)
        #expect(await fixture.messenger.sendText("Önceki mesaj"))
        StubCommunityRelay.failRequests(true)
        await fixture.messenger.send(attachment: .photo(.file(try fixture.pickedPhoto()), caption: "Fotoğraf"), in: id)
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            #expect(fixture.viewModel.messages.count == 2)
            if fixture.service.messages.count == 2, fixture.bridge.outgoingMedia.items.isEmpty { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(fixture.service.messages.count == 2)
        #expect(fixture.bridge.outgoingMedia.items.isEmpty)
        #expect(fixture.viewModel.messages.count == 2)
    }
}
