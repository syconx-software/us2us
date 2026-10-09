import CoreGraphics
import Foundation
import ImageIO
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

// MARK: Paths

enum RepositoryPaths {
    /// .../ios/Us2usAppCore/Tests/Us2usRuntimeTests/Support/TestSupport.swift
    static let package: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url
    }()

    static var runtimeSources: URL { package.appendingPathComponent("Sources/Us2usRuntime") }
}

/// A per-test scratch directory, removed when the value is dropped.
final class TemporaryDirectory: @unchecked Sendable {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent(
            "us2us-runtime-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }

    var directories: AppDirectories { AppDirectories(root: url.appendingPathComponent("app", isDirectory: true)) }
}

func randomKey() -> Data {
    var generator = SystemRandomNumberGenerator()
    return Data((0..<32).map { _ in UInt8.random(in: 0...255, using: &generator) })
}

// MARK: Clock

/// A clock that only moves when told to. Sleepers wake when the time passes
/// their deadline (or when cancelled).
final class ManualClock: RuntimeClock, @unchecked Sendable {
    private struct Sleeper {
        let deadline: Date
        let continuation: CheckedContinuation<Void, Error>
    }

    private let lock = NSLock()
    private var current: Date
    private var sleepers: [UUID: Sleeper] = [:]

    init(start: Date = Date()) {
        current = start
    }

    func now() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    var sleeperCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return sleepers.count
    }

    func sleep(seconds: TimeInterval) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                let deadline = current.addingTimeInterval(max(0, seconds))
                if deadline <= current {
                    lock.unlock()
                    continuation.resume()
                    return
                }
                sleepers[id] = Sleeper(deadline: deadline, continuation: continuation)
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            let sleeper = sleepers.removeValue(forKey: id)
            lock.unlock()
            sleeper?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Moves time forward and wakes every sleeper whose deadline passed.
    func advance(by seconds: TimeInterval) {
        lock.lock()
        current = current.addingTimeInterval(seconds)
        let due = sleepers.filter { $0.value.deadline <= current }
        for id in due.keys { sleepers[id] = nil }
        lock.unlock()
        for sleeper in due.values { sleeper.continuation.resume() }
    }
}

// MARK: Waiting

/// Polls `condition` until it holds (real time; the work runs on other tasks).
/// The default timeout is generous: a whole-package run shares the
/// cooperative pool with CPU-heavy tests (PBKDF2, SQLCipher, video export)
/// on a loaded machine, and a passing condition returns at once anyway.
/// Negative checks ("nothing is sent") pass a short timeout explicitly.
func eventually(
    timeout: TimeInterval = 30, _ message: String = "condition", isolation: isolated (any Actor)? = #isolation,
    sourceLocation: SourceLocation = #_sourceLocation, _ condition: () async throws -> Bool
) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if try await condition() { return }
        try await Task.sleep(nanoseconds: 10_000_000)
    }
    Issue.record("timed out waiting for \(message)", sourceLocation: sourceLocation)
    throw TestTimeout()
}

struct TestTimeout: Error {}

/// Polls `condition` for `timeout` (real time) and returns whether it ever
/// held, recording nothing: for negative checks ("nothing is sent"), where
/// ``eventually(timeout:_:isolation:sourceLocation:_:)`` would record its
/// timeout as an issue even though the timeout is the expected outcome.
func happens(
    within timeout: TimeInterval, isolation: isolated (any Actor)? = #isolation, _ condition: () async throws -> Bool
) async rethrows -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if try await condition() { return true }
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
    return try await condition()
}

/// Lets queued tasks run.
func settle(_ rounds: Int = 20) async {
    for _ in 0..<rounds {
        await Task.yield()
        try? await Task.sleep(nanoseconds: 1_000_000)
    }
}

// MARK: Fake backend

/// One recorded send.
struct SentMessage: Sendable {
    let content: BRPContent
    let to: AccountId
    let requestState: ConversationRequestState
    let accessKey: Data?
    let ephemeral: Bool

