package com.syconx.us2us.app.flows

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 03 · Kilit end to end with the design fixtures (iOS `LockUITests`). The emulator has no enrolled
 * biometrics, so the lock opens on the code keypad.
 */
@RunWith(AndroidJUnit4::class)
class LockFlowTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val app = AppDriver(compose)

    private fun launchLocked() {
        app.launch("U2Start" to "locked")
        app.waitForText("Gizli kodunu gir", timeoutMillis = 10_000)
    }

    /** Third wrong code → the keypad waits with a live countdown. */
    @Test
    fun thirdWrongCodeWaits() {
        launchLocked()
        repeat(3) { attempt ->
            app.typeCode("111112")
            if (attempt < 2) app.waitForTag("lock.message")
        }
        app.waitForText("Çok fazla yanlış deneme.", substring = true)
        app.waitUntil("the keypad waits") { !app.isKeyEnabled('1') }
    }

    /** 3.3: “Tekrar dene” closes; erase asks a second time; “Vazgeç” goes back; confirming wipes and returns to 1.1. */
    @Test
    fun forgotCodeEraseNeedsASecondConfirmation() {
        launchLocked()
        app.tap("lock.forgot")
        app.waitForText("Kod kurtarılamaz.")
        app.tap("lock.forgot.retry")
        app.waitForTextGone("Kod kurtarılamaz.")

        app.tap("lock.forgot")
        app.tap("lock.forgot.erase")
        app.waitForTag("lock.erase.confirm")
        assertTrue(app.exists(AppDriver.hasTextLike("Her şey silinsin mi?")))
        app.waitForTagGone("lock.forgot.erase")
        app.tap("lock.erase.cancel")
        app.waitForTag("lock.forgot.erase")
        app.waitForTagGone("lock.erase.confirm")

        app.tap("lock.forgot.erase")
        app.waitForTag("lock.erase.confirm")
        // Armed after its cross-fade.
        app.settle(800)
        app.tapUntil("lock.erase.confirm", "erased to 1.1") { app.exists("welcome.start") }
    }
}
