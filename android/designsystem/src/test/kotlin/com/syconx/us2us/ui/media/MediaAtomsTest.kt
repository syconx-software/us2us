package com.syconx.us2us.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.syconx.us2us.ui.tokens.DesignSpec
import javax.imageio.ImageIO
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.bubbles.BubbleImageCache
import com.syconx.us2us.ui.bubbles.BubbleMediaView
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The media atoms' rules and renders that need no screenshot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class MediaAtomsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theArtworkRendersAtItsOwnProportions() {
        val photo = DesignArtworkBitmap.render(DesignArtwork.NightPier, 1_536)
        assertEquals(1_536, photo.width)
        assertEquals(1_024, photo.height)
        val poster = DesignArtworkBitmap.render(DesignArtwork.SunsetVideo, 1_280)
        assertEquals(1_280, poster.width)
        assertEquals(720, poster.height)
        // The night pier is dark at the top, lit around its horizon; the sunset glows at 62 % / 58 %.
        val top = Color(photo.getPixel(10, 10))
        assertTrue("night sky $top", top.red < 0.2f && top.blue > top.red)
        val sun = Color(poster.getPixel((1_280 * 0.62f).toInt(), (720 * 0.58f).toInt()))
        assertTrue("sun $sun", sun.red > 0.9f && sun.green > 0.75f)
    }

    @Test
    fun theFixturesGetAJpegOfTheArtwork() {
        val jpeg = runBlocking { BitmapDesignArtworkEncoder().jpeg(DesignArtwork.NightPier, 1_536) }
        assertNotNull(jpeg)
        val decoded = BitmapFactory.decodeByteArray(jpeg!!.toByteArray(), 0, jpeg.size)
        assertEquals(1_536, decoded.width)
        assertNull(runBlocking { BitmapDesignArtworkEncoder().mp4(DesignArtwork.SunsetVideo) })
    }

    @Test
    fun everyMediaGlyphHasInkInsideItsBox() {
        for (glyph in MediaGlyph.entries) {
            // The bounds include the arcs' control points, which may stand a little outside the grid.
            val bounds = glyph.path(256f).getBounds()
            assertTrue("$glyph: $bounds", bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 272f && bounds.bottom <= 256f)
            assertTrue("$glyph is empty", bounds.width > 40f && bounds.height > 40f)
        }
        assertTrue(MediaGlyph.Pause.isFilled && MediaGlyph.Stop.isFilled && !MediaGlyph.Download.isFilled)
    }

    @Test
    fun theMediaActivityTracksOneOpeningFile() {
        val activity = ConversationMediaActivity()
        val a = MessageId("a")
        val b = MessageId("b")
        activity.begin(a)
        assertTrue(activity.isOpening(a))
        activity.end(b)
        assertTrue(activity.isOpening(a))
        activity.end(a)
        assertFalse(activity.isOpening(a))
    }

    @Test
    fun theSavedChipIsOnlyOnOwnSavedMedia() {
        fun message(content: MessageContent, direction: MessageDirection, saved: Boolean = true) = Message(
            id = MessageId("x"),
            direction = direction,
            sentAt = DesignFixtures.referenceDate,
            content = content,
            savedByPeer = saved,
        )
        val photo = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null)
        val file = MessageContent.File("a.pdf", 1, "PDF", 1)
        assertTrue(SavedMediaChip.isShown(message(photo, MessageDirection.Outgoing)))
        assertTrue(SavedMediaChip.isShown(message(file, MessageDirection.Outgoing)))
        assertFalse(SavedMediaChip.isShown(message(photo, MessageDirection.Incoming)))
        assertFalse(SavedMediaChip.isShown(message(photo, MessageDirection.Outgoing, saved = false)))
        assertFalse(SavedMediaChip.isShown(message(MessageContent.Text("x"), MessageDirection.Outgoing)))
        assertFalse(SavedMediaChip.isShown(message(MessageContent.Deleted, MessageDirection.Outgoing)))
        assertTrue(message(MessageContent.Text("x"), MessageDirection.Incoming, saved = false).copy(reactions = listOf(Reaction("👍"))).showsBubbleChips)
    }

    @Test
    fun aBubblePictureIsDecodedOnceAtItsSizeAndDroppedOnClear() {
        BubbleImageCache.clear()
        val file = File.createTempFile("bubble", ".png")
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 120, 40)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri: URI = file.toURI()
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                Box(Modifier.background(Color.Black)) {
                    BubbleMediaView(MediaSource.File(uri), DpSize(258.dp, 176.dp), Modifier.testTag("media"))
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { BubbleImageCache.image(uri, 774) != null }
        val image = BubbleImageCache.image(uri, 774)!!
        // 258 dp at 3× = 774 px on the long side, the proportions kept.
        assertEquals(774, image.width)
        assertEquals(581, image.height)
        assertEquals(1, BubbleImageCache.size)
        BubbleImageCache.clear()
        assertEquals(0, BubbleImageCache.size)
        // Low memory or the interface going away empties it too.
        BubbleImageCache.decode(ApplicationProvider.getApplicationContext(), uri, 100)
        ApplicationProvider.getApplicationContext<android.app.Application>().onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(0, BubbleImageCache.size)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun aFrozenClockHoldsTheIndeterminateRingAtAQuarter() {
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                CompositionLocalProvider(LocalMediaClock provides DesignClock.frozen(DesignFixtures.referenceDate)) {
                    Box(Modifier.size(40.dp).background(Color.Black).testTag("ring")) {
                        MediaProgressRing(fraction = null, modifier = Modifier.size(40.dp), tint = Color.White, track = Color.Transparent)
                    }
                }
            }
        }
        compose.waitForIdle()
        val file = DesignSpec.shotFile("media-ring-frozen.png")
        compose.onNodeWithTag("ring").captureRoboImage(file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val image = ImageIO.read(file)
        fun inkIn(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (y in y0 until y1) for (x in x0 until x1) if ((image.getRGB(x, y) shr 8 and 0xFF) > 128) return true
            return false
        }
        val half = image.width / 2
        // A quarter from 12 o'clock clockwise: ink at the top right, none at the bottom left.
        assertTrue("top-right quarter is drawn", inkIn(half, 0, image.width, half))
        assertFalse("bottom-left quarter is empty", inkIn(0, half + 4, half - 4, image.height))
    }
}
