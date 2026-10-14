package com.syconx.us2us.domain

import com.syconx.us2us.domain.design.DesignFixtures
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** QR payloads, protocol tables, file names, voice notes and picked media. */
class CodecsTest {
    @Test
    fun `the safety number QR code is U2SN base32 without padding, both ways`() {
        val payload = DesignFixtures.safetyNumberPayload
        // Python: "U2SN:" + base64.b32encode(payload).rstrip("=")
        val expected = "U2SN:GA4DEMRTG44DMMRWG42DAMRUGIZTENRTGEZDIOJTGU4TEMJUGAYDQNBRHA4DGMBUGI3DCMRQGE2DQNRUHA3TSNJTGY2DCNRZ"
        assertEquals(expected, SafetyQRCodec.encode(payload))
        assertEquals(payload, SafetyQRCodec.decode(expected))
        for (size in 0..40) {
            val bytes = Bytes.ofUnsigned((0 until size).map { (it * 37 + 11) % 256 })
            val decoded = SafetyQRCodec.decode(SafetyQRCodec.encode(bytes))
            if (size == 0) assertNull(decoded) else assertEquals(bytes, decoded)
        }
        assertNull(SafetyQRCodec.decode("U2SN:"))
        assertNull(SafetyQRCodec.decode("U2SN:abc"))
        assertNull(SafetyQRCodec.decode("https://us2us.app"))
        assertTrue(SafetyQRCodec.constantTimeEquals(payload, DesignFixtures.safetyNumberPayload))
        assertFalse(SafetyQRCodec.constantTimeEquals(payload, Bytes.utf8("0")))
    }

    @Test
    fun `contact codes carry the exact name and an optional hex fingerprint`() {
        assertEquals("us2us:@selin_n#8a95e3f5", ContactCode.encode("selin_n", "8a95e3f5"))
        assertEquals("us2us:@selin_n", ContactCode.encode("selin_n", ""))
        assertEquals(ContactCode.Decoded("selin_n", "8a95e3f5"), ContactCode.decode("us2us:@selin_n#8a95e3f5"))
        assertEquals(ContactCode.Decoded("deniz", null), ContactCode.decode("us2us:@deniz"))
        assertEquals(ContactCode.Decoded("deniz", null), ContactCode.decode("us2us:@deniz#"))
        assertNull(ContactCode.decode("us2us:@Deniz"))
        assertNull(ContactCode.decode("us2us:@deniz#xyz"))
        assertNull(ContactCode.decode("us2us:deniz"))
        assertNull(ContactCode.decode("us2us:@" + "a".repeat(600)))
        assertEquals("deniz", ContactCode.username("us2us:@deniz#00"))
        assertEquals("8a95e3f5cc758424b5e14c50e96acd0b", DesignFixtures.fixtureKeyFingerprint("selin_n"))
    }

    @Test
    fun `the throttle table is PROTOCOL 5·2`() {
        val expected = listOf(0, 0, 0, 1, 5, 15, 30, 60, 60, 60).map { it.minutes }
        assertEquals(expected, (0..9).map(UnlockThrottle::delay))
        assertEquals(UpcomingDelay(3, 1.minutes), UnlockThrottle.upcomingDelay(0))
        assertEquals(UpcomingDelay(1, 1.minutes), UnlockThrottle.upcomingDelay(2))
        assertEquals(UpcomingDelay(1, 5.minutes), UnlockThrottle.upcomingDelay(3))
        assertEquals(UpcomingDelay(1, 60.minutes), UnlockThrottle.upcomingDelay(12))
        assertEquals(UpcomingDelay(3, 1.minutes), UnlockStatus.Initial.upcoming)
        assertTrue(SecretCodeRules.isWellFormed("246813"))
        assertFalse(SecretCodeRules.isWellFormed("24681"))
        assertFalse(SecretCodeRules.isWellFormed("２４６８１３"))
    }

