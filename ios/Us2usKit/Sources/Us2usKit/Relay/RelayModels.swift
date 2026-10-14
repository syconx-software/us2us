import Foundation

// JSON bodies of the relay API (PROTOCOL.md §4). Binary fields are `Data`;
// the relay coders encode them as base64url without padding (§2).

public struct HealthResponse: Codable, Sendable, Equatable {
    public let ok: Bool
    public let version: String
}

/// `POST /v1/accounts` (§4.3).
public struct RegistrationRequest: Codable, Sendable, Equatable {
    public let usernameHash: Data
    public let identityKey: Data
    public let registrationId: UInt32
    public let signedPreKey: SignedPreKeyEntity
    public let kyberLastResortKey: SignedPreKeyEntity
    public let oneTimePreKeys: [PreKeyEntity]
    public let kyberOneTimePreKeys: [SignedPreKeyEntity]
    public let pow: PowSolution

    public init(
        usernameHash: Data, identityKey: Data, registrationId: UInt32, signedPreKey: SignedPreKeyEntity,
        kyberLastResortKey: SignedPreKeyEntity, oneTimePreKeys: [PreKeyEntity],
        kyberOneTimePreKeys: [SignedPreKeyEntity], pow: PowSolution
    ) {
        self.usernameHash = usernameHash
        self.identityKey = identityKey
        self.registrationId = registrationId
        self.signedPreKey = signedPreKey
        self.kyberLastResortKey = kyberLastResortKey
        self.oneTimePreKeys = oneTimePreKeys
        self.kyberOneTimePreKeys = kyberOneTimePreKeys
        self.pow = pow
    }
}

public struct RegistrationResponse: Codable, Sendable, Equatable {
    public let accountId: AccountId
    public let authToken: Data
}

/// `GET /v1/accounts/me`.
public struct AccountInfo: Codable, Sendable, Equatable {
    public let accountId: AccountId
    public let usernameHash: Data
}

struct UsernameHashBody: Codable, Sendable {
    let usernameHash: Data
}

/// What a relay that predates client-chosen tokens answers to a rotation
/// (`200`, the deprecated form of §4.3).
struct AuthTokenResponse: Codable, Sendable {
    let authToken: Data
}

/// `POST /v1/accounts/me/token`: the token the client chose and stored (§4.3).
struct AuthTokenRotationBody: Codable, Sendable {
    let newAuthToken: Data
}

/// `PUT /v1/accounts/me/access-key`; `null` disables access-key delivery, so
/// the key is always encoded, even when `nil`.
struct AccessKeyHashBody: Codable, Sendable {
    let accessKeyHash: Data?

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(accessKeyHash, forKey: .accessKeyHash)
    }
}

/// `{"accountId","identityKey"}` from the identity lookup and the directory.
public struct IdentityResponse: Codable, Sendable, Equatable {
    public let accountId: AccountId
    public let identityKey: Data
}

/// `PUT /v1/keys` (§4.5): any subset; one-time keys are appended.
public struct KeyUploadRequest: Codable, Sendable, Equatable {
    public var signedPreKey: SignedPreKeyEntity?
    public var kyberLastResortKey: SignedPreKeyEntity?
    public var oneTimePreKeys: [PreKeyEntity]?
    public var kyberOneTimePreKeys: [SignedPreKeyEntity]?
    /// Deletes all stored one-time keys of both types first (after a restore).
    public var replaceAll: Bool?

    public init(
        signedPreKey: SignedPreKeyEntity? = nil, kyberLastResortKey: SignedPreKeyEntity? = nil,
        oneTimePreKeys: [PreKeyEntity]? = nil, kyberOneTimePreKeys: [SignedPreKeyEntity]? = nil,
        replaceAll: Bool? = nil
    ) {
        self.signedPreKey = signedPreKey
        self.kyberLastResortKey = kyberLastResortKey
        self.oneTimePreKeys = oneTimePreKeys
        self.kyberOneTimePreKeys = kyberOneTimePreKeys
        self.replaceAll = replaceAll
    }

