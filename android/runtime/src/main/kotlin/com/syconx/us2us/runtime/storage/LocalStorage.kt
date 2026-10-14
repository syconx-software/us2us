package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.messenger.AccountStore
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.ItemCipher
import com.syconx.us2us.core.vault.ItemDecryptionException
import com.syconx.us2us.runtime.vault.KeySlotException
import com.syconx.us2us.runtime.vault.KeySlotStore
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import com.syconx.us2us.runtime.vault.SlotId
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The encrypted local storage of one installation (PROTOCOL.md §5.3).
 *
 * Lifecycle (§5.4):
 * 1. Unlock the vault → DEK.
 * 2. [open]: crypto-shreds overdue ghost conversations (no DEK needed), opens the SQLCipher
 *    database with `dbKey = HKDF(DEK, "BRP/db/v1")`, migrates it, then finishes wipes and
 *    garbage-collects leftovers (rows and files of shredded conversations, expired disappearing
 *    messages, orphaned files and slots).
 * 3. Use [write] / [read], [protocolStorage], [contactStore] and [accountStore] while unlocked;
 *    call [performDueWipes] on foreground and from in-app timers ([nextDeadline]).
 * 4. [close] when the vault locks: keys are dropped, the database is checkpointed and closed,
 *    plaintext staging files are removed.
 *
 * While locked, only [runBackgroundWipes] and [nextBackgroundWipeDate] work: they read the schedule
 * `{slotId, deadline}` and delete slot files, nothing else.
 *
 * With the core's `Messenger`: build its `ProtocolStore` on [protocolStorage] and pass
 * [contactStore] and [accountStore] (rebuild the messenger after every reopen — a closed storage
 * refuses all calls). Receive inside [InboundStaging.withStagedInboundWrites] (via
 * [SqlProtocolStorage.withStagedInboundWrites]) and store each delivered message in one [write]
 * that also records `tx.envelopes.record(message.envelopeId)`.
 *
 * Thread-safe; every call is synchronous and blocking: run them off the main thread. Never call
 * the core's `ProtocolStore` inside [read] or [write]: it takes its own lock, which a concurrent
 * decryption holds while it waits for this one.
 */
