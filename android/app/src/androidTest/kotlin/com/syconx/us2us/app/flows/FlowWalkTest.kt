package com.syconx.us2us.app.flows

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Walks the flows of `ios/design/flows.md` end to end with real taps on the design fixtures,
 * keeping a picture at every step (iOS `FlowWalkUITests`; the pictures land in the app's external
 * files, `flows/<test>/`).
 *
 * The walks through the scanner (iOS 07 and 09) need the simulator's stand-in for the camera; the
 * emulator has a camera, so they are left to the scanner's own tests.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FlowWalkTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val app = AppDriver(compose)

    // MARK: 01–02 · Account creation

    /** 1.1 → 1.2 → 1.3 → 2.1 (taken → available) → 2.2 weak → set → confirm mismatch → set → confirm → 2.3 → 4.1. */
    @Test
    fun test01CreateAccount() {
        app.launch("U2Start" to "welcome")
        app.waitForTag("welcome.start", timeoutMillis = 10_000)
        app.snap("01 1.1 Karşılama")
        app.tap("welcome.start")
        app.settle()
        app.snap("02 1.2 Bildirim yok")
        app.tap("welcome.page2.cta")
        app.settle()
        app.snap("03 1.3 Sessizlik siler")
        app.tap("welcome.page3.cta")
        app.type("selin")
        app.waitForText("Alınmış")
        app.snap("04 2.1 Alınmış")
        app.type("_k")
        app.waitUntil("“Devam” enabled") { app.isEnabled("onboarding.username.continue") }
        app.snap("05 2.1 Kullanılabilir")
        app.tap("onboarding.username.continue")
        app.waitForText("Gizli kodunu belirle")
        app.snap("06 2.2 Gizli kod")
        app.typeCode("111111")
        app.settle(600)
        app.snap("07 2.2 Zayıf kod")
        app.settle(1_200)
        app.typeCode("246813")
        app.waitForText("Kodu tekrar gir")
        app.snap("08 2.2 Onay")
        app.typeCode("246814")
        app.settle(600)
        app.snap("09 2.2 Eşleşmedi")
        app.waitForText("Gizli kodunu belirle")
        app.settle(1_000)
        app.typeCode("246813")
        app.waitForText("Kodu tekrar gir")
        app.typeCode("246813")
        app.waitForTag("onboarding.acknowledge")
        app.settle()
        app.snap("10 2.3 Geri dönüş yok")
        app.tap("onboarding.acknowledge")
        app.tap("onboarding.finish")
        app.waitForText("Sohbetler", timeoutMillis = 8_000)
        app.settle()
        app.snap("11 4.1 Sohbetler")
    }

    /** 1.1 “Yedekten geri yükle” → (picker) → 7.3 password → restore → 2.2 ×2 (“1 / 2”) → 2.3 (“2 / 2”) → 4.1. */
    @Test
    fun test02RestoreOnANewDevice() {
        app.launch("U2Start" to "welcome", "U2PickBackup" to "YES")
        app.tap("welcome.restore", timeoutMillis = 10_000)
        app.waitForTag("restore.password")
        app.settle()
        app.snap("01 7.3 Parola")
        app.type("yanlis-parola", tag = "restore.password")
        app.tap("restore.action.submit")
        app.waitForText("Parola yanlış")
        app.settle(1_200)
        app.snap("02 7.3 Parola yanlış")
        app.replace("kuzey7deniz42k", tag = "restore.password")
        app.tap("restore.action.submit")
        app.settle(3_000)
        app.snap("03 7.3 Geri yükleniyor")
        app.tap("restore.action.continue", timeoutMillis = 20_000)
        app.waitForText("Gizli kodunu belirle")
        app.settle()
        app.snap("04 2.2 Yeni kod 1-2")
        app.typeCode("246813")
        app.waitForText("Kodu tekrar gir")
        app.typeCode("246813")
        app.waitForTag("onboarding.acknowledge")
        app.settle()
        app.snap("05 2.3 2-2")
        app.tap("onboarding.acknowledge")
        app.tap("onboarding.finish")
        app.waitForTag("chat.deniz", timeoutMillis = 8_000)
        app.snap("06 4.1 geri yüklendi")
    }

    // MARK: 03 · Lock

    /** Launch → 3.2 → wrong code → right code → the route's chat; background → locked again; 3.3 → “Tekrar dene”. */
    @Test
    fun test03UnlockAndRelock() {
        app.launch("U2Start" to "locked", "U2Route" to "conversation:deniz")
        app.waitForText("Gizli kodunu gir", timeoutMillis = 10_000)
        app.snap("01 3.2 Kod")
        app.typeCode("111112")
        app.waitForTag("lock.message")
        app.snap("02 3.2 Kod yanlış")
        app.typeCode("246813")
        app.waitForTag("message.d6", timeoutMillis = 8_000)
        app.settle()
        app.snap("03 5.1 after unlock")
        app.backgroundAndReturn()
        app.waitForText("Gizli kodunu gir")
        app.snap("04 3.2 relocked")
        app.tap("lock.forgot")
        app.waitForText("Kod kurtarılamaz.")
        app.settle()
        app.snap("05 3.3 Kodu unuttum")
        app.tap("lock.forgot.retry")
        app.waitForTextGone("Kod kurtarılamaz.")
        app.typeCode("246813")
        app.waitForTag("message.d6", timeoutMillis = 8_000)
        app.waitForTextGone("Gizli kodunu gir")
    }

    /** 3.3 › “Yedekten geri yükle” → 7.3 inside the lock → 2.2 ×2 → 2.3 → 4.1; the new code unlocks from now on. */
    @Test
    fun test04RestoreFromTheLock() {
        app.launch("U2Start" to "locked", "U2PickBackup" to "YES")
        app.tap("lock.forgot", timeoutMillis = 10_000)
        app.tap("lock.forgot.restore")
        app.waitForTag("restore.password", timeoutMillis = 8_000)
        app.settle()
        app.snap("01 3.3 › 7.3")
        app.type("kuzey7deniz42k", tag = "restore.password")
        app.tap("restore.action.submit")
        app.tap("restore.action.continue", timeoutMillis = 20_000)
        app.waitForText("Gizli kodunu belirle", timeoutMillis = 8_000)
        app.settle()
        app.snap("02 2.2 after restore in lock")
        app.typeCode("471958")
        app.waitForText("Kodu tekrar gir")
        app.typeCode("471958")
        app.tap("onboarding.acknowledge")
        app.tap("onboarding.finish")
        app.waitForTag("chat.deniz", timeoutMillis = 8_000)
        app.snap("03 4.1 after restore in lock")
        app.backgroundAndReturn()
        app.waitForText("Gizli kodunu gir")
        app.typeCode("471958")
        app.waitForTextGone("Gizli kodunu gir", timeoutMillis = 8_000)
        app.waitForTag("chat.deniz")
    }

    // MARK: 04 · Chats

    /** 4.1 → 4.3 → 4.4 → “Kabul et” → 5.1 (replaces 4.4); 4.3 “Sil” collapses a card. */
    @Test
    fun test05RequestsPreviewAccept() {
        app.launch("U2Start" to "chats")
        app.tap("chats.requests", timeoutMillis = 10_000)
        app.waitForTag("request.n_aksoy")
        app.settle()
        app.snap("01 4.3 Mesaj istekleri")
        app.tap("request.n_aksoy.delete")
        app.waitForTagGone("request.n_aksoy")
        app.settle()
        app.snap("02 4.3 after Sil")
        app.tap("request.kuzey_dev")
        app.waitForTag("preview.accept")
        app.settle()
        app.snap("03 4.4 İstek önizleme")
        app.tap("preview.accept")
        app.waitForTag("conversation.ghost", timeoutMillis = 8_000)
        app.settle(1_000)
        app.snap("04 5.1 accepted")
        app.tap("chatHeader.back")
        // 4.4 was replaced by 5.1: back is 4.3, then 4.1.
        app.tap("requests.back")
        app.waitForTag("chat.kuzey_dev")
        app.settle()
        app.snap("05 4.1 with kuzey_dev")
    }

    /** 4.1 note-pencil → 4.2 → exact lookup → “Mesaj yaz” → 5.1 → a message → back; QR menu → “Kodumu göster”. */
    @Test
    fun test06NewChatAndMyCode() {
        app.launch("U2Start" to "chats")
        app.tap("chats.new", timeoutMillis = 10_000)
        app.type("lale", tag = "newChat.field")
        app.waitForTag("newChat.write")
        app.settle()
        app.snap("01 4.2 found")
        app.tap("newChat.write")
        app.waitForTag("chatHeader.back", timeoutMillis = 8_000)
        app.settle(1_000)
        app.snap("02 5.1 new chat")
        app.type("Merhaba!", tag = "composer.input")
        app.tapText("Gönder")
        app.settle(1_000)
        app.snap("03 5.1 first message")
        app.tap("chatHeader.back")
        app.waitForTag("chat.lale")
        app.settle()
        app.snap("04 4.1 with lale")
        app.tap("chats.qr")
        app.tap("menu.myCode")
        app.waitForTag("myCode.sheet")
        app.settle(1_000)
        app.snap("05 4.1 Kodumu göster")
    }

    // MARK: 05 · Conversation

    /** 5.1: long-press → 5.3 → Bilgi → 5.4; reply; delete for me; Ghost chip → 5.5 → Kaydet; view once → 5.6 → close. */
    @Test
    fun test08Conversation() {
        app.launch("U2Route" to "conversation:deniz")
        app.waitForTag("message.d6", timeoutMillis = 10_000)
        app.settle()
        app.snap("01 5.1 Sohbet")
        app.longPress("message.d6")
        app.waitForTag("menu.info")
        app.settle()
        app.snap("02 5.3 Eylemler")
        app.tap("menu.info")
        app.settle(1_000)
        app.snap("03 5.4 Mesaj bilgisi")
        app.tap("sheet.close")
        app.waitForTagGone("info.sheet")
        app.settle(1_000)
        app.longPress("message.d3")
        app.tap("menu.reply")
        app.settle(1_000)
        app.snap("04 5.1 reply quote")
        app.type("Evet, boşum", tag = "composer.input")
        app.tapText("Gönder")
        app.settle(1_000)
        app.snap("05 5.1 replied")
        app.longPress("message.d5")
        app.tap("menu.deleteMe")
        app.waitForTagGone("message.d5")
        app.settle()
        app.snap("06 5.1 after Benden sil")
        app.tap("conversation.ghost")
        app.waitForTag("ghost.save")
        app.settle()
        app.snap("07 5.5 Ghost")
        app.tapText("6 sa")
        app.tap("ghost.save")
        app.settle(1_500)
        app.snap("08 5.1 Ghost changed")
        app.tap("chatHeader.back")
        app.tap("chat.mert_k")
        app.waitForTag("message.m6", timeoutMillis = 8_000)
        app.settle()
        app.snap("09 5.2 Medya")
        app.tap("message.m6")
        app.waitForTag("viewer.close", timeoutMillis = 8_000)
        app.settle(1_000)
        app.snap("10 5.6 Tek görüntülemelik")
        app.tap("viewer.close")
        app.waitForTagGone("viewer.close")
        app.settle(1_000)
        app.snap("11 5.2 Açıldı")
        assertTrue(app.exists("message.m6"))
    }

    // MARK: 07 · Settings

    /** 4.1 avatar → 7.1 → presence picker; theme light; code change; 7.2 export → share sheet. */
    @Test
    fun test10Settings() {
        app.launch("U2Start" to "chats")
        app.tap("chats.settings", timeoutMillis = 10_000)
        app.waitForText("Ayarlar")
        app.settle()
        app.snap("01 7.1 Ayarlar")
        app.tap("settings.presence")
        app.settle()
        app.snap("02 7.1 › Aktif durumu")
        app.tap("picker.back")
        app.tap("settings.theme")
        app.tap("picker.option.2")
        app.settle(1_000)
        app.snap("03 Tema · Açık")
        app.tap("picker.option.1")
        app.tap("picker.back")
        app.tap("settings.changeCode")
        app.waitForText("Mevcut kodunu gir")
        app.settle()
        app.snap("04 Gizli kodu değiştir · mevcut")
        app.typeCode("246813")
        app.waitForText("Yeni kodunu belirle")
        app.typeCode("471958")
        app.waitForText("Yeni kodu tekrar gir")
        app.typeCode("471958")
        // 7.1 stays composed under the code screen: wait for the code screen to leave.
        app.waitForTextGone("Yeni kodu tekrar gir", timeoutMillis = 8_000)
        app.settle(500)
        app.snap("05 7.1 kod değişti")
        app.tap("settings.backup")
        app.waitForTag("backup.password")
        app.settle()
        app.snap("06 7.2 Yedeği dışa aktar")
        app.type("v7qz3kd9xw2mtr", tag = "backup.password")
        app.tap("backup.export")
        app.waitForTag("backup.password.confirm")
        app.type("v7qz3kd9xw2mtr", tag = "backup.password.confirm")
        app.tap("backup.export")
        // The system share sheet (another activity) with the encrypted file.
        app.waitForSystemActivity()
        app.snap("07 7.2 share sheet")
        app.closeSystemActivity()
    }

    /** 7.1 › Her şeyi sil → confirmation → 1.1. */
    @Test
    fun test11EraseFromSettings() {
        app.launch("U2Route" to "settings")
        app.tap("settings.erase", timeoutMillis = 10_000)
        app.waitForTag("settings.confirm.destructive")
        app.settle()
        app.snap("01 7.1 Her şey silinsin mi?")
        app.tapUntil("settings.confirm.destructive", "erased to 1.1") { app.exists("welcome.start") }
        app.settle()
        app.snap("02 1.1 after erase")
    }
}
