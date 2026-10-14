import Foundation
import LibSignalClient

/// Reference ``ProtocolStore`` held in memory.
///
/// Unlike libsignal's `InMemorySignalProtocolStore` it implements the BRP
/// rules of PROTOCOL.md §5: one-time Kyber keys are deleted when used, the
/// last-resort key is kept and `(kyberId, signedId, baseKey)` is tracked to
/// reject reuse, and identities follow TOFU with change detection (accept and
/// flag on receive, block on send until approved).
///
/// Records are kept serialized, exactly as a persistent store would hold them.
public final class InMemoryProtocolStore: ProtocolStore {
    private struct IdentityRecord {
        var key: Data
        /// The last approved key while `key` awaits approval (`nil` if unknown,
        /// e.g. restored from a backup).
        var previous: Data?
        var approved: Bool
        var verified: Bool
        /// When and how `key` was verified (only while `verified`).
        var verification: IdentityVerification? = nil
        /// While `key` awaits approval: whether `previous` (the approved key)
        /// was verified, and when and how. A return to it keeps that
        /// verification (§5), so a key injected once cannot strip the seal.
        var previousVerified = false
        var previousVerification: IdentityVerification? = nil
    }

    private struct KyberEntry {
        var record: Data
        var lastResort: Bool
    }

    private struct LastResortUse: Hashable {
        var kyberId: UInt32
        var signedId: UInt32
        var baseKey: Data
    }

    private struct State {
        var identities: [AccountId: IdentityRecord] = [:]
        var preKeys: [UInt32: Data] = [:]
        var signedPreKeys: [UInt32: Data] = [:]
        var kyberPreKeys: [UInt32: KyberEntry] = [:]
        var lastResortUses: Set<LastResortUse> = []
        var sessions: [AccountId: Data] = [:]
        var nextKeyIds: [PreKeyIdSpace: UInt32] = [.oneTime: 1, .signed: 1, .kyber: 1]
    }

    private let identityKeyPair: IdentityKeyPair
    private let registrationId: UInt32
    private let state = Locked(State())

    public init(identity: IdentityKeyPair, registrationId: UInt32) {
        self.identityKeyPair = identity
        self.registrationId = registrationId
    }

    private func account(for address: ProtocolAddress) throws -> AccountId {
        guard let account = AccountId(address: address) else {
            throw ProtocolStoreError.unsupportedAddress(address.name)
        }
        return account
    }

    // MARK: IdentityKeyStore

