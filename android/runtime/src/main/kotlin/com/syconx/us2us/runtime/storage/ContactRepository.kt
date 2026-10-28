package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ProfileUpdate
import com.syconx.us2us.core.username.Username
import java.time.Instant

/**
 * Contacts and the message-request state machine of PROTOCOL.md §6.4:
 *
 * | from | event | to |
 * |---|---|---|
 * | none | we send | outgoing request |
 * | none | verified message received | incoming request |
 * | outgoing request | `MessageRequestResponse{ACCEPT}` received | accepted |
 * | incoming request | user accepts and the relay answered 202 | accepted |
 * | any | Block | blocked |
 *
 * No other event ends a request. Unblocking returns to the state before the block.
 */
class ContactRepository internal constructor(private val tx: StorageTransaction) {
    fun get(account: AccountId): StoredContact? =
        tx.connection.queryOne("SELECT ${ContactRows.COLUMNS} FROM contacts WHERE account = ?", account.toBytes())?.let(::contact)

    /** All contacts except those waiting to be forgotten, newest first. */
    fun all(includingPendingForget: Boolean = false): List<StoredContact> = tx.connection.query(
        "SELECT ${ContactRows.COLUMNS} FROM contacts WHERE pending_forget = 0 OR ? ORDER BY created_at DESC",
        includingPendingForget,
    ).map(::contact)

    fun accepted(): List<StoredContact> = tx.connection.query(
        "SELECT ${ContactRows.COLUMNS} FROM contacts WHERE request_state = ? AND pending_forget = 0",
        ContactState.ACCEPTED.raw,
    ).map(::contact)

    /** Every blocked account, with a conversation or without one (a ghost wipe or a deleted conversation leaves the block, §6.4). */
    fun blocked(): List<StoredContact> = tx.connection.query(
        "SELECT ${ContactRows.COLUMNS} FROM contacts WHERE request_state = ? AND pending_forget = 0",
        ContactState.BLOCKED.raw,
    ).map(::contact)

    fun requestState(account: AccountId): ContactState? =
        contactState(tx.connection.queryOne("SELECT request_state FROM contacts WHERE account = ?", account.toBytes())?.text("request_state"))

    // MARK: §6.4 transitions

    /** Before sending to [account]: an account without state becomes an outgoing request. Returns the state to send under (throws if blocked). */
    fun prepareToSend(account: AccountId, now: Instant = Instant.now()): ContactState {
        tx.requireWritable()
        requestState(account)?.let { state ->
            if (state == ContactState.BLOCKED) storageFailure(StorageError.InvalidState("blocked"))
            return state
        }
        insert(account, ContactState.OUTGOING_REQUEST, now)
        return ContactState.OUTGOING_REQUEST
    }

    /**
     * After a message from [account] passed sender verification (§6.3): creates an incoming request
     * for an unknown account, and ends an outgoing request on `ACCEPT`. Returns the new state;
     * blocked means the message must be dropped locally.
     */
    fun noteVerifiedMessage(content: Content, account: AccountId, now: Instant = Instant.now()): ContactState {
        tx.requireWritable()
        val current = requestState(account)
        // An outgoing request nothing was sent for yet is no request the peer could accept (M-9).
        if (current == ContactState.OUTGOING_REQUEST && !requestSent(account)) return current
        val next = MessageRequests.afterIncoming(current, content)
        if (current == null) {
            insert(account, next, now)
        } else if (next != current) {
            setState(next, account)
        }
        return next
    }

    /**
     * Whether the relay accepted (`202`) a visible message of ours to [account] while it was an
     * outgoing request. Opening a handle makes the row before anything is sent (audit M-9).
     */
    fun requestSent(account: AccountId): Boolean =
        tx.connection.queryOne("SELECT request_sent_at FROM contacts WHERE account = ?", account.toBytes())
            ?.long("request_sent_at") != null

    /** The relay answered `202` for a visible message of ours to [account]: its outgoing request now exists on the other phone. */
    fun markRequestSent(account: AccountId, now: Instant = Instant.now()) {
        tx.requireWritable()
        tx.connection.run(
            "UPDATE contacts SET request_sent_at = ? WHERE account = ? AND request_state = ? AND request_sent_at IS NULL",
            now.toEpochMilli(),
            account.toBytes(),
            ContactState.OUTGOING_REQUEST.raw,
        )
    }

    /**
     * A verified visible message from [account], while our outgoing request to it was opened but
     * never sent: nothing of ours reached it, so this is its own request to us (audit M-9).
     */
    fun turnUnsentRequestIntoIncoming(account: AccountId) {
        tx.requireWritable()
        if (requestState(account) != ContactState.OUTGOING_REQUEST || requestSent(account)) return
        setState(ContactState.INCOMING_REQUEST, account)
    }

