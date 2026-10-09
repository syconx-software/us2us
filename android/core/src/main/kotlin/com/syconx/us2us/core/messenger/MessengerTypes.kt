package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.attachment.BlobSource
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.relay.RelaySocket
import com.syconx.us2us.core.username.Username
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The local account. Holds secrets; persist it only inside the vault-protected database. */
data class LocalAccount(
    val accountId: AccountId,
    val authToken: AuthToken,
    val username: Username,
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
    /** Our current access key, shared with accepted contacts (PROTOCOL.md §4.6). */
    val accessKey: AccessKey,
    val displayName: String = "",
    /** "Show when I'm active" / "Aktif olduğumu göster" (PROTOCOL.md §8.6). On by default. */
    val presenceSharing: Boolean = true,
    /** Relay steps of registration or restore that are still due; see [Messenger.performMaintenance]. */
    val pendingSetup: Set<PendingSetup> = emptySet(),
) {
    override fun toString(): String = "LocalAccount($accountId, $username)"
}

/**
 * A relay step that must happen after the account exists but may fail on a bad network. Keeping it
 * in the persisted [LocalAccount] means a failure never loses the credentials the relay handed out.
 */
enum class PendingSetup {
    /** Publish `SHA-256(accessKey)` (after registration). */
    PUBLISH_ACCESS_KEY,

    /**
     * Upload a fresh key set with `replaceAll` (after a restore, §5.5). Until this succeeds the relay
     * hands out pre-keys whose private keys are gone, so it is retried until it does.
     */
    REPLACE_PRE_KEYS,

    /**
     * Send our complete `ProfileUpdate` to every accepted contact (after a restore, once the keys
     * are replaced, §5.5 step 5). Contacts that cannot be reached get it before our next message.
     */
    ANNOUNCE_PROFILE,
}

/** Persists [LocalAccount] whenever the facade changes it (token rotation, username, access key). */
fun interface AccountStore {
    fun save(account: LocalAccount)
}

data class MessengerConfig(
    val clock: Clock = Clock.systemUTC(),
    val preKeyPolicy: PreKeyPolicy = PreKeyPolicy(),
    /** Workers used to solve proof-of-work challenges. */
    val powParallelism: Int = Runtime.getRuntime().availableProcessors(),
    val reconnectMinDelay: Duration = 1.seconds,
    val reconnectMaxDelay: Duration = 60.seconds,
    /** Backoff for relay setup steps that failed ([PendingSetup]); they are retried while connected. */
    val setupRetryMinDelay: Duration = 5.seconds,
    val setupRetryMaxDelay: Duration = 300.seconds,
    /** How often the receive loop sends `{"type":"ping"}`: at most 30 s (see [RelaySocket.KEEPALIVE_INTERVAL]). */
    val keepAliveInterval: Duration = RelaySocket.KEEPALIVE_INTERVAL,
    /** Where attachment blobs are staged during upload and download; the system default if null. */
    val tempDirectory: File? = null,
    /**
     * Send batched delivery receipts for stored bubbles of accepted contacts (PROTOCOL.md §8.4): the
     * sender's only sign that the recipient opened the app. Read receipts stay with the app.
     */
    val sendDeliveryReceipts: Boolean = true,
) {
    init {
        require(keepAliveInterval.isPositive() && keepAliveInterval <= 30.seconds) { "PROTOCOL.md §4.7: ping at least every 30 s" }
        require(setupRetryMinDelay.isPositive() && setupRetryMinDelay <= setupRetryMaxDelay) { "invalid setup retry backoff" }
    }
}

/** A decrypted message handed to the app. */
class IncomingMessage(
    /** Relay envelope id; the facade acknowledges it after the handler returns. */
    val envelopeId: String,
    val serverTimestamp: Long,
    val sender: AccountId,
    /** The sender's verified username if it came with this message, else the last one we knew. */
    val senderUsername: String?,
    val content: Content,
    /** The conversation state after this message, e.g. [ContactState.INCOMING_REQUEST] for a new request. */
    val contactState: ContactState,
    /** The sender's safety number changed and the user has not accepted it yet (PROTOCOL.md §5). */
    val identityChanged: Boolean,
)

/** Receives decrypted messages. The envelope is acknowledged only after this returns normally. */
fun interface MessageHandler {
    suspend fun onMessage(message: IncomingMessage)
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,

    /** Connected; the stored backlog is still arriving. */
    CONNECTED,

    /** Connected and the backlog is delivered (`queue_empty`); new messages arrive live. */
    UP_TO_DATE,
}

/** An attachment to encrypt, upload and send. */
class OutgoingAttachment(
    val kind: Attachment.Kind,
    val contentType: String,
    /** The plaintext; opened once. Strip EXIF/GPS metadata before handing images or videos over (§7). */
    val source: BlobSource,
    val fileName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Int = 0,
    val caption: String = "",
    val thumbnail: ByteArray? = null,
    val waveform: ByteArray? = null,
)

/** Sending is blocked: the contact's safety number changed and the user has not accepted it (§5). */
class IdentityChangedException(val accountId: AccountId) : Exception("identity of $accountId changed; user must accept it")

/** The backup file decrypted, but its contents are malformed or of an unknown version (§5.5). */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Ephemeral sends need the contact's access key; the relay refuses proof-of-work for them (§4.6). */
class AccessKeyRequiredException(val accountId: AccountId) : Exception("no access key for $accountId; ephemeral sends need one")

/** Sending is not allowed in the current [state]: blocked, or an incoming request not accepted yet (§6.4). */
class SendNotAllowedException(val accountId: AccountId, val state: ContactState) :
    Exception("cannot send to $accountId in state $state")

/** The relay rejected this connection for good: another connection replaced it, or the token is invalid. */
class ConnectionRejectedException(val code: Int?, val httpStatus: Int?) :
    Exception("relay rejected the connection (close code $code, HTTP $httpStatus)")