class LocalStorage private constructor(
    val layout: StorageLayout,
    internal val database: EncryptedDatabase,
    internal val keyring: ConversationKeyring,
) {
    internal val files = ItemFileStore(layout)
    private val lock = Any()
    private var cachedAccount: LocalAccount? = null

    @Volatile
    private var accountWasSaved = false

    /**
     * Told what a wake for an overdue wipe ([runBackgroundWipes]) wiped through this open storage,
     * so its owner forgets what went with it. Called on that wake's thread.
     */
    @Volatile
    internal var wipeListener: ((WipeReport) -> Unit)? = null

    internal val staging = InboundStaging(database)

    /** The core's `ProtocolStorage` on the database, with the staged inbound writes of §5. */
    val protocolStorage = SqlProtocolStorage(this)

    /** The core's `ContactStore` on the `contacts` table. */
    val contactStore = SqlContactStore(this)

    /**
     * Persists the core's `LocalAccount` inside the database (pass it to `Messenger` as its
     * `AccountStore`).
     */
    val accountStore = AccountStore { account ->
        database.write(Writer.DETACHED) { connection -> AccountRepository.save(connection, account) }
        synchronized(lock) { cachedAccount = account }
        accountWasSaved = true
    }

    init {
        database.setParticipant(staging)
    }

    /** The result of opening: the storage and what was wiped on the way. */
    class Opened(val storage: LocalStorage, val wipeReport: WipeReport)

    /** Locks: drops keys and caches, closes the database, removes staging files. */
    fun close() {
        files.clearStaging()
        database.close()
        keyring.close()
        synchronized(lock) { cachedAccount = null }
    }

    val isOpen: Boolean get() = database.isOpen

    /**
     * Whether [accountStore] committed an account through this instance: a durable fact, known
     * even when the database can no longer be read. A setup that fails afterwards must keep the
     * vault — the saved token may be the account's only credential (PROTOCOL §5.5).
     */
    val hasSavedAccount: Boolean get() = accountWasSaved

    internal fun ownAccount(connection: SqlConnection): LocalAccount {
        synchronized(lock) { cachedAccount }?.let { return it }
        val account = AccountRepository.load(connection) ?: storageFailure(StorageError.InvalidState("no local account"))
        synchronized(lock) { cachedAccount = account }
        return account
    }

    /** The stored account, or `null` before registration or restore. */
    internal fun knownAccount(connection: SqlConnection): LocalAccount? = try {
        ownAccount(connection)
    } catch (e: StorageException) {
        if (e.error is StorageError.InvalidState) null else throw e
    }

    internal fun rememberAccount(account: LocalAccount?) {
        synchronized(lock) { cachedAccount = account }
    }

    // MARK: Transactions

    /**
     * Runs [body] in one atomic write transaction. If it throws, nothing it did is kept (files it
     * wrote become orphans, garbage-collected later). Inside the inbound staging scope this also
     * commits the staged decryption state of the current coroutine.
     */
    fun <T> write(body: (StorageTransaction) -> T): T =
        database.write { connection -> body(StorageTransaction(connection, this, isWritable = true)) }

    /** Runs [body] on one consistent snapshot. Writing inside throws. */
    fun <T> read(body: (StorageTransaction) -> T): T =
        database.read { connection -> body(StorageTransaction(connection, this, isWritable = false)) }

    // MARK: Files

    /** A path for plaintext about to be encrypted (upload) or just decrypted (download); wiped on open and close. */
    fun makeStagingFile(): File = files.newStagingFile()

    // MARK: Wipes (§5.3, §8.1, §8.2)

    /**
     * Executes every wipe that is due: ghost deadlines (slot first, rows second, files last),
     * conversations whose slot was already shredded (e.g. while locked), and expired disappearing
     * messages. With [collectGarbage] it also removes orphaned slots and item files. Call on unlock
     * ([open] does), on foreground and when [nextDeadline] fires.
     *
     * For ghost timeouts a `ConversationWipe{GHOST_TIMEOUT}` to the contact is put in the outbox
     * (§8.2), conversation-less so nothing of the conversation survives.
     */
    fun performDueWipes(now: Instant = Instant.now(), collectGarbage: Boolean = true): WipeReport =
        WipeService.performDueWipes(this, now, collectGarbage)

    /**
     * Wipes a conversation now: `ConversationWipe` received (pass [WipeReason.GHOST_TIMEOUT] for
     * `GHOST_TIMEOUT`, [WipeReason.REQUESTED] for `USER_REQUEST`) or the user deleted it. A received
     * wipe is never answered with another, so nothing is enqueued here.
     */
    fun wipeConversation(id: ConversationId, reason: WipeReason, now: Instant = Instant.now()): WipedConversation? =
        write { tx ->
            val conversation = tx.conversations.get(id) ?: return@write null
            WipeService.wipe(conversation, reason, tx, now)
        }

    /** The earliest ghost deadline or disappearing deadline, for an in-app timer while unlocked. */
    fun nextDeadline(): Instant? = read { tx ->
        val ghost = tx.connection.queryOne(
            "SELECT min(ghost_deadline_at) AS t FROM conversations WHERE ghost_deadline_at IS NOT NULL",
        )?.instant("t")
        listOfNotNull(ghost, tx.messages.nextExpiry()).minOrNull()
    }

    /** Rewrites the background schedule from the database (repairs drift after a crash between the two writes). */
    internal fun reconcileGhostSchedule() {
        val wanted = read { tx ->
            tx.connection.query("SELECT slot_id, ghost_deadline_at FROM conversations").mapNotNull { row ->
                val slotId = row.blob("slot_id")?.let(::slotIdOf) ?: return@mapNotNull null
                val deadline = row.instant("ghost_deadline_at") ?: return@mapNotNull null
                slotId to deadline
            }.toMap()
        }
        val scheduled = keyring.deadlines()
        for ((slotId, deadline) in wanted) {
            if (scheduled[slotId]?.toEpochMilli() != deadline.toEpochMilli()) keyring.setDeadline(deadline, slotId)
        }
        for (slotId in scheduled.keys) if (slotId !in wanted) keyring.setDeadline(null, slotId)
    }

    companion object {
        /** Opens (creating on first use) the storage with the vault's DEK. */
        fun open(layout: StorageLayout, dek: SecretKeyMaterial, now: Instant = Instant.now()): Opened =
            dek.withDataKey { open(layout, it, now, SqlCipherDriver) }

        internal fun open(layout: StorageLayout, dek: DataKey, now: Instant, driver: SqlDriver): Opened =
            OpenStorages.serialized { openExclusively(layout, dek, now, driver) }

        private fun openExclusively(layout: StorageLayout, dek: DataKey, now: Instant, driver: SqlDriver): Opened {
            var alreadyOpen = false
            OpenStorages.forEach(layout) { if (it.isOpen) alreadyOpen = true }
            // One instance per directory: two would stage and cache keys independently.
            if (alreadyOpen) storageFailure(StorageError.InvalidState("storage already open; close it first"))
            layout.prepare()
            val keyring = ConversationKeyring(layout, dek)
            // §5.4 step 1: overdue wipes first, before any content can be read.
            try {
                keyring.destroyExpiredSlots(now)
            } catch (_: StorageException) {
            }
            val databaseKey = dek.databaseKey()
            val database = try {
                EncryptedDatabase.open(layout.databaseFile, databaseKey, Schema.migrations, driver)
            } catch (e: Throwable) {
                keyring.close()
                throw e
            } finally {
                databaseKey.fill(0)
            }
            // A WAL inherited from an unclean exit (crash, the process killed in the grace period)
            // can still hold page images of deleted content: fold it in now.
            try {
                database.checkpoint()
            } catch (_: StorageException) {
            }
            val storage = LocalStorage(layout, database, keyring)
            OpenStorages.register(storage)
            storage.files.clearStaging()
            try {
                return Opened(storage, storage.performDueWipes(now))
            } catch (e: Throwable) {
                storage.close()
                throw e
            }
        }

        /**
         * Removes every plaintext staging file without a key. For process start: a crash or a
         * kill skips [close], and decrypted media must not wait for the next unlock. Never call it
         * while this process has the storage open (transfers in flight use staging).
         */
        fun purgeStaging(layout: StorageLayout) {
            ItemFileStore(layout).clearStaging()
        }

        /**
         * Wipes everything on this phone: database, slots, files (account deletion, erase
         * everything, §5.2). Destroy the vault's keys too.
         */
        fun destroyAll(layout: StorageLayout) {
            OpenStorages.forEach(layout) { it.close() }
            // Slots first: from here on nothing is decryptable, even if the rest fails.
            for (name in layout.slotDirectory.list().orEmpty()) removeIfPresent(File(layout.slotDirectory, name))
            for (file in layout.databaseFiles) removeIfPresent(file)
            removeIfPresent(layout.root)
        }

        /**
         * Crypto-shreds conversations whose ghost deadline passed, without the DEK: reads only the
         * schedule `{slotId, deadline}` and deletes slot files. Rows and files are collected at the
         * next unlock. Returns the number of conversations destroyed.
         *
         * If the storage is still open in this process (the vault has not locked yet), the full
         * unlocked pass runs instead, through the open instance, so there is one writer of the
         * schedule and no cached key survives; its [wipeListener] hears what went.
         */
        fun runBackgroundWipes(layout: StorageLayout, now: Instant = Instant.now()): Int = OpenStorages.serialized {
            var open: LocalStorage? = null
            OpenStorages.forEach(layout) { if (it.isOpen) open = it }
            open?.let { storage ->
                return@serialized try {
                    val report = storage.performDueWipes(now, collectGarbage = false)
                    storage.wipeListener?.invoke(report)
                    report.conversations.size
                } catch (_: StorageException) {
                    0
                }
            }
            // Nothing of this directory is open in this process, so no transfer can be using
            // staging: plaintext left by a process that died goes now, without a key.
            purgeStaging(layout)
            try {
                KeySlotStore(layout.slotDirectory).destroyExpiredSlots(now).size
            } catch (_: KeySlotException) {
                0
            }
        }

        /** When the next background wipe is due (for an exact alarm). */
        fun nextBackgroundWipeDate(layout: StorageLayout): Instant? = try {
            KeySlotStore(layout.slotDirectory).deadlines().values.minOrNull()
        } catch (_: KeySlotException) {
            null
        }
    }
}

