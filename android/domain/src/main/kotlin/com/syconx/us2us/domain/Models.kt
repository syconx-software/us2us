package com.syconx.us2us.domain

import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.RequestId
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

// App-level models the screens render. They are deliberately UI-shaped (what the design shows),
// independent of the protocol's wire types; the live MessengerService maps the core's state into
// them. Every value is immutable: a change is a new value (`copy`).

// MARK: - People

/**
 * C12 avatar palettes (fill / letter): A `accent800`/`accent100`, B `accent700`/`accent100`,
 * C `neutral800`/`neutral100`, D (self, outlined) `accent900`/`accent300` + 1 pt `accent700` ring.
 */
enum class AvatarPalette(val raw: String) {
    A("a"),
    B("b"),
    C("c"),
    D("d"),
    ;

    companion object {
        fun fromRaw(raw: String): AvatarPalette? = entries.firstOrNull { it.raw == raw }

        /**
         * Deterministic from the username (FNV-1a over its UTF-8 bytes, stable across launches and
         * devices). The user's own avatar is always [D]; unknown senders (requests) always [C];
         * callers decide those cases.
         */
        fun forUsername(username: String): AvatarPalette {
            var hash = 0x811C9DC5u
            for (byte in username.encodeToByteArray()) {
                hash = hash xor (byte.toUByte().toUInt())
                hash *= 0x01000193u
            }
            return entries[(hash % entries.size.toUInt()).toInt()]
        }
    }
}

/** Activity shown in the chat header (only for accepted contacts that share it). */
sealed interface Presence {
    /** Not shared, or not an accepted contact: no status line. */
    data object Hidden : Presence

    /** “Aktif” with a 7 pt online dot. */
    data object Online : Presence

    /** “Az önce aktifti”. */
    data object JustNow : Presence

    /** “{n} dk önce aktifti” / “Bugün {HH:mm} aktifti” (proposed). */
    data class LastSeen(val at: Instant) : Presence
}

data class Peer(
    /** ASCII lower-case, exact (design 2.1). */
    val username: String,
    val palette: AvatarPalette,
    val isVerified: Boolean,
    /** Presence and the online dot exist only for accepted contacts (4.1 note). */
    val isAcceptedContact: Boolean,
    val presence: Presence,
    /**
     * The peer's safety number changed and the change waits for the user (compare, or continue
     * without verifying): sending is held (5.1 banner).
     */
    val safetyNumberChanged: Boolean = false,
    /**
     * Fingerprint of the peer's current identity key, `null` while none is known (no safety number
     * to compare yet). Changes with the key, so 6.1 reloads when it does.
     */
    val identityFingerprint: String? = null,
    /**
     * The profile picture to draw (PROTOCOL §8.9): an accepted contact's last `ProfileUpdate`, the
     * search copy for the user's own message request, otherwise (incoming requests, blocked) the
     * initial.
     */
    val avatar: AvatarPicture = AvatarPicture.Initials,
    /**
     * The user blocked this person (PROTOCOL §6.4): what they send is dropped on this phone, nothing
     * can be sent to them, and the chat stays, read only, until “Engeli kaldır”. They are not told.
     */
    val isBlocked: Boolean = false,
) {
    val id: String get() = username

    val isOnline: Boolean get() = isAcceptedContact && presence == Presence.Online

    /**
     * 5.1 first-contact prompt: a safety number exists but was never verified, and no change waits
     * for a decision (the banner covers that).
     */
    val offersVerification: Boolean get() = !isVerified && !safetyNumberChanged && identityFingerprint != null
}

data class OwnAccount(val username: String) {
    /** Always [AvatarPalette.D] (C12). */
    val palette: AvatarPalette get() = AvatarPalette.D
}

// MARK: - Connection

/** The line at the top of the chat list (4.1). */
enum class ConnectionState {
    /** “Hat açık · mesajlar geliyor”: packets flow. */
    Open,

    /** “Hat kuruluyor…” (proposed). */
    Connecting,

    /** “Hat kapalı · yeniden deneniyor” (proposed): line static. */
    Closed,

    /**
     * “Hat kapandı · oturum sona erdi”: the server refused this phone's token (the backup was
     * restored on another phone, or the account was deleted); nothing is retried.
     */
    Revoked,
}

// MARK: - Messages

enum class MessageDirection { Incoming, Outgoing }

