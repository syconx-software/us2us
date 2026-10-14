package com.syconx.us2us.feature.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.shot.LocalShotSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.sheet.U2SheetTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 03 driven through the screens: the code keypad, the wait and its countdown, “Kodu unuttum” › erase, and 3.1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class LockInteractionTest {
    @get:Rule
    val host = ShotHost()

    private fun typeOnKeypad(code: String) {
        for (digit in code) {
            host.compose.onAllNodes(hasContentDescription(digit.toString()))[0].performClick()
            host.advance(16)
        }
    }

    private fun exists(tag: String): Boolean =
        host.compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSixthDigitSubmitsAndTheRightCodeUnlocks() {
        var fixture: LockFixture? = null
        host.render(ShotTheme.Dark, "tr") { LockApp(onFixture = { fixture = it }) }
        typeOnKeypad("111112")
        host.advance(400)
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        host.advance(1_000)
        typeOnKeypad(DesignFixtures.DEMO_SECRET_CODE)
        host.advance(400)
        val app = checkNotNull(fixture)
        assertTrue(app.model.calls.contains("didUnlock"))
        assertEquals(Overlay.None, app.model.overlay)
    }

    @Test
    fun theWaitCountsDownOnTheWallClockAndTheKeypadComesBack() {
        val clocks = TestClocks()
        var flow: LockFlowModel? = null
        host.render(ShotTheme.Dark, "tr") {
            val spec = checkNotNull(LocalShotSpec.current)
            val fixture = remember { LockFixture(english = spec.lang == "en", clocks = clocks) }
            val scope = rememberCoroutineScope()
            val model = remember {
                fixture.service.setWrongCodes(2)
                LockFlowModel(
                    messenger = fixture.service,
                    scope = scope,
                    biometry = Biometry.None,
                    onUnlock = fixture.model::didUnlock,
                    onErase = {},
                    now = { clocks.now },
                ).also { flow = it }
            }
            U2PreviewRoot(appModel = fixture.model, dark = spec.isDark, designInsets = spec.designInsets) {
                Box(Modifier.fillMaxSize()) { LockFlowContent(model, onRestore = {}, showsDemoHint = false) }
            }
        }
        typeOnKeypad("111112")
        host.advance(400)
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true).assertTextEquals("Çok fazla yanlış deneme. 01:00 sonra tekrar\u00A0dene.")
        host.compose.onAllNodes(hasContentDescription("1"))[0].assertIsNotEnabled()

        clocks.advance(15)
        host.advance(1_000)
        host.compose.onNodeWithTag(LockTags.MESSAGE, useUnmergedTree = true).assertTextEquals("Çok fazla yanlış deneme. 00:45 sonra tekrar\u00A0dene.")

        clocks.advance(46)
        host.advance(60_000)
        assertNull(checkNotNull(flow).code.message)
        host.compose.onAllNodes(hasContentDescription("1"))[0].assertIsEnabled()
    }

    @Test
    fun forgotThenEraseAsksTwiceThenErases() {
        var fixture: LockFixture? = null
        host.render(ShotTheme.Dark, "tr") { LockApp(onFixture = { fixture = it }) }
        host.compose.onNodeWithTag(LockTags.FORGOT).performClick()
        host.advance(1_000)
        assertTrue(exists(U2SheetTags.SHEET))
        host.compose.onNodeWithTag(LockTags.FORGOT_ERASE).performClick()
        host.advance(1_000)
        assertTrue(exists(LockTags.ERASE_CONFIRM))
        host.compose.onNodeWithTag(LockTags.ERASE_CANCEL).performClick()
        host.advance(1_000)
        assertTrue(exists(LockTags.FORGOT_RESTORE))

        host.compose.onNodeWithTag(LockTags.FORGOT_ERASE).performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag(LockTags.ERASE_CONFIRM).performClick()
        host.advance(1_500)
        val app = checkNotNull(fixture)
        assertTrue(app.model.calls.contains("eraseEverything"))
        assertNull(app.service.account.value)
        assertEquals(Phase.Onboarding, app.model.phase)
    }

    @Test
    fun retryClosesTheSheetAndRestoreHandsOffTo73() {
        // `U2PickBackup`: the lock's picker “picks” the stand-in backup, then 7.3 takes over with it.
        host.render(ShotTheme.Dark, "tr") { LockApp(configuration = LaunchConfig(pickBackup = true)) }
        host.compose.onNodeWithTag(LockTags.FORGOT).performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag(LockTags.FORGOT_RETRY).performClick()
        host.advance(1_000)
        assertTrue(!exists(LockTags.FORGOT_RESTORE))
        host.compose.onNodeWithTag(LockTags.FORGOT).performClick()
        host.advance(1_000)
        host.compose.onNodeWithTag(LockTags.FORGOT_RESTORE).performClick()
        host.advance(1_500)
        assertTrue(exists(RESTORE_SCREEN_TAG))
    }

    @Test
    fun faceIdFallsBackToTheCodeAndTheKeyGoesBack() {
        var fixture: LockFixture? = null
        host.render(ShotTheme.Dark, "tr") {
            LockApp(
                configuration = LaunchConfig(),
                onFixture = {
                    it.service.biometry = Biometry.Face
                    it.service.biometricsResult = false
                    fixture = it
                },
            )
        }
        // 3.1 scanned at once (the app is active) and was refused: after its dwell, 3.2.
        assertTrue("a refused scan leaves for the code", exists(LockTags.KEYPAD))
        assertTrue(!exists(LockTags.BIOMETRIC_DISC))
        checkNotNull(fixture).service.biometricsResult = true
        host.compose.onNodeWithContentDescription("Biyometrik ile aç", substring = true).assertExists()
        host.compose.onAllNodes(hasContentDescription("Biyometrik", substring = true))[0].performClick()
        host.advance(1_000)
        assertTrue(checkNotNull(fixture).model.calls.contains("didUnlock"))
    }
}
