package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.net.RelayStream
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerification
import java.io.File

/**
 * A received, verified and decrypted message: the core's `IncomingMessage`. Here
 * [IncomingMessage.senderUsername] is set only when `sender_username` came with this message and
 * was verified against the directory (§6.3); [IncomingMessage.contactState] is not used.
 */
typealias InboundMessage = IncomingMessage

/** The relay accepted a send (`202`). */
data class SendOutcome(val serverTimestamp: Long)

/** Whether the account's token can be used after [MessagingBackend.finishPendingTokenRotation]. */
enum class TokenRotationResult {
    /** No rotation was pending, or it has just been settled. */
    READY,

    /** `401` for the backup's token and for the new one: another phone restored the same backup first, or the account was deleted. */
    BACKUP_NO_LONGER_VALID,
}

/** A directory hit (§4.4): the account and the identity key the relay serves. */
class DirectoryEntry(val account: AccountId, identityKey: ByteArray) {
    private val key = identityKey.copyOf()
    val identityKey: ByteArray get() = key.copyOf()

    override fun equals(other: Any?): Boolean = other is DirectoryEntry && other.account == account && other.key.contentEquals(key)

    override fun hashCode(): Int = account.hashCode() * 31 + key.contentHashCode()

    override fun toString(): String = "DirectoryEntry($account)"
}

/** A contact a profile or presence goes to, with the access key its delivery may use. */
data class Recipient(val account: AccountId, val accessKey: ByteString?)

/**
 * Everything the runtime does over the network or with libsignal state, so the runtime can be
 * tested without a relay. [MessengerBackend] is the production implementation on the core's
 * `Messenger`.
 */
interface MessagingBackend {
    val accountId: AccountId

    suspend fun username(): Username

    suspend fun currentAccessKey(): ByteString

    suspend fun openStream(): RelayStream

    /**
     * Seals and delivers [content]. [requestState] and [theirAccessKey] are the engine's view of
     * the contact; a backend that keeps its own contact records may use those instead.
     */
    suspend fun send(
        content: Content,
        recipient: AccountId,
        requestState: ContactState,
        theirAccessKey: ByteString?,
        ephemeral: Boolean,
    ): SendOutcome

    /** One ephemeral fan-out (§8.6); returns who was addressed. */
    suspend fun sendPresence(presence: Content, contacts: List<Recipient>): List<AccountId>

    /**
     * Whether the backend's own receive loop announces presence (ONLINE after the backlog, the
     * 240 s refresh, replies) — the engine then only tracks what it receives and ends with
     * [announceOffline].
     */
    val runsPresence: Boolean get() = false

    /** OFFLINE to the contacts the backend sees online (only when [runsPresence]). */
    suspend fun announceOffline() {}

    /**
     * Pending relay setup, pre-key refill and signed / Kyber last-resort rotation (§5).
     */
    suspend fun performMaintenance()

    /** A new access key on the relay (§6.4 block); returns it. */
    suspend fun rotateAccessKey(): ByteString

    /** Completes the profile announcement after a restore (§5.5 step 5); returns who could not be reached. */
    suspend fun announceProfile(profile: Content, contacts: List<Recipient>): List<AccountId>

    suspend fun needsProfileAnnouncement(): Boolean

    /**
     * Whether profile changes (display name, presence sharing, username, picture) reach accepted
     * contacts through the backend itself ([updateProfile], [changeUsername], [setOwnAvatar]),
     * so the engine must not announce them again.
     */
    val announcesProfileChanges: Boolean get() = false

    /** The display name and presence setting the backend's account keeps (and announces, see [announcesProfileChanges]). */
    suspend fun updateProfile(displayName: String, presenceSharing: Boolean) {}

    suspend fun uploadAttachment(file: File, kind: Attachment.Kind, contentType: String, fileName: String?): Attachment

    /** Downloads, verifies and decrypts into [destination], reporting the fraction received (0…1) while it downloads. */
    suspend fun downloadAttachment(attachment: Attachment, destination: File, progress: (Double) -> Unit = {})

    /** Unsend: deletes our uploaded blob from the relay (§4.9). */
    suspend fun deleteUploadedMedia(attachment: Attachment)

    suspend fun findAccount(username: Username): DirectoryEntry?

    suspend fun usernameOwner(username: Username): AccountId?

    /** `PUT /v1/accounts/me/username` (§3.3); the account is saved with the new name. */
    suspend fun changeUsername(username: Username)

    suspend fun safetyNumber(contact: AccountId): SafetyNumber?

    suspend fun identityTrust(contact: AccountId): ContactIdentity

    // Key-bound identity decisions (§5): each compares and stores in one step.

    /** Accepts a pending change without verifying, if the current key's number is [shownNumber]. */
    suspend fun approveIdentityChange(contact: AccountId, shownNumber: ByteArray): Boolean

    /** Verifies the stored key if its number is still [comparedNumber]. */
    suspend fun markVerified(contact: AccountId, comparedNumber: ByteArray, verification: IdentityVerification): Boolean

    /** Verifies the stored key if a scanned safety-number payload matches it. */
    suspend fun verifySafetyNumber(contact: AccountId, scanned: ByteArray, verification: IdentityVerification): Boolean

    /** Pins the key a contact code committed to (verified, approved). */
    suspend fun pinVerifiedIdentity(identityKey: ByteArray, contact: AccountId, verification: IdentityVerification): Boolean

    suspend fun clearVerification(contact: AccountId)

    suspend fun identityVerification(contact: AccountId): IdentityVerification?

    suspend fun forgetContact(contact: AccountId)

    /** The encrypted account backup file (§5.5) under [password]. */
    suspend fun exportBackup(password: String): ByteArray

    suspend fun deleteAccount()

    /** The own identity key (serialized public key), for the contact code's fingerprint. */
    suspend fun ownIdentityKey(): ByteArray

    /**
     * A restore's token rotation is still unconfirmed (§5.5 step 3.4): the relay has not said yet
     * whether it holds the new token or still the backup's, so the old phone may still have access.
     * Backends without token rotations (test doubles) never have one pending.
     */
    suspend fun hasPendingTokenRotation(): Boolean = false

    /**
     * §5.5 step 3.4 now: asks the relay which of the two stored tokens the account has and keeps
     * that one. [TokenRotationResult.READY] also when nothing was pending; throws while the outcome
     * is still unknown (no answer, `429`, `5xx`) or the relay refused the new token.
     */
    suspend fun finishPendingTokenRotation(): TokenRotationResult = TokenRotationResult.READY

    // Profile pictures (§8.9, §8.10). `null` is the initial letter.

    suspend fun ownAvatar(): OwnProfilePicture = OwnProfilePicture(null, showInSearch = true, searchRecord = AvatarRecordStatus.IN_SYNC)

    suspend fun setOwnAvatar(picture: ProfilePicture?, showInSearch: Boolean) {}

    /** Makes a record write still owed now, one at a time (§8.10); the relay's answer is in the result, never thrown. */
    suspend fun syncAvatarRecord(): AvatarRecordStatus = AvatarRecordStatus.IN_SYNC

    /** The picture of a search result (§8.10 step 4), no contact needed; the initial letter on any failure. */
    suspend fun searchAvatar(username: Username, account: AccountId): ProfilePicture? = null

    /** The vault locks: forget what is kept in memory (search pictures, derived seeds). */
    fun close() {}
}
