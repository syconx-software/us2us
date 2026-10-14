package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.StartScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `safety:<chat>/<state>`: every iOS state id has a plan with the stack and the service set up as iOS does. */
class VerificationHarnessTest {
    private val deniz = ChatId("deniz")

    @Test
    fun everyStateHasAPlan() {
        assertEquals(LaunchRoute.Feature.Safety, VerificationHarness.feature)
        assertEquals(30, VerificationHarness.states.size)
        for (id in VerificationHarness.states) {
            val route = LaunchRoute.parse("safety:$id")!!
            val plan = VerificationHarness.plan(route.target, route.state, fixtures())
            assertNotNull(id, plan)
            assertEquals(StartScreen.Chats, plan!!.start)
            assertEquals(id.substringAfter('/'), plan.uiState?.state)
        }
        assertNull(VerificationHarness.plan("deniz", "no-such-state", null))
        assertNull(VerificationHarness.plan(null, "safety", null))
        assertEquals("no state: 6.1", "safety", VerificationHarness.plan("deniz", null, null)?.uiState?.state)
    }

    @Test
    fun stacksFollowTheIosRoutes() {
        fun stack(state: String) = VerificationHarness.plan("deniz", state, null)!!.stack
        assertEquals(listOf<AppRoute>(AppRoute.SafetyNumber(deniz)), stack("scan"))
        assertEquals(listOf<AppRoute>(AppRoute.SafetyNumber(deniz, ContactTab.Photos)), stack("photos"))
        assertEquals(listOf<AppRoute>(AppRoute.SafetyNumber(deniz, ContactTab.Documents)), stack("documents"))
        for (flow in listOf("flow-match", "flow-mismatch", "flow-manual", "flow-remove")) {
            assertEquals("$flow starts from 5.1", listOf(AppRoute.Conversation(deniz), AppRoute.SafetyNumber(deniz)), stack(flow))
        }
    }

    @Test
    fun preparationsSetTheServiceUp() = runBlocking {
        val service = fixtures()
        VerificationHarness.plan("deniz", "verified-manual", service)!!.prepare!!.invoke()
        assertEquals(com.syconx.us2us.domain.VerificationMethod.Manual, service.safetyNumber(deniz)?.method)
        val changed = fixtures()
        VerificationHarness.plan("deniz", "changed", changed)!!.prepare!!.invoke()
        assertTrue(changed.peer(deniz).value!!.safetyNumberChanged)
        assertNull("plain states change nothing", VerificationHarness.plan("deniz", "scan", service)!!.prepare)
    }

    @Test
    fun aStateIsAppliedOnce() {
        val ui = VerificationHarness.plan("deniz", "contact-scan", null)!!.uiState as VerificationUiState
        assertTrue(ui.isContact)
        assertTrue(ui.claim())
        assertFalse(ui.claim())
        val flow = VerificationHarness.plan("deniz", "flow-contact", null)!!.uiState as VerificationUiState
        assertTrue(flow.isContact)
        assertFalse((VerificationHarness.plan("deniz", "scan", null)!!.uiState as VerificationUiState).isContact)
    }
}
