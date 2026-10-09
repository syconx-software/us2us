import Foundation
import LibSignalClient

public enum MessengerError: Error, Equatable, Sendable {
    /// The sealed envelope would exceed the relay's 64 KiB limit.
    case contentTooLarge
    case invalidContent(ContentValidationError)
    /// The conversation is blocked; nothing may be sent (§6.4).
    case blocked
    /// The encrypted attachment exceeds the relay's media limit.
    case attachmentTooLarge
    /// Ephemeral sends need the recipient's access key; proof-of-work is refused (§4.6).
    case ephemeralRequiresAccessKey
    case invalidBackup
}

/// A received and decrypted message (PROTOCOL.md §6.3).
public struct IncomingMessage: Sendable {
    /// Relay id, already acknowledged or to be acknowledged by the caller.
    public let relayId: String
    public let serverTimestamp: Int64
    public let sender: AccountId
    /// Present only when `sender_username` was verified against the directory.
    public let senderUsername: NormalizedUsername?
    public let content: BRPContent
    /// The sender's safety number changed with this message.
    public let identityChanged: Bool
}

/// Why an envelope was dropped. Dropped envelopes are acknowledged (§6.2).
public enum DropReason: Error, Equatable, Sendable {
    case envelope(SealedEnvelopeError)
    case sender(SenderVerificationError)
    case session(SessionError)
}

public enum InboundResult: Sendable {
    case message(IncomingMessage)
    /// Undecryptable or unverifiable: acknowledge and forget it.
    case dropped(DropReason)
    /// A transient failure (e.g. the identity lookup failed): do not
    /// acknowledge; the relay delivers it again.
    case retryLater(String)
}

