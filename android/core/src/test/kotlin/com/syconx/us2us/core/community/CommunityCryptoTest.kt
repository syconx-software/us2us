package com.syconx.us2us.core.community

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.testing.Party
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.keys.PreKeyPolicy
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.proto.content
import kotlin.time.Duration.Companion.milliseconds
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.UUID

class CommunityCryptoTest {
    private val relay = FakeRelay().start()
    @AfterEach fun close() = relay.close()
    private suspend fun account(username: String): Pair<Messenger, InMemoryContactStore> {
        val contacts = InMemoryContactStore()
        return Messenger.register(Username(username), RelayClient(relay.url), InMemoryProtocolStorage(), contacts,
            MessengerConfig(preKeyPolicy = PreKeyPolicy(batchSize = 1, refillThreshold = 0), powParallelism = 1)) to contacts
    }
    private fun member(messenger: Messenger) = CommunityMember(messenger.accountId, Base64Url.encode(messenger.account.identityKeyPair.publicKey.serialize()))
    private fun state(alice: Messenger, bob: Messenger) = CommunityState(AccountId(UUID.randomUUID()), 1, "public", alice.accountId,
        member(alice).identityKey, "Members", listOf(member(alice), member(bob)).sortedBy { it.accountId.toString() })
    private fun payload(state: CommunityState) = communityMessage {
        communityId = ByteString.copyFrom(state.id.toBytes()); revision = state.revision
        messageId = ByteString.copyFrom(AccountId(UUID.randomUUID()).toBytes()); sentAt = System.currentTimeMillis(); text = "Topluluk mesajı"
    }

    @Test fun `group crypto creates no direct contacts and existing one to one still works`() = runBlocking {
        val (alice, aliceContacts) = account("alice")
        val (bob, bobContacts) = account("bob")
        val state = state(alice, bob)
        val envelope = alice.encryptCommunityMessage(member(bob), payload(state))
        val decoded = bob.decryptCommunityEnvelope(envelope, state)
        assertEquals("Topluluk mesajı", decoded?.second?.text)
        assertEquals(alice.accountId, decoded?.first)
        assertTrue(aliceContacts.all().isEmpty()); assertTrue(bobContacts.all().isEmpty())
        assertNull(bob.decryptCommunityEnvelope(envelope, state), "duplicate envelope cannot become another message")
        val receiving = TestClient(bob, this)
        try {
            receiving.awaitUpToDate()
            alice.sendText(bob.accountId, "Direct after group")
            val direct = receiving.nextData()
            assertEquals("Direct after group", direct.content.data.text.body)
            assertEquals(ContactState.INCOMING_REQUEST, direct.contactState)
            assertNull(bobContacts.get(alice.accountId)?.theirAccessKey)
        } finally { receiving.job.cancelAndJoin() }
        assertTrue(relay.violations.isEmpty(), relay.violations.toString())
    }

    @Test fun `removed sender and substituted key are refused before the ratchet advances`() = runBlocking {
        val (alice, _) = account("alice")
        val (bob, _) = account("bob")
        val state = state(alice, bob)
        val envelope = alice.encryptCommunityMessage(member(bob), payload(state))
        val removed = state.copy(ownerAccountId = bob.accountId, ownerIdentityKey = member(bob).identityKey, members = listOf(member(bob)))
        assertNull(bob.decryptCommunityEnvelope(envelope, removed))
        val badKey = Base64Url.encode(IdentityKeyPair.generate().publicKey.serialize())
        val forged = state.copy(members = state.members.map { if (it.accountId == alice.accountId) it.copy(identityKey = badKey) else it })
        assertNull(bob.decryptCommunityEnvelope(envelope, forged))
        assertNotNull(bob.decryptCommunityEnvelope(envelope, state), "the refused attempts must not consume the real envelope")
    }

    @Test fun `recipient prekey identity is checked against owner signed member identity`() = runBlocking {
        val (alice, contacts) = account("alice")
        val (bob, _) = account("bob")
        val state = state(alice, bob)
        val wrong = member(bob).copy(identityKey = Base64Url.encode(IdentityKeyPair.generate().publicKey.serialize()))
        assertThrows<IllegalArgumentException> { alice.encryptCommunityMessage(wrong, payload(state)) }
        val envelope = alice.encryptCommunityMessage(member(bob), payload(state))
        assertNotNull(bob.decryptCommunityEnvelope(envelope, state))
        assertTrue(contacts.all().isEmpty())
    }
    @Test fun `a community payload sent to the direct mailbox is ignored before contact processing`() = runBlocking {
        val (alice, _) = account("alice")
        val (bob, contacts) = account("bob")
        val state = state(alice, bob)
        val receiving = TestClient(bob, this)
        try {
            receiving.awaitUpToDate()
            alice.send(bob.accountId, content { community = payload(state) })
            assertTrue(receiving.receivesNothingWithin(300.milliseconds))
            assertTrue(contacts.all().isEmpty())
            assertEquals(0, relay.mailboxSize(bob.accountId))
        } finally { receiving.job.cancelAndJoin() }
    }

    @Test fun `100 member fanout uses 99 separate libsignal sessions and no personal credentials`() = runBlocking {
        val parties = List(100) { Party(policy = PreKeyPolicy(batchSize = 1, refillThreshold = 0)) }
        val contacts = List(100) { InMemoryContactStore() }
        val clients = parties.mapIndexed { index, party ->
            Messenger(LocalAccount(party.accountId, AuthToken(ByteArray(32) { 7 }), Username("member_$index"),
                party.store.identityKeyPair, party.store.localRegistrationId, AccessKey.generate()), party.store,
                contacts[index], RelayClient("http://127.0.0.1:1"), MessengerConfig(clock = party.clock))
        }
        val owner = clients.first()
        val roster = CommunityState(AccountId(UUID.randomUUID()), 1, "public", owner.accountId, member(owner).identityKey,
            "100 members", clients.map(::member).sortedBy { it.accountId.toString() })
        val body = payload(roster)
        val ciphertexts = ArrayList<String>()
        for (index in 1..99) {
            parties.first().sessions.startSession(parties[index].accountId, parties[index].bundle())
            val envelope = owner.encryptCommunityMessage(member(clients[index]), body)
            assertTrue(envelope.size <= 65536)
            ciphertexts += Base64Url.encode(envelope)
            assertEquals(body, clients[index].decryptCommunityEnvelope(envelope, roster)?.second)
        }
        assertEquals(99, ciphertexts.distinct().size)
        assertTrue(contacts.all { it.all().isEmpty() })
    }

}
