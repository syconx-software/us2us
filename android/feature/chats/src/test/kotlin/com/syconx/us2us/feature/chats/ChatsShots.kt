package com.syconx.us2us.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.LocalShotSpec
import com.syconx.us2us.shot.ShotSpec
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.nav.SoftNavigationStack
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** A stand-in for the screens of other features pushed over the chats (5.1, 4.4): the background and a tag. */
const val OTHER_SCREEN_TAG = "test.otherScreen"

/** The fixtures app a chats screen runs in: the design's data on the frozen design clock, no network latency. */
class ChatsFixture(english: Boolean, ghostUrgent: Boolean = false, chatsDemo: String? = null, route: String? = null) {
    val service = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        ghostUrgent = ghostUrgent,
        english = english,
        latency = Duration.ZERO,
    )
    val model = PreviewAppModel(
        messenger = service,
        clock = service.clock,
        configuration = LaunchConfig(launchRoute = route?.let(LaunchRoute::parse), chatsDemo = chatsDemo),
    )
    val router get() = model.router

    /** The harness plan of [route] (`chats:4.2`, `invite:edit`, `newChat:deniz`), prepared on the service and pushed on the router. */
    fun apply(route: String): HarnessPlan? {
        val launch = LaunchRoute.parse(route) ?: error("not a route: $route")
        val harness = chatsHarnesses.firstOrNull { it.feature == launch.feature } ?: return null
        val plan = harness.plan(launch.target, launch.state, service) ?: return null
        runBlocking { plan.prepare?.invoke() }
        router.setPath(plan.stack)
        return plan
    }
}

/**
 * 4.1 with the main stack above it, as the app's main stack draws it, in the theme of the shot:
 * [route]'s harness state applied first. Screens of other features are a plain stand-in.
 */
@Composable
fun ChatsApp(
    route: String? = null,
    ghostUrgent: Boolean = false,
    chatsDemo: String? = null,
    reduceMotion: Boolean = false,
    onFixture: (ChatsFixture) -> Unit = {},
) {
    val spec: ShotSpec = checkNotNull(LocalShotSpec.current) { "render through ShotHost" }
    val fixture = remember { ChatsFixture(english = spec.lang == "en", ghostUrgent = ghostUrgent, chatsDemo = chatsDemo, route = route).also(onFixture) }
    val plan = remember { route?.let(fixture::apply) }
    U2PreviewRoot(
        appModel = fixture.model,
        dark = spec.isDark,
        designInsets = spec.designInsets,
        reduceMotion = reduceMotion,
        harnessState = plan?.uiState,
    ) {
        SoftNavigationStack(
            router = fixture.router,
            root = { ChatsScreen() },
            destination = { route ->
                when (route) {
                    AppRoute.Requests -> MessageRequestsScreen()
                    else -> Box(Modifier.fillMaxSize().background(U2Theme.colors.bg).testTag(OTHER_SCREEN_TAG))
                }
            },
        )
    }
}
