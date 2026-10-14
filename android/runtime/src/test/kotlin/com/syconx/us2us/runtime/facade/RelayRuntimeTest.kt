package com.syconx.us2us.runtime.facade

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.TokenRefusedException
import com.syconx.us2us.core.messenger.TokenRotationPendingException
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.WrongBackupPasswordException
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessagingBackend
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.PlainSqlDriver
import com.syconx.us2us.runtime.support.AppPhase
import com.syconx.us2us.runtime.support.NoBackgroundTasks
import com.syconx.us2us.runtime.support.ManualCaptureEvents
import com.syconx.us2us.runtime.support.RuntimeStrings
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.SystemClipboard
import com.syconx.us2us.runtime.support.SystemRuntimeClock
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.InMemorySecureItemStore
import com.syconx.us2us.runtime.vault.NoBiometrics
import com.syconx.us2us.runtime.vault.VaultController
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The app runtime with the production backend factory against the core's fake relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RelayRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val relay = FakeRelay().start()
    private val phones = ArrayList<Phone>()

    @After
    fun tearDown() = runBlocking {
        for (phone in phones) {
            try {
                phone.runtime.lockNow()
            } catch (_: Exception) {
            }
            phone.main.close()
        }
        relay.close()
    }

    private inner class Phone(root: File, backends: BackendFactory = RelayBackendFactory(relay.url)) {
        val main = Executors.newSingleThreadExecutor { Thread(it, "phone-main") }.asCoroutineDispatcher()
        val environment = RuntimeEnvironment(
            directories = AppDirectories(File(root, "app")),
            secureItems = InMemorySecureItemStore(),
            biometrics = NoBiometrics,
            backends = backends,
            clock = SystemRuntimeClock,
            vault = VaultController.Configuration(iterations = 1000),
            engine = MessagingEngine.Configuration(presenceReplyDelay = Duration.ZERO),
            reconnectBackoff = Backoff(base = 200.milliseconds, cap = 1.seconds, floor = 100.milliseconds),
            storage = StorageOpener { layout, key, now -> key.withDataKey { LocalStorage.open(layout, it, now, PlainSqlDriver) } },
        )
        val runtime: AppRuntime = runBlocking {
            withContext(main) {
                AppRuntime(
                    environment,
                    NoBackgroundTasks(),
                    ManualCaptureEvents(),
                    SecurePasteboard(SystemClipboard(org.robolectric.RuntimeEnvironment.getApplication())),
                    RuntimeStrings("en") { key, _ -> key.name },
                    main,
                )
            }
        }

        init {
            phones += this
        }

        suspend fun phase(phase: AppPhase) = withContext(main) { runtime.scenePhaseChanged(phase) }
    }

    /** An account with an exported backup, on a phone that is locked afterwards. */
    private suspend fun backedUpAccount(): Pair<AccountId, ByteArray> {
        val phone = Phone(temporary.newFolder())
        phone.runtime.createAccount("selin_n", "Selin", CODE, CODE)
        eventually { phone.runtime.connection.value == ConnectionStatus.Open }
        val account = phone.runtime.ownAccountId!!
        val file = phone.runtime.exportBackup(PASSWORD, PASSWORD).readBytes()
        phone.runtime.lockNow()
        return account to file
    }

    /** A restore whose rotation was carried out by the relay, but neither its answer nor the follow-up question got through (§5.5 step 3.4). */
    private suspend fun restoreLosingTheRotationsAnswer(file: ByteArray): Phone {
        val phone = Phone(temporary.newFolder())
        // Not in the foreground: nothing connects by itself, so only an explicit check settles it.
        phone.phase(AppPhase.INACTIVE)
        relay.loseNextAnswer("POST", ROTATION)
        relay.failNext("POST", ROTATION)
        phone.runtime.restore(file, PASSWORD, NEW_CODE, NEW_CODE)
        assertEquals(LockState.UNLOCKED, phone.runtime.lockState.value)
        return phone
    }

    @Test
    fun registerReceiveAcceptAndRestoreOnANewPhone() = runBlocking {
        coroutineScope {
            val phone = Phone(temporary.newFolder())
            phone.runtime.createAccount("selin_n", "Selin", CODE, CODE)
            assertEquals(LockState.UNLOCKED, phone.runtime.lockState.value)
            eventually { phone.runtime.connection.value == ConnectionStatus.Open }
            val selin = phone.runtime.ownAccountId!!

            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            alice.messenger.findByUsername("selin_n")
            alice.messenger.sendText(selin, "merhaba selin")
            eventually(message = "request listed") { phone.runtime.requests.value.any { it.id == alice.accountId } }
            phone.runtime.acceptRequest(alice.accountId)
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            eventually(message = "accepted chat") { phone.runtime.chats.value.any { it.id == alice.accountId } }

            // The profile follows the key exchange, with the display name from onboarding.
            val profile = alice.next { it.content.hasControl() && it.content.control.hasProfile() }.content.control.profile
            assertEquals("Selin", profile.displayName)
            assertEquals("selin_n", profile.username)

            // A conversation on screen marks its messages displayed and answers with a read receipt.
            phone.runtime.openConversation(alice.accountId)
            alice.next { it.content.hasReceipt() && it.content.receipt.type == com.syconx.us2us.core.proto.ReceiptMessage.Type.READ }
            phone.runtime.sendText("hoş geldin", alice.accountId)
            assertEquals("hoş geldin", alice.nextData().content.data.text.body)

            // Lock and unlock: the history is still there and the line opens again.
            phone.runtime.lockNow()
            phone.runtime.unlock(CODE)
            eventually { phone.runtime.connection.value == ConnectionStatus.Open }
            phone.runtime.openConversation(alice.accountId)
            eventually { phone.runtime.openMessages.value.any { it.body == MessageBody.Text("hoş geldin") } }

            // Backup → restore on a new phone; the old phone loses its line.
            val file = phone.runtime.exportBackup(PASSWORD, PASSWORD).readBytes()
            val newPhone = Phone(temporary.newFolder())
            try {
                newPhone.runtime.restore(file, "yanlis-parola-00", CODE, CODE)
                fail("a wrong password restores nothing")
            } catch (_: WrongBackupPasswordException) {
            }
            assertEquals(LockState.NEEDS_SETUP, newPhone.runtime.lockState.value)
            newPhone.runtime.restore(file, PASSWORD, "730415", "730415")
            assertEquals(LockState.UNLOCKED, newPhone.runtime.lockState.value)
            assertEquals("Selin", newPhone.runtime.settings.value.displayName)
            assertEquals(ContactState.ACCEPTED, newPhone.runtime.contact(alice.accountId)?.requestState)
            eventually { newPhone.runtime.chats.value.any { it.id == alice.accountId } }

            // The new phone takes over the conversation.
            eventually { newPhone.runtime.connection.value == ConnectionStatus.Open }
            // The old phone's next connection is refused for good.
            phone.runtime.lockNow()
            phone.runtime.unlock(CODE)
            eventually(message = "old line revoked") { phone.runtime.connection.value == ConnectionStatus.Revoked }
            newPhone.runtime.sendText("yeni telefon", alice.accountId)
            assertEquals("yeni telefon", alice.nextData().content.data.text.body)

            // The backup's token was rotated: the file can never be restored again.
            try {
                newPhone.runtime.checkBackup(file, PASSWORD)
                fail("a used backup is refused")
            } catch (e: RuntimeException) {
                assertEquals(RuntimeError.BackupRevoked, e.error)
            }
            assertTrue(newPhone.runtime.lockState.value == LockState.UNLOCKED)
            alice.job.cancelAndJoin()
        }
    }

    /** 7.1 and 4.1 say so while the relay has not said which token the account has; nothing goes out with either token meanwhile. */
    @Test
    fun aRestoreWhoseRotationGotNoAnswerSaysSoUntilItIsSettled() = runBlocking {
        val (selin, file) = backedUpAccount()
        val phone = restoreLosingTheRotationsAnswer(file)
        assertTrue(phone.runtime.tokenRotationPending.value)

        val lookups = relay.requests.count { it.startsWith("GET /v1/directory/") }
        try {
            phone.runtime.lookupAccount("selin_n")
            fail("nothing is asked with either token")
        } catch (_: TokenRotationPendingException) {
        }
        assertEquals(lookups, relay.requests.count { it.startsWith("GET /v1/directory/") })

        // Kept with the account: still pending once the vault is opened again.
        phone.runtime.lockNow()
        assertFalse("false while locked", phone.runtime.tokenRotationPending.value)
        phone.runtime.unlock(NEW_CODE)
        assertTrue(phone.runtime.tokenRotationPending.value)

        // “Şimdi kontrol et”: the relay holds the new token.
        assertEquals(TokenRotationCheck.SETTLED, phone.runtime.checkPendingTokenRotation())
        assertFalse(phone.runtime.tokenRotationPending.value)
        phone.phase(AppPhase.ACTIVE)
        eventually { phone.runtime.connection.value == ConnectionStatus.Open }
        assertEquals(selin, phone.runtime.lookupAccount("selin_n")?.account)
    }

    /** The connection settles it by itself: every attempt asks the relay first, and the notice goes once it has answered. */
    @Test
    fun theConnectionSettlesAPendingRotation() = runBlocking {
        val (_, file) = backedUpAccount()
        val phone = restoreLosingTheRotationsAnswer(file)
        assertTrue(phone.runtime.tokenRotationPending.value)
        // The first attempt gets no answer either; the backoff asks again.
        relay.failNext("POST", ROTATION)
        phone.phase(AppPhase.ACTIVE)
        eventually { phone.runtime.connection.value == ConnectionStatus.Open }
        eventually(message = "notice gone") { !phone.runtime.tokenRotationPending.value }
    }

    @Test
    fun aRotationRefusedLaterSignsThisPhoneOut() = runBlocking {
        val (selin, file) = backedUpAccount()
        val phone = restoreLosingTheRotationsAnswer(file)
        // Another phone restored the same backup and rotated first.
        relay.rotateTokenElsewhere(selin)
        assertEquals(TokenRotationCheck.REFUSED, phone.runtime.checkPendingTokenRotation())
        assertFalse(phone.runtime.tokenRotationPending.value)
        phone.phase(AppPhase.ACTIVE)
        eventually(message = "line revoked") { phone.runtime.connection.value == ConnectionStatus.Revoked }
    }

    @Test
    fun aBackupRestoredElsewhereDuringTheRotationLeavesNothing() = runBlocking {
        val (_, file) = backedUpAccount()
        val production = RelayBackendFactory(relay.url)
        val racing = object : BackendFactory by production {
            override suspend fun restore(backup: AccountBackupFile, storage: LocalStorage): MessagingBackend {
                // Another phone restores the same backup between the relay check and the rotation.
                relay.rotateTokenElsewhere(backup.accountId)
                return production.restore(backup, storage)
            }
        }
        val phone = Phone(temporary.newFolder(), racing)
        try {
            phone.runtime.restore(file, PASSWORD, NEW_CODE, NEW_CODE)
            fail("a refused rotation restores nothing")
        } catch (e: RuntimeException) {
            assertEquals(RuntimeError.BackupRevoked, e.error)
        }
        assertEquals(LockState.NEEDS_SETUP, phone.runtime.lockState.value)
        assertFalse(phone.runtime.keptAccountAfterFailedSetup.value)
    }

    /** The relay answers `400` for the new token (§5.5): the core's own path, not a stand-in. */
    @Test
    fun aRefusedNewTokenLeavesNothingAndTheBackupStillWorks() = runBlocking {
        val (selin, file) = backedUpAccount()
        val phone = Phone(temporary.newFolder())
        relay.refuseNext("POST", ROTATION, 400)
        val rotations = relay.tokenRotations.size
        try {
            phone.runtime.restore(file, PASSWORD, NEW_CODE, NEW_CODE)
            fail("a refused token restores nothing")
        } catch (_: TokenRefusedException) {
        }
        assertEquals("one question, refused", rotations + 1, relay.tokenRotations.size)
        assertEquals(LockState.NEEDS_SETUP, phone.runtime.lockState.value)
        assertFalse(phone.runtime.keptAccountAfterFailedSetup.value)
        assertFalse("nothing kept", phone.runtime.vault.hasVault)

        // Nothing changed on the relay: the same file restores on the next try.
        phone.runtime.restore(file, PASSWORD, NEW_CODE, NEW_CODE)
        assertEquals(LockState.UNLOCKED, phone.runtime.lockState.value)
        assertEquals(selin, phone.runtime.ownAccountId)
        eventually { phone.runtime.connection.value == ConnectionStatus.Open }
    }

    /** 4.2's picture of a search result through the runtime: kept for a minute, forgotten with the lock. */
    @Test
    fun aSearchResultsPictureGoesWithTheLock() = runBlocking {
        coroutineScope {
            val alice = TestClient.register(relay.url, "alice", this)
            alice.messenger.setProfilePicture(ProfilePicture.preset(3))
            val phone = Phone(temporary.newFolder())
            phone.runtime.createAccount("selin_n", "Selin", CODE, CODE)
            val found = phone.runtime.lookupAccount("alice")!!
            assertEquals(alice.accountId, found.account)
            val before = relay.avatarFetches
            assertEquals(ProfilePicture.preset(3), phone.runtime.avatar("alice", found.account).picture)
            assertEquals(before + 1, relay.avatarFetches)
            phone.runtime.avatar("alice", found.account)
            assertEquals("answered from memory", before + 1, relay.avatarFetches)

            // The lock's teardown forgets the copy in the backend that held it.
            val backend = phone.runtime.session!!.backend
            phone.runtime.lockNow()
            assertEquals(ProfilePicture.preset(3), backend.searchAvatar(Username("alice"), found.account))
            assertEquals(before + 2, relay.avatarFetches)

            phone.runtime.unlock(CODE)
            assertEquals(ProfilePicture.preset(3), phone.runtime.avatar("alice", found.account).picture)
            assertEquals("asked again after the unlock", before + 3, relay.avatarFetches)
            alice.job.cancelAndJoin()
        }
    }

    private companion object {
        const val CODE = "482917"
        const val NEW_CODE = "730415"
        const val PASSWORD = "yedek-parolasi-42"
        const val ROTATION = "/v1/accounts/me/token"
    }
}