/** C16: the four delivery states (plus the local “sending”). */
enum class MessageStatus {
    /** Still on this device. */
    Sending,

    /** Gönderildi: left the device; the relay holds the sealed envelope. */
    Sent,

    /** İletildi: the recipient opened the app and fetched it. */
    Delivered,

    /** Okundu: the recipient opened the chat (read receipts on both sides). */
    Read,

    /** İletilemedi: not fetched within 14 days; deleted from the relay. */
    Failed,
}

/** Ghost mode periods (5.5): the chat is erased if it stays silent this long. */
enum class GhostPeriod(val raw: String, val duration: Duration) {
    OneHour("oneHour", 1.hours),
    SixHours("sixHours", 6.hours),
    OneDay("oneDay", 24.hours),
    ThreeDays("threeDays", 3.days),
    SevenDays("sevenDays", 7.days),
    ;

    val hours: Int get() = duration.inWholeHours.toInt()

    companion object {
        fun fromRaw(raw: String): GhostPeriod? = entries.firstOrNull { it.raw == raw }
    }
}

/** Disappearing-message timers (5.5): counted from when the recipient reads it. */
enum class DisappearingTimer(val raw: String, val duration: Duration?) {
    Off("off", null),
    ThirtySeconds("thirtySeconds", 30.seconds),
    FiveMinutes("fiveMinutes", 5.minutes),
    OneHour("oneHour", 1.hours),
    OneDay("oneDay", 1.days),
    ;

    companion object {
        fun fromRaw(raw: String): DisappearingTimer? = entries.firstOrNull { it.raw == raw }
    }
}

/** A chat's Ghost state (C17). */
data class GhostState(
    val isEnabled: Boolean,
    val period: GhostPeriod,
    /** When the chat is erased unless a message arrives; `null` when off. */
    val deadline: Instant?,
) {
    /** What is left at [now]; `null` when Ghost is off. Never negative. */
    fun remaining(now: Instant): Duration? {
        if (!isEnabled || deadline == null) return null
        val left = java.time.Duration.between(now, deadline).toKotlinDuration()
        return if (left.isNegative()) Duration.ZERO else left
    }

    /** Red in its last hour. */
    fun isUrgent(now: Instant): Boolean = remaining(now)?.let(::isUrgentRemaining) ?: false

    companion object {
        val Off = GhostState(isEnabled = false, period = GhostPeriod.OneDay, deadline = null)

        /** The Ghost pill turns red below this (`U2Format.ghostIsUrgent`). */
        val UrgentBelow: Duration = 1.hours

        fun isUrgentRemaining(remaining: Duration): Boolean = remaining < UrgentBelow
    }
}

/** The placeholder images the design paints with CSS gradients. */
enum class DesignArtwork(val raw: String) {
    /** 5.1 photo “Şu yeni yer, Moda'da”: a night pier with lights. */
    NightPier("nightPier"),

    /** 5.2 video poster: warm sunset glow. */
    SunsetVideo("sunsetVideo"),
}

/** Where media comes from. Fixtures use the design's own painted stand-ins. */
sealed interface MediaSource {
    data class Artwork(val artwork: DesignArtwork) : MediaSource

    data class File(val uri: URI) : MediaSource

    /**
     * Decrypted image bytes held in memory only (the live service: an inline thumbnail, then the
     * full image), never written to disk to be shown.
     */
    data class Memory(val data: Bytes) : MediaSource
}

/** Where the file of a photo, video or file message is (5.2 › States): what its bubble shows and what a tap does. */
sealed interface MediaAvailability {
    /** On this device (received and decrypted, or sent from it): opens at once. */
    data object Ready : MediaAvailability

    /** Still on the relay: a tap downloads it. */
    data object Remote : MediaAvailability

    /** On its way; the fraction (0…1) when it is known. */
    data class Downloading(val fraction: Double?) : MediaAvailability

    /** From a message request: downloaded once the request is accepted (§6.4). */
    data object Held : MediaAvailability

    /** Expired on the relay (14 days) or failed its integrity check. */
    data object Unavailable : MediaAvailability

    /**
     * A photo or video picked in “+”, still being prepared on this phone (re-encoded at ≤ 2048 px /
     * ≤ 720p without metadata, one item at a time) before it is sent: “Hazırlanıyor”.
     */
    data object Preparing : MediaAvailability

