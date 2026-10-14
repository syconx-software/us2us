package com.syconx.us2us.domain

import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelsTest {
    private val now = Instant.parse("2026-10-08T15:52:00Z")

    @Test
    fun `avatar palettes are FNV-1a of the UTF-8 name, as on iOS`() {
        // Expected values computed independently (Python) with the same hash.
        val expected = mapOf(
            "deniz" to AvatarPalette.D, "mert_k" to AvatarPalette.D, "ayla_" to AvatarPalette.B, "kaan" to AvatarPalette.C,
            "lale" to AvatarPalette.B, "kuzey_dev" to AvatarPalette.D, "n_aksoy" to AvatarPalette.D, "hediye_kazan" to AvatarPalette.B,
            "" to AvatarPalette.B,
        )
        for ((name, palette) in expected) assertEquals(palette, AvatarPalette.forUsername(name), name)
    }

    @Test
    fun `ghost state counts down, never below zero, and is urgent in its last hour`() {
        val ghost = GhostState(isEnabled = true, period = GhostPeriod.OneDay, deadline = now.plusSeconds(23 * 3600 + 41 * 60 + 7))
        assertEquals(23.hours + 41.minutes + 7.seconds, ghost.remaining(now))
        assertFalse(ghost.isUrgent(now))
        val urgent = ghost.copy(deadline = now.plusSeconds(42 * 60 + 10))
        assertTrue(urgent.isUrgent(now))
        assertEquals(Duration.ZERO, ghost.copy(deadline = now.minusSeconds(5)).remaining(now))
        assertNull(GhostState.Off.remaining(now))
        assertFalse(GhostState.Off.isUrgent(now))
        assertEquals(168, GhostPeriod.SevenDays.hours)
    }

    @Test
    fun `message kinds and copyable text`() {
        fun message(content: MessageContent) = Message(MessageId("x"), MessageDirection.Incoming, now, content)
        assertEquals("live", message(MessageContent.LiveLocation(now, true)).contentKind)
        assertEquals("liveEnded", message(MessageContent.LiveLocation(now, false)).contentKind)
        assertEquals("viewOnce.unopened", message(MessageContent.ViewOnce(false, ViewOnceState.Unopened)).contentKind)
        assertEquals("sticker", message(MessageContent.TextSticker("tamam!")).contentKind)
        assertEquals("caption", message(MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), "caption")).copyableText)
        assertNull(message(MessageContent.Deleted).copyableText)
        assertTrue(message(MessageContent.Deleted).isDeleted)
        assertTrue(message(MessageContent.System(SystemEvent.GhostDisabled)).isSystem)
        val pending = message(MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), 18.seconds, 0))
            .copy(mediaAvailability = MediaAvailability.Preparing)
        assertTrue(pending.isPendingMedia)
        assertTrue(MediaAvailability.Downloading(0.4).isDownloading)
    }

    @Test
    fun `peers offer verification only with a known, unverified, unchanged key`() {
        val peer = Peer("deniz", AvatarPalette.A, isVerified = false, isAcceptedContact = true, presence = Presence.Online, identityFingerprint = "ab")
        assertTrue(peer.offersVerification)
        assertTrue(peer.isOnline)
        assertFalse(peer.copy(safetyNumberChanged = true).offersVerification)
        assertFalse(peer.copy(identityFingerprint = null).offersVerification)
        assertFalse(peer.copy(isAcceptedContact = false).isOnline)
    }

    @Test
    fun `safety numbers know when they are verified or changed`() {
        val info = SafetyNumberInfo(listOf("08223"), Bytes.utf8("08223"), verifiedAt = null, method = null, changedAt = now)
        assertTrue(info.hasUnverifiedChange)
        assertFalse(info.isVerified)
        assertTrue(info.copy(isVerifiedWithoutRecord = true).isVerified)
        assertFalse(info.copy(verifiedAt = now).hasUnverifiedChange)
    }

    @Test
    fun `backup contents never write media without messages`() {
        assertFalse(BackupContents(messages = false, media = true).includesMedia)
        assertTrue(BackupContents(messages = false, settingsAndContacts = false).isEmpty)
        assertEquals(listOf(RestoreProgress.Stage.Keys), restoreProgress(includesHistory = false).stages)
        assertEquals(RestoreProgress.Stage.entries, restoreProgress(includesHistory = true).stages)
    }

    private fun restoreProgress(includesHistory: Boolean) =
        RestoreProgress(0.5, RestoreProgress.Stage.Keys, 0, 0, 0, 1.seconds, includesHistory = includesHistory)

    @Test
    fun `bytes compare by content`() {
        assertEquals(Bytes.utf8("abc"), Bytes.of("abc".encodeToByteArray()))
        assertEquals(Bytes.utf8("abc").hashCode(), Bytes.of("abc".encodeToByteArray()).hashCode())
        assertNotEquals(Bytes.utf8("abc"), Bytes.utf8("abd"))
        assertEquals("00ff10", Bytes.ofUnsigned(listOf(0, 255, 16)).toHex())
        val source = byteArrayOf(1, 2, 3)
        val copy = Bytes.of(source)
        source[0] = 9
        assertEquals(1.toByte(), copy[0])
    }

    @Test
    fun `avatar choices survive storage and unknown presets draw as the initial`() {
        assertEquals(AvatarChoice.Preset(7), AvatarChoice.fromStorageValue("preset:7"))
        assertEquals("preset:7", AvatarChoice.Preset(7).storageValue)
        assertEquals(AvatarChoice.Initials, AvatarChoice.fromStorageValue("preset:0"))
        assertEquals(AvatarChoice.Initials, AvatarChoice.fromStorageValue("preset:x"))
        assertEquals(AvatarChoice.Photo, AvatarChoice.fromStorageValue(" photo "))
        assertEquals(AvatarChoice.Initials, AvatarChoice.fromStorageValue(""))
        assertEquals(AvatarChoice.Preset(99), AvatarChoice.fromStorageValue("preset:99"))
        assertEquals(AvatarChoice.Initials, AvatarChoice.Preset(99).drawn)
        assertTrue(AvatarPicture.preset(99).isInitials)
        assertEquals(5, AvatarPicture.preset(5).presetId)
        assertTrue(AvatarPicture(AvatarChoice.Photo).isInitials)
        val photo = AvatarPhotoData(Bytes.utf8("jpeg bytes"), "image/jpeg")
        assertEquals(AvatarPicture.photo(photo), AvatarPicture.photo(AvatarPhotoData(Bytes.utf8("jpeg bytes"), "image/jpeg")))
        assertTrue(AvatarPicture.photo(photo).isPhoto)
        // A preset choice never carries photo bytes.
        assertNull(AvatarPicture(AvatarChoice.Preset(3), photo).photo)
        assertEquals(22, AvatarPresetCatalog.all.size)
        assertEquals((1..22).toList(), AvatarPresetCatalog.all.map { it.id })
    }

    @Test
    fun `own avatar is shown in search only with a picture and the setting on`() {
        assertFalse(OwnAvatarState().isShownInSearch)
        assertTrue(OwnAvatarState(picture = AvatarPicture.preset(2)).isShownInSearch)
        assertFalse(OwnAvatarState(picture = AvatarPicture.preset(2), showInSearch = false).isShownInSearch)
    }

    @Test
    fun `live shares are active until they expire or stop`() {
        val share = LiveShare(MessageId("m8"), MessageDirection.Incoming, now, LivePosition(40.98, 29.02), now.plusSeconds(60), isStopped = false)
        assertTrue(share.isActive(now))
        assertFalse(share.isActive(now.plusSeconds(60)))
        assertFalse(share.copy(isStopped = true).isActive(now))
        val snapshot = LiveLocationSnapshot(listOf(share))
        assertEquals(share, snapshot.activeShare(MessageDirection.Incoming, now))
        assertNull(snapshot.activeShare(MessageDirection.Outgoing, now))
        assertFalse(LivePosition(91.0, 0.0).isValidCoordinate)
        assertFalse(LivePosition(Double.NaN, 0.0).isValidCoordinate)
    }

    @Test
    fun `media export never lets disappearing, pending or held media out`() {
        val photo = Message(MessageId("p"), MessageDirection.Incoming, now, MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null))
        assertTrue(MediaExportPolicy.allowsExport(photo))
        assertTrue(MediaExportPolicy.savesToGallery(photo))
        assertFalse(MediaExportPolicy.allowsExport(photo.copy(disappearing = DisappearingTimer.FiveMinutes)))
        assertFalse(MediaExportPolicy.allowsExport(photo.copy(mediaAvailability = MediaAvailability.Held)))
        assertTrue(MediaExportPolicy.allowsExport(photo.copy(mediaAvailability = MediaAvailability.Remote)))
        val movie = photo.copy(content = MessageContent.File("tatil.mov", 1, "MOV", null))
        assertTrue(MediaExportPolicy.isVideo(movie))
        assertEquals(SaveNoticeKind.Video, MediaExportPolicy.noticeKind(movie))
        assertEquals(SaveNoticeKind.File, MediaExportPolicy.noticeKind(photo.copy(content = MessageContent.File("a.pdf", 1, "PDF", 1))))
        assertFalse(MediaExportPolicy.allowsExport(photo.copy(content = MessageContent.ViewOnce(false, ViewOnceState.Unopened))))
        assertTrue(MediaExportPolicy.tellsSender(photo))
    }
}
