package com.syconx.us2us.ui.icons

import androidx.compose.ui.geometry.Rect
import com.syconx.us2us.shot.ShotPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bundled glyphs are exactly icons.md's list, and each one parses into a drawable outline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhosphorGlyphTest {
    /** The invite channel glyphs (Invite.xcassets): bundled by the feature, not listed in icons.md. */
    private val inviteGlyphs = setOf(PhosphorGlyph.EnvelopeSimple, PhosphorGlyph.WhatsappLogo)

    @Test
    fun theGlyphsAreExactlyTheDesignsList() {
        val table = File(ShotPaths.designDir, "icons.md").readLines()
        val row = Regex("""^\| `([a-z0-9-]+)` \| (regular|fill) \|""")
        val listed = table.mapNotNull { row.find(it) }.map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertEquals("icons.md lists 57 glyph/weight combinations", 57, listed.size)
        val bundled = PhosphorGlyph.entries
            .filter { it !in inviteGlyphs }
            .flatMap { glyph -> glyph.availableWeights.map { glyph.raw to it.name.lowercase() } }
            .toSet()
        assertEquals(listed, bundled)
        assertEquals(setOf(PhosphorWeight.Regular), PhosphorGlyph.EnvelopeSimple.availableWeights)
        assertEquals(setOf(PhosphorWeight.Fill), PhosphorGlyph.MapPin.availableWeights)
        assertEquals(setOf(PhosphorWeight.Regular, PhosphorWeight.Fill), PhosphorGlyph.Ghost.availableWeights)
        assertEquals(PhosphorGlyph.XCircle, PhosphorGlyph.fromRaw("x-circle"))
        assertEquals("ph-checks-fill", PhosphorGlyph.Checks.assetName(PhosphorWeight.Fill))
    }

    @Test
    fun everyGlyphParsesInsideItsGrid() {
        for (glyph in PhosphorGlyph.entries) {
            for (weight in glyph.availableWeights) {
                val vector = glyph.imageVector(weight)
                assertEquals(256f, vector.viewportWidth)
                assertEquals(256f, vector.viewportHeight)
                val bounds: Rect = glyph.path(weight).getBounds()
                assertTrue("${glyph.raw} ($weight) is empty", bounds.width > 64f || bounds.height > 64f)
                assertTrue("${glyph.raw} ($weight) leaves the grid: $bounds", bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 256f && bounds.bottom <= 256f)
                // Built once.
                assertTrue(vector === glyph.imageVector(weight))
            }
        }
    }

    @Test
    fun aWeightTheDesignDoesNotUseIsRefused() {
        try {
            PhosphorGlyph.MapPin.imageVector(PhosphorWeight.Regular)
            fail("map-pin is bundled in fill only")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }
}