/// The high-level client: registration with proof-of-work, directory lookup,
/// sending and receiving every content type, attachments, and key upkeep.
/// All libsignal state changes happen on this actor, one at a time.
public actor Messenger {
    public struct Configuration: Sendable {
        /// Relay `MEDIA_MAX_BYTES` (§4.9).
        public var maxMediaBytes: UInt64 = 64 * 1024 * 1024
        /// Where encrypted attachments are staged during upload and download.
        public var temporaryDirectory: URL = FileManager.default.temporaryDirectory

        public init() {}
    }

    public private(set) var account: LocalAccount
    public nonisolated let relay: RelayClient
    public nonisolated let store: any ProtocolStore
    private let cipher: SessionCipher
    private let verifier: SenderVerifier
    private let configuration: Configuration
    private let saveAccount: AccountSaver
    private var pendingSessions: [AccountId: Task<Void, Error>] = [:]
    private var recentRelayIds = RecentIds(capacity: 2048)

    /// - Parameter saveAccount: persists ``account`` whenever it changes.
    public init(
        account: LocalAccount, store: any ProtocolStore, relay: RelayClient,
        directory: (any IdentityDirectory)? = nil, configuration: Configuration = Configuration(),
        saveAccount: @escaping AccountSaver = { _ in }
    ) {
        self.account = account
        self.store = store
        self.relay = relay
        self.saveAccount = saveAccount
        self.cipher = SessionCipher(localAccount: account.accountId, store: store)
        self.verifier = SenderVerifier(
            directory: directory ?? RelayIdentityDirectory(relay: relay),
            storedIdentity: { [store] in try store.identity(for: $0.protocolAddress, context: NullContext()) })
        self.configuration = configuration
        relay.setCredentials(account.credentials)
    }

    // MARK: Registration and restore (§4.3, §5.5)

    /// Registers a new account. `store` must hold a freshly generated identity
    /// (see ``LocalIdentity``); this generates and uploads all pre-keys,
    /// solves the registration proof-of-work and sets up an access key.
    public static func register(
        username: NormalizedUsername, store: any ProtocolStore, relay: RelayClient,
        directory: (any IdentityDirectory)? = nil, configuration: Configuration = Configuration(),
        saveAccount: @escaping AccountSaver = { _ in }, now: Date = Date()
    ) async throws -> Messenger {
        let usernameHash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        let identity = try store.identityKeyPair(context: NullContext())
        let generator = PreKeyGenerator(store: store)
        let challenge = try await relay.powChallenge(for: .register)
        let solution = try await ProofOfWork.solve(challenge)
        let registration = RegistrationRequest(
            usernameHash: usernameHash,
            identityKey: identity.identityKey.serialize(),
            registrationId: try store.localRegistrationId(context: NullContext()),
            signedPreKey: try generator.generateSignedPreKey(now: now),
            kyberLastResortKey: try generator.generateLastResortKyberPreKey(now: now),
            oneTimePreKeys: try generator.generateOneTimePreKeys(),
            kyberOneTimePreKeys: try generator.generateOneTimeKyberPreKeys(now: now),
            pow: solution)
        let credentials = try await relay.register(registration)

        let account = LocalAccount(
            accountId: credentials.accountId, authToken: credentials.authToken, username: username,
            accessKey: AccessKey.generate(), pendingSetup: [.publishAccessKey])
        // The account exists from here on: save the credentials before anything else can fail.
        try saveAccount(account)
        let messenger = Messenger(
            account: account, store: store, relay: relay, directory: directory, configuration: configuration,
            saveAccount: saveAccount)
        // A failure is kept in `account.pendingSetup` for performMaintenance.
        try? await messenger.completePendingSetup(now: now)
        return messenger
    }

    /// Restores an account from a decrypted backup on a new phone, in the
    /// order of §5.5:
    ///
    /// 1. Verifies the **whole** backup (version, every field's size and
    ///    format) before anything is created or the relay is contacted, so a
    ///    bad backup changes nothing (``MessengerError/invalidBackup``).
    /// 2. `makeStore` creates the new store (inside the new vault, §5.2), and
    ///    the contacts' identity keys are written to it as they were: trusted
    ///    keys stay trusted, so nobody sees a safety-number change, and a
    ///    change the user had not accepted (`identity_unaccepted`) stays
    ///    blocked until ``approveIdentityChange(for:)``.
    /// 3. The auth token is rotated (the old phone loses access) and the
    ///    account is handed to `saveAccount` before anything else can fail.
    /// 4. Fresh pre-keys are uploaded with `replaceAll`. A failure stays in
    ///    ``LocalAccount/pendingSetup`` and is retried by
    ///    ``performMaintenance(now:)`` and while ``run(_:deliver:onQueueEmpty:)``
    ///    is connected, until it succeeds.
    /// 5. The app then creates its contacts from `backup.contacts` (state,
    ///    access keys, `presence_sharing`) and sends a complete `ProfileUpdate`
    ///    to every accepted contact with ``announceProfile(_:to:)``: the
    ///    restored phone has no sessions until it writes first.
    ///
    /// - Parameter makeStore: creates the new store for the restored identity
    ///   and registration id.
    public static func restore(
        from backup: BRPAccountBackup, relay: RelayClient,
        makeStore: (IdentityKeyPair, UInt32) throws -> any ProtocolStore,
        directory: (any IdentityDirectory)? = nil, configuration: Configuration = Configuration(),
        saveAccount: @escaping AccountSaver = { _ in }, now: Date = Date()
    ) async throws -> Messenger {
        let restored = try validate(backup)
        let store = try makeStore(restored.identity, backup.registrationID)
        for contact in restored.contacts {
            guard let identity = contact.identity else { continue }
            try store.restoreIdentity(
                identity, for: contact.account, approved: !contact.unaccepted, verified: contact.verified)
        }

        relay.setCredentials(RelayCredentials(accountId: restored.accountId, authToken: backup.authToken))
        let rotated = try await relay.rotateAuthToken()
        let account = LocalAccount(
            accountId: restored.accountId, authToken: rotated.authToken, username: restored.username,
            accessKey: backup.accessKey, pendingSetup: [.replacePreKeys, .announceProfile])
        // The backup's token is revoked now: never lose the new one, whatever fails next.
        try saveAccount(account)
        let messenger = Messenger(
            account: account, store: store, relay: relay, directory: directory, configuration: configuration,
            saveAccount: saveAccount)
        try? await messenger.completePendingSetup(now: now)
        return messenger
    }

    /// A backup that passed ``validate(_:)``.
    struct ValidatedBackup {
        struct Contact {
            let account: AccountId
            let identity: IdentityKey?
            let unaccepted: Bool
            let verified: Bool
        }

        let accountId: AccountId
        let identity: IdentityKeyPair
        let username: NormalizedUsername
        let contacts: [Contact]
    }

    /// Checks every field of a decrypted backup (version, sizes, formats) as
    /// §5.5 step 1 requires; throws ``MessengerError/invalidBackup`` otherwise.
    @discardableResult
    static func validate(_ backup: BRPAccountBackup) throws(MessengerError) -> ValidatedBackup {
        guard backup.version == AccountBackupFile.currentVersion, let accountId = AccountId(bytes: backup.accountID),
            backup.authToken.count == 32, backup.accessKey.count == AccessKey.byteCount,
            let username = try? NormalizedUsername(backup.username), username.value == backup.username,
            backup.displayName.utf8.count <= ContentLimits.maxDisplayNameBytes,
            let identity = try? IdentityKeyPair(bytes: backup.identityKeyPair),
            LocalIdentity.registrationIdRange.contains(backup.registrationID)
        else { throw .invalidBackup }

        var seen: Set<AccountId> = [accountId]
        let contacts = try backup.contacts.map { contact throws(MessengerError) -> ValidatedBackup.Contact in
            guard let account = AccountId(bytes: contact.accountID), seen.insert(account).inserted,
                contact.username.isEmpty
                    || (try? NormalizedUsername(contact.username))?.value == contact.username,
                contact.displayName.utf8.count <= ContentLimits.maxDisplayNameBytes,
                contact.theirAccessKey.isEmpty || contact.theirAccessKey.count == AccessKey.byteCount
            else { throw .invalidBackup }
            var key: IdentityKey?
            if !contact.identityKey.isEmpty {
                // libsignal tolerates trailing bytes after a key; the format does not.
                guard contact.identityKey.count == 33, let parsed = try? IdentityKey(bytes: contact.identityKey)
                else { throw .invalidBackup }
                key = parsed
            }
            guard key != nil || !contact.identityUnaccepted else { throw .invalidBackup }
            return ValidatedBackup.Contact(
                account: account, identity: key, unaccepted: contact.identityUnaccepted, verified: contact.verified)
        }
        return ValidatedBackup(accountId: accountId, identity: identity, username: username, contacts: contacts)
    }

    /// The `AccountBackup` for ``AccountBackupFile/export(_:password:)``. The app
    /// supplies its contacts (state, names, access keys, `presence_sharing`);
    /// their identity fields — the current key, `verified` and
    /// `identity_unaccepted` — are taken from the store, so an unaccepted
    /// safety-number change stays blocked on the new phone (§5.5).
    public func makeBackup(
        displayName: String, contacts: [BRPBackupContact], now: Date = Date()
    ) throws
        -> BRPAccountBackup
    {
        var backup = BRPAccountBackup()
        backup.version = AccountBackupFile.currentVersion
        backup.accountID = account.accountId.bytes
        backup.authToken = account.authToken
        backup.identityKeyPair = try store.identityKeyPair(context: NullContext()).serialize()
        backup.registrationID = try store.localRegistrationId(context: NullContext())
        backup.username = account.username.value
        backup.displayName = displayName
        backup.accessKey = account.accessKey
        backup.contacts = try contacts.map { contact in
            var contact = contact
            contact.identityKey = Data()
            contact.verified = false
            contact.identityUnaccepted = false
            guard let account = AccountId(bytes: contact.accountID) else { return contact }
            switch try store.identityTrust(for: account) {
            case .unknown:
                break
            case .trusted(let key, let verified):
                contact.identityKey = key.serialize()
                contact.verified = verified
            case .changed(_, let current):
                contact.identityKey = current.serialize()
                contact.identityUnaccepted = true
            }
            return contact
        }
        backup.createdAt = now.millisecondsSince1970
        return backup
    }

    // MARK: Account (§4.3, §4.4)

    /// Exact-match directory lookup (§4.4); `nil` if nobody has this username.
    public func findAccount(username: NormalizedUsername) async throws -> IdentityResponse? {
        let hash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        return try await relay.lookup(usernameHash: hash)
    }

    /// Checks a contact's username claim against the directory (§3.3): the
    /// result for ``ContactProfile/recordingUsernameCheck(_:owner:contact:)``. Run it
    /// lazily, e.g. when the conversation is opened, and only for an unchecked
    /// claim (``ContactProfile/needsUsernameCheck``), so a contact cannot use
    /// up our directory quota by announcing names. Lookups are cached as well.
    ///
    /// - Returns: the account the directory maps the username to, or `nil`.
    public func usernameOwner(_ username: NormalizedUsername) async throws -> AccountId? {
        try await verifier.directory.account(for: username, refresh: false)
    }

    /// Changes the username on the relay (§3.3). Afterwards send a complete
    /// ``BRPContent/profileUpdate(displayName:username:presenceSharing:avatar:updatedAt:)``
    /// to every accepted contact (§8.7).
    public func changeUsername(to username: NormalizedUsername) async throws {
        let hash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        try await relay.changeUsername(hash: hash)
        try updateAccount { $0.username = username }
    }

    /// Replaces our access key (e.g. after blocking someone, §6.4) and returns
    /// it. Send it to every remaining contact with ``BRPContent/accessKeyUpdate(_:)``.
    public func rotateAccessKey() async throws -> Data {
        let accessKey = AccessKey.generate()
        try await relay.setAccessKeyHash(AccessKey.hash(accessKey))
        try updateAccount { $0.accessKey = accessKey }
        return accessKey
    }

    /// Sends a complete `ProfileUpdate` (§8.7) to accepted contacts, one by one,
    /// and clears ``PendingRelaySetup/announceProfile``. Used after a restore
    /// (§5.5 step 5), once the keys are replaced: if that upload is still
    /// pending it is tried first, and its failure is thrown. Sends to contacts
    /// whose safety number changed are skipped (sending is blocked).
    ///
    /// - Parameter contacts: every accepted contact with the access key it gave us.
    /// - Returns: the contacts that could not be reached; send it to them later.
    @discardableResult
    public func announceProfile(
        _ profile: BRPContent, to contacts: [(account: AccountId, theirAccessKey: Data?)]
    ) async throws -> [AccountId] {
        guard case .control(let control) = profile.body, case .profile = control.action else {
            throw MessengerError.invalidContent(.malformedField("profile"))
        }
        if account.pendingSetup.contains(.replacePreKeys) {
            try await completePendingSetup(now: Date())
        }
        var unreached: [AccountId] = []
        for contact in contacts {
            do {
                try await send(profile, to: contact.account, requestState: .accepted, theirAccessKey: contact.theirAccessKey)
            } catch {
                unreached.append(contact.account)
            }
        }
        try updateAccount { $0.pendingSetup.remove(.announceProfile) }
        return unreached
    }

    /// Applies `change` to ``account`` and saves it.
    private func updateAccount(_ change: (inout LocalAccount) -> Void) throws {
        change(&account)
        try saveAccount(account)
    }

    public func deleteAccount() async throws {
        try await relay.deleteAccount()
    }

    // MARK: Identity and safety numbers (§5)

    public func identityTrust(for contact: AccountId) throws -> IdentityTrust {
        try store.identityTrust(for: contact)
    }

    /// Accepts a changed safety number, which unblocks sending.
    public func approveIdentityChange(for contact: AccountId) throws {
        if case .changed(_, let current) = try store.identityTrust(for: contact) {
            try store.approveIdentity(current, for: contact)
        }
    }

    public func markVerified(_ verified: Bool, contact: AccountId) throws {
        try store.setIdentityVerified(verified, for: contact)
    }

    /// The safety number with `contact`, or `nil` before any key is known.
    public func safetyNumber(with contact: AccountId) throws -> SafetyNumber? {
        guard let remote = try store.identity(for: contact.protocolAddress, context: NullContext()) else { return nil }
        let local = try store.identityKeyPair(context: NullContext()).identityKey
        return try SafetyNumber(
            local: account.accountId, localIdentity: local, remote: contact, remoteIdentity: remote)
    }

    // MARK: Sending (§4.6, §5, §6)

    /// Encrypts, seals and sends `content`.
    ///
    /// - Parameters:
    ///   - authorization: from ``MessageRequestPolicy/delivery(state:theirAccessKey:)``.
    ///   - includeSenderUsername: only while our request is pending (§6.4).
    ///   - ephemeral: deliver only if the recipient is online (typing, live-location
    ///     ticks, presence replies); requires an access key.
    @discardableResult
    public func send(
        _ content: BRPContent, to recipient: AccountId, authorization: DeliveryAuthorization,
        includeSenderUsername: Bool = false, ephemeral: Bool = false
    ) async throws -> SendMessageResponse {
        if ephemeral, case .proofOfWork = authorization { throw MessengerError.ephemeralRequiresAccessKey }
        do {
            try content.validateForSending()
        } catch {
            throw MessengerError.invalidContent(error)
        }
        try await ensureSession(with: recipient)
        let envelope = try cipher.seal(
            content, for: recipient, senderUsername: includeSenderUsername ? account.username : nil)
        guard envelope.count <= SealedEnvelope.maxEnvelopeBytes else { throw MessengerError.contentTooLarge }

        let relayAuthorization: SendAuthorization
        switch authorization {
        case .accessKey(let key):
            relayAuthorization = .accessKey(key)
        case .proofOfWork:
            let challenge = try await relay.powChallenge(for: .message(to: recipient))
            relayAuthorization = .proofOfWork(try await ProofOfWork.solve(challenge))
        }
        return try await relay.sendMessage(
            envelope, to: recipient, authorization: relayAuthorization, ephemeral: ephemeral)
    }

    /// Sends according to the §6.4 rules for the conversation's request state.
    @discardableResult
    public func send(
        _ content: BRPContent, to recipient: AccountId, requestState: ConversationRequestState,
        theirAccessKey: Data?, ephemeral: Bool = false
    ) async throws -> SendMessageResponse {
        guard let delivery = MessageRequestPolicy.delivery(state: requestState, theirAccessKey: theirAccessKey) else {
            throw MessengerError.blocked
        }
        return try await send(
            content, to: recipient, authorization: delivery.authorization,
            includeSenderUsername: delivery.includeSenderUsername, ephemeral: ephemeral)
    }

    /// Fans out a presence announcement (§8.6) to eligible contacts in as few
    /// `POST /v1/messages/ephemeral` requests as possible. Contacts without a
    /// usable session are skipped rather than consuming their one-time
    /// pre-keys for a status update. Returns the contacts addressed.
    @discardableResult
    public func sendPresence(
        _ presence: BRPContent, to contacts: [(account: AccountId, accessKey: Data)]
    ) async throws -> [AccountId] {
        guard case .presence = presence.body else { throw MessengerError.invalidContent(.malformedField("presence")) }
        var messages: [EphemeralMessage] = []
        var addressed: [AccountId] = []
        for contact in contacts {
            guard try cipher.hasUsableSession(with: contact.account) else { continue }
            do {
                let envelope = try cipher.seal(presence, for: contact.account)
                messages.append(EphemeralMessage(to: contact.account, accessKey: contact.accessKey, envelope: envelope))
                addressed.append(contact.account)
            } catch SessionError.identityChanged {
                continue  // blocked until the user accepts the new safety number
            }
        }
        try await relay.sendEphemeral(messages)
        return addressed
    }

    /// Fetches a bundle and starts a session unless a usable one exists.
    /// Concurrent sends to the same recipient share one bundle fetch, so no
    /// one-time pre-key is wasted.
    private func ensureSession(with recipient: AccountId) async throws {
        if try cipher.hasUsableSession(with: recipient) { return }
        if let pending = pendingSessions[recipient] {
            return try await pending.value
        }
        let task = Task { try await self.startSession(with: recipient) }
        pendingSessions[recipient] = task
        defer { pendingSessions[recipient] = nil }
        try await task.value
    }

    private func startSession(with recipient: AccountId) async throws {
        let bundle = try await relay.preKeyBundle(for: recipient)
        guard bundle.accountId == recipient else { throw SessionError.invalidBundle("accountId") }
        try cipher.processBundle(bundle)  // back on the actor
    }

    // MARK: Attachments (§7, §4.9)

    /// Encrypts the file at `url`, uploads the blob and returns the
    /// `Attachment` to put in a `DataMessage`. Strip EXIF/GPS before calling.
    public func uploadAttachment(
        fileAt url: URL, kind: BRPAttachment.Kind, contentType: String, fileName: String? = nil
    ) async throws -> BRPAttachment {
        // The blob size follows from the file size, so an oversized video is
        // refused before minutes of encryption rather than after.
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.uint64Value
        if let fileSize, AttachmentCipher.blobSize(forPlaintextSize: fileSize) > configuration.maxMediaBytes {
            throw MessengerError.attachmentTooLarge
        }
        let blobURL = configuration.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".blob")
        defer { try? FileManager.default.removeItem(at: blobURL) }
        // Off the actor: encrypting a large file must not hold up receiving.
        let info = try await Task.detached(priority: .userInitiated) {
            try AttachmentCipher.encryptFile(at: url, to: blobURL)
        }.value
        guard info.blobSize <= configuration.maxMediaBytes else { throw MessengerError.attachmentTooLarge }
        let upload = try await relay.uploadMedia(fileAt: blobURL)
        guard upload.mediaId.count == RandomId.byteCount, upload.deleteToken.count == 32 else {
            throw RelayError.invalidResponse
        }

        var attachment = BRPAttachment()
        attachment.kind = kind
        attachment.mediaID = upload.mediaId
        attachment.deleteToken = upload.deleteToken
        attachment.key = info.key
        attachment.digest = info.digest
        attachment.size = info.plaintextSize
        attachment.contentType = contentType
        if let fileName { attachment.fileName = fileName }
        return attachment
    }

    /// Downloads, verifies (digest and HMAC) and decrypts an attachment into
    /// `destination`, then deletes the blob from the relay, as recipients
    /// must once a download is complete (§4.9). The download stops at the blob
    /// size the declared `size` implies (§7), so a lying sender cannot fill the
    /// disk; sizes above 2^40 bytes are refused outright.
    public func downloadAttachment(
        _ attachment: BRPAttachment, to destination: URL, deleteFromRelay: Bool = true
    ) async throws {
        guard attachment.mediaID.count == RandomId.byteCount, attachment.size <= Self.maxAttachmentBytes else {
            throw AttachmentError.sizeMismatch
        }
        let blobURL = configuration.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".blob")
        defer { try? FileManager.default.removeItem(at: blobURL) }
        do {
            try await relay.downloadMedia(
                id: attachment.mediaID, to: blobURL,
                maxBytes: AttachmentCipher.blobSize(forPlaintextSize: attachment.size))
        } catch RelayError.payloadTooLarge {
            throw AttachmentError.sizeMismatch
        }
        try await Task.detached(priority: .userInitiated) {
            try AttachmentCipher.decryptFile(
                at: blobURL, to: destination, key: attachment.key, digest: attachment.digest, size: attachment.size)
        }.value
        if deleteFromRelay {
            // Best effort: the relay deletes it after its TTL anyway.
            try? await relay.deleteMedia(id: attachment.mediaID, deleteToken: attachment.deleteToken)
        }
    }

    // MARK: Receiving (§5.4, §6.3)

    /// Opens, verifies and decrypts one envelope. Never throws: the result
    /// says whether to acknowledge.
    public func process(_ envelope: RelayEnvelope) async -> InboundResult {
        let sealed: BRPSealedContent
        do {
            sealed = try SealedEnvelope.open(envelope.envelope, with: try store.identityKeyPair(context: NullContext()))
        } catch let error as SealedEnvelopeError {
            return .dropped(.envelope(error))
        } catch {
            return .retryLater("identity unavailable")
        }

        let sender: VerifiedSender
        do {
            sender = try await verifier.verify(sealed)
        } catch let error as SenderVerificationError {
            return .dropped(.sender(error))
        } catch {
            return .retryLater("sender verification: \(error)")
        }

        do {
            let decrypted = try cipher.decrypt(sealed)
            return .message(
                IncomingMessage(
                    relayId: envelope.id, serverTimestamp: envelope.serverTimestamp, sender: decrypted.sender,
                    senderUsername: sender.username, content: decrypted.content,
                    identityChanged: decrypted.identityChanged))
        } catch let error as SessionError {
            return .dropped(.session(error))
        } catch {
            return .retryLater("store: \(error)")
        }
    }

    /// Drains the mailbox over HTTP. Each message is handed to `deliver` and
    /// acknowledged only after `deliver` returns, so persist it there.
    /// Returns the number of delivered messages.
    @discardableResult
    public func receivePending(
        batchSize: Int = 100, deliver: @Sendable (IncomingMessage) async throws -> Void
    ) async throws -> Int {
        var delivered = 0
        while true {
            let page = try await relay.fetchMessages(limit: batchSize)
            var acknowledged: [String] = []
            for envelope in page.messages {
                switch try await handle(envelope, deliver: deliver) {
                case .delivered:
                    delivered += 1
                    acknowledged.append(envelope.id)
                case .discarded:
                    acknowledged.append(envelope.id)
                case .keep:
                    break
                }
            }
            try await relay.acknowledge(acknowledged)
            // Stop when nothing could be acknowledged, rather than refetching the same page.
            guard page.more, !acknowledged.isEmpty else { return delivered }
        }
    }

    /// Runs a foreground WebSocket until it closes: decrypts each message,
    /// hands it to `deliver`, then acknowledges it. Re-sent messages are
    /// de-duplicated by relay id (§4.7). Throws the connection's close error.
    ///
    /// Relay steps left in ``LocalAccount/pendingSetup`` (the key upload after
    /// a restore must eventually succeed, §5.5) are retried after the backlog
    /// and then with every `pong`, i.e. about every keep-alive interval.
    public func run(
        _ connection: RelayConnection,
        deliver: @Sendable (IncomingMessage) async throws -> Void,
        onQueueEmpty: @Sendable () async -> Void = {}
    ) async throws {
        // The event stream has a single consumer, so the socket is useless after this returns.
        defer { connection.close() }
        for try await event in connection.events {
            try await handle(
                event, deliver: deliver, onQueueEmpty: onQueueEmpty,
                acknowledge: { try await connection.acknowledge($0) })
        }
    }

    /// One event of ``run(_:deliver:onQueueEmpty:)``.
    func handle(
        _ event: RelayEvent,
        deliver: @Sendable (IncomingMessage) async throws -> Void,
        onQueueEmpty: @Sendable () async -> Void,
        acknowledge: @Sendable ([String]) async throws -> Void
    ) async throws {
        switch event {
        case .message(let envelope):
            switch try await handle(envelope, deliver: deliver) {
            case .delivered, .discarded: try await acknowledge([envelope.id])
            case .keep: break
            }
        case .queueEmpty:
            await retryPendingSetup()
            await onQueueEmpty()
        case .pong:
            await retryPendingSetup()
        }
    }

    private func retryPendingSetup() async {
        guard !account.pendingSetup.isDisjoint(with: .relayOnly) else { return }
        try? await completePendingSetup(now: Date())
    }

    private enum Handling {
        case delivered
        /// Dropped or already handled: acknowledge without delivering.
        case discarded
        /// Transient failure: leave it on the relay.
        case keep
    }

    private func handle(
        _ envelope: RelayEnvelope, deliver: @Sendable (IncomingMessage) async throws -> Void
    ) async throws -> Handling {
        if recentRelayIds.contains(envelope.id) { return .discarded }
        switch await process(envelope) {
        case .message(let message):
            try await deliver(message)
            recentRelayIds.insert(envelope.id)
            return .delivered
        case .dropped:
            recentRelayIds.insert(envelope.id)
            return .discarded
        case .retryLater:
            return .keep
        }
    }

    // MARK: Maintenance (§5, §5.5)

    /// Brings the relay in line with this installation; call on every app
    /// start. Finishes the relay steps of ``LocalAccount/pendingSetup`` (not
    /// ``PendingRelaySetup/announceProfile``, which needs the app's contacts), refills one-time pre-keys
    /// below 20 back to 100, rotates the signed and Kyber last-resort keys every
    /// 30 days, and deletes replaced ones 30 days after their replacement.
    public func performMaintenance(now: Date = Date()) async throws {
        try await completePendingSetup(now: now)
        try await maintainPreKeys(now: now)
    }

    private func completePendingSetup(now: Date) async throws {
        if account.pendingSetup.contains(.publishAccessKey) {
            try await relay.setAccessKeyHash(AccessKey.hash(account.accessKey))
            try updateAccount { $0.pendingSetup.remove(.publishAccessKey) }
        }
        if account.pendingSetup.contains(.replacePreKeys) {
            let generator = PreKeyGenerator(store: store)
            try await relay.uploadKeys(
                KeyUploadRequest(
                    signedPreKey: try generator.generateSignedPreKey(now: now),
                    kyberLastResortKey: try generator.generateLastResortKyberPreKey(now: now),
                    oneTimePreKeys: try generator.generateOneTimePreKeys(),
                    kyberOneTimePreKeys: try generator.generateOneTimeKyberPreKeys(now: now),
                    replaceAll: true))
            try updateAccount { $0.pendingSetup.remove(.replacePreKeys) }
        }
    }

    private func maintainPreKeys(now: Date) async throws {
        let status = try await relay.keyStatus()
        let generator = PreKeyGenerator(store: store)
        var upload = KeyUploadRequest()
        let target = PreKeyGenerator.oneTimeBatchSize
        if status.oneTimePreKeys < PreKeyMaintenance.refillThreshold {
            upload.oneTimePreKeys = try generator.generateOneTimePreKeys(count: target - status.oneTimePreKeys)
        }
        if status.kyberOneTimePreKeys < PreKeyMaintenance.refillThreshold {
            upload.kyberOneTimePreKeys = try generator.generateOneTimeKyberPreKeys(
                count: target - status.kyberOneTimePreKeys, now: now)
        }

        let signed = try store.signedPreKeys().map { PreKeyMaintenance.Key(id: $0.id, timestamp: $0.timestamp) }
        if PreKeyMaintenance.needsRotation(keys: signed, relayCurrentId: status.signedPreKeyId, now: now) {
            upload.signedPreKey = try generator.generateSignedPreKey(now: now)
        }
        let lastResort = try store.lastResortKyberPreKeys().map {
            PreKeyMaintenance.Key(id: $0.id, timestamp: $0.timestamp)
        }
        if PreKeyMaintenance.needsRotation(keys: lastResort, relayCurrentId: status.kyberLastResortKeyId, now: now) {
            upload.kyberLastResortKey = try generator.generateLastResortKyberPreKey(now: now)
        }
        if !upload.isEmpty {
            try await relay.uploadKeys(upload)
        }

        for id in PreKeyMaintenance.expired(
            keys: try store.signedPreKeys().map {
                PreKeyMaintenance.Key(id: $0.id, timestamp: $0.timestamp)
            }, now: now)
        {
            try store.removeSignedPreKey(id: id)
        }
        for id in PreKeyMaintenance.expired(
            keys: try store.lastResortKyberPreKeys().map {
                PreKeyMaintenance.Key(id: $0.id, timestamp: $0.timestamp)
            }, now: now)
        {
            try store.removeKyberPreKey(id: id)
        }
    }

    /// Forgets a contact's session and identity (ghost mode `wipe_contact`, §8.2).
    public func forgetContact(_ contact: AccountId) throws {
        try store.deleteContact(contact)
    }
}

