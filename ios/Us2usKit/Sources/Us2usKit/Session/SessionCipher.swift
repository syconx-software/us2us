import Foundation
import LibSignalClient
import SwiftProtobuf

public enum SessionError: Error, Equatable, Sendable {
    /// The contact's safety number changed; sending is blocked until the user
    /// accepts the new identity (PROTOCOL.md §5).
    case identityChanged(AccountId)
    /// No usable session; fetch a pre-key bundle first.
    case noSession(AccountId)
    case invalidBundle(String)
    case malformedSealedContent(String)
    case invalidPadding
    case invalidContent
    /// A libsignal failure (bad MAC, duplicate, missing pre-key, …), described
    /// without message contents.
    case decryptionFailed(String)
    case encryptionFailed(String)
}

/// A decrypted inner message (§6.3 steps 3–4).
public struct DecryptedContent: Sendable {
    public let sender: AccountId
    public let content: BRPContent
    /// The sender's identity key changed with this message (it came from a new
    /// installation). Show the safety-number warning; sending is now blocked.
    public let identityChanged: Bool
}

/// libsignal sessions plus the sealed envelope for one local account, without
/// any networking (PROTOCOL.md §5, §5.1, §6). Not thread-safe on its own:
/// callers serialize use (``Messenger`` does so on its actor).
public struct SessionCipher: Sendable {
    public let localAccount: AccountId
    public let store: any ProtocolStore

    public init(localAccount: AccountId, store: any ProtocolStore) {
        self.localAccount = localAccount
        self.store = store
    }

    private var context: StoreContext { NullContext() }

    /// Whether a session exists that can encrypt right now (libsignal treats an
    /// unacknowledged session older than 30 days as stale).
    public func hasUsableSession(with account: AccountId, now: Date = Date()) throws -> Bool {
        guard let record = try store.loadSession(for: account.protocolAddress, context: context) else { return false }
        return record.hasCurrentState(now: now)
    }

    /// Starts a session from a relay bundle (§4.5). libsignal verifies both
    /// pre-key signatures. If the bundle carries a different identity than the
    /// one trusted for this contact, the change is recorded and
    /// ``SessionError/identityChanged(_:)`` is thrown.
    public func processBundle(_ bundle: PreKeyBundleResponse, now: Date = Date()) throws {
        guard bundle.deviceId == AccountId.deviceId else { throw SessionError.invalidBundle("deviceId") }
        guard bundle.accountId != localAccount else { throw SessionError.invalidBundle("own account") }
        let preKeyBundle: PreKeyBundle
        let identity: IdentityKey
        // libsignal tolerates trailing bytes after a key; the wire format does not.
        let ecKeys =
            [bundle.identityKey, bundle.signedPreKey.publicKey] + [bundle.oneTimePreKey?.publicKey].compactMap { $0 }
        guard ecKeys.allSatisfy({ $0.count == 33 }) else { throw SessionError.invalidBundle("key length") }
        do {
            identity = try IdentityKey(bytes: bundle.identityKey)
            let signedPreKey = try PublicKey(bundle.signedPreKey.publicKey)
            let kyberPreKey = try KEMPublicKey(bundle.kyberPreKey.publicKey)
            if let oneTime = bundle.oneTimePreKey {
                preKeyBundle = try PreKeyBundle(
                    registrationId: bundle.registrationId, deviceId: bundle.deviceId,
                    prekeyId: oneTime.keyId, prekey: try PublicKey(oneTime.publicKey),
                    signedPrekeyId: bundle.signedPreKey.keyId, signedPrekey: signedPreKey,
                    signedPrekeySignature: bundle.signedPreKey.signature, identity: identity,
                    kyberPrekeyId: bundle.kyberPreKey.keyId, kyberPrekey: kyberPreKey,
                    kyberPrekeySignature: bundle.kyberPreKey.signature)
            } else {
                preKeyBundle = try PreKeyBundle(
                    registrationId: bundle.registrationId, deviceId: bundle.deviceId,
                    signedPrekeyId: bundle.signedPreKey.keyId, signedPrekey: signedPreKey,
                    signedPrekeySignature: bundle.signedPreKey.signature, identity: identity,
                    kyberPrekeyId: bundle.kyberPreKey.keyId, kyberPrekey: kyberPreKey,
                    kyberPrekeySignature: bundle.kyberPreKey.signature)
            }
        } catch {
            throw SessionError.invalidBundle("keys")
        }

        let address = bundle.accountId.protocolAddress
        let process = {
            try processPreKeyBundle(
                preKeyBundle, for: address, ourAddress: self.localAccount.protocolAddress,
                sessionStore: self.store, identityStore: self.store, now: now, context: self.context)
        }
        do {
            do {
                try process()
            } catch SignalError.untrustedIdentity {
                // Record the new key as "changed, awaiting approval" so the UI
                // can show the safety-number warning. If the bundle carries the
                // key the user had approved before (an earlier change was
                // reverted), the store trusts it again and the session starts.
                _ = try store.saveIdentity(identity, for: address, context: context)
                try process()
            }
        } catch SignalError.untrustedIdentity {
            throw SessionError.identityChanged(bundle.accountId)
        } catch SignalError.invalidSignature(let message) {
            throw SessionError.invalidBundle("signature: \(message)")
        } catch let error as SignalError {
            throw SessionError.invalidBundle(String(describing: error))
        }
    }

