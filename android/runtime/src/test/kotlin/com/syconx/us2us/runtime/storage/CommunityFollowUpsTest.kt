package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.community.SignedCommunityState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.relay.CommunityRequest
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.runtime.community.CommunityEngine
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.Collections

/**
 * Client follow-ups of the 2026-10-28 relay fixes (messenger-relay-fixes.md §5): re-admitting a
 * member who left (M-11), "reject all" (M-14), and the owner dropping a leaver from the roster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunityFollowUpsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: LocalStorage
    private val server = MockWebServer()

    @Before fun open() { storage = TestEnvironment(temporary.newFolder()).open(); server.start() }

    @After fun close() { storage.close(); server.close() }

    private fun key(pair: IdentityKeyPair) = Base64Url.encode(pair.publicKey.serialize())
    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
    private fun status(code: Int, wire: String = "not_found") = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body("""{"error":{"code":"$wire","message":"test"}}""").build()
    private fun noContent() = MockResponse.Builder().code(204).build()
    private fun signedJson(signed: SignedCommunityState) = """{"state":"${signed.state}","signature":"${signed.signature}"}"""

    /** The owner's phone and a relay that keeps the latest signed roster and the pending requests. */
    private inner class OwnerRelay(memberCount: Int) {
        val account = storage.saveAccount()
        val party = Party.on(storage, account)
        val ownerMember = CommunityMember(account.accountId, key(account.identityKeyPair))
        val others = List(memberCount) { CommunityMember(newAccountId(), key(IdentityKeyPair.generate())) }
        val initial = CommunityState(newAccountId(), 1, "public", account.accountId, ownerMember.identityKey, "Topluluk",
            (others + ownerMember).sortedBy { it.accountId.toString() })
        @Volatile var latest: SignedCommunityState = initial.sign(account.identityKeyPair)
        val published: MutableList<CommunityState> = Collections.synchronizedList(ArrayList())
        val pending: MutableList<CommunityRequest> = Collections.synchronizedList(ArrayList())
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        var bulkRejectSupported = true
        val repository = CommunityRepository(storage)
        val engine = CommunityEngine(Messenger(account, party.store, storage.contactStore, RelayClient(server.url("/").toString())), storage)
        private val id get() = initial.id

        init {
            repository.pin(id, ownerMember.identityKey, initial.name)
            repository.save(latest)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
            }
        }

        val state: CommunityState get() = latest.verify()

        private fun handle(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            calls += "${request.method} $path"
            val base = "/v1/communities/$id"
            return when {
                request.method == "GET" && path == base -> json(signedJson(latest))
                request.method == "PUT" && path == "$base/state" -> {
                    val body = request.body!!.utf8()
                    val signed = SignedCommunityState(
                        Regex("\"state\":\"([^\"]+)\"").find(body)!!.groupValues[1],
                        Regex("\"signature\":\"([^\"]+)\"").find(body)!!.groupValues[1],
                    )
                    val next = signed.verify(ownerMember.identityKey, state)
                    published += next
                    latest = signed
                    json(signedJson(signed))
                }
                request.method == "GET" && path == "$base/requests" ->
                    json("""{"requests":[${pending.joinToString(",") { """{"accountId":"${it.accountId}","identityKey":"${it.identityKey}","createdAt":${it.createdAt}}""" }}]}""")
                request.method == "DELETE" && path == "$base/requests" ->
                    if (bulkRejectSupported) noContent().also { pending.clear() } else status(405, "bad_request")
                request.method == "DELETE" && path.startsWith("$base/requests/") -> {
                    val account = AccountId.parse(path.substringAfterLast('/'))
                    if (pending.removeAll { it.accountId == account }) noContent() else status(404)
                }
                request.method == "GET" && path == "/v1/communities/mine" -> json("""{"communities":[${signedJson(latest)}]}""")
                request.method == "GET" && path == "/v1/communities/inbox" -> json("""{"envelopes":[],"more":false}""")
                request.method == "PUT" && path.startsWith("$base/messages/") -> status(404)
                else -> status(404)
            }
        }
    }

    @Test
    fun aMemberWhoLeftIsReadmittedWithTwoSignedStates() = runBlocking {
        val relay = OwnerRelay(memberCount = 2)
        val leaver = relay.others.first()
        // It left (still listed in the signed roster) and asks again, under a new identity key.
        val request = CommunityRequest(leaver.accountId, key(IdentityKeyPair.generate()), 1)
        relay.pending += request
        relay.engine.approve(relay.initial.id, request)

        assertEquals(2, relay.published.size)
        val (without, with) = relay.published
        assertEquals(2L, without.revision)
        assertNull("first a roster without it", without.member(leaver.accountId))
        assertEquals(3L, with.revision)
        assertEquals("then with the key it asks to add", request.identityKey, with.member(leaver.accountId)?.identityKey)
        assertEquals(relay.initial.members.size, with.members.size)
    }

    @Test
    fun aNewMemberIsStillAdmittedWithOneState() = runBlocking {
        val relay = OwnerRelay(memberCount = 1)
        val request = CommunityRequest(newAccountId(), key(IdentityKeyPair.generate()), 1)
        relay.pending += request
        relay.engine.approve(relay.initial.id, request)
        assertEquals(1, relay.published.size)
        assertEquals(2L, relay.published.single().revision)
        assertEquals(request.identityKey, relay.published.single().member(request.accountId)?.identityKey)
    }

    @Test
    fun rejectAllUsesTheBulkRouteAndFallsBackOnAnOlderRelay() = runBlocking {
        val relay = OwnerRelay(memberCount = 1)
        repeat(3) { relay.pending += CommunityRequest(newAccountId(), key(IdentityKeyPair.generate()), 1) }
        relay.engine.rejectAll(relay.initial.id)
        assertTrue(relay.pending.isEmpty())
        assertEquals(1, relay.calls.count { it == "DELETE /v1/communities/${relay.initial.id}/requests" })
        assertFalse(relay.calls.any { it.startsWith("DELETE /v1/communities/${relay.initial.id}/requests/") })

        relay.bulkRejectSupported = false
        repeat(2) { relay.pending += CommunityRequest(newAccountId(), key(IdentityKeyPair.generate()), 1) }
        relay.engine.rejectAll(relay.initial.id)
        assertTrue(relay.pending.isEmpty())
        assertEquals(2, relay.calls.count { it.startsWith("DELETE /v1/communities/${relay.initial.id}/requests/") })
    }

    @Test
    fun theOwnerDropsAListedMemberWhoseDeliveryIsRefusedAsGone() = runBlocking {
        val relay = OwnerRelay(memberCount = 2)
        val (leaver, stays) = relay.others
        // A text queued for both; the relay answers 404 for both deliveries here (left).
        val message = newAccountId()
        relay.repository.add(StoredCommunityMessage(relay.initial.id, relay.account.accountId, message, 1234, "selam", "pending"))
        storage.write {
            relay.repository.enqueue(CommunityDelivery(relay.initial.id, message, leaver.accountId, 1, byteArrayOf(1, 2, 3)))
        }
        relay.engine.sync()
        val latest = relay.state
        assertNull("the leaver is no longer listed", latest.member(leaver.accountId))
        assertTrue("the others stay", latest.member(stays.accountId) != null)
        assertEquals(2L, latest.revision)
    }
}
