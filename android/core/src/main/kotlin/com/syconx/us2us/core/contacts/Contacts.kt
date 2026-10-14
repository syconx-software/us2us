package com.syconx.us2us.core.contacts

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.accessKeyUpdate
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.messageRequestResponse
import java.util.concurrent.ConcurrentHashMap

/** Where a conversation stands in the message-request flow (PROTOCOL.md §6.4). */
enum class ContactState {
    /** We wrote first and the peer has not accepted yet. */
    OUTGOING_REQUEST,

    /** The peer wrote first and we have not accepted yet. */
    INCOMING_REQUEST,

    ACCEPTED,

    /** Messages from this account are dropped locally. */
    BLOCKED,
}

/** What the directory says about a contact's [Contact.username] (PROTOCOL.md §3.3). */
enum class UsernameStatus {
    /** Only claimed, by a `ProfileUpdate` or a backup. Show it as unverified until [com.syconx.us2us.core.messenger.Messenger.verifyUsername] ran. */
    UNCHECKED,

    /** The directory maps the username to this account: our own lookup, a verified `sender_username`, or [com.syconx.us2us.core.messenger.Messenger.verifyUsername]. */
    VERIFIED,

    /** The directory maps the username to nobody or to another account. Never show it as this contact's name. */
    MISMATCH,
}

data class Contact(
    val accountId: AccountId,
    /**
     * Normalized username, as last announced by the peer (request or `ProfileUpdate`) or found by
     * our own lookup. A username from a `ProfileUpdate` is only a claim; see [usernameStatus].
     */
    val username: String?,
    val state: ContactState,
    /** The peer's access key, which lets us deliver without proof-of-work. */
    val theirAccessKey: AccessKey? = null,
    /** Whether the peer has our current access key. Reset when we rotate it. */
    val accessKeyShared: Boolean = false,
    /** Display name from the peer's last `ProfileUpdate`. */
    val displayName: String? = null,
    /**
     * The peer shares "active now" presence (`ProfileUpdate.presence_sharing`, PROTOCOL.md §8.6).
     * Unknown until its first `ProfileUpdate`, which accepted contacts exchange right after the
     * access keys; until then nothing is sent.
     */
    val presenceSharing: Boolean = false,
    /** Whether the peer has our current profile (username, display name, presence sharing, picture). */
    val profileShared: Boolean = false,
    /** Whether [username] was checked against the directory, and with what result (§3.3). */
    val usernameStatus: UsernameStatus = UsernameStatus.UNCHECKED,
    /**
     * The peer's profile picture from its last `ProfileUpdate.profile_avatar` (PROTOCOL.md §8.9),
     * already checked; `null` is the initial letter. Persist it only in the encrypted store, as
     * `picture.toProto().toByteArray()` (read back with `ProfilePicture.parse`); it goes with the
     * contact.
     */
    val picture: ProfilePicture? = null,
    /**
     * Whether a `ProfileUpdate` of the peer has arrived (or was restored from a backup). From then
     * on [picture] is the peer's own choice, and its avatar record is not fetched (§8.9, §8.10).
     */
    val profileReceived: Boolean = false,
    /**
     * While [ContactState.BLOCKED]: the state the block interrupted, which unblocking returns to.
     * `null` for an account blocked as a stranger or restored blocked from a backup (which holds
     * only the flag, §5.5); unblocking makes it an incoming request.
     */
    val stateBeforeBlock: ContactState? = null,
)

/** Persistence for contacts. The app implements it on its encrypted database. */
interface ContactStore {
    fun get(accountId: AccountId): Contact?
    fun put(contact: Contact)
    fun remove(accountId: AccountId)
    fun all(): List<Contact>
}

class InMemoryContactStore : ContactStore {
    private val contacts = ConcurrentHashMap<AccountId, Contact>()

    override fun get(accountId: AccountId): Contact? = contacts[accountId]
    override fun put(contact: Contact) {
        contacts[contact.accountId] = contact
    }
    override fun remove(accountId: AccountId) {
        contacts.remove(accountId)
    }
    override fun all(): List<Contact> = contacts.values.toList()
}

/** The rules of PROTOCOL.md §6.4 and §8.4, in one place for both the facade and the app. */
object MessageRequests {
    /** `sender_username` is included only while our own request is pending. */
    fun includeSenderUsername(state: ContactState): Boolean = state == ContactState.OUTGOING_REQUEST

    /**
     * Whether the facade lets the user write in [state]: not to blocked accounts, and not before
     * accepting an incoming request (accepting is the first answer).
     */
    fun maySend(state: ContactState): Boolean = state == ContactState.OUTGOING_REQUEST || state == ContactState.ACCEPTED

    /**
     * Delivery and read receipts and typing indicators are never sent while a request is pending
     * in either direction, nor to blocked contacts.
     */
    fun maySendReceiptsAndTyping(state: ContactState): Boolean = state == ContactState.ACCEPTED

    /** Whether a message from an account in [state] is handed to the app at all. */
    fun accepts(state: ContactState?): Boolean = state != ContactState.BLOCKED

    /**
     * The state after receiving a verified [content] from [current] (or from a stranger if `null`).
     * Only an explicit `ACCEPT` ends our request, so both phones always agree on whether the
     * conversation is still a request (ios/Us2usKit applies the same rule).
     */
    fun afterIncoming(current: ContactState?, content: Content): ContactState = when {
        current == null -> ContactState.INCOMING_REQUEST
        current == ContactState.OUTGOING_REQUEST && isAcceptance(content) -> ContactState.ACCEPTED
        else -> current
    }

    fun isAcceptance(content: Content): Boolean =
        content.hasControl() && content.control.hasRequestResponse() &&
            content.control.requestResponse.type == MessageRequestResponse.Type.ACCEPT

    /** What the recipient sends first on Accept; its access key follows (§6.4). */
    fun acceptContent(): Content =
        content { control = controlMessage { requestResponse = messageRequestResponse { type = MessageRequestResponse.Type.ACCEPT } } }

    fun accessKeyUpdateContent(accessKey: AccessKey): Content =
        content { control = controlMessage { this.accessKey = accessKeyUpdate { this.accessKey = accessKey.toBytes().toByteString() } } }
}
