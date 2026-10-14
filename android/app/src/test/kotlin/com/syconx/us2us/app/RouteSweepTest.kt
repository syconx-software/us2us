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
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder
import java.io.File
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole app in every harness state, as the debug build launches it (`U2Route <route>` with
 * `U2Fixtures`, `U2FrozenClock`, `U2DesignInsets`): the root, the real route host and every feature
 * together, so composites are drawn as the app draws them (5.2 and 5.6 over 5.1, 6.2 over 6.1, 4.2
 * over 4.1, 7.2 over 7.1, the lock over the cover). Dark and light in Turkish and English, named like
 * the iOS simulator captures (`conversation_deniz_5.3`), into `android/build/shots/sweep/android/`;
 * `scripts/parity_report.py` ranks them against `android/build/ios-reference/`.
 *
 * One test per route group and language keeps each run short; a route that throws is reported at
 * the end with the others still shot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class RouteSweepTest {
    @get:Rule
    val host = ShotHost(outputDir = File(ShotPaths.shotsDir, "sweep/android"))

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The debug services of the app with the test's fixtures: no network latency, the design pictures as JPEG. */
    private class SweepServices : AppServices {
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

        override fun live(configuration: LaunchConfig): MessengerService = error("no live service in the sweep")

        override fun harnessPlan(configuration: LaunchConfig, messenger: MessengerService): HarnessPlan? =
            HarnessRegistry.plan(configuration.launchRoute, messenger as? FixtureControls)
    }

    /** Shoots [routes] (or a gallery) in [lang], dark and light; returns the routes that failed. */
    private fun sweep(routes: List<String>, lang: String) {
        val failures = mutableListOf<String>()
        for (theme in ShotTheme.entries) {
            for (route in routes) {
                runCatching { shoot(route, theme, lang) }.onFailure { failures += "${theme.raw}-$lang $route: $it" }
            }
        }
        assertTrue("routes that failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun shoot(route: String, theme: ShotTheme, lang: String) {
        val arguments = buildMap {
            if (route.startsWith(GALLERY)) put("U2Gallery", route.removePrefix(GALLERY)) else put("U2Route", route)
            put("U2Theme", theme.raw)
            put("U2Language", lang)
            put("U2Fixtures", "YES")
            put("U2FrozenClock", "YES")
            put("U2DesignInsets", "YES")
        }
        val configuration = LaunchConfig.from { arguments[it] }
        val preferences = AppPreferences(context.getSharedPreferences("sweep", Context.MODE_PRIVATE), appliesLanguage = false)
        var model: AppModel? = null
        host.render(theme, lang, ShotOptions(settleMillis = 0)) {
            val app = remember { AppModel(configuration, preferences, SweepServices()).also { model = it } }
            DisposableEffect(app) { onDispose { app.close() } }
            AppRoot(app)
        }
        settle(SETTLE_MILLIS)
        // Pictures, files and video posters are decoded off the main thread, in real time.
        if (REAL_TIME.any { it in route }) settleInRealTime(REAL_TIME_MILLIS)
        host.capture(name(route))
        model?.close()
    }

    /** Moves the paused Compose clock and the main looper together (the app model's work runs on the main dispatcher). */
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

    /** Lets work on other threads finish (decoding, file copies) while frames keep coming. */
    private fun settleInRealTime(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            Thread.sleep(20)
            host.compose.mainClock.advanceTimeBy(16)
            shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(16))
        }
        host.compose.waitForIdle()
    }

    private fun name(route: String): String = when (route) {
        "${GALLERY}YES" -> "gallery"
        "${GALLERY}stickers" -> "gallery_stickers"
        "${GALLERY}avatars" -> "gallery_avatars"
        else -> ShotPaths.routeName(route)
    }

    @Test fun onboardingTr() = sweep(ROUTES.getValue("onboarding"), "tr")

    @Test fun onboardingEn() = sweep(ROUTES.getValue("onboarding"), "en")

    @Test fun lockTr() = sweep(ROUTES.getValue("lock"), "tr")

    @Test fun lockEn() = sweep(ROUTES.getValue("lock"), "en")

    @Test fun chatsTr() = sweep(ROUTES.getValue("chats"), "tr")

    @Test fun chatsEn() = sweep(ROUTES.getValue("chats"), "en")

    @Test fun conversationDenizTr() = sweep(ROUTES.getValue("conversationDeniz"), "tr")

    @Test fun conversationDenizEn() = sweep(ROUTES.getValue("conversationDeniz"), "en")

    @Test fun conversationMertTr() = sweep(ROUTES.getValue("conversationMert"), "tr")

    @Test fun conversationMertEn() = sweep(ROUTES.getValue("conversationMert"), "en")

    @Test fun safetyTr() = sweep(ROUTES.getValue("safety"), "tr")

    @Test fun safetyEn() = sweep(ROUTES.getValue("safety"), "en")

    @Test fun settingsTr() = sweep(ROUTES.getValue("settings"), "tr")

    @Test fun settingsEn() = sweep(ROUTES.getValue("settings"), "en")

    @Test fun galleriesTr() = sweep(GALLERIES, "tr")

    companion object {
        /** iOS waits 2.5 s after launch before its capture; the harness's own delays end by then. */
        private const val SETTLE_MILLIS = 3_000L

        /** States whose pictures, files or posters are made off the main thread. */
        private val REAL_TIME = listOf("conversation:", "settings:avatar", "settings:7.1", "settings:qr", "chats:4.2", "newChat", "gallery:")
        private const val REAL_TIME_MILLIS = 1_500L

        private const val GALLERY = "gallery:"
        private val GALLERIES = listOf("${GALLERY}YES", "${GALLERY}stickers", "${GALLERY}avatars")

        /** Every harness state the iOS capture job shoots, by group (`android/scripts/capture_ios_refs.sh`, plus Engellenenler). */
        val ROUTES: Map<String, List<String>> = mapOf(
        "onboarding" to listOf(
            "onboarding:1.1",
            "onboarding:1.2",
            "onboarding:1.3",
            "onboarding:2.1",
            "onboarding:2.1-blurred",
            "onboarding:2.1-rules",
            "onboarding:2.1-empty",
            "onboarding:2.1-checking",
            "onboarding:2.1-taken",
            "onboarding:2.1-short",
            "onboarding:2.1-invalid",
            "onboarding:2.1-turkish",
            "onboarding:2.1-firstLetter",
            "onboarding:2.2",
            "onboarding:2.2-empty",
            "onboarding:2.2-confirm",
            "onboarding:2.2-weak",
            "onboarding:2.2-mismatch",
            "onboarding:2.3",
            "onboarding:2.3-unchecked",
            "onboarding:2.3-error",
            "onboarding:2.3-kept",
            "onboarding:2.3-taken",
            "onboarding:7.3",
            "onboarding:restore-2.2",
            "onboarding:restore-2.3",
            "onboarding:restore-2.3-gone",
            "onboarding:restore-2.3-damaged",
            "onboarding:restore-2.3-lock",
            "onboarding:restore-2.3-toLock",
        ),
        "lock" to listOf(
            "lock:faceid",
            "lock:touchid",
            "lock:code",
            "lock:typing",
            "lock:wrong",
            "lock:lastchance",
            "lock:throttled",
            "lock:unavailable",
            "lock:unusable",
            "lock:wiped",
            "lock:forgot",
            "lock:erase",
            "lock:restore",
            "lock:cover",
            "lock:switcher",
        ),
        "chats" to listOf(
            "chats:4.1",
            "chats:4.1-connecting",
            "chats:4.1-closed",
            "chats:4.1-empty",
            "chats:4.1-qr",
            "chats:4.1-mycode",
            "chats:4.2",
            "chats:4.2-keyboard",
            "chats:4.2-empty",
            "chats:4.2-searching",
            "chats:4.2-notfound",
            "chats:4.2-existing",
            "chats:4.2-self",
            "chats:4.2-mycode",
            "chats:4.3",
            "chats:4.3-empty",
            "chats:4.3-block",
            "chats:4.4",
            "chats:4.4-media",
            "chats:4.4-link",
            "chats:4.4-report",
            "chats:4.1-blocked",
            "newChat",
            "newChat:deniz",
            "requests",
            "request:kuzey_dev",
            "request:n_aksoy",
            "request:hediye_kazan",
            "invite",
            "invite:edit",
            "invite:edited",
            "invite:copied",
            "invite:sent",
            "invite:link",
            "invite:link-unknown",
        ),
        "conversationDeniz" to listOf(
            "conversation:deniz/5.1",
            "conversation:deniz/5.1-failed",
            "conversation:deniz/5.1-reply",
            "conversation:deniz/5.1-attach",
            "conversation:deniz/5.1-stickers",
            "conversation:deniz/5.1-blocked",
            "conversation:deniz/5.1-viewer",
            "conversation:deniz/5.1-viewer-zoomed",
            "conversation:deniz/5.3",
            "conversation:deniz/5.3-incoming",
            "conversation:deniz/5.3-confirm",
            "conversation:deniz/5.4",
            "conversation:deniz/5.4-pending",
            "conversation:deniz/5.4-failed",
            "conversation:deniz/5.5",
            "conversation:deniz/5.6",
            "conversation:deniz/5.6-clean",
            "conversation:deniz/5.6-captured",
            "conversation:deniz/5.1-save-notice",
            "conversation:deniz/5.1-voice-hold",
            "conversation:deniz/5.1-voice-locked",
            "conversation:deniz/5.1-voice-review",
            "conversation:deniz/5.1-voice-hint",
            "conversation:deniz/5.1-voice-permission",
            "conversation:deniz/5.1-voice-unplayed",
        ),
        "conversationMert" to listOf(
            "conversation:mert_k/5.2",
            "conversation:mert_k/5.2-ended",
            "conversation:mert_k/5.2-opened",
            "conversation:mert_k/5.2-player",
            "conversation:mert_k/5.2-player-loading",
            "conversation:mert_k/5.2-file",
            "conversation:mert_k/5.2-file-actions",
            "conversation:mert_k/5.2-remote",
            "conversation:mert_k/5.2-downloading",
            "conversation:mert_k/5.2-unavailable",
            "conversation:mert_k/5.2-preparing",
            "conversation:mert_k/5.2-prepare-failed",
            "conversation:mert_k/5.2-file-disappearing",
            "conversation:mert_k/5.2-saved",
            "conversation:mert_k/5.4-saved",
            "conversation:mert_k/5.2-place",
            "conversation:mert_k/5.2-map",
            "conversation:mert_k/5.2-map-peer",
            "conversation:mert_k/5.2-map-ask",
            "conversation:mert_k/5.2-map-denied",
            "conversation:mert_k/5.5",
        ),
        "safety" to listOf(
            "safety:deniz",
            "safety:deniz/photos",
            "safety:deniz/documents",
            "safety:deniz/safety-verified",
            "safety:deniz/changed",
            "safety:deniz/menu",
            "safety:deniz/block",
            "safety:deniz/blocked",
            "safety:deniz/report",
            "safety:deniz/report-address",
            "safety:deniz/confirm-accept",
            "safety:deniz/confirm",
            "safety:deniz/confirm-remove",
            "safety:deniz/scan",
            "safety:deniz/mismatch",
            "safety:deniz/mycode",
            "safety:deniz/denied",
            "safety:deniz/torch",
            "safety:deniz/verified",
            "safety:deniz/verified-manual",
            "safety:deniz/contact-scan",
            "safety:deniz/contact-unknown",
            "safety:deniz/contact-own",
            "safety:deniz/contact-mycode",
            "safety:deniz/contact-verified",
        ),
        "settings" to listOf(
            "settings:7.1",
            "settings:7.1-end",
            "settings:7.1-access",
            "settings:7.1-access-checking",
            "settings:7.1-access-unanswered",
            "settings:qr",
            "settings:erase",
            "settings:erase-failed",
            "settings:avatar",
            "settings:avatar-crop",
            "settings:presence",
            "settings:autolock",
            "settings:ghost",
            "settings:disappearing",
            "settings:theme",
            "settings:language",
            "settings:7.2",
            "settings:7.2-empty",
            "settings:7.2-weak",
            "settings:7.2-generated",
            "settings:7.2-revealed",
            "settings:7.2-copied",
            "settings:7.2-confirm",
            "settings:7.2-mismatch",
            "settings:7.2-exporting",
            "settings:7.3",
            "settings:7.3-pick",
            "settings:7.3-password",
            "settings:7.3-wrong",
            "settings:7.3-done",
            "settings:7.3-cancel",
            "settings:code",
            "settings:code-wrong",
            "settings:code-new",
            "settings:code-weak",
            "settings:code-confirm",
            "settings:code-mismatch",
            "settings:faceid",
            "settings:about",
            "backup",
            "settings:7.1-blocked",
            "settings:blocked",
            "settings:blocked-empty",
        ),
        )
    }
}
