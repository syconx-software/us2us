package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** Marks the coroutines that process inbound envelopes (see [InboundStaging.withStagedInboundWrites]). */
internal class InboundStagingScope(val owner: InboundStaging) {
    val id: UUID = UUID.randomUUID()
}

/**
 * The stored identity of a contact, as the `identities` row holds it.
 *
 * [verifiedAt] (ms since 1970) and [verifiedMethod] say when and how [key] was verified, only while
 * it is [verified]. While [key] awaits approval, [previousVerified] and its record say whether the
 * approved key it replaces ([previous]) was verified, so a return to it keeps that verification (§5).
 */
internal data class IdentityRow(
    val key: ByteString,
    val previous: ByteString?,
    val approved: Boolean,
    val verified: Boolean,
    val verifiedAt: Long? = null,
    val verifiedMethod: Long? = null,
    val previousVerified: Boolean = false,
    val previousVerifiedAt: Long? = null,
    val previousVerifiedMethod: Long? = null,
) {
    val verification: IdentityVerification?
        get() {
            if (!approved || !verified) return null
            val at = verifiedAt ?: return null
            val method = IdentityVerificationMethod.of(verifiedMethod) ?: return null
            return IdentityVerification(method, Instant.ofEpochMilli(at))
        }

    fun recording(verification: IdentityVerification?): IdentityRow =
        copy(verifiedAt = verification?.verifiedAt?.toEpochMilli(), verifiedMethod = verification?.method?.raw?.toLong())
}

internal data class LastResortUse(val kyberId: Int, val signedId: Int, val baseKey: ByteString)

/**
 * The staged writes of PROTOCOL.md §5's atomic receive.
 *
 * A decryption advances the ratchet *before* the app has stored the message; if both were committed
 * separately, a crash in between would lose the message for good (libsignal rejects the
 * redelivered copy as a duplicate). Inside [withStagedInboundWrites] the writes a decryption makes
 * (session, identity, used pre-keys) are therefore staged in memory and committed in the same
 * SQLite transaction as the next app [EncryptedDatabase.write] of that coroutine — the one that
 * stores the message.
 *
 * Staged state is never dropped behind anyone's back:
 * * every read sees it (read-your-writes for all callers);
 * * any write the protocol storage makes outside the scope (e.g. a concurrent send advancing the
 *   same session) commits it first;
 * * it is committed when the scope ends, and [discardStagedInboundWrites] drops it only if nothing
 *   outside the scope has seen it (so the relay's redelivery can be decrypted again).
 *
 * The overlay is guarded by the database lock.
 */
internal class InboundStaging(private val database: EncryptedDatabase) : TransactionParticipant {
    class Overlay {
        val sessions = HashMap<AccountId, ByteArray>()
        val identities = HashMap<AccountId, IdentityRow>()
        val removedPreKeys = HashSet<Int>()
        val removedKyberPreKeys = HashSet<Int>()
        val lastResortUses = HashSet<LastResortUse>()
        val contributors = HashSet<UUID>()
        var observedOutsideScope = false

        val isEmpty: Boolean
            get() = sessions.isEmpty() && identities.isEmpty() && removedPreKeys.isEmpty() &&
                removedKyberPreKeys.isEmpty() && lastResortUses.isEmpty()
    }

    /** Exactly what one flush wrote into the open transaction. If that transaction rolls back, these writes become pending again. */
    private class FlushToken(val flushed: Overlay)

    private val scopes = ThreadLocal<InboundStagingScope?>()

    var overlay = Overlay()
        private set

    /** The scope of the calling coroutine, if it belongs to this storage. */
    val currentScope: InboundStagingScope?
        get() = scopes.get()?.takeIf { it.owner === this }

    /**
     * Runs [operation] (typically the core's receive loop) with the decryption writes of this
     * coroutine staged until its next storage write transaction, which then commits them together
     * with the stored message. Whatever is still staged when [operation] returns or throws is
     * committed.
     */
    suspend fun <T> withStagedInboundWrites(operation: suspend () -> T): T {
        if (currentScope != null) return operation()
        val scope = InboundStagingScope(this)
        val result = try {
            withContext(scopes.asContextElement(scope)) { operation() }
        } catch (e: Throwable) {
            try {
                flushStagedWrites()
            } catch (_: StorageException) {
            }
            throw e
        }
        flushStagedWrites()
        return result
    }

    /**
     * Runs [operation] outside the staging scope, also when called inside it: whatever it writes
     * through the protocol storage commits at once (and commits what is staged first). For sends
     * from within the receive loop, so a sent message is never encrypted twice under one chain key
     * after a crash.
     */
    suspend fun <T> withoutStaging(operation: suspend () -> T): T {
        if (currentScope == null) return operation()
        return withContext(scopes.asContextElement(null)) { operation() }
    }

    /**
     * Drops the writes staged by the current scope, if nothing outside the scope has read or built
     * on them; otherwise commits them. Call it when a decrypted message could not be stored, so the
     * relay's redelivery decrypts again. Returns whether the writes were dropped.
     */
    fun discardStagedInboundWrites(): Boolean {
        val discarded = database.locked {
            if (overlay.isEmpty) return@locked true
            val scope = currentScope
            if (scope == null || overlay.contributors != setOf(scope.id) || overlay.observedOutsideScope) return@locked false
            overlay = Overlay()
            true
        }
        if (!discarded) flushStagedWrites()
        return discarded
    }

    /** Commits whatever is staged, right now. */
    fun flushStagedWrites() {
        val pending = database.locked { !overlay.isEmpty }
        if (!pending) return
        database.write(Writer.PROTOCOL_STORE) { }
    }

