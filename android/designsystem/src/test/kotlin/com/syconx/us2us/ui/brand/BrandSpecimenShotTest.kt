package com.syconx.us2us.ui.brand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.CatalogSticker
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole drawn set on specimen pages, in dark and light, for review against the iOS galleries:
 * build/shots/android/designsystem/brand/<theme>-tr/brand-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BrandSpecimenShotTest {
    @get:Rule
    val host = BrandShots.specimenHost()

    @Test
    fun stickersInTurkish() {
        for (theme in ShotTheme.entries) {
            val file = host.shoot("brand-stickers-tr", theme, "tr") {
                StickerSheet(StickerCatalog.all.map { BrandStickerSelection(it, StickerLanguage.Turkish) }, side = 70.dp)
            }
            assertTrue(file.isFile)
        }
    }

    @Test
    fun wordsInBothLanguages() {
        val words = StickerCatalog.all.filter(CatalogSticker::isText)
        for (theme in ShotTheme.entries) {
            host.shoot("brand-stickers-words", theme, "tr") {
                StickerSheet(
                    words.flatMap { listOf(BrandStickerSelection(it, StickerLanguage.Turkish), BrandStickerSelection(it, StickerLanguage.English)) },
                    side = 70.dp,
                )
            }
        }
    }

    @Test
    fun stickersLarge() {
        for (theme in ShotTheme.entries) {
            for ((page, ids) in StickerCatalog.all.map { it.id }.chunked(12).withIndex()) {
                host.shoot("brand-stickers-large-${page + 1}", theme, "tr") {
                    StickerSheet(ids.mapNotNull { id -> StickerCatalog.sticker(id)?.let { BrandStickerSelection(it, StickerLanguage.Turkish) } }, side = 120.dp, columns = 3)
                }
            }
        }
    }

    @Test
    fun avatars() {
        for (theme in ShotTheme.entries) {
            host.shoot("brand-avatars", theme, "tr") {
                U2Screen {
                    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 70.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (row in BrandAvatarCatalog.all.chunked(5)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (preset in row) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        BrandAvatarView(preset, 64.dp)
                                        U2Text("${preset.id}", U2TextStyles.monoTag, color = U2Theme.colors.textSecondary)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (preset in BrandAvatarCatalog.all.take(11)) BrandAvatarView(preset, 28.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (preset in BrandAvatarCatalog.all.drop(11)) BrandAvatarView(preset, 28.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (preset in BrandAvatarCatalog.all.take(4)) BrandAvatarView(preset, 96.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A grid of stickers with their ids. */
@Composable
private fun StickerSheet(selections: List<BrandStickerSelection>, side: Dp, columns: Int = 5) {
    U2Screen {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, top = 66.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (row in selections.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (selection in row) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BrandStickerView(selection, Modifier.size(side))
                            U2Text("${selection.id}", U2TextStyles.monoTag, color = U2Theme.colors.textSecondary)
                        }
                    }
                    Spacer(Modifier.size(0.dp))
                }
            }
            Box(Modifier.size(1.dp))
        }
    }
}
