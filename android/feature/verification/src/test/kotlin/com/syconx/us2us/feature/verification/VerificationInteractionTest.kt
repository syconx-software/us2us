package com.syconx.us2us.feature.verification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2FadeCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 06 driven by taps, as a person would: scanning, marking, the tabs, add by QR and the scripted flows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class VerificationInteractionTest {
    private var rig = VerificationRig()

    @get:Rule
    val host = ShotHost(environment = verificationShots { rig })

    private val deniz = ChatId("deniz")

    private fun open(route: String, fromChat: Boolean = false, content: @Composable () -> Unit = { VerificationApp(rig) }) {
        rig = VerificationRig()
        rig.apply(route)
        if (fromChat) rig.router.setPath(listOf(AppRoute.Conversation(deniz)) + rig.router.path)
        host.render(ShotTheme.Dark, "tr", content = content)
    }

    private fun shows(tag: String): Boolean =
        host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun tap(tag: String) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        host.advance(1_200)
    }

    @Test
    fun aMatchVerifiesAndSohbeteDonGoesBackToTheChat() {
        // 6.1 on 5.1, as from the chat header.
        open("safety:deniz/safety", fromChat = true)
        assertEquals(listOf(AppRoute.Conversation(deniz), AppRoute.SafetyNumber(deniz)), rig.router.path)
        tap(VerificationTags.SCAN)
        assertTrue(shows(VerificationTags.SCAN_SCREEN))
        tap(VerificationTags.SIMULATOR_MOCK)
        assertTrue(shows(VerificationTags.VERIFIED_SCREEN))
        assertFalse("the cover faded away", shows(VerificationTags.SCAN_SCREEN))
        assertTrue(rig.fixtures.peer(deniz).value!!.isVerified)
        tap(VerificationTags.VERIFIED_BACK)
        assertEquals(listOf<AppRoute>(AppRoute.Conversation(deniz)), rig.router.path)
    }

    @Test
    fun markingByHandAsksFirst() {
        open("safety:deniz/safety")
        tap(VerificationTags.MARK_VERIFIED)
        assertTrue(shows(VerificationTags.CONFIRM))
        tap(VerificationTags.CONFIRM_CANCEL)
        assertFalse(shows(VerificationTags.VERIFIED_SCREEN))
        tap(VerificationTags.MARK_VERIFIED)
        tap(VerificationTags.CONFIRM)
        assertTrue(shows(VerificationTags.VERIFIED_SCREEN))
        assertTrue(shows(VerificationTags.VERIFIED_PILL))
        // Opened without the chat under it: “Sohbete dön” puts the chat in its place.
        tap(VerificationTags.VERIFIED_CLOSE)
        assertEquals(listOf<AppRoute>(AppRoute.Conversation(deniz)), rig.router.path)
    }

    @Test
    fun removingAVerificationAsksAndTakesTheSealAway() {
        open("safety:deniz/safety-verified")
        assertTrue(shows(VerificationTags.VERIFIED_SINCE))
        tap(VerificationTags.MARK_VERIFIED)
        tap("confirm.destructive")
        assertFalse(rig.fixtures.peer(deniz).value!!.isVerified)
        assertFalse(shows(VerificationTags.VERIFIED_SINCE))
    }

    @Test
    fun aWrongCodeWarnsAndTheOwnCodeComesAndGoes() {
        open("safety:deniz/scan")
        host.compose.onNodeWithTag(VerificationTags.SIMULATOR_MOCK, useUnmergedTree = true).performTouchInput { longClick() }
        host.advance(1_200)
        assertTrue(shows(VerificationTags.WARNING))
        tap(VerificationTags.WARNING)
        assertFalse(shows(VerificationTags.WARNING))
        host.compose.onNodeWithTag(VerificationTags.TORCH, useUnmergedTree = true).assert(isNotSelected())
        tap(VerificationTags.TORCH)
        host.compose.onNodeWithTag(VerificationTags.TORCH, useUnmergedTree = true).assert(isSelected())
        tap(VerificationTags.SHOW_MY_CODE)
        assertTrue(shows(VerificationTags.OWN_CODE))
        assertFalse("no torch on the own code", shows(VerificationTags.TORCH))
        tap(VerificationTags.BACK_TO_SCANNING)
        assertTrue(shows(VerificationTags.VIEWFINDER))
        tap(VerificationTags.SCAN_CLOSE)
        assertFalse(shows(VerificationTags.SCAN_SCREEN))
    }

    @Test
    fun theTabsShowTheChatsMedia() {
        open("safety:deniz/safety")
        tap(VerificationTags.tab("Photos"))
        assertTrue(shows(VerificationTags.PHOTOS))
        tap(VerificationTags.tile("d2"))
        assertTrue("the gallery opens over the grid", shows(VerificationTags.GALLERY))
        assertEquals(listOf<AppRoute>(AppRoute.SafetyNumber(deniz)), rig.router.path)
    }

    @Test
    fun backPopsTheContactScreen() {
        open("safety:deniz/safety")
        tap(VerificationTags.BACK)
        assertTrue(rig.router.path.isEmpty())
    }

    @Test
    fun addingByQrVerifiesAndOpensTheChat() {
        open("safety:deniz/safety") {
            var presented by remember { mutableStateOf(true) }
            VerificationApp(rig)
            U2FadeCover(isPresented = presented, onEscape = { presented = false }) {
                QrScanCover(QrScanMode.AddContact, onDismiss = { presented = false })
            }
        }
        tap(VerificationTags.SIMULATOR_MOCK)
        assertTrue(shows(VerificationTags.VERIFIED_SCREEN))
        tap(VerificationTags.VERIFIED_BACK)
        assertEquals(AppRoute.Conversation(deniz), rig.router.path.last())
    }

    @Test
    fun theScriptedManualFlowPlaysToTheChat() {
        open("safety:deniz/flow-manual")
        host.advance(VerificationHarness.STEP_MILLIS)
        assertTrue(shows(VerificationTags.CONFIRM))
        host.advance(VerificationHarness.STEP_MILLIS)
        assertTrue(shows(VerificationTags.VERIFIED_SCREEN))
        assertEquals(VerificationMethod.Manual, kotlinx.coroutines.runBlocking { rig.fixtures.safetyNumber(deniz) }?.method)
        host.advance(VerificationHarness.STEP_MILLIS)
        assertEquals(listOf<AppRoute>(AppRoute.Conversation(deniz)), rig.router.path)
    }
}
