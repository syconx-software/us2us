package com.syconx.us2us.core.session

import com.syconx.us2us.core.keys.InvalidBundleException
import com.syconx.us2us.core.keys.toPreKeyBundle
import com.syconx.us2us.core.relay.PreKeyBundleEntity
import com.syconx.us2us.core.relay.SignedPreKeyEntity
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.testing.Party
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.message.CiphertextMessage

/** Two in-memory parties talking through libsignal, without any network. */
class SessionTest {

    private fun connected(): Pair<Party, Party> {
        val alice = Party()
        val bob = Party()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        return alice to bob
    }

    @Test
    fun `pre-key message, reply and many rounds`() {
        val (alice, bob) = connected()

        val first = alice.encryptTo(bob, "Merhaba 👋")
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertEquals("Merhaba 👋", bob.decryptFrom(alice, first))

        // Until Bob replies, Alice keeps sending pre-key messages.
        val second = alice.encryptTo(bob, "still pre-key")
        assertEquals(CiphertextMessage.PREKEY_TYPE, second.type)
        assertEquals("still pre-key", bob.decryptFrom(alice, second))

        val reply = bob.encryptTo(alice, "hi")
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        assertEquals("hi", alice.decryptFrom(bob, reply))

        val afterReply = alice.encryptTo(bob, "now whisper")
        assertEquals(CiphertextMessage.WHISPER_TYPE, afterReply.type)
        assertEquals("now whisper", bob.decryptFrom(alice, afterReply))

        repeat(20) { round ->
            val (from, to) = if (round % 3 == 0) bob to alice else alice to bob
            repeat(round % 4 + 1) { i ->
                val body = "round $round message $i"
                assertEquals(body, to.decryptFrom(from, from.encryptTo(to, body)))
            }
        }
    }

    @Test
    fun `out-of-order delivery and duplicates`() {
        val (alice, bob) = connected()
        bob.decryptFrom(alice, alice.encryptTo(bob, "setup"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "ack"))

        val sent = (0 until 6).map { alice.encryptTo(bob, "m$it") }
        for (i in listOf(4, 0, 5, 2, 1, 3)) assertEquals("m$i", bob.decryptFrom(alice, sent[i]))
        assertThrows<DuplicateMessageException> { bob.decryptFrom(alice, sent[2]) }
    }

    @Test
    fun `one-time pre-keys are deleted on use`() {
        val alice = Party()
        val bob = Party()
        val bundle = bob.bundleEntity()
        val ecId = bundle.oneTimePreKey!!.keyId
        val kyberId = bundle.kyberPreKey.keyId
        assertTrue(bob.store.containsPreKey(ecId))
        assertTrue(bob.store.containsKyberPreKey(kyberId))
        assertFalse(bob.store.isLastResortKyberPreKey(kyberId))

        alice.sessions.startSession(bob.accountId, bundle.toPreKeyBundle(bob.accountId))
        val first = alice.encryptTo(bob, "first")
        bob.decryptFrom(alice, first)

        assertFalse(bob.store.containsPreKey(ecId), "EC one-time key must be removed")
        assertFalse(bob.store.containsKyberPreKey(kyberId), "Kyber one-time key must be removed")

        // A replay after the session is gone cannot be decrypted any more.
        bob.store.deleteSession(alice.accountId.protocolAddress())
        assertThrows<InvalidKeyIdException> { bob.decryptFrom(alice, first) }
    }

    @Test
    fun `last-resort Kyber key is kept and a reused base key is rejected`() {
        val alice = Party()
        val carol = Party()
        val bob = Party()
        val lastResort = bob.lastResortKyberId

        alice.sessions.startSession(bob.accountId, bob.bundle(withOneTimeEc = false, withOneTimeKyber = false))
        val fromAlice = alice.encryptTo(bob, "via last resort")
        assertEquals("via last resort", bob.decryptFrom(alice, fromAlice))
        assertTrue(bob.store.containsKyberPreKey(lastResort), "last-resort key must stay")

        // A second sender may use the same last-resort key with a different base key.
        carol.sessions.startSession(bob.accountId, bob.bundle(withOneTimeEc = false, withOneTimeKyber = false))
        assertEquals("carol too", bob.decryptFrom(carol, carol.encryptTo(bob, "carol too")))

        // Replaying Alice's first message against a fresh session state reuses her base key.
        bob.store.deleteSession(alice.accountId.protocolAddress())
        assertThrows<ReusedBaseKeyException> { bob.decryptFrom(alice, fromAlice) }
    }

