package com.syconx.us2us.feature.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.measuredFrame
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.nav.SoftNavigationStack
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.math.abs
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** The stand-in for the screens of other features (4.1 at the root, 5.1 pushed). */
const val OTHER_SCREEN_TAG = "test.otherScreen"

/**
 * The world of one 06 shot: the fixtures service on the frozen design moment, in the shot's
 * language, without latency; the app model around it; the harness state the screens read. Make it
 * before rendering; the environment picks it up.
 */
internal class VerificationRig(english: Boolean = false) {
    val fixtures = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        english = english,
        latency = Duration.ZERO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    val model = PreviewAppModel(fixtures, clock = fixtures.clock)
    val router get() = model.router
    var harness: HarnessUiState? = null

    /** Plans [route] (`safety:deniz/scan`) like the app's harness registry: the service side and the stack now, the screen side once it shows. */
    fun apply(route: String): HarnessPlan {
        val launch = checkNotNull(LaunchRoute.parse(route)) { "not a route: $route" }
        val plan = checkNotNull(VerificationHarness.plan(launch.target, launch.state, fixtures)) { "no plan for $route" }
        runBlocking { plan.prepare?.invoke() }
        router.setPath(plan.stack)
        harness = plan.uiState
        return plan
    }
}

/** The environment of this module's shots: the shot's theme, design insets, the rig's app model and harness state. */
internal fun verificationShots(rig: () -> VerificationRig) = ShotEnvironment { spec, content ->
    val current = rig()
    U2PreviewRoot(current.model, dark = spec.isDark, designInsets = spec.designInsets, harnessState = current.harness) { content() }
}

/** The main stack as the app draws it: 4.1 (a stand-in) at the root, 6.1 for its routes, a stand-in for 5.1. */
@Composable
internal fun VerificationApp(rig: VerificationRig) {
    SoftNavigationStack(
        router = rig.router,
        root = { StandIn() },
        destination = { route ->
            when (route) {
                is AppRoute.SafetyNumber -> SafetyNumberScreen(route.chat, route.tab)
                else -> StandIn()
            }
        },
    )
}

@Composable
private fun StandIn() {
    Box(Modifier.fillMaxSize().background(U2Theme.colors.bg).testTag(OTHER_SCREEN_TAG))
}

/**
 * Frame checks against a layout that report every mismatch together: a node by tag against an
 * element, optionally moved by [dy] (6.1's content sits under the contact screen's tabs).
 */
internal class Frames(private val compose: SemanticsNodeInteractionsProvider, private val layout: LayoutSpec) {
    private val problems = mutableListOf<String>()
    private var count = 0

    /** The largest difference of any checked frame, in dp. */
    var largest = 0f
        private set

    fun check(tag: String, element: String, dx: Float = 0f, dy: Float = 0f, tolerance: Float = 1f, sizeOnly: Boolean = false) {
        count++
        val expected = layout[element].frame.offset(dx, dy)
        val actual = runCatching { compose.measuredFrame(tag) }.getOrElse {
            problems += "$tag ($element): ${it.message?.lineSequence()?.firstOrNull()}"
            return
        }
        val distance = if (sizeOnly) maxOf(abs(actual.w - expected.w), abs(actual.h - expected.h)) else expected.distanceTo(actual)
        largest = maxOf(largest, distance)
        if (distance > tolerance + 0.001f) problems += "$tag ($element${if (dy != 0f) " +$dy" else ""}): expected [$expected], got [$actual]"
    }

    /** The frame of [tag], for checks of a centre or an edge. */
    fun frame(tag: String): Frame = compose.measuredFrame(tag)

    fun assertAll() {
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} of $count frames differ from ${layout.name}:\n" + problems.joinToString("\n"))
    }
}
