package com.syconx.us2us.ui.type

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.tokens.U2FontFamily

/**
 * The bundled variable fonts with the exact axis values the design uses.
 *
 * The fonts are the variable TTFs the iOS app bundles, straight from the google/fonts
 * repository (res/font, SIL OFL 1.1 — assets/licenses):
 *
 * | file | axes | design values |
 * |---|---|---|
 * | doto_variable.ttf | ROND 0–100, wght 100–900 | ROND 100, wght 800/900 |
 * | martian_mono_variable.ttf | wght 100–800, wdth 75–112.5 | wdth 100, wght 400/500 |
 * | inter_variable.ttf | opsz 14–32, wght 100–900 | opsz 14, wght 400–700 |
 *
 * Every axis is set explicitly: Martian Mono's default instance is wdth 112.5 (the design was
 * rendered at 100), Doto's dots are square without ROND 100, and Inter must keep the text cut
 * at every size. Italic is the synthetic oblique Compose applies (a 0.25 shear, as the
 * browser that drew the design does): the families declare upright faces only.
 */
@OptIn(ExperimentalTextApi::class)
object U2Fonts {
    /** Weights declared per family: every CSS weight inside the font's wght range. */
    private val weights = mapOf(
        U2FontFamily.Doto to (100..900 step 100),
        U2FontFamily.MartianMono to (100..800 step 100),
        U2FontFamily.Inter to (100..900 step 100),
    )

    private val families: Map<U2FontFamily, FontFamily> = U2FontFamily.entries.associateWith { family ->
        FontFamily(
            weights.getValue(family).map { weight ->
                Font(
                    resId = resource(family),
                    weight = FontWeight(weight),
                    style = FontStyle.Normal,
                    variationSettings = variationSettings(family, weight),
                )
            },
        )
    }

    /** The Compose font family of a design family (upright faces at every weight). */
    fun family(family: U2FontFamily): FontFamily = families.getValue(family)

    /** The font resource of a family. */
    fun resource(family: U2FontFamily): Int = when (family) {
        U2FontFamily.Doto -> R.font.doto_variable
        U2FontFamily.MartianMono -> R.font.martian_mono_variable
        U2FontFamily.Inter -> R.font.inter_variable
    }

    /** The axis values for a family at a CSS weight. */
    fun variationSettings(family: U2FontFamily, weight: Int): FontVariation.Settings = when (family) {
        U2FontFamily.Doto -> FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting(ROND, 100f))
        U2FontFamily.MartianMono -> FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(100f))
        U2FontFamily.Inter -> FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting(OPSZ, 14f))
    }

    /** The same settings as a CSS `font-variation-settings` string (for android.graphics.Paint). */
    fun variationSettingsCss(family: U2FontFamily, weight: Int): String = when (family) {
        U2FontFamily.Doto -> "'wght' $weight, '$ROND' 100"
        U2FontFamily.MartianMono -> "'wght' $weight, 'wdth' 100"
        U2FontFamily.Inter -> "'wght' $weight, '$OPSZ' 14"
    }

    private const val ROND = "ROND"
    private const val OPSZ = "opsz"
}
