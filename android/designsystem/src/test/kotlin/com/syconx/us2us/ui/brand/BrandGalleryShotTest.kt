@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.syconx.us2us.ui.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The review galleries as the iOS captures name them (`-U2Gallery stickers` → `gallery_stickers`,
 * `avatars` → `gallery_avatars`), in dark and light, for `compare_shots.py` against
 * `android/build/ios-reference/`; and the picker sheet, its preview and the panel as specimens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BrandGalleryShotTest {
    @get:Rule
    val host = BrandShots.appHost()

    @Test
    fun stickerGallery() {
        for (theme in ShotTheme.entries) {
            val file = host.shoot("gallery_stickers", theme, "tr") { BrandStickerGallery() }
            assertTrue(file.isFile)
            host.compose.onNodeWithTag(BrandGalleryTags.STICKERS, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun avatarGallery() {
        for (theme in ShotTheme.entries) {
            host.shoot("gallery_avatars", theme, "tr") { BrandAvatarGallery() }
            host.compose.onNodeWithTag(BrandGalleryTags.AVATARS, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun avatarGalleryPicker() {
        for (theme in ShotTheme.entries) {
            host.render(theme, "tr") { BrandAvatarGallery() }
            host.compose.onNodeWithTag(BrandGalleryTags.AVATARS, useUnmergedTree = true)
            host.compose.onNode(androidx.compose.ui.test.hasText("Picker")).performClick()
            host.advance(1_000)
            host.compose.onNodeWithTag(AvatarPickerTags.PICKER, useUnmergedTree = true).assertIsDisplayed()
            host.capture("gallery_avatars-picker")
        }
    }
}

/** The picker in its two presentations, the preview, and English, for review (specimens). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class StickerPickerShotTest {
    @get:Rule
    val host = BrandShots.specimenHost()

    private val recents = listOf(1, 101, 205, 302).mapNotNull { id -> StickerCatalog.sticker(id)?.let { BrandStickerSelection(it, StickerLanguage.Turkish) } }

    @Test
    fun sheet() {
        for (lang in listOf("tr", "en")) {
            for (theme in ShotTheme.entries) {
                host.shoot("brand-picker-sheet", theme, lang) {
                    Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
                    U2Sheet(isPresented = true, onDismissRequest = {}, style = U2SheetStyle.Content) {
                        StickerPickerSheet(onPick = {}, onDismiss = {}, recents = recents)
                    }
                }
                host.compose.onNodeWithTag(StickerPickerTags.PICKER, useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }

    @Test
    fun wordsPackAndPreview() {
        for (theme in ShotTheme.entries) {
            host.render(theme, "tr") {
                Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
                U2Sheet(isPresented = true, onDismissRequest = {}, style = U2SheetStyle.Content) {
                    StickerPickerSheet(onPick = {}, onDismiss = {}, recents = recents)
                }
            }
            host.compose.onNodeWithTag(StickerPickerTags.tab(StickerCatalog.WORDS_PACK_ID), useUnmergedTree = true).performClick()
            host.advance(600)
            host.capture("brand-picker-words")
            host.preview("103.tr")
            host.advance(800)
            host.compose.onNodeWithTag(StickerPickerTags.PREVIEW, useUnmergedTree = true).assertIsDisplayed()
            host.capture("brand-picker-preview")
        }
    }

    @Test
    fun panel() {
        for (theme in ShotTheme.entries) {
            host.shoot("brand-picker-panel", theme, "tr") {
                Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
                    BrandStickerPicker(
                        recents = recents,
                        onSend = {},
                        presentation = StickerPickerPresentation.Panel(336.dp),
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/**
 * TalkBack's “Önizle” on the cell of [selectionId]. The state it writes is applied at once: on the
 * paused test clock nothing else would send the snapshot's apply notifications.
 */
internal fun ShotHost.preview(selectionId: String) {
    compose.onNodeWithTag(StickerPickerTags.cell(selectionId), useUnmergedTree = true).performCustomAccessibilityActionWithLabel("Önizle")
    compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
}
