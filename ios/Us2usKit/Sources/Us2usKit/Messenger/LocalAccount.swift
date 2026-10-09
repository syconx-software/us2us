import Foundation

/// What a registered installation must persist (inside the vault, §5.2) besides
/// its ``ProtocolStore``. ``Messenger`` hands every change to its
/// ``AccountSaver`` before it relies on it.
public struct LocalAccount: Sendable, Equatable, Codable {
    public let accountId: AccountId
    public var authToken: Data
    public var username: NormalizedUsername
    /// Our current access key (16 bytes), given to accepted contacts (§4.6).
    public var accessKey: Data
    /// Relay calls that must still succeed after registration or restore.
    public var pendingSetup: PendingRelaySetup

    public init(
        accountId: AccountId, authToken: Data, username: NormalizedUsername, accessKey: Data,
        pendingSetup: PendingRelaySetup = []
    ) {
        self.accountId = accountId
        self.authToken = authToken
        self.username = username
        self.accessKey = accessKey
        self.pendingSetup = pendingSetup
    }

    var credentials: RelayCredentials {
        RelayCredentials(accountId: accountId, authToken: authToken)
    }
}

/// Follow-up relay calls of registration and restore. They run right away,
/// but once the relay has issued credentials a failure must not lose them, so
/// failed steps stay here and ``Messenger/performMaintenance(now:)`` retries them.
public struct PendingRelaySetup: OptionSet, Sendable, Hashable, Codable {
    public let rawValue: Int

    public init(rawValue: Int) {
        self.rawValue = rawValue
    }

    /// `PUT /v1/accounts/me/access-key` with the hash of ``LocalAccount/accessKey``.
    public static let publishAccessKey = PendingRelaySetup(rawValue: 1 << 0)
    /// Fresh pre-keys uploaded with `replaceAll` after a restore (§5.5). Until
    /// it succeeds the relay hands out pre-keys whose private keys are gone, so
    /// it is retried until it does.
    public static let replacePreKeys = PendingRelaySetup(rawValue: 1 << 1)
    /// A complete `ProfileUpdate` to every accepted contact once the keys are
    /// replaced (§5.5 step 5), with ``Messenger/announceProfile(_:to:)``.
    public static let announceProfile = PendingRelaySetup(rawValue: 1 << 2)

    /// The steps ``Messenger/performMaintenance(now:)`` runs by itself.
    static let relayOnly: PendingRelaySetup = [.publishAccessKey, .replacePreKeys]
}

/// Persists a ``LocalAccount`` inside the vault (§5.2). ``Messenger`` calls it
/// whenever the account changes, before any relay call that depends on the
/// change: after a restore the rotated token is saved before the key upload
/// (§5.5), because the backup's token no longer works. If it throws, the
/// operation stops with that error.
public typealias AccountSaver = @Sendable (LocalAccount) throws -> Void

extension NormalizedUsername: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        do {
            self = try NormalizedUsername(raw)
        } catch {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "invalid username")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(value)
    }
}
