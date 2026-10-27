package com.syconx.us2us.app.flows

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import com.syconx.us2us.app.MainActivity
import com.syconx.us2us.app.Us2usApplication
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.MessengerScenePhase
import com.syconx.us2us.live.LiveMessenger
import com.syconx.us2us.live.LivePlatform
import com.syconx.us2us.live.RealMessengerService
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.RelayBackendFactory
import com.syconx.us2us.runtime.facade.RuntimeEnvironment
import com.syconx.us2us.runtime.support.ProcessBackgroundTasks
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.SystemCaptureEvents
import com.syconx.us2us.runtime.support.SystemClipboard
import com.syconx.us2us.runtime.vault.ActivityProvider
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.FragmentBiometricPresenter
import com.syconx.us2us.runtime.vault.KeystoreBiometricProtector
import com.syconx.us2us.runtime.vault.KeystoreItemStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native UI, local relay, SQLCipher and Keystore. UI Automator avoids
 * requiring a live connection to be idle. Each run uses a unique vault and
 * Keystore aliases, never the installed user's account.
 */
@RunWith(AndroidJUnit4::class)
class CommunityFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var relay: String
    private lateinit var token: String
    private fun <T> onMain(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }
    private fun find(label: String): UiObject2? {
        // Typography can glue the final words with a non-breaking space.
        val exact = Pattern.compile("(?iu)" + label.split(' ').joinToString("[\\s\\u00a0]+") { Pattern.quote(it) })
        return device.findObject(By.text(exact)) ?: device.findObject(By.desc(exact)) ?: device.findObject(By.descContains(label))
    }
    private fun waitFor(label: String, timeout: Long = 20_000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            find(label)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("“$label” did not appear")
    }
    private fun tap(label: String) { waitFor(label).click() }
    private fun tapScrolling(label: String) {
        repeat(5) {
            find(label)?.let { node -> node.click(); return }
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 3, 30)
        }
        tap(label)
    }
    private fun type(text: String) {
        var entered = false
        repeat(8) {
            if (!entered) {
                try {
                    // The top sheet's field comes after the covered directory.
                    device.findObjects(By.clazz("android.widget.EditText")).lastOrNull()?.click()
                    val field = device.findObjects(By.clazz("android.widget.EditText")).lastOrNull()
                        ?: throw AssertionError("no editable field")
                    field.text = text
                    entered = true
                } catch (_: StaleObjectException) { Thread.sleep(100) }
            }
        }
        assertTrue("text entered in the current field", entered)
        device.pressBack()
    }
    private fun code() { for (digit in "246813") tap(digit.toString()) }
    private fun directory() { tap("Yeni sohbet"); tap("Topluluklar"); waitFor("Açık toplulukları adla ara") }
    private fun create(name: String, public: Boolean) {
        tapScrolling("Topluluk oluştur")
        waitFor("Yeni topluluk")
        type(name)
        if (public) tap("Herkese açık: aramada görünür")
        tap("Oluştur")
        waitFor("Üyeler ve yönetim")
    }
    private fun api(path: String, method: String = "GET", status: Int = 200): JSONObject {
        val c = URL(relay + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.requestMethod = method
            c.setRequestProperty("x-auth-token", token)
            if (method == "POST") {
                c.doOutput = true; c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write("{}".toByteArray()) }
            }
            assertEquals(path, status, c.responseCode)
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return if (body.startsWith("{")) JSONObject(body) else JSONObject()
        } finally { c.disconnect() }
    }

    @Test fun creationDiscoveryMediaApprovalAndRelock() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("relayUrl")
        val applicantToken = args.getString("communityPeerToken")
        assumeTrue("requires local relay and community API applicant", endpoint != null && applicantToken != null)
        relay = endpoint!!; token = applicantToken!!
        assertTrue(URI(relay).host in setOf("127.0.0.1", "localhost", "::1"))
        val application = context.applicationContext as Us2usApplication
        val suffix = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "community-ui-$suffix")
        val activities = com.syconx.us2us.app.ActivityProvider.of(application)
        val originalFactory = application.services.liveServiceFactory
        val idleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        var scenario: ActivityScenario<MainActivity>? = null
        val service = onMain {
            val items = KeystoreItemStore(File(root, "items"), "community-ui-items-$suffix")
            val bio = KeystoreBiometricProtector(context,
                FragmentBiometricPresenter(ActivityProvider { activities.current as? androidx.fragment.app.FragmentActivity }),
                "community-ui-bio-$suffix")
            val env = RuntimeEnvironment(AppDirectories(root), items, bio, RelayBackendFactory(relay))
            val runtime = AppRuntime(env, ProcessBackgroundTasks(context), SystemCaptureEvents(application),
                SecurePasteboard(SystemClipboard(context)), LiveMessenger.runtimeStrings(application) { activities.current })
            RealMessengerService(runtime, LivePlatform(context, { activities.current })).also {
                application.services.liveServiceFactory = { _, _ -> it }
            }
        }
        try {
            val user = "aui_" + suffix.take(8)
            onMain {
                service.scenePhaseChanged(MessengerScenePhase.Active)
                service.createAccount(user, "246813", faceIdEnabled = false)
                service.scenePhaseChanged(MessengerScenePhase.Background)
                application.startOver()
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
                .putExtra("U2Fixtures", "NO").putExtra("U2RelayURL", relay)
                .putExtra("U2Language", "tr").putExtra("U2Theme", "dark")
                .putExtra("U2ResetPreferences", "YES").putExtra("U2AllowCapture", "YES"))
            waitFor("Gizli kodunu gir"); code(); waitFor("Sohbetler"); directory()
            val publicName = "UI Açık $user"
            val privateName = "UI Gizli $user"
            create(privateName, public = false)
            tap("Geri"); directory()
            assertEquals(0, api("/v1/communities?query=" + java.net.URLEncoder.encode(privateName, "UTF-8"))
                .getJSONArray("communities").length())
            create(publicName, public = true)
            type("UI testi önceki mesaj"); tap("Gönder"); waitFor("UI testi önceki mesaj")
            val list = api("/v1/communities?query=" + java.net.URLEncoder.encode(publicName, "UTF-8")).getJSONArray("communities")
            val id = (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("name") == publicName }.getString("id")
            val photo = File(context.cacheDir, "community-ui-$suffix.png")
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            try { photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            onMain { service.sendAttachment(ConversationAttachment.Photo(MediaSource.File(photo.toURI()), "UI fotoğraf"), CommunityChats.chatId(id)) }
            waitFor("UI fotoğraf"); waitFor("UI testi önceki mesaj")
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("Gizli kodunu gir"); code()
            waitFor("UI testi önceki mesaj"); waitFor("UI fotoğraf")
            api("/v1/communities/$id/requests", "POST", 202)
            api("/v1/communities/$id", status = 404)
            tap("Üyeler ve yönetim")
            waitFor("Onayla", 30_000).click()
            val deadline = System.currentTimeMillis() + 20_000
            while (find("Onayla") != null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("the request was approved", find("Onayla") == null)
            api("/v1/communities/$id")
        } catch (failure: Throwable) {
            val folder = File(context.getExternalFilesDir("flows"), "CommunityFlowTest").apply { mkdirs() }
            device.takeScreenshot(File(folder, "failed.png")); device.dumpWindowHierarchy(File(folder, "failed.xml"))
            File(folder, "failed.xml").readText().chunked(3000).forEach { android.util.Log.e("CommunityUITest", it) }
            throw failure
        } finally {
            onMain {
                try { if (service.hasAccount.value) runCatching { service.deleteAccountOnRelay() } }
                finally {
                    service.eraseEverything(); service.close()
                    application.services.liveServiceFactory = originalFactory; application.startOver()
                }
            }
            scenario?.close(); root.deleteRecursively()
            Configurator.getInstance().waitForIdleTimeout = idleTimeout
        }
    }
}
