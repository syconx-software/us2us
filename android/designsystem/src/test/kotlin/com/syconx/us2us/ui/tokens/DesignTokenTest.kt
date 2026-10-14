package com.syconx.us2us.ui.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The design system mirrors ios/design/tokens.json 1:1 (port of DesignTokenTests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DesignTokenTest {
    private val tokens: JSONObject = DesignSpec.json("tokens.json")

    private fun hexAlpha(value: String): Pair<Int, Float> {
        val parts = value.split("@")
        val rgb = parts[0].removePrefix("#").toInt(16)
        val alpha = if (parts.size > 1) parts[1].removeSuffix("%").toFloat() / 100f else 1f
        return rgb to alpha
    }

    private fun JSONObject.entries(): List<Pair<String, Any>> = keys().asSequence().map { it to get(it) }.toList()

    @Test
    fun everyColorTokenMatchesTokensJson() {
        val color = tokens.getJSONObject("color")
        var checked = 0
        for (group in listOf("base", "semantic")) {
            for ((name, value) in color.getJSONObject(group).entries()) {
                val entry = value as JSONObject
                val token = assertNotNullAndGet(U2ColorTokens.all[name], "missing colour token $name")
                val (dark, darkAlpha) = hexAlpha(entry.getString("dark"))
                val (light, lightAlpha) = hexAlpha(entry.getString("light"))
                assertEquals("$name dark", dark, token.dark)
                assertEquals("$name dark alpha", darkAlpha, token.darkAlpha, 0.001f)
                assertEquals("$name light", light, token.light)
                assertEquals("$name light alpha", lightAlpha, token.lightAlpha, 0.001f)
                checked++
            }
        }
        assertTrue(checked > 70)
        assertEquals(26 + 49 - 1, U2ColorTokens.all.size)
    }

    /** Semantic aliases are their definition: `ref · opacity`, or the sheet backdrop's colour-mix. */
    @Test
    fun semanticAliasesFollowTheirDefinition() {
        val color = tokens.getJSONObject("color")
        val base = color.getJSONObject("base")
        for ((name, value) in color.getJSONObject("semantic").entries()) {
            val entry = value as JSONObject
            val token = U2ColorTokens.all.getValue(name)
            if (entry.has("ref")) {
                val ref = U2ColorTokens.all.getValue(entry.getString("ref"))
                val opacity = entry.getDouble("opacity").toFloat()
                assertEquals("$name dark", ref.dark, token.dark)
                assertEquals("$name light", ref.light, token.light)
                assertEquals("$name dark alpha", ref.darkAlpha * opacity, token.darkAlpha, 0.001f)
                assertEquals("$name light alpha", ref.lightAlpha * opacity, token.lightAlpha, 0.001f)
            }
        }
        // color-mix(in srgb, bg 55 %, #05060c)
        fun mix(bg: Int, other: Int): Int = listOf(16, 8, 0).fold(0) { acc, shift ->
            val a = (bg shr shift) and 0xFF
            val b = (other shr shift) and 0xFF
            acc or (Math.round(a * 0.55 + b * 0.45).toInt() shl shift)
        }
        assertEquals(mix(hexAlpha(base.getJSONObject("bg").getString("dark")).first, 0x05060c), U2ColorTokens.sheetBackdrop.dark)
        assertEquals(mix(hexAlpha(base.getJSONObject("bg").getString("light")).first, 0x05060c), U2ColorTokens.sheetBackdrop.light)
        assertEquals(0x0e101a, U2ColorTokens.sheetBackdrop.dark)
        assertEquals(0x888991, U2ColorTokens.sheetBackdrop.light)
    }

    @Test
    fun themedColorsResolvePerScheme() {
        val text = U2ColorTokens.text
        assertEquals(0xe9 / 255f, text.components(dark = true)[0], 0.001f)
        assertEquals(0x29 / 255f, text.components(dark = false)[0], 0.001f)
        assertEquals(0.68f, U2ColorTokens.textSecondary.components(dark = true)[3], 0.001f)
        assertEquals(Color(0xFFE9E9ED), U2ColorScheme.Dark.text)
        assertEquals(Color(0xFF292B31), U2ColorScheme.Light.text)
        assertEquals(Color(0xFF161826), U2ColorScheme.Dark.bg)
        assertEquals(Color(0xFFF3F5FE), U2ColorScheme.Light.bg)
        assertEquals(0.68f, U2ColorScheme.Dark.textSecondary.alpha, 1 / 255f)
        assertEquals(Color(0xFFE9E9ED).copy(alpha = 0.68f), U2ColorScheme.Dark.textSecondary)
        assertEquals(Color(0xFF161826).copy(alpha = 0.8f), U2ColorScheme.Dark.headerFill)
        assertEquals(Color(0xFFF3F5FE).copy(alpha = 0.8f), U2ColorScheme.Light.headerFill)
        assertTrue(U2ColorScheme.Dark.isDark)
        assertTrue(!U2ColorScheme.Light.isDark)
        // The light theme inverts the tonal steps.
        assertEquals(U2ColorScheme.Dark.accent900, U2ColorScheme.Light.accent100)
        assertEquals(U2ColorScheme.Dark.neutral100, U2ColorScheme.Light.neutral900)
    }

    @Test
    fun fixedColorsMatchTokensJson() {
        val fixed = tokens.getJSONObject("color").getJSONObject("fixed")
        val byName = mapOf(
            "toggleKnob" to U2FixedColors.toggleKnob,
            "qrPaper" to U2FixedColors.qrPaper,
            "qrInk" to U2FixedColors.qrInk,
            "scrimBase" to U2FixedColors.scrimBase,
            "lockOverlay" to U2FixedColors.lockOverlay,
            "actionsOverlay" to U2FixedColors.actionsOverlay,
            "sheetShadow" to U2FixedColors.sheetShadow,
            "cameraBg" to U2FixedColors.cameraBg,
            "mediaViewerBg" to U2FixedColors.mediaViewerBg,
            "switcherBg" to U2FixedColors.switcherBg,
            "onMedia" to U2FixedColors.onMedia,
            "onCameraGlass" to U2FixedColors.onCameraGlass,
            "scanCorner" to U2FixedColors.scanCorner,
            "scanGlow" to U2FixedColors.scanGlow,
            "stickerRing" to U2FixedColors.stickerRing,
        )
        for ((name, color) in byName) {
            val (rgb, alpha) = hexAlpha(fixed.getJSONObject(name).getString("value"))
            assertEquals(name, U2ThemedColor.fixed(rgb, alpha).resolve(dark = true), color)
            assertEquals(name, color, U2ColorScheme.Dark.run { fixedByName(name) })
            assertEquals(name, color, U2ColorScheme.Light.run { fixedByName(name) })
        }
        assertEquals(Color(0xFF0C0D16), U2FixedColors.onMediaGlassBase)
    }

    private fun U2ColorScheme.fixedByName(name: String): Color = when (name) {
        "toggleKnob" -> toggleKnob
        "qrPaper" -> qrPaper
        "qrInk" -> qrInk
        "scrimBase" -> scrimBase
        "lockOverlay" -> lockOverlay
        "actionsOverlay" -> actionsOverlay
        "sheetShadow" -> sheetShadow
        "cameraBg" -> cameraBg
        "mediaViewerBg" -> mediaViewerBg
        "switcherBg" -> switcherBg
        "onMedia" -> onMedia
        "onCameraGlass" -> onCameraGlass
        "scanCorner" -> scanCorner
        "scanGlow" -> scanGlow
        "stickerRing" -> stickerRing
        else -> error(name)
    }

    @Test
    fun everyTypeStyleMatchesTokensJson() {
        val styles = tokens.getJSONObject("typography").getJSONObject("styles")
        val byName = U2TextStyles.all.associateBy { it.name }
        val families = mapOf("Doto" to U2FontFamily.Doto, "Martian Mono" to U2FontFamily.MartianMono, "Inter" to U2FontFamily.Inter)
        for ((name, value) in styles.entries()) {
            if (name == "displayPunctuation") continue
            val entry = value as JSONObject
            val style = assertNotNullAndGet(byName[name], "missing type style $name")
            assertEquals(name, families.getValue(entry.getString("family")), style.family)
            assertEquals(name, entry.getInt("weight"), style.weight)
            assertEquals(name, entry.getDouble("size").toFloat(), style.size, 0.001f)
            assertEquals(name, entry.getDouble("lineHeight").toFloat(), style.lineHeight, 0.001f)
            assertEquals(name, entry.optDouble("letterSpacing", 0.0).toFloat(), style.tracking, 0.001f)
            assertEquals(name, entry.optString("textCase") == "uppercase", style.uppercase)
            assertEquals(name, entry.optBoolean("monospacedDigits", false), style.monospacedDigits)
            // Display (Doto) moments and fixed glyphs keep their size at any font scale.
            if (style.family == U2FontFamily.Doto || name.startsWith("avatarInitial")) assertTrue(name, !style.scalesWithFontSize)
        }
        assertEquals(styles.length() - 1, U2TextStyles.all.size)
        assertTrue(U2TextStyles.bubbleTombstone.italic)
        assertTrue(!U2TextStyles.keypadDigit.scalesWithFontSize)
        assertTrue(U2TextStyles.bodyL.scalesWithFontSize)
    }

    @Test
    fun fontMetricsMatchTokensJsonAndTheBundledFiles() {
        val fonts = tokens.getJSONObject("typography").getJSONObject("fonts")
        val byName = mapOf("Doto" to U2FontFamily.Doto, "Martian Mono" to U2FontFamily.MartianMono, "Inter" to U2FontFamily.Inter)
        for ((name, family) in byName) {
            val entry = fonts.getJSONObject(name)
            assertEquals(name, entry.getInt("unitsPerEm"), family.unitsPerEm)
            assertEquals(name, entry.getInt("ascender"), family.ascender)
            assertEquals(name, entry.getInt("descender"), family.descender)
            assertEquals(name, entry.getInt("lineGap"), family.lineGap)
        }
        // The same files as the iOS app.
        val ios = File(DesignSpec.androidDir.parentFile, "ios/App/Resources/Fonts")
        val res = File(DesignSpec.androidDir, "designsystem/src/main/res/font")
        for ((iosName, androidName) in listOf(
            "Doto-Variable.ttf" to "doto_variable.ttf",
            "MartianMono-Variable.ttf" to "martian_mono_variable.ttf",
            "Inter-Variable.ttf" to "inter_variable.ttf",
        )) {
            assertTrue(androidName, File(ios, iosName).readBytes().contentEquals(File(res, androidName).readBytes()))
        }
    }

    @Test
    fun metricsMatchTokensJson() {
        val spacing = tokens.getJSONObject("spacing").getJSONObject("named")
        assertEquals(spacing.getDouble("screenGutterL").dpValue(), U2Spacing.screenGutterL)
        assertEquals(spacing.getDouble("chatGutter").dpValue(), U2Spacing.chatGutter)
        assertEquals(spacing.getDouble("groupedSeparatorInset").dpValue(), U2Spacing.groupedSeparatorInset)
        assertEquals(tokens.getJSONObject("spacing").getJSONArray("values").length(), U2Spacing.scale.size)

        val radius = tokens.getJSONObject("radius").getJSONObject("named")
        assertEquals(radius.getDouble("sheet").dpValue(), U2Radius.sheet)
        assertEquals(radius.getDouble("bubble").dpValue(), U2Radius.bubble)
        assertEquals(radius.getDouble("grabber").dpValue(), U2Radius.grabber)
        assertEquals(tokens.getJSONObject("radius").getJSONArray("values").length(), U2Radius.scale.size)

        val size = tokens.getJSONObject("size")
        assertEquals(DpSize(402.dp, 874.dp), U2Size.designScreen)
        assertEquals(size.getDouble("statusBarHeightInDesign").dpValue(), U2Size.designStatusBar)
        assertEquals(size.getDouble("homeIndicatorArea").dpValue(), U2Size.designHomeIndicator)
        assertEquals(size.getDouble("buttonPrimaryHeight").dpValue(), U2Size.buttonPrimary)
        assertEquals(size.getJSONObject("toggle").getDouble("w").dpValue(), U2Size.toggleWidth)
        assertEquals(size.getJSONObject("avatar").getDouble("listRow").dpValue(), U2Size.avatarListRow)
        assertEquals(DpSize(46.dp, 56.dp), U2Size.fileTile)
        assertEquals(DpSize(22.dp, 6.dp), U2Size.pageDotActive)
        assertEquals(116.dp, U2Size.faceIdDisc)

        val opacity = tokens.getJSONObject("opacity")
        assertEquals(opacity.getDouble("disabled").toFloat(), U2Opacity.disabled, 0.0001f)
        assertEquals(opacity.getDouble("coverContent").toFloat(), U2Opacity.coverContent, 0.0001f)

        assertEquals(20.dp, U2Blur.header)
        assertEquals(8.dp, U2Blur.mediaButton)
        assertEquals(9.dp, U2Blur.privacyCover)
        assertEquals(14.dp, U2Blur.contextBackdrop)
    }

    @Test
    fun shadowsAreParsedLayerByLayer() {
        val glowButton = U2ShadowTokens.glowButton.layers.single()
        assertEquals(false, glowButton.inset)
        assertEquals(32.dp, glowButton.blur)
        assertEquals((-8).dp, glowButton.spread)
        assertEquals(U2ColorTokens.accentGlow, glowButton.color)

        val field = U2ShadowTokens.glowField.layers
        assertEquals(2, field.size)
        assertEquals(true, field[0].inset)
        assertEquals(1.5.dp, field[0].spread)
        assertEquals(U2ColorTokens.accent, field[0].color)
        assertEquals(26.dp, field[1].blur)
        assertEquals((-10).dp, field[1].spread)

        val qr = U2ShadowTokens.glowQRCard.layers
        assertEquals(U2ColorTokens.strokeCard, qr[0].color)
        assertEquals(20.dp, qr[1].y)

        val md = U2ShadowTokens.shadowMd.layers
        assertEquals(U2ThemedColor(dark = 0x595d6c, light = 0xb2b6ca), md[0].color)
        assertEquals(U2ThemedColor(dark = 0x000000, darkAlpha = 0.55f, light = 0x292b31, lightAlpha = 0.10f), md[1].color)

        assertEquals(3.dp, U2ShadowTokens.cutoutRingList.layers.single().spread)
        assertEquals(2.dp, U2ShadowTokens.cutoutRingHeader.layers.single().spread)
        assertEquals(2.5.dp, U2ShadowTokens.cutoutRingReactionChip.layers.single().spread)
        assertEquals(U2ColorTokens.bg, U2ShadowTokens.cutoutRingList.layers.single().color)
        assertEquals(U2ColorTokens.accent700, U2ShadowTokens.ringAccentSelf.layers.single().color)
        assertEquals(U2ThemedColor.fixed(0x000000, 0.4f), U2ShadowTokens.mapPin.layers.single().color)
        assertTrue(U2ShadowTokens.mapPin.isDropShadowFilter)
        assertEquals(U2ThemedColor.fixed(0xf5f4ff), U2ShadowTokens.sticker.layers[0].color)
    }

    @Test
    fun scenePaletteMatchesTokensJson() {
        val palette = tokens.getJSONObject("scene3D").getJSONObject("palette")
        for ((theme, value) in listOf("dark" to U2Scene3DTokens.dark, "light" to U2Scene3DTokens.light)) {
            val entry = palette.getJSONObject(theme)
            assertEquals(theme, Color(0xFF000000 or hexAlpha(entry.getString("cup")).first.toLong()), value.cup)
            assertEquals(theme, Color(0xFF000000 or hexAlpha(entry.getString("rim")).first.toLong()), value.rim)
            assertEquals(theme, entry.getDouble("wireOp").toFloat(), value.wireOp, 0.0001f)
            assertEquals(theme, entry.getString("blending") == "additive", value.additiveBlending)
        }
        assertEquals(34f, U2Scene3DTokens.fovYDegrees, 0f)
        assertEquals(listOf(0f, 0.55f, 7.8f), U2Scene3DTokens.cameraPosition)
    }

    @Test
    fun motionTokensAreSoft() {
        assertEquals(0.5f, U2MotionTokens.Duration.screen, 0f)
        assertEquals(0.55f, U2MotionTokens.Duration.sheet, 0f)
        assertEquals(0.8f, U2MotionTokens.Duration.ambient, 0f)
        assertEquals(listOf(0.45f, 0f, 0.55f, 1f), U2MotionTokens.curveSoft)
        assertEquals(listOf(0f, 0f, 0.58f, 1f), U2MotionTokens.curveOut)
        assertEquals(6f, U2MotionTokens.LinePacket.duration, 0f)
        assertEquals(2.8f, U2MotionTokens.PulseRing.duration, 0f)
        assertEquals(2, U2MotionTokens.PulseRing.count)
    }

    /** The committed Kotlin is what the generator writes from the current tokens.json. */
    @Test
    fun generatedFilesAreUpToDate() {
        val script = File(DesignSpec.androidDir, "scripts/generate_tokens.py")
        val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("python3 not available", python != null)
        val process = ProcessBuilder(python, script.path, "--check").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("generator timed out", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    private fun Double.dpValue(): Dp = toFloat().dp

    private fun <T> assertNotNullAndGet(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }
}
