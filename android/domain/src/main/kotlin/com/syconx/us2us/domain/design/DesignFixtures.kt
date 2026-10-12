package com.syconx.us2us.domain.design

import com.syconx.us2us.domain.AppSettings
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.BackupEstimate
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.PresenceVisibility
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.domain.sticker.StickerCatalog
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The data drawn in the design, exactly: names, palettes, messages, times, statuses, Ghost
 * counters, requests, the safety number, settings and the backup estimate. Times are wall-clock
 * times on the design's day (Thursday 8 October 2026) in the device's time zone, so “18:10” always
 * renders as 18:10.
 *
 * User content (the demo messages) exists in Turkish and in the English the design's section 09
 * shows. The fixtures service serves it; previews, galleries and harnesses read it directly.
 */
object DesignFixtures {
    /** The design's moment: 8 Oct 2026, 18:52 (the 6.3 verification time). */
    val referenceDate: Instant get() = date(day = 8, hour = 18, minute = 52)

    /** The demo account. */
    const val OWN_USERNAME = "selin_n"

    /** The demo secret code (any valid, non-trivial six digits would do). */
    const val DEMO_SECRET_CODE = "246813"

    /**
     * The password that opens the fixtures' backups (14 characters: the 7.3 reference draws 14
     * dots). Backups exported in the same session open with their own password too.
     */
    const val DEMO_BACKUP_PASSWORD = "kuzey7deniz42k"

    /**
     * The 7.2 reference password: 14 random lower-case letters and digits, “Güçlü” with 82 % of the
     * meter lit (72.4 bits). It contains no word, year or run, so the strength estimate counts every
     * character.
     */
    const val DEMO_EXPORT_PASSWORD = "v7qz3kd9xw2mtr"

    /** 7.3: “8 Ekim 2026, 21:14”. */
    val designBackupDate: Instant get() = date(day = 8, hour = 21, minute = 14)

    /** 6.1 safety number with deniz: 12 groups of 5 digits. */
    val safetyNumberGroups: List<String> = listOf(
        "08223", "78626", "74024", "23263",
        "12493", "59214", "00841", "88304",
        "26120", "14864", "87953", "64169",
    )

    /** deniz's scannable payload: the 60 digits as text (the fixtures compare raw payloads). */
    val safetyNumberPayload: Bytes get() = Bytes.utf8(safetyNumberGroups.joinToString(""))

    /** 5.1 voice message waveform: the design's 30 bars (heights of a 28 pt box). */
    val voiceWaveform: List<Double> = listOf(
        6, 10, 16, 12, 20, 14, 8, 18, 22, 12, 9,
        15, 20, 11, 7, 13, 19, 16, 10, 6, 12, 17, 21, 14, 9, 11, 16, 8, 12, 18,
    ).map { it / 28.0 }

    /** 11 of the 30 bars are drawn as played. */
    const val VOICE_PLAYED_FRACTION = 11.0 / 30.0

    // MARK: Time helpers

    /** A wall-clock time in October 2026 (or September with `month = 9`) in the device's time zone. */
    fun date(month: Int = 10, day: Int, hour: Int, minute: Int, second: Int = 0): Instant =
        DesignClock.designDate(month = month, day = day, hour = hour, minute = minute, second = second)

    fun remaining(hours: Int = 0, minutes: Int = 0, seconds: Int = 0, days: Int = 0): Duration =
        days.days + hours.hours + minutes.minutes + seconds.seconds

