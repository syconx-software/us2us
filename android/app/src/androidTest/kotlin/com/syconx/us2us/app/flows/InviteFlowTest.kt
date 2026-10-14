package com.syconx.us2us.app.flows

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Features/Invite: the invite sheet and its ways in (iOS `InviteUITests`). */
@RunWith(AndroidJUnit4::class)
class InviteFlowTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val app = AppDriver(compose)

    private fun launch(vararg arguments: Pair<String, String>, language: String = "tr", theme: String = "dark") =
        app.launch("U2Start" to "chats", *arguments, language = language, theme = theme)

    private fun assertChannels(titles: List<String>) {
        for ((id, title) in CHANNELS.zip(titles)) {
            val tag = "invite.channel.$id"
            app.waitForTag(tag)
            val label = app.label(tag)
            assertTrue("channel $id: “$label”", label.startsWith(title))
        }
    }

    /** `invite`: the message and the five channels; copy shows its status, editing works, “Kapat” closes. */
    @Test
    fun inviteSheetShowsTheMessageAndChannels() {
        launch("U2Route" to "invite")
        app.waitForTag("invite.channel.sms", timeoutMillis = 10_000)
        assertTrue(app.exists(AppDriver.hasTextLike("Davet et")))
        app.waitForTag("invite.preview")
        val text = app.value("invite.preview").orEmpty()
        assertTrue(text, text.contains("Kullanıcı adım: @selin_n"))
        assertTrue(text, text.contains("https://us2us.app/u/selin_n"))
        assertChannels(listOf("Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"))
        assertTrue(app.label("invite.channel.copy").contains("us2us.app/u/selin_n"))
        app.snap("invite-tr-dark")

        // Copy: the row says so (and that the clipboard forgets it).
        app.tap("invite.channel.copy")
        app.waitUntil("“Kopyalandı”") { app.label("invite.channel.copy").contains("Kopyalandı") }
        app.snap("invite-copied")

        // Edit, then done: the bubble shows the edited text.
        app.tap("invite.edit")
        app.waitForTag("invite.editor")
        app.type(" Görüşürüz!", tag = "invite.editor")
        app.snap("invite-editing")
        app.tap("invite.done")
        app.waitUntil("the edited message") { app.value("invite.preview").orEmpty().contains("Görüşürüz!") }

        app.tapText("Kapat")
        app.waitForTagGone("invite.channel.sms")
    }

    /** English UI: the English template and channel names. */
    @Test
    fun inviteSheetInEnglish() {
        launch("U2Route" to "invite", language = "en", theme = "light")
        app.waitForTag("invite.channel.sms", timeoutMillis = 10_000)
        app.waitForTag("invite.preview")
        assertTrue(app.value("invite.preview").orEmpty().contains("My username: @selin_n"))
        assertChannels(listOf("Messages", "WhatsApp", "Email", "More", "Copy link"))
        app.snap("invite-en-light")
    }

    /** “Diğer” opens the system share sheet over the invite sheet. */
    @Test
    fun moreReachesTheShareSheet() {
        launch("U2Route" to "invite")
        app.tap("invite.channel.more", timeoutMillis = 10_000)
        app.waitForSystemActivity()
        app.snap("invite-more-share-sheet")
        app.closeSystemActivity()
        app.waitForTag("invite.channel.sms")
    }

    /** 4.2: no exact match → “Davet et” opens the invitation inside the sheet; “Geri” returns. */
    @Test
    fun newChatNotFoundOffersAnInvite() {
        launch("U2Route" to "chats:4.2-notfound")
        app.tap("newChat.invite", timeoutMillis = 10_000)
        app.waitForTag("invite.channel.sms")
        assertChannels(listOf("Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"))
        app.snap("newchat-invite-page")
        app.tapText("Geri")
        app.waitForTag("newChat.invite")
    }

    /** 7.1 › Uygulama › “Arkadaşını davet et”. */
    @Test
    fun settingsRowOpensTheInvitation() {
        launch("U2Route" to "settings")
        app.waitForTag("settings.back", timeoutMillis = 10_000)
        app.tap("settings.invite")
        app.waitForTag("invite.channel.sms")
        assertChannels(listOf("Mesaj", "WhatsApp", "E-posta", "Diğer", "Bağlantıyı kopyala"))
    }

    /** “Kodum” sheet: “Davet bağlantını paylaş” is there. */
    @Test
    fun myCodeOffersToShareTheLink() {
        launch("U2Route" to "chats:4.1-mycode")
        app.waitForText("Davet bağlantını paylaş", timeoutMillis = 10_000)
        app.snap("mycode-share")
    }

    /** An invite link for deniz: 4.2 rises with the name searched and found; nothing is sent until “Mesaj yaz”. */
    @Test
    fun inviteLinkOpensNewChatSearched() {
        launch("U2Route" to "invite:link")
        app.waitForTag("newChat.write", timeoutMillis = 10_000)
        app.snap("link-deniz")
    }

    /** Links that are not invite links are ignored. */
    @Test
    fun foreignLinkIsIgnored() {
        launch("U2Route" to "chats", "U2OpenURL" to "https://evil.example/u/deniz")
        app.waitForTag("chats.new", timeoutMillis = 10_000)
        app.settle(2_000)
        assertTrue("4.2 does not rise", !app.exists("newChat.bar"))
    }

    private companion object {
        val CHANNELS = listOf("sms", "whatsapp", "email", "more", "copy")
    }
}
