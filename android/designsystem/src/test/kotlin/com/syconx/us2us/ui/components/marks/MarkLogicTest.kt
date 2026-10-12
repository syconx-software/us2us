package com.syconx.us2us.ui.components.marks

import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.components.AvatarImageCache
import com.syconx.us2us.ui.components.AvatarPhotoDecoder
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.GhostIndicatorState
import com.syconx.us2us.ui.components.LinePacketTiming
import com.syconx.us2us.ui.components.QrModules
import com.syconx.us2us.ui.components.U2Ticker
import com.syconx.us2us.ui.components.avatarInitial
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rules behind the marks: packet keyframes, QR codes, Ghost states, initials, photos and the ticker. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MarkLogicTest {
    @Test
    fun packetsStandWhereTheReferencesPausedThem() {
        // 2 s in: the left-to-right packet has travelled 60 % of the bar (4.1: centre 54.19 on 29…71;
        // 6.1: centre 226 on 76…326) at full opacity; the reverse one has not started.
        val forward = LinePacketTiming.state(LinePacketTiming.FROZEN_TIME, 0.0)
        assertEquals(0.6, forward.position, 0.005)
        assertEquals(1.0, forward.opacity, 1e-9)
        val reverse = LinePacketTiming.state(LinePacketTiming.FROZEN_TIME, LinePacketTiming.REVERSE_DELAY)
        assertEquals(0.0, reverse.opacity, 1e-9)
        assertEquals((54.19 - 29) / 42, forward.position, 0.01)
    }

    @Test
    fun packetKeyframes() {
        val base = 6_000_000.0 // a wall-clock time at the start of a period: both packets are running
        assertEquals(0.0, LinePacketTiming.state(base, 0.0).opacity, 1e-9)
        assertEquals(0.5, LinePacketTiming.state(base + 0.18, 0.0).opacity, 0.01)
        assertEquals(1.0, LinePacketTiming.state(base + 1.0, 0.0).opacity, 1e-9)
        assertEquals(0.0, LinePacketTiming.state(base + 3.6, 0.0).opacity, 1e-9)
        assertEquals(1.0, LinePacketTiming.state(base + 3.6, 0.0).position, 1e-9)
        assertEquals(0.5, LinePacketTiming.state(base + 1.8, 0.0).position, 1e-6)
        // Same phase one period later.
        assertEquals(LinePacketTiming.state(base + 2.2, 3.0), LinePacketTiming.state(base + 8.2, 3.0))
    }

    @Test
    fun theSafetyCardIsTheRealCode() {
        val text = SafetyQRCodec.encode(DesignFixtures.safetyNumberPayload)
        val modules = QrModules.of(text)
        assertNotNull(modules)
        assertEquals("60 bytes → version 5 (37 × 37) with error correction M", 37, modules!!.size)
        assertEquals(modules, QrModules.cached(text))
        assertSame(QrModules.cached(text), QrModules.cached(text))
        // No quiet zone: the finder patterns touch the edges (7 × 7, dark ring).
        for (corner in listOf(0 to 0, 0 to modules.size - 7, modules.size - 7 to 0)) {
            val (row, column) = corner
            for (i in 0 until 7) {
                assertTrue(modules[row, column + i])
                assertTrue(modules[row + 6, column + i])
                assertTrue(modules[row + i, column])
                assertTrue(modules[row + i, column + 6])
            }
            assertFalse(modules[row + 1, column + 1])
            assertTrue(modules[row + 3, column + 3])
        }
    }

    @Test
    fun contactCodesEncode() {
        val modules = QrModules.of(ContactCode.encode("selin_n"))
        assertNotNull(modules)
        assertTrue(modules!!.size in 21..41)
        assertEquals(modules, QrModules.of(ContactCode.encode("selin_n")))
    }

    @Test
    fun ghostIndicatorStates() {
        val now = DesignFixtures.referenceDate
        val chats = DesignFixtures.chats(now, ghostUrgent = false, english = false)
        assertEquals(GhostIndicatorState.Calm, GhostIndicatorState.of(chats[0].ghost, now))
        assertEquals(GhostIndicatorState.Off, GhostIndicatorState.of(GhostState.Off, now))
        val urgent = DesignFixtures.chats(now, ghostUrgent = true, english = false)[0].ghost
        assertEquals(GhostIndicatorState.Urgent, GhostIndicatorState.of(urgent, now))
        val noDeadline = GhostState(isEnabled = true, period = GhostPeriod.OneDay, deadline = null)
        assertEquals(GhostIndicatorState.Off, GhostIndicatorState.of(noDeadline, now))
    }

    @Test
    fun initialsAndSizes() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("D", avatarInitial("deniz"))
            assertEquals("not the Turkish dotted İ", "I", avatarInitial("ilgaz"))
            assertEquals("", avatarInitial(""))
        } finally {
            Locale.setDefault(locale)
        }
        assertEquals(listOf(64, 52, 50, 48, 44, 40, 38), AvatarSize.entries.map { it.diameter.value.toInt() })
        assertEquals(listOf(23f, 19f, 18f, 17f, 16f, 14f, 14f), AvatarSize.entries.map { it.letterStyle.size })
        assertEquals(11f, AvatarSize.Header.onlineDot.value)
        assertEquals(2f, AvatarSize.Header.onlineRing.value)
        assertEquals(AvatarPalette.D, AvatarPalette.fromRaw("d"))
    }

    @Test
    fun photosDecodeOnlyWhenTheyAreProfilePhotos() {
        val photo = requireNotNull(DesignAvatars.photoAvatar.photo)
        val bytes = photo.data.toByteArray()
        val bitmap = AvatarPhotoDecoder.decode(bytes, photo.contentType)
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertNull("the type must match the bytes", AvatarPhotoDecoder.decode(bytes, "image/heic"))
        assertNull(AvatarPhotoDecoder.decode(bytes, "image/png"))
        assertNull(AvatarPhotoDecoder.decode(byteArrayOf(1, 2, 3), "image/jpeg"))
        runBlocking {
            AvatarImageCache.removeAll()
            val image = AvatarImageCache.image(photo)
            assertNotNull(image)
            assertSame(image, AvatarImageCache.cached(photo))
            val broken = AvatarPhotoData(Bytes.wrap(byteArrayOf(-1, -40, -1, 0)), "image/jpeg")
            assertNull(AvatarImageCache.image(broken))
            assertNull(AvatarImageCache.cached(broken))
            AvatarImageCache.removeAll()
            assertNull(AvatarImageCache.cached(photo))
        }
    }

    @Test
    fun theTickerFollowsTheClockOnlyWhileItRuns() {
        var wall = Instant.parse("2026-10-08T15:52:00Z")
        val clock = DesignClock.running(from = DesignFixtures.referenceDate, wall = { wall })
        val start = clock.now
        val ticker = U2Ticker(clock, interval = 10.milliseconds)
        assertEquals(start, ticker.now)
        runBlocking {
            withTimeout(5_000) {
                wall = wall.plusSeconds(30)
                val job = launch { ticker.run() }
                delay(60)
                assertEquals(start.plusSeconds(30), ticker.now)
                job.cancel()
            }
        }
        wall = wall.plusSeconds(30)
        assertEquals("stopped: no tick", start.plusSeconds(30), ticker.now)

        val frozen = U2Ticker(DesignClock.frozen(DesignFixtures.referenceDate))
        runBlocking { withTimeout(1_000) { frozen.run() } } // returns at once
        assertEquals(DesignFixtures.referenceDate, frozen.now)
    }
}
