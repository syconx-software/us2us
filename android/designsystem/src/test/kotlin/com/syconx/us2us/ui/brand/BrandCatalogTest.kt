package com.syconx.us2us.ui.brand

import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.ui.tokens.U2ColorTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawn side of the built-in sets (iOS StickerCatalogTests' art and type checks, and
 * AvatarPresetTests): art for every sticker id, words that fit, and a distinct look for every
 * preset avatar.
 */
class BrandCatalogTest {
    @Test
    fun everyStickerHasArt() {
        for (sticker in StickerCatalog.all) assertNotNull(sticker.key, BrandStickerArtwork.artwork(sticker.id))
        assertNull(BrandStickerArtwork.artwork(999))
        assertNull(BrandStickerArtwork.artwork(0))
    }

    @Test
    fun stickersLeanALittle() {
        for (sticker in StickerCatalog.all) {
            val tilt = requireNotNull(BrandStickerArtwork.artwork(sticker.id)).tilt
            assertTrue("${sticker.key} leans $tilt°", tilt in -6f..6f)
        }
    }

    @Test
    fun wordsFitAPickerCell() {
        for (sticker in StickerCatalog.all.filter { it.isText }) {
            for (language in StickerLanguage.entries) {
                val lines = requireNotNull(sticker.lines)[language]
                assertTrue("${sticker.key} $language", lines.size in 1..2)
                assertTrue("${sticker.key} $language", lines.all { it.isNotEmpty() && BrandStickerType.length(it) <= 12 })
            }
        }
    }

    @Test
    fun fittedSizeIsArithmetic() {
        // The longest line fills the width: 72 / (8 × 0.58 + 0.02) for “Merhaba!”.
        assertEquals(72f / (8 * 0.58f + 0.02f), BrandStickerType.fittedSize(listOf("Merhaba!"), 72f, 40f, 30f), 0.001f)
        // Never above the maximum.
        assertEquals(30f, BrandStickerType.fittedSize(listOf("OK!"), 72f, 32f, 30f), 0.001f)
        // Two lines: the block's height decides (cap height + leading + room for accents).
        assertEquals(40f / (0.7f + 1f + 0.26f), BrandStickerType.fittedSize(listOf("Seni", "duyuyorum"), 400f, 40f, 99f), 0.001f)
        assertTrue(BrandStickerType.fittedSize(listOf("Teşekkürler"), 84f, 25f, 22f, tracking = -0.04f) > 12f)
        // Characters, not UTF-16 units.
        assertEquals(3, BrandStickerType.length("Şşş"))
    }

    @Test
    fun presetsAreTheDomainCatalogue() {
        assertEquals(AvatarPresetCatalog.all.map { it.id }, BrandAvatarCatalog.all.map { it.id })
        assertEquals(AvatarPresetCatalog.all.map { it.key }, BrandAvatarCatalog.all.map { it.key })
        assertEquals(AvatarPresetCatalog.all.map { it.label }, BrandAvatarCatalog.all.map { it.label })
        assertEquals("cup.headphones", BrandAvatarCatalog.preset(2)?.key)
        assertEquals(BrandAvatarAccessory.GhostSheet, BrandAvatarCatalog.preset(8)?.accessory)
        assertNull(BrandAvatarCatalog.preset(0))
        assertNull(BrandAvatarCatalog.preset(999))
        assertTrue(BrandAvatarCatalog.all.size in 16..24)
    }

    @Test
    fun noTwoPresetsLookTheSame() {
        val looks = BrandAvatarCatalog.all.map { listOf(it.backdrop, it.expression, it.accessory, it.cup) }
        assertEquals(BrandAvatarCatalog.all.size, looks.toSet().size)
        assertEquals(BrandAvatarCatalog.all.size, BrandAvatarCatalog.all.map { it.label.turkish }.toSet().size)
        assertEquals(BrandAvatarCatalog.all.size, BrandAvatarCatalog.all.map { it.label.english }.toSet().size)
    }

    @Test
    fun everyAccessoryIsWornAndTheBackdropsAreSharedOut() {
        val worn = BrandAvatarCatalog.all.map { it.accessory }.toSet()
        for (accessory in BrandAvatarAccessory.entries) assertTrue("$accessory is drawn but no preset wears it", accessory in worn)
        for (backdrop in BrandAvatarBackdrop.entries) {
            val count = BrandAvatarCatalog.all.count { it.backdrop == backdrop }
            assertTrue("$backdrop: $count", count in 1..4)
        }
    }

    @Test
    fun nocturneBackdropsAreTheInitialAvatarsPalettes() {
        assertEquals(U2ColorTokens.accent800, BrandAvatarBackdrop.Violet.token)
        assertEquals(U2ColorTokens.accent700, BrandAvatarBackdrop.Plum.token)
        assertEquals(U2ColorTokens.accent900, BrandAvatarBackdrop.Ink.token)
        assertEquals(U2ColorTokens.neutral800, BrandAvatarBackdrop.Slate.token)
        assertTrue(BrandAvatarBackdrop.Ink.needsLightHairline)
        assertFalse(BrandAvatarBackdrop.Mint.needsLightHairline)
    }

    @Test
    fun smallAvatarsLeaveTheExtrasOut() {
        assertEquals(BrandAvatarDetail.Compact, BrandAvatarDetail.of(28f))
        assertEquals(BrandAvatarDetail.Compact, BrandAvatarDetail.of(38f))
        assertEquals(BrandAvatarDetail.Full, BrandAvatarDetail.of(40f))
        assertEquals(BrandAvatarDetail.Full, BrandAvatarDetail.of(96f))
    }

    @Test
    fun affineTransformsReadLikeCoreGraphics() {
        // translatedBy / rotated / scaledBy act on the point first: scale, then rotate, then move.
        val t = Affine.translation(10, 20).rotated(Math.PI / 2).scaled(2, 2)
        val point = t.apply(androidx.compose.ui.geometry.Offset(1f, 0f))
        assertEquals(10f, point.x, 1e-4f)
        assertEquals(22f, point.y, 1e-4f)
        val back = t.inverted().apply(point)
        assertEquals(1f, back.x, 1e-4f)
        assertEquals(0f, back.y, 1e-4f)
        // concatenating: this, then the other.
        val moved = Affine.scale(3, 3).concatenating(Affine.translation(1, 1)).apply(androidx.compose.ui.geometry.Offset(1f, 1f))
        assertEquals(4f, moved.x, 1e-4f)
        assertEquals(4f, moved.y, 1e-4f)
    }

    @Test
    fun theShadowBlurKeepsItsMass() {
        val width = 61
        val values = FloatArray(width * width)
        values[30 * width + 30] = 1f
        StickerShadows.gaussian(values, width, width, 5f)
        assertEquals(1f, values.sum(), 0.01f)
        // Symmetric round the source.
        assertEquals(values[30 * width + 25], values[30 * width + 35], 1e-4f)
        assertEquals(values[25 * width + 30], values[35 * width + 30], 1e-4f)
        assertTrue(values[30 * width + 30] > values[30 * width + 36])
    }
}
