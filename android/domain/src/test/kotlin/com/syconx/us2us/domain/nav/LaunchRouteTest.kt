package com.syconx.us2us.domain.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The launch arguments' grammar, the same as the iOS `LaunchRoute` and `AppConfiguration`. */
class LaunchRouteTest {
    @Test
    fun `routes parse feature, target and state`() {
        assertEquals(LaunchRoute(LaunchRoute.Feature.Conversation, "deniz", "5.3"), LaunchRoute.parse("conversation:deniz/5.3"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.Conversation, "mert_k", null), LaunchRoute.parse("conversation:mert_k"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.Settings, null, "7.2-weak"), LaunchRoute.parse("settings:7.2-weak"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.Lock, null, "throttled"), LaunchRoute.parse("lock:throttled"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.Chats, null, null), LaunchRoute.parse("chats"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.NewChat, "", null), LaunchRoute.parse("newChat"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.NewChat, "deniz", null), LaunchRoute.parse("newChat:deniz"))
        assertEquals(LaunchRoute(LaunchRoute.Feature.Safety, "deniz", "contact-scan"), LaunchRoute.parse("safety:deniz/contact-scan"))
        assertNull(LaunchRoute.parse("conversation"))
        assertNull(LaunchRoute.parse("unknown:x"))
        assertEquals("conversation:deniz/5.3", LaunchRoute.parse("conversation:deniz/5.3").toString())
        assertEquals("invite:link-unknown", LaunchRoute.parse("invite:link-unknown").toString())
    }

    @Test
    fun `routes imply their start and their stack`() {
        assertEquals(StartScreen.Welcome, LaunchRoute.parse("onboarding:2.1")!!.impliedStart)
        assertEquals(StartScreen.Locked, LaunchRoute.parse("lock:code")!!.impliedStart)
        assertEquals(StartScreen.Chats, LaunchRoute.parse("settings")!!.impliedStart)
        assertEquals(listOf(AppRoute.Requests, AppRoute.RequestPreview(RequestId("kuzey_dev"))), LaunchRoute.parse("request:kuzey_dev")!!.path)
        assertEquals(listOf(AppRoute.Settings, AppRoute.BackupExport), LaunchRoute.parse("backup")!!.path)
        assertEquals(listOf(AppRoute.SafetyNumber(ChatId("deniz"))), LaunchRoute.parse("safety:deniz/scan")!!.path)
        assertEquals("5.3", LaunchRoute.parse("conversation:deniz/5.3")!!.stateFor(LaunchRoute.Feature.Conversation))
        assertNull(LaunchRoute.parse("conversation:deniz/5.3")!!.stateFor(LaunchRoute.Feature.Settings))
    }

    @Test
    fun `the configuration reads the iOS arguments`() {
        val arguments = mapOf(
            "U2Route" to "lock:wrong", "U2Start" to "chats", "U2Theme" to "light", "U2Language" to "en",
            "U2FrozenClock" to "YES", "U2Fixtures" to "true", "U2Gallery" to "stickers", "U2DesignInsets" to "1",
            "U2ConversationDemoDelay" to "2.5",
        )
        val config = LaunchConfig.from { arguments[it] }
        // The lock harness always starts locked.
        assertEquals(StartScreen.Locked, config.start)
        assertEquals("light", config.theme)
        assertEquals("en", config.language)
        assertTrue(config.frozenClock)
        assertTrue(config.usesFixtures)
        assertTrue(config.showsStickerGallery)
        assertFalse(config.showsGallery)
        assertTrue(config.designInsets)
        assertEquals(2.5, config.conversationDemoDelay)
        assertEquals(StartScreen.Chats, LaunchConfig.from { if (it == "U2Route") "settings" else null }.start)
        assertEquals(StartScreen.Welcome, LaunchConfig.from { null }.start)
        assertNull(LaunchConfig.from { if (it == "U2Theme") "sepia" else null }.theme)
        assertTrue("U2AllowCapture" in LaunchConfig.argumentNames)
    }
}
