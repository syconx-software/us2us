package com.syconx.us2us.runtime.storage

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.keys.PreKeyManager
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.keys.toPreKeyBundle
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.relay.PreKeyBundleEntity
import com.syconx.us2us.core.session.SessionCrypto
import com.syconx.us2us.core.store.InMemoryProtocolStore
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.DataKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The platform's SQLite (Robolectric's native build) as the storage's SQL binding in JVM tests.
 * There is no SQLCipher on the host, so the key is only checked, not used: a file opened with a key
 * other than the one that created it is refused like SQLCipher refuses it. The encryption itself is
 * covered by the device tests.
 */
internal object PlainSqlDriver : SqlDriver {
    private val keys = ConcurrentHashMap<String, String>()

    override fun open(file: File, rawKey: ByteArray): SqlEngine {
        val path = file.absoluteFile.canonicalPath
        val key = Hex.encode(rawKey)
        if (!file.exists()) keys[path] = key
        val known = keys.putIfAbsent(path, key)
        if (known != null && known != key) storageFailure(StorageError.WrongKeyOrNotADatabase)
        val database = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return PlainEngine(database)
    }

    private class PlainEngine(private val database: SQLiteDatabase) : SqlEngine {
        override fun execute(sql: String) = database.execSQL(sql)

        override fun query(sql: String, arguments: Array<Any?>): Cursor = database.rawQueryWithFactory(
            { _, driver, table, query ->
                query.bind(arguments)
                SQLiteCursor(driver, table, query)
            },
            sql,
            null,
            "",
        )

        override fun update(sql: String, arguments: Array<Any?>): Int =
            database.compileStatement(sql).use { it.bind(arguments); it.executeUpdateDelete() }

        override fun insert(sql: String, arguments: Array<Any?>): Long =
            database.compileStatement(sql).use { it.bind(arguments); it.executeInsert() }

        override fun close() = database.close()

        private fun SQLiteProgram.bind(arguments: Array<Any?>) {
            arguments.forEachIndexed { offset, value ->
                val index = offset + 1
                when (value) {
                    null -> bindNull(index)
                    is Long -> bindLong(index, value)
                    is Double -> bindDouble(index, value)
                    is String -> bindString(index, value)
                    is ByteArray -> bindBlob(index, value)
                    else -> error("cannot bind $value")
                }
            }
        }
    }
}

/** A wall clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-10-08T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** One installation's storage directory and DEK, opened on the plain SQLite binding. */
class TestEnvironment(root: File) {
    val layout = StorageLayout(File(root, "storage"))
    val dek = DataKey(ByteArray(32).also(SecureRandom()::nextBytes))

    fun open(now: Instant = Instant.now(), key: DataKey = dek): LocalStorage = openWithReport(now, key).storage

    fun openWithReport(now: Instant = Instant.now(), key: DataKey = dek): LocalStorage.Opened =
        LocalStorage.open(layout, key, now, PlainSqlDriver)
}

fun randomId(): ByteString = RandomIds.make()

fun newAccountId(): AccountId = AccountId(UUID.randomUUID())

fun newAccount(
    accountId: AccountId = newAccountId(),
    username: String = "alice",
    identity: IdentityKeyPair = Keys.generateIdentityKeyPair(),
): LocalAccount = LocalAccount(
    accountId = accountId,
    authToken = AuthToken(ByteArray(AuthToken.LENGTH).also(SecureRandom()::nextBytes)),
    username = Username(username),
    identityKeyPair = identity,
    registrationId = Keys.generateRegistrationId(),
    accessKey = AccessKey.generate(),
)

/** Stores [account] as the installation's own account. */
fun LocalStorage.saveAccount(account: LocalAccount = newAccount()): LocalAccount {
    accountStore.save(account)
    return account
}

/** A contact in [state] with its conversation. */
fun LocalStorage.conversationWith(
    contact: AccountId = newAccountId(),
    state: com.syconx.us2us.core.contacts.ContactState = com.syconx.us2us.core.contacts.ContactState.ACCEPTED,
    now: Instant = Instant.now(),
): Conversation = write { tx ->
    if (tx.contacts.requestState(contact) == null) tx.contacts.create(contact, state, now)
    tx.conversations.ensure(contact, now)
}

fun textMessage(body: String, id: ByteString = randomId(), sentAt: Long = 1_760_000_000_000L, timer: Int = 0): DataMessage =
    dataMessage {
        messageId = id
        this.sentAt = sentAt
        expireTimerSeconds = timer
        text = text { this.body = body }
    }

