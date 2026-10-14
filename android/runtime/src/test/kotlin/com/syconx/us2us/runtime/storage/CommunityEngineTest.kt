package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.session.SealedEnvelope
import com.syconx.us2us.runtime.community.CommunityEngine
import org.signal.libsignal.protocol.IdentityKeyPair
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunityEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: LocalStorage
    private val server = MockWebServer()
    @Before fun open() { storage = TestEnvironment(temporary.newFolder()).open(); server.start() }
    @After fun close() { storage.close(); server.close() }

    @Test fun malformedFirstEnvelopeDoesNotBlockValidSecondOrCreateDirectContact() = runBlocking {
        runInboxScenario(invalidRoster = false)
    }
    @Test fun malformedFirstRosterDoesNotBlockValidSecondOrAdvanceItsRatchet() = runBlocking {
        runInboxScenario(invalidRoster = true)
    }
    private suspend fun runInboxScenario(invalidRoster: Boolean) {
        val account = storage.saveAccount()
        val receiver = Party.on(storage, account)
        val sender = Party.inMemory()
        val ownKey = Base64Url.encode(account.identityKeyPair.publicKey.serialize())
        val state = CommunityState(newAccountId(), 1, "public", account.accountId, ownKey, "Poison handling",
            listOf(CommunityMember(account.accountId, ownKey), CommunityMember(sender.accountId, Base64Url.encode(sender.store.identityKeyPair.publicKey.serialize()))).sortedBy { it.accountId.toString() })
        val signed = state.sign(account.identityKeyPair)
        val signedJson = """{"state":"${signed.state}","signature":"${signed.signature}"}"""
        sender.sessions.startSession(receiver.accountId, receiver.bundle())
        val body = communityMessage { communityId = ByteString.copyFrom(state.id.toBytes()); revision = 1; messageId = randomId(); sentAt = 1234; text = "valid after poison" }
        val encrypted = sender.sessions.encrypt(receiver.accountId, content { community = body })
        val envelope = SealedEnvelope.seal(sealedContent {
            senderAccountId = ByteString.copyFrom(sender.accountId.toBytes()); senderDeviceId = 1
            type = encrypted.type; ciphertext = ByteString.copyFrom(encrypted.serialize())
        }, account.identityKeyPair.publicKey)
        val repository = CommunityRepository(storage)
        repository.pin(state.id, ownKey, state.name)
        repository.save(signed)
        val engine = CommunityEngine(Messenger(account, receiver.store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        server.enqueue(json("""{"communities":[$signedJson]}"""))
        val firstBytes = if (invalidRoster) envelope else byteArrayOf(0)
        server.enqueue(json("""{"envelopes":[{"id":"poison","communityId":"${state.id}","revision":1,"serverTimestamp":1234,"envelope":"${Base64Url.encode(firstBytes)}"},{"id":"valid","communityId":"${state.id}","revision":1,"serverTimestamp":1234,"envelope":"${Base64Url.encode(envelope)}"}],"more":false}"""))
        server.enqueue(json(if (invalidRoster) """{"state":"AA","signature":"${signed.signature}"}""" else signedJson))
        server.enqueue(json(signedJson))
        server.enqueue(MockResponse.Builder().code(204).build())
        engine.sync()
        assertEquals("a roster that does not verify is reported", invalidRoster, engine.securityWarning)
        assertEquals("valid after poison", repository.messages(state.id).single().text)
        assertTrue(repository.wasProcessed("poison")); assertTrue(repository.wasProcessed("valid"))
        assertTrue(storage.contactStore.all().isEmpty())
        val requests = List(5) { server.takeRequest(5, TimeUnit.SECONDS)!! }
        val ack = requests.last()
        assertEquals("/v1/communities/inbox/ack", ack.url.encodedPath)
        assertTrue(ack.body!!.utf8().contains("poison")); assertTrue(ack.body!!.utf8().contains("valid"))
    }

    @Test fun oldRelayOnlyDisablesCommunitiesAndNetworkFailureIsRetryable() = runBlocking {
        val account = storage.saveAccount()
        val party = Party.on(storage, account)
        val engine = CommunityEngine(Messenger(account, party.store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"old relay"}}""").build())
        engine.sync()
        assertFalse(engine.supported)
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"code":"internal","message":"temporarily unavailable"}}""").build())
        try { engine.sync(); fail("network failure swallowed") } catch (_: RelayException) { }
        assertTrue(storage.contactStore.all().isEmpty())
    }
    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
    private fun status(code: Int, wire: String = "bad_request") = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body("""{"error":{"code":"$wire","message":"test"}}""").build()
    private fun key(pair: IdentityKeyPair) = Base64Url.encode(pair.publicKey.serialize())

    /** An engine for this storage's own account, owning a community of [members] other accounts. */
    private inner class Owned(members: Int) {
        val account = storage.saveAccount()
        val party = Party.on(storage, account)
        val others = List(members) { CommunityMember(newAccountId(), key(IdentityKeyPair.generate())) }
        val state = CommunityState(newAccountId(), 1, "public", account.accountId, key(account.identityKeyPair), "Topluluk",
            (others + CommunityMember(account.accountId, key(account.identityKeyPair))).sortedBy { it.accountId.toString() })
        val signed = state.sign(account.identityKeyPair)
        val signedJson = """{"state":"${signed.state}","signature":"${signed.signature}"}"""
        val repository = CommunityRepository(storage)
        val engine = CommunityEngine(Messenger(account, party.store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        init {
            repository.pin(state.id, key(account.identityKeyPair), state.name)
            repository.save(signed)
        }
        fun message(text: String): AccountId = newAccountId().also { repository.add(StoredCommunityMessage(state.id, account.accountId, it, 1234, text, "pending")) }
        fun queue(message: AccountId, recipient: CommunityMember, envelope: ByteArray = byteArrayOf(1, 2, 3)) =
            repository.enqueue(CommunityDelivery(state.id, message, recipient.accountId, state.revision, envelope))
        fun status(message: AccountId) = repository.messages(state.id).single { it.messageId == message }.status
    }

    private fun route(handler: (RecordedRequest) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request) ?: status(404, "not_found")
        }
    }
    private fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    @Test fun outboxErrorsAreSortedByCauseAndNeverHoldUpTheInbox() = runBlocking {
        val owned = Owned(members = 5)
        val (a, b, c, d, e) = owned.others
        val first = owned.message("first")
        val conflicted = owned.message("conflicted")
        val toLeaver = owned.message("to someone who left")
        val second = owned.message("second")
        storage.write {
            owned.queue(first, a); owned.queue(first, b); owned.queue(first, c)
            owned.queue(conflicted, c); owned.queue(conflicted, b)
            owned.queue(toLeaver, e)
            owned.queue(second, a); owned.queue(second, d)
        }
        val sends = ArrayList<Pair<String, String>>()
        route { request ->
            val path = request.url.encodedPath
            when {
                path == "/v1/communities/mine" -> json("""{"communities":[${owned.signedJson}]}""")
                path.contains("/messages/") -> {
                    val (recipient, message) = path.split("/").takeLast(2)
                    sends += recipient to message
                    when {
                        recipient == a.accountId.toString() -> MockResponse.Builder().code(429).addHeader("Retry-After", "600")
                            .body("""{"error":{"code":"community_inbox_full","message":"full"}}""").build()
                        recipient == e.accountId.toString() -> status(404, "not_found")
                        recipient == b.accountId.toString() -> status(403, "access_denied")
                        message == conflicted.toString() -> status(409, "stale_version")
                        recipient == d.accountId.toString() -> status(503, "internal")
                        else -> MockResponse.Builder().code(202).body("""{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1234}""").build()
                    }
                }
                path == "/v1/communities/inbox" -> json("""{"envelopes":[{"id":"gone","communityId":"${owned.state.id}","revision":1,"serverTimestamp":1234,"envelope":"AQID"}],"more":false}""")
                path == "/v1/communities/${owned.state.id}" -> status(404, "not_found")
                path == "/v1/communities/inbox/ack" -> MockResponse.Builder().code(204).build()
                else -> null
            }
        }
        owned.engine.sync()

        val pending = owned.repository.pending().map { it.messageId to it.recipient }
        assertEquals("a full inbox only defers its recipient", listOf(first to a.accountId, second to a.accountId, second to d.accountId), pending)
        assertEquals("one refused recipient fails the message, the others still get it", "failed", owned.status(first))
        assertEquals("a changed roster stops the whole message", "stale", owned.status(conflicted))
        assertEquals("pending", owned.status(second))
        assertEquals("a recipient who left is skipped, not a failure", "sent", owned.status(toLeaver))
        assertFalse("the conflicted message is not sent on", sends.contains(b.accountId.toString() to conflicted.toString()))
        assertFalse("the deferred recipient is not retried in the same pass", sends.contains(a.accountId.toString() to second.toString()))
        assertTrue("a server failure ends the pass", sends.contains(d.accountId.toString() to second.toString()))
        val ack = requests().single { it.url.encodedPath == "/v1/communities/inbox/ack" }
        assertTrue("an unreadable community's record is acknowledged, not retried forever", ack.body!!.utf8().contains("gone"))
        assertTrue(owned.repository.wasProcessed("gone"))

        // The full inbox said when to come back: the next pass leaves that recipient alone.
        sends.clear()
        owned.engine.sync()
        assertFalse(sends.any { it.first == a.accountId.toString() })
        assertTrue(sends.contains(d.accountId.toString() to second.toString()))
    }

    @Test fun anEmptyInboxPageWithMoreBehindItKeepsPaging() = runBlocking {
        val owned = Owned(members = 1)
        var pages = 0
        route { request ->
            when (request.url.encodedPath) {
                "/v1/communities/mine" -> json("""{"communities":[${owned.signedJson}]}""")
                "/v1/communities/inbox" -> { pages += 1; json("""{"envelopes":[],"more":${pages < 3}}""") }
                else -> null
            }
        }
        owned.engine.sync()
        assertEquals(3, pages)
    }

    @Test fun anOversizedEnvelopeFailsOnlyItsRecipientWithoutReachingTheRelay() = runBlocking {
        val owned = Owned(members = 2)
        val (a, b) = owned.others
        val message = owned.message("big")
        storage.write { owned.queue(message, a, ByteArray(RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES + 1)); owned.queue(message, b) }
        route { request ->
            when (request.url.encodedPath) {
                "/v1/communities/mine" -> json("""{"communities":[${owned.signedJson}]}""")
                "/v1/communities/inbox" -> json("""{"envelopes":[],"more":false}""")
                else -> if (request.url.encodedPath.contains("/messages/")) MockResponse.Builder().code(202).body("""{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1234}""").build() else null
            }
        }
        owned.engine.sync()
        assertTrue(owned.repository.pending().isEmpty())
        assertEquals("failed", owned.status(message))
        val sent = requests().filter { it.url.encodedPath.contains("/messages/") }.map { it.url.encodedPath.split("/").takeLast(2).first() }
        assertEquals(listOf(b.accountId.toString()), sent)
    }

    @Test fun aRolledBackRosterRaisesTheWarningAndIsNotApplied() = runBlocking {
        val owned = Owned(members = 1)
        val newer = owned.state.copy(revision = 2).sign(owned.account.identityKeyPair)
        owned.repository.save(newer)
        route { request ->
            when (request.url.encodedPath) {
                "/v1/communities/mine" -> json("""{"communities":[${owned.signedJson}]}""")
                "/v1/communities/inbox" -> json("""{"envelopes":[],"more":false}""")
                else -> null
            }
        }
        owned.engine.sync()
        assertTrue(owned.engine.securityWarning)
        assertEquals(2L, owned.repository.get(owned.state.id)!!.state!!.revision)
        assertTrue("a refused roster does not end the membership", owned.repository.get(owned.state.id)!!.active)
    }

    @Test fun leavingWithdrawingAndForgettingRemoveTheCommunityAndItsKey() = runBlocking {
        val account = storage.saveAccount()
        val party = Party.on(storage, account)
        val engine = CommunityEngine(Messenger(account, party.store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        val repository = CommunityRepository(storage)
        val owner = IdentityKeyPair.generate()
        val ownerId = newAccountId()
        val joined = CommunityState(newAccountId(), 1, "public", ownerId, key(owner), "Joined",
            listOf(CommunityMember(ownerId, key(owner)), CommunityMember(account.accountId, key(account.identityKeyPair))).sortedBy { it.accountId.toString() })
        repository.pin(joined.id, key(owner), joined.name)
        repository.save(joined.sign(owner))
        repository.add(StoredCommunityMessage(joined.id, ownerId, newAccountId(), 1, "history", "received"))
        val pendingId = newAccountId()
        repository.pin(pendingId, key(owner), "Pending")
        val slots = storage.keyring.slotIds()
        assertEquals(2, slots.size)
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(status(404, "not_found"))
        engine.leave(joined.id)
        engine.withdraw(pendingId)
        val calls = requests()
        assertEquals(listOf("DELETE /v1/communities/${joined.id}/members/me", "DELETE /v1/communities/$pendingId/requests/me"), calls.map { "${it.method} ${it.url.encodedPath}" })
        assertNull(repository.get(joined.id)); assertNull(repository.get(pendingId))
        assertTrue("history goes with it", repository.messages(joined.id).isEmpty())
        assertTrue("both key slots are destroyed", storage.keyring.slotIds().isEmpty())

        val ended = joined.copy(id = newAccountId())
        repository.pin(ended.id, key(owner), "Ended")
        repository.save(ended.sign(owner))
        repository.deactivateMissing(emptySet())
        engine.forget(ended.id)
        assertNull(repository.get(ended.id))
        assertEquals("forgetting is local only", 0, requests().size)
    }

    @Test fun aCurrentMembershipIsNotForgottenAndAnOwnerCannotLeave() = runBlocking {
        val owned = Owned(members = 1)
        try { owned.engine.forget(owned.state.id); fail("current membership forgotten") } catch (_: IllegalArgumentException) { }
        try { owned.engine.leave(owned.state.id); fail("owner left") } catch (_: IllegalArgumentException) { }
        assertNotNull(owned.repository.get(owned.state.id))
        assertEquals(0, requests().size)
    }

    @Test fun buildingTheEngineWritesNothingUntilFirstUse() {
        val account = storage.saveAccount()
        val owned = CommunityRepository(storage)
        val id = newAccountId()
        owned.pin(id, key(account.identityKeyPair), "Interrupted")
        val message = newAccountId()
        owned.add(StoredCommunityMessage(id, account.accountId, message, 1, "half prepared", "preparing"))
        val engine = CommunityEngine(Messenger(account, Party.on(storage, account).store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        assertEquals("preparing", owned.messages(id).single().status)
        engine.communities()
        assertEquals("failed", owned.messages(id).single().status)
    }
}