    /** Preparing that photo or video failed: nothing left the phone; “Tekrar gönder” prepares it again. */
    data object PreparationFailed : MediaAvailability

    val isReady: Boolean get() = this == Ready

    /** The photo or video exists only on this phone so far (preparing, or failed to). */
    val isPending: Boolean get() = this == Preparing || this == PreparationFailed

    val isDownloading: Boolean get() = this is Downloading
}

/** The pixel size an attachment was sent with (0 when the sender did not say). */
data class MediaDimensions(val width: Int, val height: Int) {
    val aspectRatio: Float?
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
}

/** The decrypted bytes of a photo, video or file message, held in memory only (players, viewers and previews take them from here). */
data class MediaPayload(
    val data: Bytes,
    /** MIME type as sent (`image/jpeg`, `video/mp4`, `application/pdf` …). */
    val contentType: String,
    /** The name as sent (files), sender-controlled: sanitise before use ([FileNameSanitizer]). */
    val fileName: String?,
)

data class QuotedMessage(
    val author: String,
    val kind: Kind,
    val thumbnail: MediaSource? = null,
    /** The quoted message (the live service sends its id inside the reply). */
    val messageId: MessageId? = null,
) {
    sealed interface Kind {
        data class Text(val text: String) : Kind

        data object Photo : Kind

        data object Video : Kind

        data object Voice : Kind

        data class File(val name: String) : Kind
    }
}

data class Reaction(
    val emoji: String,
    val count: Int = 1,
    val includesMe: Boolean = false,
)

enum class ViewOnceState(val raw: String) {
    Unopened("unopened"),
    Opened("opened"),
    Expired("expired"),
}

sealed interface SystemEvent {
    data class GhostEnabled(val period: GhostPeriod) : SystemEvent

    data object GhostDisabled : SystemEvent

    data class DisappearingChanged(val timer: DisappearingTimer) : SystemEvent

    data class ScreenshotTaken(val by: String) : SystemEvent

    data object SafetyNumberChanged : SystemEvent

    /** A changed safety number was accepted without verifying it (no seal). */
    data object SafetyNumberAccepted : SystemEvent

    data object SafetyNumberVerified : SystemEvent
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent

    data class Photo(val source: MediaSource, val caption: String?) : MessageContent

    data class Video(val source: MediaSource, val duration: Duration, val byteCount: Long) : MessageContent

    /** Bar heights 0…1 (the design's waveform) and how much is played. */
    data class Voice(val duration: Duration, val waveform: List<Double>, val playedFraction: Double) : MessageContent

    data class File(val name: String, val byteCount: Long, val typeLabel: String, val pageCount: Int?) : MessageContent

    data class Location(val name: String, val locality: String, val latitude: Double, val longitude: Double) : MessageContent

    data class LiveLocation(val expiresAt: Instant, val isActive: Boolean) : MessageContent

    /**
     * A Doto text sticker (“tamam!”): the plain text stickers of builds before the built-in
     * catalogue (`pack_id` “us2us.text”), kept for history.
     */
    data class TextSticker(val text: String) : MessageContent

    /**
     * A built-in sticker (PROTOCOL §8.8) exactly as it travelled: its pack and id, its `emoji` and
     * the language its words were shown in on the sender's side (“tr”, “en”; empty for a wordless
     * sticker or an older sender). The bubble resolves it against
     * [com.syconx.us2us.domain.sticker.StickerCatalog]; an unknown sticker falls back to its emoji.
     */
    data class Sticker(val packId: String, val stickerId: Int, val emoji: String, val lang: String) : MessageContent

    data class ViewOnce(val isVideo: Boolean, val state: ViewOnceState) : MessageContent

    /** “Bu mesaj herkesten silindi”. */
    data object Deleted : MessageContent

    data class System(val event: SystemEvent) : MessageContent
}

