package com.syconx.us2us.domain

import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.design.DesignMediaArchive
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The design's data, as the iOS fixtures serve it. */
class DesignFixturesTest {
    private val now = DesignFixtures.referenceDate

    @Test
    fun `the design moment is 8 October 2026, 18·52 on the device's clock`() {
        assertEquals(LocalDateTime.of(2026, 10, 8, 18, 52), LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
        assertEquals(LocalDateTime.of(2026, 9, 24, 11, 5), LocalDateTime.ofInstant(DesignFixtures.date(month = 9, day = 24, hour = 11, minute = 5), ZoneId.systemDefault()))
    }

    @Test
    fun `the chat list is the design's, in its order`() {
        val chats = DesignFixtures.chats(now, ghostUrgent = false, english = false)
        assertEquals(listOf("deniz", "mert_k", "ayla_", "kaan", "ece_y", "bora", "oya_t"), chats.map { it.peer.username })
        assertEquals(chats.map { it.peer.username }, chats.map { it.id.value })
        assertEquals(listOf(AvatarPalette.A, AvatarPalette.B, AvatarPalette.C, AvatarPalette.D, AvatarPalette.A, AvatarPalette.B, AvatarPalette.C), chats.map { it.peer.palette })
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0), chats.map { it.unreadCount })
        assertTrue(chats[0].peer.isVerified)
        assertTrue(chats[1].peer.isOnline)
        assertEquals(Presence.JustNow, chats[0].peer.presence)
        assertEquals(23.hours + 41.minutes + 7.seconds, chats[0].ghost.remaining(now))
        assertEquals(37.minutes + 20.seconds, chats[2].ghost.remaining(now))
        assertEquals(GhostPeriod.SevenDays, chats[3].ghost.period)
        assertEquals(
            listOf(
                MessagePreviewKind.Disappearing, MessagePreviewKind.Sticker, MessagePreviewKind.PhotoViewOnce,
                MessagePreviewKind.Text("Pazar kahvaltıya geliyor musun?"), MessagePreviewKind.Text("Orada görüşürüz"),
                MessagePreviewKind.LiveLocationEnded, MessagePreviewKind.Deleted,
            ),
            chats.map { it.lastMessage?.kind },
        )
        assertEquals(listOf(MessageStatus.Sent, null, MessageStatus.Delivered, MessageStatus.Read, MessageStatus.Failed, null, null), chats.map { it.lastMessage?.status })
        val times = chats.map { LocalDateTime.ofInstant(it.lastMessage!!.sentAt, ZoneId.systemDefault()).toString() }
        assertEquals(
            listOf("2026-10-08T18:10", "2026-10-08T18:44", "2026-10-08T16:20", "2026-10-07T21:34", "2026-09-24T11:05", "2026-09-22T19:40", "2026-09-19T09:12"),
            times,
        )
        assertTrue(chats.take(3).all { it.peer.avatar.isInitials })
        assertEquals(listOf(2, 5, 16), chats.subList(3, 6).map { it.peer.avatar.presetId })
        assertTrue(chats[6].peer.avatar.isPhoto)
    }

    @Test
    fun `the urgent variant puts deniz in her last hour`() {
        val urgent = DesignFixtures.chats(now, ghostUrgent = true, english = false)
        assertEquals(42.minutes + 10.seconds, urgent[0].ghost.remaining(now))
        assertTrue(urgent[0].ghost.isUrgent(now))
    }

    @Test
    fun `the English content is the design's section 09`() {
        val chats = DesignFixtures.chats(now, ghostUrgent = false, english = true)
        assertEquals(MessagePreviewKind.Text("Coming to Sunday breakfast?"), chats[3].lastMessage?.kind)
        val deniz = DesignFixtures.denizMessages(english = true)
        assertEquals(MessageContent.Text("Are you free tonight?"), deniz[1].content)
        assertEquals(MessageContent.Text("Door code 4471"), deniz[6].content)
    }

    @Test
    fun `the conversations hold the 5·1 and 5·2 messages`() {
        val deniz = DesignFixtures.messages(ChatId("deniz"), english = false)
        assertEquals((0..6).map { "d$it" }, deniz.map { it.id.value })
        assertEquals(MessageContent.System(SystemEvent.GhostEnabled(GhostPeriod.OneDay)), deniz[0].content)
        assertEquals(listOf(Reaction("❤️")), deniz[2].reactions)
        assertEquals(QuotedMessage.Kind.Photo, deniz[3].replyTo?.kind)
        assertEquals(42.seconds, (deniz[4].content as MessageContent.Voice).duration)
        assertEquals(30, (deniz[4].content as MessageContent.Voice).waveform.size)
        assertTrue(deniz[4].isVoicePlayed)
        assertEquals(DisappearingTimer.FiveMinutes, deniz[6].disappearing)
        val mert = DesignFixtures.messages(ChatId("mert_k"), english = false)
        assertEquals(listOf("ms1", "ms2", "ms3", "ms4", "m1", "m2", "m3", "m4", "m5", "m6", "m7"), mert.map { it.id.value })
        assertEquals(MessageContent.Sticker("us2us.words", 101, "👋", "tr"), mert[1].content)
        assertEquals(MessageContent.Sticker("us2us.cups", 6, "😍", ""), mert[3].content)
        assertEquals(now.plusSeconds(47 * 60), (mert[7].content as MessageContent.LiveLocation).expiresAt)
        assertEquals(MessageContent.TextSticker("tamam!"), mert.last().content)
        assertTrue(DesignFixtures.messages(ChatId("ayla_"), english = false).isEmpty())
        assertEquals(listOf("6.-", "101.tr"), DesignFixtures.stickerRecents(english = false))
    }

    @Test
    fun `the three requests of 4·3`() {
        val requests = DesignFixtures.requests(now, english = false)
        assertEquals(listOf("kuzey_dev", "n_aksoy", "hediye_kazan"), requests.map { it.sender })
        assertEquals(listOf(2L, 26L, 72L), requests.map { java.time.Duration.between(it.receivedAt, now).toHours() })
        assertEquals(listOf(emptySet(), setOf(MessageRequest.Flag.HasMedia), setOf(MessageRequest.Flag.HasLink, MessageRequest.Flag.Suspicious)), requests.map { it.flags })
        assertEquals(listOf(3, 1, 1), requests.map { it.messages.size })
        assertEquals(listOf(true, true, false), requests.map { it.offersAvatar })
        assertTrue(requests.all { it.avatar.isInitials })
    }

    @Test
    fun `settings, backup and the safety number`() {
        assertEquals(12, DesignFixtures.safetyNumberGroups.size)
        assertTrue(DesignFixtures.safetyNumberGroups.all { it.length == 5 && it.all(Char::isDigit) })
        assertEquals(60, DesignFixtures.safetyNumberPayload.size)
        assertEquals(GhostPeriod.OneDay, DesignFixtures.settings.defaultGhost)
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 30), LocalDateTime.ofInstant(DesignFixtures.settings.lastBackupAt, ZoneId.systemDefault()))
        assertEquals("us2us-yedek-2026-10-08.u2u", DesignFixtures.backupEstimate.fileName)
        assertEquals(0.62, DesignFixtures.restoreProgress.fraction)
        assertEquals(14, DesignFixtures.DEMO_BACKUP_PASSWORD.length)
        assertEquals(14, DesignFixtures.DEMO_EXPORT_PASSWORD.length)
        assertTrue(SecretCodeRules.isWellFormed(DesignFixtures.DEMO_SECRET_CODE))
        assertFalse(UsernameRules.isValid("selin.n"))
        assertTrue(DesignFixtures.TAKEN_AT_SETUP_USERNAME !in DesignFixtures.takenUsernames)
    }

    @Test
    fun `the demo pictures`() {
        val photo = DesignAvatars.photoAvatar
        assertTrue(photo.isPhoto)
        val bytes = photo.photo!!.data
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(AvatarPicture.preset(14), DesignAvatars.searchAvatar("lale"))
        assertEquals(AvatarPicture.preset(21), DesignAvatars.searchAvatar("kuzey_dev"))
        assertEquals(AvatarPicture.Initials, DesignAvatars.searchAvatar("deniz"))
        assertEquals(AvatarPicture.Initials, DesignAvatars.requestAvatar("n_aksoy"))
    }

    @Test
    fun `the contact screen's archive pages newest first`() {
        val deniz = ChatId("deniz")
        val all = DesignMediaArchive.archive(deniz, english = false) + DesignFixtures.messages(deniz, english = false)
        val first = DesignMediaArchive.page(all, ChatMediaKind.Visual, before = null, limit = 30)
        assertEquals(30, first.size)
        assertEquals(MessageId("d2"), first.first().id)
        assertTrue(first.zipWithNext().all { (a, b) -> a.sentAt >= b.sentAt })
        val second = DesignMediaArchive.page(all, ChatMediaKind.Visual, before = first.last().id, limit = 30)
        assertEquals(11, second.size)
        assertTrue(DesignMediaArchive.page(all, ChatMediaKind.Visual, before = MessageId("gone"), limit = 30).isEmpty())
        val documents = DesignMediaArchive.page(all, ChatMediaKind.Documents, before = null, limit = 30)
        assertEquals(6, documents.size)
        assertEquals("ucak-bileti.pdf", (documents.first().content as MessageContent.File).name)
        assertEquals(8, DesignMediaArchive.archive(deniz, false).count { it.content is MessageContent.Video })
    }

    @Test
    fun `the stand-in backup file is written once`() {
        val directory = Files.createTempDirectory("u2u").toFile()
        try {
            val uri = DesignFixtures.standInBackupFile(directory)
            assertEquals("us2us-yedek-2026-10-08.u2u", File(uri).name)
            assertEquals("U2UBACKUP\u0001", File(uri).readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
