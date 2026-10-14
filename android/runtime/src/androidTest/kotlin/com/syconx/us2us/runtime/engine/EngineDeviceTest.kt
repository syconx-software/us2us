package com.syconx.us2us.runtime.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.net.RelayStream
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.StorageLayout
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The messaging engine on the phone's SQLCipher storage: a received message and a sent one in one
 * conversation, both kept encrypted across a restart, the runtime state and notice lines opaque on
 * disk. The relay is a recording stand-in.
 */
@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var directories: AppDirectories
    private val dek = ByteArray(32).also(SecureRandom()::nextBytes)

    @Before
    fun setUp() {
        directories = AppDirectories(File(context.noBackupFilesDir, "engine-device-test").also { it.deleteRecursively() })
        directories.prepare()
    }

    @After
    fun tearDown() {
        directories.root.deleteRecursively()
    }

    private fun open(): LocalStorage = LocalStorage.open(StorageLayout.standard(directories), SecretKeyMaterial(dek)).storage

    private fun ByteArray.contains(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private fun allBytes(): ByteArray =
        directories.root.walkTopDown().filter { it.isFile }.fold(ByteArray(0)) { result, file -> result + file.readBytes() }

    @Test
    fun messagesAndLinesStayEncryptedAcrossARestart() = runBlocking {
        val peer = AccountId(UUID.randomUUID())
        var storage = open()
        val account = LocalAccount(
            accountId = AccountId(UUID.randomUUID()),
            authToken = AuthToken(ByteArray(AuthToken.LENGTH).also(SecureRandom()::nextBytes)),
            username = Username("device_test"),
            identityKeyPair = Keys.generateIdentityKeyPair(),
            registrationId = Keys.generateRegistrationId(),
            accessKey = AccessKey.generate(),
        )
        storage.accountStore.save(account)
        var backend = RecordingBackend(account.accountId)
        var engine = MessagingEngine(storage, backend, directories.runtime, SecretKeyMaterial(dek))
        engine.start()
        engine.receive(
            IncomingMessage(
                "relay-1",
                0,
                peer,
                "deniz_k",
                content { data = dataMessage { messageId = id(); sentAt = 1; text = text { body = "gizli selam" } } },
                ContactState.INCOMING_REQUEST,
                false,
            ),
        )
        engine.acceptRequest(peer)
        engine.send(OutgoingContent.Text("gizli cevap"), peer)
        engine.updateUserSettings { it.copy(displayName = "Gizli Ad") }
        engine.screenCaptured(peer, recording = false)
        withTimeout(10.seconds) { while (backend.sent.none { it.hasData() }) delay(20.milliseconds) }
        engine.stop()
        storage.close()

        val bytes = allBytes()
        for (secret in listOf("gizli selam", "gizli cevap", "Gizli Ad", "deniz_k")) assertFalse(secret, bytes.contains(secret))

        storage = open()
        backend = RecordingBackend(account.accountId)
        engine = MessagingEngine(storage, backend, directories.runtime, SecretKeyMaterial(dek))
        engine.start()
        val texts = engine.messages(peer).mapNotNull { (it.body as? MessageBody.Text)?.text }
        assertEquals(listOf("gizli selam", "gizli cevap"), texts)
        assertTrue(engine.messages(peer).any { it.body == MessageBody.Notice(SystemNotice.ScreenshotByMe) })
        assertEquals("Gizli Ad", engine.currentSettings().displayName)
        engine.stop()
        storage.close()
    }

    private fun id(): ByteString = ByteString.copyFrom(ByteArray(16).also(SecureRandom()::nextBytes))

    /** Records sends; everything else is inert. */
    private class RecordingBackend(override val accountId: AccountId) : MessagingBackend {
        val sent = java.util.concurrent.CopyOnWriteArrayList<Content>()

        override suspend fun username(): Username = Username("device_test")

        override suspend fun currentAccessKey(): ByteString = ByteString.EMPTY

        override suspend fun openStream(): RelayStream = error("no relay in this test")

        override suspend fun send(
            content: Content,
            recipient: AccountId,
            requestState: ContactState,
            theirAccessKey: ByteString?,
            ephemeral: Boolean,
        ): SendOutcome {
            sent += content
            return SendOutcome(0)
        }

        override suspend fun sendPresence(presence: Content, contacts: List<Recipient>): List<AccountId> = emptyList()

        override suspend fun performMaintenance() = Unit

        override suspend fun rotateAccessKey(): ByteString = ByteString.EMPTY

        override suspend fun announceProfile(profile: Content, contacts: List<Recipient>): List<AccountId> = emptyList()

        override suspend fun needsProfileAnnouncement(): Boolean = false

        override suspend fun uploadAttachment(file: File, kind: Attachment.Kind, contentType: String, fileName: String?): Attachment =
            error("no uploads in this test")

        override suspend fun downloadAttachment(attachment: Attachment, destination: File, progress: (Double) -> Unit) =
            error("no downloads in this test")

        override suspend fun deleteUploadedMedia(attachment: Attachment) = Unit

        override suspend fun findAccount(username: Username): DirectoryEntry? = null

        override suspend fun usernameOwner(username: Username): AccountId? = null

        override suspend fun changeUsername(username: Username) = Unit

        override suspend fun safetyNumber(contact: AccountId): SafetyNumber? = null

        override suspend fun identityTrust(contact: AccountId): ContactIdentity = ContactIdentity.Unknown

        override suspend fun approveIdentityChange(contact: AccountId, shownNumber: ByteArray): Boolean = false

        override suspend fun markVerified(contact: AccountId, comparedNumber: ByteArray, verification: IdentityVerification): Boolean = false

        override suspend fun verifySafetyNumber(contact: AccountId, scanned: ByteArray, verification: IdentityVerification): Boolean = false

        override suspend fun pinVerifiedIdentity(identityKey: ByteArray, contact: AccountId, verification: IdentityVerification): Boolean = false

        override suspend fun clearVerification(contact: AccountId) = Unit

        override suspend fun identityVerification(contact: AccountId): IdentityVerification? = null

        override suspend fun forgetContact(contact: AccountId) = Unit

        override suspend fun exportBackup(password: String): ByteArray = ByteArray(0)

        override suspend fun deleteAccount() = Unit

        override suspend fun ownIdentityKey(): ByteArray = ByteArray(0)
    }
}