data class Message(
    val id: MessageId,
    val direction: MessageDirection,
    val sentAt: Instant,
    val content: MessageContent,
    /** Outgoing only. */
    val status: MessageStatus? = null,
    val replyTo: QuotedMessage? = null,
    val reactions: List<Reaction> = emptyList(),
    /** Set for disappearing messages (copy is disabled for them, 5.3). */
    val disappearing: DisappearingTimer = DisappearingTimer.Off,
    /** Status history for the info sheet (5.4). */
    val deliveredAt: Instant? = null,
    val readAt: Instant? = null,
    val failedAt: Instant? = null,
    /** Photo, video and file messages: where the file is (5.2 › States). */
    val mediaAvailability: MediaAvailability = MediaAvailability.Ready,
    /** Photo and video messages: the size they were sent with, if known. */
    val mediaSize: MediaDimensions? = null,
    /**
     * Voice messages: incoming, played on this phone; outgoing, the other side played it (their
     * VIEWED receipt, shown while read receipts are on).
     */
    val isVoicePlayed: Boolean = false,
    /**
     * Photo, video and file messages I sent: the other side saved it to their phone (their SAVED
     * receipt, PROTOCOL §8.4), the “Kaydetti” chip. Always told, independent of read receipts.
     */
    val savedByPeer: Boolean = false,
    /** When their SAVED arrived (5.4 › “Kaydedildi”), if known. */
    val savedByPeerAt: Instant? = null,
    /** Photo, video and file messages: I saved it or handed it on from one of the app's viewers (the viewer's “Kaydet” shows the check). */
    val savedByMe: Boolean = false,
) {
    val isOutgoing: Boolean get() = direction == MessageDirection.Outgoing

    val isDisappearing: Boolean get() = disappearing != DisappearingTimer.Off

    /**
     * A picked photo or video still being prepared, or whose preparation failed: it is not a
     * message yet (no reactions, replies or info; it can only be sent again or removed).
     */
    val isPendingMedia: Boolean get() = mediaAvailability.isPending

    /** System lines are not bubbles: no actions, no tail, no status. */
    val isSystem: Boolean get() = content is MessageContent.System

    val isDeleted: Boolean get() = content == MessageContent.Deleted

    /** A key that changes when the bubble kind changes (text → tombstone …), so the row cross-fades between the two instead of morphing. */
    val contentKind: String
        get() = when (val content = content) {
            is MessageContent.Text -> "text"
            is MessageContent.Photo -> "photo"
            is MessageContent.Video -> "video"
            is MessageContent.Voice -> "voice"
            is MessageContent.File -> "file"
            is MessageContent.Location -> "location"
            is MessageContent.LiveLocation -> if (content.isActive) "live" else "liveEnded"
            is MessageContent.TextSticker, is MessageContent.Sticker -> "sticker"
            is MessageContent.ViewOnce -> "viewOnce.${content.state.raw}"
            MessageContent.Deleted -> "deleted"
            is MessageContent.System -> "system"
        }

    /** The plain text a message carries (text, photo caption), for copying. */
    val copyableText: String?
        get() = when (val content = content) {
            is MessageContent.Text -> content.text
            is MessageContent.Photo -> content.caption
            else -> null
        }
}

/** The contact screen's media tabs (6.1 › Fotoğraflar / Belgeler). */
enum class ChatMediaKind {
    /** Photos and videos. */
    Visual,

    /** Files. */
    Documents,
}

// MARK: - Chat list

/** What a chat-list row previews (4.1 line 2). */
sealed interface MessagePreviewKind {
    data class Text(val text: String) : MessagePreviewKind

    data object Disappearing : MessagePreviewKind

    data object Sticker : MessagePreviewKind

    data object Photo : MessagePreviewKind

    data object PhotoViewOnce : MessagePreviewKind

    data object Video : MessagePreviewKind

    data object Voice : MessagePreviewKind

    data class File(val name: String) : MessagePreviewKind

    data object Location : MessagePreviewKind

    data object LiveLocation : MessagePreviewKind

    data object LiveLocationEnded : MessagePreviewKind

    data object Deleted : MessagePreviewKind
}

data class MessagePreview(
    val kind: MessagePreviewKind,
    val direction: MessageDirection,
    val status: MessageStatus?,
    val sentAt: Instant,
)

data class ChatSummary(
    val id: ChatId,
    val peer: Peer,
    val lastMessage: MessagePreview?,
    val unreadCount: Int,
    val ghost: GhostState,
    val disappearing: DisappearingTimer,
)

// MARK: - Requests (4.3, 4.4)

