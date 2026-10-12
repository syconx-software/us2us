package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.BackupContact
import com.syconx.us2us.core.proto.backupContact
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.vault.VaultFiles
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The storage's part of account backups (PROTOCOL.md §5.5). The core's `Messenger` builds,
 * encrypts and restores the backup file itself (`exportBackup`, `Messenger.restore`), reading and
 * writing contacts through [SqlContactStore]; this service covers what the storage adds around it.
 *
 * Which contacts a backup holds: accepted and blocked ones. Pending message requests are not
 * relationships yet (§6.4). On restore every non-blocked contact comes back as accepted, with the
 * access key it gave us, its profile claim (unchecked), its `presence_sharing` and the profile
 * picture it last sent (§8.9).
 */
object AccountBackupService {
    /** The contacts a backup holds, as `BackupContact`s without their identity fields (the core fills those in). */
    fun backupContacts(tx: StorageTransaction): List<BackupContact> = tx.contacts.all()
        .filter { it.requestState == ContactState.ACCEPTED || it.requestState == ContactState.BLOCKED }
        .map { contact ->
            backupContact {
                accountId = ByteString.copyFrom(contact.accountId.toBytes())
                username = contact.profile.username?.value.orEmpty()
                displayName = contact.profile.displayName.orEmpty()
                blocked = contact.isBlocked
                theirAccessKey = contact.theirAccessKey ?: ByteString.EMPTY
                presenceSharing = contact.profile.presenceSharing
                contact.profile.picture?.let { avatar = it.toProto() }
            }
        }

    /**
     * Writes an exported backup to a staging file to hand to the share sheet or the system file
     * picker. The name carries no account information.
     */
    fun writeToStaging(file: ByteArray, storage: LocalStorage, now: Instant = Instant.now()): File {
        val directory = storage.makeStagingFile()
        if (!directory.mkdirs()) storageFailure(StorageError.Io("cannot prepare staging"))
        val name = "us2us-backup-${STAMP.format(now.atZone(ZoneId.systemDefault()))}.brpb"
        val target = File(directory, name)
        try {
            VaultFiles.write(target, file)
        } catch (_: IOException) {
            storageFailure(StorageError.Io("write backup"))
        }
        return target
    }

    /**
     * Creates the contacts of a validated backup (skipping ones that exist). Every contact that is
     * not blocked also gets its (empty) conversation: the chat list is built from conversations, so
     * without one a restored contact would exist but appear nowhere (messages are never in a
     * backup, PROTOCOL §5.5).
     */
    fun restoreContacts(backup: AccountBackup, tx: StorageTransaction, now: Instant = Instant.now()) {
        for (entry in backup.contactsList) {
            val account = accountIdOf(entry.accountId.toByteArray()) ?: continue
            if (tx.contacts.get(account) != null) continue
            tx.contacts.create(account, if (entry.blocked) ContactState.BLOCKED else ContactState.ACCEPTED, now)
            val username = entry.username.takeIf { it.isNotEmpty() }?.let { text -> Username.parse(text)?.takeIf { it.value == text } }
            tx.contacts.setProfile(
                ContactProfile(
                    username = username,
                    displayName = entry.displayName.ifEmpty { null },
                    presenceSharing = entry.presenceSharing,
                    picture = if (entry.hasAvatar()) ProfilePicture.fromProto(entry.avatar) else null,
                ),
                account,
            )
            if (entry.theirAccessKey.size() == AccessKey.LENGTH) tx.contacts.setTheirAccessKey(entry.theirAccessKey, account)
            if (!entry.blocked) {
                // The restored account keeps its access key, which these contacts hold.
                tx.contacts.setOurAccessKeySent(backup.accessKey, account)
                tx.conversations.ensure(account, now)
            }
        }
    }

    /**
     * Completes a restore done by the core's `Messenger.restore` on this storage: the contacts it
     * wrote get their conversations, and the access key they hold is recorded as the account's
     * (the core writes contacts before the account exists). Call it once the restored account is
     * saved, in a write transaction.
     */
    fun finishRestore(tx: StorageTransaction, now: Instant = Instant.now()) {
        tx.requireWritable()
        val account = tx.account.load() ?: storageFailure(StorageError.InvalidState("no local account"))
        val accessKey = ByteString.copyFrom(account.accessKey.toBytes())
        val placeholder = ByteString.copyFrom(ByteArray(AccessKey.LENGTH))
        for (contact in tx.contacts.all()) {
            if (contact.ourAccessKeySent == placeholder) tx.contacts.setOurAccessKeySent(accessKey, contact.accountId)
            if (contact.requestState == ContactState.ACCEPTED) tx.conversations.ensure(contact.accountId, now)
        }
    }

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
}
