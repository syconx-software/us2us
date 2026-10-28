package com.syconx.us2us.core.messenger

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.google.protobuf.ByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.presence.PresencePolicy
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.BackupContact
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.WrongBackupPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The facade end to end against [FakeRelay]. The shared scenario comes from [ConversationScenario]. */
class MessengerTest : ConversationScenario() {
    private val relay = FakeRelay().start()
    override val relayUrl: String get() = relay.url

    @AfterEach
    fun stopRelay() = relay.close()

    override fun expectDelivery(recipient: TestClient, method: String) = assertEquals(method, relay.lastDeliveryTo(recipient.accountId))

    override suspend fun finalChecks(alice: TestClient, bob: TestClient) {
        assertEquals(emptyList<String>(), relay.violations, "the client leaked sender/recipient links")
        assertEquals(0, relay.mediaCount(), "the recipient deletes downloaded media")
    }

    private suspend fun acceptedPair(scope: CoroutineScope): Pair<TestClient, TestClient> {
        val alice = TestClient.register(relayUrl, "alice", scope)
        val bob = TestClient.register(relayUrl, "bob", scope)
        alice.awaitUpToDate()
        bob.awaitUpToDate()
        alice.messenger.findByUsername("bob")
        alice.messenger.sendText(bob.accountId, "hi")
        bob.nextData()
        bob.messenger.acceptMessageRequest(alice.accountId)
        bob.next { it.content.hasControl() && it.content.control.hasAccessKey() }
        return alice to bob
    }

    @Test
    fun `usernames are unique`() = runBlocking {
        Messenger.register(Username("taken"), RelayClient(relayUrl), config = TestClient.testConfig)
        val error = assertThrows<RelayException> {
            runBlocking { Messenger.register(Username("taken"), RelayClient(relayUrl), config = TestClient.testConfig) }
        }
        assertEquals(RelayErrorCode.USERNAME_TAKEN, error.code)
    }

