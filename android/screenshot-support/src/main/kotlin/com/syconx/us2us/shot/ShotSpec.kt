package com.syconx.us2us.shot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** The two token sets of the design. [raw] is the `U2Theme` value and the shot folder prefix. */
enum class ShotTheme(val raw: String) {
    Dark("dark"),
    Light("light"),
    ;

    val isDark: Boolean get() = this == Dark
}

/** How a screen is set up before it is captured. */
data class ShotOptions(
    /** Safe-area insets fixed at the design's 62 (status bar and Dynamic Island) and 34 (home indicator). */
    val designInsets: Boolean = true,
    /** Time stands at the design's moment ([ShotSpec.nowMillis]) and the Compose clock only moves by [settleMillis]. */
    val frozenClock: Boolean = true,
    /**
     * How far the paused Compose clock is moved after the first frame: entrance transitions finish,
     * and looping animations stand where the references caught them (CSS paused at t = 2 s).
     */
    val settleMillis: Long = DesignMoment.ANIMATION_MILLIS,
)

/** One rendered screen: theme, language and the environment it was given. */
data class ShotSpec(
    val theme: ShotTheme,
    /** `tr` or `en`. Turkish is the design's language; section 09 is English. */
    val lang: String,
    val options: ShotOptions = ShotOptions(),
) {
    val isDark: Boolean get() = theme.isDark
    val designInsets: Boolean get() = options.designInsets
    val frozenClock: Boolean get() = options.frozenClock

    /** The design's moment in epoch milliseconds (8 Oct 2026, 18:52 in [DesignMoment.zone]). */
    val nowMillis: Long get() = DesignMoment.epochMillis

    val locale: Locale get() = Locale.forLanguageTag(if (lang == "tr") "tr-TR" else "en-US")

    /** Robolectric qualifiers: the iOS reference size (402 × 874 dp at 3×), the language and the night mode. */
    val qualifiers: String
        get() {
            val language = if (lang == "tr") "tr-rTR" else "en-rUS"
            val night = if (isDark) "night" else "notnight"
            return "$language-w${DesignMoment.WIDTH_DP}dp-h${DesignMoment.HEIGHT_DP}dp-port-$night-xxhdpi"
        }
}

/** The fixed facts of the design's screenshots. */
object DesignMoment {
    /** 402 × 874 pt at 3× = 1206 × 2622 px, an iPhone 17 Pro. */
    const val WIDTH_DP = 402
    const val HEIGHT_DP = 874
    const val DENSITY = 3f
    const val WIDTH_PX = 1206
    const val HEIGHT_PX = 2622

    /** The design's safe area: the status bar with the Dynamic Island, and the home indicator. */
    const val SAFE_TOP_DP = 62
    const val SAFE_BOTTOM_DP = 34

    /** CSS animations in the references are paused 2 s in. */
    const val ANIMATION_MILLIS = 2_000L

    /**
     * Shots run in one fixed zone so wall-clock times never depend on the machine. The design's times
     * are wall-clock times on its day, so any zone without a DST change on 8 Oct gives the same pictures.
     */
    val zone: ZoneId = ZoneId.of("Europe/Istanbul")

    /** 8 Oct 2026, 18:52 — the design's moment (the 6.3 verification time). */
    val dateTime: LocalDateTime = LocalDateTime.of(2026, 10, 8, 18, 52, 0)

    val epochMillis: Long get() = dateTime.atZone(zone).toInstant().toEpochMilli()
}

/** The spec of the shot being rendered, for content that sets up its own environment. */
val LocalShotSpec = staticCompositionLocalOf<ShotSpec?> { null }

/**
 * Sets up what a screen needs around it in a shot: the theme, the frozen design clock, the design
 * insets, the fixtures service and so on. The screenshot host itself only fixes the Android side
 * (size, density, night mode, locale, time zone, insets); a module passes its environment to
 * [ShotHost] once, and every shot of that module is wrapped in it.
 */
interface ShotEnvironment {
    @Composable
    fun Provide(spec: ShotSpec, content: @Composable () -> Unit)

    companion object {
        /** No wrapper: the content reads [LocalShotSpec] itself, if it needs to. */
        val None: ShotEnvironment = ShotEnvironment { _, content -> content() }
    }
}

/** A [ShotEnvironment] from a composable wrapper. */
fun ShotEnvironment(wrap: @Composable (spec: ShotSpec, content: @Composable () -> Unit) -> Unit): ShotEnvironment =
    object : ShotEnvironment {
        @Composable
        override fun Provide(spec: ShotSpec, content: @Composable () -> Unit) = wrap(spec, content)
    }
