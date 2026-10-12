package com.syconx.us2us.cli

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.ContactStore
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.messenger.AccountStore
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The bot's account across restarts (`--data <dir>`), kept as the core's own account backup
 * (PROTOCOL.md §5.5) under a random backup password: [BACKUP_FILE] and [PASSWORD_FILE], owner-only
 * (0700 directory, 0600 files), each replaced atomically. The first start registers and writes the
 * backup at once ([register]); later starts [restore] it.
 *
 * A restore rotates the auth token and revokes the backup's, so the account goes through this
 * [AccountStore]: a change of what the backup holds of it is written before [save] returns, the
 * restore's own one included. While the rotation's outcome is unknown (PROTOCOL.md §5.5 step 3.4)
 * the file keeps the backup's token; the save that settles it writes the token the relay holds. Contact changes (an accepted request, a contact's new key or profile)
 * follow [backupDelay] later from [runBackups], coalesced: every write derives the backup key anew.
 * A shutdown ([flush]) while the relay is creating the account or rotating its token waits until
 * the account is saved.
 *
 * One process at a time: a second bot on the directory would rotate the token under the first,
 * whose next write would bring the revoked token back.
 */
internal class BotData private constructor(
    val dir: File,
    private val password: String,
    private val lock: FileLock,
    private val iterations: Int,
    private val backupDelay: Duration,
    private val log: (String) -> Unit,
) : AccountStore, Closeable {
    private val backupFile = File(dir, BACKUP_FILE)
    private val writeLock = ReentrantLock()

    /** Signalled when a start has saved its account, or ended without one. */
    private val startSaved = writeLock.newCondition()
    private val contactsChanged = AtomicBoolean(false)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Hand these to the messenger: changes the backup holds are written by [runBackups]. */
    private val contacts: ContactStore = WatchedContacts(InMemoryContactStore()) {
        contactsChanged.set(true)
        wakeUp.trySend(Unit)
    }

    // Guarded by writeLock.
    private var messenger: Messenger? = null
    private var starting = false
    private var restoring: AccountBackup? = null
    private var latest: LocalAccount? = null
    private var written: LocalAccount? = null

    /** Backups written by this process, for tests. */
    @Volatile
    var writes = 0
        private set

    /** The backup of an earlier start, or `null` before the first one. Decrypting it takes a moment. */
    fun load(): SavedAccount? {
        if (!backupFile.exists()) return null
        val file = backupFile.readBytes()
        return SavedAccount(file, AccountBackup.parseFrom(BackupFile.open(file, password)))
    }

    /** A new account, saved here before this returns (see [Messenger.register] for the exceptions). */
    suspend fun register(username: Username, relay: RelayClient, config: MessengerConfig = MessengerConfig()): Messenger = start(null) {
        Messenger.register(username, relay, InMemoryProtocolStorage(), contacts, config, accountStore = this)
    }

    /** The account in [saved], which [load] returned (see [Messenger.restore] for the exceptions). */
    suspend fun restore(saved: SavedAccount, relay: RelayClient, config: MessengerConfig = MessengerConfig()): Messenger = start(saved.backup) {
        Messenger.restore(saved.file, password, relay, InMemoryProtocolStorage(), contacts, config, accountStore = this)
    }

    override fun save(account: LocalAccount) {
        writeLock.withLock {
            latest = account
            if (account.backedUp() != written?.backedUp()) writeOrRetry()
        }
    }

    /** Writes contact changes and retries failed writes until cancelled. */
    suspend fun runBackups() {
        while (true) {
            wakeUp.receive()
            delay(backupDelay)
            withContext(Dispatchers.IO) { flush() }
        }
    }

    /** Writes whatever changed since the last backup, e.g. on shutdown. */
    fun flush() {
        writeLock.withLock {
            // Stopped mid-start: the relay may already hold the new account or token, so it goes to disk first.
            while (starting && written == null) startSaved.await()
            val messenger = messenger ?: return
            if (contactsChanged.get() || messenger.account.backedUp() != written?.backedUp()) writeOrRetry()
        }
    }

    override fun close() = lock.channel().close()

    /** Registers or restores ([create]) with [restoring] as the backup being restored, and saves the account. */
    private suspend fun start(restoring: AccountBackup?, create: suspend () -> Messenger): Messenger {
        writeLock.withLock {
            this.restoring = restoring
            starting = true
        }
        try {
            return create().also(::attach)
        } finally {
            writeLock.withLock {
                starting = false
                this.restoring = null
                startSaved.signalAll()
            }
        }
    }

    private fun attach(messenger: Messenger) {
        writeLock.withLock {
            this.messenger = messenger
            // Restored contacts are the backup's own; only a registration has nothing on disk yet.
            contactsChanged.set(false)
            if (messenger.account.backedUp() != written?.backedUp()) writeOrRetry()
        }
    }

    /** [write], or if that fails, a log line and a retry from [runBackups]. Holds [writeLock]. */
    private fun writeOrRetry() {
        try {
            write()
        } catch (e: Exception) {
            // Whatever it was, the messenger keeps the account in memory and carries on.
            log("could not save the backup, retrying: ${e.message}")
            wakeUp.trySend(Unit)
        }
    }

    /** Holds [writeLock]. */
    private fun write() {
        val messenger = messenger
        val restoring = restoring
        // Read before exporting: a change meanwhile is then written again, never lost.
        val account = messenger?.account ?: latest ?: return
        // Inside register: the first backup is written once it returns ([attach]).
        if (messenger == null && restoring == null) return
        // A token rotation whose outcome is unknown: nothing new for the file, which keeps the
        // backup's token until the save that settles it.
        if (account.pendingAuthToken != null) return
        val pendingContacts = contactsChanged.getAndSet(false)
        try {
            val file = if (messenger != null) {
                messenger.exportBackupBlocking(password, iterations)
            } else {
                // Inside restore, right after the token rotation: nothing else differs from the backup (§5.5 step 3).
                val rotated = checkNotNull(restoring).toBuilder().setAuthToken(account.authToken.toBytes().toByteString()).build()
                BackupFile.create(password, rotated.toByteArray(), iterations)
            }
            replaceOwnerOnly(backupFile, file)
            written = account
            startSaved.signalAll()
            writes++
            log("backup saved")
        } catch (e: Exception) {
            if (pendingContacts) contactsChanged.set(true)
            throw e
        }
    }

    /** The backup file of an earlier start and what it holds. */
    class SavedAccount(val file: ByteArray, val backup: AccountBackup) {
        val username: String get() = backup.username
    }

    companion object {
        const val BACKUP_FILE = "account.brpb"
        const val PASSWORD_FILE = "backup-password"
        const val LOCK_FILE = "lock"

        private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")
        private val LOCK_POLL = 200.milliseconds
        private val random = SecureRandom()

        /**
         * Opens [dir], creating it and the backup password on the first start, and takes its lock,
         * waiting up to [lockWait] for a bot that is still shutting down.
         *
         * @throws IOException if the directory cannot be used, or another bot holds it.
         */
        fun open(
            dir: File,
            log: (String) -> Unit = {},
            iterations: Int = BackupFile.ITERATIONS,
            backupDelay: Duration = 2.seconds,
            lockWait: Duration = 15.seconds,
        ): BotData {
            val path = dir.absoluteFile.toPath()
            Files.createDirectories(path)
            Files.setPosixFilePermissions(path, OWNER_ONLY_DIRECTORY)
            val lock = lock(File(dir, LOCK_FILE), lockWait) ?: throw IOException("another us2us-bot is using $dir")
            try {
                // Left by a write that was cut off; the file it was to replace is intact.
                dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
                return BotData(dir.absoluteFile, password(dir), lock, iterations, backupDelay, log)
            } catch (e: Exception) {
                lock.channel().close()
                throw e
            }
        }

        private fun lock(file: File, wait: Duration): FileLock? {
            val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val channel = FileChannel.open(file.toPath(), options, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
            try {
                Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY_FILE)
                val deadline = System.nanoTime() + wait.inWholeNanoseconds
                while (true) {
                    tryLock(channel)?.let { return it }
                    if (System.nanoTime() >= deadline) break
                    Thread.sleep(LOCK_POLL.inWholeMilliseconds)
                }
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            channel.close()
            return null
        }

        private fun tryLock(channel: FileChannel): FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null // Held within this process.
        }

        /** The backup password, made on the first start. Never logged. */
        private fun password(dir: File): String {
            val file = File(dir, PASSWORD_FILE)
            // Copied in from elsewhere, they may be readable by others: not any more.
            listOf(file, File(dir, BACKUP_FILE)).filter { it.exists() }.forEach { Files.setPosixFilePermissions(it.toPath(), OWNER_ONLY_FILE) }
            if (file.exists()) return file.readText().trim()
            // A new password could never open the existing backup, and registering would replace it.
            if (File(dir, BACKUP_FILE).exists()) throw IOException("$file is missing, so the backup in $dir cannot be opened")
            val password = generateSequence { newPassword() }.first { BackupPasswordPolicy.check(it) == null }
            replaceOwnerOnly(file, password.toByteArray(Charsets.UTF_8))
            return password
        }

        /** 256 random bits, base64url: far beyond every rule of §5.5. */
        private fun newPassword(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        /** Atomically, like [writeState], and on disk before the old file is gone; 0600 from the start. */
        private fun replaceOwnerOnly(target: File, bytes: ByteArray) {
            val dir = target.parentFile.toPath()
            val temp = Files.createTempFile(dir, target.name, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
            try {
                Files.setPosixFilePermissions(temp, OWNER_ONLY_FILE)
                FileOutputStream(temp.toFile()).use {
                    it.write(bytes)
                    it.fd.sync()
                }
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.deleteIfExists(temp)
                throw e
            }
            // The rename itself, durable too. Best effort: not every platform syncs a directory.
            try {
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
            }
        }

        /** What the backup holds of the account: pending setup steps, the avatar version and presence sharing are not in it. */
        private fun LocalAccount.backedUp() = copy(presenceSharing = true, avatarVersion = 0, pendingSetup = emptySet())
    }
}

/** Reports changes to what the backup holds of a contact (§5.5); every other write passes quietly. */
private class WatchedContacts(private val contacts: ContactStore, private val onChange: () -> Unit) : ContactStore by contacts {
    override fun put(contact: Contact) {
        val before = contacts.get(contact.accountId)
        contacts.put(contact)
        if (before?.backedUp() != contact.backedUp()) onChange()
    }

    override fun remove(accountId: AccountId) {
        val before = contacts.get(accountId)
        contacts.remove(accountId)
        if (before?.backedUp() != null) onChange()
    }

    /** `null` for a contact the backup leaves out: a message request in either direction. */
    private fun Contact.backedUp(): Contact? =
        takeIf { it.state == ContactState.ACCEPTED || it.state == ContactState.BLOCKED }?.copy(
            accessKeyShared = false,
            profileShared = false,
            usernameStatus = UsernameStatus.UNCHECKED,
            profileReceived = false,
            stateBeforeBlock = null,
        )
}
