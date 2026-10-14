package com.syconx.us2us.runtime.facade

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.InvalidBackupPasswordException
import com.syconx.us2us.runtime.engine.FakeBackend
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessagingBackend
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.engine.SystemNotice
import com.syconx.us2us.runtime.engine.presenceContent
import com.syconx.us2us.runtime.engine.textContent
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.storage.ContactProfile
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.PlainSqlDriver
import com.syconx.us2us.runtime.storage.newAccount
import com.syconx.us2us.runtime.support.AppPhase
import com.syconx.us2us.runtime.support.ManualCaptureEvents
import com.syconx.us2us.runtime.support.ManualClock
import com.syconx.us2us.runtime.support.NoBackgroundTasks
import com.syconx.us2us.runtime.support.RuntimeStrings
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.SystemClipboard
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.InMemorySecureItemStore
import com.syconx.us2us.runtime.vault.NoBiometrics
import com.syconx.us2us.runtime.vault.SecretCodeError
import com.syconx.us2us.runtime.vault.UnlockError
import com.syconx.us2us.runtime.vault.UnlockException
import com.syconx.us2us.runtime.vault.VaultController
import com.syconx.us2us.runtime.vault.VaultError
import com.syconx.us2us.runtime.vault.VaultException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Registers into the storage it is given and hands out one [FakeBackend]. */
class FakeBackendFactory : BackendFactory {
    val accountId: AccountId = AccountId(UUID.randomUUID())
    var backend: FakeBackend? = null
        private set
    var registrations = 0
        private set

    private fun backendOn(storage: LocalStorage): FakeBackend {
        val existing = backend
        if (existing != null) {
            existing.storage = storage
            return existing
        }
        return FakeBackend(accountId, storage).also { backend = it }
    }

    override suspend fun register(username: Username, storage: LocalStorage): MessagingBackend {
        registrations += 1
        storage.accountStore.save(newAccount(accountId, username.value))
        return backendOn(storage)
    }

    override fun open(account: LocalAccount, storage: LocalStorage): MessagingBackend = backendOn(storage)

    override suspend fun restore(backup: AccountBackupFile, storage: LocalStorage): MessagingBackend = error("not used")

    override suspend fun checkBackup(backup: AccountBackupFile) = Unit
}

/** An app runtime on a temporary directory, the plain SQL binding and a fake backend. */
class RuntimeFixture(root: File) {
    val clock = ManualClock()
    val factory = FakeBackendFactory()
    val items = InMemorySecureItemStore()
    val tasks = NoBackgroundTasks()
    val capture = ManualCaptureEvents()
    val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
    val environment = RuntimeEnvironment(
        directories = AppDirectories(File(root, "app")),
        secureItems = items,
        biometrics = NoBiometrics,
        backends = factory,
        clock = clock,
        vault = VaultController.Configuration(iterations = 1000),
        engine = MessagingEngine.Configuration(presenceReplyDelay = Duration.ZERO),
        reconnectBackoff = Backoff(base = 1.seconds, cap = 4.seconds, floor = 1.seconds),
        gracePeriod = 30.seconds,
        storage = StorageOpener { layout, key, now -> key.withDataKey { LocalStorage.open(layout, it, now, PlainSqlDriver) } },
    )
    val pasteboard = SecurePasteboard(SystemClipboard(org.robolectric.RuntimeEnvironment.getApplication()))
    var runtime = newRuntime()

    private fun newRuntime() = runBlocking {
        withContext(main) {
            AppRuntime(environment, tasks, capture, pasteboard, RuntimeStrings("en") { key, _ -> key.name }, main)
        }
    }

    val backend: FakeBackend get() = factory.backend!!

    /** A new process on the same files (an app relaunch). */
    fun relaunch() {
        runtime = newRuntime()
    }

    suspend fun onboard() = runtime.createAccount("selin_n", "Selin", CODE, CODE)

    /** Delivers the backlog end on the current stream. */
    suspend fun connect() {
        eventually { factory.backend?.latestStream?.isRunning == true }
        backend.latestStream!!.push(com.syconx.us2us.runtime.engine.FakeStream.Event.QueueEmpty)
        eventually { runtime.connection.value == ConnectionStatus.Open }
    }

    suspend fun phase(phase: AppPhase) = withContext(main) { runtime.scenePhaseChanged(phase) }

