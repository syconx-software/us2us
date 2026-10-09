import Foundation
import Us2usKit
import Us2usStorage

/// A received, verified and decrypted message (Us2usKit `IncomingMessage`).
public struct InboundMessage: Sendable {
    public let relayId: String
    public let serverTimestamp: Int64
    public let sender: AccountId
    /// Set only when `sender_username` was verified against the directory (§6.3).
    public let senderUsername: NormalizedUsername?
    public let content: BRPContent
    /// The sender's safety number changed with this message.
    public let identityChanged: Bool

    public init(
        relayId: String, serverTimestamp: Int64, sender: AccountId, senderUsername: NormalizedUsername?,
        content: BRPContent, identityChanged: Bool
    ) {
        self.relayId = relayId
        self.serverTimestamp = serverTimestamp
        self.sender = sender
        self.senderUsername = senderUsername
        self.content = content
        self.identityChanged = identityChanged
    }

    init(_ message: IncomingMessage) {
        self.init(
            relayId: message.relayId, serverTimestamp: message.serverTimestamp, sender: message.sender,
            senderUsername: message.senderUsername, content: message.content, identityChanged: message.identityChanged)
    }
}

/// One open WebSocket (§4.7): the backlog, `queue_empty`, then live messages.
public protocol RelayStream: Sendable {
    /// Runs until the socket closes. Each message is handed to `deliver` and
    /// acknowledged after `deliver` returns; re-sent frames are de-duplicated
    /// by relay id. Returns normally after ``close()``; throws the close error
    /// otherwise (`RelayError.connectionReplaced`, `.server(status: 401, …)`, …).
    func run(
        deliver: @escaping @Sendable (InboundMessage) async throws -> Void,
        onQueueEmpty: @escaping @Sendable () async -> Void
    ) async throws
    func close()
}

/// The relay accepted a send (`202`).
public struct SendOutcome: Sendable, Equatable {
    public let serverTimestamp: Int64

    public init(serverTimestamp: Int64) {
        self.serverTimestamp = serverTimestamp
    }
}

/// Everything the runtime does over the network or with libsignal state,
/// so the runtime can be tested without a relay. ``MessengerBackend`` is the
/// production implementation on Us2usKit's `Messenger`.
public protocol MessagingBackend: Sendable {
    var accountId: AccountId { get }
    func username() async -> NormalizedUsername
    func currentAccessKey() async -> Data

    func openStream() async throws -> any RelayStream
    func send(
        _ content: BRPContent, to recipient: AccountId, requestState: ConversationRequestState, theirAccessKey: Data?,
        ephemeral: Bool
    ) async throws -> SendOutcome
    /// One `POST /v1/messages/ephemeral` fan-out (§8.6); returns who was addressed.
    func sendPresence(_ presence: BRPContent, to contacts: [(account: AccountId, accessKey: Data)]) async throws
        -> [AccountId]
    /// Pending relay setup, pre-key refill (< 20 → 100) and signed / Kyber
    /// last-resort rotation (30 days, §5).
    func performMaintenance() async throws
    /// A new access key on the relay (§6.4 block); returns it.
    func rotateAccessKey() async throws -> Data
    /// Completes `PendingRelaySetup.announceProfile` after a restore (§5.5 step 5).
    func announceProfile(_ profile: BRPContent, to contacts: [(account: AccountId, theirAccessKey: Data?)]) async throws
        -> [AccountId]
    func needsProfileAnnouncement() async -> Bool

    func uploadAttachment(fileAt url: URL, kind: BRPAttachment.Kind, contentType: String, fileName: String?)
        async throws -> BRPAttachment
    func downloadAttachment(_ attachment: BRPAttachment, to destination: URL) async throws
    /// Unsend: deletes our uploaded blob from the relay (§4.9).
    func deleteUploadedMedia(_ attachment: BRPAttachment) async throws

    func findAccount(username: NormalizedUsername) async throws -> AccountId?
    func usernameOwner(_ username: NormalizedUsername) async throws -> AccountId?
    /// `PUT /v1/accounts/me/username` (§3.3); the account is saved with the new name.
    func changeUsername(to username: NormalizedUsername) async throws

    func safetyNumber(with contact: AccountId) async throws -> SafetyNumber?
    func identityTrust(for contact: AccountId) async throws -> IdentityTrust
    func approveIdentityChange(for contact: AccountId) async throws
    func markVerified(_ verified: Bool, contact: AccountId) async throws
    func forgetContact(_ contact: AccountId) async throws

    func makeBackup(displayName: String, contacts: [BRPBackupContact]) async throws -> BRPAccountBackup
    func deleteAccount() async throws
}

/// ``RelayStream`` on Us2usKit's `RelayConnection` + `Messenger.run`.
///
/// Atomic receive: the run is wrapped in Us2usStorage's
/// `withStagedInboundWrites`, so the libsignal state a decryption advances is
/// committed in the same SQLite transaction that stores the message
/// (``MessagingEngine/receive(_:)`` does one write per message). A crash in
/// between can then neither lose the message nor leave a ratchet that rejects
/// the relay's redelivery.
struct MessengerStream: RelayStream {
    let messenger: Messenger
    let connection: RelayConnection
    let protocolStore: PersistentProtocolStore

