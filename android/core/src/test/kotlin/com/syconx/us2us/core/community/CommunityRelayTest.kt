package com.syconx.us2us.core.community

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.relay.RelayClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class CommunityRelayTest {
    @Test fun `community delivery is authenticated separately and never sends a personal access key`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val token = AuthToken(ByteArray(32) { 7 })
            val relay = RelayClient(server.url("/").toString()).withAuthToken(token)
            val id = AccountId(UUID.randomUUID()); val recipient = AccountId(UUID.randomUUID()); val message = AccountId(UUID.randomUUID())
            server.enqueue(MockResponse.Builder().code(202).body("""{"id":"AAAAAAAAAAAAAAAAAAAAAA","serverTimestamp":1234}""").build())
            relay.sendCommunityEnvelope(id, recipient, message, 2, byteArrayOf(1, 2, 3))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/communities/$id/messages/$recipient/$message", request.url.encodedPath)
            assertEquals("PUT", request.method)
            assertEquals(token.headerValue, request.headers["X-Auth-Token"])
            assertEquals("2", request.headers["X-Community-Revision"])
            assertNull(request.headers["X-Access-Key"]); assertNull(request.headers["X-PoW"])
            assertArrayEquals(byteArrayOf(1, 2, 3), request.body!!.toByteArray())
        } finally { server.close() }
    }

    @Test fun `leaving and withdrawing address only the own membership`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val token = AuthToken(ByteArray(32) { 7 })
            val relay = RelayClient(server.url("/").toString()).withAuthToken(token)
            val id = AccountId(UUID.randomUUID())
            server.enqueue(MockResponse.Builder().code(204).build())
            server.enqueue(MockResponse.Builder().code(204).build())
            server.enqueue(MockResponse.Builder().code(409).body("""{"error":{"code":"bad_request","message":"owner cannot leave"}}""").build())
            relay.leaveCommunity(id)
            relay.withdrawCommunityRequest(id)
            val owner = assertThrows(com.syconx.us2us.core.relay.RelayException::class.java) { runBlocking { relay.leaveCommunity(id) } }
            assertEquals(409, owner.httpStatus)
            val leave = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("DELETE", leave.method)
            assertEquals("/v1/communities/$id/members/me", leave.url.encodedPath)
            assertEquals(token.headerValue, leave.headers["X-Auth-Token"])
            val withdraw = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("DELETE", withdraw.method)
            assertEquals("/v1/communities/$id/requests/me", withdraw.url.encodedPath)
        } finally { server.close() }
    }

    @Test fun `a full community inbox is told apart and says when to retry`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val relay = RelayClient(server.url("/").toString()).withAuthToken(AuthToken(ByteArray(32) { 7 }))
            val id = AccountId(UUID.randomUUID())
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "600").body("""{"error":{"code":"community_inbox_full","message":"full"}}""").build())
            server.enqueue(MockResponse.Builder().code(409).body("""{"error":{"code":"community_full","message":"full"}}""").build())
            val full = assertThrows(com.syconx.us2us.core.relay.RelayException::class.java) { runBlocking { relay.sendCommunityEnvelope(id, id, id, 1, byteArrayOf(1)) } }
            assertEquals(com.syconx.us2us.core.relay.RelayErrorCode.COMMUNITY_INBOX_FULL, full.code)
            assertEquals(600L, full.retryAfterSeconds)
            val group = assertThrows(com.syconx.us2us.core.relay.RelayException::class.java) { runBlocking { relay.requestCommunity(id) } }
            assertEquals(com.syconx.us2us.core.relay.RelayErrorCode.COMMUNITY_FULL, group.code)
        } finally { server.close() }
    }

    @Test fun `an oversized community envelope never reaches the relay`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val relay = RelayClient(server.url("/").toString()).withAuthToken(AuthToken(ByteArray(32) { 7 }))
            val id = AccountId(UUID.randomUUID())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { relay.sendCommunityEnvelope(id, id, id, 1, ByteArray(RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES + 1)) }
            }
            assertEquals(0, server.requestCount)
        } finally { server.close() }
    }

    @Test fun `invite secret appears only in the authenticated request body`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val token = AuthToken(ByteArray(32) { 7 }); val invite = Base64Url.encode(ByteArray(32) { 9 })
            val relay = RelayClient(server.url("/").toString()).withAuthToken(token)
            val id = AccountId(UUID.randomUUID())
            server.enqueue(MockResponse.Builder().code(202).body("""{"id":"$id","name":"","visibility":"private","ownerAccountId":"$id","ownerIdentityKey":"key","memberCount":1,"revision":1}""").build())
            relay.redeemCommunityInvite(id, invite)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/communities/invites/redeem", request.url.encodedPath)
            assertNull(request.url.query)
            assertEquals(token.headerValue, request.headers["X-Auth-Token"])
            assertTrue(request.body!!.utf8().contains(invite))
        } finally { server.close() }
    }
}
