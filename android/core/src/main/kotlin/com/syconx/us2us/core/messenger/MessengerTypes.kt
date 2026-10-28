package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.attachment.BlobSource
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.Sticker
import com.syconx.us2us.core.relay.RelaySocket
import com.syconx.us2us.core.sticker.StickerCatalog
import com.syconx.us2us.core.sticker.StickerLanguage
import com.syconx.us2us.core.sticker.StickerResolution
import com.syconx.us2us.core.sticker.StickerSelection
import com.syconx.us2us.core.username.Username
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
    /** The user's profile picture (PROTOCOL.md §8.9); `null` is the initial letter. */
    val profilePicture: ProfilePicture? = null,
    /**
     * "Aramada göster" / "Show in search" (PROTOCOL.md §8.10). Registration explicitly opts out;
     * this raw initializer retains the legacy default for existing-account adapters. Persisted
     * and restored preferences are always explicit. Off: only contacts receive the picture.
     */
    val showPictureInSearch: Boolean = true,
    /** The last avatar record version this account wrote with (§8.10); the next write uses this + 1. */
    val avatarVersion: Long = 0,
    /** Relay steps of registration or restore that are still due; see [Messenger.performMaintenance]. */
    val pendingSetup: Set<PendingSetup> = emptySet(),
    /**
     * The token [authToken] is being rotated to (PROTOCOL.md §4.3, §5.5 step 3): stored before the
     * rotation is sent and kept until its outcome is known, so a lost answer never leaves the account
     * without a token that works. While it is set, neither token is used for anything but finding
     * out the outcome ([Messenger.finishPendingTokenRotation]). Persist it with the account.
     */
    val pendingAuthToken: AuthToken? = null,
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

    /**
     * Bring the avatar record on the relay in line with the picture, "Show in search" and the
     * username (PROTOCOL.md §8.10, "Publishing"): set by every change of them and by a restore, and
     * cleared once the relay has accepted the write that matches them.
     */
    SYNC_AVATAR,
}

/** Whether the account's token can be used after [Messenger.finishPendingTokenRotation]. */
enum class TokenRotationResult {
    /**
     * No rotation was pending, or it has just been settled: the account's token is the one the
     * relay holds, and the backup's token (the old phone's) no longer works.
     */
    READY,

    /**
     * `401` for the backup's token and for the new one: another phone restored the same backup and
     * rotated first, or the account was deleted. The relay refuses this installation from now on;
     * every later call in this process says so again (§5.5 step 3.5).
     */
    BACKUP_NO_LONGER_VALID,
}

/** Where the account's avatar record for search stands (PROTOCOL.md §8.10, "Publishing"). */
enum class AvatarRecordStatus {
    /** The relay holds what the picture, "Show in search" and the username ask for: the record, or none. */
    IN_SYNC,

    /**
     * A write is still owed: it got no answer, `429` or `5xx`, or a restore's keys or token
     * rotation are not settled yet. It is made again at the next connection or the next change.
     */
    PENDING,

