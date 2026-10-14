package com.syconx.us2us.ui.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One colour token: a value for each theme (tokens.json › color).
 *
 * The dark value is the design's default. Screens never use literal colours, only
 * `U2Theme.colors.<token>`, which is this token resolved for the current theme.
 */
@Immutable
data class U2ThemedColor(
    /** 0xRRGGBB in the dark theme. */
    val dark: Int,
    val darkAlpha: Float = 1f,
    /** 0xRRGGBB in the light theme. */
    val light: Int,
    val lightAlpha: Float = 1f,
) {
    /** The same token at a different opacity (`X·N%` in the inventory). */
    fun opacity(alpha: Float): U2ThemedColor =
        U2ThemedColor(dark = dark, darkAlpha = darkAlpha * alpha, light = light, lightAlpha = lightAlpha * alpha)

    /** The colour in one theme. */
    fun resolve(dark: Boolean): Color =
        if (dark) color(this.dark, darkAlpha) else color(light, lightAlpha)

    /** sRGB components (0…1) in one theme: red, green, blue, alpha. */
    fun components(dark: Boolean): FloatArray {
        val (hex, alpha) = if (dark) this.dark to darkAlpha else light to lightAlpha
        return floatArrayOf(
            ((hex shr 16) and 0xFF) / 255f,
            ((hex shr 8) and 0xFF) / 255f,
            (hex and 0xFF) / 255f,
            alpha,
        )
    }

    companion object {
        /** A theme-independent colour (tokens.json › color.fixed). */
        fun fixed(hex: Int, alpha: Float = 1f): U2ThemedColor =
            U2ThemedColor(dark = hex, darkAlpha = alpha, light = hex, lightAlpha = alpha)

        private fun color(hex: Int, alpha: Float): Color = Color(
            red = ((hex shr 16) and 0xFF) / 255f,
            green = ((hex shr 8) and 0xFF) / 255f,
            blue = (hex and 0xFF) / 255f,
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}
