package com.syconx.us2us.app.flows

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end walks through the shell with the design fixtures (iOS `AppFlowUITests`). */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val app = AppDriver(compose)

    /** 2.1 → 2.2 (set, confirm) → 2.3, once the welcome pages are behind. */
    private fun createAccount(name: String) {
        app.tap("welcome.start", timeoutMillis = 10_000)
        app.tap("welcome.page2.cta")
        app.tap("welcome.page3.cta")
        app.type(name)
        app.waitUntil("“Devam” enabled") { app.isEnabled("onboarding.username.continue") }
        app.tap("onboarding.username.continue")
        app.typeCode("246813")
        app.waitForText("Kodu tekrar gir")
        app.typeCode("246813")
        app.tap("onboarding.acknowledge")
    }

    /** 1.1 → 1.2 → 1.3 → 2.1 → 2.2 (set, confirm) → 2.3 → 4.1. */
    @Test
    fun onboardingReachesTheChatList() {
        app.launch("U2Start" to "welcome")
        createAccount("ada_k")
        app.tap("onboarding.finish")
        app.waitForText("Sohbetler")
        assertTrue(app.exists("chat.deniz"))
    }

    /**
     * 2.1 “Kurallara uygun” → 2.2 → 2.3: the name was taken meanwhile → “Başka bir ad seç” → 2.1,
     * the name kept and marked → another name → 2.3 again with the same code → 4.1.
     */
    @Test
    fun nameTakenAtSetupChoosesAnother() {
        app.launch("U2Start" to "welcome")
        app.tap("welcome.start", timeoutMillis = 10_000)
        app.tap("welcome.page2.cta")
        app.tap("welcome.page3.cta")
        app.type("ilgaz")
        app.waitForText("Kurallara uygun")
        app.waitUntil("“Devam” enabled") { app.isEnabled("onboarding.username.continue") }
        app.tap("onboarding.username.continue")
        app.typeCode("246813")
        app.waitForText("Kodu tekrar gir")
        app.typeCode("246813")
        app.tap("onboarding.acknowledge")
        app.tap("onboarding.finish")
        app.waitForText("Bu kullanıcı adı alınmış.")
        app.waitUntil("“Başka bir ad seç”") { app.label("onboarding.finish").contains("Başka bir ad seç") }
        app.tap("onboarding.finish")

        // 2.1: the name is still there, taken.
        app.waitForText("Alınmış")
        app.waitForText("ilgaz")
        assertFalse(app.isEnabled("onboarding.username.continue"))
        app.type("_k")
        app.waitUntil("“Devam” enabled") { app.isEnabled("onboarding.username.continue") }
        app.tap("onboarding.username.continue")

        // 2.3 again, without the code being asked for.
        app.waitUntil("“Kurulumu tamamla”") { app.label("onboarding.finish").contains("Kurulumu tamamla") }
        assertFalse("2.2 is not shown again", app.exists(AppDriver.hasTextLike("Gizli kodunu belirle")))
        app.waitUntil("“Kurulumu tamamla” enabled") { app.isEnabled("onboarding.finish") }
        app.tap("onboarding.finish")
        app.waitForText("Sohbetler")
    }

    /** Launch locked → the code (wrong once, then right) → 4.1. */
    @Test
    fun lockScreenUnlocksWithTheCode() {
        app.launch("U2Start" to "locked")
        app.waitForText("Gizli kodunu gir")
        app.typeCode("111112")
        app.waitForText("Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        app.typeCode("246813")
        app.waitForText("Sohbetler")
    }

    /** 4.1 → 5.1 → back; 4.1 → 7.1 → theme picker. */
    @Test
    fun mainStackNavigation() {
        app.launch("U2Start" to "chats")
        app.tap("chat.deniz", timeoutMillis = 10_000)
        app.waitForText("Kapı kodu 4471")
        app.tap("chatHeader.back")
        app.waitForText("Sohbetler")
        app.tap("chats.settings")
        app.waitForText("Ayarlar")
        app.tap("settings.theme")
        app.tap("picker.option.2")
        app.waitUntil("“Açık” selected") { app.isSelected("picker.option.2") }
        app.tap("picker.option.1")
        app.waitUntil("“Koyu” selected") { app.isSelected("picker.option.1") }
    }

    /** Every return from the background locks. */
    @Test
    fun returningFromBackgroundLocks() {
        app.launch("U2Start" to "chats")
        app.waitForText("Sohbetler", timeoutMillis = 10_000)
        app.backgroundAndReturn()
        app.waitForText("Gizli kodunu gir")
    }

    /** The English UI (09). */
    @Test
    fun english() {
        app.launch("U2Start" to "chats", language = "en")
        app.waitForText("Chats", timeoutMillis = 10_000)
        assertTrue(app.exists(AppDriver.hasTextLike("Line open · messages arriving")))
        assertEquals(false, app.exists(AppDriver.hasTextLike("Sohbetler")))
    }
}
