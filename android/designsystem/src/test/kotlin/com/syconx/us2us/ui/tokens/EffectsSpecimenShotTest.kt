package com.syconx.us2us.ui.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.lineGradient
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.theme.u2Shadow
import com.syconx.us2us.ui.theme.u2ShadowMd
import com.syconx.us2us.ui.type.U2Text
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The effects on one page (dark and light): CSS glows outside the box only, inner strokes,
 * rings, `shadowMd`, the line gradient and a header backdrop over content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class EffectsSpecimenShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun effectsDark() = specimen(dark = true)

    @Test
    fun effectsLight() = specimen(dark = false)

    private fun specimen(dark: Boolean) {
        compose.setContent { U2Theme(dark = dark, reduceMotion = false) { Specimen() } }
        // Robolectric does not render RenderEffects (not even Compose's own Modifier.blur), so the
        // header's blur shows only on a device; here it is the tint over the recorded content.
        val file = Shots.capture(compose, "effects-${if (dark) "dark" else "light"}")
        val image = ImageIO.read(file)
        val bg = (if (dark) U2ColorScheme.Dark else U2ColorScheme.Light).bg
        val bgArgb = (0xFF shl 24) or ((bg.red * 255 + 0.5f).toInt() shl 16) or ((bg.green * 255 + 0.5f).toInt() shl 8) or (bg.blue * 255 + 0.5f).toInt()
        // The outline CTA (x 24…378, y 40…94 dp): its glow shows outside the box…
        val outside = image.getRGB(200 * 3, 34 * 3)
        assertTrue("glow outside the box", outside != bgArgb)
        // …but not inside it (CSS box-shadow is clipped to the outside): the centre is bg.
        assertEquals("no glow inside the box", bgArgb, image.getRGB(60 * 3, 67 * 3))
    }

    @Composable
    private fun Specimen() {
        val colors = U2Theme.colors
        val source = rememberU2BackdropSource()
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            Column(Modifier.fillMaxSize().u2BackdropSource(source).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(40.dp))
                val button = RoundedCornerShape(U2Radius.button)
                Box(
                    Modifier.fillMaxWidth().height(U2Size.buttonPrimary)
                        .u2BoxShadow(button, U2BoxShadows.glowButton)
                        .u2InnerStroke(colors.accent, 1.dp, button),
                    contentAlignment = Alignment.Center,
                ) { U2Text("glowButton", U2TextStyles.button, color = colors.accent300) }
                Spacer(Modifier.height(36.dp))
                Row {
                    // Code dots: empty ring, filled with glow.
                    Box(Modifier.size(U2Size.codeDot).u2InnerStroke(colors.textTertiary, 1.5.dp, CircleShape))
                    Spacer(Modifier.width(U2Size.codeDotGap))
                    Box(Modifier.size(U2Size.codeDot).u2BoxShadow(CircleShape, U2BoxShadows.glowCodeDot).background(colors.accent400, CircleShape))
                    Spacer(Modifier.width(40.dp))
                    // Online dot with its cut-out ring over an avatar.
                    Box(Modifier.size(U2Size.avatarListRow)) {
                        Box(Modifier.matchParentSize().background(colors.accent900, CircleShape).u2Shadow(U2ShadowTokens.ringAccent, CircleShape))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(U2Size.onlineDotList)
                                .u2Shadow(U2ShadowTokens.cutoutRingList, CircleShape).background(colors.online, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(40.dp))
                    Box(Modifier.size(U2Size.iconButton).background(colors.fillGlass, CircleShape).u2Shadow(U2ShadowTokens.hairlineGlass, CircleShape))
                }
                Spacer(Modifier.height(36.dp))
                // Focused field: inner accent stroke + outer glow.
                val field = RoundedCornerShape(U2Radius.field)
                Box(Modifier.fillMaxWidth().height(U2Size.fieldUsername).u2Shadow(U2ShadowTokens.glowField, field).background(colors.surface, field))
                Spacer(Modifier.height(36.dp))
                Row {
                    // 5.3 menu with shadowMd; 6.1 QR card glow.
                    val menu = RoundedCornerShape(U2Radius.menu)
                    Box(Modifier.size(150.dp, 110.dp).u2ShadowMd(menu).background(colors.menuFill, menu))
                    Spacer(Modifier.width(24.dp))
                    val qr = RoundedCornerShape(U2Radius.qrCard)
                    Box(Modifier.size(150.dp, 110.dp).u2Shadow(U2ShadowTokens.glowQRCard, qr).background(colors.qrPaper, qr))
                }
                Spacer(Modifier.height(36.dp))
                // Line mark: gradient bar, a packet with its glow, the verified seal.
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.lineGradient))
                    Box(Modifier.offset(x = 120.dp).size(44.dp, 4.dp).u2BoxShadow(CircleShape, U2BoxShadows.glowPacket).background(Brush.horizontalGradient(listOf(colors.accent200.copy(alpha = 0f), colors.accent200, colors.accent200.copy(alpha = 0f))), CircleShape))
                    Box(Modifier.offset(x = 260.dp).size(24.dp).u2Shadow(U2ShadowTokens.glowSeal, CircleShape).background(colors.accent900, CircleShape))
                }
                Spacer(Modifier.height(36.dp))
                // Content for the backdrop: stripes.
                Column {
                    repeat(6) { i ->
                        Box(Modifier.fillMaxWidth().height(14.dp).background(if (i % 2 == 0) colors.accent400 else colors.online))
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            // A header over the stripes: blur(20) saturate(140 %) over bg·80 %.
            Box(
                Modifier.offset(y = 620.dp).fillMaxWidth().height(70.dp)
                    .u2Backdrop(U2BackdropStyle.Header, source = source),
                contentAlignment = Alignment.Center,
            ) { U2Text("header backdrop", U2TextStyles.navTitle) }
            // A banner without a source: tint only.
            val banner = RoundedCornerShape(U2Radius.card)
            Box(
                Modifier.offset(x = 24.dp, y = 730.dp).size(354.dp, 56.dp)
                    .u2Shadow(U2ShadowTokens.floatingBanner, banner)
                    .u2Backdrop(U2BackdropStyle.Banner, banner),
                contentAlignment = Alignment.Center,
            ) { U2Text("floatingBanner", U2TextStyles.label15) }
        }
    }
}
