package com.syconx.us2us.core.store

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.keys.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange
import org.signal.libsignal.protocol.state.SessionRecord
import java.util.UUID

class ProtocolStoreTest {
    private val identity = Keys.generateIdentityKeyPair()
    private val store = InMemoryProtocolStore(identity, 42)
    private val peer = AccountId(UUID.randomUUID())
    private val address = peer.protocolAddress()

    @Test
    fun `local identity and registration id`() {
        assertEquals(identity, store.identityKeyPair)
        assertEquals(42, store.localRegistrationId)
    }

    @Test
    fun `trust on first use, change detection and acceptance`() {
        val first = Keys.generateIdentityKeyPair().publicKey
        val second = Keys.generateIdentityKeyPair().publicKey

        assertTrue(store.isTrustedIdentity(address, first, Direction.SENDING), "unknown identities are trusted on first use")
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, first))
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, first))
        assertEquals(IdentityTrust.TRUSTED, store.identity(peer)?.trust)
        assertTrue(store.isTrustedIdentity(address, first, Direction.SENDING))
        assertFalse(store.isTrustedIdentity(address, second, Direction.SENDING))
        assertTrue(store.isTrustedIdentity(address, second, Direction.RECEIVING), "receiving accepts and flags")

        store.setVerified(peer, true)
        assertEquals(IdentityChange.REPLACED_EXISTING, store.saveIdentity(address, second))
        val changed = store.identity(peer)!!
        assertEquals(IdentityTrust.CHANGED, changed.trust)
        assertEquals(second, changed.identityKey)
        assertFalse(changed.verified, "verification does not carry over to a new key")
        assertFalse(store.isTrustedIdentity(address, second, Direction.SENDING), "sending is blocked until accepted")

        store.acceptIdentity(peer)
        assertEquals(IdentityTrust.TRUSTED, store.identity(peer)?.trust)
        assertTrue(store.isTrustedIdentity(address, second, Direction.SENDING))
        assertEquals(second, store.getIdentity(address))
    }

    @Test
    fun `identities restored from a backup keep their trust`() {
        val key = Keys.generateIdentityKeyPair().publicKey
        store.restoreIdentity(peer, key, IdentityTrust.TRUSTED, verified = true)
        assertEquals(IdentityTrust.TRUSTED, store.identity(peer)?.trust)
        assertTrue(store.identity(peer)!!.verified)
        assertTrue(store.isTrustedIdentity(address, key, Direction.SENDING))
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, key), "the same key from the peer changes nothing")

        // A change the user had not accepted on the old phone stays blocked, and is never verified.
        val other = AccountId(UUID.randomUUID())
        store.restoreIdentity(other, key, IdentityTrust.CHANGED, verified = true)
        val changed = store.identity(other)!!
        assertEquals(IdentityTrust.CHANGED, changed.trust)
        assertFalse(changed.verified)
        assertFalse(store.isTrustedIdentity(other.protocolAddress(), key, Direction.SENDING))
        assertTrue(store.isTrustedIdentity(other.protocolAddress(), key, Direction.RECEIVING))
        store.acceptIdentity(other)
        assertTrue(store.isTrustedIdentity(other.protocolAddress(), key, Direction.SENDING))
    }

    @Test
    fun `one-time Kyber keys are deleted when used`() {
        val record = Keys.kyberPreKey(identity, 7, 0)
        store.storeKyberPreKey(record, lastResort = false)
        store.markKyberPreKeyUsed(7, 1, ECKeyPair.generate().publicKey)
        assertFalse(store.containsKyberPreKey(7))
        assertThrows<InvalidKeyIdException> { store.loadKyberPreKey(7) }
        assertThrows<ReusedBaseKeyException> { store.markKyberPreKeyUsed(7, 1, ECKeyPair.generate().publicKey) }
    }

    @Test
    fun `last-resort Kyber keys are kept and each base key is accepted once per signed pre-key`() {
        store.storeKyberPreKey(Keys.kyberPreKey(identity, 9, 0), lastResort = true)
        val baseKey = ECKeyPair.generate().publicKey
        store.markKyberPreKeyUsed(9, 1, baseKey)
        assertTrue(store.containsKyberPreKey(9))
        assertThrows<ReusedBaseKeyException> { store.markKyberPreKeyUsed(9, 1, baseKey) }
        store.markKyberPreKeyUsed(9, 2, baseKey) // other signed pre-key: a different triple
        store.markKyberPreKeyUsed(9, 1, ECKeyPair.generate().publicKey)

        // Deleting the key forgets its base keys, so a re-created id starts clean.
        store.removeKyberPreKey(9)
        store.storeKyberPreKey(Keys.kyberPreKey(identity, 9, 0), lastResort = true)
        store.markKyberPreKeyUsed(9, 1, baseKey)
    }

    @Test
    fun `the libsignal store method keeps the kind of an existing Kyber key`() {
        store.storeKyberPreKey(Keys.kyberPreKey(identity, 3, 0), lastResort = true)
        store.storeKyberPreKey(3, Keys.kyberPreKey(identity, 3, 1))
        assertTrue(store.isLastResortKyberPreKey(3))
        store.storeKyberPreKey(4, Keys.kyberPreKey(identity, 4, 1))
        assertFalse(store.isLastResortKyberPreKey(4))
        assertEquals(listOf(3), store.lastResortKyberPreKeys().map { it.id })
    }

    @Test
    fun `pre-key and signed pre-key CRUD`() {
        store.storePreKey(5, Keys.oneTimePreKey(5))
        assertTrue(store.containsPreKey(5))
        assertEquals(5, store.loadPreKey(5).id)
        store.removePreKey(5)
        assertThrows<InvalidKeyIdException> { store.loadPreKey(5) }

        store.storeSignedPreKey(6, Keys.signedPreKey(identity, 6, 123))
        assertEquals(123, store.loadSignedPreKey(6).timestamp)
        assertEquals(listOf(6), store.loadSignedPreKeys().map { it.id })
        store.removeSignedPreKey(6)
        assertFalse(store.containsSignedPreKey(6))
    }

    @Test
    fun `sessions and forgetting a contact`() {
        assertNull(store.loadSession(address))
        assertFalse(store.containsSession(address))
        assertThrows<NoSessionException> { store.loadExistingSessions(listOf(address)) }
        store.storeSession(address, SessionRecord())
        assertTrue(store.containsSession(address))
        assertFalse(store.hasUsableSession(peer), "an empty record has no sender chain")
        assertEquals(emptyList<Int>(), store.getSubDeviceSessions(address.name))

        store.saveIdentity(address, Keys.generateIdentityKeyPair().publicKey)
        store.forget(peer)
        assertNull(store.loadSession(address))
        assertNull(store.identity(peer))
    }
}