    /// Pads, session-encrypts and seals `content` for `recipient` (§5.1, §6).
    /// `senderUsername` is set only while the conversation is a message request (§6.4).
    public func seal(
        _ content: BRPContent, for recipient: AccountId, senderUsername: NormalizedUsername? = nil, now: Date = Date()
    ) throws -> Data {
        let address = recipient.protocolAddress
        guard let recipientIdentity = try store.identity(for: address, context: context) else {
            throw SessionError.noSession(recipient)
        }
        let padded = ContentPadding.pad(try content.serializedBytes())
        let ciphertext: CiphertextMessage
        do {
            ciphertext = try signalEncrypt(
                message: padded, for: address, localAddress: localAccount.protocolAddress,
                sessionStore: store, identityStore: store, now: now, context: context)
        } catch SignalError.untrustedIdentity {
            throw SessionError.identityChanged(recipient)
        } catch SignalError.sessionNotFound {
            throw SessionError.noSession(recipient)
        } catch let error as SignalError {
            throw SessionError.encryptionFailed(String(describing: error))
        }

        var sealed = BRPSealedContent()
        sealed.senderAccountID = localAccount.bytes
        sealed.senderDeviceID = AccountId.deviceId
        sealed.type = UInt32(ciphertext.messageType.rawValue)
        sealed.ciphertext = ciphertext.serialize()
        if let senderUsername { sealed.senderUsername = senderUsername.value }
        return try SealedEnvelope.seal(sealed, to: recipientIdentity)
    }

    /// Session-decrypts an opened, already sender-verified envelope (§6.3
    /// steps 3–4). Duplicates and replays fail inside libsignal.
    public func decrypt(_ sealed: BRPSealedContent) throws -> DecryptedContent {
        let header = try SealedHeader(sealed)
        guard header.sender != localAccount else { throw SessionError.malformedSealedContent("self-send") }
        let address = header.sender.protocolAddress
        let trustBefore = try store.identityTrust(for: header.sender)

        let padded: Data
        do {
            switch header.kind {
            case .preKey:
                padded = try signalDecryptPreKey(
                    message: try PreKeySignalMessage(bytes: sealed.ciphertext), from: address,
                    localAddress: localAccount.protocolAddress, sessionStore: store, identityStore: store,
                    preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: context)
            case .whisper:
                padded = try signalDecrypt(
                    message: try SignalMessage(bytes: sealed.ciphertext), from: address,
                    to: localAccount.protocolAddress, sessionStore: store, identityStore: store, context: context)
            }
        } catch let error as SignalError {
            throw SessionError.decryptionFailed(String(describing: error))
        }

        let content: BRPContent
        do {
            content = try BRPContent(serializedBytes: try ContentPadding.unpad(padded))
        } catch is ContentPaddingError {
            throw SessionError.invalidPadding
        } catch {
            throw SessionError.invalidContent
        }
        do {
            try content.validateStructure()
        } catch {
            throw SessionError.invalidContent
        }

        let trustAfter = try store.identityTrust(for: header.sender)
        let changed: Bool
        if case .changed = trustAfter { changed = trustAfter != trustBefore } else { changed = false }
        return DecryptedContent(sender: header.sender, content: content, identityChanged: changed)
    }
}

/// The validated routing fields of a `SealedContent`.
struct SealedHeader {
    enum Kind {
        case whisper  // SignalMessage, type 2
        case preKey  // PreKeySignalMessage, type 3
    }

    let sender: AccountId
    let kind: Kind

    init(_ sealed: BRPSealedContent) throws {
        guard let sender = AccountId(bytes: sealed.senderAccountID) else {
            throw SessionError.malformedSealedContent("sender_account_id")
        }
        guard sealed.senderDeviceID == AccountId.deviceId else {
            throw SessionError.malformedSealedContent("sender_device_id")
        }
        switch sealed.type {
        case UInt32(CiphertextMessage.MessageType.whisper.rawValue): kind = .whisper
        case UInt32(CiphertextMessage.MessageType.preKey.rawValue): kind = .preKey
        default: throw SessionError.malformedSealedContent("type")
        }
        self.sender = sender
    }
}
