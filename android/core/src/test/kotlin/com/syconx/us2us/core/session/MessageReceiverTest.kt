package com.syconx.us2us.core.session

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.testing.Party
import com.syconx.us2us.core.testing.textContent
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

class MessageReceiverTest {
    private val alice = Party()
    private val bob = Party()

    /**
     * What the relay would answer; tests rewrite it to simulate lies and outages. [cachedOwners]
     * plays an earlier answer that is still cached: it is returned unless the caller asks for a
     * refresh, which reads [owners] and replaces it.
     */
    private class FakeDirectory : SenderDirectory {
        val identities = HashMap<AccountId, IdentityKey>()
        val owners = HashMap<String, AccountId>()
        val cachedOwners = HashMap<String, AccountId>()
        var offline = false
        var identityLookups = 0
        var refreshes = 0

        override suspend fun identityKey(accountId: AccountId): IdentityKey? {
            if (offline) throw IOException("relay unreachable")
            identityLookups++
            return identities[accountId]
        }

        override suspend fun accountFor(username: Username, refresh: Boolean): AccountId? {
            if (!refresh) cachedOwners[username.value]?.let { return it }
            if (offline) throw IOException("relay unreachable")
            if (refresh) refreshes++
            val owner = owners[username.value]
            if (owner != null) cachedOwners[username.value] = owner else cachedOwners.remove(username.value)
            return owner
        }
    }

    private val directory = FakeDirectory().apply {
        identities[alice.accountId] = alice.identityKey
        owners["alice_01"] = alice.accountId
    }
    private val receiver = MessageReceiver(bob.accountId, bob.store, bob.sessions, SenderVerifier(bob.store, directory), Mutex())

    init {
        alice.sessions.startSession(bob.accountId, bob.bundle())
    }

    private fun envelopeFromAlice(message: CiphertextMessage, username: String = "", senderId: AccountId = alice.accountId): ByteArray =
        SealedEnvelope.seal(
            sealedContent {
                senderAccountId = senderId.toBytes().toByteString()
                senderDeviceId = 1
                type = message.type
                ciphertext = message.serialize().toByteString()
                senderUsername = username
            },
            bob.identityKey,
        )

