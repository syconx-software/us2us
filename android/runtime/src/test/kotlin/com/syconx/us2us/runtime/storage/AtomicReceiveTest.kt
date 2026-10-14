package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.messenger.LocalAccount
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.store.ProtocolStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.message.CiphertextMessage
import kotlin.concurrent.thread

/**
 * The ratchet advance of a decryption and the stored message commit in one transaction, so a crash
 * in between can never lose a message (the iOS AtomicReceiveTests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomicReceiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(
        val environment: TestEnvironment,
        var storage: LocalStorage,
        val account: LocalAccount,
        val bob: Party,
        val alice: Party,
        val conversation: Conversation,
    ) {
        val protocol: SqlProtocolStorage get() = storage.protocolStorage

        /** Bob's state after a relaunch on [storage]. */
        fun reopened(): Party {
            storage = environment.open()
            return Party(ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage), account.accountId)
        }
    }

    private fun fixture(): Fixture {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open()
        val account = storage.saveAccount()
        val bob = Party.on(storage, account)
        val alice = Party.inMemory()
        alice.sessions.startSession(bob.accountId, bob.bundle())
        val (_, conversation) = storage.contactWithConversation(alice.accountId)
        return Fixture(environment, storage, account, bob, alice, conversation)
    }

    private fun Fixture.receive(message: CiphertextMessage): Content =
        bob.sessions.decrypt(alice.accountId, message.type, message.serialize())

    private fun Fixture.store(content: Content, relayId: String) {
        storage.write { tx ->
            tx.messages.storeIncoming(content.data, conversation.id)
            tx.envelopes.record(relayId)
        }
    }

    /** Runs [block] on another thread, outside the caller's staging scope (another coroutine of the app). */
    private fun <T> elsewhere(block: () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    private fun preKeyCount(storage: LocalStorage): Long = storage.read { it.connection.scalar("SELECT count(*) FROM pre_keys") as Long }

    @Test
    fun aCrashAfterDecryptingButBeforeStoringLosesNothing() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "önemli")
        val preKeysBefore = preKeyCount(f.storage)
        // Decrypt inside the scope, then "crash" (the storage closes without committing anything).
        runCatching {
            runBlocking {
                f.protocol.withStagedInboundWrites {
                    assertEquals("önemli", f.receive(envelope).data.text.body)
                    assertTrue(f.protocol.hasStagedWrites)
                    f.storage.close()
                }
            }
        }
        val bob = f.reopened()
        assertNull(f.protocol.loadSession(f.alice.accountId.toString(), 1))
        assertEquals(preKeysBefore, preKeyCount(f.storage))
        // The relay redelivers the unacknowledged envelope: it still decrypts.
        assertEquals("önemli", bob.decryptFrom(f.alice, envelope))
        f.storage.close()
    }

    @Test
    fun storingTheMessageCommitsTheSessionAdvanceInTheSameTransaction() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "birlikte")
        runBlocking {
            f.protocol.withStagedInboundWrites {
                val content = f.receive(envelope)
                // Not durable yet.
                assertTrue(f.protocol.hasStagedWrites)
                f.store(content, "r1")
                assertFalse(f.protocol.hasStagedWrites)
            }
        }
        f.storage.close()
        val bob = f.reopened()
        assertNotNull(f.protocol.loadSession(f.alice.accountId.toString(), 1))
        val messages = f.storage.read { it.messages.page(f.conversation.id) }
        assertEquals(listOf("birlikte"), messages.map { it.content?.text?.body })
        assertTrue(f.storage.read { it.envelopes.contains("r1") })
        // The same envelope is now a duplicate for libsignal, and known to the envelope log.
        try {
            bob.decryptFrom(f.alice, envelope)
            fail("decrypted twice")
        } catch (_: Exception) {
        }
        f.storage.close()
    }

    @Test
    fun aFailedStoreRollsBackBothTheMessageAndTheSessionAdvance() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "x")
        runBlocking {
            f.protocol.withStagedInboundWrites {
                f.receive(envelope)
                try {
                    f.storage.write { tx ->
                        tx.envelopes.record("r1")
                        error("boom")
                    }
                    fail("expected the failure")
                } catch (_: IllegalStateException) {
                }
                // Still staged (not lost, not committed): discard it for the redelivery.
                assertTrue(f.protocol.hasStagedWrites)
                assertTrue(f.protocol.discardStagedInboundWrites())
            }
        }
        assertEquals("x", f.bob.decryptFrom(f.alice, envelope))
        assertFalse(f.storage.read { it.envelopes.contains("r1") })
        f.storage.close()
    }

    @Test
    fun aWriteFromOutsideTheScopeCommitsStagedStateFirst() {
        val f = fixture()
        // An established session in both directions.
        f.bob.decryptFrom(f.alice, f.alice.encryptTo(f.bob, "a"))
        f.alice.decryptFrom(f.bob, f.bob.encryptTo(f.alice, "b"))
        val envelope = f.alice.encryptTo(f.bob, "c")
        runBlocking {
            f.protocol.withStagedInboundWrites {
                f.receive(envelope)
                assertTrue(f.protocol.hasStagedWrites)
                // A concurrent send elsewhere advances the same session.
                val reply = elsewhere { f.bob.encryptTo(f.alice, "reply") }
                assertFalse(f.protocol.hasStagedWrites)
                assertEquals("reply", f.alice.decryptFrom(f.bob, reply))
                // Already committed by the send: nothing is left to discard.
                assertTrue(f.protocol.discardStagedInboundWrites())
            }
        }
        f.storage.close()
        val bob = f.reopened()
        // The persisted session contains both the receive and the send.
        assertEquals("after", bob.decryptFrom(f.alice, f.alice.encryptTo(bob, "after")))
        assertEquals("after2", f.alice.decryptFrom(bob, bob.encryptTo(f.alice, "after2")))
        f.storage.close()
    }

    @Test
    fun aSendInsideTheReceiveLoopRunsOutsideTheScope() {
        val f = fixture()
        f.bob.decryptFrom(f.alice, f.alice.encryptTo(f.bob, "a"))
        f.alice.decryptFrom(f.bob, f.bob.encryptTo(f.alice, "b"))
        val envelope = f.alice.encryptTo(f.bob, "c")
        runBlocking {
            f.protocol.withStagedInboundWrites {
                f.store(f.receive(envelope), "r1")
                // A receipt sent from the loop: its chain step is committed at once.
                f.protocol.withoutStaging { f.bob.encryptTo(f.alice, "receipt") }
                assertFalse(f.protocol.hasStagedWrites)
            }
        }
    }

    @Test
    fun aProtocolWriteNestedInAnotherTransactionCommitsStagedStateFirst() {
        val f = fixture()
        f.bob.decryptFrom(f.alice, f.alice.encryptTo(f.bob, "a"))
        f.alice.decryptFrom(f.bob, f.bob.encryptTo(f.alice, "b"))
        val envelope = f.alice.encryptTo(f.bob, "c")
        var replies: List<CiphertextMessage> = emptyList()
        runBlocking {
            f.protocol.withStagedInboundWrites {
                f.receive(envelope)
                // Another coroutine encrypts inside its own storage transaction (savepoints).
                replies = elsewhere {
                    f.storage.write { tx ->
                        tx.envelopes.record("other-work")
                        listOf(f.bob.encryptTo(f.alice, "r1"), f.bob.encryptTo(f.alice, "r2"))
                    }
                }
                assertFalse(f.protocol.hasStagedWrites)
            }
        }
        f.storage.close()
        val bob = f.reopened()
        for (reply in replies) f.alice.decryptFrom(f.bob, reply)
        // Had the staged (older) session overwritten the sealed one, this would reuse r1's chain key.
        assertEquals("r3", f.alice.decryptFrom(bob, bob.encryptTo(f.alice, "r3")))
        f.storage.close()
    }

    @Test
    fun stagedStateReadOutsideTheScopeCanNoLongerBeDiscarded() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "seen")
        runBlocking {
            f.protocol.withStagedInboundWrites {
                f.receive(envelope)
                assertNotNull(elsewhere { f.protocol.loadSession(f.alice.accountId.toString(), 1) })
                assertFalse(f.protocol.discardStagedInboundWrites())
                // Committed instead.
                assertFalse(f.protocol.hasStagedWrites)
            }
        }
        f.storage.close()
    }

    @Test
    fun stagingIsPerStorage() {
        val f = fixture()
        val other = TestEnvironment(temporary.newFolder())
        val otherStorage = other.open()
        val carol = Party.on(otherStorage, otherStorage.saveAccount(newAccount(username = "carol")))
        runBlocking {
            f.protocol.withStagedInboundWrites {
                carol.sessions.startSession(f.alice.accountId, f.alice.bundle())
                assertFalse(f.protocol.hasStagedWrites)
                assertFalse(otherStorage.protocolStorage.hasStagedWrites)
                assertNotNull(elsewhere { otherStorage.protocolStorage.loadSession(f.alice.accountId.toString(), 1) })
            }
        }
        otherStorage.close()
        f.storage.close()
    }

    @Test
    fun whateverIsStillStagedWhenTheScopeEndsIsCommitted() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "late")
        runBlocking { f.protocol.withStagedInboundWrites { f.receive(envelope) } }
        assertFalse(f.protocol.hasStagedWrites)
        f.storage.close()
        f.reopened()
        assertNotNull(f.protocol.loadSession(f.alice.accountId.toString(), 1))
        f.storage.close()
    }

    @Test
    fun theCoreContactStoreDoesNotCommitTheRatchetAheadOfTheMessage() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "contact first")
        runCatching {
            runBlocking {
                f.protocol.withStagedInboundWrites {
                    f.receive(envelope)
                    // What the core records about the sender before handing the message over.
                    val contact = f.storage.contactStore.get(f.alice.accountId)!!
                    f.storage.contactStore.put(contact.copy(displayName = "Alice"))
                    assertTrue(f.protocol.hasStagedWrites)
                    f.storage.close()
                }
            }
        }
        val bob = f.reopened()
        assertEquals("Alice", f.storage.contactStore.get(f.alice.accountId)?.displayName)
        // The redelivery decrypts again and repeats the (idempotent) contact update.
        assertEquals("contact first", bob.decryptFrom(f.alice, envelope))
        f.storage.close()
    }

    @Test
    fun aDuplicateIsRejectedAfterTheCommit() {
        val f = fixture()
        val envelope = f.alice.encryptTo(f.bob, "once")
        runBlocking { f.protocol.withStagedInboundWrites { f.store(f.receive(envelope), "r1") } }
        try {
            f.bob.decryptFrom(f.alice, envelope)
            fail("decrypted twice")
        } catch (_: DuplicateMessageException) {
        } catch (_: Exception) {
        }
        f.storage.close()
    }
}
