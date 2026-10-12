package com.syconx.us2us.app.debug

import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.feature.chats.chatsHarnesses
import com.syconx.us2us.feature.conversation.conversationHarnesses
import com.syconx.us2us.feature.lock.lockHarnesses
import com.syconx.us2us.feature.onboarding.onboardingHarnesses
import com.syconx.us2us.feature.settings.settingsHarnesses
import com.syconx.us2us.feature.verification.verificationHarnesses

/**
 * Maps a launch route's feature to the harness that draws its states. Each feature module lists its
 * harnesses (one per route feature); a route without a harness, or a state the harness does not
 * know, opens as it would without a state.
 */
object HarnessRegistry {
    val harnesses: List<FeatureHarness> by lazy {
        onboardingHarnesses + lockHarnesses + chatsHarnesses + conversationHarnesses + verificationHarnesses + settingsHarnesses
    }

    /** The harness for [feature], if any. */
    fun harness(feature: LaunchRoute.Feature): FeatureHarness? = harnesses.firstOrNull { it.feature == feature }

    /**
     * The plan of [route]'s state: the harness's own, or `null` when the route has no harness or the
     * harness has no plan for it (the app then uses the route's start and path).
     */
    fun plan(route: LaunchRoute?, controls: FixtureControls?): HarnessPlan? {
        route ?: return null
        val harness = harness(route.feature) ?: return null
        return harness.plan(route.target, route.state, controls)
    }
}