    @Test
    fun `a verified message request is decrypted`() = runBlocking {
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "merhaba"), username = "alice_01"))
        result as ReceiveResult.Decrypted
        assertEquals(alice.accountId, result.sender)
        assertEquals("alice_01", result.senderUsername?.value)
        assertEquals("merhaba", result.content.data.text.body)
        assertTrue(result.preKeyMessage)
    }

    @Test
    fun `a pre-key message whose identity differs from the relay's is dropped`() = runBlocking {
        directory.identities[alice.accountId] = Party().identityKey
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "x")))
        assertEquals(DropReason.UNVERIFIED_SENDER, (result as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `an unknown sender account is dropped`() = runBlocking {
        directory.identities.clear()
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "x")))
        assertEquals(DropReason.UNVERIFIED_SENDER, (result as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `a username that belongs to someone else is dropped`() = runBlocking {
        directory.owners["alice_01"] = AccountId(UUID.randomUUID())
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "x"), username = "alice_01"))
        assertEquals(DropReason.UNVERIFIED_SENDER, (result as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `a username that moved is asked again before the request is dropped`() = runBlocking {
        // An earlier answer said alice_01 belongs to Carol; the name has since moved to Alice.
        val carol = Party()
        directory.cachedOwners["alice_01"] = carol.accountId
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "the name is mine now"), username = "alice_01"))
        assertEquals("the name is mine now", (result as ReceiveResult.Decrypted).content.data.text.body)
        assertEquals("alice_01", result.senderUsername?.value)
        assertEquals(1, directory.refreshes)

        // The refreshed answer replaced the old one: Carol's still-installed client is refused.
        directory.identities[carol.accountId] = carol.identityKey
        carol.sessions.startSession(bob.accountId, bob.bundle())
        val stale = envelopeFromAlice(carol.encryptTo(bob, "still alice_01"), username = "alice_01", senderId = carol.accountId)
        assertEquals(DropReason.UNVERIFIED_SENDER, (receiver.receive(stale) as ReceiveResult.Dropped).reason)
        assertEquals(2, directory.refreshes)
    }

    @Test
    fun `a matching cached owner needs no refresh`() = runBlocking {
        directory.cachedOwners["alice_01"] = alice.accountId
        receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "x"), username = "alice_01")) as ReceiveResult.Decrypted
        assertEquals(0, directory.refreshes)
    }

    @Test
    fun `a refresh that cannot reach the relay keeps the envelope for later`() = runBlocking {
        directory.cachedOwners["alice_01"] = Party().accountId
        // Alice's identity is already known, so only the username refresh needs the relay.
        bob.store.saveIdentity(alice.accountId.protocolAddress(), alice.identityKey)
        directory.offline = true
        val envelope = envelopeFromAlice(alice.encryptTo(bob, "later"), username = "alice_01")
        assertThrows<IOException> { runBlocking { receiver.receive(envelope) } }
        directory.offline = false
        val result = receiver.receive(envelope) as ReceiveResult.Decrypted
        assertEquals("later", result.content.data.text.body)
        assertEquals("alice_01", result.senderUsername?.value)
    }

    @Test
    fun `a username that is not in normalized form is dropped`() = runBlocking {
        directory.owners["alice_01"] = alice.accountId
        val result = receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "x"), username = "Alice_01"))
        assertEquals(DropReason.UNVERIFIED_SENDER, (result as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `an unreachable relay keeps the envelope for later`() = runBlocking {
        directory.offline = true
        val envelope = envelopeFromAlice(alice.encryptTo(bob, "later"))
        assertThrows<IOException> { runBlocking { receiver.receive(envelope) } }
        directory.offline = false
        assertEquals("later", (receiver.receive(envelope) as ReceiveResult.Decrypted).content.data.text.body)
    }

    @Test
    fun `known identities are not looked up again`() = runBlocking {
        receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "one")))
        val lookups = directory.identityLookups
        receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "two")))
        assertEquals(lookups, directory.identityLookups)
    }

    @Test
    fun `whisper messages after the reply need no relay lookups`() = runBlocking {
        receiver.receive(envelopeFromAlice(alice.encryptTo(bob, "first")))
        alice.decryptFrom(bob, bob.encryptTo(alice, "reply"))
        directory.offline = true
        val message = alice.encryptTo(bob, "whisper")
        assertEquals(CiphertextMessage.WHISPER_TYPE, message.type)
        val result = receiver.receive(envelopeFromAlice(message)) as ReceiveResult.Decrypted
        assertEquals("whisper", result.content.data.text.body)
        assertNull(result.senderUsername)
    }

    @Test
    fun `duplicates, self-sent, garbage and spoofed senders are dropped`() = runBlocking {
        val envelope = envelopeFromAlice(alice.encryptTo(bob, "once"))
        receiver.receive(envelope) as ReceiveResult.Decrypted
        assertEquals(DropReason.DUPLICATE, (receiver.receive(envelope) as ReceiveResult.Dropped).reason)

        val selfSent = envelopeFromAlice(alice.encryptTo(bob, "me"), senderId = bob.accountId)
        assertEquals(DropReason.SELF_SENT, (receiver.receive(selfSent) as ReceiveResult.Dropped).reason)

        assertEquals(DropReason.INVALID_ENVELOPE, (receiver.receive(byteArrayOf(1, 2, 3)) as ReceiveResult.Dropped).reason)

        // Carol claims to be Alice: her pre-key message carries Carol's identity, not Alice's.
        val carol = Party()
        carol.sessions.startSession(bob.accountId, bob.bundle())
        val spoofed = envelopeFromAlice(carol.encryptTo(bob, "I am Alice"))
        assertEquals(DropReason.UNVERIFIED_SENDER, (receiver.receive(spoofed) as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `a whisper message without a session cannot be decrypted`() = runBlocking {
        val first = alice.encryptTo(bob, "first")
        bob.decryptFrom(alice, first)
        alice.decryptFrom(bob, bob.encryptTo(alice, "reply"))
        val whisper = alice.encryptTo(bob, "after")
        bob.store.deleteSession(alice.accountId.protocolAddress())
        assertEquals(DropReason.DECRYPTION_FAILED, (receiver.receive(envelopeFromAlice(whisper)) as ReceiveResult.Dropped).reason)
    }

    @Test
    fun `garbage ciphertexts in a valid envelope are dropped, never thrown`() = runBlocking {
        val rnd = Random(5)
        for (type in listOf(CiphertextMessage.WHISPER_TYPE, CiphertextMessage.PREKEY_TYPE)) {
            repeat(20) {
                val envelope = SealedEnvelope.seal(
                    sealedContent {
                        senderAccountId = alice.accountId.toBytes().toByteString()
                        senderDeviceId = 1
                        this.type = type
                        ciphertext = rnd.nextBytes(rnd.nextInt(1, 300)).toByteString()
                    },
                    bob.identityKey,
                )
                val result = receiver.receive(envelope)
                assertTrue(result is ReceiveResult.Dropped, "type $type")
            }
        }
    }

    @Test
    fun `bad padding inside a valid session message is invalid content`() = runBlocking {
        val unpadded = SessionCipher(alice.store, alice.store, alice.store, alice.store, alice.store, alice.accountId.protocolAddress(), bob.accountId.protocolAddress())
            .encrypt(textContent("no padding").toByteArray())
        assertEquals(DropReason.INVALID_CONTENT, (receiver.receive(envelopeFromAlice(unpadded)) as ReceiveResult.Dropped).reason)
    }
}
