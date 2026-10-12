import Foundation
import Us2usKit
import Us2usStorage

/// What changed, for the observable facade.
public enum EngineChange: Sendable, Equatable {
    /// The chat list (conversations, requests, contacts) changed.
    case conversations
    /// The messages of one conversation changed.
    case messages(AccountId)
    case presence
    case typing
    /// A new message arrived (for an in-app banner when it is not on screen).
    case newMessage(AccountId)
    /// A conversation was wiped (ghost mode, `ConversationWipe`, or locally).
    case wiped(AccountId)
    /// A safety number changed; sending to this contact is blocked until accepted.
    case safetyNumberChanged(AccountId)
    /// An attachment of the message is downloading: the fraction received
    /// (0…1), at most every 2 %.
    case downloadProgress(MessageId, AccountId, Double)
}

public enum EngineError: Error, Equatable, Sendable {
    /// No such contact or conversation.
    case unknownContact
    /// Not allowed in the contact's request state (accepting what is not a
    /// request, answering a request before accepting it, sending to a
    /// blocked contact …).
    case notAllowed
    /// The message cannot be acted on (not ours, deleted, not view-once …).
    case invalidMessage
    /// The media of the message is not available on this phone.
    case mediaUnavailable
    /// Text, caption or another field over its protocol limit.
    case tooLarge
    /// The username is not registered (exact match only, §4.4).
    case usernameNotFound
    /// Starting a conversation with ourselves.
    case ownAccount
    /// The engine is stopped (the vault locked).
    case stopped
}

