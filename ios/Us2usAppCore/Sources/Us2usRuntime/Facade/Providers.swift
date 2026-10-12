import Foundation
import LibSignalClient
import Us2usKit
import Us2usStorage

/// Creates the relay/crypto backend of an account on its open storage.
public protocol BackendFactory: Sendable {
    /// Registers a new account (proof-of-work, pre-keys, access key, §4.3).
    /// `storage` already holds the new identity; the account is saved to it
    /// before anything else can fail.
    func register(username: NormalizedUsername, storage: LocalStorage) async throws -> any MessagingBackend
    /// The backend of a registered installation.
    func open(account: LocalAccount, storage: LocalStorage) throws -> any MessagingBackend
    /// Restores an account from a decrypted backup into a fresh `storage`
    /// (§5.5): full validation first, identity and contacts, token rotation
    /// (the new token stored before it is sent, the outcome saved before the
    /// key upload), fresh pre-keys. Throws `BackupRestoreError` when the
    /// relay refuses the backup.
    func restore(_ backup: BRPAccountBackup, storage: LocalStorage) async throws -> any MessagingBackend
    /// The read-only relay check of a backup before anything on this phone
    /// is replaced: one authenticated `GET /v1/keys/status` with the backup's
    /// token, nothing stored; on the relay only the account's last-seen day
    /// changes (§4.10), as with any authenticated request. Throws
    /// `BackupRestoreError.backupNoLongerValid` when the relay refuses the
    /// token, and the relay's error when it could not be asked.
    func checkBackup(_ backup: BRPAccountBackup) async throws
}

/// The production backend factory: one ``RelayClient`` per account, built by
/// ``NetworkPolicy`` (https only, constant headers, isolated anonymous session).
public struct RelayBackendFactory: BackendFactory {
    public let relayURL: URL

    public init(relayURL: URL = RelayClient.productionURL) {
        self.relayURL = relayURL
    }

    static func configuration(for storage: LocalStorage) -> Messenger.Configuration {
        var configuration = Messenger.Configuration()
        // Attachment blobs are staged in the storage's protected staging directory.
        configuration.temporaryDirectory = storage.layout.stagingDirectory
        return configuration
    }

    public func register(username: NormalizedUsername, storage: LocalStorage) async throws -> any MessagingBackend {
        guard let protocolStore = storage.protocolStore else { throw StorageError.invalidState("no identity") }
        let relay = try NetworkPolicy.makeRelayClient(baseURL: relayURL)
        let messenger = try await Messenger.register(
            username: username, store: protocolStore, relay: relay, configuration: Self.configuration(for: storage),
            saveAccount: storage.accountSaver)
        return MessengerBackend(
            messenger: messenger, accountId: await messenger.account.accountId, protocolStore: protocolStore)
    }

    public func open(account: LocalAccount, storage: LocalStorage) throws -> any MessagingBackend {
        guard let protocolStore = storage.protocolStore else { throw StorageError.invalidState("no identity") }
        let relay = try NetworkPolicy.makeRelayClient(baseURL: relayURL)
        let messenger = Messenger(
            account: account, store: protocolStore, relay: relay, configuration: Self.configuration(for: storage),
            saveAccount: storage.accountSaver)
        return MessengerBackend(messenger: messenger, accountId: account.accountId, protocolStore: protocolStore)
    }

    public func restore(_ backup: BRPAccountBackup, storage: LocalStorage) async throws -> any MessagingBackend {
        let relay = try NetworkPolicy.makeRelayClient(baseURL: relayURL)
        let messenger = try await AccountBackupService.restore(
            backup, storage: storage, relay: relay, configuration: Self.configuration(for: storage))
        guard let protocolStore = storage.protocolStore else { throw StorageError.invalidState("no identity") }
        return MessengerBackend(
            messenger: messenger, accountId: await messenger.account.accountId, protocolStore: protocolStore)
    }

    public func checkBackup(_ backup: BRPAccountBackup) async throws {
        // Its own client: nothing of an open session is used or changed.
        let relay = try NetworkPolicy.makeRelayClient(baseURL: relayURL)
        try await Messenger.checkBackup(backup, relay: relay)
    }
}