    var isAccessKeyUpdate: Bool {
        if case .control(let control) = content.body, case .accessKey = control.action { return true }
        return false
    }

    var isProfile: Bool {
        if case .control(let control) = content.body, case .profile = control.action { return true }
        return false
    }

    var isAccept: Bool {
        if case .control(let control) = content.body, case .requestResponse(let response) = control.action {
            return response.type == .accept
        }
        return false
    }

    var receipt: BRPReceiptMessage? {
        if case .receipt(let receipt) = content.body { return receipt }
        return nil
    }

    var data: BRPDataMessage? {
        if case .data(let data) = content.body { return data }
        return nil
    }

    var control: BRPControlMessage.OneOf_Action? {
        if case .control(let control) = content.body { return control.action }
        return nil
    }
}

/// A relay stream driven by the test.
final class FakeStream: RelayStream, @unchecked Sendable {
    enum Event: Sendable {
        case message(InboundMessage)
        case queueEmpty
        case fail(RelayError)
    }

    private let events: AsyncStream<Event>
    private let continuation: AsyncStream<Event>.Continuation
    private let state = LockedState<(closed: Bool, delivered: [String], running: Bool)>((false, [], false))

    init() {
        (events, continuation) = AsyncStream.makeStream(of: Event.self)
    }

    func push(_ event: Event) { continuation.yield(event) }

    var delivered: [String] { state.withLock { $0.delivered } }
    var isClosed: Bool { state.withLock { $0.closed } }
    var isRunning: Bool { state.withLock { $0.running } }

    func run(
        deliver: @escaping @Sendable (InboundMessage) async throws -> Void,
        onQueueEmpty: @escaping @Sendable () async -> Void
    ) async throws {
        state.withLock { $0.running = true }
        defer { state.withLock { $0.running = false } }
        for await event in events {
            switch event {
            case .message(let message):
                try await deliver(message)
                state.withLock { $0.delivered.append(message.relayId) }
            case .queueEmpty:
                await onQueueEmpty()
            case .fail(let error):
                throw error
            }
        }
    }

    func close() {
        state.withLock { $0.closed = true }
        continuation.finish()
    }
}

/// An in-memory relay + crypto stand-in: records what the runtime sends and
/// lets tests inject failures.
final class FakeBackend: MessagingBackend, @unchecked Sendable {
    struct State {
        var username: NormalizedUsername
        var accessKey: Data
        var sent: [SentMessage] = []
        var presence: [(content: BRPContent, to: [AccountId])] = []
        var streams: [FakeStream] = []
        var openFailures: [RelayError] = []
        var sendFailures: [(AccountId?, Error)] = []
        var directory: [String: AccountId] = [:]
        var uploads: [Data: Data] = [:]
        var downloads: [Data: Data] = [:]
        var downloadFailures: [Data: Error] = [:]
        var deletedMedia: [Data] = []
        var maintenanceRuns = 0
        var rotations = 0
        var identity: [AccountId: IdentityTrust] = [:]
        var approved: [AccountId] = []
        var needsAnnouncement = false
        var announced: [AccountId] = []
    }

    let accountId: AccountId
    let state: LockedState<State>
    /// The storage's account is kept in step with access-key rotations, as
    /// `Messenger` does through its account saver.
    var accountSaver: AccountSaver?

    init(accountId: AccountId = AccountId(uuid: UUID()), username: String = "tester", accessKey: Data = Data(repeating: 9, count: 16)) {
        self.accountId = accountId
        state = LockedState(State(username: try! NormalizedUsername(username), accessKey: accessKey))
    }

    var sent: [SentMessage] { state.withLock { $0.sent } }
    var presenceSends: [(content: BRPContent, to: [AccountId])] { state.withLock { $0.presence } }
    var latestStream: FakeStream? { state.withLock { $0.streams.last } }
    var streamCount: Int { state.withLock { $0.streams.count } }

