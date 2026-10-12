package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.PendingSetup
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * The core's `Messenger` on this storage end to end, against the core's fake relay: registration
 * saves the account, messages are stored with the ratchet in one transaction, everything survives
 * a relaunch, and a backup restores contacts with their conversations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessengerOnStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val relay = FakeRelay().start()

    @After
    fun stopRelay() = relay.close()

    /** Bob's phone: a messenger on [storage] whose receive loop stores every message like the app's engine. */
    private class Phone(val storage: LocalStorage, val messenger: Messenger, scope: CoroutineScope) {
        val stored = Channel<IncomingMessage>(Channel.UNLIMITED)
        val job: Job = scope.launch {
            storage.protocolStorage.withStagedInboundWrites {
                messenger.runReceiveLoop { message ->
                    storage.write { tx ->
                        if (tx.envelopes.contains(message.envelopeId)) return@write
                        if (message.content.hasData()) {
                            val conversation = tx.conversations.ensure(message.sender)
                            tx.messages.storeIncoming(message.content.data, conversation.id, message.serverTimestamp)
                        }
                        tx.envelopes.record(message.envelopeId)
                    }
                    stored.send(message)
                }
            }
        }

        suspend fun nextData() = withTimeout(60.seconds) {
            var message = stored.receive()
            while (!message.content.hasData()) message = stored.receive()
            message
        }

        suspend fun awaitUpToDate() = withTimeout(60.seconds) {
            messenger.connection.first { it == com.syconx.us2us.core.messenger.ConnectionState.UP_TO_DATE }
        }
    }

    private fun messengerOn(storage: LocalStorage, account: LocalAccount) = Messenger(
        account,
        ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage),
        storage.contactStore,
        RelayClient(relay.url),
        TestClient.testConfig,
        storage.accountStore,
    )

    @Test
    fun theMessengerRunsOnTheStorageAndSurvivesARelaunch() = runBlocking {
        coroutineScope {
            val environment = TestEnvironment(temporary.newFolder())
            var storage = environment.open()
            val registered = Messenger.register(
                Username("bob"),
                RelayClient(relay.url),
                storage.protocolStorage,
                storage.contactStore,
                TestClient.testConfig,
                storage.accountStore,
            )
            assertTrue(storage.hasSavedAccount)
            var bob = Phone(storage, registered, this)
            val alice = TestClient.register(relay.url, "alice", this)
            alice.awaitUpToDate()
            bob.awaitUpToDate()

            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(registered.accountId, "merhaba")
            assertEquals("merhaba", bob.nextData().content.data.text.body)
            assertEquals(ContactState.INCOMING_REQUEST, storage.contactStore.get(alice.accountId)?.state)
            assertFalse(storage.protocolStorage.hasStagedWrites)
            bob.messenger.acceptMessageRequest(alice.accountId)
            alice.next { it.content.hasControl() && it.content.control.hasAccessKey() }

            // The app relaunches: everything comes back from the database.
            bob.job.cancelAndJoin()
            storage.close()
            storage = environment.open()
            val account = storage.read { it.account.load()!! }
            assertEquals(registered.accountId, account.accountId)
            assertArrayEquals(registered.account.identityKeyPair.serialize(), account.identityKeyPair.serialize())
            bob = Phone(storage, messengerOn(storage, account), this)
            bob.awaitUpToDate()
            withTimeout(30.seconds) { while (bob.messenger.account.pendingSetup.isNotEmpty()) delay(20) }

            alice.messenger.sendText(account.accountId, "yeniden")
            assertEquals("yeniden", bob.nextData().content.data.text.body)
            bob.messenger.sendText(alice.accountId, "buradayım")
            assertEquals("buradayım", alice.nextData().content.data.text.body)

            val conversation = storage.read { it.conversations.forContact(alice.accountId)!! }
            val texts = storage.read { tx -> tx.messages.page(conversation.id).map { it.content?.text?.body } }
            assertEquals(listOf("merhaba", "yeniden"), texts)
            val contact = storage.contactStore.get(alice.accountId)!!
            assertEquals(ContactState.ACCEPTED, contact.state)
            assertNotNull(contact.theirAccessKey)
            assertTrue(contact.accessKeyShared)
            assertEquals(ByteString.copyFrom(account.accessKey.toBytes()), storage.read { it.contacts.get(alice.accountId)!!.ourAccessKeySent })

            // A backup restores the contacts on a new phone, each with its conversation.
            val backup = bob.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            bob.job.cancelAndJoin()
            storage.close()
            val newPhone = TestEnvironment(temporary.newFolder()).open()
            val restored = Messenger.restore(
                backup,
                "yedek parolam 7",
                RelayClient(relay.url),
                newPhone.protocolStorage,
                newPhone.contactStore,
                TestClient.testConfig,
                newPhone.accountStore,
            )
            newPhone.write { tx -> AccountBackupService.finishRestore(tx) }
            val restoredContact = newPhone.read { it.contacts.get(alice.accountId)!! }
            assertEquals(ContactState.ACCEPTED, restoredContact.requestState)
            assertEquals(ByteString.copyFrom(restored.account.accessKey.toBytes()), restoredContact.ourAccessKeySent)
            assertNotNull(newPhone.read { it.conversations.forContact(alice.accountId) })
            assertTrue(PendingSetup.REPLACE_PRE_KEYS !in newPhone.read { it.account.load()!! }.pendingSetup)
            newPhone.close()
            alice.job.cancelAndJoin()
        }
    }
}