    @Test
    fun `generated backup passwords have a fixed shape and strength`() {
        assertEquals(31, BackupPasswordRules.GENERATED_ALPHABET.toSet().size)
        for (lookAlike in "0o1li") assertFalse(lookAlike in BackupPasswordRules.GENERATED_ALPHABET)
        assertEquals(20 * kotlin.math.ln(31.0) / kotlin.math.ln(2.0), BackupPasswordRules.generatedEntropyBits, 1e-9)
        assertTrue(BackupPasswordRules.isGeneratedFormat("k7mq-x2pd-9tfa-wn3h-b8ze"))
        val others = listOf(
            "", "k7mq-x2pd-9tfa-wn3h", "k7mq-x2pd-9tfa-wn3h-b8z", "k7mq-x2pd-9tfa-wn3h-b8ze-",
            "K7MQ-X2PD-9TFA-WN3H-B8ZE", "k7mq x2pd 9tfa wn3h b8ze", "o7mq-x2pd-9tfa-wn3h-b8ze", "k7mqx2pd9tfawn3hb8ze",
            "k7mq--x2pd-9tfa-wn3h-b8ze",
        )
        for (password in others) assertFalse(BackupPasswordRules.isGeneratedFormat(password), password)
    }

    @Test
    fun `file names from the other side are made safe`() {
        assertEquals("passwd", FileNameSanitizer.sanitized("../../etc/passwd"))
        assertEquals("fatura.pdf", FileNameSanitizer.sanitized("..\\fatura.pdf"))
        // A right-to-left override cannot hide the real extension.
        assertEquals("faturafdp.exe", FileNameSanitizer.sanitized("fatura‮fdp.exe"))
        assertEquals("file", FileNameSanitizer.sanitized(null))
        assertEquals("file.pdf", FileNameSanitizer.sanitized("", "application/pdf"))
        assertEquals("notlar.txt", FileNameSanitizer.sanitized("notlar", "text/plain"))
        assertEquals("hidden", FileNameSanitizer.sanitized("...hidden"))
        assertEquals("a b.pdf", FileNameSanitizer.sanitized("  a b.pdf  "))
        assertEquals("ab.pdf", FileNameSanitizer.sanitized("a:b.pdf"))
        val long = FileNameSanitizer.sanitized("x".repeat(300) + ".pdf")
        assertEquals(FileNameSanitizer.MAX_LENGTH, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun `file kinds come from the name, then the content type`() {
        assertEquals(MediaFileKind.Pdf, MediaFileKind.of("kira-sozlesmesi.pdf"))
        assertEquals(MediaFileKind.Movie, MediaFileKind.of("tatil.MOV"))
        assertEquals(MediaFileKind.Image, MediaFileKind.of("ev-plani.png"))
        assertEquals(MediaFileKind.Audio, MediaFileKind.of("ses.m4a"))
        assertEquals(MediaFileKind.Other, MediaFileKind.of("fotograflar.zip"))
        assertEquals(MediaFileKind.Movie, MediaFileKind.of("clip", "video/mp4"))
        assertTrue(MediaFileKind.opensInPhotoViewer("ev-plani.png"))
        assertFalse(MediaFileKind.opensInPhotoViewer("scan.tiff"))
    }

    @Test
    fun `voice waveforms resample to the wire and to the bubble`() {
        assertEquals(1.0, VoiceWaveform.level(0f))
        assertEquals(0.0, VoiceWaveform.level(-80f))
        assertEquals(0.5, VoiceWaveform.level(-25f), 1e-9)
        assertEquals(0.0, VoiceWaveform.level(Float.NaN))
        assertEquals(listOf(0, 128, 255), VoiceWaveform.samples(listOf(0.0, 0.5, 1.0)))
        assertEquals(100, VoiceWaveform.samples(List(1000) { 0.5 }).size)
        assertEquals(listOf(1.0, 3.0), VoiceWaveform.resample(listOf(0.0, 2.0, 2.0, 4.0), 2))
        assertEquals(listOf(1.0, 1.0, 2.0, 2.0), VoiceWaveform.resample(listOf(1.0, 2.0), 4))
        assertEquals(List(30) { 0.3 }, VoiceWaveform.bars(null, 30))
        assertEquals(0.08, VoiceWaveform.bars(Bytes.ofUnsigned(listOf(0, 0)), 2)[0])
    }

    @Test
    fun `the mic's hold, slide, lock and release`() {
        var gesture = VoiceHoldGesture().press()!!
        assertNull(gesture.press())
        val started = gesture.holdDelayPassed()
        assertEquals(VoiceHoldGesture.Event.StartRecording, started.event)
        gesture = started.gesture
        val slid = gesture.slide(SlideOffset(-55f, 10f))
        assertEquals(0.5, slid.gesture.cancelProgress, 1e-6)
        assertEquals(0.0, slid.gesture.lockProgress)
        assertEquals(VoiceHoldGesture.Event.Cancel, slid.gesture.slide(SlideOffset(-110f, 0f)).event)
        val locked = gesture.slide(SlideOffset(0f, -80f))
        assertEquals(VoiceHoldGesture.Event.Lock, locked.event)
        assertEquals(VoiceHoldGesture.Phase.Locked, locked.gesture.phase)
        assertEquals(VoiceHoldGesture.Event.Send, locked.gesture.sendLocked(3.seconds).event)
        assertEquals(VoiceHoldGesture.Event.Hint, gesture.release(500.milliseconds).event)
        assertEquals(VoiceHoldGesture.Event.Send, gesture.release(1.seconds).event)
        assertEquals(VoiceHoldGesture.Event.Hint, VoiceHoldGesture().press()!!.release(5.seconds).event)
        assertEquals(VoiceHoldGesture.Event.Cancel, locked.gesture.deleteLocked().event)
        assertNull(VoiceHoldGesture().release(1.seconds).event)
    }

    @Test
    fun `playback rates cycle and read like the locale`() {
        assertEquals(VoicePlaybackRate.OneAndHalf, VoicePlaybackRate.Normal.next)
        assertEquals(VoicePlaybackRate.Normal, VoicePlaybackRate.Double.next)
        assertEquals("1,5×", VoicePlaybackRate.OneAndHalf.label(Locale.forLanguageTag("tr")))
        assertEquals("1.5×", VoicePlaybackRate.OneAndHalf.label(Locale.ENGLISH))
        assertEquals("2×", VoicePlaybackRate.Double.label(Locale.ENGLISH))
        assertEquals(42_000L, VoiceRecording(Bytes.Empty, 42.seconds, Bytes.Empty).durationMilliseconds)
    }

    @Test
    fun `a pick leaves as one message per item, the caption on the first photo`() {
        val video = PickedMediaItem(PickedMediaItem.Kind.Video, PickedMediaItem.Source.Artwork(DesignArtwork.SunsetVideo))
        val photo = PickedMediaItem(PickedMediaItem.Kind.Photo, PickedMediaItem.Source.Artwork(DesignArtwork.NightPier))
        val messages = PickedMediaPlan.messages(listOf(video, photo, photo), "  Dünkü yürüyüşten  ")
        assertEquals(listOf(null, "Dünkü yürüyüşten", null), messages.map { it.caption })
        assertEquals(10, PickedMediaPlan.messages(List(14) { photo }, null).size)
        assertEquals(listOf<String?>(null), PickedMediaPlan.messages(listOf(photo, photo), "x", viewOnce = true).map { it.caption })
        assertNull(PickedMediaPlan.usableCaption("x".repeat(ContentLimits.MAX_CAPTION_BYTES + 1)))
        assertNull(PickedMediaPlan.usableCaption(" \n "))
    }

    @Test
    fun `memory playback answers clamped byte ranges`() {
        assertEquals(0 until 10, MemoryMediaRanges.byteRange(0, 10, toEnd = false, total = 100))
        assertEquals(90 until 100, MemoryMediaRanges.byteRange(90, 50, toEnd = false, total = 100))
        assertEquals(40 until 100, MemoryMediaRanges.byteRange(40, 0, toEnd = true, total = 100))
        assertNull(MemoryMediaRanges.byteRange(100, 1, toEnd = false, total = 100))
        assertNull(MemoryMediaRanges.byteRange(-1, 1, toEnd = false, total = 100))
        assertNull(MemoryMediaRanges.byteRange(5, 0, toEnd = false, total = 100))
    }
}
