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
    /// The token rotation being settled, shared by concurrent callers (§5.5 step 3).
    var tokenRotation: Task<TokenRotationOutcome, any Error>?
    /// The relay refused both tokens of a rotation settled in this process:
    /// ``finishPendingTokenRotation()`` keeps saying so, also once nothing
    /// is pending any more.
    var tokenRotationRefused = false
    private var recentRelayIds = RecentIds(capacity: 2048)
    /// Profile-picture state kept in memory only (seeds, search copies, the
    /// record write in progress; Messenger+Avatar.swift).
    var avatarSession = AvatarSession()

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
        // None while a token rotation is unresolved: see finishPendingTokenRotation().
        relay.setCredentials(account.usableCredentials)
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
    ///    blocked until ``approveIdentityChange(for:shownNumber:)``.
    /// 3. The auth token is rotated, so the old phone loses access, to a new
    ///    token chosen here: the account goes to `saveAccount` with the
    ///    backup's token and the new one as ``LocalAccount/pendingAuthToken``
    ///    before the relay hears of it, and once more, with only the token
    ///    the relay holds, when the answer says which one that is. If no
    ///    answer arrives (also not to the follow-up questions of step 3.4),
    ///    the restore still returns, with both tokens kept and none used:
    ///    ``finishPendingTokenRotation()`` settles it later, before anything
    ///    else is sent. `401` for both tokens throws
    ///    ``BackupRestoreError/backupNoLongerValid``, `400`
    ///    ``BackupRestoreError/tokenRefused``.
    /// 4. Fresh pre-keys are uploaded with `replaceAll`. A failure stays in
    ///    ``LocalAccount/pendingSetup`` and is retried by
    ///    ``performMaintenance(now:)`` and while ``run(_:deliver:onQueueEmpty:)``
    ///    is connected, until it succeeds.
    /// 5. The app then creates its contacts from `backup.contacts` (state,
    ///    access keys, `presence_sharing`) and sends a complete `ProfileUpdate`
    ///    to every accepted contact with ``announceProfile(_:to:)``: the
    ///    restored phone has no sessions until it writes first. The profile
    ///    picture and "Show in search" come back with the account, and once
    ///    the keys are uploaded the avatar record is brought in line with
    ///    them (``syncAvatarRecord()``; also by maintenance, if it could not).
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

        // §5.5 step 3.1: the new token is stored next to the backup's before
        // anything is sent, so whichever answer gets lost, one of them works.
        // The picture and its setting come back with the account; the relay's
        // record is brought in line once the keys are replaced (step 5). The
        // backup holds no version, so that write may be answered 409 first.
        let account = LocalAccount(
            accountId: restored.accountId, authToken: backup.authToken, username: restored.username,
            accessKey: backup.accessKey, pendingSetup: [.replacePreKeys, .announceProfile],
            pendingAuthToken: TokenRotation.newToken(),
            profileAvatar: ProfilePicture(backup.hasAvatar ? backup.avatar : nil).serializedProfileAvatar,
            avatarSearchOff: backup.avatarSearchOff, avatarRecordPending: true)
        try saveAccount(account)
        let messenger = Messenger(
            account: account, store: store, relay: relay, directory: directory, configuration: configuration,
            saveAccount: saveAccount)
        let outcome: TokenRotationOutcome?
        do {
            outcome = try await messenger.settleTokenRotation(firstAttempt: true)
        } catch {
            // The outcome is unknown (step 3.4): both tokens stay stored and
            // unused until finishPendingTokenRotation() finds it out.
            return messenger
        }
        switch outcome {
        case .committed?, nil: break
        case .unchanged?: throw BackupRestoreError.tokenRefused
        case .backupNoLongerValid?: throw BackupRestoreError.backupNoLongerValid
        }
        if (try? await messenger.completePendingSetup(now: now)) != nil {
            await messenger.syncAvatarRecord()
        }
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
    ///
    /// A token rotation whose outcome is unknown is settled first, so the
    /// backup carries the token the relay holds; a backup made with the other
    /// one could never be restored.
    public func makeBackup(
        displayName: String, contacts: [BRPBackupContact], now: Date = Date()
    ) async throws
        -> BRPAccountBackup
    {
        if try await finishPendingTokenRotation() == .backupNoLongerValid {
            throw BackupRestoreError.backupNoLongerValid
        }
        var backup = BRPAccountBackup()
        backup.version = AccountBackupFile.currentVersion
        backup.accountID = account.accountId.bytes
        backup.authToken = account.authToken
        backup.identityKeyPair = try store.identityKeyPair(context: NullContext()).serialize()
        backup.registrationID = try store.localRegistrationId(context: NullContext())
        backup.username = account.username.value
        backup.displayName = displayName
        backup.accessKey = account.accessKey
        if let avatar = account.profilePicture.profileAvatar { backup.avatar = avatar }
        backup.avatarSearchOff = account.avatarSearchOff
        backup.contacts = try contacts.map { contact in
            var contact = contact
            contact.identityKey = Data()
            contact.verified = false
            contact.identityUnaccepted = false
            // Only a picture that passes §8.9 goes into the file.
            if contact.hasAvatar, ProfilePicture.validated(contact.avatar) == nil { contact.clearAvatar() }
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
        // A file a restore would refuse is no backup: what is written is
        // checked by the same rules as what is read (§5.5 step 1), so local
        // data outside them fails the export (``MessengerError/invalidBackup``)
        // instead of producing a file that opens as damaged when needed.
        try Self.validate(backup)
        return backup
    }

    // MARK: Account (§4.3, §4.4)

    /// Exact-match directory lookup (§4.4); `nil` if nobody has this username.
    public func findAccount(username: NormalizedUsername) async throws -> IdentityResponse? {
        let hash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        let found = try await relay.lookup(usernameHash: hash)
        noteDirectoryAnswer(for: username, account: found?.accountId)
        return found
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
    /// ``BRPContent/profileUpdate(displayName:username:presenceSharing:picture:updatedAt:)``
    /// to every accepted contact (§8.7). The relay deleted the avatar record
    /// with the change; a published picture is sealed again under the new
    /// name in the background (§8.10).
    public func changeUsername(to username: NormalizedUsername) async throws {
        let hash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        try await relay.changeUsername(hash: hash)
        try updateAccount { $0.username = username }
        republishAvatarAfterUsernameChange()
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

    /// Saves `settled` and only then uses it: the relay client authenticates
    /// with its token, or with none while a token rotation is pending.
    func commitAccount(_ settled: LocalAccount) throws {
        try saveAccount(settled)
        account = settled
        relay.setCredentials(settled.usableCredentials)
    }

    /// `DELETE /v1/accounts/me`. A pending token rotation is settled first,
    /// so the request carries the token the relay holds.
    public func deleteAccount() async throws {
        try await finishPendingTokenRotation()
        try await relay.deleteAccount()
    }

    // MARK: Identity and safety numbers (§5)

    public func identityTrust(for contact: AccountId) throws -> IdentityTrust {
        try store.identityTrust(for: contact)
    }

    // Every call below that trusts or verifies a key is bound to the safety
    // number the user saw or compared, and checks it in the same step that
    // stores the decision: a key that changes meanwhile (§5) is never
    // accepted or sealed by a decision made about another key.

    /// Accepts a pending safety-number change without verifying it (sending
    /// resumes; the contact stays unverified) — only if the current key is
    /// the one whose number the user was shown (`shownNumber`,
    /// ``SafetyNumber/scannable``). Returns whether a change was accepted.
    public func approveIdentityChange(for contact: AccountId, shownNumber: Data) throws -> Bool {
        guard case .changed(_, let current) = try store.identityTrust(for: contact),
            try safetyNumber(with: contact, remoteIdentity: current).scannable == shownNumber
        else { return false }
        return try store.acceptIdentity(current, for: contact)
    }

    /// Marks `contact` verified after the user compared the safety number
    /// another way — only if it is still the number they compared
    /// (`comparedNumber`, ``SafetyNumber/scannable``). A pending change of
    /// that key is accepted with it. Returns `false` and changes nothing
    /// when the key changed since.
    public func markVerified(
        contact: AccountId, comparedNumber: Data, verification: IdentityVerification
    ) throws -> Bool {
        guard let remote = try store.identity(for: contact.protocolAddress, context: NullContext()),
            try safetyNumber(with: contact, remoteIdentity: remote).scannable == comparedNumber
        else { return false }
        return try store.verifyIdentity(remote, for: contact, verification: verification)
    }

    /// Compares a safety-number QR payload scanned from the contact's phone
    /// with the number of the stored key and, on a match, marks that key
    /// verified — in one step. Returns whether it matched and was verified.
    public func verifySafetyNumber(
        contact: AccountId, scanned: Data, verification: IdentityVerification
    ) throws -> Bool {
        guard let remote = try store.identity(for: contact.protocolAddress, context: NullContext()) else { return false }
        let number = try safetyNumber(with: contact, remoteIdentity: remote)
        guard (try? number.matches(scanned: scanned)) == true else { return false }
        return try store.verifyIdentity(remote, for: contact, verification: verification)
    }

    /// Pins the identity key a contact code committed to (§5): `identityKey`
    /// is the key the directory serves, after its fingerprint was found equal
    /// to the one in the code scanned side by side. See
    /// ``ProtocolStore/pinVerifiedIdentity(_:for:verification:)``; `false`
    /// when another key is stored (or the key is malformed).
    public func pinVerifiedIdentity(
        _ identityKey: Data, for contact: AccountId, verification: IdentityVerification
    ) throws -> Bool {
        guard contact != account.accountId, identityKey.count == 33,
            let key = try? IdentityKey(bytes: identityKey)
        else { return false }
        return try store.pinVerifiedIdentity(key, for: contact, verification: verification)
    }

    /// Removes the verification (the seal); the key stays accepted.
    public func clearVerification(contact: AccountId) throws {
        try store.setIdentityVerified(false, for: contact)
    }

    /// When and how the contact's current key was verified (`nil`: not
    /// verified, or verified without a record).
    public func identityVerification(for contact: AccountId) throws -> IdentityVerification? {
        try store.identityVerification(for: contact)
    }

    /// The safety number with `contact`, or `nil` before any key is known.
    public func safetyNumber(with contact: AccountId) throws -> SafetyNumber? {
        guard let remote = try store.identity(for: contact.protocolAddress, context: NullContext()) else { return nil }
        return try safetyNumber(with: contact, remoteIdentity: remote)
    }

    private func safetyNumber(with contact: AccountId, remoteIdentity: IdentityKey) throws -> SafetyNumber {
        let local = try store.identityKeyPair(context: NullContext()).identityKey
        return try SafetyNumber(
            local: account.accountId, localIdentity: local, remote: contact, remoteIdentity: remoteIdentity)
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
    /// disk; sizes above 2^40 bytes are refused outright. `progress` hears
    /// the fraction of the blob received (0…1) while it downloads.
    public func downloadAttachment(
        _ attachment: BRPAttachment, to destination: URL, deleteFromRelay: Bool = true,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        guard attachment.mediaID.count == RandomId.byteCount, attachment.size <= Self.maxAttachmentBytes else {
            throw AttachmentError.sizeMismatch
        }
        let blobURL = configuration.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".blob")
        defer { try? FileManager.default.removeItem(at: blobURL) }
        let blobSize = AttachmentCipher.blobSize(forPlaintextSize: attachment.size)
        var received: (@Sendable (UInt64) -> Void)?
        if let progress {
            received = { bytes in progress(blobSize > 0 ? min(1, Double(bytes) / Double(blobSize)) : 0) }
        }
        do {
            try await relay.downloadMedia(id: attachment.mediaID, to: blobURL, maxBytes: blobSize, progress: received)
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
            // A record write still owed goes out once per connection (§8.10).
            scheduleAvatarSync()
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
    /// An avatar-record write still owed (§8.10) is made after the setup.
    public func performMaintenance(now: Date = Date()) async throws {
        try await completePendingSetup(now: now)
        await syncAvatarRecord()
        try await maintainPreKeys(now: now)
    }

    private func completePendingSetup(now: Date) async throws {
        // Nothing reaches the relay before a token rotation is settled (§5.5 step 3.3).
        if try await finishPendingTokenRotation() == .backupNoLongerValid {
            throw BackupRestoreError.backupNoLongerValid
        }
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
