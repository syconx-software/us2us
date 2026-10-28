package com.syconx.us2us.shot

import java.io.File

/**
 * The review matrix for scenes that need interaction (a long press, a sheet): every theme and
 * language at the design size, larger system font sizes and a compact Android phone. Scenes run
 * only when the `UX_PHASE` environment variable names a phase (`before`, `after` …), and their
 * shots go to `android/reports/ux-polish/<phase>/<variant>/<name>.png`.
 */
object UxMatrix {
    /** The phase being shot, or `null` when the matrix is off. */
    val phase: String? = System.getenv("UX_PHASE")?.takeIf { it.matches(Regex("[a-z0-9-]+")) }

    val enabled: Boolean get() = phase != null

    /** One cell of the matrix. */
    data class Variant(val theme: ShotTheme, val lang: String, val fontScale: Float = 1f, val screen: ShotScreen = ShotScreen.Phone) {
        /** `dark-tr-phone-fs100`, `light-tr-small-fs200` … */
        val folder: String
            get() = "${theme.raw}-$lang-${if (screen == ShotScreen.Phone) "phone" else "small"}-fs${Math.round(fontScale * 100)}"

        /** Shot options for this cell: real Android bars on the small phone, the design's elsewhere. */
        fun options(settleMillis: Long = DesignMoment.ANIMATION_MILLIS): ShotOptions =
            ShotOptions(settleMillis = settleMillis, fontScale = fontScale, screen = screen)
    }

    /** The design size in both themes and languages, then the larger fonts and the small phone. */
    val variants: List<Variant> = listOf(
        Variant(ShotTheme.Dark, "tr"),
        Variant(ShotTheme.Light, "tr"),
        Variant(ShotTheme.Dark, "en"),
        Variant(ShotTheme.Light, "en"),
        Variant(ShotTheme.Light, "tr", 1.3f),
        Variant(ShotTheme.Dark, "en", 1.3f, ShotScreen.SmallPhone),
        Variant(ShotTheme.Light, "tr", 2.0f, ShotScreen.SmallPhone),
        Variant(ShotTheme.Dark, "en", 2.0f),
    )

    /** Where [name] of [variant] goes in the current phase. */
    fun file(variant: Variant, name: String): File =
        File(ShotPaths.androidDir, "reports/ux-polish/${checkNotNull(phase) { "UX_PHASE is not set" }}/${variant.folder}/${ShotPaths.fileName(name)}.png")
}
