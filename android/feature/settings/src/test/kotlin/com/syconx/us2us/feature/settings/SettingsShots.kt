package com.syconx.us2us.feature.settings

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithTag
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.LocalShotSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotSpec
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.nav.SoftNavigationStack
import com.syconx.us2us.ui.theme.U2Theme
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/** The stand-in for 4.1 under 7.1 (another feature's screen): the background and a tag. */
const val CHATS_STAND_IN = "test.chats"

/** The fixtures app the settings screens run in: the design's data on the frozen design clock, no network latency. */
class SettingsFixture(english: Boolean, route: String?, pickBackup: Boolean = false) {
    val service = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        english = english,
        latency = Duration.ZERO,
    )
    val model = PreviewAppModel(
        messenger = service,
        clock = service.clock,
        configuration = LaunchConfig(launchRoute = route?.let(LaunchRoute::parse), pickBackup = pickBackup),
    )
    val router get() = model.router

    /** The harness plan of [route] (`settings:7.2-weak`, `settings`), prepared on the service and pushed on the router. */
    fun apply(route: String): HarnessPlan? {
        val launch = LaunchRoute.parse(route) ?: error("not a route: $route")
        val harness = settingsHarnesses.firstOrNull { it.feature == launch.feature }
        val plan = harness?.plan(launch.target, launch.state, service)
        if (plan == null) {
            router.setPath(launch.path)
            return null
        }
        runBlocking { plan.prepare?.invoke() }
        router.setPath(plan.stack)
        return plan
    }
}

/**
 * The main stack with [route]'s screens pushed over a stand-in 4.1, as the app's main stack draws
 * them, in the theme of the shot (or the one 7.1 › Tema picked: it applies live).
 */
@Composable
fun SettingsApp(
    route: String? = "settings",
    reduceMotion: Boolean = false,
    pickBackup: Boolean = false,
    onFixture: (SettingsFixture) -> Unit = {},
) {
    val spec: ShotSpec = checkNotNull(LocalShotSpec.current) { "render through ShotHost" }
    val fixture = remember { SettingsFixture(english = spec.lang == "en", route = route, pickBackup = pickBackup).also(onFixture) }
    val plan = remember { route?.let(fixture::apply) }
    val dark = when (fixture.model.preferences.theme) {
        ThemePreference.System -> spec.isDark
        ThemePreference.Dark -> true
        ThemePreference.Light -> false
    }
    U2PreviewRoot(
        appModel = fixture.model,
        dark = dark,
        designInsets = spec.designInsets,
        reduceMotion = reduceMotion,
        harnessState = plan?.uiState,
    ) {
        SoftNavigationStack(
            router = fixture.router,
            root = { Box(Modifier.fillMaxSize().background(U2Theme.colors.bg).testTag(CHATS_STAND_IN)) },
            destination = { screen ->
                when (screen) {
                    AppRoute.Settings -> SettingsRootScreen()
                    is AppRoute.Picker -> SettingsPickerScreen(screen.kind)
                    AppRoute.BackupExport -> BackupExportScreen()
                    is AppRoute.Page -> SettingsPageScreen(screen.kind)
                    else -> Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
                }
            },
        )
    }
}

/**
 * Moves the paused Compose clock and the main looper together: the settings flow's work runs on the
 * main dispatcher (it outlives the screens), whose delays wait on the looper's own clock.
 */
fun ShotHost.settle(millis: Long) {
    var left = millis
    while (left > 0) {
        val step = minOf(left, 100L)
        advance(step)
        shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(step))
        left -= step
    }
    compose.waitForIdle()
}

/** No node is tagged [tag]. */
fun ShotHost.gone(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

/** A node is tagged [tag]. */
fun ShotHost.has(tag: String): Boolean = !gone(tag)
