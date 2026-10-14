package com.syconx.us2us.runtime.facade

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.messenger.BackupNoLongerValidException
import com.syconx.us2us.core.messenger.InvalidBackupException
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.messenger.PendingSetup
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.BackupContact
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.runtime.engine.MessagingBackend
import com.syconx.us2us.runtime.engine.MessengerBackend
import com.syconx.us2us.runtime.engine.ReceiveGuardedContactStore
import com.syconx.us2us.runtime.net.NetworkPolicy
import com.syconx.us2us.runtime.storage.AccountBackupService
import com.syconx.us2us.runtime.storage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException

/**
 * A backup file decrypted and verified as a whole (§5.5): every field well-formed, before anything
 * on this phone or on the relay changes. The core restores from [file] and [password] again.
 */
class AccountBackupFile private constructor(
    private val bytes: ByteArray,
    internal val password: String,
    val backup: AccountBackup,
) {
    internal val file: ByteArray get() = bytes.copyOf()

    val accountId: AccountId get() = AccountId.fromBytes(backup.accountId.toByteArray())

    internal val authToken: AuthToken get() = AuthToken(backup.authToken.toByteArray())

    override fun toString(): String = "AccountBackupFile(redacted)"

    companion object {
        private const val VERSION = 1
        private const val IDENTITY_KEY_LENGTH = 33
        private const val MAX_DISPLAY_NAME_BYTES = 64

        /**
         * Decrypts (about a second: PBKDF2) and verifies a backup file. Call it off the main thread.
         *
         * @throws com.syconx.us2us.core.vault.WrongBackupPasswordException for a wrong password.
         * @throws InvalidBackupException for a damaged, malformed or unknown backup.
         */
        fun import(file: ByteArray, password: String): AccountBackupFile {
            val plaintext = BackupFile.open(file, password)
            val backup = try {
                AccountBackup.parseFrom(plaintext)
            } catch (e: InvalidProtocolBufferException) {
                throw InvalidBackupException("malformed AccountBackup", e)
            } finally {
                plaintext.fill(0)
            }
            validate(backup)
            return AccountBackupFile(file.copyOf(), password, backup)
        }

        /** The checks the core makes before it restores (§5.5), so a bad backup changes nothing here either. */
        private fun validate(backup: AccountBackup) {
            if (backup.version != VERSION) throw InvalidBackupException("unsupported backup version")
            val account = parsed("account id") { AccountId.fromBytes(backup.accountId.toByteArray()) }
            parsed("auth token") { AuthToken(backup.authToken.toByteArray()) }
            parsed("identity key pair") { IdentityKeyPair(backup.identityKeyPair.toByteArray()) }
            if (backup.registrationId !in 1..Keys.REGISTRATION_ID_MAX) throw InvalidBackupException("registration id out of range")
            if (Username.parse(backup.username)?.value != backup.username) throw InvalidBackupException("username is not normalized")
            if (backup.displayName.toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_NAME_BYTES) throw InvalidBackupException("display name too long")
            parsed("access key") { AccessKey(backup.accessKey.toByteArray()) }
            val contacts = backup.contactsList.map(::validateContact)
            if (account in contacts) throw InvalidBackupException("the account is its own contact")
            if (contacts.toSet().size != contacts.size) throw InvalidBackupException("duplicate contact")
        }

        private fun validateContact(saved: BackupContact): AccountId {
            val peer = parsed("contact account id") { AccountId.fromBytes(saved.accountId.toByteArray()) }
            if (!saved.identityKey.isEmpty) {
                if (saved.identityKey.size() != IDENTITY_KEY_LENGTH) throw InvalidBackupException("malformed contact identity key")
                parsed("contact identity key") { IdentityKey(saved.identityKey.toByteArray()) }
            } else if (saved.identityUnaccepted) {
                throw InvalidBackupException("unaccepted identity without a key")
            }
            if (saved.username.isNotEmpty() && Username.parse(saved.username)?.value != saved.username) {
                throw InvalidBackupException("contact username is not normalized")
            }
            if (saved.displayName.toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_NAME_BYTES) throw InvalidBackupException("contact display name too long")
            if (!saved.theirAccessKey.isEmpty) parsed("contact access key") { AccessKey(saved.theirAccessKey.toByteArray()) }
            return peer
        }

        private inline fun <T> parsed(what: String, parse: () -> T): T = try {
            parse()
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("malformed $what", e)
        } catch (e: InvalidKeyException) {
            throw InvalidBackupException("malformed $what", e)
        }
    }
}