/// The messaging core: one actor that drives Us2usStorage (all records,
/// atomic transactions, crypto-shredding) and Us2usKit's `Messenger` (all
/// crypto and relay calls). Inbound messages, user actions, timers and the
/// outbox never race each other.
///
/// Responsibilities (PROTOCOL.md §5.4, §6.4, §8):
/// * inbound: one storage transaction per message (with the decryption's
///   ratchet state, see ``MessengerStream``), request state machine,
///   receipts, reactions, deletes, view-once, live location, LWW settings,
///   wipes, screenshots, profiles, presence;
/// * the durable outbox: per-contact order, delivery receipts merged into one
///   `ReceiptMessage` (≤ 1000 ids), uploads first, classified retries;
/// * presence fan-out, replies, the 240 s refresh and OFFLINE (§8.6);
/// * ghost and disappearing deadlines; media downloads after acceptance;
/// * access-key rotation on block and the profile exchange after acceptance.
public actor MessagingEngine {
    public struct Configuration: Sendable {
        /// Debounce before presence replies go out in one request.
        public var presenceReplyDelay: TimeInterval = 0.5
        /// How long a typing indicator stays without a refresh.
        public var typingTimeout: TimeInterval = 15
        /// Minimum spacing of our own "typing started" sends.
        public var typingResendInterval: TimeInterval = 5
        /// Pre-key maintenance at most this often while connected.
        public var maintenanceInterval: TimeInterval = 6 * 3600
        /// Messages loaded per conversation for the UI.
        public var pageSize = 500
        /// Outbox retries (Us2usStorage `RetryPolicy`).
        public var retryPolicy = RetryPolicy.standard

        public init() {}
    }

    let storage: LocalStorage
    let backend: any MessagingBackend
    let clock: any RuntimeClock
    let configuration: Configuration
    let stateFile: RuntimeStateFile
    let notices: NoticeStore

    var runtime: RuntimeStateData
    var presence = PresenceTracker()
    var typing: [AccountId: Date] = [:]
    var lastTypingSent: [AccountId: Date] = [:]
    /// Our live-location sessions (§8.3): session id → peer and end.
    var liveSessions: [Data: (peer: AccountId, expiresAt: Date)] = [:]
    /// Contacts whose changed safety number blocks sending.
    var held: Set<AccountId> = []
    var running = false
    var connected = false
    var lastMaintenance: Date?

    let outboxWake = WakeSignal()
    let schedulerWake = WakeSignal()
    var tasks: [Task<Void, Never>] = []
    var presenceReplyTask: Task<Void, Never>?
    var pendingPresenceReplies: Set<AccountId> = []
    var downloadTask: Task<Void, Never>?
    var downloadAgain = false
    /// Attachments the user asked for while auto-download is off.
    var requestedDownloads: Set<Data> = []
    /// Uploads that failed transiently: not before this time.
    var uploadBackoff: [Int64: (attempts: Int, notBefore: Date)] = [:]
    /// Outbox entries handed to the relay right now (their fate is unknown
    /// until the call returns): an unsend must then send a `Delete`.
    var inFlightOutbox: Set<Int64> = []
    /// Decrypted copies handed out by ``exportMedia(_:peer:)``: deleted as
    /// soon as their message is gone (expired, deleted, wiped) and on stop.
    var exportedMedia: [URL: (conversation: ConversationId, message: MessageId)] = [:]
    /// The last ONLINE fan-out (§8.6); a reconnect within its validity does
    /// not announce again.
    var lastOnlineAnnouncement: Date?

    /// Every change, in order, for the facade.
    public nonisolated let changes: AsyncStream<EngineChange>
    let changeContinuation: AsyncStream<EngineChange>.Continuation

    /// - Parameters:
    ///   - stateDirectory: where the runtime state and notice items live
    ///     (inside the app's protected, backup-excluded directory).
    ///   - key: the DEK; sub-keys are derived from it and kept while running.
    public init(
        storage: LocalStorage, backend: any MessagingBackend, stateDirectory: URL, key: SecretKeyMaterial,
        clock: any RuntimeClock = SystemClock(), configuration: Configuration = Configuration()
    ) throws {
        self.storage = storage
        self.backend = backend
        self.clock = clock
        self.configuration = configuration
        try FileManager.default.createDirectory(
            at: stateDirectory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        self.stateFile = try RuntimeStateFile(directory: stateDirectory, dek: key)
        self.notices = try NoticeStore(
            directory: stateDirectory.appendingPathComponent("notices", isDirectory: true), dek: key)
        self.runtime = stateFile.load()
        (changes, changeContinuation) = AsyncStream.makeStream(of: EngineChange.self, bufferingPolicy: .unbounded)
    }

    deinit {
        changeContinuation.finish()
    }

    var me: AccountId { backend.accountId }
    var settings: UserSettings { runtime.settings }

    func emit(_ change: EngineChange) {
        switch change {
        case .messages, .wiped, .conversations: discardStaleExports()
        default: break
        }
        changeContinuation.yield(change)
    }

    /// Deletes decrypted copies whose message is no longer shown (expired,
    /// deleted for me or for everyone, wiped, its file gone): §8.1
    /// "attachments … are deleted with it" holds for plaintext copies too.
    func discardStaleExports() {
        guard !exportedMedia.isEmpty else { return }
        let now = clock.now()
        for (url, reference) in exportedMedia {
            let alive = (try? read { tx -> Bool in
                guard let message = try tx.messages.get(reference.message, in: reference.conversation, now: now),
                    !message.isDeleted, message.content != nil
                else { return false }
                return try tx.attachments.forMessage(reference.message, in: reference.conversation).contains(where: \.hasFile)
            }) ?? false
            if !alive || !FileManager.default.fileExists(atPath: url.path) {
                try? FileManager.default.removeItem(at: url)
                exportedMedia[url] = nil
            }
        }
    }

    func saveRuntime() {
        do {
            try stateFile.save(runtime)
        } catch {
            RuntimeLog.messaging.error("runtime state not saved: \(error.logLabel, privacy: .public)")
        }
    }

    // MARK: Lifecycle

    /// Starts after an unlock (§5.4 step 1). `openReport` is what opening the
    /// storage already wiped (overdue ghost conversations, expired messages).
    public func start(openReport: WipeReport? = nil) async {
        guard !running else { return }
        running = true
        if let openReport { handleWipeReport(openReport) }
        collectNoticeGarbage()
        resetInterruptedTransfers()
        refreshHeld()
        // Before the outbox loop runs, so the stops go out with the first sends.
        stopOrphanedLiveLocations()
        tasks.append(Task { [weak self] in await self?.outboxLoop() })
        tasks.append(Task { [weak self] in await self?.schedulerLoop() })
        tasks.append(Task { [weak self] in await self?.presenceRefreshLoop() })
        startDownloads()
    }

    /// Stops everything (the vault is locking). Pending work stays in the
    /// database and resumes after the next unlock. Keys derived from the DEK
    /// are zeroed.
    public func stop() async {
        running = false
        connected = false
        for task in tasks { task.cancel() }
        presenceReplyTask?.cancel()
        downloadTask?.cancel()
        let all = tasks + [presenceReplyTask, downloadTask].compactMap { $0 }
        tasks = []
        presenceReplyTask = nil
        downloadTask = nil
        for task in all { await task.value }
        presence = PresenceTracker()
        typing = [:]
        liveSessions = [:]
        lastOnlineAnnouncement = nil
        for url in exportedMedia.keys { try? FileManager.default.removeItem(at: url) }
        exportedMedia = [:]
        stateFile.close()
        notices.close()
    }

    /// The system takes the background time back while the engine is still
    /// stopping (a slow network): zero every key derived from the DEK now,
    /// from any thread. Later writes of the runtime state and notices fail.
    public nonisolated func wipeDerivedKeys() {
        stateFile.close()
        notices.close()
    }

    /// The connection received its backlog (`queue_empty`): batched receipts
    /// and the outbox go out, presence is announced, keys topped up, and a
    /// restore's profile announcement finished.
    public func connectionReachedQueueEmpty() async {
        guard running else { return }
        connected = true
        outboxWake.signal()
        // A connection that keeps dropping right after its backlog must not
        // fan ONLINE out to every contact each time (§8.6, §1.1): within
        // the refresh interval the last announcement is still valid.
        let now = clock.now()
        if lastOnlineAnnouncement.map({ now.timeIntervalSince($0) >= PresencePolicy.refreshInterval }) ?? true {
            lastOnlineAnnouncement = now
            await announceOnline()
        }
        await finishRestoreAnnouncementIfNeeded()
        await maintainKeysIfDue()
        startDownloads()
    }

    public func connectionClosed() {
        connected = false
    }

    /// End of the background grace period (§5.4 step 3): OFFLINE to the
    /// contacts we see online.
    public func goingOffline() async {
        guard running else { return }
        await announceOffline()
    }

    // MARK: Helpers

    func read<T>(_ body: (StorageTransaction) throws -> T) throws -> T {
        try storage.read(body)
    }

    func write<T>(_ body: (StorageTransaction) throws -> T) throws -> T {
        try storage.write(body)
    }

    /// Our current access key (saved by `Messenger` into the database).
    func ourAccessKey(_ tx: StorageTransaction) throws -> Data {
        guard let account = try tx.account.load() else { throw EngineError.stopped }
        return account.accessKey
    }

    func isPresenceEligible(_ contact: Contact) -> Bool {
        PresencePolicy.isEligible(
            state: contact.requestState, weShare: settings.presenceSharing, theyShare: contact.presenceSharing)
            && contact.theirAccessKey != nil && !contact.pendingForget
    }

    func refreshHeld() {
        let contacts = (try? read { try $0.contacts.all() }) ?? []
        held = Set(contacts.filter(\.identityChanged).map(\.accountId))
    }

    /// Interrupted transfers (the app locked or crashed mid-way) start over.
    func resetInterruptedTransfers() {
        _ = try? write { tx in
            for attachment in try tx.attachments.inStates([.downloading], limit: 1000) {
                try tx.attachments.setState(.pendingDownload, of: attachment.id)
            }
            for attachment in try tx.attachments.inStates([.uploading], limit: 1000) {
                try tx.attachments.setState(.pendingUpload, of: attachment.id)
            }
        }
    }
}