/** One open transaction. Only valid inside the closure it was passed to. */
class StorageTransaction internal constructor(
    internal val connection: SqlConnection,
    internal val storage: LocalStorage,
    internal val isWritable: Boolean,
) {
    val account: AccountRepository get() = AccountRepository(this)
    val contacts: ContactRepository get() = ContactRepository(this)
    val conversations: ConversationRepository get() = ConversationRepository(this)
    val messages: MessageRepository get() = MessageRepository(this)
    val attachments: AttachmentRepository get() = AttachmentRepository(this)
    val outbox: OutboxRepository get() = OutboxRepository(this)
    val envelopes: EnvelopeLog get() = EnvelopeLog(this)

    internal fun requireWritable() {
        if (!isWritable) storageFailure(StorageError.InvalidState("write inside a read"))
    }

    /** Runs after the transaction commits (never after a rollback). */
    internal fun afterCommit(hook: () -> Unit) = storage.database.afterCommit(hook)

    /** Folds and truncates the WAL after the commit, so destroyed or rewritten content leaves no older page image behind. */
    internal fun checkpointAfterCommit() = storage.database.checkpointAfterCommit()

    internal fun ownAccountId(): AccountId = storage.ownAccount(connection).accountId

    /** `CK` of a conversation. */
    internal fun conversationKey(slotId: SlotId): ByteArray = storage.keyring.key(slotId)

    internal fun conversationKey(conversationId: ConversationId): ByteArray {
        val slotId = connection.queryOne("SELECT slot_id FROM conversations WHERE id = ?", conversationId.raw)
            ?.blob("slot_id") ?: storageFailure(StorageError.NotFound)
        return conversationKey(slotIdOf(slotId))
    }

    internal fun seal(plaintext: ByteArray, itemId: ByteString, key: ByteArray): ByteArray = try {
        ItemCipher.seal(key, itemId.toByteArray(), plaintext)
    } catch (e: IllegalArgumentException) {
        storageFailure(StorageError.Integrity, e)
    }

    internal fun open(item: ByteArray, itemId: ByteString, key: ByteArray): ByteArray = try {
        ItemCipher.open(key, itemId.toByteArray(), item)
    } catch (e: ItemDecryptionException) {
        storageFailure(StorageError.Integrity, e)
    } catch (e: IllegalArgumentException) {
        storageFailure(StorageError.Integrity, e)
    }
}

internal fun slotIdOf(bytes: ByteArray): SlotId = try {
    SlotId.of(bytes)
} catch (e: IllegalArgumentException) {
    storageFailure(StorageError.Integrity, e)
}

internal fun accountIdOf(bytes: ByteArray?): AccountId? = bytes?.takeIf { it.size == AccountId.BYTES }?.let(AccountId::fromBytes)

/** Open storages of this process, so a locked-path wipe can evict cached keys. */
internal object OpenStorages {
    private val lock = Any()

    /** Serializes opening, so two concurrent opens of one directory cannot both succeed. */
    private val openLock = ReentrantLock()
    private val storages = ArrayList<WeakReference<LocalStorage>>()

    fun <T> serialized(body: () -> T): T = openLock.withLock(body)

    fun register(storage: LocalStorage) {
        synchronized(lock) {
            storages.removeAll { it.get() == null }
            storages += WeakReference(storage)
        }
    }

    fun forEach(layout: StorageLayout, body: (LocalStorage) -> Unit) {
        val open = synchronized(lock) { storages.mapNotNull { it.get() } }
        for (storage in open) if (storage.layout.sameRoot(layout)) body(storage)
    }
}
