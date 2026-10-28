package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.store.KeyIdCounters
import com.syconx.us2us.core.store.ProtocolStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * The core's `ProtocolStore` on [SqlProtocolStorage] (the iOS ProtocolStoreContractTests and
 * PersistentSessionTests), and the key-bound verification calls (IdentityVerificationStoreTests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProtocolStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var environment: TestEnvironment
    private lateinit var storage: LocalStorage
    private lateinit var account: LocalAccount
    private lateinit var store: ProtocolStore
    private val contact = newAccountId()
    private val address get() = contact.protocolAddress()
    private val verifiedAt = Instant.ofEpochMilli(1_791_000_123_456L)
    private val byQr = IdentityVerification(IdentityVerificationMethod.QR_CODE, verifiedAt)
    private val byHand = IdentityVerification(IdentityVerificationMethod.MANUAL, verifiedAt)

    @Before
    fun setUp() {
        environment = TestEnvironment(temporary.newFolder())
        storage = environment.open()
        account = storage.saveAccount()
        store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
    }

    @After
    fun tearDown() {
        storage.close()
    }

    private fun newKey(): IdentityKey = IdentityKeyPair.generate().publicKey

    private fun reopen() {
        storage.close()
        storage = environment.open()
        store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
    }

    private val storage2 get() = storage.protocolStorage

    // MARK: Identities (§5)

    @Test
    fun trustOnFirstUseAChangeIsFlaggedAndBlocksSending() {
        val first = newKey()
        val second = newKey()
        assertEquals(ContactIdentity.Unknown, storage2.identityTrust(contact))
        assertTrue(store.isTrustedIdentity(address, first, Direction.SENDING))
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, first))
        assertEquals(ContactIdentity.Trusted(first, false), storage2.identityTrust(contact))
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, first))

        assertFalse(store.isTrustedIdentity(address, second, Direction.SENDING))
        assertTrue(store.isTrustedIdentity(address, second, Direction.RECEIVING))
        assertEquals(IdentityChange.REPLACED_EXISTING, store.saveIdentity(address, second))
        assertEquals(ContactIdentity.Changed(first, second), storage2.identityTrust(contact))
        assertFalse(store.isTrustedIdentity(address, second, Direction.SENDING))
        assertEquals(second, store.getIdentity(address))

        store.acceptIdentity(contact)
        assertTrue(store.isTrustedIdentity(address, second, Direction.SENDING))
        assertEquals(ContactIdentity.Trusted(second, false), storage2.identityTrust(contact))
    }

    @Test
    fun identitiesRestoredFromABackupKeepTheirTrust() {
        val key = newKey()
        store.restoreIdentity(contact, key, IdentityTrust.TRUSTED, verified = true)
        assertEquals(ContactIdentity.Trusted(key, true), storage2.identityTrust(contact))
        // Restored verified: no made-up date.
        assertNull(storage2.identityVerification(contact))
        assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, key))

        val other = newAccountId()
        store.restoreIdentity(other, key, IdentityTrust.CHANGED, verified = true)
        assertEquals(ContactIdentity.Changed(null, key), storage2.identityTrust(other))
        assertFalse(store.isTrustedIdentity(other.protocolAddress(), key, Direction.SENDING))
        assertTrue(store.isTrustedIdentity(other.protocolAddress(), key, Direction.RECEIVING))
        val newer = newKey()
        assertEquals(IdentityChange.REPLACED_EXISTING, store.saveIdentity(other.protocolAddress(), newer))
        assertEquals(ContactIdentity.Changed(null, newer), storage2.identityTrust(other))
        storage2.approveIdentity(newer, other)
        assertEquals(ContactIdentity.Trusted(newer, false), storage2.identityTrust(other))
    }

    @Test
    fun repeatedChangesBeforeApprovalKeepTheLastApprovedKeyAsPrevious() {
        val keys = List(3) { newKey() }
        for (key in keys) store.saveIdentity(address, key)
        assertEquals(ContactIdentity.Changed(keys[0], keys[2]), storage2.identityTrust(contact))
    }

    @Test
    fun verifyingInPersonApprovesTheCurrentKeyAndAChangeClearsVerification() {
        val first = newKey()
        val second = newKey()
        store.saveIdentity(address, first)
        storage2.setIdentityVerified(true, contact)
        assertEquals(ContactIdentity.Trusted(first, true), storage2.identityTrust(contact))
        store.saveIdentity(address, second)
        assertEquals(ContactIdentity.Changed(first, second), storage2.identityTrust(contact))
        storage2.setIdentityVerified(true, contact)
        assertEquals(ContactIdentity.Trusted(second, true), storage2.identityTrust(contact))
        assertTrue(store.isTrustedIdentity(address, second, Direction.SENDING))
    }

    @Test
    fun theCoresSetVerifiedRecordsWhenTheUserVerified() {
        val key = newKey()
        store.saveIdentity(address, key)
        store.setVerified(contact, true)
        assertEquals(IdentityVerificationMethod.MANUAL, storage2.identityVerification(contact)?.method)
        assertTrue(store.identity(contact)!!.verified)
        store.setVerified(contact, false)
        assertNull(storage2.identityVerification(contact))
    }

    @Test
    fun aContactCodePinsItsKeyWhenNoneIsStored() {
        val scanned = newKey()
        assertTrue(storage2.pinVerifiedIdentity(scanned, contact, byQr))
        assertEquals(ContactIdentity.Trusted(scanned, true), storage2.identityTrust(contact))
        assertEquals(byQr, storage2.identityVerification(contact))
        assertTrue(store.isTrustedIdentity(address, scanned, Direction.SENDING))
        // The relay's first bundle with another key is a change, not a first use.
        val substituted = newKey()
        assertFalse(store.isTrustedIdentity(address, substituted, Direction.SENDING))
        assertEquals(IdentityChange.REPLACED_EXISTING, store.saveIdentity(address, substituted))
        assertEquals(ContactIdentity.Changed(scanned, substituted), storage2.identityTrust(contact))
        assertNull(storage2.identityVerification(contact))
    }

    @Test
    fun pinningTheStoredKeyVerifiesAndApprovesIt() {
        val key = newKey()
        store.saveIdentity(address, key)
        assertEquals(ContactIdentity.Trusted(key, false), storage2.identityTrust(contact))
        assertTrue(storage2.pinVerifiedIdentity(key, contact, byQr))
        assertEquals(ContactIdentity.Trusted(key, true), storage2.identityTrust(contact))
        assertEquals(IdentityVerificationMethod.QR_CODE, storage2.identityVerification(contact)?.method)
    }

    @Test
    fun pinningOverAnotherStoredKeyChangesNothing() {
        val first = newKey()
        val stored = newKey()
        val scanned = newKey()
        store.saveIdentity(address, stored)
        assertFalse(storage2.pinVerifiedIdentity(scanned, contact, byQr))
        assertEquals(ContactIdentity.Trusted(stored, false), storage2.identityTrust(contact))
        // A pending change: the scanned key is neither the new nor the old one.
        val other = newAccountId()
        store.saveIdentity(other.protocolAddress(), first)
        store.saveIdentity(other.protocolAddress(), stored)
        assertFalse(storage2.pinVerifiedIdentity(scanned, other, byQr))
        assertFalse(storage2.pinVerifiedIdentity(first, other, byQr))
        // Nor the pending key itself: the approved one is still held (§5).
        assertFalse(storage2.pinVerifiedIdentity(stored, other, byQr))
        assertEquals(ContactIdentity.Changed(first, stored), storage2.identityTrust(other))
        assertNull(storage2.identityVerification(other))
        assertFalse(store.isTrustedIdentity(other.protocolAddress(), stored, Direction.SENDING))
    }

    @Test
    fun verifyingIsRefusedForAKeyOtherThanTheComparedOne() {
        val compared = newKey()
        val newer = newKey()
        assertFalse("nothing stored", storage2.verifyIdentity(compared, contact, byHand))
        store.saveIdentity(address, compared)
        // The key changes while the user compares the old number.
        store.saveIdentity(address, newer)
        assertFalse(storage2.verifyIdentity(compared, contact, byHand))
        assertFalse(storage2.acceptIdentity(compared, contact))
        assertEquals(ContactIdentity.Changed(compared, newer), storage2.identityTrust(contact))
        assertFalse(store.isTrustedIdentity(address, newer, Direction.SENDING))
        assertNull(storage2.identityVerification(contact))
        // The number of the current key: verified, which also accepts it.
        assertTrue(storage2.verifyIdentity(newer, contact, byHand))
        assertEquals(ContactIdentity.Trusted(newer, true), storage2.identityTrust(contact))
        assertEquals(byHand, storage2.identityVerification(contact))
        assertTrue(store.isTrustedIdentity(address, newer, Direction.SENDING))
    }

    @Test
    fun acceptingAChangeWithoutVerifyingApprovesOnlyTheShownKeyUnverified() {
        val old = newKey()
        val new = newKey()
        store.saveIdentity(address, old)
        assertTrue(storage2.verifyIdentity(old, contact, byQr))
        store.saveIdentity(address, new)
        assertTrue(storage2.acceptIdentity(new, contact))
        assertEquals(ContactIdentity.Trusted(new, false), storage2.identityTrust(contact))
        assertNull(storage2.identityVerification(contact))
        assertTrue(store.isTrustedIdentity(address, new, Direction.SENDING))
        assertTrue(storage2.acceptIdentity(new, contact))
        assertEquals(ContactIdentity.Trusted(new, false), storage2.identityTrust(contact))
    }

    @Test
    fun theRecordBelongsToTheVerifiedKey() {
        val key = newKey()
        store.saveIdentity(address, key)
        assertTrue(storage2.verifyIdentity(key, contact, byHand))
        storage2.approveIdentity(key, contact)
        assertEquals(byHand, storage2.identityVerification(contact))
        storage2.setIdentityVerified(false, contact)
        assertEquals(ContactIdentity.Trusted(key, false), storage2.identityTrust(contact))
        assertNull(storage2.identityVerification(contact))
        // Verified without a record (as by an earlier version): no made-up date.
        storage2.setIdentityVerified(true, contact)
        assertEquals(ContactIdentity.Trusted(key, true), storage2.identityTrust(contact))
        assertNull(storage2.identityVerification(contact))
    }

    @Test
    fun aChangeRemembersWhetherTheApprovedKeyWasVerifiedAcrossARelaunch() {
        val verified = newKey()
        val injected = newKey()
        val another = newKey()
        assertTrue(storage2.pinVerifiedIdentity(verified, contact, byQr))
        store.saveIdentity(address, injected)
        store.saveIdentity(address, another)
        reopen()
        assertEquals(ContactIdentity.Changed(verified, another), storage2.identityTrust(contact))
        val row = storage.read { tx ->
            tx.connection.queryOne(
                "SELECT previous_verified, previous_verified_at, previous_verified_method FROM identities WHERE account = ?",
                contact.toBytes(),
            )!!
        }
        assertTrue(row.bool("previous_verified"))
        assertEquals(verifiedAt.toEpochMilli(), row.long("previous_verified_at"))
        assertEquals(IdentityVerificationMethod.QR_CODE.raw.toLong(), row.long("previous_verified_method"))
    }

    @Test
    fun aReturnToTheApprovedKeyRestoresItsVerification() {
        // M-8 (PROTOCOL §5, iOS PersistentProtocolStore): a key injected once, then the real one again.
        val verified = newKey()
        val injected = newKey()
        assertTrue(storage2.pinVerifiedIdentity(verified, contact, byQr))
        store.saveIdentity(address, injected)
        assertEquals(ContactIdentity.Changed(verified, injected), storage2.identityTrust(contact))
        store.saveIdentity(address, verified)
        assertEquals(ContactIdentity.Trusted(verified, true), storage2.identityTrust(contact))
        assertEquals(byQr, storage2.identityVerification(contact))
        assertEquals(IdentityTrust.TRUSTED, store.identity(contact)?.trust)
        assertTrue(store.isTrustedIdentity(address, verified, Direction.SENDING))
        reopen()
        assertEquals(ContactIdentity.Trusted(verified, true), storage2.identityTrust(contact))
        assertEquals(byQr, storage2.identityVerification(contact))
    }

    @Test
    fun aReturnAfterSeveralChangesStillFindsTheApprovedKey() {
        val approved = newKey()
        store.saveIdentity(address, approved)
        store.saveIdentity(address, newKey())
        store.saveIdentity(address, newKey())
        store.saveIdentity(address, approved)
        // Approved, never verified: it comes back approved and unverified, with nothing to accept.
        assertEquals(ContactIdentity.Trusted(approved, false), storage2.identityTrust(contact))
        assertNull(storage2.identityVerification(contact))
    }

    @Test
    fun aReturnToTheApprovedKeyIsNotAnnouncedAsAChange() = runBlocking {
        val approved = newKey()
        store.saveIdentity(address, approved)
        val announced = mutableListOf<com.syconx.us2us.core.AccountId>()
        val watcher = launch(kotlinx.coroutines.Dispatchers.Unconfined) { store.identityChanges.collect { announced += it } }
        store.saveIdentity(address, newKey())
        store.saveIdentity(address, approved)
        kotlinx.coroutines.yield()
        watcher.cancel()
        assertEquals(listOf(contact), announced)
    }

    @Test
    fun whenAndHowAKeyWasVerifiedSurvivesARelaunch() {
        val key = newKey()
        assertTrue(storage2.pinVerifiedIdentity(key, contact, byQr))
        reopen()
        assertEquals(ContactIdentity.Trusted(key, true), storage2.identityTrust(contact))
        assertEquals(byQr, storage2.identityVerification(contact))
    }

    @Test
    fun identitiesVerifiedBeforeV4KeepTheirVerificationWithoutARecord() {
        storage.close()
        val other = TestEnvironment(temporary.newFolder())
        other.layout.prepare()
        val key = newKey()
        val first = newKey()
        val pending = newAccountId()
        val v3 = EncryptedDatabase.open(other.layout.databaseFile, other.dek.databaseKey(), Schema.migrations.take(3), PlainSqlDriver)
        v3.write { connection ->
            connection.run(
                "INSERT INTO identities (account, identity_key, previous_key, approved, verified) VALUES (?, ?, NULL, 1, 1)",
                contact.toBytes(),
                key.serialize(),
            )
            connection.run(
                "INSERT INTO identities (account, identity_key, previous_key, approved, verified) VALUES (?, ?, ?, 0, 0)",
                pending.toBytes(),
                key.serialize(),
                first.serialize(),
            )
        }
        v3.close()
        storage = other.open()
        val protocol = storage.protocolStorage
        assertEquals(ContactIdentity.Trusted(key, true), protocol.identityTrust(contact))
        assertNull(protocol.identityVerification(contact))
        assertEquals(ContactIdentity.Changed(first, key), protocol.identityTrust(pending))
        assertTrue(protocol.verifyIdentity(key, contact, byHand))
        assertEquals(byHand, protocol.identityVerification(contact))
    }

    @Test
    fun addressesNotBuiltFromAnAccountIdAreRefused() {
        try {
            storage2.loadSession("+14151111111", 1)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(storage2.loadSession(contact.toString(), 2))
        try {
            storage2.storeSession(contact.toString(), 2, ByteArray(1))
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    // MARK: Keys

    @Test
    fun oneTimeKyberKeysAreDeletedWhenUsedLastResortKeysTrackReuse() {
        val oneTime = List(2) { Keys.kyberPreKey(account.identityKeyPair, 10 + it, 0) }
        oneTime.forEach { store.storeKyberPreKey(it, lastResort = false) }
        val lastResort = Keys.kyberPreKey(account.identityKeyPair, 20, 0)
        store.storeKyberPreKey(lastResort, lastResort = true)
        val baseKey = ECKeyPair.generate().publicKey

        store.markKyberPreKeyUsed(oneTime[0].id, 1, baseKey)
        assertEquals(1, store.loadKyberPreKeys().count { !store.isLastResortKyberPreKey(it.id) })
        try {
            store.loadKyberPreKey(oneTime[0].id)
            fail("used one-time key still there")
        } catch (_: InvalidKeyIdException) {
        }

        store.markKyberPreKeyUsed(lastResort.id, 1, baseKey)
        try {
            store.markKyberPreKeyUsed(lastResort.id, 1, baseKey)
            fail("reuse accepted")
        } catch (_: ReusedBaseKeyException) {
        }
        store.markKyberPreKeyUsed(lastResort.id, 2, baseKey)
        store.markKyberPreKeyUsed(lastResort.id, 1, ECKeyPair.generate().publicKey)
        assertEquals(listOf(lastResort.id), store.lastResortKyberPreKeys().map { it.id })

        // Storing again keeps the last-resort flag.
        store.storeKyberPreKey(lastResort.id, store.loadKyberPreKey(lastResort.id))
        assertEquals(1, store.lastResortKyberPreKeys().size)
        store.removeKyberPreKey(lastResort.id)
        assertTrue(store.lastResortKyberPreKeys().isEmpty())
        // Its base-key records went with it.
        assertEquals(0L, storage.read { it.connection.scalar("SELECT count(*) FROM kyber_last_resort_uses") })
    }

    @Test
    fun keyIdCountersArePerSpaceAndPersist() {
        assertNull(store.keyIdCounters())
        store.storeKeyIdCounters(KeyIdCounters(5, 7, 0xFF_FFFF))
        reopen()
        assertEquals(KeyIdCounters(5, 7, 0xFF_FFFF), store.keyIdCounters())
        val spaces = storage.read { tx -> tx.connection.query("SELECT space FROM key_id_counters ORDER BY space").map { it.text("space") } }
        assertEquals(listOf("kyber", "oneTime", "signed"), spaces)
    }

    @Test
    fun generatedPreKeysAreStoredAndSigned() {
        val party = Party.on(storage, account)
        assertTrue(party.lastResortKyberId > 0)
        val identity = account.identityKeyPair.publicKey
        val signed = store.loadSignedPreKeys()
        assertEquals(1, signed.size)
        assertTrue(identity.publicKey.verifySignature(signed[0].keyPair.publicKey.serialize(), signed[0].signature))
        assertEquals(5, storage.read { (it.connection.scalar("SELECT count(*) FROM pre_keys") as Long).toInt() })
        assertEquals(1, store.lastResortKyberPreKeys().size)
        store.removeSignedPreKey(signed[0].id)
        assertTrue(store.loadSignedPreKeys().isEmpty())
    }

    // MARK: Sessions

    @Test
    fun forgettingAContactRemovesSessionAndIdentity() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        assertNotNull(store.loadSession(bob.accountId.protocolAddress()))
        store.forget(bob.accountId)
        assertNull(store.loadSession(bob.accountId.protocolAddress()))
        assertEquals(ContactIdentity.Unknown, storage2.identityTrust(bob.accountId))
    }

    @Test
    fun bundleThenPreKeyMessageThenRepliesOnTheRatchet() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        assertFalse(alice.sessions.hasUsableSession(bob.accountId))
        alice.sessions.startSession(bob.accountId, bob.bundle())
        val first = alice.encryptTo(bob, "Merhaba")
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertEquals("Merhaba", bob.decryptFrom(alice, first))
        val reply = bob.encryptTo(alice, "Selam")
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        assertEquals("Selam", alice.decryptFrom(bob, reply))
        val second = alice.encryptTo(bob, "Nasılsın?")
        assertEquals(CiphertextMessage.WHISPER_TYPE, second.type)
        assertEquals("Nasılsın?", bob.decryptFrom(alice, second))
    }

    @Test
    fun outOfOrderDeliveryDecryptsReplaysAreRejected() {
        val bob = Party.on(storage, account)
        val alice = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        bob.decryptFrom(alice, alice.encryptTo(bob, "hello"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "hi"))
        val messages = (1..3).map { alice.encryptTo(bob, "m$it") }
        assertEquals("m3", bob.decryptFrom(alice, messages[2]))
        assertEquals("m1", bob.decryptFrom(alice, messages[0]))
        assertEquals("m2", bob.decryptFrom(alice, messages[1]))
        try {
            bob.decryptFrom(alice, messages[1])
            fail("replay accepted")
        } catch (_: DuplicateMessageException) {
        }
    }

    @Test
    fun aUsedOneTimeKyberKeyAndEcPreKeyAreDeleted() {
        val bob = Party.on(storage, account)
        val alice = Party.inMemory()
        val bundle = bob.bundle()
        alice.sessions.startSession(bob.accountId, bundle)
        val first = alice.encryptTo(bob, "one")
        bob.decryptFrom(alice, first)
        assertNull(storage2.loadKyberPreKey(bundle.kyberPreKeyId))
        assertNull(storage2.loadPreKey(bundle.preKeyId))
        storage2.removeAllSessions(alice.accountId.toString())
        try {
            bob.decryptFrom(alice, first)
            fail("a used one-time key opened a second session")
        } catch (_: Exception) {
        }
    }

    @Test
    fun theLastResortKeyIsKeptButNeverAcceptedTwiceWithTheSameBaseKey() {
        val bob = Party.on(storage, account)
        val alice = Party.inMemory()
        val bundle = bob.bundle(withOneTimeEc = false, withOneTimeKyber = false)
        alice.sessions.startSession(bob.accountId, bundle)
        val first = alice.encryptTo(bob, "one")
        bob.decryptFrom(alice, first)
        assertNotNull(storage2.loadKyberPreKey(bundle.kyberPreKeyId))
        storage2.removeAllSessions(alice.accountId.toString())
        try {
            bob.decryptFrom(alice, first)
            fail("a replayed first message opened a second session")
        } catch (_: Exception) {
        }
        val carol = Party.inMemory()
        carol.sessions.startSession(bob.accountId, bundle)
        assertEquals("from carol", bob.decryptFrom(carol, carol.encryptTo(bob, "from carol")))
    }

    @Test
    fun aChangedIdentityBlocksSendingUntilApproved() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        bob.decryptFrom(alice, alice.encryptTo(bob, "hello"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "hi"))

        val reinstalledBob = Party(com.syconx.us2us.core.store.InMemoryProtocolStore(Keys.generateIdentityKeyPair(), Keys.generateRegistrationId()), bob.accountId)
        reinstalledBob.sessions.startSession(alice.accountId, alice.bundle())
        assertEquals("new phone", alice.decryptFrom(reinstalledBob, reinstalledBob.encryptTo(alice, "new phone")))
        assertEquals(IdentityTrust.CHANGED, store.identity(bob.accountId)?.trust)
        try {
            alice.encryptTo(reinstalledBob, "reply")
            fail("sent to a changed identity")
        } catch (_: UntrustedIdentityException) {
        }
        assertTrue(storage2.acceptIdentity(reinstalledBob.store.identityKeyPair.publicKey, bob.accountId))
        assertEquals("reply", reinstalledBob.decryptFrom(alice, alice.encryptTo(reinstalledBob, "reply")))
    }

    @Test
    fun aPinnedKeyBlocksASessionWithABundleUnderAnotherKey() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        val bobKey = bob.store.identityKeyPair.publicKey
        assertTrue(storage2.pinVerifiedIdentity(bobKey, bob.accountId, byQr))
        // The relay hands out its own keys for Bob.
        val attacker = Party(com.syconx.us2us.core.store.InMemoryProtocolStore(Keys.generateIdentityKeyPair(), Keys.generateRegistrationId()), bob.accountId)
        try {
            alice.sessions.startSession(bob.accountId, attacker.bundle())
            fail("a substituted bundle was accepted")
        } catch (_: UntrustedIdentityException) {
        }
        // The real bundle still works, verified as before.
        alice.sessions.startSession(bob.accountId, bob.bundle())
        assertEquals(ContactIdentity.Trusted(bobKey, true), storage2.identityTrust(bob.accountId))
        assertEquals(byQr, storage2.identityVerification(bob.accountId))
        assertEquals("hi", bob.decryptFrom(alice, alice.encryptTo(bob, "hi")))
    }

    @Test
    fun everythingSurvivesClosingAndReopeningTheDatabase() {
        var alice = Party.on(storage, account)
        val bob = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        bob.decryptFrom(alice, alice.encryptTo(bob, "one"))
        alice.decryptFrom(bob, bob.encryptTo(alice, "two"))
        store.setVerified(bob.accountId, true)
        val preKeys = storage.read { it.connection.scalar("SELECT count(*) FROM pre_keys") }
        reopen()
        val reloaded = storage.read { it.account.load()!! }
        assertArrayEquals(account.identityKeyPair.serialize(), reloaded.identityKeyPair.serialize())
        assertEquals(account.registrationId, reloaded.registrationId)
        assertEquals(preKeys, storage.read { it.connection.scalar("SELECT count(*) FROM pre_keys") })
        assertEquals(ContactIdentity.Trusted(bob.store.identityKeyPair.publicKey, true), storage2.identityTrust(bob.accountId))
        alice = Party(store, account.accountId)
        assertEquals("three", bob.decryptFrom(alice, alice.encryptTo(bob, "three")))
        assertEquals("four", alice.decryptFrom(bob, bob.encryptTo(alice, "four")))
    }

    @Test
    fun persistentAndInMemoryPeersInteroperate() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        bob.sessions.startSession(alice.accountId, alice.bundle())
        for (round in 0 until 20) {
            val (sender, receiver) = if (round % 2 == 0) bob to alice else alice to bob
            assertEquals("r$round", receiver.decryptFrom(sender, sender.encryptTo(receiver, "r$round")))
        }
    }

    @Test
    fun subDeviceSessionsDoNotExist() {
        val alice = Party.on(storage, account)
        val bob = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        assertEquals(listOf(AccountId.DEVICE_ID), storage2.sessionDeviceIds(bob.accountId.toString()))
        assertTrue(store.getSubDeviceSessions(bob.accountId.toString()).isEmpty())
        assertTrue(store.containsSession(SignalProtocolAddress(bob.accountId.toString(), AccountId.DEVICE_ID)))
    }
}