data class MessageRequest(
    val id: RequestId,
    val sender: String,
    val receivedAt: Instant,
    /** The first message's text, or a media placeholder. */
    val preview: MessagePreviewKind,
    val flags: Set<Flag>,
    /** The request thread, decrypted locally (4.4). */
    val messages: List<Message>,
    /**
     * What 4.3 and 4.4 draw: always the initial. A stranger's picture is neither fetched nor
     * decoded before the user asks for it on the opened request (“Profil resmini göster”) or
     * accepts (PROTOCOL §8.9).
     */
    val avatar: AvatarPicture = AvatarPicture.Initials,
    /** 4.4 may offer “Profil resmini göster”: the sender's username is verified, so its search copy can be looked up under it, once, on a tap. */
    val offersAvatar: Boolean = false,
) {
    enum class Flag { HasMedia, HasLink, Suspicious }
}

/**
 * Someone the user blocked, as 7.1 › Engellenenler lists them: also when their chat is gone (Ghost
 * erased it, or it was deleted): the block stays.
 */
data class BlockedPerson(
    /** The person's chat id (the account in the live service), also when there is no chat: what `unblock(person)` takes. */
    val id: ChatId,
    /** What the list shows: the username, else the name 4.1 would show. */
    val name: String,
    /** `false` when this phone keeps no username for the person (a Ghost wipe with `wipe_contact`, PROTOCOL §8.2, kept only the block). */
    val isUsernameKnown: Boolean = true,
    val palette: AvatarPalette,
    /** Always the initial: no picture is shown for someone blocked (§8.9). */
    val avatar: AvatarPicture = AvatarPicture.Initials,
) {
    companion object {
        /** The list's order: by username (as Finder sorts: case-insensitive, numbers by value), people without one last. */
        fun listed(people: List<BlockedPerson>): List<BlockedPerson> =
            people.sortedWith(compareBy<BlockedPerson> { !it.isUsernameKnown }.then { a, b -> StandardNameOrder.compare(a.name, b.name) })
    }
}

