package com.syconx.us2us.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.ChatBackground
import com.syconx.us2us.domain.ChatBackgroundPreset
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.theme.U2Theme
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
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

/** “Sohbet arka planı”: a choice is only previewed until “Kaydet”, and it belongs to this chat alone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class ChatBackgroundSheetTest {
    @get:Rule val compose = createComposeRule()

    private val service = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        english = true,
        latency = Duration.ZERO,
    )
    private val deniz = ChatId("deniz")
    private val kaan = ChatId("kaan")
    private var closed = 0

    private fun show(messenger: MessengerService = service) {
        compose.setContent {
            U2Theme(dark = true) { ChatBackgroundContent(deniz, messenger) { closed += 1 } }
        }
        compose.waitForIdle()
    }

    private fun stored(chat: ChatId): ChatBackground = runBlocking { service.chatBackground(chat) }

    @Test fun aSwatchIsOnlyPreviewedUntilSaveAndThenBelongsToThisChatAlone() {
        show()
        compose.onNodeWithTag(ChatBackgroundTags.swatch(ChatBackgroundPreset.Sage)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatBackgroundTags.swatch(ChatBackgroundPreset.Sage)).assertIsSelected()
        assertEquals(ChatBackground.Standard, stored(deniz))
        assertEquals(0, service.chatBackgroundRevision.value)

        compose.onNodeWithTag(ChatBackgroundTags.SAVE).performClick()
        compose.waitUntil { closed == 1 }
        assertEquals(ChatBackground.Preset(ChatBackgroundPreset.Sage), stored(deniz))
        assertEquals(ChatBackground.Standard, stored(kaan))
        assertEquals(1, service.chatBackgroundRevision.value)
    }

    @Test fun saveWithNothingChangedJustCloses() {
        show()
        compose.onNodeWithTag(ChatBackgroundTags.SAVE).performClick()
        compose.waitUntil { closed == 1 }
        assertEquals(0, service.chatBackgroundRevision.value)
    }

    @Test fun itOpensOnTheSavedChoiceAndTheDefaultSwatchReturnsToTheAppsOwn() {
        runBlocking { service.setChatBackground(ChatBackground.Preset(ChatBackgroundPreset.Dusk), deniz) }
        show()
        compose.onNodeWithTag(ChatBackgroundTags.swatch(ChatBackgroundPreset.Dusk)).assertIsSelected()
        compose.onNodeWithTag(ChatBackgroundTags.STANDARD).performClick()
        compose.onNodeWithTag(ChatBackgroundTags.SAVE).performClick()
        compose.waitUntil { closed == 1 }
        assertEquals(ChatBackground.Standard, stored(deniz))
    }

    @Test fun aSaveThatFailsStaysOpenAndSaysSo() {
        show(object : MessengerService by service {
            override suspend fun setChatBackground(background: ChatBackground, chat: ChatId) = false
        })
        compose.onNodeWithTag(ChatBackgroundTags.swatch(ChatBackgroundPreset.Rose)).performClick()
        compose.onNodeWithTag(ChatBackgroundTags.SAVE).performClick()
        compose.waitUntil { compose.onAllNodesWithProblem().isNotEmpty() }
        assertEquals(0, closed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithProblem() =
        onAllNodes(androidx.compose.ui.test.hasText("The background could not be saved. Try again.")).fetchSemanticsNodes()

    @Test fun thePreparedPhotoIsSmallerUprightJpegAndAnythingElseIsRefused() {
        val source = Bitmap.createBitmap(3_200, 2_000, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val jpeg = ChatBackgroundPhoto.prepare(png)
        assertNotNull(jpeg)
        assertTrue(jpeg!!.size <= ChatBackgroundPhoto.MAX_BYTES)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertEquals(ChatBackgroundPhoto.MAX_SIDE, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals(1_000, minOf(bounds.outWidth, bounds.outHeight))
        assertFalse(String(jpeg, Charsets.ISO_8859_1).contains("Exif"))
        assertNull(ChatBackgroundPhoto.prepare("not an image".toByteArray()))
    }

    @Test fun thePresetIdsMatchIos() {
        assertEquals(
            listOf("mist", "sage", "sand", "rose", "dusk", "ocean", "moss", "peach"),
            ChatBackgroundPreset.entries.map { it.id },
        )
    }
}
