// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Us2usKit

/// Marks the task that processes inbound envelopes (see
/// ``PersistentProtocolStore/withStagedInboundWrites(_:)``).
final class InboundStagingScope: Sendable {
    let id = UUID()
    let storeID: ObjectIdentifier

    init(storeID: ObjectIdentifier) {
        self.storeID = storeID
    }
}

enum StagingContext {
    @TaskLocal static var scope: InboundStagingScope?
}

/// ``ProtocolStore`` on the SQLCipher database, with exactly the semantics of
/// Us2usKit's ``InMemoryProtocolStore`` (PROTOCOL.md §5): TOFU with change
/// detection, one-time Kyber keys deleted when used, the last-resort key kept
/// with `(kyberId, signedId, baseKey)` tracked to reject reuse.
///
/// **Atomic receive.** A decryption advances the ratchet *before* the app has
/// stored the message; if both were committed separately, a crash in between
/// would lose the message for good (libsignal rejects the redelivered copy as
/// a duplicate). Inside ``withStagedInboundWrites(_:)`` the writes a
/// decryption makes (session, identity, used pre-keys) are therefore staged
/// in memory and committed in the same SQLite transaction as the next
/// ``LocalStorage/write(_:)`` of that task — the one that stores the message.
///
/// Staged state is never dropped behind anyone's back:
/// * every read sees it (read-your-writes for all callers);
/// * any write the store makes outside the scope (e.g. a concurrent send
///   advancing the same session) commits it first, so a sent message can
///   never be encrypted twice under the same chain key after a crash;
/// * it is committed when the scope ends, and
///   ``discardStagedInboundWrites()`` drops it only if nothing outside the
///   scope has seen it (so the relay's redelivery can be decrypted again).
public final class PersistentProtocolStore: ProtocolStore, TransactionParticipant, @unchecked Sendable {
    private struct IdentityRow: Equatable {
        var key: Data
        /// The last approved key while `key` awaits approval (`nil` if unknown).
        var previous: Data?
        var approved: Bool
        var verified: Bool
    }

    private struct LastResortUse: Hashable {
        var kyberId: UInt32
        var signedId: UInt32
        var baseKey: Data
    }

    /// Staged writes; guarded by the database lock.
    private struct Overlay {
        var sessions: [AccountId: Data] = [:]
        var identities: [AccountId: IdentityRow] = [:]
        var removedPreKeys: Set<UInt32> = []
        var removedKyberPreKeys: Set<UInt32> = []
        var lastResortUses: Set<LastResortUse> = []
        var contributors: Set<UUID> = []
        var observedOutsideScope = false

        var isEmpty: Bool {
            sessions.isEmpty && identities.isEmpty && removedPreKeys.isEmpty && removedKyberPreKeys.isEmpty
                && lastResortUses.isEmpty
        }
    }

    /// Exactly what one flush wrote into the open transaction. If that
    /// transaction rolls back, these writes become pending again.
    private final class FlushToken {
        let flushed: Overlay
        init(_ flushed: Overlay) { self.flushed = flushed }
    }

    private let database: EncryptedDatabase
    private let identityKeyPair: IdentityKeyPair
    private let registrationId: UInt32
    private var overlay = Overlay()

    private init(database: EncryptedDatabase, identity: IdentityKeyPair, registrationId: UInt32) {
        self.database = database
        self.identityKeyPair = identity
        self.registrationId = registrationId
    }

    /// Stores a newly generated (or restored, §5.5) identity and returns its store.
    static func create(
        database: EncryptedDatabase, identity: IdentityKeyPair, registrationId: UInt32
    ) throws -> PersistentProtocolStore {
        try database.write { connection in
            if try connection.queryOne("SELECT 1 FROM local_identity WHERE id = 1") != nil {
                throw StorageError.invalidState("an identity already exists")
            }
            try connection.run(
                "INSERT INTO local_identity (id, identity_key_pair, registration_id) VALUES (1, ?, ?)",
                [identity.serialize(), registrationId])
        }
        let store = PersistentProtocolStore(database: database, identity: identity, registrationId: registrationId)
        database.setParticipant(store)
        return store
    }