/** Swift's `localizedStandardCompare`: case-insensitive, accents count, runs of digits compare by value. */
internal object StandardNameOrder : Comparator<String> {
    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.ROOT).apply {
        strength = java.text.Collator.SECONDARY
    }

    override fun compare(a: String, b: String): Int {
        val left = chunks(a)
        val right = chunks(b)
        for (index in 0 until minOf(left.size, right.size)) {
            val x = left[index]
            val y = right[index]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val byValue = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                if (byValue != 0) byValue else x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                synchronized(collator) { collator.compare(x, y) }
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    private fun chunks(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (index in 1..text.length) {
            if (index == text.length || text[index].isDigit() != text[index - 1].isDigit()) {
                result += text.substring(start, index)
                start = index
            }
        }
        return result
    }
}

// MARK: - Lookup (4.2)

sealed interface UserLookup {
    data object Idle : UserLookup

    data object Searching : UserLookup

    data class Found(val peer: Peer, val isExistingChat: Boolean) : UserLookup

    data object NotFound : UserLookup
}

/** 2.1 availability while typing. */
sealed interface UsernameAvailability {
    data object Empty : UsernameAvailability

    data object Checking : UsernameAvailability

    /** Valid, and known to be free. */
    data object Available : UsernameAvailability

    /** Valid; whether it is free is known only when setup registers it (the directory answers only accounts, PROTOCOL §4.4). */
    data object MeetsRules : UsernameAvailability

    data object Taken : UsernameAvailability

    data class Invalid(val violation: UsernameRules.Violation) : UsernameAvailability
}

// MARK: - Verification (06)

enum class VerificationMethod(val raw: String) {
    /** “yüz yüze”: QR scanned side by side. */
    FaceToFace("faceToFace"),

    /** “elle işaretlendi”. */
    Manual("manual"),
}

data class SafetyNumberInfo(
    /** 12 groups of 5 digits (60 digits), identical on both sides. */
    val groups: List<String>,
    /** The scannable fingerprint payload (QR content, see [SafetyQRCodec]). */
    val scannablePayload: Bytes,
    val verifiedAt: Instant?,
    val method: VerificationMethod?,
    /**
     * When the peer's number last changed (new device, reinstall, restore) and the change has not
     * been verified since: 6.1 shows the danger banner and the seal is gone (flows.md › 4.6).
     */
    val changedAt: Instant? = null,
    /**
     * Verified, but when and how is not known (restored from a backup, or verified by an earlier
     * version): no date is shown, never a made-up one.
     */
    val isVerifiedWithoutRecord: Boolean = false,
) {
    val isVerified: Boolean get() = verifiedAt != null || isVerifiedWithoutRecord

    val hasUnverifiedChange: Boolean get() = changedAt != null && !isVerified
}

/** Result of comparing a scanned QR code with a chat's safety number (6.2). */
enum class SafetyCodeComparison {
    /** Both sides see the same safety number → 6.3. */
    Match,

    /** A us2us safety-number code, but not this chat's → “Numaralar uyuşmuyor”. */
    Mismatch,

    /** Not a us2us safety-number code at all (a URL, a Wi-Fi code …): ignored. */
    NotASafetyCode,
}

/** What a QR text read while adding a contact (4.1 / 4.2 → 6.2) stands for. */
sealed interface ContactCodeResolution {
    /** Someone's us2us contact code. */
    data class Contact(val username: String) : ContactCodeResolution

    /** The user's own code. */
    data object OwnCode : ContactCodeResolution

    /** A us2us contact code, but nobody with that name. */
    data object Unknown : ContactCodeResolution

    /** The name could not be looked up (no connection, the server busy): not “nobody found”; scanning again later can work. */
    data object LookupFailed : ContactCodeResolution

    /**
     * The code's key fingerprint is not the key the server serves for that name, or this phone
     * holds another key for them (“Numaralar uyuşmuyor”): nothing is added or verified.
     */
    data object KeyMismatch : ContactCodeResolution

    /** Not a us2us contact code (a URL, a safety-number code …): ignored. */
    data object NotAContactCode : ContactCodeResolution
}

/** What “QR kodla ekle” did with a scanned contact code. */
sealed interface ContactCodeAddition {
    /** Added (or found) and verified face to face: the code's key fingerprint matched and that key is pinned → 6.3. */
    data class Verified(val chat: ChatId) : ContactCodeAddition

    /** Added (or found) but not verified: the code carried no key fingerprint (older app versions) → the chat opens unverified. */
    data class Added(val chat: ChatId) : ContactCodeAddition

    /** The code's key is not the one the relay serves, or this phone holds another key for the contact: nothing verified. */
    data object Mismatch : ContactCodeAddition

    /** Nobody could be added (no network, not found). */
    data object Failed : ContactCodeAddition
}

// MARK: - Settings (07)

enum class PresenceVisibility(val raw: String) {
    /** “Kabul ettiklerim” (default). */
    AcceptedContacts("acceptedContacts"),

    /** “Kimse”: and then you don't see anyone else's either. */
    Nobody("nobody"),
    ;

    companion object {
        fun fromRaw(raw: String): PresenceVisibility? = entries.firstOrNull { it.raw == raw }
    }
}

/** 7.1 › Görünüm: a device preference (readable before unlocking), stored as [raw]. */
enum class ThemePreference(val raw: String) {
    System("system"),
    Dark("dark"),
    Light("light"),
    ;

    companion object {
        fun fromRaw(raw: String): ThemePreference? = entries.firstOrNull { it.raw == raw }
    }
}

/** Settings kept in the vault (7.1). Theme and language are device preferences, not part of these. */
data class AppSettings(
    val presenceVisibility: PresenceVisibility = PresenceVisibility.AcceptedContacts,
    val readReceipts: Boolean = true,
    val faceIdEnabled: Boolean = true,
    val defaultGhost: GhostPeriod? = GhostPeriod.OneDay,
    val defaultDisappearing: DisappearingTimer = DisappearingTimer.Off,
    val lastBackupAt: Instant? = null,
)

// MARK: - Attachments (5.1 “+”)

/** What “+” can send. Stickers go through [MessengerService.sendSticker]. */
sealed interface ConversationAttachment {
    data class Photo(val source: MediaSource, val caption: String?) : ConversationAttachment

    data class File(val name: String, val byteCount: Long, val typeLabel: String, val pageCount: Int?) : ConversationAttachment

    data class Location(val name: String, val locality: String, val latitude: Double, val longitude: Double) : ConversationAttachment

    data class LiveLocation(val duration: Duration) : ConversationAttachment

    /** A file picked in the document picker (live service; a copy in the app's storage). */
    data class Document(val uri: URI) : ConversationAttachment

    /**
     * A video picked in the photo picker (live service; a copy in the app's storage, re-exported at
     * ≤ 720p without metadata before it is sent). A pick of several photos and videos goes through
     * [MessengerService.sendPickedMedia] instead.
     */
    data class Video(val uri: URI) : ConversationAttachment
}

// MARK: - Backup (7.2, 7.3)

/**
 * What a backup should hold. The account backup (PROTOCOL.md §5.5) is always the account, its
 * identity key and the contacts, never messages or media, so 7.2 offers no choice and asks for
 * settings and contacts only. The message switches are kept for a later format that carries history.
 */
data class BackupContents(
    val messages: Boolean = true,
    /** Photos and videos of the included chats: it only takes effect together with [messages] (see [includesMedia]). */
    val media: Boolean = true,
    val settingsAndContacts: Boolean = true,
) {
    /** Whether media is actually written: never without the messages. */
    val includesMedia: Boolean get() = messages && media

    /** Nothing would be written (media alone is never exported). */
    val isEmpty: Boolean get() = !messages && !settingsAndContacts

    companion object {
        /** The backup file's extension (`.u2u`). */
        const val FILE_EXTENSION = "u2u"
    }
}

/** A backup file the user picked (7.3 file card). */
data class BackupFileInfo(
    /** A copy inside the app's private storage (never the picked original). */
    val uri: URI,
    val fileName: String,
    val byteCount: Long,
    val createdAt: Instant?,
)

/** Why a picked file can't be restored. */
enum class BackupFileError {
    /** The file could not be read (permissions, a cloud placeholder, I/O). */
    Unreadable,

    /** Not a us2us backup (wrong type or a damaged header). */
    NotABackup,

    /** It opened with its password, but its contents are damaged or malformed: no restore accepts it. */
    Damaged,

    /** The server refuses its token: its account was deleted, or it was restored on another phone since. */
    NoLongerValid,
}

/** The backup password check (7.3). */
enum class BackupOpenResult {
    /** The password opened it, every field is valid and the relay still accepts its token: restoring can go ahead. */
    Opened,
    WrongPassword,

    /** The file decrypts but is damaged or malformed: no restore accepts it. */
    Damaged,

    /** The relay refuses the backup's token: its account was deleted, or it was restored on another phone since. */
    NoLongerValid,

    /** The relay could not be asked (no connection, relay busy): nothing was changed, try again. */
    Unverified,
}

/** Finishing a restore (2.3) failed in a way trying again cannot fix. */
enum class RestoreFailure {
    /** The relay refuses the backup's token: its account was deleted, or the backup was restored on another phone since. */
    BackupNoLongerValid,

    /** The file can't be read, or it decrypts but is damaged or malformed. */
    BackupDamaged,
}

/** Creating the account (2.3) was refused before anything was kept. */
enum class AccountCreationFailure {
    /** Someone registered the name first (the relay's 409 `username_taken`): another name can be chosen and setup finished with the same code. */
    UsernameTaken,
}

/**
 * 2.3 could not finish, yet a vault is on this phone, locked: onboarding cannot go on (trying again
 * would find that vault and fail), the lock opens it.
 */
enum class SetupInterruption {
    /**
     * The new account, created or restored here, was saved before the failure: it is kept under
     * the new code, and its first unlock finishes what is missing (PROTOCOL §5.5).
     */
    AccountKept,

    /**
     * Restoring inside the lock (3.3 › 7.3) was refused before anything was erased (the backup is
     * no longer valid, or damaged): the vault this phone held is untouched.
     */
    PreviousVaultKept,
}

/** Changing the secret code (7.1 › Gizli kodu değiştir) failed. */
enum class ChangeSecretCodeError {
    /** The current code was wrong (it counted as a wrong unlock attempt). */
    WrongCurrentCode,

    /** Re-keying the vault failed; the old code still works. */
    Failed,
}

data class BackupEstimate(
    val fileName: String,
    val chatCount: Int,
    val messageCount: Int,
    val mediaBytes: Long,
    val totalBytes: Long,
)

data class RestoreProgress(
    val fraction: Double,
    val stage: Stage,
    val messagesDone: Int,
    val messagesTotal: Int,
    val mediaBytes: Long,
    val estimatedRemaining: Duration,
    /**
     * The backup carries message history and media (the design's planned format). An account
     * backup never does (PROTOCOL §5.5): then 7.3 shows only the keys step and says that messages
     * don't come back.
     */
    val includesHistory: Boolean = true,
) {
    enum class Stage { Keys, Messages, Media }

    /** The steps 7.3 lists. */
    val stages: List<Stage> get() = if (includesHistory) Stage.entries else listOf(Stage.Keys)
}