    @Test
    fun `identity change is accepted for receiving, flagged, and blocks sending until accepted`() = runBlocking {
        val (alice, bob) = connected()
        bob.decryptFrom(alice, alice.encryptTo(bob, "hello"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "hello back"))
        assertEquals(IdentityTrust.TRUSTED, alice.store.identity(bob.accountId)?.trust)

        // Bob reinstalls: same account id, new identity key, new pre-keys.
        val newBob = Party(accountId = bob.accountId)
        newBob.sessions.startSession(alice.accountId, alice.bundle())

        val changes = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { alice.store.identityChanges.first() } }
        assertEquals("I reinstalled", alice.decryptFrom(newBob, newBob.encryptTo(alice, "I reinstalled")))
        assertEquals(bob.accountId, changes.await())

        val state = alice.store.identity(bob.accountId)!!
        assertEquals(IdentityTrust.CHANGED, state.trust)
        assertEquals(newBob.identityKey, state.identityKey)
        assertThrows<UntrustedIdentityException> { alice.encryptTo(newBob, "blocked") }

        alice.store.acceptIdentity(bob.accountId)
        assertEquals("unblocked", newBob.decryptFrom(alice, alice.encryptTo(newBob, "unblocked")))
    }

    @Test
    fun `a bundle with a changed identity is refused`() {
        val (alice, bob) = connected()
        bob.decryptFrom(alice, alice.encryptTo(bob, "hello"))

        val impostor = Party(accountId = bob.accountId)
        assertThrows<UntrustedIdentityException> { alice.sessions.startSession(bob.accountId, impostor.bundle()) }
    }

    @Test
    fun `tampered bundle signatures are rejected`() {
        val alice = Party()
        val bob = Party()
        val good = bob.bundleEntity()
        fun SignedPreKeyEntity.flipped() = SignedPreKeyEntity(keyId, publicKey, signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })
        fun PreKeyBundleEntity.with(signed: SignedPreKeyEntity = signedPreKey, kyber: SignedPreKeyEntity = kyberPreKey) =
            PreKeyBundleEntity(accountId, deviceId, registrationId, identityKey, signed, kyber, oneTimePreKey)

        assertThrows<InvalidKeyException> { alice.sessions.startSession(bob.accountId, good.with(signed = good.signedPreKey.flipped()).toPreKeyBundle(bob.accountId)) }
        assertThrows<InvalidKeyException> { alice.sessions.startSession(bob.accountId, good.with(kyber = good.kyberPreKey.flipped()).toPreKeyBundle(bob.accountId)) }
        assertNull(alice.store.loadSession(bob.accountId.protocolAddress()))
    }

    @Test
    fun `malformed bundles are rejected before reaching libsignal`() {
        val bob = Party()
        val good = bob.bundleEntity()
        assertThrows<InvalidBundleException> { good.toPreKeyBundle(Party().accountId) }
        val truncatedIdentity = PreKeyBundleEntity(good.accountId, 1, good.registrationId, good.identityKey.copyOf(32), good.signedPreKey, good.kyberPreKey, good.oneTimePreKey)
        assertThrows<InvalidBundleException> { truncatedIdentity.toPreKeyBundle(bob.accountId) }
        val trailingBytes = PreKeyBundleEntity(good.accountId, 1, good.registrationId, good.identityKey + 0, good.signedPreKey, good.kyberPreKey, good.oneTimePreKey)
        assertThrows<InvalidBundleException> { trailingBytes.toPreKeyBundle(bob.accountId) }
        val otherDevice = PreKeyBundleEntity(good.accountId, 2, good.registrationId, good.identityKey, good.signedPreKey, good.kyberPreKey, good.oneTimePreKey)
        assertThrows<InvalidBundleException> { otherDevice.toPreKeyBundle(bob.accountId) }
    }

    @Test
    fun `a bundle without a one-time EC key still works`() {
        val alice = Party()
        val bob = Party()
        alice.sessions.startSession(bob.accountId, bob.bundle(withOneTimeEc = false))
        assertEquals("no opk", bob.decryptFrom(alice, alice.encryptTo(bob, "no opk")))
    }
}
