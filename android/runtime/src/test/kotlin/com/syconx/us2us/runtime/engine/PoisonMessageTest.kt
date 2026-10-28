package com.syconx.us2us.runtime.engine

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.text
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
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Audit 2026-10-28 H-1: a validly encrypted but malformed message (or one the engine cannot take)
 * must never stop the receive loop. Bob runs the app's whole receive path (staged inbound writes,
 * engine, supervisor) against the core's fake relay; the attacker is a stranger with a real client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PoisonMessageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val relay = FakeRelay().start()

    @After
    fun stopRelay() = relay.close()

    private class Phone(root: File, private val intercept: (suspend (IncomingMessage) -> Unit)? = null) {
        val dek = DataKey(ByteArray(32).also(SecureRandom()::nextBytes))
        val directories = AppDirectories(File(root, "app")).also { it.prepare() }
        val storage: LocalStorage = LocalStorage.open(StorageLayout.standard(directories), dek, java.time.Instant.now(), PlainSqlDriver).storage
        lateinit var backend: MessagingBackend
        lateinit var engine: MessagingEngine
        lateinit var supervisor: ConnectionSupervisor
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun start(backend: MessagingBackend) {
            this.backend = backend
            engine = MessagingEngine(storage, backend, directories.runtime, SecretKeyMaterial(dek.toBytes()))
            supervisor = ConnectionSupervisor(
                opener = { backend.openStream() },
                events = ConnectionEvents(
                    deliver = { message ->
                        intercept?.invoke(message)
                        engine.receive(message)
                    },
                    queueEmpty = { engine.connectionReachedQueueEmpty() },
                ),
                scope = scope,
                backoff = Backoff(base = 100.milliseconds, cap = 300.milliseconds),
            )
            engine.start()
            supervisor.start()
        }

        suspend fun stop() {
            supervisor.stop()
            engine.stop()
            backend.close()
            scope.cancel()
            storage.close()
        }
    }

    private fun malformedText(idBytes: Int, body: String): Content = content {
        data = dataMessage {
            messageId = ByteArray(idBytes) { 9 }.toByteString()
            sentAt = 1_760_000_000_000
            text = text { this.body = body }
        }
    }

    @Test
    fun malformedButDecryptableMessagesAreDroppedAndAcknowledgedAndDeliveryContinues() = runBlocking {
        coroutineScope {
            val bob = Phone(temporary.newFolder())
            bob.start(RelayBackendFactory(relay.url).register(Username("bob"), bob.storage))
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val mallory = TestClient.register(relay.url, "mallory", this)
            mallory.awaitUpToDate()
            mallory.messenger.findByUsername("bob")
            val to = bob.backend.accountId

            // The attack of the audit: a visible text whose message_id is 3 bytes (a stranger's
            // text opens a request, so no contact state is needed), then the other fixed-size ids.
            mallory.messenger.send(to, malformedText(3, "wedge"))
            mallory.messenger.send(to, malformedText(17, "wedge too"))
            mallory.messenger.send(to, receiptContent(ReceiptMessage.Type.READ, listOf(byteArrayOf(1, 2, 3).toByteString())))
            mallory.messenger.send(to, content {
                data = dataMessage {
                    messageId = RandomIds.make()
                    sentAt = 1
                    text = text { body = "quoting nothing" }
                    quote = quote { targetMessageId = byteArrayOf(4).toByteString() }
                }
            })
            mallory.messenger.send(to, content {
                data = dataMessage {
                    messageId = RandomIds.make()
                    sentAt = 1
                    liveLocation = liveLocation { sessionId = byteArrayOf(5, 6).toByteString() }
                }
            })
            mallory.messenger.sendText(to, "selam")

            eventually(message = "the valid message behind the bad ones arrives") {
                bob.engine.messages(mallory.accountId).any { it.body == MessageBody.Text("selam") }
            }
            assertEquals(listOf(MessageBody.Text("selam")), bob.engine.messages(mallory.accountId).map { it.body })
            assertEquals(ContactState.INCOMING_REQUEST, bob.storage.read { it.contacts.get(mallory.accountId)!! }.requestState)
            eventually(message = "every envelope acknowledged") { relay.mailboxSize(to) == 0 }

            bob.stop()
            mallory.job.cancelAndJoin()
        }
    }

    @Test
    fun aMessageTheEngineKeepsRefusingIsDroppedAfterBoundedAttemptsWithoutBlockingTheOthers() = runBlocking {
        coroutineScope {
            val poisonAttempts = AtomicInteger()
            lateinit var bob: Phone
            bob = Phone(temporary.newFolder()) { message ->
                if (message.content.hasData() && message.content.data.text.body == "poison") {
                    poisonAttempts.incrementAndGet()
                    // As the engine does when it cannot store a message: the decryption is rolled back.
                    bob.storage.protocolStorage.discardStagedInboundWrites()
                    throw IllegalStateException("the engine refused the message")
                }
            }
            bob.start(RelayBackendFactory(relay.url).register(Username("bob"), bob.storage))
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            val to = bob.backend.accountId

            alice.messenger.sendText(to, "poison")
            alice.messenger.sendText(to, "after the poison")
            // Not blocked: the next envelope is handled while the bad one waits at the relay.
            eventually(message = "the next message arrives") {
                bob.engine.messages(alice.accountId).any { it.body == MessageBody.Text("after the poison") }
            }
            eventually(message = "only the refused envelope waits") { relay.mailboxSize(to) == 1 }
            assertEquals(1, poisonAttempts.get())

            // Each new connection tries it again; after the last allowed attempt it is acknowledged.
            while (poisonAttempts.get() < Messenger.MAX_HANDLER_ATTEMPTS) {
                val seen = poisonAttempts.get()
                bob.supervisor.stop()
                bob.supervisor.start()
                eventually(message = "redelivered") { poisonAttempts.get() > seen }
            }
            eventually(message = "dropped and acknowledged") { relay.mailboxSize(to) == 0 }
            bob.supervisor.stop()
            bob.supervisor.start()
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            kotlinx.coroutines.delay(500.milliseconds)
            assertEquals(Messenger.MAX_HANDLER_ATTEMPTS, poisonAttempts.get())
            assertEquals(listOf(MessageBody.Text("after the poison")), bob.engine.messages(alice.accountId).map { it.body })

            bob.stop()
            alice.job.cancelAndJoin()
        }
    }

    @Test
    fun aClosedStoreNeverCountsAsAFailedAttempt() = runBlocking {
        coroutineScope {
            val refusals = AtomicInteger()
            val storeClosed = AtomicBoolean(true)
            lateinit var bob: Phone
            bob = Phone(temporary.newFolder()) { message ->
                if (storeClosed.get() && message.content.hasData()) {
                    refusals.incrementAndGet()
                    bob.storage.protocolStorage.discardStagedInboundWrites()
                    throw EngineException(EngineError.STOPPED)
                }
            }
            bob.start(RelayBackendFactory(relay.url).register(Username("bob"), bob.storage))
            eventually { bob.supervisor.status.value == ConnectionStatus.Open }
            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bob.backend.accountId, "kept")

            // The vault closed under it more often than a bad message may fail: still kept.
            eventually(timeout = 60.seconds, message = "refused often") { refusals.get() > Messenger.MAX_HANDLER_ATTEMPTS + 1 }
            assertEquals(1, relay.mailboxSize(bob.backend.accountId))
            storeClosed.set(false)
            eventually(message = "delivered once the store is back") {
                bob.engine.messages(alice.accountId).any { it.body == MessageBody.Text("kept") }
            }
            eventually { relay.mailboxSize(bob.backend.accountId) == 0 }
            bob.stop()
            alice.job.cancelAndJoin()
        }
    }
}