    func sent(to peer: AccountId) -> [SentMessage] { sent.filter { $0.to == peer } }

    func failNextSend(to peer: AccountId? = nil, with error: Error) {
        state.withLock { $0.sendFailures.append((peer, error)) }
    }

    func failNextOpen(with error: RelayError) {
        state.withLock { $0.openFailures.append(error) }
    }

    func register(_ username: String, as account: AccountId) {
        state.withLock { $0.directory[username] = account }
    }

    func username() async -> NormalizedUsername { state.withLock { $0.username } }
    func currentAccessKey() async -> Data { state.withLock { $0.accessKey } }

    func openStream() async throws -> any RelayStream {
        try state.withLock { state -> FakeStream in
            if !state.openFailures.isEmpty { throw state.openFailures.removeFirst() }
            let stream = FakeStream()
            state.streams.append(stream)
            return stream
        }
    }

    func send(
        _ content: BRPContent, to recipient: AccountId, requestState: ConversationRequestState, theirAccessKey: Data?,
        ephemeral: Bool
    ) async throws -> SendOutcome {
        try state.withLock { state in
            if let index = state.sendFailures.firstIndex(where: { $0.0 == nil || $0.0 == recipient }) {
                throw state.sendFailures.remove(at: index).1
            }
            if ephemeral && theirAccessKey == nil { throw MessengerError.ephemeralRequiresAccessKey }
            if requestState == .blocked { throw MessengerError.blocked }
            state.sent.append(
                SentMessage(
                    content: content, to: recipient, requestState: requestState, accessKey: theirAccessKey,
                    ephemeral: ephemeral))
        }
        return SendOutcome(serverTimestamp: Int64(Date().timeIntervalSince1970 * 1000))
    }

    func sendPresence(_ presence: BRPContent, to contacts: [(account: AccountId, accessKey: Data)]) async throws
        -> [AccountId]
    {
        let accounts = contacts.map(\.account)
        state.withLock { $0.presence.append((presence, accounts)) }
        return accounts
    }

    func performMaintenance() async throws { state.withLock { $0.maintenanceRuns += 1 } }

    func rotateAccessKey() async throws -> Data {
        let key = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        let saver = accountSaver
        state.withLock {
            $0.accessKey = key
            $0.rotations += 1
        }
        if let saver, let account = savedAccount {
            var updated = account
            updated.accessKey = key
            try saver(updated)
        }
        return key
    }

    /// Set by tests so the fake can update the stored account like `Messenger`.
    var savedAccount: LocalAccount?

    func announceProfile(_ profile: BRPContent, to contacts: [(account: AccountId, theirAccessKey: Data?)]) async throws
        -> [AccountId]
    {
        state.withLock {
            $0.announced = contacts.map(\.account)
            $0.needsAnnouncement = false
        }
        return []
    }

    func needsProfileAnnouncement() async -> Bool { state.withLock { $0.needsAnnouncement } }

    func uploadAttachment(fileAt url: URL, kind: BRPAttachment.Kind, contentType: String, fileName: String?)
        async throws -> BRPAttachment
    {
        let bytes = try Data(contentsOf: url)
        var attachment = BRPAttachment()
        attachment.kind = kind
        attachment.mediaID = RandomId.make()
        attachment.deleteToken = Data(repeating: 3, count: 32)
        attachment.key = Data(repeating: 4, count: 64)
        attachment.digest = Data(repeating: 5, count: 32)
        attachment.size = UInt64(bytes.count)
        attachment.contentType = contentType
        if let fileName { attachment.fileName = fileName }
        state.withLock { $0.uploads[attachment.mediaID] = bytes }
        return attachment
    }

