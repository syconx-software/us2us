package com.syconx.us2us.app

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.app.debug.HarnessRegistry
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotOptions
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.shot.ShotScreen
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder
import java.io.File
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessibility and small-screen matrix: the main harness states of every feature at larger
 * system font sizes (1.3 and 2.0) and on a compact Android phone (360 × 640 with real Android
 * system bars), next to the design size that [RouteSweepTest] covers. Runs only when `UX_PHASE`
 * is set (`before`, `after` …); the shots go to `android/reports/ux-polish/<phase>/<variant>/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class UxMatrixShotTest {
    private val phase: String? = System.getenv("UX_PHASE")?.takeIf { it.matches(Regex("[a-z0-9-]+")) }

    @get:Rule
    val host = ShotHost(outputDir = File(ShotPaths.androidDir, "reports/ux-polish/${phase ?: "unused"}"))

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** One cell of the matrix: theme and language, font size and screen. */
    enum class Variant(val theme: ShotTheme, val lang: String, val fontScale: Float, val screen: ShotScreen) {
        LightTrFont130(ShotTheme.Light, "tr", 1.3f, ShotScreen.Phone),
        DarkEnSmallFont130(ShotTheme.Dark, "en", 1.3f, ShotScreen.SmallPhone),
        LightTrSmallFont200(ShotTheme.Light, "tr", 2.0f, ShotScreen.SmallPhone),
        DarkEnFont200(ShotTheme.Dark, "en", 2.0f, ShotScreen.Phone),
        ;

        /** The shot's folder: `light-tr-phone-fs130`. */
        val folder: String
            get() = "${theme.raw}-$lang-${if (screen == ShotScreen.Phone) "phone" else "small"}-fs${(fontScale * 100).toInt()}"
    }

    private class MatrixServices : AppServices {
        override fun usesFixtures(configuration: LaunchConfig) = true

        override fun fixtures(configuration: LaunchConfig, clock: DesignClock, english: Boolean, scope: CoroutineScope): MessengerService =
            FixturesMessengerService(
                hasAccount = configuration.start != StartScreen.Welcome,
                clock = clock,
                ghostUrgent = configuration.ghostUrgent,
                english = english,
                latency = Duration.ZERO,
                scope = scope,
            ).apply { artworkEncoder = BitmapDesignArtworkEncoder() }

        override fun live(configuration: LaunchConfig): MessengerService = error("no live service in the matrix")

        override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? =
            HarnessRegistry.plan(configuration.launchRoute, messenger as? FixtureControls)
    }

    private fun sweep(group: String) {
        assumeTrue("set UX_PHASE to shoot the matrix", phase != null)
        val failures = mutableListOf<String>()
        for (variant in Variant.entries) {
            for (route in ROUTES.getValue(group)) {
                runCatching { shoot(route, variant) }.onFailure { failures += "${variant.folder} $route: $it" }
            }
        }
        assertTrue("routes that failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun shoot(route: String, variant: Variant) {
        val designInsets = variant.screen == ShotScreen.Phone
        val arguments = buildMap {
            put("U2Route", route)
            put("U2Theme", variant.theme.raw)
            put("U2Language", variant.lang)
            put("U2Fixtures", "YES")
            put("U2FrozenClock", "YES")
            if (designInsets) put("U2DesignInsets", "YES")
        }
        val configuration = LaunchConfig.from { arguments[it] }
        val preferences = AppPreferences(context.getSharedPreferences("matrix", Context.MODE_PRIVATE), appliesLanguage = false)
        var model: AppModel? = null
        val options = ShotOptions(settleMillis = 0, fontScale = variant.fontScale, screen = variant.screen, designInsets = true)
        host.render(variant.theme, variant.lang, options) {
            val app = remember { AppModel(configuration, preferences, MatrixServices()).also { model = it } }
            DisposableEffect(app) { onDispose { app.close() } }
            AppRoot(app)
        }
        settle(SETTLE_MILLIS)
        if (REAL_TIME.any { it in route }) settleInRealTime(REAL_TIME_MILLIS)
        val file = host.capture(ShotPaths.routeName(route))
        // capture() files by theme and language; the matrix files by variant.
        val target = File(file.parentFile.parentFile, "${variant.folder}/${file.name}")
        target.parentFile?.mkdirs()
        file.renameTo(target)
        File(file.parentFile, file.nameWithoutExtension + ".json").delete()
        // The theme-language folder capture() wrote into is left empty: gone (delete() keeps a folder that is not).
        file.parentFile?.delete()
        model?.close()
    }

    private fun settle(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 100L)
            host.compose.mainClock.advanceTimeBy(step)
            shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(step))
            left -= step
        }
        host.compose.waitForIdle()
    }

    private fun settleInRealTime(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            Thread.sleep(20)
            host.compose.mainClock.advanceTimeBy(16)
            shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(16))
        }
        host.compose.waitForIdle()
    }

    @Test fun onboarding() = sweep("onboarding")

    @Test fun lock() = sweep("lock")

    @Test fun chats() = sweep("chats")

    @Test fun conversation() = sweep("conversation")

    @Test fun safety() = sweep("safety")

    @Test fun settings() = sweep("settings")

    companion object {
        private const val SETTLE_MILLIS = 3_000L
        private val REAL_TIME = listOf("conversation:", "settings:avatar", "settings:7.1", "settings:qr", "chats:4.2", "newChat")
        private const val REAL_TIME_MILLIS = 1_500L

        /** The states that carry each screen's layout: the matrix needs no more than these. */
        val ROUTES: Map<String, List<String>> = mapOf(
            "onboarding" to listOf(
                "onboarding:1.1", "onboarding:2.1", "onboarding:2.1-taken", "onboarding:2.2", "onboarding:2.2-weak",
                "onboarding:2.3", "onboarding:2.3-error", "onboarding:7.3", "onboarding:restore-2.2", "onboarding:restore-2.3-damaged",
            ),
            "lock" to listOf(
                "lock:code", "lock:wrong", "lock:lastchance", "lock:throttled", "lock:unavailable", "lock:forgot", "lock:erase",
            ),
            "chats" to listOf(
                "chats:4.1", "chats:4.1-connecting", "chats:4.1-closed", "chats:4.1-empty", "chats:4.1-qr", "chats:4.1-mycode",
                "chats:4.2", "chats:4.2-keyboard", "chats:4.2-empty", "chats:4.2-searching", "chats:4.2-notfound",
                "chats:4.2-existing", "chats:4.2-self", "chats:4.3", "chats:4.3-empty", "chats:4.3-block", "chats:4.4",
                "chats:4.4-link", "chats:4.4-report", "chats:4.1-blocked", "invite", "invite:link",
            ),
            "conversation" to listOf(
                "conversation:deniz/5.1", "conversation:deniz/5.1-failed", "conversation:deniz/5.1-reply",
                "conversation:deniz/5.1-attach", "conversation:deniz/5.1-stickers", "conversation:deniz/5.1-blocked",
                "conversation:deniz/5.3", "conversation:deniz/5.3-incoming", "conversation:deniz/5.3-confirm",
                "conversation:deniz/5.4", "conversation:deniz/5.5", "conversation:deniz/5.6",
                "conversation:deniz/5.1-voice-hold", "conversation:deniz/5.1-voice-locked", "conversation:deniz/5.1-voice-review",
                "conversation:deniz/5.1-voice-permission", "conversation:deniz/5.1-save-notice",
                "conversation:mert_k/5.2", "conversation:mert_k/5.2-file", "conversation:mert_k/5.2-file-actions",
                "conversation:mert_k/5.2-downloading", "conversation:mert_k/5.2-unavailable", "conversation:mert_k/5.2-place",
                "conversation:mert_k/5.2-map", "conversation:mert_k/5.2-map-ask", "conversation:mert_k/5.2-map-denied",
                "conversation:mert_k/5.5",
            ),
            "safety" to listOf(
                "safety:deniz", "safety:deniz/safety-verified", "safety:deniz/changed", "safety:deniz/menu", "safety:deniz/block",
                "safety:deniz/report", "safety:deniz/confirm-remove", "safety:deniz/scan", "safety:deniz/mismatch",
                "safety:deniz/denied", "safety:deniz/verified",
            ),
            "settings" to listOf(
                "settings:7.1", "settings:7.1-end", "settings:7.1-access", "settings:qr", "settings:erase", "settings:avatar",
                "settings:avatar-crop", "settings:presence", "settings:autolock", "settings:ghost", "settings:disappearing",
                "settings:theme", "settings:language", "settings:7.2", "settings:7.2-weak", "settings:7.2-mismatch",
                "settings:7.2-exporting", "settings:7.3", "settings:7.3-wrong", "settings:7.3-done", "settings:code",
                "settings:code-wrong", "settings:blocked", "settings:blocked-empty", "settings:about", "backup",
            ),
        )
    }
}