    /** The user accepted an incoming request and the relay answered 202 for our `ACCEPT` (§6.4). Before that the state must not change. */
    fun acceptIncomingRequest(account: AccountId) {
        tx.requireWritable()
        when (requestState(account)) {
            ContactState.INCOMING_REQUEST -> setState(ContactState.ACCEPTED, account)
            ContactState.ACCEPTED -> return
            else -> storageFailure(StorageError.InvalidState("no incoming request"))
        }
    }

    /**
     * Both sides wrote first (§6.4 crossing requests): we are in an outgoing request and a verified
     * visible message from the contact carried its `sender_username`. Both phones then treat that as
     * mutual acceptance. Returns whether the state changed.
     */
    fun acceptCrossingRequest(account: AccountId): Boolean {
        tx.requireWritable()
        if (requestState(account) != ContactState.OUTGOING_REQUEST) return false
        setState(ContactState.ACCEPTED, account)
        return true
    }

    /** Any state → blocked. The runtime must rotate the access key (§6.4). */
    fun block(account: AccountId, now: Instant = Instant.now()) {
        tx.requireWritable()
        val current = requestState(account)
        if (current == null) {
            insert(account, ContactState.BLOCKED, now)
            return
        }
        if (current == ContactState.BLOCKED) return
        tx.connection.run(
            "UPDATE contacts SET request_state = ?, state_before_block = ? WHERE account = ?",
            ContactState.BLOCKED.raw,
            current.raw,
            account.toBytes(),
        )
    }

    /** Blocked → the state before the block. Returns `false` (and changes nothing) when the account is not blocked. */
    fun unblock(account: AccountId): Boolean {
        tx.requireWritable()
        val row = tx.connection.queryOne(
            "SELECT request_state, state_before_block FROM contacts WHERE account = ?",
            account.toBytes(),
        ) ?: return false
        if (row.text("request_state") != ContactState.BLOCKED.raw) return false
        val previous = contactState(row.text("state_before_block")) ?: ContactState.INCOMING_REQUEST
        tx.connection.run(
            "UPDATE contacts SET request_state = ?, state_before_block = NULL WHERE account = ?",
            previous.raw,
            account.toBytes(),
        )
        return true
    }

    /**
     * Unblocking an account with nothing to go back to: no conversation, and before the block no
     * contact either (blocked as a stranger, or kept as a bare block by "leave no trace",
     * [stripToBlock]) or only an incoming request, whose messages went with the conversation. It is
     * forgotten, a stranger again: writing to it starts a new chat and a message from it is a new
     * request, never an empty one. Returns `false` (and changes nothing) for any other account.
     */
    fun unblockLeavingNothing(account: AccountId): Boolean {
        tx.requireWritable()
        val row = tx.connection.queryOne(
            "SELECT request_state, state_before_block FROM contacts WHERE account = ?",
            account.toBytes(),
        ) ?: return false
        if (row.text("request_state") != ContactState.BLOCKED.raw) return false
        if (row.text("state_before_block") !in setOf(null, ContactState.INCOMING_REQUEST.raw)) return false
        if (tx.conversations.forContact(account) != null) return false
        forget(account)
        return true
    }

    /**
     * The user writes to a blocked account again (4.2) that has no state to go back to (blocked
     * before it was a contact, or kept as a bare block by "leave no trace", [stripToBlock]):
     * unblocking it then makes it an outgoing request, as a new chat would be. A state kept from
     * before the block stays as it is.
     */
    fun unblockAsOutgoingRequest(account: AccountId) {
        tx.requireWritable()
        tx.connection.run(
            "UPDATE contacts SET state_before_block = ? WHERE account = ? AND request_state = ? AND state_before_block IS NULL",
            ContactState.OUTGOING_REQUEST.raw,
            account.toBytes(),
            ContactState.BLOCKED.raw,
        )
    }

    // MARK: Access keys (§4.6, §6.4)

    /** The contact's `AccessKeyUpdate`. */
    fun setTheirAccessKey(key: ByteString?, account: AccountId) {
        tx.requireWritable()
        if (key != null && key.size() != AccessKey.LENGTH) storageFailure(StorageError.InvalidState("access key size"))
        update(account, "their_access_key = ?", key)
    }

    /** Records which of our access keys the contact holds. */
    fun setOurAccessKeySent(key: ByteString?, account: AccountId) {
        tx.requireWritable()
        update(account, "our_access_key_sent = ?", key)
    }

    /** Accepted contacts that do not hold [current] yet (after a rotation). */
    fun needingAccessKey(current: ByteString): List<StoredContact> = accepted().filter { it.ourAccessKeySent != current }