    func run(
        deliver: @escaping @Sendable (InboundMessage) async throws -> Void,
        onQueueEmpty: @escaping @Sendable () async -> Void
    ) async throws {
        try await protocolStore.withStagedInboundWrites {
            try await messenger.run(
                connection, deliver: { message in try await deliver(InboundMessage(message)) },
                onQueueEmpty: onQueueEmpty)
        }
    }

    func close() {
        connection.close()
    }
}

/// The production backend: Us2usKit's `Messenger` (one actor for all
/// libsignal state) and its `RelayClient`.
public struct MessengerBackend: MessagingBackend {
    public let messenger: Messenger
    public let accountId: AccountId
    public let protocolStore: PersistentProtocolStore
    /// Seconds between `{"type":"ping"}` frames; §4.7 requires at least every
    /// 30 s, 25 leaves room for scheduling delays.
    public var keepAliveInterval: TimeInterval = 25

    public init(messenger: Messenger, accountId: AccountId, protocolStore: PersistentProtocolStore) {
        self.messenger = messenger
        self.accountId = accountId
        self.protocolStore = protocolStore
    }

    public func username() async -> NormalizedUsername { await messenger.account.username }
    public func currentAccessKey() async -> Data { await messenger.account.accessKey }

    public func openStream() async throws -> any RelayStream {
        MessengerStream(
            messenger: messenger, connection: try messenger.relay.connect(keepAliveInterval: keepAliveInterval),
            protocolStore: protocolStore)
    }

    public func send(
        _ content: BRPContent, to recipient: AccountId, requestState: ConversationRequestState, theirAccessKey: Data?,
        ephemeral: Bool
    ) async throws -> SendOutcome {
        let response = try await messenger.send(
            content, to: recipient, requestState: requestState, theirAccessKey: theirAccessKey, ephemeral: ephemeral)
        return SendOutcome(serverTimestamp: response.serverTimestamp)
    }

    public func sendPresence(_ presence: BRPContent, to contacts: [(account: AccountId, accessKey: Data)]) async throws
        -> [AccountId]
    {
        guard !contacts.isEmpty else { return [] }
        return try await messenger.sendPresence(presence, to: contacts)
    }

    public func performMaintenance() async throws { try await messenger.performMaintenance() }
    public func rotateAccessKey() async throws -> Data { try await messenger.rotateAccessKey() }

    public func announceProfile(_ profile: BRPContent, to contacts: [(account: AccountId, theirAccessKey: Data?)])
        async throws -> [AccountId]
    {
        try await messenger.announceProfile(profile, to: contacts)
    }

    public func needsProfileAnnouncement() async -> Bool {
        await messenger.account.pendingSetup.contains(.announceProfile)
    }

    public func uploadAttachment(fileAt url: URL, kind: BRPAttachment.Kind, contentType: String, fileName: String?)
        async throws -> BRPAttachment
    {
        try await messenger.uploadAttachment(fileAt: url, kind: kind, contentType: contentType, fileName: fileName)
    }

    public func downloadAttachment(_ attachment: BRPAttachment, to destination: URL) async throws {
        try await messenger.downloadAttachment(attachment, to: destination)
    }

    public func deleteUploadedMedia(_ attachment: BRPAttachment) async throws {
        try await messenger.relay.deleteMedia(id: attachment.mediaID, deleteToken: attachment.deleteToken)
    }

    public func findAccount(username: NormalizedUsername) async throws -> AccountId? {
        try await messenger.findAccount(username: username)?.accountId
    }

    public func usernameOwner(_ username: NormalizedUsername) async throws -> AccountId? {
        try await messenger.usernameOwner(username)
    }

    public func changeUsername(to username: NormalizedUsername) async throws {
        try await messenger.changeUsername(to: username)
    }

    public func safetyNumber(with contact: AccountId) async throws -> SafetyNumber? {
        try await messenger.safetyNumber(with: contact)
    }

    public func identityTrust(for contact: AccountId) async throws -> IdentityTrust {
        try await messenger.identityTrust(for: contact)
    }

    public func approveIdentityChange(for contact: AccountId) async throws {
        try await messenger.approveIdentityChange(for: contact)
    }

    public func markVerified(_ verified: Bool, contact: AccountId) async throws {
        try await messenger.markVerified(verified, contact: contact)
    }

    public func forgetContact(_ contact: AccountId) async throws { try await messenger.forgetContact(contact) }

    public func makeBackup(displayName: String, contacts: [BRPBackupContact]) async throws -> BRPAccountBackup {
        try await messenger.makeBackup(displayName: displayName, contacts: contacts)
    }

    public func deleteAccount() async throws { try await messenger.deleteAccount() }
}