    /// The store of the existing identity, or `nil` before one was created.
    static func open(database: EncryptedDatabase) throws -> PersistentProtocolStore? {
        let row = try database.read { connection in
            try connection.queryOne("SELECT identity_key_pair, registration_id FROM local_identity WHERE id = 1")
        }
        guard let row else { return nil }
        guard let bytes = row.blob("identity_key_pair"), let registrationId = row.int64("registration_id"),
            let identity = try? IdentityKeyPair(bytes: bytes)
        else { throw ProtocolStoreError.corruptRecord }
        let store = PersistentProtocolStore(
            database: database, identity: identity, registrationId: UInt32(registrationId))
        database.setParticipant(store)
        return store
    }

    // MARK: Staging

    private var currentScope: InboundStagingScope? {
        guard let scope = StagingContext.scope, scope.storeID == ObjectIdentifier(self) else { return nil }
        return scope
    }

    /// Runs `operation` (typically `Messenger.process(_:)` or `Messenger.run`)
    /// with the decryption writes of this task staged until the task's next
    /// storage write transaction, which then commits them together with the
    /// stored message. Whatever is still staged when `operation` returns or
    /// throws is committed.
    public func withStagedInboundWrites<T>(_ operation: () async throws -> T) async throws -> T {
        if currentScope != nil { return try await operation() }
        let scope = InboundStagingScope(storeID: ObjectIdentifier(self))
        do {
            let result = try await StagingContext.$scope.withValue(scope) { try await operation() }
            try flushStagedWrites()
            return result
        } catch {
            try? flushStagedWrites()
            throw error
        }
    }

    /// Drops the writes staged by the current scope, if nothing outside the
    /// scope has read or built on them; otherwise commits them. Call it when
    /// a decrypted message could not be stored (`retryLater`), so the relay's
    /// redelivery decrypts again. Returns whether the writes were dropped.
    @discardableResult
    public func discardStagedInboundWrites() throws -> Bool {
        let discarded = try database.locked { _ -> Bool in
            guard !overlay.isEmpty else { return true }
            guard let scope = currentScope, overlay.contributors == [scope.id], !overlay.observedOutsideScope else {
                return false
            }
            overlay = Overlay()
            return true
        }
        if !discarded { try flushStagedWrites() }
        return discarded
    }

    /// Commits whatever is staged, right now.
    public func flushStagedWrites() throws {
        let pending = try database.locked { _ in !overlay.isEmpty }
        guard pending else { return }
        try database.write(protocolStore: true) { _ in }
    }

    /// Whether writes are staged (for tests and diagnostics).
    public var hasStagedWrites: Bool {
        (try? database.locked { _ in !overlay.isEmpty }) ?? false
    }

    func transactionDidBegin(_ connection: SQLiteConnection, initiatedByProtocolStore: Bool) throws -> AnyObject? {
        guard !overlay.isEmpty, initiatedByProtocolStore || currentScope != nil else { return nil }
        for (account, record) in overlay.sessions {
            try connection.run(
                "INSERT OR REPLACE INTO sessions (account, record) VALUES (?, ?)", [account.bytes, record])
        }
        for (account, row) in overlay.identities {
            try Self.writeIdentity(row, for: account, connection)
        }
        for id in overlay.removedPreKeys {
            try connection.run("DELETE FROM pre_keys WHERE id = ?", [id])
        }
        for id in overlay.removedKyberPreKeys {
            try connection.run("DELETE FROM kyber_pre_keys WHERE id = ? AND last_resort = 0", [id])
        }
        for use in overlay.lastResortUses {
            try connection.run(
                "INSERT OR IGNORE INTO kyber_last_resort_uses (kyber_id, signed_id, base_key) VALUES (?, ?, ?)",
                [use.kyberId, use.signedId, use.baseKey])
        }
        // From here on the transaction holds these writes: reads go to the
        // database, which also sees any newer write made in the same
        // transaction (a flushed overlay must never shadow those).
        let flushed = overlay
        overlay = Overlay()
        return FlushToken(flushed)
    }