    // MARK: Profiles (§8.7, §3.3)

    /**
     * Applies a complete `ProfileUpdate`; an older one (by `updated_at`) is ignored. Returns the
     * stored profile. An `updated_at` more than a day ahead of [now] is clamped, so a far-future
     * stamp cannot make every later update look older.
     */
    fun applyProfileUpdate(update: ProfileUpdate, account: AccountId, now: Instant = Instant.now()): ContactProfile {
        tx.requireWritable()
        val ceiling = now.toEpochMilli() + StorageRules.MAX_CLOCK_SKEW.inWholeMilliseconds
        val clamped = if (java.lang.Long.compareUnsigned(update.updatedAt, ceiling) > 0) {
            update.toBuilder().setUpdatedAt(ceiling).build()
        } else {
            update
        }
        val contact = get(account) ?: storageFailure(StorageError.NotFound)
        if (java.lang.Long.compareUnsigned(clamped.updatedAt, contact.profile.updatedAt) < 0) return contact.profile
        val profile = contact.profile.applying(clamped)
        setProfile(profile, account)
        return profile
    }

    /** Stores [profile]; its picture as a serialized `ProfileAvatar` (none for the initial letter), inside the encrypted database only (§8.9). */
    fun setProfile(profile: ContactProfile, account: AccountId) {
        tx.requireWritable()
        update(
            account,
            "username = ?, username_status = ?, display_name = ?, presence_sharing = ?, avatar = ?, profile_updated_at = ?",
            profile.username?.value,
            profile.usernameStatus.raw,
            profile.displayName,
            profile.presenceSharing,
            profile.picture?.toProto()?.toByteArray(),
            profile.updatedAt,
        )
    }

    fun recordUsernameCheck(username: Username, owner: AccountId?, account: AccountId) {
        val contact = get(account) ?: storageFailure(StorageError.NotFound)
        setProfile(contact.profile.recordingUsernameCheck(username, owner, account), account)
    }

    /**
     * Our own directory lookup answered that [username] belongs to [owner] (§3.3): every other
     * contact still showing that name gets [UsernameStatus.MISMATCH], so it is never shown as their
     * name again (an account deleted and its username registered by someone else). Returns how many
     * contacts changed.
     */
    fun noteUsernameOwner(username: Username, owner: AccountId): Int {
        tx.requireWritable()
        // A deleted contact's kept name is not its name any more either.
        tx.connection.run("DELETE FROM former_contacts WHERE username = ? AND account != ?", username.value, owner.toBytes())
        return tx.connection.run(
            "UPDATE contacts SET username_status = ? WHERE username = ? AND account != ? AND username_status != ?",
            UsernameStatus.MISMATCH.raw,
            username.value,
            owner.toBytes(),
            UsernameStatus.MISMATCH.raw,
        )
    }

    /** A username verified by our own lookup or a verified `sender_username`. */
    fun confirmUsername(username: Username, account: AccountId) {
        val contact = get(account) ?: storageFailure(StorageError.NotFound)
        setProfile(contact.profile.confirming(username), account)
    }

    fun setProfileSent(at: Instant?, account: AccountId) {
        tx.requireWritable()
        update(account, "profile_sent_at = ?", at?.toEpochMilli())
    }

    // MARK: Creation and removal

    /** Creates a contact with an explicit state (e.g. from a backup). */
    fun create(account: AccountId, state: ContactState, now: Instant = Instant.now()) {
        tx.requireWritable()
        if (requestState(account) != null) storageFailure(StorageError.InvalidState("contact exists"))
        insert(account, state, now)
    }

    /** Contacts flagged by a ghost wipe with `wipe_contact` (§8.2). */
    fun pendingForget(): List<AccountId> =
        tx.connection.query("SELECT account FROM contacts WHERE pending_forget = 1").mapNotNull { accountIdOf(it.blob("account")) }

    /** Deletes the contact, its conversation (crypto-shredded first), pending sends to it, and its session and identity. */
    fun forget(account: AccountId) {
        tx.requireWritable()
        tx.conversations.forContact(account)?.let { WipeService.wipe(it, WipeReason.REQUESTED, tx) }
        tx.connection.run("DELETE FROM outbox WHERE recipient = ?", account.toBytes())
        tx.connection.run("DELETE FROM contacts WHERE account = ?", account.toBytes())
        forgetFormerName(account)
        tx.storage.protocolStorage.deleteProtocolState(account, tx.connection)
    }