    private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())

    // MARK: Peers

    val deniz = Peer(username = "deniz", palette = AvatarPalette.A, isVerified = true, isAcceptedContact = true, presence = Presence.JustNow)
    val mert = Peer(username = "mert_k", palette = AvatarPalette.B, isVerified = false, isAcceptedContact = true, presence = Presence.Online)
    val ayla = Peer(username = "ayla_", palette = AvatarPalette.C, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden)

    // Proposed: the rows below the design's first three show profile pictures (DesignAvatars):
    // three presets and a photo.
    val kaan: Peer get() = Peer(
        username = "kaan", palette = AvatarPalette.D, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden,
        avatar = DesignAvatars.chatAvatar("kaan"),
    )
    val ece: Peer get() = Peer(
        username = "ece_y", palette = AvatarPalette.A, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden,
        avatar = DesignAvatars.chatAvatar("ece_y"),
    )
    val bora: Peer get() = Peer(
        username = "bora", palette = AvatarPalette.B, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden,
        avatar = DesignAvatars.chatAvatar("bora"),
    )
    val oya: Peer get() = Peer(
        username = "oya_t", palette = AvatarPalette.C, isVerified = false, isAcceptedContact = true, presence = Presence.Hidden,
        avatar = DesignAvatars.chatAvatar("oya_t"),
    )

    // MARK: Chats (4.1 / 8.1 / 9.1)

    /** The seven chat-list rows, in the design's order. */
    fun chats(now: Instant, ghostUrgent: Boolean, english: Boolean): List<ChatSummary> {
        val denizRemaining = if (ghostUrgent) remaining(minutes = 42, seconds = 10) else remaining(hours = 23, minutes = 41, seconds = 7)
        return listOf(
            ChatSummary(
                id = ChatId("deniz"), peer = deniz,
                lastMessage = MessagePreview(MessagePreviewKind.Disappearing, MessageDirection.Outgoing, MessageStatus.Sent, date(day = 8, hour = 18, minute = 10)),
                unreadCount = 0,
                ghost = GhostState(isEnabled = true, period = GhostPeriod.OneDay, deadline = now + denizRemaining),
                disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("mert_k"), peer = mert,
                lastMessage = MessagePreview(MessagePreviewKind.Sticker, MessageDirection.Incoming, null, date(day = 8, hour = 18, minute = 44)),
                unreadCount = 2, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("ayla_"), peer = ayla,
                lastMessage = MessagePreview(MessagePreviewKind.PhotoViewOnce, MessageDirection.Outgoing, MessageStatus.Delivered, date(day = 8, hour = 16, minute = 20)),
                unreadCount = 0,
                ghost = GhostState(isEnabled = true, period = GhostPeriod.OneDay, deadline = now + remaining(minutes = 37, seconds = 20)),
                disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("kaan"), peer = kaan,
                lastMessage = MessagePreview(
                    MessagePreviewKind.Text(if (english) "Coming to Sunday breakfast?" else "Pazar kahvaltıya geliyor musun?"),
                    MessageDirection.Outgoing, MessageStatus.Read, date(day = 7, hour = 21, minute = 34),
                ),
                unreadCount = 0,
                ghost = GhostState(isEnabled = true, period = GhostPeriod.SevenDays, deadline = now + remaining(hours = 4, days = 6)),
                disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("ece_y"), peer = ece,
                lastMessage = MessagePreview(
                    MessagePreviewKind.Text(if (english) "See you there" else "Orada görüşürüz"),
                    MessageDirection.Outgoing, MessageStatus.Failed, date(month = 9, day = 24, hour = 11, minute = 5),
                ),
                unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("bora"), peer = bora,
                lastMessage = MessagePreview(MessagePreviewKind.LiveLocationEnded, MessageDirection.Incoming, null, date(month = 9, day = 22, hour = 19, minute = 40)),
                unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            ),
            ChatSummary(
                id = ChatId("oya_t"), peer = oya,
                lastMessage = MessagePreview(MessagePreviewKind.Deleted, MessageDirection.Incoming, null, date(month = 9, day = 19, hour = 9, minute = 12)),
                unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
            ),
        )
    }

    // MARK: Conversations (5.1 / 8.2 / 9.2 and 5.2)

    fun messages(chat: ChatId, english: Boolean): List<Message> = when (chat.value) {
        "deniz" -> denizMessages(english)
        "mert_k" -> mertMessages(english)
        else -> emptyList()
    }

    /** 5.1: the chat with deniz. */
    fun denizMessages(english: Boolean): List<Message> {
        fun t(tr: String, en: String) = if (english) en else tr
        return listOf(
            Message(
                id = MessageId("d0"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 0),
                content = MessageContent.System(SystemEvent.GhostEnabled(GhostPeriod.OneDay)),
            ),
            Message(
                id = MessageId("d1"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 2),
                content = MessageContent.Text(t("Bu akşam boş musun?", "Are you free tonight?")),
            ),
            Message(
                id = MessageId("d2"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 3),
                content = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption = t("Şu yeni yer, Moda’da", "That new place in Moda")),
                reactions = listOf(Reaction(emoji = "❤️")),
            ),
            Message(
                id = MessageId("d3"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 18, minute = 5),
                content = MessageContent.Text(t("Çok güzelmiş! 8 gibi olur mu?", "Looks great! Around 8?")), status = MessageStatus.Read,
                replyTo = QuotedMessage(author = "deniz", kind = QuotedMessage.Kind.Photo, thumbnail = MediaSource.Artwork(DesignArtwork.NightPier)),
                deliveredAt = date(day = 8, hour = 18, minute = 6), readAt = date(day = 8, hour = 18, minute = 7),
            ),
            Message(
                id = MessageId("d4"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 7),
                content = MessageContent.Voice(duration = 42.seconds, waveform = voiceWaveform, playedFraction = VOICE_PLAYED_FRACTION),
                isVoicePlayed = true,
            ),
            Message(
                id = MessageId("d5"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 18, minute = 9),
                content = MessageContent.Text(t("Tamam, iskelede buluşalım", "OK, meet you at the pier")), status = MessageStatus.Delivered,
                deliveredAt = date(day = 8, hour = 18, minute = 9),
            ),
            Message(
                id = MessageId("d6"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 18, minute = 10),
                content = MessageContent.Text(t("Kapı kodu 4471", "Door code 4471")), status = MessageStatus.Sent,
                disappearing = DisappearingTimer.FiveMinutes,
            ),
        )
    }

    /**
     * 5.2: the content catalogue in the chat with mert_k (Ghost off). It starts with a few built-in
     * stickers that morning (proposed; above what the 5.2 reference shows); the design's own text
     * sticker, “tamam!”, stays the last message.
     */
    fun mertMessages(english: Boolean): List<Message> = stickerMessages(english) + listOf(
        Message(
            id = MessageId("m1"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 12, minute = 40),
            content = MessageContent.File(name = "kira-sozlesmesi.pdf", byteCount = 2_100_000, typeLabel = "PDF", pageCount = 4),
            status = MessageStatus.Read,
        ),
        Message(
            id = MessageId("m2"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 12, minute = 52),
            content = MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), duration = 18.seconds, byteCount = 12_400_000),
        ),
        Message(
            id = MessageId("m3"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 17, minute = 58),
            content = MessageContent.Location(name = "Moda İskelesi", locality = "Kadıköy, İstanbul", latitude = 40.9846, longitude = 29.0249),
        ),
        Message(
            id = MessageId("m4"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 18, minute = 1),
            content = MessageContent.LiveLocation(expiresAt = date(day = 8, hour = 18, minute = 52) + 47.minutes, isActive = true),
            status = MessageStatus.Read,
        ),
        Message(id = MessageId("m5"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 20), content = MessageContent.Deleted),
        Message(
            id = MessageId("m6"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 40),
            content = MessageContent.ViewOnce(isVideo = false, state = ViewOnceState.Unopened),
        ),
        Message(
            id = MessageId("m7"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 18, minute = 44),
            content = MessageContent.TextSticker("tamam!"),
        ),
    )

    /** Built-in stickers (PROTOCOL §8.8) at the start of the chat with mert_k: the words in the language of the demo content. */
    fun stickerMessages(english: Boolean): List<Message> {
        val lang = if (english) "en" else "tr"
        fun sticker(id: Int, lang: String = ""): MessageContent {
            val sticker = StickerCatalog.sticker(id) ?: return MessageContent.Deleted
            return MessageContent.Sticker(packId = sticker.packId, stickerId = id, emoji = sticker.emoji, lang = if (sticker.isText) lang else "")
        }
        return listOf(
            Message(id = MessageId("ms1"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 12, minute = 30), content = sticker(1)),
            Message(
                id = MessageId("ms2"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 12, minute = 31), content = sticker(101, lang),
                status = MessageStatus.Read, deliveredAt = date(day = 8, hour = 12, minute = 31), readAt = date(day = 8, hour = 12, minute = 32),
            ),
            Message(id = MessageId("ms3"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 12, minute = 33), content = sticker(302, lang)),
            Message(
                id = MessageId("ms4"), direction = MessageDirection.Outgoing, sentAt = date(day = 8, hour = 12, minute = 34), content = sticker(6),
                status = MessageStatus.Read, deliveredAt = date(day = 8, hour = 12, minute = 34), readAt = date(day = 8, hour = 12, minute = 35),
            ),
        )
    }

    /** The picker's recents in the fixtures: what selin_n sent above, newest first. */
    fun stickerRecents(english: Boolean): List<String> = listOf("6.-", "101.${if (english) "en" else "tr"}")

    /**
     * “+” › Fotoğraf ve video in the fixtures: three of the design's pictures, as if picked in this
     * order (the real picker runs only with the live service).
     */
    val pickedPhotos: List<PickedMediaItem> = listOf(
        PickedMediaItem(PickedMediaItem.Kind.Photo, PickedMediaItem.Source.Artwork(DesignArtwork.NightPier)),
        PickedMediaItem(PickedMediaItem.Kind.Photo, PickedMediaItem.Source.Artwork(DesignArtwork.SunsetVideo)),
        PickedMediaItem(PickedMediaItem.Kind.Photo, PickedMediaItem.Source.Artwork(DesignArtwork.NightPier)),
    )

    // MARK: Requests (4.3 / 4.4)

    fun requests(now: Instant, english: Boolean): List<MessageRequest> {
        fun t(tr: String, en: String) = if (english) en else tr
        val kuzeyMessages = listOf(
            Message(
                id = MessageId("k1"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 16, minute = 12),
                content = MessageContent.Text(t("Merhaba! Dünkü meetup’taki sunumunu çok beğendim.", "Hi! I really liked your talk at yesterday's meetup.")),
            ),
            Message(
                id = MessageId("k2"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 16, minute = 12),
                content = MessageContent.Text(t("Slaytları paylaşabilir misin? Bağlantıyı da bırakıyorum:", "Could you share the slides? Here's the link:")),
            ),
            Message(
                id = MessageId("k3"), direction = MessageDirection.Incoming, sentAt = date(day = 8, hour = 16, minute = 13),
                content = MessageContent.Text("slides.kuzey.dev/meetup"),
            ),
        )
        return listOf(
            MessageRequest(
                id = RequestId("kuzey_dev"), sender = "kuzey_dev", receivedAt = now - 2.hours,
                preview = MessagePreviewKind.Text(
                    t(
                        "Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin?",
                        "Hi! I really liked your talk at yesterday's meetup — could you share the slides?",
                    ),
                ),
                flags = emptySet(), messages = kuzeyMessages, offersAvatar = true,
            ),
            MessageRequest(
                id = RequestId("n_aksoy"), sender = "n_aksoy", receivedAt = now - 26.hours,
                preview = MessagePreviewKind.Photo, flags = setOf(MessageRequest.Flag.HasMedia),
                messages = listOf(
                    Message(
                        id = MessageId("n1"), direction = MessageDirection.Incoming, sentAt = date(day = 7, hour = 15, minute = 30),
                        content = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.SunsetVideo), caption = null),
                    ),
                ),
                offersAvatar = true,
            ),
            MessageRequest(
                id = RequestId("hediye_kazan"), sender = "hediye_kazan", receivedAt = now - 3.days,
                preview = MessagePreviewKind.Text(
                    t("Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/…", "Congratulations! Click the link now to claim your prize: bit.ly/…"),
                ),
                flags = setOf(MessageRequest.Flag.HasLink, MessageRequest.Flag.Suspicious),
                messages = listOf(
                    Message(
                        id = MessageId("h1"), direction = MessageDirection.Incoming, sentAt = date(day = 5, hour = 10, minute = 2),
                        content = MessageContent.Text(
                            t("Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/3xQ", "Congratulations! Click the link now to claim your prize: bit.ly/3xQ"),
                        ),
                    ),
                ),
            ),
        )
    }

    private operator fun Instant.minus(duration: Duration): Instant = minus(duration.toJavaDuration())

    // MARK: Verification (6.1)

    /** deniz's safety number as drawn in 6.1: not verified yet. */
    val denizSafetyNumber: SafetyNumberInfo
        get() = SafetyNumberInfo(groups = safetyNumberGroups, scannablePayload = safetyNumberPayload, verifiedAt = null, method = null)

    /**
     * The fixtures' stand-in for a user's identity-key fingerprint: what their contact code carries
     * (the live code: SHA-256 of the key). The first 16 bytes of SHA-256("us2us-identity-<username>"), as hex.
     */
    fun fixtureKeyFingerprint(username: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("us2us-identity-$username".encodeToByteArray())
        return Bytes.wrap(digest.copyOf(16)).toHex()
    }

    // MARK: Settings & backup (7.1 / 7.2)

    val settings: AppSettings
        get() = AppSettings(
            presenceVisibility = PresenceVisibility.AcceptedContacts, readReceipts = true, faceIdEnabled = true,
            defaultGhost = GhostPeriod.OneDay, defaultDisappearing = DisappearingTimer.Off, lastBackupAt = date(day = 8, hour = 9, minute = 30),
        )

    val backupEstimate = BackupEstimate(
        fileName = "us2us-yedek-2026-10-08.u2u", chatCount = 12, messageCount = 3_920,
        mediaBytes = 162_000_000, totalBytes = 184_000_000,
    )

    /** 7.3: shown mid-restore at 62 %. */
    val restoreProgress = RestoreProgress(
        fraction = 0.62, stage = RestoreProgress.Stage.Messages, messagesDone = 2_431, messagesTotal = 3_920,
        mediaBytes = 162_000_000, estimatedRemaining = 60.seconds,
    )

    /** Usernames the fixtures treat as taken (2.1): the people of the design, plus “lale”, someone with no chat yet (4.2 “Mesaj yaz” walks). */
    val takenUsernames: Set<String> = setOf("selin", "deniz", "mert_k", "ayla_", "kaan", "ece_y", "bora", "oya_t", "kuzey_dev", "lale")

    /** A name that meets the rules on 2.1 (“Kurallara uygun”, as the live service says it) and is taken when 2.3 registers it. */
    const val TAKEN_AT_SETUP_USERNAME = "ilgaz"

    // MARK: Live location (5.2 › harita)

    /** selin_n: her share (m4) and her phone, Moda Caddesi. */
    val ownFixturePosition = LivePosition(latitude = 40.9872, longitude = 29.0262, accuracy = 12.0)

    /** mert_k's demo share starts at Moda İskelesi (the m3 place). */
    val peerFixturePosition = LivePosition(latitude = 40.9846, longitude = 29.0249, accuracy = 18.0)

    /** The demo share's message. */
    val demoLiveShareId = MessageId("m8")

    /**
     * `U2PickBackup` (UI tests, flow walks): the system document picker runs in another process, so
     * 7.3 “picks” this stand-in `.u2u` file instead (the fixtures open it with
     * [DEMO_BACKUP_PASSWORD]). Written once into [directory] (the app's cache by default).
     */
    fun standInBackupFile(directory: File = File(System.getProperty("java.io.tmpdir"))): URI {
        val file = File(directory, backupEstimate.fileName)
        if (!file.exists()) {
            directory.mkdirs()
            file.writeBytes("U2UBACKUP\u0001".encodeToByteArray())
        }
        return file.toURI()
    }
}
