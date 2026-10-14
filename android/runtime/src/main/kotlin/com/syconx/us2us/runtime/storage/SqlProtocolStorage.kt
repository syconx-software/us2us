package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.store.KeyIdCounters
import com.syconx.us2us.core.store.ProtocolStorage
import com.syconx.us2us.core.store.StoredIdentity
import com.syconx.us2us.core.store.StoredKyberPreKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.time.Clock

/**
 * The core's [ProtocolStorage] on the SQLCipher database, in the iOS app's tables (`identities`,
 * `pre_keys`, `signed_pre_keys`, `kyber_pre_keys`, `kyber_last_resort_uses`, `sessions`,
 * `key_id_counters`). The core's `ProtocolStore` keeps every protocol rule; this class stores and
 * loads bytes, plus the bookkeeping the iOS store keeps next to them (the previous approved key
 * and when and how a key was verified).
 *
 * **Atomic receive.** Inside [withStagedInboundWrites] a decryption's writes (session, identity,
 * used one-time keys, last-resort uses) are staged and committed with the next app write of the
 * same coroutine, which stores the message ([InboundStaging]). Every read sees staged state; every
 * write made outside the scope commits it first.
 *
 * Only device 1 exists (protocol v1): sessions of other device ids are never stored.
 */
class SqlProtocolStorage internal constructor(
    private val storage: LocalStorage,
    private val clock: Clock = Clock.systemUTC(),
) : ProtocolStorage {
    private val database get() = storage.database
    private val staging get() = storage.staging

    // MARK: Staging

    /**
     * Runs [operation] (the core's receive loop, or one envelope) with the decryption writes of this
     * coroutine staged until its next [LocalStorage.write], which then commits them together with
     * the stored message. Whatever is still staged when [operation] returns or throws is committed.
     *
     * Sending inside the scope (receipts, presence replies, sharing the profile) would stage the
     * advanced sending chain too; run those through [withoutStaging].
     */
    suspend fun <T> withStagedInboundWrites(operation: suspend () -> T): T = staging.withStagedInboundWrites(operation)

    /** Runs [operation] outside the staging scope (see [InboundStaging.withoutStaging]). */
    suspend fun <T> withoutStaging(operation: suspend () -> T): T = staging.withoutStaging(operation)

    /**
     * Drops the writes staged by the current scope, if nothing outside it has seen them; otherwise
     * commits them. Call it when a decrypted message could not be stored, so the redelivery
     * decrypts again. Returns whether they were dropped.
     */
    fun discardStagedInboundWrites(): Boolean = staging.discardStagedInboundWrites()

    /** Commits whatever is staged, right now. */
    fun flushStagedWrites() = staging.flushStagedWrites()

    /** Whether writes are staged (for tests and diagnostics). */
    val hasStagedWrites: Boolean get() = staging.hasStagedWrites

    // MARK: Identities

    override fun loadIdentity(name: String): StoredIdentity? {
        val account = account(name)
        val row = database.locked { connection -> staging.identityRow(account, connection) } ?: return null
        return StoredIdentity(
            identityKey = row.key.toByteArray(),
            trust = if (row.approved) IdentityTrust.TRUSTED else IdentityTrust.CHANGED,
            verified = row.verified,
            // Not kept: the iOS schema has no such column, and the core only carries it along.
            firstSeenAt = 0L,
        )
    }

    override fun storeIdentity(name: String, identity: StoredIdentity) {
        val account = account(name)
        val now = clock.millis()
        val scope = staging.currentScope
        if (scope != null) {
            database.locked { connection ->
                val row = rowFor(identity, staging.identityRow(account, connection), now)
                staging.stage(scope) { it.identities[account] = row }
            }
            return
        }
        database.write(Writer.PROTOCOL_STORE) { connection ->
            ProtocolRows.writeIdentity(connection, account, rowFor(identity, ProtocolRows.storedIdentityRow(connection, account), now))
        }
    }

    override fun removeIdentity(name: String) {
        val account = account(name)
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run("DELETE FROM identities WHERE account = ?", account.toBytes())
            database.afterCommit { staging.overlay.identities.remove(account) }
        }
    }

    // MARK: One-time EC pre-keys

    override fun loadPreKey(id: Int): ByteArray? = database.locked { connection ->
        if (id in staging.overlay.removedPreKeys) {
            staging.noteOverlayHit()
            return@locked null
        }
        connection.queryOne("SELECT record FROM pre_keys WHERE id = ?", id)?.blob("record")
    }

    override fun storePreKey(id: Int, record: ByteArray) {
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run("INSERT OR REPLACE INTO pre_keys (id, record) VALUES (?, ?)", id, record)
        }
    }

    override fun removePreKey(id: Int) {
        val scope = staging.currentScope
        if (scope != null) {
            database.locked { staging.stage(scope) { it.removedPreKeys += id } }
            return
        }
        database.write(Writer.PROTOCOL_STORE) { connection -> connection.run("DELETE FROM pre_keys WHERE id = ?", id) }
    }

    // MARK: Signed EC pre-keys

    override fun loadSignedPreKey(id: Int): ByteArray? = database.locked { connection ->
        connection.queryOne("SELECT record FROM signed_pre_keys WHERE id = ?", id)?.blob("record")
    }

    override fun loadSignedPreKeys(): List<ByteArray> = database.locked { connection ->
        connection.query("SELECT record FROM signed_pre_keys ORDER BY id").mapNotNull { it.blob("record") }
    }

    override fun storeSignedPreKey(id: Int, record: ByteArray) {
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run("INSERT OR REPLACE INTO signed_pre_keys (id, record) VALUES (?, ?)", id, record)
        }
    }

    override fun removeSignedPreKey(id: Int) {
        database.write(Writer.PROTOCOL_STORE) { connection -> connection.run("DELETE FROM signed_pre_keys WHERE id = ?", id) }
    }

    // MARK: Kyber pre-keys

    override fun loadKyberPreKey(id: Int): StoredKyberPreKey? = database.locked { connection ->
        if (id in staging.overlay.removedKyberPreKeys) {
            staging.noteOverlayHit()
            return@locked null
        }
        connection.queryOne("SELECT record, last_resort FROM kyber_pre_keys WHERE id = ?", id)?.let { row ->
            StoredKyberPreKey(id, row.blob("record") ?: storageFailure(StorageError.Integrity), row.bool("last_resort"))
        }
    }

    override fun loadKyberPreKeys(): List<StoredKyberPreKey> = database.locked { connection ->
        connection.query("SELECT id, record, last_resort FROM kyber_pre_keys ORDER BY id").mapNotNull { row ->
            val id = row.int("id") ?: return@mapNotNull null
            if (id in staging.overlay.removedKyberPreKeys) {
                staging.noteOverlayHit()
                return@mapNotNull null
            }
            StoredKyberPreKey(id, row.blob("record") ?: return@mapNotNull null, row.bool("last_resort"))
        }
    }

    override fun storeKyberPreKey(key: StoredKyberPreKey) {
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run(
                "INSERT OR REPLACE INTO kyber_pre_keys (id, record, last_resort) VALUES (?, ?, ?)",
                key.id,
                key.record,
                key.lastResort,
            )
        }
    }

    override fun removeKyberPreKey(id: Int) {
        val scope = staging.currentScope
        if (scope != null) {
            // A used one-time key is staged like the session it opened; a last-resort key is only
            // ever removed by maintenance, which writes through.
            val staged = database.locked { connection ->
                val oneTime = connection.queryOne("SELECT 1 FROM kyber_pre_keys WHERE id = ? AND last_resort = 0", id) != null
                if (oneTime) staging.stage(scope) { it.removedKyberPreKeys += id }
                oneTime
            }
            if (staged) return
        }
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run("DELETE FROM kyber_pre_keys WHERE id = ?", id)
            connection.run("DELETE FROM kyber_last_resort_uses WHERE kyber_id = ?", id)
        }
    }

    override fun recordKyberBaseKey(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray): Boolean {
        val use = LastResortUse(kyberPreKeyId, signedPreKeyId, ByteString.copyFrom(baseKey))
        val scope = staging.currentScope
        if (scope != null) {
            return database.locked { connection ->
                if (use in staging.overlay.lastResortUses) {
                    staging.noteOverlayHit()
                    return@locked false
                }
                if (alreadyUsed(connection, use)) return@locked false
                staging.stage(scope) { it.lastResortUses += use }
                true
            }
        }
        return database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run(
                "INSERT OR IGNORE INTO kyber_last_resort_uses (kyber_id, signed_id, base_key) VALUES (?, ?, ?)",
                use.kyberId,
                use.signedId,
                use.baseKey,
            ) > 0
        }
    }

    private fun alreadyUsed(connection: SqlConnection, use: LastResortUse): Boolean = connection.queryOne(
        "SELECT 1 FROM kyber_last_resort_uses WHERE kyber_id = ? AND signed_id = ? AND base_key = ?",
        use.kyberId,
        use.signedId,
        use.baseKey,
    ) != null

    // MARK: Sessions

    override fun loadSession(name: String, deviceId: Int): ByteArray? {
        if (deviceId != AccountId.DEVICE_ID) return null
        val account = account(name)
        return database.locked { connection ->
            staging.overlay.sessions[account]?.let {
                staging.noteOverlayHit()
                return@locked it.copyOf()
            }
            connection.queryOne("SELECT record FROM sessions WHERE account = ?", account.toBytes())?.blob("record")
        }
    }

    override fun storeSession(name: String, deviceId: Int, record: ByteArray) {
        require(deviceId == AccountId.DEVICE_ID) { "unsupported address" }
        val account = account(name)
        val bytes = record.copyOf()
        val scope = staging.currentScope
        if (scope != null) {
            database.locked { staging.stage(scope) { it.sessions[account] = bytes } }
            return
        }
        database.write(Writer.PROTOCOL_STORE) { connection -> ProtocolRows.writeSession(connection, account, bytes) }
    }

    override fun removeSession(name: String, deviceId: Int) {
        if (deviceId != AccountId.DEVICE_ID) return
        removeAllSessions(name)
    }

    override fun removeAllSessions(name: String) {
        val account = account(name)
        database.write(Writer.PROTOCOL_STORE) { connection ->
            connection.run("DELETE FROM sessions WHERE account = ?", account.toBytes())
            database.afterCommit { staging.overlay.sessions.remove(account) }
        }
    }

    override fun sessionDeviceIds(name: String): List<Int> {
        val account = account(name)
        return database.locked { connection ->
            val staged = account in staging.overlay.sessions
            if (staged) staging.noteOverlayHit()
            if (staged || connection.queryOne("SELECT 1 FROM sessions WHERE account = ?", account.toBytes()) != null) {
                listOf(AccountId.DEVICE_ID)
            } else {
                emptyList()
            }
        }
    }

    // MARK: Key id counters

    override fun loadKeyIdCounters(): KeyIdCounters? = database.locked { connection ->
        val counters = connection.query("SELECT space, next_id FROM key_id_counters").associate { row ->
            row.text("space") to (row.int("next_id") ?: KEY_ID_RANGE.first)
        }
        if (counters.isEmpty()) return@locked null
        KeyIdCounters(
            nextPreKeyId = counters[ONE_TIME] ?: KEY_ID_RANGE.first,
            nextSignedPreKeyId = counters[SIGNED] ?: KEY_ID_RANGE.first,
            nextKyberPreKeyId = counters[KYBER] ?: KEY_ID_RANGE.first,
        )
    }

    override fun storeKeyIdCounters(counters: KeyIdCounters) {
        database.write(Writer.PROTOCOL_STORE) { connection ->
            for ((space, next) in listOf(ONE_TIME to counters.nextPreKeyId, SIGNED to counters.nextSignedPreKeyId, KYBER to counters.nextKyberPreKeyId)) {
                connection.run("INSERT OR REPLACE INTO key_id_counters (space, next_id) VALUES (?, ?)", space, next)
            }
        }
    }

    // MARK: Identity verification bound to keys (PROTOCOL.md §5, the iOS store's calls)

    /** The contact's identity as the storage holds it. */
    fun identityTrust(account: AccountId): ContactIdentity {
        val row = database.locked { connection -> staging.identityRow(account, connection) } ?: return ContactIdentity.Unknown
        val current = identityKey(row.key)
        return if (row.approved) {
            ContactIdentity.Trusted(current, row.verified)
        } else {
            ContactIdentity.Changed(row.previous?.let(::identityKey), current)
        }
    }

    /** When and how the contact's current key was verified, if it is verified and approved. */
    fun identityVerification(account: AccountId): IdentityVerification? =
        database.locked { connection -> staging.identityRow(account, connection) }?.verification

    /**
     * Accepts [identity] as the contact's key (the user acknowledged a change). A verification of
     * exactly this key survives; anything else is dropped with the old key.
     */
    fun approveIdentity(identity: IdentityKey, account: AccountId) {
        val key = ByteString.copyFrom(identity.serialize())
        database.write(Writer.PROTOCOL_STORE) { connection ->
            val existing = ProtocolRows.storedIdentityRow(connection, account)
            val kept = existing?.takeIf { it.approved && it.key == key && it.verified }
            val row = IdentityRow(key, previous = null, approved = true, verified = kept != null).recording(kept?.verification)
            ProtocolRows.writeIdentity(connection, account, row)
        }
    }

    /** Verifying the current key in person also accepts it; unverifying drops the record. */
    fun setIdentityVerified(verified: Boolean, account: AccountId) {
        database.write(Writer.PROTOCOL_STORE) { connection ->
            var row = ProtocolRows.storedIdentityRow(connection, account) ?: return@write
            if (verified) {
                if (!(row.approved && row.verified)) row = row.recording(null)
                row = row.copy(approved = true, previous = null)
            } else {
                row = row.recording(null)
            }
            ProtocolRows.writeIdentity(connection, account, row.copy(verified = verified))
        }
    }

    // The key-bound calls below read inside the write transaction, which first commits whatever a
    // decryption staged: they always compare with the newest key, and nothing can change it in between.

    /** Accepts a change only if [expected] (the key shown to the user) is still the stored one. */
    fun acceptIdentity(expected: IdentityKey, account: AccountId): Boolean {
        val key = ByteString.copyFrom(expected.serialize())
        return database.write(Writer.PROTOCOL_STORE) { connection ->
            val row = ProtocolRows.storedIdentityRow(connection, account)
            if (row == null || row.key != key) return@write false
            if (!row.approved) ProtocolRows.writeIdentity(connection, account, IdentityRow(key, null, approved = true, verified = false))
            true
        }
    }

    /** Verifies the stored key only if it is [expected] (the one compared); also accepts it. */
    fun verifyIdentity(expected: IdentityKey, account: AccountId, verification: IdentityVerification): Boolean {
        val key = ByteString.copyFrom(expected.serialize())
        return database.write(Writer.PROTOCOL_STORE) { connection ->
            val stored = ProtocolRows.storedIdentityRow(connection, account)
            if (stored == null || stored.key != key) return@write false
            ProtocolRows.writeIdentity(connection, account, IdentityRow(key, null, approved = true, verified = true).recording(verification))
            true
        }
    }

    /**
     * A contact code pins [identity] when no key is stored, or verifies it when it is the stored
     * one. Another stored key — a different one, or the approved key a pending change to this one
     * would replace — is left alone, and `false` returned (§5).
     */
    fun pinVerifiedIdentity(identity: IdentityKey, account: AccountId, verification: IdentityVerification): Boolean {
        val key = ByteString.copyFrom(identity.serialize())
        return database.write(Writer.PROTOCOL_STORE) { connection ->
            val stored = ProtocolRows.storedIdentityRow(connection, account)
            if (stored != null && (stored.key != key || !(stored.approved || stored.previous == null))) return@write false
            ProtocolRows.writeIdentity(connection, account, IdentityRow(key, null, approved = true, verified = true).recording(verification))
            true
        }
    }

    /** Deletes the session and identity of [account] inside an open transaction, staged state included once it commits. */
    internal fun deleteProtocolState(account: AccountId, connection: SqlConnection) {
        ProtocolRows.deleteProtocolState(connection, account)
        staging.forgetAfterCommit(account)
    }

    // MARK: Helpers

    private fun account(name: String): AccountId = try {
        AccountId.parse(name)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("unsupported address", e)
    }

    private fun identityKey(bytes: ByteString): IdentityKey = try {
        IdentityKey(bytes.toByteArray())
    } catch (e: InvalidKeyException) {
        storageFailure(StorageError.Integrity, e)
    }

    /**
     * What to store for the core's [identity], given the row it replaces: the previous approved key
     * and its verification are kept while a change awaits approval, and a verification the user
     * starts on the stored key (the core's `setVerified`) gets a record. A key that arrives already
     * verified (restored from a backup) has none: no date is made up.
     */
    private fun rowFor(identity: StoredIdentity, existing: IdentityRow?, now: Long): IdentityRow {
        val key = ByteString.copyFrom(identity.identityKey)
        val approved = identity.trust == IdentityTrust.TRUSTED
        val verified = approved && identity.verified
        fun newRecord(): IdentityVerification? =
            if (verified) IdentityVerification(IdentityVerificationMethod.MANUAL, java.time.Instant.ofEpochMilli(now)) else null
        if (existing == null) return IdentityRow(key, null, approved, verified)
        if (existing.key == key) {
            val record = when {
                !verified -> null
                existing.approved && existing.verified -> existing.verification ?: newRecord()
                else -> newRecord()
            }
            return IdentityRow(
                key = key,
                previous = if (approved) null else existing.previous,
                approved = approved,
                verified = verified,
                previousVerified = !approved && existing.previousVerified,
                previousVerifiedAt = if (approved) null else existing.previousVerifiedAt,
                previousVerifiedMethod = if (approved) null else existing.previousVerifiedMethod,
            ).recording(record)
        }
        if (approved) return IdentityRow(key, null, approved = true, verified = verified)
        // A changed key: remember the approved one it replaces, and whether that one was verified.
        val approvedKey = if (existing.approved) existing.key else existing.previous
        val approvedVerified = if (existing.approved) existing.verified else existing.previousVerified
        val approvedAt = if (existing.approved) existing.verifiedAt else existing.previousVerifiedAt
        val approvedMethod = if (existing.approved) existing.verifiedMethod else existing.previousVerifiedMethod
        val keeps = approvedKey != null && approvedVerified
        return IdentityRow(
            key = key,
            previous = approvedKey,
            approved = false,
            verified = false,
            previousVerified = keeps,
            previousVerifiedAt = if (keeps) approvedAt else null,
            previousVerifiedMethod = if (keeps) approvedMethod else null,
        )
    }

    private companion object {
        /** Key ids are 1…16777215 (§4.3). */
        val KEY_ID_RANGE = 1..0xFF_FFFF
        const val ONE_TIME = "oneTime"
        const val SIGNED = "signed"
        const val KYBER = "kyber"
    }
}