    func transactionDidRollBack(token: AnyObject) {
        // Called under the database lock, right after ROLLBACK. Restore what
        // the transaction would have committed, unless restaged since.
        guard let flushed = (token as? FlushToken)?.flushed else { return }
        for (account, record) in flushed.sessions where overlay.sessions[account] == nil {
            overlay.sessions[account] = record
        }
        for (account, row) in flushed.identities where overlay.identities[account] == nil {
            overlay.identities[account] = row
        }
        overlay.removedPreKeys.formUnion(flushed.removedPreKeys)
        overlay.removedKyberPreKeys.formUnion(flushed.removedKyberPreKeys)
        overlay.lastResortUses.formUnion(flushed.lastResortUses)
        overlay.contributors.formUnion(flushed.contributors)
        overlay.observedOutsideScope = overlay.observedOutsideScope || flushed.observedOutsideScope
    }

    /// Records that a read hit staged state; outside the staging scope this
    /// forbids discarding it later.
    private func noteOverlayHit() {
        if let scope = currentScope, overlay.contributors.isSubset(of: [scope.id]) { return }
        overlay.observedOutsideScope = true
    }

    private func stage(_ change: (inout Overlay) -> Void, scope: InboundStagingScope) {
        change(&overlay)
        overlay.contributors.insert(scope.id)
    }

    // MARK: Helpers

    private func account(for address: ProtocolAddress) throws -> AccountId {
        guard address.deviceId == AccountId.deviceId, let serviceId = address.serviceId, serviceId.kind == .aci
        else {
            throw ProtocolStoreError.unsupportedAddress(address.name)
        }
        return AccountId(uuid: serviceId.rawUUID)
    }

    private func identityRow(for account: AccountId, _ connection: SQLiteConnection) throws -> IdentityRow? {
        if let staged = overlay.identities[account] {
            noteOverlayHit()
            return staged
        }
        return try Self.storedIdentityRow(for: account, connection)
    }

    private static func storedIdentityRow(for account: AccountId, _ connection: SQLiteConnection) throws
        -> IdentityRow?
    {
        guard
            let row = try connection.queryOne(
                "SELECT identity_key, previous_key, approved, verified FROM identities WHERE account = ?",
                [account.bytes])
        else { return nil }
        guard let key = row.blob("identity_key") else { throw ProtocolStoreError.corruptRecord }
        return IdentityRow(
            key: key, previous: row.blob("previous_key"), approved: row.bool("approved"),
            verified: row.bool("verified"))
    }

    private static func writeIdentity(_ row: IdentityRow, for account: AccountId, _ connection: SQLiteConnection)
        throws
    {
        try connection.run(
            """
            INSERT OR REPLACE INTO identities (account, identity_key, previous_key, approved, verified)
            VALUES (?, ?, ?, ?, ?)
            """, [account.bytes, row.key, row.previous, row.approved, row.verified])
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
        func apply(_ existing: IdentityRow?) -> (IdentityRow?, IdentityChange) {
            guard let existing else {
                return (IdentityRow(key: key, previous: nil, approved: true, verified: false), .newOrUnchanged)
            }
            if existing.key == key { return (nil, .newOrUnchanged) }
            let approvedKey = existing.approved ? existing.key : existing.previous
            if key == approvedKey {
                // Back to the key the user already accepted: nothing left to approve.
                return (IdentityRow(key: key, previous: nil, approved: true, verified: false), .replacedExisting)
            }
            return (IdentityRow(key: key, previous: approvedKey, approved: false, verified: false), .replacedExisting)
        }
        if let scope = currentScope {
            return try database.locked { connection in
                let (row, change) = apply(try identityRow(for: account, connection))
                if let row { stage({ $0.identities[account] = row }, scope: scope) }
                return change
            }
        }
        return try database.write(protocolStore: true) { connection in
            let (row, change) = apply(try Self.storedIdentityRow(for: account, connection))
            if let row { try Self.writeIdentity(row, for: account, connection) }
            return change
        }
    }