    @Test
    fun `a taken username leaves nothing behind and another one can be tried at once`() = runBlocking {
        coroutineScope {
            val alice = TestClient.register(relayUrl, "alice", this)
            alice.awaitUpToDate()

            // Both attempts share the relay client and the stores, as an onboarding screen keeps them.
            val client = RelayClient(relayUrl)
            val storage = KeyTrackingStorage()
            val contacts = InMemoryContactStore()
            val saved = mutableListOf<LocalAccount>()
            val error = assertThrows<RelayException> {
                runBlocking { Messenger.register(Username("alice"), client, storage, contacts, TestClient.testConfig, accountStore = { saved += it }) }
            }
            assertEquals(RelayErrorCode.USERNAME_TAKEN, error.code)
            assertEquals(emptyList<LocalAccount>(), saved, "no account was created")
            assertEquals(Triple(0, 0, 0), storage.keyCounts(), "the refused attempt's keys are deleted")

            val bob = TestClient(
                Messenger.register(Username("bob_2"), client, storage, contacts, TestClient.testConfig, accountStore = { saved += it }),
                this,
            )
            assertEquals(listOf(bob.accountId), saved.map { it.accountId }.distinct(), "only the second attempt's account is saved")
            val batch = TestClient.testConfig.preKeyPolicy.batchSize
            assertEquals(Triple(1, batch, 1 + batch), storage.keyCounts(), "only the second attempt's keys")
            assertEquals(batch to batch, relay.oneTimeKeyCounts(bob.accountId))

            bob.awaitUpToDate()
            assertEquals(bob.accountId, alice.messenger.findByUsername("bob_2")?.accountId)
            alice.messenger.sendText(bob.accountId, "merhaba")
            assertEquals("merhaba", bob.nextData().content.data.text.body)
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `unknown and own usernames are not found`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        assertNull(alice.findByUsername("nobody"))
        assertNull(alice.findByUsername("not a username!"))
        assertNull(alice.findByUsername("ALICE"))
    }

    @Test
    fun `blocking rotates the access key and silences the blocked sender`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val carol = TestClient.register(relayUrl, "carol", this)
            carol.awaitUpToDate()
            carol.messenger.findByUsername("bob")
            carol.messenger.sendText(bob.accountId, "hello bob")
            bob.nextData()
            bob.messenger.acceptMessageRequest(carol.accountId)
            carol.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            bob.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            val oldKey = bob.messenger.account.accessKey

            assertEquals(emptyList<Any>(), bob.messenger.block(alice.accountId))
            assertNotEquals(oldKey, bob.messenger.account.accessKey)
            assertEquals(ContactState.BLOCKED, bob.messenger.contact(alice.accountId)?.state)

            // Carol, still accepted, receives the new key and keeps using access-key delivery.
            carol.next { it.content.hasControl() && it.content.control.hasAccessKey() }
            assertEquals(bob.messenger.account.accessKey, carol.messenger.contact(bob.accountId)?.theirAccessKey)
            carol.messenger.sendText(bob.accountId, "still here")
            assertEquals("access-key", relay.lastDeliveryTo(bob.accountId))
            assertEquals("still here", bob.nextData().content.data.text.body)

            // Alice's old key is refused; she falls back to proof-of-work and Bob drops it unseen.
            alice.messenger.sendText(bob.accountId, "are you there?")
            assertEquals("pow", relay.lastDeliveryTo(bob.accountId))
            assertNull(alice.messenger.contact(bob.accountId)?.theirAccessKey)
            assertTrue(bob.receivesNothingWithin(500.milliseconds))
            assertEquals(0, relay.mailboxSize(bob.accountId), "dropped envelopes are still acknowledged")

            listOf(alice, bob, carol).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `unblocking returns to the state before the block and hands over the current access key`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            alice.next { it.content.hasControl() && it.content.control.hasProfile() }
            bob.messenger.block(alice.accountId)
            bob.messenger.block(alice.accountId) // twice: still unblocked to ACCEPTED, not to BLOCKED
            assertEquals(ContactState.ACCEPTED, bob.messenger.contact(alice.accountId)?.stateBeforeBlock)
            val currentKey = bob.messenger.account.accessKey

            assertTrue(bob.messenger.unblock(alice.accountId))
            val contact = bob.messenger.contact(alice.accountId)
            assertEquals(ContactState.ACCEPTED, contact?.state)
            assertNull(contact?.stateBeforeBlock)
            // Only our current key, then our profile: no ACCEPT or anything else that would tell her.
            val key = alice.next { it.sender == bob.accountId }.content.control.accessKey.accessKey
            assertEquals(currentKey.toBytes().toByteString(), key)
            assertTrue(alice.next { it.sender == bob.accountId }.content.control.hasProfile())
            assertTrue(alice.receivesNothingWithin(500.milliseconds))
            assertEquals(currentKey, alice.messenger.contact(bob.accountId)?.theirAccessKey)

            alice.messenger.sendText(bob.accountId, "barışalım mı?")
            assertEquals("access-key", relay.lastDeliveryTo(bob.accountId))
            assertEquals("barışalım mı?", bob.nextData().content.data.text.body)
            assertTrue(bob.messenger.unblock(alice.accountId), "unblocking an unblocked contact changes nothing")
            assertEquals(ContactState.ACCEPTED, bob.messenger.contact(alice.accountId)?.state)
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a stranger or a contact restored blocked is unblocked as an incoming request, and backups follow`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val carol = TestClient.register(relayUrl, "carol", this)
            val blocked = { backup: ByteArray ->
                AccountBackup.parseFrom(BackupFile.open(backup, "yedek parolam 7")).contactsList
                    .associate { AccountId.fromBytes(it.accountId.toByteArray()) to it.blocked }
            }

            bob.messenger.block(carol.accountId)
            assertTrue(bob.messenger.unblock(carol.accountId))
            assertEquals(ContactState.INCOMING_REQUEST, bob.messenger.contact(carol.accountId)?.state)
            assertTrue(carol.receivesNothingWithin(300.milliseconds), "nothing tells a stranger")

            bob.messenger.block(alice.accountId)
            assertEquals(mapOf(alice.accountId to true), blocked(bob.messenger.exportBackup("yedek parolam 7", iterations = 1000)))
            bob.messenger.unblock(alice.accountId)
            assertEquals(mapOf(alice.accountId to false), blocked(bob.messenger.exportBackup("yedek parolam 7", iterations = 1000)))

            // A backup holds only the flag: unblocked after a restore, the contact writes first again.
            bob.messenger.block(alice.accountId)
            val backup = bob.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            bob.job.cancelAndJoin()
            val restored = Messenger.restore(backup, "yedek parolam 7", RelayClient(relayUrl), InMemoryProtocolStorage(), InMemoryContactStore(), TestClient.testConfig)
            assertEquals(ContactState.BLOCKED, restored.contact(alice.accountId)?.state)
            assertTrue(restored.unblock(alice.accountId))
            assertEquals(ContactState.INCOMING_REQUEST, restored.contact(alice.accountId)?.state)
            assertEquals(emptyMap<Any, Boolean>(), blocked(restored.exportBackup("yedek parolam 7", iterations = 1000)), "a request is not in a backup")
            listOf(alice, carol).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `messages stored while offline are fetched over HTTP`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.findByUsername("bob")
        repeat(3) { alice.sendText(bob.accountId, "offline $it") }
        assertEquals(3, relay.mailboxSize(bob.accountId))

        val received = mutableListOf<String>()
        assertEquals(3, bob.fetchPending { received += it.content.data.text.body })
        assertEquals(listOf("offline 0", "offline 1", "offline 2"), received)
        assertEquals(0, relay.mailboxSize(bob.accountId))
    }

    @Test
    fun `a handler failure leaves only that envelope at the relay and the ones behind it are handled (H-1)`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.findByUsername("bob")
        alice.sendText(bob.accountId, "refused")
        alice.sendText(bob.accountId, "behind it")
        val received = mutableListOf<String>()
        val handled = bob.fetchPending {
            val body = it.content.data.text.body
            check(body != "refused") { "the app cannot take this one" }
            received += body
        }
        assertEquals(1, handled)
        assertEquals(listOf("behind it"), received)
        assertEquals(1, relay.mailboxSize(bob.accountId), "only the refused envelope stays")

        // Without staged writes (the app has them) its message key is spent: the redelivery is a
        // duplicate, dropped and acknowledged, so it never blocks the mailbox either.
        assertEquals(1, bob.fetchPending { received += it.content.data.text.body })
        assertEquals(0, relay.mailboxSize(bob.accountId))
        assertEquals(listOf("behind it"), received)

        // A store that is unavailable (an IOException) is not the message's fault: it stays at the relay.
        alice.sendText(bob.accountId, "later")
        assertThrows<IOException> { runBlocking { bob.fetchPending { throw IOException("vault closed") } } }
        assertEquals(1, relay.mailboxSize(bob.accountId))
    }

    @Test
    fun `a malformed message from a stranger is dropped and acknowledged, the next one arrives (H-1)`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.findByUsername("bob")
        alice.send(
            bob.accountId,
            content {
                data = com.syconx.us2us.core.proto.dataMessage {
                    messageId = byteArrayOf(1, 2, 3).toByteString()
                    sentAt = 1
                    text = com.syconx.us2us.core.proto.text { body = "wedge" }
                }
            },
        )
        alice.sendText(bob.accountId, "selam")
        val received = mutableListOf<String>()
        assertEquals(2, bob.fetchPending { received += it.content.data.text.body }, "both acknowledged")
        assertEquals(listOf("selam"), received)
        assertEquals(0, relay.mailboxSize(bob.accountId))
    }

    @Test
    fun `every request message carries its verified sender username, also one already known (M-9)`() = runBlocking {
        coroutineScope {
            val alice = TestClient.register(relayUrl, "alice", this)
            val bob = TestClient.register(relayUrl, "bob", this)
            alice.awaitUpToDate()
            bob.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bob.accountId, "bir")
            alice.messenger.sendText(bob.accountId, "iki")
            assertEquals("alice", bob.nextData().verifiedSenderUsername?.value)
            assertEquals("alice", bob.nextData().verifiedSenderUsername?.value)
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `backup and restore keep the identity, so contacts see no safety-number change`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val backup = alice.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            alice.job.cancelAndJoin()

            val restored = Messenger.restore(backup, "yedek parolam 7", RelayClient(relayUrl), InMemoryProtocolStorage(), InMemoryContactStore(), TestClient.testConfig)
            assertEquals(alice.accountId, restored.accountId)
            assertEquals(ContactState.ACCEPTED, restored.contact(bob.accountId)?.state)
            // The keys are replaced before restore returns; the profile announcement is next (§5.5).
            assertEquals(setOf(PendingSetup.ANNOUNCE_PROFILE), restored.account.pendingSetup)
            assertEquals(100 to 100, relay.oneTimeKeyCounts(alice.accountId))
            // The old phone's token no longer works.
            val oldPhone = assertThrows<RelayException> { runBlocking { alice.messenger.maintainPreKeys() } }
            assertEquals(RelayErrorCode.UNAUTHORIZED, oldPhone.code)

            // Without writing anything, the new phone sends its complete profile to its contacts.
            val newPhone = TestClient(restored, this)
            newPhone.awaitUpToDate()
            val announced = bob.next { it.content.hasControl() && it.content.control.hasProfile() }
            assertEquals("alice", announced.content.control.profile.username)
            assertTrue(announced.content.control.profile.presenceSharing)
            assertFalse(announced.identityChanged)
            withTimeout(30.seconds) { while (restored.account.pendingSetup.isNotEmpty()) delay(20) }

            newPhone.messenger.sendText(bob.accountId, "new phone, same me")
            val message = bob.nextData()
            assertEquals("new phone, same me", message.content.data.text.body)
            assertFalse(message.identityChanged)
            assertEquals(ContactState.ACCEPTED, message.contactState)

            bob.messenger.sendText(alice.accountId, "welcome back")
            assertEquals("welcome back", newPhone.nextData().content.data.text.body)
            listOf(newPhone, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a username change is announced to contacts`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            assertEquals(emptyList<Any>(), alice.messenger.changeUsername(Username("alice_new")))
            val update = bob.next { it.content.hasControl() && it.content.control.hasProfile() && it.content.control.profile.username == "alice_new" }
            assertTrue(update.content.control.profile.presenceSharing, "every ProfileUpdate carries the whole profile")
            assertEquals("alice_new", bob.messenger.contact(alice.accountId)?.username)
            assertEquals(alice.accountId, bob.messenger.findByUsername("alice_new")?.accountId)
            assertNull(bob.messenger.findByUsername("alice"))
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a registration whose access-key upload fails keeps the account and finishes later`() = runBlocking {
        val saved = mutableListOf<LocalAccount>()
        relay.failNext("PUT", "/v1/accounts/me/access-key")
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = TestClient.testConfig, accountStore = { saved += it })
        assertEquals(setOf(PendingSetup.PUBLISH_ACCESS_KEY), bob.account.pendingSetup)
        assertEquals(bob.account, saved.last(), "the credentials are saved before anything else can fail")

        bob.performMaintenance()
        assertEquals(emptySet<PendingSetup>(), bob.account.pendingSetup)
        assertEquals(emptySet<PendingSetup>(), saved.last().pendingSetup)
    }

    @Test
    fun `a restore whose key upload fails is saved and replaces the keys later`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val backup = alice.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            alice.job.cancelAndJoin()

            val saved = mutableListOf<LocalAccount>()
            relay.failNext("PUT", "/v1/keys")
            val restored = Messenger.restore(
                backup, "yedek parolam 7", RelayClient(relayUrl), InMemoryProtocolStorage(), InMemoryContactStore(), TestClient.testConfig, accountStore = { saved += it },
            )
            // The avatar record waits for the keys (§5.5 step 5).
            assertEquals(setOf(PendingSetup.REPLACE_PRE_KEYS, PendingSetup.SYNC_AVATAR, PendingSetup.ANNOUNCE_PROFILE), restored.account.pendingSetup)
            // The new token is saved as pending before the relay hears of it, then as the account's (§5.5 step 3).
            assertEquals(2, saved.size)
            assertEquals(alice.messenger.account.authToken, saved.first().authToken)
            val chosen = checkNotNull(saved.first().pendingAuthToken)
            assertEquals(chosen, saved.last().authToken, "the rotated token is saved at once")
            assertNull(saved.last().pendingAuthToken)

            // The receive loop finishes the setup after the backlog; the relay then serves only new keys.
            val newPhone = TestClient(restored, this)
            newPhone.awaitUpToDate()
            withTimeout(30.seconds) { while (restored.account.pendingSetup.isNotEmpty()) delay(20) }
            assertEquals(100 to 100, relay.oneTimeKeyCounts(alice.accountId))

            bob.messenger.forgetContact(alice.accountId)
            bob.messenger.findByUsername("alice")
            bob.messenger.sendText(alice.accountId, "fresh session")
            assertEquals("fresh session", newPhone.nextData().content.data.text.body)
            listOf(newPhone, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a malformed backup is refused before the old phone loses its token`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        val good = AccountBackup.parseFrom(BackupFile.open(alice.exportBackup("yedek parolam 7", iterations = 1000), "yedek parolam 7"))
        val broken = BackupFile.create("yedek parolam 7", good.toBuilder().setUsername("Not Normalized").build().toByteArray(), 1000)
        assertThrows<InvalidBackupException> { runBlocking { Messenger.restore(broken, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig) } }
        assertThrows<WrongBackupPasswordException> { runBlocking { Messenger.restore(broken, "yanlis parola 7", RelayClient(relayUrl), config = TestClient.testConfig) } }
        assertFalse(alice.maintainPreKeys(), "the token still works")
    }

