package com.syconx.us2us.domain.harness

import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.StartScreen

/**
 * One feature's debug harness: opens every screen of the feature in every drawn state directly
 * (`U2Route <feature>[:<target>][/<state>]`), for screenshots and design comparisons. The state ids
 * are the iOS harnesses' own (`conversation:deniz/5.3`, `settings:7.2-weak`, `lock:throttled`), so
 * iOS and Android screenshot jobs map one to one.
 *
 * The app's debug `HarnessRegistry` maps [feature] to its harness and calls [plan] once at launch,
 * before the first frame. Release builds never call it.
 */
interface FeatureHarness {
    /** The route feature this harness answers (`U2Route <feature>…`). */
    val feature: LaunchRoute.Feature

    /** Every state id the harness draws, in the iOS harness's order (the screenshot jobs iterate it). */
    val states: List<String>

    /**
     * How to open [state] of [target] (both from the route; `null` when absent). [controls] are the
     * fixtures' knobs, `null` when the app runs the live service (a harness then sets up what it can
     * without them). `null` when the state is not this harness's.
     */
    fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan?
}

/**
 * What a harness state needs from the app: where it starts, which main-stack screens are pushed
 * (without animation) above 4.1, and the feature's own state for its screens.
 */
data class HarnessPlan(
    /** The first screen: onboarding, the lock or the chat list. */
    val start: StartScreen,
    /** The main-stack screens above 4.1, bottom first (empty: 4.1 itself). */
    val stack: List<AppRoute> = emptyList(),
    /**
     * The feature's own state for its screens (which sheet is open, what is typed, which step is
     * shown …); the app hands it to the feature through `LocalHarnessState` and never looks inside.
     */
    val uiState: HarnessUiState? = null,
    /**
     * Work on the service before the first frame of the state (wrong codes entered, a message's
     * status changed, a demo share started); `null` when nothing is needed. It runs on the main
     * thread, once.
     */
    val prepare: (suspend () -> Unit)? = null,
)

/** A feature's harness state (a marker: each feature defines its own and reads it back in its screens). */
interface HarnessUiState {
    /** The harness state id this was planned for (`5.3`, `7.2-weak`). */
    val state: String
}

/** A harness state that is only its id: for features whose screens read the id themselves. */
data class HarnessStateId(override val state: String) : HarnessUiState
