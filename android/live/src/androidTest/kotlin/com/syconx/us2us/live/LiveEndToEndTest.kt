package com.syconx.us2us.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerScenePhase
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.runtime.facade.LockState
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live service end to end on a phone (Keystore, SQLCipher, libsignal) against a local relay,
 * with the core's echo bot (android/cli) on the other side:
 *
 * register → lock → unlock; the message request accepted; text, photo and voice there and back;
 * the history after a relaunch; erase everything.
 *
 * Run by `scripts/e2e-live.sh` with the instrumentation arguments `relayUrl`
 * (`http://127.0.0.1:<port>` through `adb reverse`) and `botUsername`; without them it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class LiveEndToEndTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val relayUrl: String? = arguments.getString("relayUrl")
    private val bot: String? = arguments.getString("botUsername")
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun registerLockUnlockRequestTextPhotoVoiceRelaunchAndWipe(): Unit = runBlocking {
        val relay = relayUrl
        val peerName = bot
        assumeTrue("needs relayUrl and botUsername (scripts/e2e-live.sh)", relay != null && peerName != null)
        relay!!
        peerName!!
        assertEquals("a loopback relay only", relay, RelayEndpoint.debugOverride(relay))

        var service = start(relay)
        // A leftover of an earlier run goes first.
        if (service.hasAccount.value) onMain { service.eraseEverything() }

        // 1 · register (2.3): the account exists, the line opens.
        val username = "l4e2e_" + hex(4)
        step("register $username")
        onMain { service.createAccount(username, CODE, faceIdEnabled = false) }
        assertTrue(service.hasAccount.value)
        assertEquals(username, service.account.value?.username)
        assertEquals(LockState.UNLOCKED, service.runtime.lockState.value)
        awaitTrue("the line opens") { service.connection.value == ConnectionState.Open }
        val contactCode = service.ownContactCode()
        assertNotNull(contactCode)
        assertTrue(contactCode!!.startsWith("us2us:@$username#"))

        // 2 · lock on leaving, unlock with the code (a wrong code first).
        step("lock and unlock")
        onMain {
            service.scenePhaseChanged(MessengerScenePhase.Inactive)
            service.scenePhaseChanged(MessengerScenePhase.Background)
        }
        assertEquals(LockState.LOCKED, service.runtime.lockState.value)
        assertEquals("nothing decrypted stays while locked", null, service.account.value)
        onMain { service.scenePhaseChanged(MessengerScenePhase.Active) }
        val wrong = onMain { service.unlock(WRONG_CODE) }
        assertTrue("a wrong code is a wrong code: $wrong", wrong is UnlockAttemptResult.WrongCode)
        assertEquals(1, service.unlockStatus.value.failures)
        assertEquals(UnlockAttemptResult.Unlocked, onMain { service.unlock(CODE) })
        assertEquals(0, service.unlockStatus.value.failures)
        assertEquals(username, service.account.value?.username)

        // 3 · a message request to the bot, accepted by it; the echo comes back.
        step("request to $peerName")
        val lookup = onMain { service.lookup(peerName) }
        assertTrue("the directory finds the bot: $lookup", lookup is com.syconx.us2us.domain.UserLookup.Found)
        val chat = onMain { service.startChat(peerName) } ?: throw AssertionError("no chat with $peerName")
        onMain { service.conversationAppeared(chat) }
        onMain { service.sendText("merhaba", null, chat) }
        awaitTrue("the request is accepted", 90.seconds) { service.chat(chat).value?.peer?.isAcceptedContact == true }
        awaitTrue("the echo arrives", 90.seconds) { service.messages(chat).value.hasIncomingText("echo: merhaba") }
        // A request gets no receipts (§6.4); once accepted, the next text is delivered.
        onMain { service.sendText("nasılsın", null, chat) }
        awaitTrue("the second echo arrives", 90.seconds) { service.messages(chat).value.hasIncomingText("echo: nasılsın") }
        val second = service.messages(chat).value.first { it.isOutgoing && it.content == MessageContent.Text("nasılsın") }
        awaitTrue("the text is delivered") { service.messages(chat).value.statusOf(second) in setOf(MessageStatus.Delivered, MessageStatus.Read) }

        // 4 · a photo: prepared (≤ 2048 px JPEG, no metadata), stored under its bubble's id, uploaded; the bot hashes what it got.
        step("photo")
        val photo = samplePhoto()
        onMain { service.sendAttachment(ConversationAttachment.Photo(MediaSource.File(photo.toURI()), "Moda"), chat) }
        awaitTrue("the photo is stored and sent", 120.seconds) {
            service.messages(chat).value.any { it.isOutgoing && it.content is MessageContent.Photo && it.mediaAvailability == MediaAvailability.Ready && it.status != MessageStatus.Sending }
        }
        val sentPhoto = service.messages(chat).value.last { it.isOutgoing && it.content is MessageContent.Photo }
        assertEquals("Moda", (sentPhoto.content as MessageContent.Photo).caption)
        val photoBytes = onMain { service.mediaPayload(sentPhoto.id, chat) } ?: throw AssertionError("the sent photo is not readable")
        assertEquals("image/jpeg", photoBytes.contentType)
        val photoHash = sha256(photoBytes.data.toByteArray())
        awaitTrue("the bot got exactly that photo", 120.seconds) { service.messages(chat).value.hasIncomingText("echo-attachment: $photoHash") }
        assertFalse("the picked copy is gone", File(context.cacheDir, "picked").walkTopDown().any { it.isFile })

        // 5 · a voice message from memory.
        step("voice")
        val audio = ByteArray(6_000).also(SecureRandom()::nextBytes)
        val voice = VoiceRecording(Bytes.wrap(audio), 2.seconds, Bytes.ofUnsigned((0 until 100).map { it * 2 }))
        assertTrue(onMain { service.sendVoice(voice, null, chat) })
        awaitTrue("the bot got the voice message", 120.seconds) { service.messages(chat).value.hasIncomingText("echo-attachment: ${sha256(audio)}") }
        assertTrue(service.messages(chat).value.any { it.isOutgoing && it.content is MessageContent.Voice })

        // 5b · a document from the document picker's copy: sent as it is.
        step("document")
        val document = File(File(context.cacheDir, "e2e").apply { mkdirs() }, "kira-sozlesmesi.txt")
        document.writeText("us2us · kira sözleşmesi · ${hex(8)}")
        onMain { service.sendAttachment(ConversationAttachment.Document(document.toURI()), chat) }
        awaitTrue("the bot got the document", 120.seconds) { service.messages(chat).value.hasIncomingText("echo-attachment: ${sha256(document.readBytes())}") }
        val sentFile = service.messages(chat).value.last { it.isOutgoing && it.content is MessageContent.File }.content as MessageContent.File
        assertEquals("kira-sozlesmesi.txt", sentFile.name)
        assertEquals("TXT", sentFile.typeLabel)

        // 5c · the current place (“+” › Konum) from the platform location manager, when the run allows location.
        if (arguments.getString("location") == "1") {
            step("place")
            // Location reaches an app with a screen in front only.
            val screen = ActivityScenario.launch(ComponentActivity::class.java)
            onMain { service.sendCurrentLocation(chat) }
            awaitTrue("the bot got the place", 120.seconds) {
                service.messages(chat).value.any { !it.isOutgoing && (it.content as? MessageContent.Text)?.text?.startsWith("echo-location: 40.98") == true }
            }
            // A live share runs, shows on the map's snapshot, and stops at once.
            step("live location")
            onMain { service.sendAttachment(ConversationAttachment.LiveLocation(15.minutes), chat) }
            awaitTrue("the live share is on the map") {
                service.liveLocations(chat).value.activeShare(com.syconx.us2us.domain.MessageDirection.Outgoing, java.time.Instant.now()) != null
            }
            val share = service.liveLocations(chat).value.activeShare(com.syconx.us2us.domain.MessageDirection.Outgoing, java.time.Instant.now())!!
            assertEquals(40.9846, share.position.latitude, 0.001)
            onMain { service.stopLiveLocation(share.id, chat) }
            awaitTrue("the share stopped") { service.liveLocations(chat).value.share(share.id)?.isStopped == true }
            screen.close()
        }
        val before = service.messages(chat).value.filterNot { it.isSystem }.map { it.content.summary() }

        // 6 · a relaunch: a new runtime on the same vault, locked at launch, opens with the code and shows the history.
        step("relaunch")
        onMain {
            service.conversationDisappeared(chat)
            service.runtime.lockNow()
            service.close()
        }
        service = start(relay)
        assertTrue(service.hasAccount.value)
        assertEquals(LockState.LOCKED, service.runtime.lockState.value)
        assertEquals(UnlockAttemptResult.Unlocked, onMain { service.unlock(CODE) })
        assertEquals(username, service.account.value?.username)
        awaitTrue("the chat is back") { service.chats.value.any { it.id == chat && it.peer.isAcceptedContact && it.peer.username == peerName } }
        onMain { service.conversationAppeared(chat) }
        awaitTrue("the history is back") { service.messages(chat).value.filterNot { it.isSystem }.size >= before.size }
        assertEquals(before, service.messages(chat).value.filterNot { it.isSystem }.map { it.content.summary() })
        // The line works after the relaunch too.
        onMain { service.sendText("tekrar", null, chat) }
        awaitTrue("the echo after the relaunch", 90.seconds) { service.messages(chat).value.hasIncomingText("echo: tekrar") }

        // 7 · 7.2: an account backup under a generated password, saved (the date is kept in the vault).
        step("backup")
        val password = service.generateBackupPassword()
        assertEquals(null, service.backupPasswordProblem(password))
        assertEquals(null, service.settings.value.lastBackupAt)
        val estimate = service.backupEstimate()
        assertTrue(estimate.fileName.startsWith("us2us-") && estimate.fileName.endsWith(".u2u"))
        val exported = onMain { service.exportBackup(com.syconx.us2us.domain.BackupContents(), password, estimate.fileName) {} }
        val saved = File(File(context.cacheDir, "e2e"), estimate.fileName)
        File(exported).copyTo(saved, overwrite = true)
        onMain { service.finishExport(exported, saved = true) }
        assertFalse("the exported copy goes with the share sheet", File(exported).exists())
        awaitTrue("the backup date is kept") { service.settings.value.lastBackupAt != null }

        // 8 · this phone erased (the account stays on the relay), then 7.3 restores the backup with a new code.
        step("restore")
        onMain {
            service.conversationDisappeared(chat)
            service.eraseEverything()
        }
        assertFalse(service.hasAccount.value)
        val file = onMain { service.inspectBackup(saved.toURI()) }
        assertEquals(estimate.fileName, file.fileName)
        assertEquals(com.syconx.us2us.domain.BackupOpenResult.WrongPassword, onMain { service.openBackup(file, "yanlis-parola-1234") })
        assertEquals(com.syconx.us2us.domain.BackupOpenResult.Opened, onMain { service.openBackup(file, password) })
        val progress = onMain { service.restoreBackup(file).toList() }
        assertFalse("an account backup brings no history", progress.last().includesHistory)
        onMain { service.completeRestore(NEW_CODE, faceIdEnabled = false) }
        assertEquals(LockState.UNLOCKED, service.runtime.lockState.value)
        assertEquals(username, service.account.value?.username)
        awaitTrue("the contact is back") { service.chats.value.any { it.id == chat && it.peer.username == peerName } }
        awaitTrue("the line opens after the restore", 90.seconds) { service.connection.value == ConnectionState.Open }
        onMain { service.conversationAppeared(chat) }
        onMain { service.sendText("geri geldim", null, chat) }
        awaitTrue("the echo after the restore", 90.seconds) { service.messages(chat).value.hasIncomingText("echo: geri geldim") }
        // The new code opens it from now on.
        onMain {
            service.conversationDisappeared(chat)
            service.runtime.lockNow()
        }
        assertEquals(UnlockAttemptResult.Unlocked, onMain { service.unlock(NEW_CODE) })

        // 9 · 7.1 › Her şeyi sil: the account goes from the relay, then everything on this phone; the next launch is onboarding.
        step("wipe")
        onMain {
            service.deleteAccountOnRelay()
            service.eraseEverything()
        }
        assertFalse(service.hasAccount.value)
        assertEquals(LockState.NEEDS_SETUP, service.runtime.lockState.value)
        onMain { service.close() }
        val fresh = start(relay)
        assertFalse("onboarding after the wipe", fresh.hasAccount.value)
        onMain { fresh.close() }
        assertTrue("the directory no longer knows the name", onMain { lookupGone(relay, username) })
        step("done")
    }

    /** A second phone (a new account of its own) cannot find the erased name any more. */
    private suspend fun lookupGone(relay: String, username: String): Boolean {
        val probe = start(relay)
        if (probe.hasAccount.value) probe.eraseEverything()
        probe.createAccount("l4probe_" + hex(3), CODE, faceIdEnabled = false)
        val found = probe.lookup(username)
        probe.deleteAccountOnRelay()
        probe.eraseEverything()
        probe.close()
        return found == com.syconx.us2us.domain.UserLookup.NotFound
    }

    private suspend fun start(relay: String): RealMessengerService = onMain {
        LiveMessenger.create(context, relay, activities = { null }, hooks = ExternalActivityHooks.None).also {
            it.scenePhaseChanged(MessengerScenePhase.Active)
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun awaitTrue(what: String, timeout: Duration = 60.seconds, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out: $what")
            delay(200)
        }
    }

    /** A 1600 × 1200 JPEG with something to compress, written where a picker's copy would come from. */
    private fun samplePhoto(): File {
        val bitmap = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 24, 40))
        val paint = Paint().apply { color = Color.rgb(240, 180, 90) }
        for (index in 0 until 40) canvas.drawCircle(40f * index, 600f + 200f * kotlin.math.sin(index / 3.0).toFloat(), 30f, paint)
        val folder = File(context.cacheDir, "e2e").apply { mkdirs() }
        val file = File(folder, "moda.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun step(name: String) = Log.i("us2us.e2e", "step: $name")

    private companion object {
        const val CODE = "482916"
        const val WRONG_CODE = "591824"
        const val NEW_CODE = "730418"

        fun hex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun List<Message>.hasIncomingText(text: String): Boolean = any { !it.isOutgoing && it.content == MessageContent.Text(text) }

        fun List<Message>.statusOf(message: Message): MessageStatus? = firstOrNull { it.id == message.id }?.status

        /** What a bubble is, without the bytes that are loaded later (thumbnails, full images). */
        fun MessageContent.summary(): String = when (this) {
            is MessageContent.Text -> "text:$text"
            is MessageContent.Photo -> "photo:$caption"
            is MessageContent.Voice -> "voice:$duration"
            else -> javaClass.simpleName
        }
    }
}
