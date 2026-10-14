package com.syconx.us2us.app.flows

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 07 · settings flows with the design fixtures (iOS `SettingsFlowUITests`, `BlockedPeopleUITests`). */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val app = AppDriver(compose)

    private fun launch(vararg arguments: Pair<String, String>) = app.launch("U2Start" to "chats", *arguments)

    /**
     * 7.1 › Yedek → 7.2: a short and a guessable password are refused with their reasons, then it is
     * asked twice, the mismatch is shown, then the system share sheet with the .u2u file.
     */
    @Test
    fun backupExportAsksTwiceAndOpensTheShareSheet() {
        launch("U2Route" to "settings")
        app.tap("settings.backup", timeoutMillis = 10_000)
        app.waitForText("YEDEĞE GİRECEKLER")

        app.type("kedi12", tag = "backup.password")
        app.tap("backup.export")
        app.waitForText("Yedek parolası en az 10 karakter olmalı.")

        app.replace("Ahmet1985!", tag = "backup.password")
        app.tap("backup.export")
        app.waitForText("Bu parola kolay tahmin edilir.", substring = true)

        app.replace("v7qz3kd9xw2mtr", tag = "backup.password")
        app.tap("backup.export")
        app.waitForTag("backup.password.confirm")
        app.type("v7qz3kd9xw2m", tag = "backup.password.confirm")
        app.tap("backup.export")
        app.waitForText("Parolalar eşleşmedi.")

        app.type("tr", tag = "backup.password.confirm")
        app.tap("backup.export")
        app.waitForSystemActivity()
        app.snap("7.2 share sheet")
        app.closeSystemActivity()
    }

    /**
     * 7.2: the screen says what the backup holds (no switches); “Güçlü parola oluştur” fills the field
     * and shows the password with “Kopyala”; the confirmation is still asked.
     */
    @Test
    fun generatedPasswordIsShownCopiedAndConfirmed() {
        launch("U2Route" to "settings:7.2-empty")
        app.waitForTag("backup.password.generate", timeoutMillis = 10_000)
        assertTrue(app.exists("backup.contents.footnote"))

        app.tap("backup.password.generate")
        app.waitForTag("backup.password.generated")
        // Hidden until “Göster”: TalkBack hears “Gizli”, never the password.
        assertEquals("Gizli", app.value("backup.password.generated"))
        app.tap("backup.password.reveal")
        // Read out character by character once shown.
        app.waitUntil("the password shown") { app.value("backup.password.generated") != "Gizli" }
        val generated = checkNotNull(app.value("backup.password.generated")).replace(" ", "")
        assertEquals(24, generated.length)
        assertEquals(4, generated.count { it == '-' })

        app.tap("backup.password.copy")
        app.waitForTag("backup.password.copied")
        assertTrue(app.label("backup.password.copied"), app.label("backup.password.copied").contains("Kopyalandı · yalnızca bu cihazda, 1 dk sonra silinir"))

        app.tap("backup.export")
        app.waitForTag("backup.password.confirm")
        app.type(generated, tag = "backup.password.confirm")
        app.tap("backup.export")
        app.waitForSystemActivity()
        app.closeSystemActivity()
    }

    /** 7.1 › Gizli kodu değiştir: current (through the lock's throttle) → new ×2 → back on 7.1; the lock then wants the new code. */
    @Test
    fun changingTheSecretCode() {
        launch("U2Route" to "settings")
        app.tap("settings.changeCode", timeoutMillis = 10_000)
        app.waitForText("Mevcut kodunu gir")
        app.typeCode("135790")
        app.waitForText("Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        app.typeCode("246813")
        app.waitForText("Yeni kodunu belirle")
        app.typeCode("123456")
        app.waitForText("Bu kod çok kolay tahmin edilir. Başka bir kod seç.")
        app.typeCode("471958")
        app.waitForText("Yeni kodu tekrar gir")
        app.typeCode("471958")
        app.waitForText("Gizli kodun değişti.", timeoutMillis = 8_000)

        app.backgroundAndReturn()
        app.waitForText("Gizli kodunu gir")
        app.typeCode("471958")
        app.waitForText("Ayarlar")
    }

    /** 7.1 › Aktif durumu → Kimse (reciprocal). */
    @Test
    fun presencePicker() {
        launch("U2Route" to "settings")
        app.tap("settings.presence", timeoutMillis = 10_000)
        app.waitForText("Kimse görmez; sen de kimsenin aktif durumunu görmezsin.")
        app.tap("picker.option.1")
        app.waitUntil("“Kimse” selected") { app.isSelected("picker.option.1") }
    }

    /** 7.1 › Her şeyi sil → second confirmation → 1.1. */
    @Test
    fun eraseEverythingReturnsToWelcome() {
        launch("U2Route" to "settings")
        app.tap("settings.erase", timeoutMillis = 10_000)
        app.waitForTag("settings.confirm.destructive")
        // Tap once the floating sheet has settled.
        app.settle(800)
        app.tapUntil("settings.confirm.destructive", "erased to 1.1") { app.exists("welcome.start") }
    }

    /** 7.3: wrong password → “Parola yanlış”; right one → restore → “Devam”. */
    @Test
    fun restoreWithWrongThenRightPassword() {
        launch("U2Route" to "settings:7.3-password")
        app.type("yanlis-parola", tag = "restore.password")
        app.tap("restore.action.submit")
        app.waitForText("Parola yanlış")
        app.replace("kuzey7deniz42k", tag = "restore.password")
        app.tap("restore.action.submit")
        app.waitForText("Parola doğru · yedek açıldı")
        app.waitForTag("restore.action.continue", timeoutMillis = 20_000)
    }

    /**
     * 7.1 › Gizlilik › Engellenenler (`settings:7.1-blocked`: bora blocked with the chat still in
     * 4.1, lale blocked after Ghost erased the chat): the count, both people, “Engeli kaldır” with no
     * confirmation for each, the empty state, the row without a count, bora's chat no longer blocked.
     */
    @Test
    fun unblockingEveryoneFromSettingsEndsOnTheEmptyState() {
        launch("U2Route" to "settings:7.1-blocked")
        app.waitForTag("settings.blocked", timeoutMillis = 10_000)
        val row = app.label("settings.blocked")
        assertTrue(row, row.startsWith("Engellenenler"))
        assertTrue("the row counts who is blocked: $row", row.contains("2"))
        app.snap("01 7.1 Engellenenler 2")

        app.tap("settings.blocked")
        app.waitForTag("blocked.unblock.bora")
        assertTrue("lale, whose chat is gone", app.exists("blocked.unblock.lale"))
        assertTrue(app.exists(AppDriver.hasTextLike("lale")))
        assertFalse(app.exists("blocked.empty"))
        app.snap("02 Engellenenler")

        // No confirmation: the row leaves at once, the other stays.
        app.tap("blocked.unblock.lale")
        app.waitForTagGone("blocked.unblock.lale")
        assertTrue(app.exists("blocked.unblock.bora"))
        app.snap("03 lale unblocked")

        app.tap("blocked.unblock.bora")
        app.waitForTag("blocked.empty")
        assertEquals("Engellediğin kimse yok.", app.label("blocked.empty"))
        app.waitForTagGone("blocked.list")
        app.snap("04 empty")

        // 7.1 counts nobody; 4.1 has bora's chat back, not blocked.
        app.tap("blocked.back")
        app.waitUntil("no count with nobody blocked") { !app.label("settings.blocked").contains("2") }
        app.tap("settings.back")
        app.waitForTag("chat.bora")
        assertFalse(app.label("chat.bora"), app.label("chat.bora").contains("Engellendi"))
        assertFalse("lale has no chat", app.exists("chat.lale"))
    }

    /** Someone blocked from their contact screen is listed, and unblocking them there gives 5.1 its composer back. */
    @Test
    fun someoneBlockedFromTheirContactScreenIsListedAndUnblockedThere() {
        launch("U2Route" to "conversation:deniz")
        app.tap("chatHeader.peer", timeoutMillis = 10_000)
        app.tap("contact.more")
        app.tap("menu.block")
        app.tap("contact.block.confirm")
        app.waitForTag("contact.blocked")
        app.tap("safety.back")
        app.waitForTag("conversation.blocked")
        app.tap("chatHeader.back")
        app.tap("chats.settings")
        app.waitForTag("settings.blocked")
        assertTrue(app.label("settings.blocked"), app.label("settings.blocked").contains("1"))
        app.tap("settings.blocked")
        app.tap("blocked.unblock.deniz")
        app.waitForTag("blocked.empty")
        app.tap("blocked.back")
        app.tap("settings.back")
        app.tap("chat.deniz")
        app.waitForTag("chatHeader.peer")
        app.settle()
        assertFalse("the composer is back", app.exists("conversation.blocked"))
    }
}