    /**
     * The relay has no avatar records (`404` or `405`): tried again after the next app start.
     * Contacts still get the picture end to end.
     */
    UNAVAILABLE,
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
    /** How long one proof-of-work may run before it is given up (and the send retried later). */
    val powTimeout: Duration = 3.minutes,
) {
    init {
        require(powTimeout.isPositive()) { "invalid proof-of-work timeout" }
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
    /**
     * The `sender_username` that came with this very message, checked against the directory
     * (§6.3); `null` when the message carried none. Unlike [senderUsername] it is never a name
     * known from before, so the §6.4 rules that hinge on "the message carried `sender_username`"
     * read it (audit 2026-10-28 M-9).
     */
    val verifiedSenderUsername: Username? = null,
)

/**
 * Receives decrypted messages. The envelope is acknowledged only after this returns normally.
 * Throw an [java.io.IOException] when the local store is unavailable (the message waits for the
 * next connection); any other exception counts as a failed attempt for that envelope, which is
 * dropped and acknowledged after [Messenger.MAX_HANDLER_ATTEMPTS] of them.
 */
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

/**
 * A built-in sticker to send (PROTOCOL.md §8.8): [packId] and [stickerId] name a sticker of the
 * catalog, and [emoji] is always set, the fallback of a reader that does not know it. [lang] is the
 * language its words were shown in ("tr", "en") and stays empty for a wordless sticker, so the peer
 * learns nothing about the sender's language from it. [of] builds one from the picker's choice.
 */
data class OutgoingSticker(
    val packId: String,
    /** `sticker_id`, a uint32 on the wire. */
    val stickerId: Int,
    val emoji: String,
    /** A lowercase BCP-47 primary language subtag, or empty. */
    val lang: String = "",
) {
    init {
        require(packId.isNotBlank()) { "a built-in sticker needs its pack id" }
        require(packId != StickerCatalog.LEGACY_TEXT_PACK_ID) { "${StickerCatalog.LEGACY_TEXT_PACK_ID} stickers are received, never sent (§8.8)" }
        require(emoji.isNotBlank()) { "a sticker always carries its emoji (§8.8)" }
        require(lang.isEmpty() || LANGUAGE_SUBTAG.matches(lang)) { "lang must be a lowercase primary language subtag such as \"tr\"" }
        require(lang.isEmpty() || StickerCatalog.sticker(packId, stickerId)?.isText != false) { "a wordless sticker carries no language (§8.8)" }
    }

    fun toProto(): Sticker = Sticker.newBuilder()
        .setPackId(packId)
        .setStickerId(stickerId)
        .setEmoji(emoji)
        .setLang(lang)
        .build()

    companion object {
        private val LANGUAGE_SUBTAG = Regex("[a-z]{2,8}")

        /** The sticker as chosen: its language goes along only when it has words. */
        fun of(selection: StickerSelection) =
            OutgoingSticker(selection.sticker.packId, selection.sticker.id, selection.sticker.emoji, selection.wireLanguage)
    }
}

/**
 * A received or stored `Sticker`, decoded (PROTOCOL.md §8.8). [lang] is kept as it arrived: empty
 * from older senders and for wordless stickers, and possibly in another case, with a region, or
 * unknown. [resolve] turns it into what to draw.
 */
data class StickerInfo(
    val packId: String,
    /** `sticker_id`, a uint32 on the wire. */
    val stickerId: Int,
    val emoji: String,
    val lang: String = "",
    /** A custom sticker's `STICKER_IMAGE` attachment (§7); `null` for a built-in one. */
    val custom: Attachment? = null,
) {
    /**
     * What to draw for a built-in sticker, [appLanguage] being the reader's: its words in [lang]
     * when that is Turkish or English, else in [appLanguage]. A custom sticker is drawn from [custom].
     */
    fun resolve(appLanguage: StickerLanguage): StickerResolution = StickerCatalog.resolve(packId, stickerId, emoji, lang, appLanguage)

    companion object {
        fun from(sticker: Sticker) = StickerInfo(
            packId = sticker.packId,
            stickerId = sticker.stickerId,
            emoji = sticker.emoji,
            lang = sticker.lang,
            custom = sticker.custom.takeIf { sticker.hasCustom() },
        )
    }
}

/** Sending is blocked: the contact's safety number changed and the user has not accepted it (§5). */
class IdentityChangedException(val accountId: AccountId) : Exception("identity of $accountId changed; user must accept it")

/** The backup file decrypted, but its contents are malformed or of an unknown version (§5.5). */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The relay refuses the backup's token and the new one (`401` for both, §5.5 step 3.4): another
 * phone restored the same backup and rotated first, or the account was deleted. The backup can never
 * be restored again, and this installation is signed out.
 */
class BackupNoLongerValidException : Exception("the relay refuses the backup's token: restored elsewhere, or deleted")

/**
 * The relay refused the new token (`400`, §5.5 step 3.5): nothing changed, so the backup's token,
 * which the old phone also holds, is still the account's. A correct client never gets this. After a
 * restore's first attempt the restore fails and the backup still works; while settling a pending
 * rotation a fresh token is pending in its place, and the next attempt rotates to it.
 */
class TokenRefusedException : Exception("the relay refused the new auth token; the old one is still the account's")

/**
 * A token rotation's outcome is unknown, so nothing may use either token (§5.5 step 3.5): the
 * request was not sent. [Messenger.finishPendingTokenRotation] settles it; the receive loop asks
 * before every connection attempt.
 */
class TokenRotationPendingException : IOException("the auth token rotation is not settled yet; nothing was sent")

/** Ephemeral sends need the contact's access key; the relay refuses proof-of-work for them (§4.6). */
class AccessKeyRequiredException(val accountId: AccountId) : Exception("no access key for $accountId; ephemeral sends need one")

/** Sending is not allowed in the current [state]: blocked, or an incoming request not accepted yet (§6.4). */
class SendNotAllowedException(val accountId: AccountId, val state: ContactState) :
    Exception("cannot send to $accountId in state $state")

/** The relay rejected this connection for good: another connection replaced it, or the token is invalid. */
class ConnectionRejectedException(val code: Int?, val httpStatus: Int?) :
    Exception("relay rejected the connection (close code $code, HTTP $httpStatus)")
