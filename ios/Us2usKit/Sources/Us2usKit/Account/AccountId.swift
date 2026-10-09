import Foundation
import LibSignalClient

/// An account's stable identity on the relay (PROTOCOL.md §2): a UUID that is
/// a lowercase hyphenated string in JSON and 16 raw bytes inside protobuf.
public struct AccountId: Hashable, Sendable, Comparable, CustomStringConvertible {
    public static let byteCount = 16

    public let uuid: UUID

    public init(uuid: UUID) {
        self.uuid = uuid
    }

    /// Parses the hyphenated form (either case; the canonical form is lowercase).
    public init?(string: String) {
        guard let uuid = UUID(uuidString: string) else { return nil }
        self.uuid = uuid
    }

    /// Parses the 16-byte binary form used inside protobuf messages.
    public init?(bytes: Data) {
        guard bytes.count == Self.byteCount else { return nil }
        let b = Array(bytes)
        self.uuid = UUID(
            uuid: (
                b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]
            ))
    }

    /// The 16 raw bytes, also the stable identifier for safety numbers (§5).
    public var bytes: Data {
        withUnsafeBytes(of: uuid.uuid) { Data($0) }
    }

    /// Canonical lowercase hyphenated string, as used in JSON and URLs.
    public var string: String { uuid.uuidString.lowercased() }

    public var description: String { string }

    /// The libsignal address of this account's only device. Every session and
    /// identity operation MUST use this one constructor (§2): libsignal MACs the
    /// binary ServiceId, and the stores are keyed by the resulting name.
    public var protocolAddress: ProtocolAddress {
        ProtocolAddress(Aci(fromUUID: uuid), deviceId: Self.deviceId)
    }

    /// Protocol v1 has exactly one device per account.
    public static let deviceId: UInt32 = 1

    /// Recovers the account from an address built by ``protocolAddress``.
    init?(address: ProtocolAddress) {
        guard address.deviceId == Self.deviceId,
            let serviceId = address.serviceId, serviceId.kind == .aci
        else { return nil }
        self.uuid = serviceId.rawUUID
    }

    /// Lexicographic order of the 16 raw bytes; breaks last-writer-wins ties
    /// between conversation settings (brp.proto `ConversationSettings`).
    public static func < (lhs: AccountId, rhs: AccountId) -> Bool {
        lhs.bytes.lexicographicallyPrecedes(rhs.bytes)
    }
}

extension AccountId: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let string = try container.decode(String.self)
        guard let id = AccountId(string: string) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "invalid account id")
        }
        self = id
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(string)
    }
}