extension Messenger {
    /// Larger declared attachment sizes are refused (§7 allows 2^40 as the bound).
    static let maxAttachmentBytes: UInt64 = 1 << 40
}

/// Rotation rules for signed and Kyber last-resort pre-keys (§5).
enum PreKeyMaintenance {
    static let refillThreshold = 20
    static let rotationInterval: TimeInterval = 30 * 24 * 3600
    /// Replaced keys stay this long, to decrypt late messages.
    static let retention: TimeInterval = 30 * 24 * 3600

    struct Key {
        let id: UInt32
        /// Milliseconds since the Unix epoch.
        let timestamp: UInt64

        var created: Date { Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000) }
    }

    /// The newest key is current. Rotate when there is none, when it is older
    /// than 30 days, or when the relay does not serve it.
    static func needsRotation(keys: [Key], relayCurrentId: UInt32?, now: Date) -> Bool {
        guard let current = keys.max(by: { $0.timestamp < $1.timestamp }) else { return true }
        return now.timeIntervalSince(current.created) >= rotationInterval || relayCurrentId != current.id
    }

    /// Keys that were replaced more than 30 days ago (a key counts as replaced
    /// when the next newer key was created).
    static func expired(keys: [Key], now: Date) -> [UInt32] {
        let newestFirst = keys.sorted { $0.timestamp > $1.timestamp }
        return zip(newestFirst, newestFirst.dropFirst()).compactMap { newer, older in
            now.timeIntervalSince(newer.created) >= retention ? older.id : nil
        }
    }
}

/// A bounded set of recently handled relay ids, for de-duplication (§4.7).
struct RecentIds {
    private let capacity: Int
    private var order: [String] = []
    private var members: Set<String> = []

    init(capacity: Int) {
        self.capacity = capacity
    }

    func contains(_ id: String) -> Bool { members.contains(id) }

    mutating func insert(_ id: String) {
        guard members.insert(id).inserted else { return }
        order.append(id)
        if order.count > capacity {
            members.remove(order.removeFirst())
        }
    }
}

extension MessengerError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .contentTooLarge: return localizer.string(.messengerContentTooLarge)
        case .invalidContent: return localizer.string(.messengerInvalidContent)
        case .blocked: return localizer.string(.relayAccessDenied)
        case .attachmentTooLarge: return localizer.string(.attachmentTooLarge)
        case .ephemeralRequiresAccessKey: return localizer.string(.relayAccessDenied)
        case .invalidBackup: return localizer.string(.backupInvalidFile)
        }
    }
}

extension SessionError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .identityChanged: return Localizer.current.string(.sessionIdentityChanged)
        default: return Localizer.current.string(.messengerInvalidContent)
        }
    }
}
