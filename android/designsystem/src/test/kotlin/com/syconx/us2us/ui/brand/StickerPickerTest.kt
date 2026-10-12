@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.syconx.us2us.ui.brand

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sticker picker driven like a user: send, packs, the TR/EN switch, recents, the preview and close. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class StickerPickerTest {
    @get:Rule
    val host = BrandShots.specimenHost()

    private val picked = mutableListOf<StickerSelection>()
    private val previewed = mutableListOf<BrandStickerSelection>()
    private var closed = 0

    private fun open(lang: String = "tr", recents: List<BrandStickerSelection> = emptyList()) {
        host.render(ShotTheme.Dark, lang) {
            BrandStickerPicker(
                recents = recents,
                onSend = { picked += StickerSelection(it.sticker.id, it.language.wireCode) },
                onPreview = { previewed += it },
                onClose = { closed += 1 },
            )
        }
    }

    private fun node(tag: String) = host.compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun exists(tag: String) = host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTapSendsTheStickerInTheAppsLanguage() {
        open()
        node(StickerPickerTags.cell("1.-")).performClick()
        host.advance(300)
        assertEquals(listOf(StickerSelection(1, "tr")), picked)
    }

    @Test
    fun theWordsPackSwitchesLanguage() {
        open()
        // Cups have no words: the switch is not offered.
        assertTrue(node(StickerPickerTags.LANGUAGE).fetchSemanticsNode().config.contains(SemanticsActions.OnClick).not())
        node(StickerPickerTags.tab(StickerCatalog.WORDS_PACK_ID)).performClick()
        host.advance(500)
        assertTrue(exists(StickerPickerTags.cell("101.tr")))
        node(StickerPickerTags.languageOption(StickerLanguage.English)).performClick()
        host.advance(500)
        assertTrue(exists(StickerPickerTags.cell("101.en")))
        assertEquals("İngilizce", node(StickerPickerTags.LANGUAGE).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        node(StickerPickerTags.cell("101.en")).performClick()
        host.advance(300)
        assertEquals(listOf(StickerSelection(101, "en")), picked)
    }

    @Test
    fun englishAppShowsEnglishWords() {
        open(lang = "en")
        node(StickerPickerTags.tab(StickerCatalog.WORDS_PACK_ID)).performClick()
        host.advance(500)
        assertTrue(exists(StickerPickerTags.cell("103.en")))
    }

    @Test
    fun recentsComeFirstWithoutRepeats() {
        val hello = BrandStickerSelection(requireNotNull(StickerCatalog.sticker(101)), StickerLanguage.English)
        val cup = BrandStickerSelection(requireNotNull(StickerCatalog.sticker(1)), StickerLanguage.Turkish)
        open(recents = listOf(hello, cup, hello))
        assertEquals(1, host.compose.onAllNodesWithTag(StickerPickerTags.recent("101.en"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(exists(StickerPickerTags.recent("1.-")))
        // A recent keeps the language it was sent in.
        node(StickerPickerTags.recent("101.en")).performClick()
        host.advance(300)
        assertEquals(listOf(StickerSelection(101, "en")), picked)
    }

    @Test
    fun touchAndHoldPreviewsThenSends() {
        open()
        node(StickerPickerTags.cell("3.-")).performTouchInput { longClick(durationMillis = 600) }
        host.advance(800)
        assertEquals(3, previewed.single().sticker.id)
        node(StickerPickerTags.PREVIEW).assertIsDisplayed()
        assertTrue(picked.isEmpty())
        node(StickerPickerTags.PREVIEW_SEND).performClick()
        host.advance(600)
        assertEquals(listOf(StickerSelection(3, "tr")), picked)
        assertTrue(!exists(StickerPickerTags.PREVIEW))
    }

    @Test
    fun thePreviewActionForTalkBack() {
        open()
        val cell = node(StickerPickerTags.cell("4.-")).fetchSemanticsNode()
        val actions = cell.config[SemanticsActions.CustomActions]
        assertEquals("Önizle", actions.single().label)
        host.preview("4.-")
        host.advance(800)
        assertEquals(4, previewed.single().sticker.id)
        node(StickerPickerTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun closeIsTheCallers() {
        open()
        node(StickerPickerTags.CLOSE).performClick()
        host.advance(100)
        assertEquals(1, closed)
        assertNull(previewed.firstOrNull())
    }
}
