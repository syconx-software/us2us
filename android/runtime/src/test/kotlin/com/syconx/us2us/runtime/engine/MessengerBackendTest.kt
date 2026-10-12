package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.messenger.AvatarRecordStatus as CoreAvatarRecordStatus
import com.syconx.us2us.core.messenger.OutgoingAttachment
import com.syconx.us2us.core.messenger.TokenRotationResult as CoreTokenRotationResult
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.runtime.facade.RelayBackendFactory
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.ConnectionEvents
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.net.ConnectionSupervisor
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.PlainSqlDriver
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.storage.StorageLayout
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.support.happens
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine on the core's real `Messenger` and the core's fake relay, end to end: requests,
 * acceptance, receipts, the connection's backlog and `queue_empty`, and a relaunch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessengerBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val relay = FakeRelay().start()

    @After
    fun stopRelay() = relay.close()

    /** Bob's phone: storage, engine and connection, as the facade wires them. */
    private class Phone(root: File, val dek: DataKey = DataKey(ByteArray(32).also(SecureRandom()::nextBytes))) {
        val directories = AppDirectories(File(root, "app")).also { it.prepare() }
        val layout = StorageLayout.standard(directories)
        val storage: LocalStorage = LocalStorage.open(layout, dek, java.time.Instant.now(), PlainSqlDriver).storage
        val key = SecretKeyMaterial(dek.toBytes())
        lateinit var backend: MessagingBackend
        lateinit var engine: MessagingEngine
        lateinit var supervisor: ConnectionSupervisor
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun start(backend: MessagingBackend, connect: Boolean = true) {
            this.backend = backend
            engine = MessagingEngine(storage, backend, directories.runtime, key)
            supervisor = ConnectionSupervisor(
                opener = { backend.openStream() },
                events = ConnectionEvents(deliver = { engine.receive(it) }, queueEmpty = { engine.connectionReachedQueueEmpty() }),
                scope = scope,
                backoff = Backoff(base = 200.milliseconds, cap = 1.seconds),
            )
            engine.start()
            if (connect) supervisor.start()
        }

        suspend fun stop() {
            supervisor.stop()
            engine.stop()
            backend.close()
            scope.cancel()
            storage.close()
        }
    }

    @Test
    fun aRequestIsAcceptedAndMessagesFlowBothWays() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val bob = Phone(temporary.newFolder())
            bob.start(factory.register(Username("bob"), bob.storage))
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }

            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bob.backend.accountId, "merhaba")

            // A request: stored with the verified username, nothing sent back.
            eventually(message = "request stored") { bob.engine.messages(alice.accountId).isNotEmpty() }
            val request = bob.storage.read { it.contacts.get(alice.accountId)!! }
            assertEquals(ContactState.INCOMING_REQUEST, request.requestState)
            assertEquals(UsernameStatus.VERIFIED, request.profile.usernameStatus)
            assertEquals("alice", request.profile.username?.value)
            assertEquals(MessageBody.Text("merhaba"), bob.engine.messages(alice.accountId).single().body)
            assertTrue(bob.engine.chatSummaries().single().isRequest)

            // Accepting: ACCEPT first, then our key; the request's message gets its delivery receipt.
            bob.engine.acceptRequest(alice.accountId)
            alice.next { it.content.hasControl() && it.content.control.hasRequestResponse() }
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            val receipt = alice.next { it.content.hasReceipt() }.content.receipt
            assertEquals(ReceiptMessage.Type.DELIVERY, receipt.type)

            // Both directions now, with access keys.
            val sent = alice.messenger.sendText(bob.backend.accountId, "nasılsın?")
            eventually(message = "second message") { bob.engine.messages(alice.accountId).size >= 3 }
            val delivered = alice.next { it.content.hasReceipt() }.content.receipt
            assertEquals(listOf(ByteString.copyFrom(sent.messageId)), delivered.messageIdsList)
            val reply = bob.engine.send(OutgoingContent.Text("iyiyim"), alice.accountId)
            assertEquals("iyiyim", alice.nextData().content.data.text.body)
            eventually(message = "sent") {
                bob.engine.messages(alice.accountId).first { it.messageId == reply }.status == OutgoingStatus.SENT
            }
            val contact = bob.storage.read { it.contacts.get(alice.accountId)!! }
            assertEquals(ContactState.ACCEPTED, contact.requestState)
            assertTrue(contact.theirAccessKey != null)

            // Alice reads it: the bubble shows it.
            alice.messenger.sendReceipt(bob.backend.accountId, ReceiptMessage.Type.READ, listOf(reply.toByteArray()))
            eventually(message = "read") {
                bob.engine.messages(alice.accountId).first { it.messageId == reply }.status == OutgoingStatus.READ
            }

            bob.stop()
            alice.job.cancelAndJoin()
        }
    }

    @Test
    fun aStrangersInvisibleMessageCreatesNoContact() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val bob = Phone(temporary.newFolder())
            bob.start(factory.register(Username("bob"), bob.storage))
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val mallory = TestClient.register(relay.url, "mallory", this)
            mallory.messenger.findByUsername("bob")
            // A receipt from someone Bob never heard of: no request, no contact.
            mallory.messenger.send(
                bob.backend.accountId,
                receiptContent(ReceiptMessage.Type.READ, listOf(RandomIds.make())),
            )
            assertFalse(happens(2.seconds) { bob.storage.read { it.contacts.get(mallory.accountId) } != null })
            assertTrue(bob.engine.chatSummaries().isEmpty())
            // A visible message opens the request as usual.
            mallory.messenger.sendText(bob.backend.accountId, "selam")
            eventually { bob.storage.read { it.contacts.get(mallory.accountId) }?.requestState == ContactState.INCOMING_REQUEST }
            bob.stop()
            mallory.job.cancelAndJoin()
        }
    }

    @Test
    fun anOutgoingRequestIsAcceptedByTheContact() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val bob = Phone(temporary.newFolder())
            bob.start(factory.register(Username("bob"), bob.storage))
            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }

            val peer = bob.engine.startConversation(Username("alice"))
            assertEquals(alice.accountId, peer)
            bob.engine.send(OutgoingContent.Text("selam alice"), peer)
            val first = alice.nextData()
            assertEquals("selam alice", first.content.data.text.body)
            // While our request is pending it carries our username (§6.3).
            assertEquals("bob", first.senderUsername)

            alice.messenger.acceptMessageRequest(bob.backend.accountId)
            eventually(message = "accepted") {
                bob.storage.read { it.contacts.get(alice.accountId) }?.requestState == ContactState.ACCEPTED
            }
            eventually(message = "acceptance line") {
                bob.engine.messages(alice.accountId).any { it.body == MessageBody.Notice(SystemNotice.RequestAccepted) }
            }
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            bob.stop()
            alice.job.cancelAndJoin()
        }
    }

    @Test
    fun theCoresAnswersMapOneToOne() {
        assertEquals(TokenRotationResult.READY, MessengerBackend.tokenRotationResult(CoreTokenRotationResult.READY))
        assertEquals(
            TokenRotationResult.BACKUP_NO_LONGER_VALID,
            MessengerBackend.tokenRotationResult(CoreTokenRotationResult.BACKUP_NO_LONGER_VALID),
        )
        assertEquals(AvatarRecordStatus.IN_SYNC, MessengerBackend.avatarRecordStatus(CoreAvatarRecordStatus.IN_SYNC))
        assertEquals(AvatarRecordStatus.PENDING, MessengerBackend.avatarRecordStatus(CoreAvatarRecordStatus.PENDING))
        assertEquals(AvatarRecordStatus.UNAVAILABLE, MessengerBackend.avatarRecordStatus(CoreAvatarRecordStatus.UNAVAILABLE))
    }

    /** 4.2: an exact hit's picture comes from its avatar record, with no contact made and no second request within a minute. */
    @Test
    fun aSearchResultsPictureNeedsNoContact() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val bob = Phone(temporary.newFolder())
            bob.start(factory.register(Username("bob"), bob.storage), connect = false)
            val alice = TestClient.register(relay.url, "alice", this)
            alice.messenger.setProfilePicture(ProfilePicture.preset(3))
            assertEquals(CoreAvatarRecordStatus.IN_SYNC, alice.messenger.avatarRecordStatus.value)

            val found = bob.backend.findAccount(Username("alice"))!!
            assertEquals(alice.accountId, found.account)
            val lookup = bob.engine.avatar(Username("alice"), found.account)
            assertEquals(AvatarLookup.Source.SEARCH, lookup.source)
            assertEquals(ProfilePicture.preset(3), lookup.picture)
            assertNull("a search records nothing", bob.storage.read { it.contacts.get(alice.accountId) })

            val fetches = relay.avatarFetches
            assertEquals(ProfilePicture.preset(3), bob.engine.avatar(Username("alice"), found.account).picture)
            assertEquals("answered from memory within a minute", fetches, relay.avatarFetches)
            // Locking forgets the copies: the next search asks again.
            bob.backend.close()
            assertEquals(ProfilePicture.preset(3), bob.engine.avatar(Username("alice"), found.account).picture)
            assertEquals(fetches + 1, relay.avatarFetches)
            // A record opens only under the name the account has: anything else is the initial letter.
            assertEquals(AvatarLookup.Source.INITIAL_LETTER, bob.engine.avatar(Username("alicea"), alice.accountId).source)
            bob.stop()
            alice.job.cancelAndJoin()
        }
    }

    /** 7.1's footnote: a record write still owed, made again on request, and a relay without avatar records. */
    @Test
    fun theOwnRecordsStatusFollowsTheRelay() = runBlocking {
        val factory = RelayBackendFactory(relay.url)
        val bob = Phone(temporary.newFolder())
        // Not connected: nothing retries the record in the background meanwhile.
        bob.start(factory.register(Username("bob"), bob.storage), connect = false)
        assertEquals(AvatarRecordStatus.IN_SYNC, bob.engine.ownAvatar().searchRecord)
        // The core's write and the engine's retry both get no answer.
        relay.failNext("PUT", "/v1/accounts/me/avatar")
        relay.failNext("PUT", "/v1/accounts/me/avatar")
        assertEquals(AvatarRecordStatus.PENDING, bob.engine.setOwnAvatar(ProfilePicture.preset(5), showInSearch = true).searchRecord)
        assertEquals(AvatarRecordStatus.PENDING, bob.engine.ownAvatar().searchRecord)
        assertEquals(AvatarRecordStatus.IN_SYNC, bob.engine.syncAvatarRecord())
        assertEquals(AvatarRecordStatus.IN_SYNC, bob.engine.ownAvatar().searchRecord)
        assertTrue(relay.avatarRecord(bob.backend.accountId) != null)

        relay.avatarsSupported = false
        assertEquals(AvatarRecordStatus.UNAVAILABLE, bob.engine.setOwnAvatar(ProfilePicture.preset(6), showInSearch = true).searchRecord)
        assertEquals(AvatarRecordStatus.UNAVAILABLE, bob.engine.syncAvatarRecord())
        bob.stop()
    }

    /** The bubble's ring: the fraction of the blob received, reported while it downloads, ending at 1. */
    @Test
    fun aDownloadReportsWhatTheRelayHasSentSoFar() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val bob = Phone(temporary.newFolder())
            bob.start(factory.register(Username("bob"), bob.storage))
            val changes = CopyOnWriteArrayList<EngineChange>()
            val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { bob.engine.changes.collect { changes += it } }
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bob.backend.accountId, "dosya geliyor")
            eventually(message = "request stored") { bob.engine.messages(alice.accountId).isNotEmpty() }
            bob.engine.acceptRequest(alice.accountId)
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }

            val file = ByteArray(300_000).also(SecureRandom()::nextBytes)
            val sent = alice.messenger.sendAttachment(
                bob.backend.accountId,
                OutgoingAttachment(Attachment.Kind.FILE, "application/octet-stream", source = { ByteArrayInputStream(file) }, fileName = "rapor.bin"),
            )
            val id = ByteString.copyFrom(sent.messageId)
            eventually(message = "downloaded") {
                val body = bob.engine.messages(alice.accountId).firstOrNull { it.messageId == id }?.body
                body is MessageBody.Attachment && body.attachment.availability == AttachmentItem.Availability.LOCAL
            }
            eventually(message = "progress ends at 1") { changes.any { it is EngineChange.DownloadProgress && it.fraction == 1.0 } }
            val progress = changes.filterIsInstance<EngineChange.DownloadProgress>()
            assertTrue(progress.all { it.messageId == id && it.peer == alice.accountId })
            val fractions = progress.map { it.fraction }
            assertTrue("reported while it downloads: $fractions", fractions.count { it > 0.0 && it < 1.0 } >= 2)
            assertEquals("never backwards: $fractions", fractions.sorted(), fractions)
            assertEquals(1.0, fractions.last(), 0.0)
            collector.cancel()
            bob.stop()
            alice.job.cancelAndJoin()
        }
    }

    @Test
    fun everythingSurvivesARelaunch() = runBlocking {
        coroutineScope {
            val factory = RelayBackendFactory(relay.url)
            val root = temporary.newFolder()
            var bob = Phone(root)
            bob.start(factory.register(Username("bob"), bob.storage))
            val bobId = bob.backend.accountId
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val alice = TestClient.register(relay.url, "alice", this)
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bobId, "ilk")
            eventually { bob.engine.messages(alice.accountId).isNotEmpty() }
            bob.engine.acceptRequest(alice.accountId)
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            val dek = bob.dek
            bob.stop()

            // Messages that arrive while the phone is off wait at the relay.
            alice.messenger.sendText(bobId, "kapalıyken")
            bob = Phone(root, dek)
            val account = bob.storage.read { it.account.load()!! }
            bob.start(factory.open(account, bob.storage))
            eventually(message = "backlog") {
                bob.engine.messages(alice.accountId).any { (it.body as? MessageBody.Text)?.text == "kapalıyken" }
            }
            assertEquals(
                listOf("ilk", "kapalıyken"),
                bob.engine.messages(alice.accountId).mapNotNull { (it.body as? MessageBody.Text)?.text },
            )
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            bob.engine.send(OutgoingContent.Text("geri döndüm"), alice.accountId)
            assertEquals("geri döndüm", alice.nextData().content.data.text.body)
            assertNull(alice.messenger.contact(bobId)?.takeIf { it.state != ContactState.ACCEPTED })
            bob.stop()
            alice.job.cancelAndJoin()
        }
    }
}