    @Test
    fun `contacts exchange profiles and see each other's presence`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            // Right after the access keys, both sides share their profile, which carries presence_sharing.
            withTimeout(30.seconds) {
                while (alice.messenger.contact(bob.accountId)?.presenceSharing != true || bob.messenger.contact(alice.accountId)?.presenceSharing != true) delay(20)
            }
            assertEquals(PresenceStatus.None, alice.messenger.presence(bob.accountId))

            // Bob reopens the app: one ephemeral batch announces him, and Alice answers once.
            bob.job.cancelAndJoin()
            val batchesBefore = relay.ephemeralBatches
            val bobAgain = TestClient(bob.messenger, this)
            bobAgain.awaitUpToDate()
            val online = alice.next { it.content.hasPresence() }.content.presence
            assertEquals(PresenceMessage.State.ONLINE, online.state)
            assertFalse(online.reply)
            assertEquals(batchesBefore + 1, relay.ephemeralBatches)
            assertTrue(bobAgain.next { it.content.hasPresence() }.content.presence.reply)
            assertEquals(PresenceStatus.Active, alice.messenger.presence(bob.accountId))
            assertEquals(PresenceStatus.Active, bob.messenger.presence(alice.accountId))
            assertEquals(0, relay.mailboxSize(alice.accountId) + relay.mailboxSize(bob.accountId), "presence is never stored")

