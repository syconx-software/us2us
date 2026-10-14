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
    /// The token ``authToken`` is being rotated to (§4.3, §5.5 step 3): stored
    /// before the rotation is sent and kept until its outcome is known, so a
    /// lost answer never leaves the account without a token that works. While
    /// it is set, neither token is used for anything but finding out the
    /// outcome (``Messenger/finishPendingTokenRotation()``). Absent in
    /// accounts saved before client-chosen tokens; it decodes as `nil`.
    public var pendingAuthToken: Data?
    /// The user's own profile picture (§8.9): a serialized `ProfileAvatar`
    /// that passed ``ProfilePicture/validated(_:)``, or `nil` for the initial
    /// letter. Read it as ``profilePicture``.
    public var profileAvatar: Data?
    /// "Aramada göster" / "Show in search" is off (§8.10). `false` — the
    /// default, also for accounts saved before profile pictures — is on.
    public var avatarSearchOff: Bool
    /// The last version this installation used for an avatar-record write
    /// (§4.12, §8.10), raised to the relay's `X-Avatar-Version` after a
    /// `409`; the next write uses this + 1. 0 before the first one (also
    /// after a restore: backups hold no version).
    public var avatarVersion: UInt64
    /// The relay's avatar record may differ from what the picture, the
    /// setting and the username ask for (a change not yet written, or a
    /// write whose outcome is unknown): ``Messenger/syncAvatarRecord()``
    /// brings it in line.
    public var avatarRecordPending: Bool

    public init(
        accountId: AccountId, authToken: Data, username: NormalizedUsername, accessKey: Data,
        pendingSetup: PendingRelaySetup = [], pendingAuthToken: Data? = nil, profileAvatar: Data? = nil,
        avatarSearchOff: Bool = false, avatarVersion: UInt64 = 0, avatarRecordPending: Bool = false
    ) {
        self.accountId = accountId
        self.authToken = authToken
        self.username = username
        self.accessKey = accessKey
        self.pendingSetup = pendingSetup
        self.pendingAuthToken = pendingAuthToken
        self.profileAvatar = profileAvatar
        self.avatarSearchOff = avatarSearchOff
        self.avatarVersion = avatarVersion
        self.avatarRecordPending = avatarRecordPending
    }

    /// The own profile picture; the initial letter without one.
    public var profilePicture: ProfilePicture { ProfilePicture(serialized: profileAvatar) }

    private enum CodingKeys: String, CodingKey {
        case accountId, authToken, username, accessKey, pendingSetup, pendingAuthToken
        case profileAvatar, avatarSearchOff, avatarVersion, avatarRecordPending
    }

    /// Accounts saved before a field existed decode it with its default.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        accountId = try container.decode(AccountId.self, forKey: .accountId)
        authToken = try container.decode(Data.self, forKey: .authToken)
        username = try container.decode(NormalizedUsername.self, forKey: .username)
        accessKey = try container.decode(Data.self, forKey: .accessKey)
        pendingSetup = try container.decode(PendingRelaySetup.self, forKey: .pendingSetup)
        pendingAuthToken = try container.decodeIfPresent(Data.self, forKey: .pendingAuthToken)
        profileAvatar = try container.decodeIfPresent(Data.self, forKey: .profileAvatar)
        avatarSearchOff = try container.decodeIfPresent(Bool.self, forKey: .avatarSearchOff) ?? false
        avatarVersion = try container.decodeIfPresent(UInt64.self, forKey: .avatarVersion) ?? 0
        avatarRecordPending = try container.decodeIfPresent(Bool.self, forKey: .avatarRecordPending) ?? false
    }

    var credentials: RelayCredentials {
        RelayCredentials(accountId: accountId, authToken: authToken)
    }

    /// What the relay client may authenticate with: nothing while a token
    /// rotation's outcome is unknown (§5.5 step 3.5), so no request can use
    /// either token by accident.
    var usableCredentials: RelayCredentials? {
        pendingAuthToken == nil ? credentials : nil
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