fun attachmentMessage(
    kind: Attachment.Kind = Attachment.Kind.IMAGE,
    id: ByteString = randomId(),
    caption: String = "",
    viewOnce: Boolean = false,
    timer: Int = 0,
    mediaId: ByteString = ByteString.EMPTY,
): DataMessage = dataMessage {
    messageId = id
    sentAt = 1_760_000_000_000L
    this.viewOnce = viewOnce
    expireTimerSeconds = timer
    attachment = attachment {
        this.kind = kind
        contentType = "image/jpeg"
        size = 1234
        this.caption = caption
        this.mediaId = mediaId
        thumbnail = ByteString.copyFromUtf8("thumbnail")
    }
}

fun textContent(body: String): Content = content { data = textMessage(body) }

/**
 * One libsignal party: its protocol store (in memory or on a storage), its sessions and a pretend
 * relay mailbox of its uploaded keys, so tests can build bundles as the relay hands them out.
 */
class Party(
    val store: ProtocolStore,
    val accountId: AccountId = newAccountId(),
    clock: Clock = Clock.systemUTC(),
) {
    val sessions = SessionCrypto(store, accountId, clock)
    val keys = PreKeyManager(store, PreKeyPolicy(batchSize = 5, refillThreshold = 2), clock)
    // Generated on first use: a party that only receives on existing sessions uploads nothing.
    private val uploaded by lazy { keys.generateFullSet() }
    private val oneTimeEc by lazy { ArrayDeque(uploaded.oneTimePreKeys) }
    private val oneTimeKyber by lazy { ArrayDeque(uploaded.kyberOneTimePreKeys) }

    val lastResortKyberId: Int get() = uploaded.kyberLastResortKey.keyId

    fun bundle(withOneTimeEc: Boolean = true, withOneTimeKyber: Boolean = true): org.signal.libsignal.protocol.state.PreKeyBundle {
        val ec = if (withOneTimeEc) oneTimeEc.removeFirstOrNull() else null
        val kyber = (if (withOneTimeKyber) oneTimeKyber.removeFirstOrNull() else null) ?: uploaded.kyberLastResortKey
        return PreKeyBundleEntity(
            accountId = accountId,
            deviceId = AccountId.DEVICE_ID,
            registrationId = store.localRegistrationId,
            identityKey = store.identityKeyPair.publicKey.serialize(),
            signedPreKey = uploaded.signedPreKey,
            kyberPreKey = kyber,
            oneTimePreKey = ec,
        ).toPreKeyBundle(accountId)
    }

    fun encryptTo(peer: Party, body: String): CiphertextMessage = sessions.encrypt(peer.accountId, textContent(body))

    fun decryptFrom(peer: Party, message: CiphertextMessage): String =
        sessions.decrypt(peer.accountId, message.type, message.serialize()).data.text.body

    companion object {
        fun inMemory(): Party = Party(InMemoryProtocolStore(Keys.generateIdentityKeyPair(), Keys.generateRegistrationId()))

        /** A party whose protocol state lives in [storage], as the app's own account. */
        fun on(storage: LocalStorage, account: LocalAccount): Party =
            Party(ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage), account.accountId)
    }
}

val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

fun Instant.plusSeconds(seconds: Int): Instant = plusSeconds(seconds.toLong())

/** All bytes of the database and its WAL (what an attacker copying the directory would get). */
fun TestEnvironment.databaseBytes(): ByteArray =
    layout.databaseFiles.filter { it.exists() }.fold(ByteArray(0)) { result, file -> result + file.readBytes() }

fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return needle.isEmpty()
    outer@ for (start in 0..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return true
    }
    return false
}

fun ByteArray.contains(text: String): Boolean = contains(text.toByteArray(Charsets.UTF_8))

fun sampleAttachment(size: Long = 1234, caption: String = ""): Attachment = attachment {
    kind = Attachment.Kind.IMAGE
    mediaId = randomId()
    deleteToken = ByteString.copyFrom(ByteArray(32) { 3 })
    key = ByteString.copyFrom(ByteArray(64) { 4 })
    digest = ByteString.copyFrom(ByteArray(32) { 5 })
    this.size = size
    contentType = "image/jpeg"
    this.caption = caption
    width = 640
    height = 480
}

/** An accepted contact with its conversation. */
fun LocalStorage.contactWithConversation(contact: AccountId = newAccountId(), now: Instant = Instant.now()): Pair<AccountId, Conversation> =
    write { tx ->
        tx.contacts.create(contact, com.syconx.us2us.core.contacts.ContactState.ACCEPTED, now)
        contact to tx.conversations.ensure(contact, now)
    }

fun dataMessageOf(id: ByteString = randomId(), sentAt: Instant = t0, build: com.syconx.us2us.core.proto.DataMessageKt.Dsl.() -> Unit): DataMessage =
    dataMessage {
        messageId = id
        this.sentAt = sentAt.toEpochMilli()
        build()
    }
