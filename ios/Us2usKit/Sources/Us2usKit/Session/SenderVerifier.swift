import Foundation
import LibSignalClient

/// Public directory data needed to verify sealed senders (PROTOCOL.md §4.3
/// identity lookup, §4.4 directory). Injectable so verification can be tested
/// and cached independently of the relay client.
public protocol IdentityDirectory: Sendable {
    /// The identity key registered for `account`, or `nil` if the account does
    /// not exist. `refresh` bypasses any cache.
    func identityKey(of account: AccountId, refresh: Bool) async throws -> IdentityKey?
    /// The account `username` points to, or `nil` if it is not registered.
    func account(for username: NormalizedUsername, refresh: Bool) async throws -> AccountId?
}

public enum SenderVerificationError: Error, Equatable, Sendable {
    case malformed(String)
    /// The relay knows no such account.
    case unknownSender
    /// The identity inside the PreKeySignalMessage is not the sender's registered one.
    case identityMismatch
    case invalidUsername
    /// `sender_username` does not point to the sending account.
    case usernameMismatch
}

/// A sender that passed §6.3 steps 2–3.
public struct VerifiedSender: Sendable, Equatable {
    public let account: AccountId
    /// Set only when `sender_username` was present and verified against the directory.
    public let username: NormalizedUsername?
}

/// PROTOCOL.md §6.3 steps 2–4. A sealed sender is only a claim. For messages
/// that start a session (type 3) the identity key inside is checked against
/// the relay before libsignal sees the message, unless it is the key already
/// stored for that account; messages on an existing session (type 2) are
/// authenticated by the session itself. A `sender_username` is checked
/// against the directory for every type.
///
/// Failures that must drop the envelope (a mismatch, or a `404` from either
/// lookup) are ``SenderVerificationError``s; any other error (network, `429`,
/// `5xx`) means "keep the envelope and try again later".
public struct SenderVerifier: Sendable {
    public let directory: any IdentityDirectory
    /// The identity key this phone already stores for an account, if any.
    private let storedIdentity: @Sendable (AccountId) throws -> IdentityKey?

    public init(
        directory: any IdentityDirectory,
        storedIdentity: @escaping @Sendable (AccountId) throws -> IdentityKey? = { _ in nil }
    ) {
        self.directory = directory
        self.storedIdentity = storedIdentity
    }

    public func verify(_ sealed: BRPSealedContent) async throws -> VerifiedSender {
        let header: SealedHeader
        do {
            header = try SealedHeader(sealed)
        } catch {
            throw SenderVerificationError.malformed("header")
        }
        if header.kind == .preKey {
            try await verifyIdentity(of: header.sender, in: sealed)
        }
        guard !sealed.senderUsername.isEmpty else {
            return VerifiedSender(account: header.sender, username: nil)
        }
        // The field carries the normalized form; anything else could be a
        // look-alike, so it is rejected rather than normalized here.
        guard let username = try? NormalizedUsername(sealed.senderUsername), username.value == sealed.senderUsername
        else {
            throw SenderVerificationError.invalidUsername
        }
        var owner = try await directory.account(for: username, refresh: false)
        if owner != header.sender {
            owner = try await directory.account(for: username, refresh: true)
        }
        guard owner == header.sender else { throw SenderVerificationError.usernameMismatch }
        return VerifiedSender(account: header.sender, username: username)
    }

    /// §6.3 step 2 for a PreKeySignalMessage.
    private func verifyIdentity(of sender: AccountId, in sealed: BRPSealedContent) async throws {
        let claimed: IdentityKey
        do {
            claimed = IdentityKey(publicKey: try PreKeySignalMessage(bytes: sealed.ciphertext).identityKey)
        } catch {
            throw SenderVerificationError.malformed("ciphertext")
        }
        // A key this phone already holds for the account came from the relay
        // before; asking again would only cost the relay a request.
        if try storedIdentity(sender) == claimed { return }
        var registered = try await directory.identityKey(of: sender, refresh: false)
        if registered != nil, registered != claimed {
            // The cached key may predate a reinstall; ask the relay once more.
            registered = try await directory.identityKey(of: sender, refresh: true)
        }
        guard let registered else { throw SenderVerificationError.unknownSender }
        guard registered == claimed else { throw SenderVerificationError.identityMismatch }
    }
}