/** Creates the relay/crypto backend of an account on its open storage. */
interface BackendFactory {
    /**
     * Registers a new account (proof-of-work, pre-keys, access key, §4.3). The account is saved to
     * [storage] before anything else can fail.
     */
    suspend fun register(username: Username, storage: LocalStorage): MessagingBackend

    /** The backend of a registered installation. */
    fun open(account: LocalAccount, storage: LocalStorage): MessagingBackend

    /**
     * Restores an account from a verified backup into a fresh [storage] (§5.5): identity and
     * contacts, token rotation (the new token stored before it is sent, the outcome saved before
     * the key upload), fresh pre-keys. Throws [RuntimeError.BackupRevoked] when the relay refuses
     * the backup's token and the new one, and the core's `TokenRefusedException` when it refused
     * the new token alone (the backup still works).
     */
    suspend fun restore(backup: AccountBackupFile, storage: LocalStorage): MessagingBackend

    /**
     * The read-only relay check of a backup before anything on this phone is replaced: one
     * authenticated request with the backup's token, nothing stored. Throws
     * [RuntimeError.BackupRevoked] when the relay refuses the token, and the relay's error when it
     * could not be asked.
     */
    suspend fun checkBackup(backup: AccountBackupFile)
}

/** The production backend factory: one `RelayClient` per account, built by [NetworkPolicy]. */
class RelayBackendFactory(private val relayUrl: String) : BackendFactory {
    /** Refuses an insecure URL at once rather than at the first request. */
    private fun client(): RelayClient = NetworkPolicy.makeRelayClient(relayUrl)

    private fun configuration(storage: LocalStorage) = MessengerConfig(
        // Attachment blobs are staged in the storage's own staging directory, emptied on every lock.
        tempDirectory = storage.layout.stagingDirectory.also { it.mkdirs() },
        // The engine sends delivery receipts through its durable outbox, merged per contact.
        sendDeliveryReceipts = false,
    )

    override suspend fun register(username: Username, storage: LocalStorage): MessagingBackend {
        val relay = client()
        val contacts = ReceiveGuardedContactStore(storage.contactStore)
        val messenger = Messenger.register(username, relay, storage.protocolStorage, contacts, configuration(storage), storage.accountStore)
        return MessengerBackend(messenger, storage, relay, contacts)
    }

    override fun open(account: LocalAccount, storage: LocalStorage): MessagingBackend {
        val relay = client()
        val contacts = ReceiveGuardedContactStore(storage.contactStore)
        if (PendingSetup.REPLACE_PRE_KEYS in account.pendingSetup || PendingSetup.ANNOUNCE_PROFILE in account.pendingSetup) {
            // A restore that stopped early: its contacts get their conversations (idempotent).
            storage.write { AccountBackupService.finishRestore(it) }
        }
        val store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
        val messenger = Messenger(account, store, contacts, relay, configuration(storage), storage.accountStore)
        return MessengerBackend(messenger, storage, relay, contacts)
    }

    override suspend fun restore(backup: AccountBackupFile, storage: LocalStorage): MessagingBackend {
        val relay = client()
        val contacts = ReceiveGuardedContactStore(storage.contactStore)
        val messenger = try {
            Messenger.restore(backup.file, backup.password, relay, storage.protocolStorage, contacts, configuration(storage), storage.accountStore)
        } catch (e: BackupNoLongerValidException) {
            // The relay refused both tokens: another phone restored the backup first, or the account is gone.
            runtimeFailure(RuntimeError.BackupRevoked, e)
        }
        storage.write { AccountBackupService.finishRestore(it) }
        return MessengerBackend(messenger, storage, relay, contacts)
    }

    override suspend fun checkBackup(backup: AccountBackupFile) {
        // Its own client: nothing of an open session is used or changed.
        val relay = client().withAuthToken(backup.authToken)
        try {
            withContext(Dispatchers.IO) { relay.keyStatus() }
        } catch (e: RelayException) {
            if (AppRuntime.meansAccountGone(e)) runtimeFailure(RuntimeError.BackupRevoked, e)
            throw e
        }
    }
}