    /**
     * "Kişiyi sil" (§6.4): deletes the contact, its conversation (crypto-shredded first) and pending
     * sends to it, but keeps its session and identity, and the username it was shown under. Nothing
     * is sent and the contact is not blocked: if it writes again, its message still decrypts and
     * opens a new incoming request under that name ([insert]); an identity change is still flagged,
     * because the identity stays.
     */
    fun removeKeepingSession(account: AccountId) {
        tx.requireWritable()
        val contact = get(account) ?: storageFailure(StorageError.NotFound)
        val label = contact.profile.username?.takeIf { contact.profile.usernameStatus != UsernameStatus.MISMATCH }
        tx.conversations.forContact(account)?.let { WipeService.wipe(it, WipeReason.REQUESTED, tx) }
        tx.connection.run("DELETE FROM outbox WHERE recipient = ?", account.toBytes())
        tx.connection.run("DELETE FROM contacts WHERE account = ?", account.toBytes())
        if (label != null) {
            tx.connection.run("INSERT OR REPLACE INTO former_contacts (account, username) VALUES (?, ?)", account.toBytes(), label.value)
        } else {
            forgetFormerName(account)
        }
    }

    /** The username kept for a contact deleted with [removeKeepingSession], if any. */
    fun formerName(account: AccountId): Username? =
        tx.connection.queryOne("SELECT username FROM former_contacts WHERE account = ?", account.toBytes())
            ?.text("username")?.let { text -> Username.parse(text)?.takeIf { it.value == text } }

    private fun forgetFormerName(account: AccountId) {
        tx.connection.run("DELETE FROM former_contacts WHERE account = ?", account.toBytes())
    }

    /** Removes everything about a blocked contact except the block: profile, access keys, pending sends, session and identity. */
    internal fun stripToBlock(account: AccountId) {
        update(
            account,
            """
            username = NULL, username_status = 'unchecked', display_name = NULL, presence_sharing = 0,
            avatar = NULL, profile_updated_at = 0, their_access_key = NULL, our_access_key_sent = NULL,
            profile_sent_at = NULL, state_before_block = NULL
            """.trimIndent(),
        )
        tx.connection.run("DELETE FROM outbox WHERE recipient = ?", account.toBytes())
        forgetFormerName(account)
        tx.storage.protocolStorage.deleteProtocolState(account, tx.connection)
    }

    internal fun markPendingForget(account: AccountId) = update(account, "pending_forget = 1")

    // MARK: Rows

    /**
     * Every new contact row. An account deleted with [removeKeepingSession] gets back the username it
     * was shown under, as a claim to check again (`unchecked`, §3.3); the kept name is used up.
     */
    private fun insert(account: AccountId, state: ContactState, now: Instant) {
        val former = formerName(account)
        tx.connection.run(
            "INSERT INTO contacts (account, request_state, username, username_status, created_at) VALUES (?, ?, ?, ?, ?)",
            account.toBytes(),
            state.raw,
            former?.value,
            UsernameStatus.UNCHECKED.raw,
            now.toEpochMilli(),
        )
        if (former != null) forgetFormerName(account)
    }

    private fun setState(state: ContactState, account: AccountId) = update(account, "request_state = ?", state.raw)

    private fun update(account: AccountId, assignments: String, vararg values: Any?) {
        val changed = tx.connection.run("UPDATE contacts SET $assignments WHERE account = ?", *values, account.toBytes())
        if (changed == 0) storageFailure(StorageError.NotFound)
    }

    private fun contact(row: Row): StoredContact {
        val account = accountIdOf(row.blob("account")) ?: storageFailure(StorageError.Integrity)
        val state = contactState(row.text("request_state")) ?: storageFailure(StorageError.Integrity)
        val username = row.text("username")?.let { text -> Username.parse(text)?.takeIf { it.value == text } }
        val profile = ContactProfile(
            username = username,
            usernameStatus = usernameStatus(row.text("username_status")) ?: UsernameStatus.UNCHECKED,
            displayName = row.text("display_name"),
            presenceSharing = row.bool("presence_sharing"),
            // Checked again on the way out: anything malformed is the initial letter.
            picture = row.blob("avatar")?.let(ProfilePicture::parse),
            updatedAt = row.long("profile_updated_at") ?: 0,
        )
        return StoredContact(
            accountId = account,
            requestState = state,
            profile = profile,
            theirAccessKey = row.bytes("their_access_key"),
            ourAccessKeySent = row.bytes("our_access_key_sent"),
            profileSentAt = row.instant("profile_sent_at"),
            createdAt = row.instant("created_at") ?: Instant.EPOCH,
            pendingForget = row.bool("pending_forget"),
            identity = tx.storage.protocolStorage.identityTrust(account),
        )
    }
}

internal object ContactRows {
    const val COLUMNS = """account, request_state, state_before_block, username, username_status, display_name,
        presence_sharing, avatar, profile_updated_at, their_access_key, our_access_key_sent,
        profile_sent_at, pending_forget, created_at"""
}