    fun acceptedContact(): AccountId {
        val peer = AccountId(UUID.randomUUID())
        val storage = runtime.session!!.storage
        val now = clock.now()
        val ourKey = storage.read { it.account.load()!!.accessKey.toBytes() }
        storage.write { tx ->
            tx.contacts.create(peer, ContactState.ACCEPTED, now)
            tx.contacts.setTheirAccessKey(ByteString.copyFrom(ByteArray(AccessKey.LENGTH) { 2 }), peer)
            tx.contacts.setOurAccessKeySent(ByteString.copyFrom(ourKey), peer)
            tx.contacts.setProfile(ContactProfile(displayName = "Deniz", presenceSharing = true), peer)
            tx.conversations.ensure(peer, now)
        }
        return peer
    }

    fun push(content: Content, sender: AccountId) {
        backend.latestStream!!.push(
            com.syconx.us2us.runtime.engine.FakeStream.Event.Message(
                IncomingMessage(UUID.randomUUID().toString(), 0, sender, null, content, ContactState.ACCEPTED, false),
            ),
        )
    }

    companion object {
        const val CODE = "482917"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: RuntimeFixture

    private fun newFixture(): RuntimeFixture = RuntimeFixture(temporary.newFolder()).also { fixture = it }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) {
            try {
                fixture.runtime.lockNow()
            } catch (_: Exception) {
            }
            fixture.main.close()
        }
    }

    private suspend inline fun <reified E : Throwable> assertThrows(block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${E::class.java.simpleName}")
    }

    @Test
    fun onboardingConnectsOnlyWhileActiveAndUnlocked() = runBlocking {
        val fixture = newFixture()
        assertEquals(LockState.NEEDS_SETUP, fixture.runtime.lockState.value)
        fixture.onboard()
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
        assertEquals("selin_n", fixture.runtime.ownUsername.value)
        assertEquals("Selin", fixture.runtime.settings.value.displayName)
        fixture.connect()
        assertEquals(1, fixture.factory.registrations)
    }

    @Test
    fun backgroundLocksAtOnceAndClosesAfterTheGracePeriod() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val peer = fixture.acceptedContact()
        fixture.push(textContent("selam"), peer)
        eventually { fixture.runtime.chats.value.firstOrNull()?.lastMessage?.body == MessageBody.Text("selam") }

        fixture.phase(AppPhase.INACTIVE)
        assertTrue(fixture.runtime.isPrivacyCoverVisible.value)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)

        fixture.phase(AppPhase.BACKGROUND)
        // Locked immediately, nothing left in observable state.
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        assertTrue(fixture.runtime.chats.value.isEmpty())
        assertTrue(fixture.runtime.openMessages.value.isEmpty())
        assertEquals(1, fixture.tasks.begun)
        // Still receiving during the grace period (§5.4).
        val stream = fixture.backend.latestStream!!
        assertFalse(stream.isClosed)
        val key = fixture.runtime.session!!.key

        eventually(message = "grace over") {
            fixture.clock.advance(1.seconds)
            stream.isClosed
        }
        eventually(message = "teardown finished") {
            key.isWiped && fixture.tasks.ended == 1 && fixture.runtime.connection.value == ConnectionStatus.Offline
        }
        assertNull(fixture.runtime.session)

        fixture.phase(AppPhase.ACTIVE)
        assertFalse(fixture.runtime.isPrivacyCoverVisible.value)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
        fixture.connect()
        assertEquals(2, fixture.backend.streamCount)
        eventually { fixture.runtime.chats.value.firstOrNull()?.lastMessage?.body == MessageBody.Text("selam") }
    }

    @Test
    fun returningWithinTheGracePeriodResumesTheSameSession() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        fixture.phase(AppPhase.BACKGROUND)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        fixture.phase(AppPhase.ACTIVE)
        // The lock screen again, even after a few seconds away.
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
        assertEquals(1, fixture.backend.streamCount)
        assertFalse(fixture.backend.latestStream!!.isClosed)
        assertEquals(1, fixture.tasks.ended)
        fixture.clock.advance(60.seconds)
        kotlinx.coroutines.delay(200.milliseconds)
        assertFalse(fixture.backend.latestStream!!.isClosed)
    }

    @Test
    fun theSystemEndingBackgroundTimeZeroesTheKey() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val key = fixture.runtime.session!!.key
        fixture.phase(AppPhase.BACKGROUND)
        withContext(fixture.main) { fixture.tasks.expireAll() }
        eventually { key.isWiped }
        // Unlocking at once waits for the old session to close (one storage instance).
        fixture.phase(AppPhase.ACTIVE)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
        eventually { fixture.backend.streamCount == 2 }
        assertTrue(fixture.backend.stream(0).isClosed)
        fixture.runtime.lockNow()
        fixture.runtime.unlock(RuntimeFixture.CODE)
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
    }

    @Test
    fun everyLaunchStartsLocked() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val peer = fixture.acceptedContact()
        fixture.runtime.sendText("kalıcı mı?", peer)
        fixture.runtime.lockNow()
        fixture.relaunch()
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        val wrong = assertThrows<UnlockException> { fixture.runtime.unlock("730415") }
        assertEquals(UnlockError.Vault(VaultError.WrongCode), wrong.error)
        assertEquals(LockState.LOCKED, fixture.runtime.lockState.value)
        eventually { fixture.runtime.unlockStatus.value?.failures == 1 }
        fixture.runtime.unlock(RuntimeFixture.CODE)
        eventually { fixture.runtime.chats.value.firstOrNull()?.lastMessage?.body == MessageBody.Text("kalıcı mı?") }
    }

    @Test
    fun eraseEverythingLeavesNothing() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.runtime.eraseEverything()
        assertEquals(LockState.NEEDS_SETUP, fixture.runtime.lockState.value)
        val directories = fixture.environment.directories
        assertFalse(File(directories.storage, "db/main.sqlite").exists())
        assertFalse(File(directories.vault, "vault.brpv").exists())
        // And onboarding works again.
        fixture.onboard()
        assertEquals(LockState.UNLOCKED, fixture.runtime.lockState.value)
    }

    @Test
    fun aFailedRegistrationLeavesNothingBehind() = runBlocking {
        val fixture = newFixture()
        val invalid = assertThrows<RuntimeException> { fixture.runtime.createAccount("1nvalid", "", RuntimeFixture.CODE, RuntimeFixture.CODE) }
        assertTrue(invalid.error is RuntimeError.InvalidUsername)
        val weak = assertThrows<VaultException> { fixture.runtime.createAccount("selin_n", "", "123456", "123456") }
        assertEquals(VaultError.InvalidNewCode(SecretCodeError.SEQUENCE), weak.error)
        assertEquals(LockState.NEEDS_SETUP, fixture.runtime.lockState.value)
        assertFalse(File(fixture.environment.directories.vault, "vault.brpv").exists())
    }

    @Test
    fun screenshotsAndRecordingAreReported() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val peer = fixture.acceptedContact()
        fixture.runtime.openConversation(peer)
        withContext(fixture.main) { fixture.capture.takeScreenshot() }
        eventually { fixture.runtime.openMessages.value.any { it.body == MessageBody.Notice(SystemNotice.ScreenshotByMe) } }
        withContext(fixture.main) { fixture.capture.setCaptured(true) }
        eventually { fixture.runtime.isScreenCaptured.value }
        eventually { fixture.runtime.openMessages.value.any { it.body == MessageBody.Notice(SystemNotice.ScreenRecordingByMe) } }
        eventually { fixture.backend.sent(peer).count { it.control?.hasScreenshot() == true } == 2 }
        withContext(fixture.main) { fixture.capture.setCaptured(false) }
        eventually { !fixture.runtime.isScreenCaptured.value }
    }

    @Test
    fun aConversationThatIsNotOpenStillReportsThatItsMessagesChanged() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val deniz = fixture.acceptedContact()
        val other = fixture.acceptedContact()
        fixture.runtime.openConversation(other)
        assertNull(fixture.runtime.messageRevisions.value[deniz])
        fixture.push(textContent("kapalıyken"), deniz)
        eventually { (fixture.runtime.messageRevisions.value[deniz] ?: 0) > 0 }
        assertFalse(fixture.runtime.openMessages.value.any { it.body == MessageBody.Text("kapalıyken") })
        eventually { fixture.runtime.chats.value.firstOrNull { it.id == deniz }?.unreadCount == 1 }
        // Gone with the lock, like everything observable.
        fixture.phase(AppPhase.BACKGROUND)
        assertTrue(fixture.runtime.messageRevisions.value.isEmpty())
    }

    @Test
    fun bannersForOtherConversationsShowNoContent() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val deniz = fixture.acceptedContact()
        val other = fixture.acceptedContact()
        fixture.runtime.openConversation(deniz)
        fixture.push(textContent("gizli içerik"), other)
        eventually { fixture.runtime.banner.value != null }
        assertEquals(other, fixture.runtime.banner.value?.peer)
        assertEquals("Deniz", fixture.runtime.banner.value?.title)
        fixture.push(textContent("burada"), deniz)
        eventually { fixture.runtime.openMessages.value.any { it.body == MessageBody.Text("burada") } }
        // Displayed while open: no unread count for the open conversation.
        eventually { fixture.runtime.chats.value.firstOrNull { it.id == deniz }?.unreadCount == 0 }
    }

    @Test
    fun settingsAndPresenceFlowThroughTheFacade() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        fixture.connect()
        val peer = fixture.acceptedContact()
        fixture.push(presenceContent(PresenceMessage.State.ONLINE), peer)
        eventually { fixture.runtime.presence.value[peer] == PresenceStatus.Active }
        fixture.runtime.setPresenceSharing(false)
        assertTrue(fixture.runtime.presence.value.isEmpty())
        assertFalse(fixture.runtime.settings.value.presenceSharing)
        fixture.runtime.setReadReceipts(false)
        assertFalse(fixture.runtime.settings.value.readReceipts)
    }

    /** The list behind 7.1 › Engellenenler (the iOS `BlockedContactsRuntimeTests`). */
    @Test
    fun theBlockedListFollowsBlocksAndDeletedChatsAndGoesWithTheLock() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        val deniz = fixture.acceptedContact()
        assertTrue(fixture.runtime.blockedContacts.value.isEmpty())
        fixture.runtime.block(deniz)
        eventually(message = "deniz listed") { fixture.runtime.blockedContacts.value.map { it.account } == listOf(deniz) }
        fixture.runtime.deleteConversation(deniz, forBoth = false)
        eventually(message = "the chat went") { fixture.runtime.chats.value.none { it.id == deniz } }
        assertEquals("listed without a conversation", listOf(deniz), fixture.runtime.blockedContacts.value.map { it.account })

        fixture.runtime.unblock(deniz)
        eventually(message = "deniz unlisted") { fixture.runtime.blockedContacts.value.isEmpty() }
        assertTrue(fixture.runtime.chats.value.none { it.id == deniz })

        fixture.runtime.block(deniz)
        eventually(message = "deniz listed again") { fixture.runtime.blockedContacts.value.isNotEmpty() }
        fixture.runtime.lockNow()
        assertTrue("nothing decrypted stays while locked", fixture.runtime.blockedContacts.value.isEmpty())
        fixture.runtime.unlock(RuntimeFixture.CODE)
        eventually(message = "listed after unlocking") { fixture.runtime.blockedContacts.value.map { it.account } == listOf(deniz) }
    }

    @Test
    fun aBackupNeedsAStrongPasswordTwice() = runBlocking {
        val fixture = newFixture()
        fixture.onboard()
        assertThrows<InvalidBackupPasswordException> { fixture.runtime.exportBackup("kisa", "kisa") }
        val mismatch = assertThrows<RuntimeException> { fixture.runtime.exportBackup("yedek-parolasi-42", "yedek-parolasi-43") }
        assertEquals(RuntimeError.BackupPasswordMismatch, mismatch.error)
        fixture.runtime.recordBackupSaved(fixture.clock.now())
        assertEquals(fixture.clock.now(), fixture.runtime.lastBackupAt.value)
        fixture.runtime.lockNow()
        assertNull(fixture.runtime.lastBackupAt.value)
        fixture.runtime.unlock(RuntimeFixture.CODE)
        eventually { fixture.runtime.lastBackupAt.value == fixture.clock.now() }
    }

    @Test
    fun actionsNeedAnUnlockedVault() = runBlocking {
        val fixture = newFixture()
        val send = assertThrows<RuntimeException> { fixture.runtime.sendText("x", AccountId(UUID.randomUUID())) }
        assertEquals(RuntimeError.Locked, send.error)
        val change = assertThrows<RuntimeException> { fixture.runtime.changeCode("1", "2", "2") }
        assertEquals(RuntimeError.Locked, change.error)
        assertNull(fixture.runtime.ownAccountId)
        assertNotNull(fixture.runtime.lockState.value)
    }
}