    func downloadAttachment(_ attachment: BRPAttachment, to destination: URL) async throws {
        let outcome: Result<Data, Error> = state.withLock { state in
            if let error = state.downloadFailures.removeValue(forKey: attachment.mediaID) { return .failure(error) }
            return .success(state.downloads[attachment.mediaID] ?? Data("media".utf8))
        }
        try outcome.get().write(to: destination)
    }

    func setDownload(_ bytes: Data, for mediaId: Data) { state.withLock { $0.downloads[mediaId] = bytes } }
    func failDownload(_ mediaId: Data, with error: Error) { state.withLock { $0.downloadFailures[mediaId] = error } }
    var deletedMedia: [Data] { state.withLock { $0.deletedMedia } }

    func deleteUploadedMedia(_ attachment: BRPAttachment) async throws {
        state.withLock { $0.deletedMedia.append(attachment.mediaID) }
    }

    func findAccount(username: NormalizedUsername) async throws -> AccountId? {
        state.withLock { $0.directory[username.value] }
    }

    func usernameOwner(_ username: NormalizedUsername) async throws -> AccountId? {
        state.withLock { $0.directory[username.value] }
    }

    func changeUsername(to username: NormalizedUsername) async throws { state.withLock { $0.username = username } }

    func safetyNumber(with contact: AccountId) async throws -> SafetyNumber? { nil }

    func identityTrust(for contact: AccountId) async throws -> IdentityTrust {
        state.withLock { $0.identity[contact] ?? .unknown }
    }

    func approveIdentityChange(for contact: AccountId) async throws {
        state.withLock { $0.approved.append(contact) }
    }

    func markVerified(_ verified: Bool, contact: AccountId) async throws {}
    func forgetContact(_ contact: AccountId) async throws {}

    func makeBackup(displayName: String, contacts: [BRPBackupContact]) async throws -> BRPAccountBackup {
        var backup = BRPAccountBackup()
        backup.version = AccountBackupFile.currentVersion
        backup.accountID = accountId.bytes
        backup.displayName = displayName
        backup.contacts = contacts
        return backup
    }

    func deleteAccount() async throws {}
}

// MARK: Engine fixture

/// A storage with an identity and a saved account, an engine on it, and a fake backend.
final class EngineFixture: @unchecked Sendable {
    let directory: TemporaryDirectory
    let storage: LocalStorage
    let backend: FakeBackend
    let engine: MessagingEngine
    let clock: ManualClock
    let key: SecretKeyMaterial
    let account: LocalAccount

    init(clock: ManualClock = ManualClock(), configuration: MessagingEngine.Configuration = .fast) throws {
        directory = try TemporaryDirectory()
        let directories = directory.directories
        try directories.prepare()
        let dek = randomKey()
        key = SecretKeyMaterial(copying: dek)
        storage = try LocalStorage.open(layout: directories.storage, dek: dek, now: clock.now()).storage
        _ = try storage.createIdentity(.generate(), registrationId: LocalIdentity.generateRegistrationId())
        let backend = FakeBackend()
        account = LocalAccount(
            accountId: backend.accountId, authToken: Data(repeating: 7, count: 32),
            username: try NormalizedUsername("tester"), accessKey: Data(repeating: 9, count: 16))
        try storage.accountSaver(account)
        backend.accountSaver = storage.accountSaver
        backend.savedAccount = account
        self.backend = backend
        self.clock = clock
        engine = try MessagingEngine(
            storage: storage, backend: backend, stateDirectory: directories.runtime, key: key, clock: clock,
            configuration: configuration)
    }

    deinit {
        storage.close()
    }

    func start() async {
        await engine.start()
    }

    /// Delivers `content` from `sender` as the relay would (already decrypted).
    func receive(
        _ content: BRPContent, from sender: AccountId, username: String? = nil, identityChanged: Bool = false,
        relayId: String = UUID().uuidString
    ) async throws {
        try await engine.receive(
            InboundMessage(
                relayId: relayId, serverTimestamp: Int64(clock.now().timeIntervalSince1970 * 1000), sender: sender,
                senderUsername: username.map { try! NormalizedUsername($0) }, content: content,
                identityChanged: identityChanged))
    }