    public var isEmpty: Bool {
        signedPreKey == nil && kyberLastResortKey == nil && (oneTimePreKeys ?? []).isEmpty
            && (kyberOneTimePreKeys ?? []).isEmpty && replaceAll != true
    }
}

/// `GET /v1/keys/status`.
public struct KeyStatus: Codable, Sendable, Equatable {
    public let oneTimePreKeys: Int
    public let kyberOneTimePreKeys: Int
    public let signedPreKeyId: UInt32?
    public let kyberLastResortKeyId: UInt32?
}

/// `GET /v1/keys/{accountId}`. `kyberPreKey` is a one-time key or, when none is
/// left, the last-resort key.
public struct PreKeyBundleResponse: Codable, Sendable, Equatable {
    public let accountId: AccountId
    public let deviceId: UInt32
    public let registrationId: UInt32
    public let identityKey: Data
    public let signedPreKey: SignedPreKeyEntity
    public let kyberPreKey: SignedPreKeyEntity
    public let oneTimePreKey: PreKeyEntity?

    public init(
        accountId: AccountId, deviceId: UInt32, registrationId: UInt32, identityKey: Data,
        signedPreKey: SignedPreKeyEntity, kyberPreKey: SignedPreKeyEntity, oneTimePreKey: PreKeyEntity?
    ) {
        self.accountId = accountId
        self.deviceId = deviceId
        self.registrationId = registrationId
        self.identityKey = identityKey
        self.signedPreKey = signedPreKey
        self.kyberPreKey = kyberPreKey
        self.oneTimePreKey = oneTimePreKey
    }
}

/// `202` response of `PUT /v1/messages/{recipient}`. Ephemeral sends get the
/// same shape whether or not the recipient was online (§4.6).
public struct SendMessageResponse: Codable, Sendable, Equatable {
    /// Opaque relay message id (base64url string).
    public let id: String
    public let serverTimestamp: Int64
}

/// One item of `POST /v1/messages/ephemeral` (§4.6).
public struct EphemeralMessage: Codable, Sendable, Equatable {
    public let to: AccountId
    /// The recipient's access key; ephemeral sends never use proof-of-work.
    public let accessKey: Data
    /// A sealed envelope of at most 4 KiB.
    public let envelope: Data

    public init(to: AccountId, accessKey: Data, envelope: Data) {
        self.to = to
        self.accessKey = accessKey
        self.envelope = envelope
    }
}

struct EphemeralBatchRequest: Codable, Sendable {
    let messages: [EphemeralMessage]
}

/// One stored envelope, from `GET /v1/messages` or a WebSocket `message` frame.
public struct RelayEnvelope: Codable, Sendable, Equatable {
    /// Opaque relay message id; acknowledge with exactly this string.
    public let id: String
    public let serverTimestamp: Int64
    public let envelope: Data

    public init(id: String, serverTimestamp: Int64, envelope: Data) {
        self.id = id
        self.serverTimestamp = serverTimestamp
        self.envelope = envelope
    }
}

public struct MessagesPage: Codable, Sendable, Equatable {
    public let messages: [RelayEnvelope]
    public let more: Bool
}

struct AckRequest: Codable, Sendable {
    let ids: [String]
}

/// `201` response of `POST /v1/media`.
public struct MediaUploadResponse: Codable, Sendable, Equatable {
    public let mediaId: Data
    public let deleteToken: Data
    public let expiresAt: Int64
}

struct RelayErrorBody: Codable, Sendable {
    struct Detail: Codable, Sendable {
        let code: String
        let message: String?
    }

    let error: Detail
}

extension JSONEncoder {
    /// Encoder for relay JSON: `Data` as unpadded base64url.
    static var relay: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dataEncodingStrategy = .custom { data, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(data.base64URLEncodedString())
        }
        return encoder
    }
}

extension JSONDecoder {
    /// Decoder for relay JSON: `Data` from unpadded base64url (strict).
    static var relay: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dataDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            guard let data = Data(base64URLEncoded: string) else {
                throw DecodingError.dataCorruptedError(in: container, debugDescription: "invalid base64url")
            }
            return data
        }
        return decoder
    }
}