            bob.messenger.announceOffline()
            assertEquals(PresenceMessage.State.OFFLINE, alice.next { it.content.hasPresence() }.content.presence.state)
            assertEquals(PresenceStatus.ActiveJustNow, alice.messenger.presence(bob.accountId))

            // Reciprocal: turning it off tells the contact, who forgets the status.
            assertEquals(emptyList<Any>(), bob.messenger.setPresenceSharing(false))
            alice.next { it.content.hasControl() && it.content.control.hasProfile() && !it.content.control.profile.presenceSharing }
            assertEquals(PresenceStatus.None, alice.messenger.presence(bob.accountId))
            assertEquals(false, alice.messenger.contact(bob.accountId)?.presenceSharing)
            listOf(alice, bobAgain).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `ephemeral sends need the contact's access key`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.findByUsername("bob")
        assertThrows<AccessKeyRequiredException> {
            runBlocking { alice.send(bob.accountId, PresencePolicy.online(), ephemeral = true) }
        }
        assertThrows<IllegalArgumentException> { runBlocking { alice.sendText(alice.accountId, "me") } }
        assertEquals(0, relay.mailboxSize(bob.accountId))
    }

    @Test
    fun `attachment downloads stop at the size the sender declared`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val upload = alice.messenger.uploadAttachment(
                OutgoingAttachment(Attachment.Kind.FILE, "application/octet-stream", source = { ByteArrayInputStream(ByteArray(100_000)) }),
            )
            val lying = upload.toBuilder().setSize(10).build()
            assertThrows<IOException> { runBlocking { bob.messenger.downloadAttachment(lying, ByteArrayOutputStream()) } }
            val malformed = upload.toBuilder().setKey(ByteArray(10).toByteString()).build()
            assertThrows<AttachmentIntegrityException> { runBlocking { bob.messenger.downloadAttachment(malformed, ByteArrayOutputStream()) } }
            // L-20: a declared size no relay can store is refused before anything is fetched.
            val huge = upload.toBuilder().setSize(2L shl 30).build()
            assertThrows<AttachmentIntegrityException> { runBlocking { bob.messenger.downloadAttachment(huge, ByteArrayOutputStream()) } }

            val honest = ByteArrayOutputStream()
            val fractions = CopyOnWriteArrayList<Double>()
            bob.messenger.downloadAttachment(upload, honest) { fractions += it }
            assertEquals(100_000, honest.size())
            // The progress of the blob, after every 64 KiB and ending with all of it.
            assertEquals(1.0, fractions.last())
            assertEquals(fractions.sorted(), fractions.toList())
            assertEquals(AttachmentCipher.blobSize(100_000) / (64 * 1024) + 1, fractions.size.toLong())
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `delivery receipts for a large backlog are split to fit the envelope limit`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            bob.job.cancelAndJoin()
            val count = Messenger.MAX_RECEIPT_IDS + 1
            repeat(count) { alice.messenger.sendText(bob.accountId, "m$it") }

            val bobAgain = TestClient(bob.messenger, this)
            val receipts = mutableListOf<Int>()
            while (receipts.sum() < count) {
                receipts += alice.next { it.content.hasReceipt() && it.content.receipt.type == ReceiptMessage.Type.DELIVERY }.content.receipt.messageIdsCount
            }
            assertEquals(listOf(Messenger.MAX_RECEIPT_IDS, 1), receipts)
            assertThrows<IllegalArgumentException> {
                runBlocking { bob.messenger.sendReceipt(alice.accountId, ReceiptMessage.Type.READ, List(count) { ByteArray(16) }) }
            }
            listOf(alice, bobAgain).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `an idle receive loop keeps the socket alive with JSON pings`() = runBlocking {
        coroutineScope {
            val config = TestClient.testConfig.copy(keepAliveInterval = 50.milliseconds)
            val bob = TestClient(Messenger.register(Username("bob"), RelayClient(relayUrl), config = config), this)
            bob.awaitUpToDate()
            withTimeout(30.seconds) { while (relay.pingsFrom(bob.accountId) < 3) delay(20) }
            assertEquals(ConnectionState.UP_TO_DATE, bob.messenger.connection.value)
            bob.job.cancelAndJoin()
        }
    }

    @Test
    fun `pre-keys are refilled when the relay runs low`() = runBlocking {
        val config = TestClient.testConfig.copy(preKeyPolicy = PreKeyPolicy(batchSize = 5, refillThreshold = 3))
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), config = config)
        assertEquals(5 to 5, relay.oneTimeKeyCounts(bob.accountId))
        assertFalse(bob.maintainPreKeys())
        repeat(3) {
            val sender = Messenger.register(Username("sender_$it"), RelayClient(relayUrl), config = TestClient.testConfig)
            sender.findByUsername("bob")
            sender.sendText(bob.accountId, "consume a bundle")
        }
        assertEquals(2 to 2, relay.oneTimeKeyCounts(bob.accountId))
        assertTrue(bob.maintainPreKeys())
        assertEquals(5 to 5, relay.oneTimeKeyCounts(bob.accountId))
    }
    @Test
    fun `a backup keeps unaccepted safety-number changes and presence sharing`() = runBlocking {
        coroutineScope {
            val aliceStorage = InMemoryProtocolStorage()
            val alice = TestClient(
                Messenger.register(Username("alice"), RelayClient(relayUrl), aliceStorage, InMemoryContactStore(), TestClient.testConfig),
                this,
            )
            val bob = TestClient.register(relayUrl, "bob", this)
            alice.awaitUpToDate()
            bob.awaitUpToDate()
            alice.messenger.findByUsername("bob")
            alice.messenger.sendText(bob.accountId, "hi")
            bob.nextData()
            bob.messenger.acceptMessageRequest(alice.accountId)
            withTimeout(30.seconds) { while (alice.messenger.contact(bob.accountId)?.presenceSharing != true) delay(20) }

            // Bob's key changes on Alice's phone and she has not accepted it yet.
            val account = alice.messenger.account
            ProtocolStore(account.identityKeyPair, account.registrationId, aliceStorage)
                .saveIdentity(bob.accountId.protocolAddress(), Keys.generateIdentityKeyPair().publicKey)
            val file = alice.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            val saved = AccountBackup.parseFrom(BackupFile.open(file, "yedek parolam 7")).contactsList.single()
            assertTrue(saved.identityUnaccepted)
            assertTrue(saved.presenceSharing)
            alice.job.cancelAndJoin()

            val restored = Messenger.restore(file, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig)
            assertEquals(true, restored.contact(bob.accountId)?.presenceSharing)
            assertThrows<IdentityChangedException> { runBlocking { restored.sendText(bob.accountId, "still blocked") } }
            assertTrue(restored.safetyNumber(bob.accountId) != null, "the new safety number can be shown and accepted")
            bob.job.cancelAndJoin()
        }
    }

    @Test
    fun `a malformed contact makes the whole backup invalid before the token is rotated`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val good = AccountBackup.parseFrom(BackupFile.open(alice.messenger.exportBackup("yedek parolam 7", iterations = 1000), "yedek parolam 7"))
            val contact = good.contactsList.single()
            val broken: List<(BackupContact.Builder) -> Unit> = listOf(
                { it.setTheirAccessKey(ByteString.copyFrom(ByteArray(15))) },
                { it.setUsername("Bob") },
                { it.setIdentityKey(contact.identityKey.substring(0, 32)) },
                { it.setIdentityKey(ByteString.EMPTY).setIdentityUnaccepted(true) },
                { it.setAccountId(ByteString.copyFrom(ByteArray(15))) },
                { it.setDisplayName("x".repeat(65)) },
            )
            for ((index, breakIt) in broken.withIndex()) {
                val backup = good.toBuilder().setContacts(0, contact.toBuilder().also(breakIt).build()).build()
                val file = BackupFile.create("yedek parolam 7", backup.toByteArray(), 1000)
                assertThrows<InvalidBackupException>("case $index") {
                    runBlocking { Messenger.restore(file, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig) }
                }
            }
            val duplicate = BackupFile.create("yedek parolam 7", good.toBuilder().addContacts(contact).build().toByteArray(), 1000)
            assertThrows<InvalidBackupException> { runBlocking { Messenger.restore(duplicate, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig) } }
            assertFalse(alice.messenger.maintainPreKeys(), "the old phone's token still works")
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a key upload that keeps failing after a restore is retried while connected`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val backup = alice.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            alice.job.cancelAndJoin()
            repeat(3) { relay.failNext("PUT", "/v1/keys") }
            val restored = Messenger.restore(backup, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig)
            assertEquals(setOf(PendingSetup.REPLACE_PRE_KEYS, PendingSetup.SYNC_AVATAR, PendingSetup.ANNOUNCE_PROFILE), restored.account.pendingSetup)

            val newPhone = TestClient(restored, this)
            newPhone.awaitUpToDate()
            withTimeout(30.seconds) { while (restored.account.pendingSetup.isNotEmpty()) delay(20) }
            assertEquals(100 to 100, relay.oneTimeKeyCounts(alice.accountId))
            assertEquals("alice", bob.next { it.content.hasControl() && it.content.control.hasProfile() }.content.control.profile.username)
            listOf(newPhone, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a username from a profile update is a claim until the directory confirms it`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)
            // Alice's request carried her username, which was checked before decryption (§6.3).
            assertEquals(UsernameStatus.VERIFIED, bob.messenger.contact(alice.accountId)?.usernameStatus)

            alice.messenger.changeUsername(Username("alice_new"))
            bob.next { it.content.hasControl() && it.content.control.hasProfile() && it.content.control.profile.username == "alice_new" }
            assertEquals(UsernameStatus.UNCHECKED, bob.messenger.contact(alice.accountId)?.usernameStatus)
            val lookups = relay.directoryLookups
            assertEquals(UsernameStatus.VERIFIED, bob.messenger.verifyUsername(alice.accountId))
            assertEquals(UsernameStatus.VERIFIED, bob.messenger.verifyUsername(alice.accountId))
            assertEquals(lookups + 1, relay.directoryLookups, "the result is cached on the contact")

            // Claiming someone else's name, or one nobody has, is never shown as verified.
            for ((claim, owner) in listOf("carol" to carol.accountId, "nobody_here" to null)) {
                alice.messenger.send(
                    bob.accountId,
                    content {
                        control = controlMessage {
                            profile = profileUpdate {
                                username = claim
                                displayName = "Alice"
                                presenceSharing = true
                                updatedAt = 1
                            }
                        }
                    },
                )
                bob.next { it.content.hasControl() && it.content.control.hasProfile() && it.content.control.profile.username == claim }
                assertEquals(claim, bob.messenger.contact(alice.accountId)?.username)
                assertEquals(UsernameStatus.MISMATCH, bob.messenger.verifyUsername(alice.accountId), "claim $claim (owner $owner)")
            }

            // Every ProfileUpdate replaces the whole profile: an empty display name means none.
            alice.messenger.setDisplayName("")
            bob.next { it.content.hasControl() && it.content.control.hasProfile() && it.content.control.profile.username == "alice_new" }
            assertNull(bob.messenger.contact(alice.accountId)?.displayName)
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    /**
     * Alice writes to Bob, so Bob's directory cache says "alice" is hers. Then she deletes her
     * account, which releases the name at once, and someone else registers it.
     */
    private suspend fun moveAliceAfterRequest(scope: CoroutineScope): Triple<TestClient, TestClient, TestClient> {
        val alice = TestClient.register(relayUrl, "alice", scope)
        val bob = TestClient.register(relayUrl, "bob", scope)
        alice.awaitUpToDate()
        bob.awaitUpToDate()
        alice.messenger.findByUsername("bob")
        alice.messenger.sendText(bob.accountId, "hi")
        assertEquals(alice.accountId, bob.nextData().sender)
        assertEquals(UsernameStatus.VERIFIED, bob.messenger.contact(alice.accountId)?.usernameStatus)

        // Her phone keeps the app installed but can no longer connect.
        alice.job.cancelAndJoin()
        alice.messenger.deleteAccount()
        val newAlice = TestClient.register(relayUrl, "alice", scope)
        newAlice.awaitUpToDate()
        newAlice.messenger.findByUsername("bob")
        return Triple(alice, bob, newAlice)
    }

    @Test
    fun `a username that moved while the cache is warm reaches its new owner`() = runBlocking {
        coroutineScope {
            val (oldAlice, bob, newAlice) = moveAliceAfterRequest(this)
            val lookups = relay.directoryLookups
            newAlice.messenger.sendText(bob.accountId, "the name is mine now")
            val request = bob.nextData()
            assertEquals(newAlice.accountId, request.sender)
            assertEquals("alice", request.senderUsername)
            assertEquals(UsernameStatus.VERIFIED, bob.messenger.contact(newAlice.accountId)?.usernameStatus)
            assertEquals(lookups + 1, relay.directoryLookups, "one refresh of the cached owner")

            // The old account's client can still send sealed envelopes; its claim is refused now,
            // without asking the relay again so soon.
            oldAlice.messenger.sendText(bob.accountId, "still alice")
            newAlice.messenger.sendText(bob.accountId, "second")
            val next = bob.nextData()
            assertEquals(newAlice.accountId, next.sender)
            assertEquals("second", next.content.data.text.body)
            assertEquals(lookups + 1, relay.directoryLookups)
            listOf(bob, newAlice).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a search replaces a stale cached owner`() = runBlocking {
        coroutineScope {
            val (_, bob, newAlice) = moveAliceAfterRequest(this)
            assertEquals(newAlice.accountId, bob.messenger.findByUsername("alice")?.accountId)
            val lookups = relay.directoryLookups
            newAlice.messenger.sendText(bob.accountId, "hello again")
            assertEquals(newAlice.accountId, bob.nextData().sender)
            assertEquals(UsernameStatus.VERIFIED, bob.messenger.contact(newAlice.accountId)?.usernameStatus)
            assertEquals(lookups, relay.directoryLookups, "the search already gave the new owner")
            listOf(bob, newAlice).forEach { it.job.cancelAndJoin() }
        }
    }
}

/** Counts the pre-keys it holds: signed, one-time EC, and Kyber (last-resort and one-time). */
private class KeyTrackingStorage(private val inner: InMemoryProtocolStorage = InMemoryProtocolStorage()) : ProtocolStorage by inner {
    private val preKeyIds = mutableSetOf<Int>()

    override fun storePreKey(id: Int, record: ByteArray) {
        inner.storePreKey(id, record)
        synchronized(preKeyIds) { preKeyIds += id }
    }

    override fun removePreKey(id: Int) {
        inner.removePreKey(id)
        synchronized(preKeyIds) { preKeyIds -= id }
    }

    fun keyCounts() = Triple(inner.loadSignedPreKeys().size, synchronized(preKeyIds) { preKeyIds.size }, inner.loadKyberPreKeys().size)
}
