import Foundation
import LibSignalClient
import SwiftProtobuf

public enum SealedEnvelopeError: Error, Equatable, Sendable {
    case unsupportedVersion
    /// HPKE open failed: not for us, or tampered with.
    case decryptionFailed
    case malformedContent
}

/// The sealed-sender envelope (PROTOCOL.md §6.1): HPKE (RFC 9180, inside
/// libsignal) to the recipient's identity key, so the relay never learns
/// the sender.
///
///     envelope = 0x01 ‖ HPKE-Seal(pkR = recipient identity, info = "BRP/sealed/v1", aad = 0x01, SealedContent)
public enum SealedEnvelope {
    public static let version: UInt8 = 0x01
    static let info = "BRP/sealed/v1"
    static let associatedData = Data([0x01])
    /// Relay limit for an envelope body (§4.6).
    public static let maxEnvelopeBytes = 64 * 1024

    /// Each call uses a fresh HPKE encapsulation, so envelopes are unlinkable.
    public static func seal(_ content: BRPSealedContent, to recipient: IdentityKey) throws -> Data {
        let plaintext: Data = try content.serializedBytes()
        var envelope = Data([version])
        envelope.append(recipient.publicKey.seal(plaintext, info: info, associatedData: associatedData))
        return envelope
    }

    public static func open(
        _ envelope: Data, with identity: IdentityKeyPair
    ) throws(SealedEnvelopeError)
        -> BRPSealedContent
    {
        guard envelope.first == version else { throw .unsupportedVersion }
        let plaintext: Data
        do {
            plaintext = try identity.privateKey.open(
                envelope.dropFirst(), info: info, associatedData: associatedData)
        } catch {
            throw .decryptionFailed
        }
        do {
            return try BRPSealedContent(serializedBytes: plaintext)
        } catch {
            throw .malformedContent
        }
    }
}
