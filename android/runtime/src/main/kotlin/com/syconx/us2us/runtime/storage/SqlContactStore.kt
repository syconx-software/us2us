package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactStore
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.username.Username
import java.time.Clock

/**
 * The core's [ContactStore] on the `contacts` table that [ContactRepository] reads too.
 *
 * The core keeps three facts as flags that the iOS columns keep as values; they map as:
 * * `accessKeyShared` ↔ `our_access_key_sent` is set (the account's access key when it was shared);
 * * `profileShared` ↔ `profile_sent_at` is set (when it was first marked shared);
 * * `profileReceived` ↔ `profile_updated_at` is not 0.
 *
 * Writes never take staged decryption state along ([Writer.DETACHED]): the core records what a
 * received message says about its sender before handing the message over, and that update is
 * idempotent, so it may commit ahead of the stored message; the ratchet may not.
 *
 * Contacts waiting to be forgotten (ghost `wipe_contact`, §8.2) are found by [get] but left out of
 * [all]. [remove] forgets a contact like [ContactRepository.forget]: its conversation is
 * crypto-shredded first.
 */
class SqlContactStore internal constructor(
    private val storage: LocalStorage,
    private val clock: Clock = Clock.systemUTC(),
) : ContactStore {
    override fun get(accountId: AccountId): Contact? = storage.database.locked { connection ->
        connection.queryOne("SELECT ${ContactRows.COLUMNS} FROM contacts WHERE account = ?", accountId.toBytes())?.let(::contact)
    }

    override fun put(contact: Contact) {
        storage.database.write(Writer.DETACHED) { connection ->
            val now = clock.millis()
            val existing = connection.queryOne(
                "SELECT our_access_key_sent, profile_sent_at, profile_updated_at FROM contacts WHERE account = ?",
                contact.accountId.toBytes(),
            )
            val ourKeySent = when {
                !contact.accessKeyShared -> null
                else -> existing?.blob("our_access_key_sent")
                    ?: storage.knownAccount(connection)?.accessKey?.toBytes()
                    // Restoring: the account is saved after its contacts. AccountBackupService
                    // replaces this placeholder with the account's key once it is stored.
                    ?: ByteArray(AccessKey.LENGTH)
            }
            val profileSentAt = if (contact.profileShared) existing?.long("profile_sent_at") ?: now else null
            val profileUpdatedAt = when {
                !contact.profileReceived -> 0L
                else -> existing?.long("profile_updated_at")?.takeIf { it != 0L } ?: 1L
            }
            val values = arrayOf<Any?>(
                contact.state.raw,
                contact.stateBeforeBlock?.raw,
                contact.username,
                contact.usernameStatus.raw,
                contact.displayName,
                contact.presenceSharing,
                contact.picture?.toProto()?.toByteArray(),
                profileUpdatedAt,
                contact.theirAccessKey?.toBytes(),
                ourKeySent,
                profileSentAt,
            )
            if (existing == null) {
                connection.run(
                    """
                    INSERT INTO contacts (request_state, state_before_block, username, username_status, display_name,
                        presence_sharing, avatar, profile_updated_at, their_access_key, our_access_key_sent,
                        profile_sent_at, account, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    *values,
                    contact.accountId.toBytes(),
                    now,
                )
            } else {
                connection.run(
                    """
                    UPDATE contacts SET request_state = ?, state_before_block = ?, username = ?, username_status = ?,
                        display_name = ?, presence_sharing = ?, avatar = ?, profile_updated_at = ?, their_access_key = ?,
                        our_access_key_sent = ?, profile_sent_at = ?
                    WHERE account = ?
                    """.trimIndent(),
                    *values,
                    contact.accountId.toBytes(),
                )
            }
        }
    }

    override fun remove(accountId: AccountId) {
        storage.database.write(Writer.DETACHED) { connection ->
            StorageTransaction(connection, storage, isWritable = true).contacts.forget(accountId)
        }
    }

    override fun all(): List<Contact> = storage.database.locked { connection ->
        connection.query("SELECT ${ContactRows.COLUMNS} FROM contacts WHERE pending_forget = 0 ORDER BY created_at DESC")
            .mapNotNull { row -> runCatching { contact(row) }.getOrNull() }
    }

    private fun contact(row: Row): Contact {
        val account = accountIdOf(row.blob("account")) ?: storageFailure(StorageError.Integrity)
        val state = contactState(row.text("request_state")) ?: storageFailure(StorageError.Integrity)
        return Contact(
            accountId = account,
            username = row.text("username")?.takeIf { text -> Username.parse(text)?.value == text },
            state = state,
            theirAccessKey = row.blob("their_access_key")?.takeIf { it.size == AccessKey.LENGTH }?.let(::AccessKey),
            accessKeyShared = row.blob("our_access_key_sent") != null,
            displayName = row.text("display_name"),
            presenceSharing = row.bool("presence_sharing"),
            profileShared = row.long("profile_sent_at") != null,
            usernameStatus = usernameStatus(row.text("username_status")) ?: UsernameStatus.UNCHECKED,
            picture = row.blob("avatar")?.let(ProfilePicture::parse),
            profileReceived = (row.long("profile_updated_at") ?: 0L) != 0L,
            stateBeforeBlock = contactState(row.text("state_before_block")),
        )
    }
}