    public func isTrustedIdentity(
        _ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext
    ) throws -> Bool {
        let account = try account(for: address)
        let key = identity.serialize()
        let row = try database.locked { connection in try identityRow(for: account, connection) }
        guard let row else { return true }  // trust on first use
        switch direction {
        case .receiving:
            // Accept, so the message is not lost; saveIdentity then flags the
            // change and sending stays blocked until the user approves.
            return true
        case .sending:
            return row.approved && row.key == key
        }
    }

    public func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let account = try account(for: address)
        guard let key = try database.locked({ connection in try identityRow(for: account, connection)?.key }) else {
            return nil
        }
        return try IdentityKey(bytes: key)
    }

    // MARK: PreKeyStore

    public func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        let bytes = try database.locked { connection -> Data? in
            if overlay.removedPreKeys.contains(id) {
                noteOverlayHit()
                return nil
            }
            return try connection.queryOne("SELECT record FROM pre_keys WHERE id = ?", [id])?.blob("record")
        }
        guard let bytes else { throw SignalError.invalidKeyIdentifier("no prekey with id \(id)") }
        return try PreKeyRecord(bytes: bytes)
    }

    public func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        try database.write(protocolStore: true) { connection in
            try connection.run("INSERT OR REPLACE INTO pre_keys (id, record) VALUES (?, ?)", [id, bytes])
        }
    }

    public func removePreKey(id: UInt32, context: StoreContext) throws {
        if let scope = currentScope {
            try database.locked { _ in stage({ $0.removedPreKeys.insert(id) }, scope: scope) }
            return
        }
        try database.write(protocolStore: true) { connection in
            try connection.run("DELETE FROM pre_keys WHERE id = ?", [id])
        }
    }

    // MARK: SignedPreKeyStore

    public func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        let bytes = try database.locked { connection in
            try connection.queryOne("SELECT record FROM signed_pre_keys WHERE id = ?", [id])?.blob("record")
        }
        guard let bytes else { throw SignalError.invalidKeyIdentifier("no signed prekey with id \(id)") }
        return try SignedPreKeyRecord(bytes: bytes)
    }

    public func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        try database.write(protocolStore: true) { connection in
            try connection.run("INSERT OR REPLACE INTO signed_pre_keys (id, record) VALUES (?, ?)", [id, bytes])
        }
    }

    // MARK: KyberPreKeyStore

    public func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        let bytes = try database.locked { connection -> Data? in
            if overlay.removedKyberPreKeys.contains(id) {
                noteOverlayHit()
                return nil
            }
            return try connection.queryOne("SELECT record FROM kyber_pre_keys WHERE id = ?", [id])?.blob("record")
        }
        guard let bytes else { throw SignalError.invalidKeyIdentifier("no kyber prekey with id \(id)") }
        return try KyberPreKeyRecord(bytes: bytes)
    }

    public func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        let bytes = record.serialize()
        try database.write(protocolStore: true) { connection in
            // Keeps the last-resort flag of an existing entry.
            try connection.run(
                """
                INSERT INTO kyber_pre_keys (id, record, last_resort) VALUES (?, ?, 0)
                ON CONFLICT(id) DO UPDATE SET record = excluded.record
                """, [id, bytes])
        }
    }

    public func markKyberPreKeyUsed(
        id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext
    ) throws {
        let use = LastResortUse(kyberId: id, signedId: signedPreKeyId, baseKey: baseKey.serialize())
        func lastResortFlag(_ connection: SQLiteConnection) throws -> Bool {
            guard let row = try connection.queryOne("SELECT last_resort FROM kyber_pre_keys WHERE id = ?", [id])
            else {
                throw SignalError.invalidKeyIdentifier("no kyber prekey with id \(id)")
            }
            return row.bool("last_resort")
        }
        func alreadyUsed(_ connection: SQLiteConnection) throws -> Bool {
            try connection.queryOne(
                "SELECT 1 FROM kyber_last_resort_uses WHERE kyber_id = ? AND signed_id = ? AND base_key = ?",
                [use.kyberId, use.signedId, use.baseKey]) != nil
        }
        if let scope = currentScope {
            try database.locked { connection in
                if overlay.removedKyberPreKeys.contains(id) {
                    noteOverlayHit()
                    throw SignalError.invalidKeyIdentifier("no kyber prekey with id \(id)")
                }
                if try lastResortFlag(connection) {
                    if overlay.lastResortUses.contains(use) { noteOverlayHit() }
                    guard !overlay.lastResortUses.contains(use), try !alreadyUsed(connection) else {
                        throw SignalError.invalidMessage("reused base key")
                    }
                    stage({ $0.lastResortUses.insert(use) }, scope: scope)
                } else {
                    stage({ $0.removedKyberPreKeys.insert(id) }, scope: scope)
                }
            }
            return
        }
        try database.write(protocolStore: true) { connection in
            if try lastResortFlag(connection) {
                guard try !alreadyUsed(connection) else { throw SignalError.invalidMessage("reused base key") }
                try connection.run(
                    "INSERT INTO kyber_last_resort_uses (kyber_id, signed_id, base_key) VALUES (?, ?, ?)",
                    [use.kyberId, use.signedId, use.baseKey])
            } else {
                try connection.run("DELETE FROM kyber_pre_keys WHERE id = ?", [id])
            }
        }
    }

    // MARK: SessionStore

    public func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        let account = try account(for: address)
        let bytes = try database.locked { connection -> Data? in
            if let staged = overlay.sessions[account] {
                noteOverlayHit()
                return staged
            }
            return try connection.queryOne("SELECT record FROM sessions WHERE account = ?", [account.bytes])?
                .blob("record")
        }
        return try bytes.map { try SessionRecord(bytes: $0) }
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
        if let scope = currentScope {
            try database.locked { _ in stage({ $0.sessions[account] = bytes }, scope: scope) }
            return
        }
        try database.write(protocolStore: true) { connection in
            try connection.run(
                "INSERT OR REPLACE INTO sessions (account, record) VALUES (?, ?)", [account.bytes, bytes])
        }
    }

    // MARK: ProtocolStore

    public func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, lastResort: Bool) throws {
        let bytes = record.serialize()
        try database.write(protocolStore: true) { connection in
            try connection.run(
                "INSERT OR REPLACE INTO kyber_pre_keys (id, record, last_resort) VALUES (?, ?, ?)",
                [id, bytes, lastResort])
        }
    }

    public func signedPreKeys() throws -> [SignedPreKeyRecord] {
        try database.locked { connection in
            try connection.query("SELECT record FROM signed_pre_keys ORDER BY id").compactMap { $0.blob("record") }
        }.map { try SignedPreKeyRecord(bytes: $0) }
    }

    public func removeSignedPreKey(id: UInt32) throws {
        try database.write(protocolStore: true) { connection in
            try connection.run("DELETE FROM signed_pre_keys WHERE id = ?", [id])
        }
    }

    public func lastResortKyberPreKeys() throws -> [KyberPreKeyRecord] {
        try database.locked { connection in
            try connection.query("SELECT record FROM kyber_pre_keys WHERE last_resort = 1 ORDER BY id")
                .compactMap { $0.blob("record") }
        }.map { try KyberPreKeyRecord(bytes: $0) }
    }

    public func removeKyberPreKey(id: UInt32) throws {
        try database.write(protocolStore: true) { connection in
            try connection.run("DELETE FROM kyber_pre_keys WHERE id = ?", [id])
            try connection.run("DELETE FROM kyber_last_resort_uses WHERE kyber_id = ?", [id])
        }
    }

    public func oneTimePreKeyCount() throws -> Int {
        try database.locked { connection in
            var count = Int(try connection.queryOne("SELECT count(*) AS n FROM pre_keys")?.int64("n") ?? 0)
            for id in overlay.removedPreKeys
            where try connection.queryOne("SELECT 1 FROM pre_keys WHERE id = ?", [id]) != nil {
                count -= 1
            }
            return count
        }
    }

    public func oneTimeKyberPreKeyCount() throws -> Int {
        try database.locked { connection in
            var count = Int(
                try connection.queryOne("SELECT count(*) AS n FROM kyber_pre_keys WHERE last_resort = 0")?.int64("n")
                    ?? 0)
            for id in overlay.removedKyberPreKeys
            where try connection.queryOne(
                "SELECT 1 FROM kyber_pre_keys WHERE id = ? AND last_resort = 0", [id]) != nil
            {
                count -= 1
            }
            return count
        }
    }

    /// Key ids are 1…16777215 (§4.3, never libsignal's 0xFFFFFFFF sentinel).
    static let keyIdRange: ClosedRange<UInt32> = 1...0xFF_FFFF

    public func reserveKeyIds(_ space: PreKeyIdSpace, count: Int) throws -> [UInt32] {
        let name: String
        switch space {
        case .oneTime: name = "oneTime"
        case .signed: name = "signed"
        case .kyber: name = "kyber"
        }
        return try database.write(protocolStore: true) { connection in
            var id = UInt32(
                clamping: try connection.queryOne("SELECT next_id FROM key_id_counters WHERE space = ?", [name])?
                    .int64("next_id") ?? Int64(Self.keyIdRange.lowerBound))
            if !Self.keyIdRange.contains(id) { id = Self.keyIdRange.lowerBound }
            var ids: [UInt32] = []
            ids.reserveCapacity(max(0, count))
            for _ in 0..<max(0, count) {
                ids.append(id)
                id = id >= Self.keyIdRange.upperBound ? Self.keyIdRange.lowerBound : id + 1
            }
            try connection.run(
                "INSERT OR REPLACE INTO key_id_counters (space, next_id) VALUES (?, ?)", [name, id])
            return ids
        }
    }

    public func identityTrust(for account: AccountId) throws -> IdentityTrust {
        guard let row = try database.locked({ connection in try identityRow(for: account, connection) }) else {
            return .unknown
        }
        let current = try IdentityKey(bytes: row.key)
        if row.approved {
            return .trusted(current, verified: row.verified)
        }
        return .changed(previous: try row.previous.map { try IdentityKey(bytes: $0) }, current: current)
    }

    public func approveIdentity(_ identity: IdentityKey, for account: AccountId) throws {
        let key = identity.serialize()
        try database.write(protocolStore: true) { connection in
            let existing = try Self.storedIdentityRow(for: account, connection)
            let verified = existing.map { $0.approved && $0.key == key && $0.verified } ?? false
            try Self.writeIdentity(
                IdentityRow(key: key, previous: nil, approved: true, verified: verified), for: account, connection)
        }
    }

    public func setIdentityVerified(_ verified: Bool, for account: AccountId) throws {
        try database.write(protocolStore: true) { connection in
            guard var row = try Self.storedIdentityRow(for: account, connection) else { return }
            row.verified = verified
            if verified {
                // Verifying the current key in person also accepts it.
                row.approved = true
                row.previous = nil
            }
            try Self.writeIdentity(row, for: account, connection)
        }
    }

    public func restoreIdentity(_ identity: IdentityKey, for account: AccountId, approved: Bool, verified: Bool) throws {
        let row = IdentityRow(key: identity.serialize(), previous: nil, approved: approved, verified: approved && verified)
        try database.write(protocolStore: true) { connection in
            try Self.writeIdentity(row, for: account, connection)
        }
    }

    public func deleteSession(for account: AccountId) throws {
        try database.write(protocolStore: true) { connection in
            try connection.run("DELETE FROM sessions WHERE account = ?", [account.bytes])
        }
    }

    public func deleteContact(_ account: AccountId) throws {
        try database.write(protocolStore: true) { connection in
            try deleteProtocolState(of: account, connection)
        }
    }

    /// Deletes the session and identity of `account` inside an open
    /// transaction (ghost mode `wipe_contact`, §8.2), including anything
    /// staged for it once the transaction commits, so a later flush cannot
    /// bring the session back.
    func deleteProtocolState(of account: AccountId, _ connection: SQLiteConnection) throws {
        try connection.run("DELETE FROM sessions WHERE account = ?", [account.bytes])
        try connection.run("DELETE FROM identities WHERE account = ?", [account.bytes])
        database.afterCommit { [self] in
            overlay.sessions[account] = nil
            overlay.identities[account] = nil
        }
    }
}