    /// Makes `peer` an accepted contact holding our key and giving us theirs.
    @discardableResult
    func acceptedContact(
        _ peer: AccountId = AccountId(uuid: UUID()), theirKey: Data = Data(repeating: 2, count: 16),
        presenceSharing: Bool = true
    ) throws -> AccountId {
        let now = clock.now()
        try storage.write { tx in
            try tx.contacts.create(peer, state: .accepted, now: now)
            try tx.contacts.setTheirAccessKey(theirKey, for: peer)
            try tx.contacts.setOurAccessKeySent(account.accessKey, to: peer)
            try tx.contacts.setProfileSent(at: now, to: peer)
            var profile = ContactProfile()
            profile.presenceSharing = presenceSharing
            profile.displayName = "Peer"
            try tx.contacts.setProfile(profile, for: peer)
            _ = try tx.conversations.ensure(for: peer, now: now)
        }
        return peer
    }

    func outbox() throws -> [OutboxEntry] { try storage.read { try $0.outbox.all() } }

    func messages(_ peer: AccountId) async throws -> [MessageItem] { try await engine.messages(of: peer) }

    func contact(_ peer: AccountId) throws -> Contact? { try storage.read { try $0.contacts.get(peer) } }
}

extension MessagingEngine.Configuration {
    /// Short debounces for tests.
    static var fast: MessagingEngine.Configuration {
        var configuration = MessagingEngine.Configuration()
        configuration.presenceReplyDelay = 0
        return configuration
    }
}

// MARK: Content builders

extension BRPContent {
    static func data(_ payload: BRPDataMessage.OneOf_Payload, id: Data = RandomId.make(), expire: UInt32 = 0, viewOnce: Bool = false)
        -> BRPContent
    {
        BRPContent(data: BRPDataMessage(payload: payload, messageId: id, expireTimerSeconds: expire, viewOnce: viewOnce))
    }

    static func textMessage(_ text: String, id: Data = RandomId.make(), expire: UInt32 = 0) -> BRPContent {
        var proto = BRPText()
        proto.body = text
        return .data(.text(proto), id: id, expire: expire)
    }

    static func attachmentMessage(mediaId: Data = RandomId.make(), id: Data = RandomId.make(), viewOnce: Bool = false)
        -> BRPContent
    {
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = mediaId
        attachment.deleteToken = Data(repeating: 1, count: 32)
        attachment.key = Data(repeating: 2, count: 64)
        attachment.digest = Data(repeating: 3, count: 32)
        attachment.size = 5
        attachment.contentType = "image/jpeg"
        return .data(.attachment(attachment), id: id, viewOnce: viewOnce)
    }

    static func presence(_ state: BRPPresenceMessage.State, reply: Bool = false) -> BRPContent {
        var presence = BRPPresenceMessage()
        presence.state = state
        presence.validForSeconds = state == .online ? 300 : 0
        presence.reply = reply
        return BRPContent(presence: presence)
    }
}

/// A small JPEG with EXIF and GPS metadata, for the stripping test.
func makeJPEGWithMetadata(width: Int, height: Int, at url: URL) throws {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    guard
        let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { throw TestTimeout() }
    context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    guard let image = context.makeImage(),
        let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil)
    else { throw TestTimeout() }
    let properties: [CFString: Any] = [
        kCGImagePropertyGPSDictionary: [
            kCGImagePropertyGPSLatitude: 41.0082, kCGImagePropertyGPSLatitudeRef: "N",
            kCGImagePropertyGPSLongitude: 28.9784, kCGImagePropertyGPSLongitudeRef: "E",
        ],
        kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret place"],
        kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "PhoneMaker", kCGImagePropertyTIFFModel: "Model X"],
    ]
    CGImageDestinationAddImage(destination, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw TestTimeout() }
}