    public func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        identityKeyPair
    }

    public func localRegistrationId(context: StoreContext) throws -> UInt32 {
        registrationId
    }

    public func saveIdentity(
        _ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext
    ) throws -> IdentityChange {
        let account = try account(for: address)
        let key = identity.serialize()
        return state.withLock { state in
            guard let existing = state.identities[account] else {
                state.identities[account] = IdentityRecord(key: key, previous: nil, approved: true, verified: false)
                return .newOrUnchanged
            }
            if existing.key == key { return .newOrUnchanged }
            let approvedKey = existing.approved ? existing.key : existing.previous
            // That key's verification, if it had one.
            let approvedVerified = existing.approved ? existing.verified : existing.previousVerified
            let approvedRecord = existing.approved ? existing.verification : existing.previousVerification
            if key == approvedKey {
                // Back to the key the user already accepted: nothing left to
                // approve, and its verification stands (§5).
                state.identities[account] = IdentityRecord(
                    key: key, previous: nil, approved: true, verified: approvedVerified,
                    verification: approvedVerified ? approvedRecord : nil)
            } else {
                state.identities[account] = IdentityRecord(
                    key: key, previous: approvedKey, approved: false, verified: false,
                    previousVerified: approvedKey != nil && approvedVerified,
                    previousVerification: approvedKey != nil && approvedVerified ? approvedRecord : nil)
            }
            return .replacedExisting
        }
    }

    public func isTrustedIdentity(
        _ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext
    ) throws -> Bool {
        let account = try account(for: address)
        let key = identity.serialize()
        return state.withLock { state in
            guard let record = state.identities[account] else { return true }  // trust on first use
            switch direction {
            case .receiving:
                // Accept, so the message is not lost; saveIdentity then flags the
                // change and sending stays blocked until the user approves.
                return true
            case .sending:
                return record.approved && record.key == key
            }
        }
    }

    public func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let account = try account(for: address)
        guard let key = state.withLock({ $0.identities[account]?.key }) else { return nil }
        return try IdentityKey(bytes: key)
    }

    // MARK: PreKeyStore

    public func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let bytes = state.withLock({ $0.preKeys[id] }) else {
            throw SignalError.invalidKeyIdentifier("no prekey with id \(id)")
        }
        return try PreKeyRecord(bytes: bytes)
    }

    public func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        state.withLock { $0.preKeys[id] = bytes }
    }

    public func removePreKey(id: UInt32, context: StoreContext) throws {
        state.withLock { _ = $0.preKeys.removeValue(forKey: id) }
    }

    // MARK: SignedPreKeyStore

    public func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let bytes = state.withLock({ $0.signedPreKeys[id] }) else {
            throw SignalError.invalidKeyIdentifier("no signed prekey with id \(id)")
        }
        return try SignedPreKeyRecord(bytes: bytes)
    }

    public func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        state.withLock { $0.signedPreKeys[id] = bytes }
    }

    // MARK: KyberPreKeyStore

    public func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let entry = state.withLock({ $0.kyberPreKeys[id] }) else {
            throw SignalError.invalidKeyIdentifier("no kyber prekey with id \(id)")
        }
        return try KyberPreKeyRecord(bytes: entry.record)
    }

    public func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        state.withLock { state in
            let lastResort = state.kyberPreKeys[id]?.lastResort ?? false
            state.kyberPreKeys[id] = KyberEntry(record: bytes, lastResort: lastResort)
        }
    }

    public func markKyberPreKeyUsed(
        id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext
    ) throws {
        let use = LastResortUse(kyberId: id, signedId: signedPreKeyId, baseKey: baseKey.serialize())
        try state.withLock { state in
            guard let entry = state.kyberPreKeys[id] else {
                throw SignalError.invalidKeyIdentifier("no kyber prekey with id \(id)")
            }
            if entry.lastResort {
                guard state.lastResortUses.insert(use).inserted else {
                    throw SignalError.invalidMessage("reused base key")
                }
            } else {
                state.kyberPreKeys[id] = nil
            }
        }
    }

    // MARK: SessionStore

    public func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        let account = try account(for: address)
        guard let bytes = state.withLock({ $0.sessions[account] }) else { return nil }
        return try SessionRecord(bytes: bytes)
    }

    public func loadExistingSessions(
        for addresses: [ProtocolAddress], context: StoreContext
    ) throws -> [SessionRecord] {
        try addresses.map { address in
            guard let record = try loadSession(for: address, context: context) else {
                throw SignalError.sessionNotFound("\(address)")
            }
            return record
        }
    }

    public func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        let account = try account(for: address)
        let bytes = record.serialize()
        state.withLock { $0.sessions[account] = bytes }
    }

    // MARK: ProtocolStore

    public func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, lastResort: Bool) throws {
        let bytes = record.serialize()
        state.withLock { $0.kyberPreKeys[id] = KyberEntry(record: bytes, lastResort: lastResort) }
    }

    public func signedPreKeys() throws -> [SignedPreKeyRecord] {
        try state.withLock { Array($0.signedPreKeys.values) }.map { try SignedPreKeyRecord(bytes: $0) }
    }

    public func removeSignedPreKey(id: UInt32) throws {
        state.withLock { _ = $0.signedPreKeys.removeValue(forKey: id) }
    }

    public func lastResortKyberPreKeys() throws -> [KyberPreKeyRecord] {
        try state.withLock { $0.kyberPreKeys.values.filter(\.lastResort).map(\.record) }
            .map { try KyberPreKeyRecord(bytes: $0) }
    }

    public func removeKyberPreKey(id: UInt32) throws {
        state.withLock { state in
            state.kyberPreKeys[id] = nil
            state.lastResortUses = state.lastResortUses.filter { $0.kyberId != id }
        }
    }

    public func oneTimePreKeyCount() throws -> Int {
        state.withLock { $0.preKeys.count }
    }

    public func oneTimeKyberPreKeyCount() throws -> Int {
        state.withLock { $0.kyberPreKeys.values.filter { !$0.lastResort }.count }
    }

    public func reserveKeyIds(_ space: PreKeyIdSpace, count: Int) throws -> [UInt32] {
        state.withLock { state in
            var id = state.nextKeyIds[space, default: KeyIds.range.lowerBound]
            var ids: [UInt32] = []
            ids.reserveCapacity(count)
            for _ in 0..<count {
                ids.append(id)
                id = KeyIds.next(after: id)
            }
            state.nextKeyIds[space] = id
            return ids
        }
    }

    public func identityTrust(for account: AccountId) throws -> IdentityTrust {
        guard let record = state.withLock({ $0.identities[account] }) else { return .unknown }
        let current = try IdentityKey(bytes: record.key)
        if record.approved {
            return .trusted(current, verified: record.verified)
        }
        return .changed(previous: try record.previous.map { try IdentityKey(bytes: $0) }, current: current)
    }

    public func restoreIdentity(_ identity: IdentityKey, for account: AccountId, approved: Bool, verified: Bool) throws {
        let record = IdentityRecord(
            key: identity.serialize(), previous: nil, approved: approved, verified: approved && verified)
        state.withLock { $0.identities[account] = record }
    }

    public func approveIdentity(_ identity: IdentityKey, for account: AccountId) throws {
        let key = identity.serialize()
        state.withLock { state in
            let kept = state.identities[account].flatMap { $0.approved && $0.key == key && $0.verified ? $0 : nil }
            state.identities[account] = IdentityRecord(
                key: key, previous: nil, approved: true, verified: kept != nil, verification: kept?.verification)
        }
    }

    public func setIdentityVerified(_ verified: Bool, for account: AccountId) throws {
        state.withLock { state in
            guard var record = state.identities[account] else { return }
            if verified {
                // Verifying the current key in person also accepts it.
                if !(record.approved && record.verified) { record.verification = nil }
                record.approved = true
                record.previous = nil
                record.previousVerified = false
                record.previousVerification = nil
            } else {
                record.verification = nil
            }
            record.verified = verified
            state.identities[account] = record
        }
    }

    public func acceptIdentity(_ expected: IdentityKey, for account: AccountId) throws -> Bool {
        let key = expected.serialize()
        return state.withLock { state in
            guard let record = state.identities[account], record.key == key else { return false }
            if !record.approved {
                state.identities[account] = IdentityRecord(key: key, previous: nil, approved: true, verified: false)
            }
            return true
        }
    }

    public func verifyIdentity(_ expected: IdentityKey, for account: AccountId, verification: IdentityVerification)
        throws -> Bool
    {
        let key = expected.serialize()
        return state.withLock { state in
            guard let record = state.identities[account], record.key == key else { return false }
            state.identities[account] = IdentityRecord(
                key: key, previous: nil, approved: true, verified: true, verification: verification)
            return true
        }
    }

    public func pinVerifiedIdentity(
        _ identity: IdentityKey, for account: AccountId, verification: IdentityVerification
    ) throws -> Bool {
        let key = identity.serialize()
        return state.withLock { state in
            if let record = state.identities[account] {
                // Another key is held: a different one, or the approved key a
                // pending change to this one would replace (§5).
                guard record.key == key, record.approved || record.previous == nil else { return false }
            }
            state.identities[account] = IdentityRecord(
                key: key, previous: nil, approved: true, verified: true, verification: verification)
            return true
        }
    }

    public func identityVerification(for account: AccountId) throws -> IdentityVerification? {
        state.withLock { state in
            guard let record = state.identities[account], record.approved, record.verified else { return nil }
            return record.verification
        }
    }

    public func deleteSession(for account: AccountId) throws {
        state.withLock { _ = $0.sessions.removeValue(forKey: account) }
    }

    public func deleteContact(_ account: AccountId) throws {
        state.withLock { state in
            state.sessions[account] = nil
            state.identities[account] = nil
        }
    }
}
