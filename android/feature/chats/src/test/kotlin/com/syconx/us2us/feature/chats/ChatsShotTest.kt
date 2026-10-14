package com.syconx.us2us.feature.chats

import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.sheet.U2SheetTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn screens of 04 · Sohbetler against `ios/design/reference` and the layout JSON: 4.1 (and
 * its light 8.1, English 9.1 and urgent variants), 4.2 and 4.3, dark and light. The frames are the
 * gate (±1 dp); `scripts/compare_shots.py` diffs the pictures for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ChatsShotTest {
    @get:Rule
    val host = ShotHost()

    private fun assertListFrames() {
        host.assertFrame(ChatsTags.SETTINGS, element = "r.0.0")
        host.assertFrame(ChatsTags.QR, element = "r.0.1.0")
        host.assertFrame(ChatsTags.NEW, element = "r.0.1.1")
        host.assertFrame(ChatsTags.TITLE, element = "r.1.0")
        host.assertFrame(ChatsTags.CONNECTION, element = "r.1.1")
        host.assertFrame(ChatsTags.REQUESTS, element = "r.2")
        host.assertFrame(ChatsTags.chat(ChatId("deniz")), element = "r.3")
        host.assertFrame(ChatsTags.chat(ChatId("mert_k")), element = "r.5")
        host.assertFrame(ChatsTags.chat(ChatId("ayla_")), element = "r.7")
        host.assertFrame(ChatsTags.chat(ChatId("kaan")), element = "r.9")
        host.assertFrame(ChatsTags.chat(ChatId("ece_y")), element = "r.11")
        host.assertFrame(ChatsTags.chat(ChatId("bora")), element = "r.13")
        host.assertFrame(ChatsTags.chat(ChatId("oya_t")), element = "r.15")
    }

    @Test
    fun chatList() {
        for (theme in ShotTheme.entries) {
            host.shoot("4.1-sohbet-listesi", theme, "tr") { ChatsApp("chats:4.1") }
            assertListFrames()
        }
        host.shoot("8.1-sohbet-listesi-acik", ShotTheme.Light, "tr") { ChatsApp("chats:4.1") }
        assertListFrames()
    }

    @Test
    fun chatListEnglish() {
        for (theme in ShotTheme.entries) {
            host.shoot("4.1-sohbet-listesi", theme, "en", design = "9.1-chats-en") { ChatsApp("chats:4.1") }
            host.assertFrame(ChatsTags.TITLE, element = "r.1.0")
            host.assertFrame(ChatsTags.REQUESTS, element = "r.2")
            host.assertFrame(ChatsTags.chat(ChatId("deniz")), element = "r.3")
        }
    }

    @Test
    fun chatListUrgent() {
        for (theme in ShotTheme.entries) {
            host.shoot("4.1-sohbet-listesi-urgent", theme, "tr") { ChatsApp("chats:4.1", ghostUrgent = true) }
            assertListFrames()
            host.shoot("4.1-sohbet-listesi-urgent", theme, "en", design = "9.1-chats-en-urgent") { ChatsApp("chats:4.1", ghostUrgent = true) }
        }
        host.shoot("8.1-sohbet-listesi-acik-urgent", ShotTheme.Light, "tr") { ChatsApp("chats:4.1", ghostUrgent = true) }
    }

    @Test
    fun newChat() {
        for (theme in ShotTheme.entries) {
            host.shoot("4.2-yeni-sohbet", theme, "tr") { ChatsApp("chats:4.2") }
            host.assertFrame(U2SheetTags.SHEET, element = "r.1")
            host.assertFrame(U2SheetTags.GRABBER, element = "r.1.0")
            host.assertFrame(NewChatTags.BAR, element = "r.1.1")
            host.assertFrame(NewChatTags.FIELD, element = "r.1.2.0")
            host.assertFrame(NewChatTags.HINT, element = "r.1.2.1")
            host.assertFrame(NewChatTags.RESULT, element = "r.1.3")
            host.assertFrame(NewChatTags.WRITE, element = "r.1.3.1")
            host.assertFrame(NewChatTags.OPTIONS, element = "r.1.5.0.0")
            host.assertFrame(NewChatTags.SCAN, element = "r.1.5.0.0.0")
            host.assertFrame(NewChatTags.MY_CODE, element = "r.1.5.0.0.2")
        }
    }

    @Test
    fun requests() {
        for (theme in ShotTheme.entries) {
            host.shoot("4.3-mesaj-istekleri", theme, "tr") { ChatsApp("chats:4.3") }
            host.assertFrame(RequestsTags.BACK, element = "r.0.0")
            host.assertFrame(RequestsTags.MORE, element = "r.0.2")
            host.assertFrame(RequestsTags.BANNER, element = "r.1")
            val ids = listOf("kuzey_dev", "n_aksoy", "hediye_kazan").map(::RequestId)
            ids.forEachIndexed { index, id ->
                host.assertFrame(RequestsTags.container(id), element = "r.2.$index")
                host.assertFrame(RequestsTags.card(id), element = "r.2.$index.0")
                host.assertFrame(RequestsTags.block(id), element = "r.2.$index.1.0")
                host.assertFrame(RequestsTags.delete(id), element = "r.2.$index.1.1")
                host.assertFrame(RequestsTags.accept(id), element = "r.2.$index.1.2")
            }
        }
    }
}