    /** Whether writes are staged (for tests and diagnostics). */
    val hasStagedWrites: Boolean
        get() = try {
            database.locked { !overlay.isEmpty }
        } catch (_: StorageException) {
            false
        }

    override fun transactionDidBegin(connection: SqlConnection, initiatedByProtocolStore: Boolean): Any? {
        if (overlay.isEmpty || !(initiatedByProtocolStore || currentScope != null)) return null
        for ((account, record) in overlay.sessions) ProtocolRows.writeSession(connection, account, record)
        for ((account, row) in overlay.identities) ProtocolRows.writeIdentity(connection, account, row)
        for (id in overlay.removedPreKeys) connection.run("DELETE FROM pre_keys WHERE id = ?", id)
        for (id in overlay.removedKyberPreKeys) connection.run("DELETE FROM kyber_pre_keys WHERE id = ? AND last_resort = 0", id)
        for (use in overlay.lastResortUses) {
            connection.run(
                "INSERT OR IGNORE INTO kyber_last_resort_uses (kyber_id, signed_id, base_key) VALUES (?, ?, ?)",
                use.kyberId,
                use.signedId,
                use.baseKey,
            )
        }
        // From here on the transaction holds these writes: reads go to the database, which also
        // sees any newer write made in the same transaction (a flushed overlay must never shadow those).
        val flushed = overlay
        overlay = Overlay()
        return FlushToken(flushed)
    }

    override fun transactionDidRollBack(token: Any) {
        // Called under the database lock, right after the rollback. Restore what the transaction
        // would have committed, unless restaged since.
        val flushed = (token as? FlushToken)?.flushed ?: return
        for ((account, record) in flushed.sessions) overlay.sessions.putIfAbsent(account, record)
        for ((account, row) in flushed.identities) overlay.identities.putIfAbsent(account, row)
        overlay.removedPreKeys += flushed.removedPreKeys
        overlay.removedKyberPreKeys += flushed.removedKyberPreKeys
        overlay.lastResortUses += flushed.lastResortUses
        overlay.contributors += flushed.contributors
        overlay.observedOutsideScope = overlay.observedOutsideScope || flushed.observedOutsideScope
    }

    /** Records that a read hit staged state; outside the staging scope this forbids discarding it later. */
    fun noteOverlayHit() {
        val scope = currentScope
        if (scope != null && overlay.contributors.all { it == scope.id }) return
        overlay.observedOutsideScope = true
    }

    fun stage(scope: InboundStagingScope, change: (Overlay) -> Unit) {
        change(overlay)
        overlay.contributors += scope.id
    }

    /** After the current transaction commits, nothing staged for [account] may bring its session or identity back. */
    fun forgetAfterCommit(account: AccountId) {
        database.afterCommit {
            overlay.sessions.remove(account)
            overlay.identities.remove(account)
        }
    }

    /** The identity row of [account], staged or stored. */
    fun identityRow(account: AccountId, connection: SqlConnection): IdentityRow? {
        overlay.identities[account]?.let {
            noteOverlayHit()
            return it
        }
        return ProtocolRows.storedIdentityRow(connection, account)
    }
}

/** The SQL of the protocol tables, shared by the storage and the staged flush. */
internal object ProtocolRows {
    fun storedIdentityRow(connection: SqlConnection, account: AccountId): IdentityRow? {
        val row = connection.queryOne(
            """
            SELECT identity_key, previous_key, approved, verified, verified_at, verified_method,
                previous_verified, previous_verified_at, previous_verified_method
            FROM identities WHERE account = ?
            """.trimIndent(),
            account.toBytes(),
        ) ?: return null
        return IdentityRow(
            key = row.bytes("identity_key") ?: storageFailure(StorageError.Integrity),
            previous = row.bytes("previous_key"),
            approved = row.bool("approved"),
            verified = row.bool("verified"),
            verifiedAt = row.long("verified_at"),
            verifiedMethod = row.long("verified_method"),
            previousVerified = row.bool("previous_verified"),
            previousVerifiedAt = row.long("previous_verified_at"),
            previousVerifiedMethod = row.long("previous_verified_method"),
        )
    }

    fun writeIdentity(connection: SqlConnection, account: AccountId, row: IdentityRow) {
        // The record belongs to a verified key only.
        val recorded = row.approved && row.verified
        // The previous key's verification only while a change awaits approval.
        val previousVerified = !row.approved && row.previous != null && row.previousVerified
        connection.run(
            """
            INSERT OR REPLACE INTO identities
                (account, identity_key, previous_key, approved, verified, verified_at, verified_method,
                 previous_verified, previous_verified_at, previous_verified_method)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            account.toBytes(),
            row.key,
            row.previous,
            row.approved,
            row.verified,
            if (recorded) row.verifiedAt else null,
            if (recorded) row.verifiedMethod else null,
            previousVerified,
            if (previousVerified) row.previousVerifiedAt else null,
            if (previousVerified) row.previousVerifiedMethod else null,
        )
    }

    fun writeSession(connection: SqlConnection, account: AccountId, record: ByteArray) {
        connection.run("INSERT OR REPLACE INTO sessions (account, record) VALUES (?, ?)", account.toBytes(), record)
    }

    /**
     * Deletes the session and identity of [account] inside an open transaction (ghost mode
     * `wipe_contact`, §8.2, or forgetting a contact).
     */
    fun deleteProtocolState(connection: SqlConnection, account: AccountId) {
        connection.run("DELETE FROM sessions WHERE account = ?", account.toBytes())
        connection.run("DELETE FROM identities WHERE account = ?", account.toBytes())
    }
}
