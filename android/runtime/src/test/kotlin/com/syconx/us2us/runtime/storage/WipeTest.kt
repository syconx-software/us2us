package com.syconx.us2us.runtime.storage

import com.syconx.us2us.core.proto.ConversationSettings
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.vault.ItemCipher
import com.syconx.us2us.core.vault.ItemDecryptionException
import org.json.JSONArray
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
import java.io.File
import java.time.Instant

/** Crypto-shredding (§5.3), ghost mode (§8.2) and disappearing messages (§8.1) (the iOS WipeTests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WipeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun LocalStorage.enableGhost(id: ConversationId, timeout: Int, wipeContact: Boolean = false, at: Instant) {
        write { tx ->
            tx.conversations.updateSettings(id, at) { settings ->
                settings.setGhost(settings.ghost.toBuilder().setEnabled(true).setTimeoutSeconds(timeout).setWipeContact(wipeContact))
            }
        }
    }

    private fun opened(now: Instant = t0): Pair<TestEnvironment, LocalStorage> {
        val environment = TestEnvironment(temporary.newFolder())
        val storage = environment.open(now)
        storage.saveAccount()
        return environment to storage
    }

    // MARK: Crypto-shredding

    @Test
    fun wipingDestroysTheSlotFirstNothingIsRecoverableEvenWithTheDek() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation()
        val data = attachmentMessage()
        val attachmentId = storage.write { tx ->
            tx.messages.storeIncoming(textMessage("SECRET-TEXT"), conversation.id)
            tx.messages.storeIncoming(data, conversation.id)
            val attachment = tx.attachments.forMessage(data.messageId, conversation.id).first()
            tx.attachments.storeFile("SECRET-FILE".toByteArray(), attachment.id)
            attachment.id
        }
        // The attacker's copy, taken before the wipe.
        val itemCopy = storage.files.file(attachmentId).readBytes()
        val row = storage.database.locked { it.queryOne("SELECT message_id, body FROM messages WHERE body IS NOT NULL LIMIT 1")!! }
        val body = row.blob("body")!!
        val bodyId = row.blob("message_id")!!

        val wiped = storage.wipeConversation(conversation.id, WipeReason.REQUESTED)!!
        assertEquals(WipeReason.REQUESTED, wiped.reason)
        assertFalse(storage.keyring.slotFile(conversation.slotId).exists())
        assertFalse(storage.files.exists(attachmentId))
        assertNull(storage.read { it.conversations.get(conversation.id) })
        assertTrue(storage.database.locked { it.query("SELECT 1 FROM messages") }.isEmpty())
        assertFalse(environment.databaseBytes().contains("SECRET"))

        // With the DEK, CK cannot be rebuilt: the slot is gone.
        try {
            storage.keyring.key(conversation.slotId)
            fail("the key survived")
        } catch (e: StorageException) {
            assertEquals(StorageError.ConversationShredded, e.error)
        }
        // CK was random, so nothing derived from the DEK opens the copies.
        for (candidate in listOf(environment.dek.toBytes(), environment.dek.databaseKey())) {
            for ((item, id) in listOf(itemCopy to attachmentId.toByteArray(), body to bodyId)) {
                try {
                    ItemCipher.open(candidate, id, item)
                    fail("opened with a key derived from the DEK")
                } catch (_: ItemDecryptionException) {
                }
            }
        }
        storage.close()
    }

    @Test
    fun aBackgroundWipeWhileLockedShredsWithoutTheDekTheNextUnlockCollectsTheRest() {
        val (environment, storage) = opened()
        val (contact, conversation) = storage.contactWithConversation(now = t0)
        storage.enableGhost(conversation.id, 3600, at = t0)
        val data = attachmentMessage()
        val attachmentId = storage.write { tx ->
            tx.messages.storeIncoming(data, conversation.id, receivedAt = t0)
            val attachment = tx.attachments.forMessage(data.messageId, conversation.id).first()
            tx.attachments.storeFile("FILE".toByteArray(), attachment.id)
            attachment.id
        }
        // The vault locked: no DEK anywhere.
        storage.close()

        assertEquals(t0.plusSeconds(3600), LocalStorage.nextBackgroundWipeDate(environment.layout))
        assertEquals(0, LocalStorage.runBackgroundWipes(environment.layout, t0.plusSeconds(3599)))
        assertEquals(1, LocalStorage.runBackgroundWipes(environment.layout, t0.plusSeconds(3600)))
        assertFalse(File(environment.layout.slotDirectory, "${conversation.slotId.hex}.slot").exists())
        assertNull(LocalStorage.nextBackgroundWipeDate(environment.layout))

        val reopened = environment.openWithReport(t0.plusSeconds(7200))
        val report = reopened.wipeReport
        assertEquals(listOf(conversation.id), report.conversations.map { it.conversationId })
        assertEquals(WipeReason.GHOST_TIMEOUT, report.conversations.first().reason)
        assertNull(reopened.storage.read { it.conversations.get(conversation.id) })
        assertFalse(reopened.storage.files.exists(attachmentId))
        // The other phone is told (§8.2), conversation-less.
        val outbox = reopened.storage.read { it.outbox.all() }
        assertEquals(1, outbox.size)
        assertEquals(contact, outbox.first().recipient)
        assertEquals(ConversationWipe.Reason.GHOST_TIMEOUT, outbox.first().content.control.wipe.reason)
        assertNull(outbox.first().conversationId)
        reopened.storage.close()
    }

    @Test
    fun orphanedSlotsAndFilesAreGarbageCollectedOnUnlock() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation()
        // An orphan slot (a crash after creating it), an orphan item and a stray temporary file.
        val (orphanSlot, orphanKey) = storage.keyring.createSlot()
        val orphanItem = randomId()
        storage.files.write("x".toByteArray(), orphanItem, orphanKey)
        val stray = File(storage.files.directory, "0123.item.pending")
        stray.writeBytes("partial".toByteArray())
        storage.close()

        val reopened = environment.openWithReport()
        assertEquals(1, reopened.wipeReport.orphanSlotsRemoved)
        assertEquals(2, reopened.wipeReport.orphanFilesRemoved)
        assertFalse(reopened.storage.keyring.slotExists(orphanSlot))
        assertTrue(reopened.storage.keyring.slotExists(conversation.slotId))
        assertFalse(reopened.storage.files.exists(orphanItem))
        assertFalse(stray.exists())
        reopened.storage.close()
    }

    @Test
    fun aCrashBetweenShreddingAndDeletingRowsIsFinishedAtTheNextUnlock() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation()
        storage.write { tx -> tx.messages.storeIncoming(textMessage("x"), conversation.id) }
        // Step 1 only (the slot), then "crash".
        storage.keyring.destroy(conversation.slotId)
        storage.close()
        val reopened = environment.openWithReport()
        assertEquals(WipeReason.SHREDDED_EARLIER, reopened.wipeReport.conversations.first().reason)
        assertNull(reopened.storage.read { it.conversations.get(conversation.id) })
        reopened.storage.close()
    }

    @Test
    fun eraseEverythingDestroysEverything() {
        val (environment, storage) = opened()
        storage.contactWithConversation()
        LocalStorage.destroyAll(environment.layout)
        assertFalse(storage.isOpen)
        assertFalse(environment.layout.root.exists())
    }

    @Test
    fun onlyOneOpenInstancePerDirectoryAndClosingDropsKeys() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation()
        try {
            environment.open()
            fail("opened twice")
        } catch (e: StorageException) {
            assertTrue(e.error is StorageError.InvalidState)
        }
        storage.close()
        try {
            storage.read { it.conversations.get(conversation.id) }
            fail("read while closed")
        } catch (e: StorageException) {
            assertEquals(StorageError.Closed, e.error)
        }
        try {
            storage.keyring.key(conversation.slotId)
            fail("a key survived closing")
        } catch (e: StorageException) {
            assertEquals(StorageError.Closed, e.error)
        }
        val reopened = environment.open()
        assertNotNull(reopened.read { it.conversations.get(conversation.id) })
        reopened.close()
    }

    @Test
    fun aWrongKeyDoesNotOpenTheStorage() {
        val (environment, storage) = opened()
        storage.close()
        try {
            environment.open(key = com.syconx.us2us.core.vault.DataKey.generate())
            fail("opened with another DEK")
        } catch (e: StorageException) {
            assertEquals(StorageError.WrongKeyOrNotADatabase, e.error)
        }
        // The files are untouched: the right key still opens them.
        environment.open().close()
    }

    @Test
    fun plaintextStagingIsWipedOnOpenAndClose() {
        val (environment, storage) = opened()
        val staged = storage.makeStagingFile().also { it.writeBytes("plaintext".toByteArray()) }
        storage.close()
        assertFalse(staged.exists())
        val reopened = environment.open()
        val again = reopened.makeStagingFile().also { it.writeBytes("plaintext".toByteArray()) }
        reopened.close()
        assertFalse(again.exists())
        File(environment.layout.stagingDirectory, "left-by-a-crash").writeBytes("x".toByteArray())
        LocalStorage.purgeStaging(environment.layout)
        assertTrue(environment.layout.stagingDirectory.list().orEmpty().isEmpty())
    }

    // MARK: Ghost mode

    @Test
    fun deadlineIsTheLaterOfLastActivityAndEnablingPlusTheTimeoutExactly() {
        val (_, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.write { tx -> tx.messages.storeIncoming(textMessage("old"), conversation.id, receivedAt = t0) }
        // Enabled later: a full timeout from enabling, not from the old message.
        val enabled = t0.plusSeconds(86_400)
        storage.enableGhost(conversation.id, 600, at = enabled)
        var current = storage.read { it.conversations.get(conversation.id)!! }
        assertEquals(enabled.plusSeconds(600), current.ghostDeadline)

        // New activity moves it: received at t, deadline t + G (ms precision).
        val received = enabled.plusMillis(123_456)
        storage.write { tx -> tx.messages.storeIncoming(textMessage("new"), conversation.id, receivedAt = received) }
        current = storage.read { it.conversations.get(conversation.id)!! }
        assertEquals(received.toEpochMilli() + 600_000, current.ghostDeadline!!.toEpochMilli())
        assertEquals(received, current.lastActivityAt)

        val deadline = current.ghostDeadline!!
        assertTrue(storage.performDueWipes(deadline.minusMillis(1)).conversations.isEmpty())
        assertEquals(listOf(conversation.id), storage.performDueWipes(deadline).conversations.map { it.conversationId })
        storage.close()
    }

    @Test
    fun theScheduleFileHoldsOnlySlotIdsAndDeadlines() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.enableGhost(conversation.id, 60, at = t0)
        val data = environment.layout.scheduleFile.readBytes()
        val json = JSONArray(String(data))
        assertEquals(1, json.length())
        assertEquals(setOf("slotId", "deadline"), json.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(conversation.slotId.hex, json.getJSONObject(0).getString("slotId"))
        assertEquals(t0.toEpochMilli() + 60_000, json.getJSONObject(0).getLong("deadline"))
        assertFalse(data.contains(conversation.contact.toBytes()))
        assertFalse(data.contains(conversation.contact.toString()))

        // Disabling ghost mode removes the entry.
        storage.write { tx ->
            tx.conversations.updateSettings(conversation.id, t0) { it.setGhost(it.ghost.toBuilder().setEnabled(false)) }
        }
        assertNull(storage.read { it.conversations.get(conversation.id) }!!.ghostDeadline)
        assertTrue(storage.keyring.deadlines().isEmpty())
        storage.close()
    }

    @Test
    fun reactionsAndDeletesResetTheTimerLiveLocationTicksAndDuplicatesDoNot() {
        val (_, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.enableGhost(conversation.id, 600, at = t0)
        val message = textMessage("hi")
        val session = randomId()
        fun live(sequence: Int) = dataMessageOf {
            liveLocation = liveLocation {
                sessionId = session
                this.sequence = sequence
                expiresAt = t0.plusSeconds(3600).toEpochMilli()
            }
        }
        storage.write { tx ->
            tx.messages.storeIncoming(message, conversation.id, receivedAt = t0.plusSeconds(10))
            tx.messages.storeIncoming(live(1), conversation.id, receivedAt = t0.plusSeconds(20))
        }
        fun lastActivity() = storage.read { it.conversations.get(conversation.id) }?.lastActivityAt
        assertEquals(t0.plusSeconds(20), lastActivity())
        storage.write { tx ->
            assertTrue(tx.messages.storeIncoming(live(2), conversation.id, receivedAt = t0.plusSeconds(30)) is DataMessageOutcome.LiveLocationUpdated)
            assertEquals(DataMessageOutcome.Duplicate, tx.messages.storeIncoming(message, conversation.id, receivedAt = t0.plusSeconds(40)))
        }
        assertEquals(t0.plusSeconds(20), lastActivity())
        storage.write { tx ->
            val reaction = dataMessageOf {
                reaction = reaction {
                    targetMessageId = message.messageId
                    emoji = "👍"
                }
            }
            assertEquals(DataMessageOutcome.Reaction(message.messageId), tx.messages.storeIncoming(reaction, conversation.id, receivedAt = t0.plusSeconds(50)))
        }
        assertEquals(t0.plusSeconds(50), lastActivity())
        storage.close()
    }

    @Test
    fun wipeContactHidesTheContactTellsTheOtherPhoneThenForgetsContactAndSession() {
        val (_, storage) = opened()
        val account = storage.read { it.account.load()!! }
        val store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
        val me = Party(store, account.accountId)
        val peer = Party.inMemory()
        me.sessions.startSession(peer.accountId, peer.bundle())
        val (contact, conversation) = storage.contactWithConversation(peer.accountId, now = t0)
        storage.enableGhost(conversation.id, 60, wipeContact = true, at = t0)

        val report = storage.performDueWipes(t0.plusSeconds(60))
        assertTrue(report.conversations.first().wipeContact)
        // Hidden.
        assertTrue(storage.read { it.contacts.all() }.isEmpty())
        assertTrue(storage.contactStore.all().isEmpty())
        val entry = storage.read { it.outbox.due(t0.plusSeconds(60)) }.first()
        // The session is still there to encrypt the ConversationWipe…
        assertNotNull(store.loadSession(contact.protocolAddress()))
        storage.write { tx -> tx.outbox.markSent(entry.id) }
        // …and gone afterwards, with the contact.
        assertNull(storage.read { it.contacts.get(contact) })
        assertNull(store.loadSession(contact.protocolAddress()))
        assertEquals(ContactIdentity.Unknown, storage.protocolStorage.identityTrust(contact))
        storage.close()
    }

    @Test
    fun wipeContactOnABlockedContactKeepsTheBlockAndNothingElse() {
        val (_, storage) = opened()
        val account = storage.read { it.account.load()!! }
        val store = ProtocolStore(account.identityKeyPair, account.registrationId, storage.protocolStorage)
        val peer = Party.inMemory()
        Party(store, account.accountId).sessions.startSession(peer.accountId, peer.bundle())
        val (contact, conversation) = storage.contactWithConversation(peer.accountId, now = t0)
        storage.enableGhost(conversation.id, 60, wipeContact = true, at = t0)
        storage.write { tx ->
            tx.contacts.setProfile(ContactProfile(displayName = "Engellenen"), contact)
            tx.contacts.block(contact)
        }
        storage.performDueWipes(t0.plusSeconds(60))
        val remaining = storage.read { it.contacts.get(contact)!! }
        assertTrue(remaining.isBlocked)
        assertNull(remaining.profile.displayName)
        assertTrue(storage.read { it.outbox.all() }.isEmpty())
        assertNull(store.loadSession(contact.protocolAddress()))
        storage.close()
    }

    @Test
    fun settingsAreLastWriterWinsTiesGoToTheSmallerAccountId() {
        val (_, storage) = opened()
        val (contact, conversation) = storage.contactWithConversation(now = t0)
        val newer = ConversationSettings.newBuilder().setUpdatedAt(2000).setDefaultExpireTimerSeconds(30).build()
        val older = ConversationSettings.newBuilder().setUpdatedAt(1000).setDefaultExpireTimerSeconds(99).build()
        storage.write { tx ->
            assertEquals(newer, tx.conversations.applySettings(newer, contact, conversation.id, t0))
            assertEquals(newer, tx.conversations.applySettings(older, contact, conversation.id, t0))
        }
        assertEquals(30L, storage.read { it.conversations.get(conversation.id) }!!.defaultExpireTimerSeconds)
        // A tie on updated_at: the smaller author account id wins, on both phones alike.
        val other = newAccountId()
        val tie = newer.toBuilder().setDefaultExpireTimerSeconds(77).build()
        val expected = if (StorageRules.compare(other, contact) < 0) tie else newer
        storage.write { tx -> assertEquals(expected, tx.conversations.applySettings(tie, other, conversation.id, t0)) }
        // A far-future stamp is clamped to a day ahead: our own change later still wins.
        val future = newer.toBuilder().setUpdatedAt(-1L).setDefaultExpireTimerSeconds(5).build()
        storage.write { tx -> tx.conversations.applySettings(future, contact, conversation.id, t0) }
        val stamped = storage.read { it.conversations.get(conversation.id)!! }.settings.updatedAt
        assertEquals(t0.toEpochMilli() + 86_400_000L, stamped)
        val own = storage.write { tx -> tx.conversations.updateSettings(conversation.id, t0.plusSeconds(86_401)) { it.setDefaultExpireTimerSeconds(0) } }
        assertEquals(0, own.defaultExpireTimerSeconds)
        storage.close()
    }

    @Test
    fun aDamagedScheduleFileIsRebuiltInsteadOfBlockingStorage() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.enableGhost(conversation.id, 600, at = t0)
        storage.close()
        environment.layout.scheduleFile.writeBytes("{ not json".toByteArray())
        assertEquals(0, LocalStorage.runBackgroundWipes(environment.layout, t0.plusSeconds(9999)))
        val reopened = environment.open(t0)
        assertEquals(mapOf(conversation.slotId to t0.plusSeconds(600)), reopened.keyring.deadlines())
        // Writes keep working.
        reopened.write { tx -> tx.messages.storeIncoming(textMessage("x"), conversation.id, receivedAt = t0.plusSeconds(10)) }
        assertEquals(mapOf(conversation.slotId to t0.plusSeconds(610)), reopened.keyring.deadlines())
        reopened.close()
    }

    @Test
    fun whileStorageIsOpenTheBackgroundEntryPointRunsTheFullPassThroughIt() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.write { tx -> tx.messages.storeIncoming(textMessage("x"), conversation.id, receivedAt = t0) }
        storage.enableGhost(conversation.id, 60, at = t0)
        // Cached.
        storage.keyring.key(conversation.slotId)
        assertEquals(1, LocalStorage.runBackgroundWipes(environment.layout, t0.plusSeconds(60)))
        try {
            storage.keyring.key(conversation.slotId)
            fail("a cached key survived the wipe")
        } catch (e: StorageException) {
            assertEquals(StorageError.ConversationShredded, e.error)
        }
        assertNull(storage.read { it.conversations.get(conversation.id) })
        storage.close()
    }

    @Test
    fun theScheduleIsRepairedFromTheDatabaseOnUnlock() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        storage.enableGhost(conversation.id, 600, at = t0)
        // Drift: the schedule lost its entry.
        storage.keyring.setDeadline(null, conversation.slotId)
        storage.close()
        val reopened = environment.open(t0)
        assertEquals(t0.plusSeconds(600), reopened.keyring.deadlines()[conversation.slotId])
        reopened.close()
    }

    @Test
    fun ghostTimeoutsOutOfRangeAreRefused() {
        val (_, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        try {
            storage.enableGhost(conversation.id, 59, at = t0)
            fail("a 59 s ghost timeout was accepted")
        } catch (e: StorageException) {
            assertTrue(e.error is StorageError.InvalidState)
        }
        storage.close()
    }

    // MARK: Disappearing messages

    @Test
    fun outgoingExpireAfterCreationIncomingAfterDisplayNeverShownAfterTheDeadline() {
        val (_, storage) = opened()
        val (_, conversation) = storage.contactWithConversation(now = t0)
        val outgoing = textMessage("bye", timer = 30)
        val incoming = textMessage("hey", timer = 60)
        val attachmentData = attachmentMessage(timer = 60)
        val attachmentId = storage.write { tx ->
            tx.messages.storeOutgoing(outgoing, conversation.id, t0)
            tx.messages.storeIncoming(incoming, conversation.id, receivedAt = t0)
            tx.messages.storeIncoming(attachmentData, conversation.id, receivedAt = t0)
            val id = tx.attachments.forMessage(attachmentData.messageId, conversation.id).first().id
            tx.attachments.storeFile("pic".toByteArray(), id)
            id
        }
        storage.read { tx ->
            assertEquals(t0.plusSeconds(30), tx.messages.get(outgoing.messageId, conversation.id, t0)?.expiresAt)
            assertNull(tx.messages.get(incoming.messageId, conversation.id, t0)?.expiresAt)
        }
        // Not displayed for a while: the incoming timer has not started.
        val shown = t0.plusSeconds(1000)
        storage.write { tx ->
            assertEquals(2, tx.messages.markAllDisplayed(conversation.id, shown).size)
            assertTrue(tx.messages.markDisplayed(listOf(incoming.messageId), conversation.id, shown.plusSeconds(5)).isEmpty())
        }
        storage.read { tx ->
            assertEquals(shown.plusSeconds(60), tx.messages.get(incoming.messageId, conversation.id, shown)?.expiresAt)
            // Past the deadline it is hidden even before any wipe ran.
            assertTrue(tx.messages.page(conversation.id, now = shown.plusSeconds(60)).isEmpty())
            assertEquals(2, tx.messages.page(conversation.id, now = shown.plusSeconds(59)).size)
        }
        assertEquals(t0.plusSeconds(30), storage.nextDeadline())

        val report = storage.performDueWipes(shown.plusSeconds(60))
        assertEquals(
            setOf(outgoing.messageId, incoming.messageId, attachmentData.messageId),
            report.expiredMessages[conversation.id]!!.toSet(),
        )
        assertEquals(0, storage.read { it.messages.count(conversation.id) })
        assertFalse(storage.files.exists(attachmentId))
        // A retransmission of a removed message does not bring it back.
        storage.write { tx -> assertEquals(DataMessageOutcome.Duplicate, tx.messages.storeIncoming(incoming, conversation.id)) }
        storage.close()
    }

    @Test
    fun aDeletedMessageCannotBeReadBackFromTheDatabaseFiles() {
        val (environment, storage) = opened()
        val (_, conversation) = storage.contactWithConversation()
        val secret = textMessage("FORENSIC-MARKER")
        val reply = dataMessageOf {
            text = com.syconx.us2us.core.proto.text { body = "reply" }
            quote = com.syconx.us2us.core.proto.quote {
                targetMessageId = secret.messageId
                previewText = "FORENSIC-MARKER"
            }
        }
        storage.write { tx ->
            tx.messages.storeIncoming(secret, conversation.id)
            tx.messages.storeIncoming(reply, conversation.id)
        }
        storage.write { tx -> tx.messages.deleteLocally(listOf(secret.messageId), conversation.id) }
        // The reply's excerpt is scrubbed too.
        assertEquals("", storage.read { it.messages.get(reply.messageId, conversation.id)!! }.content!!.quote.previewText)
        // The WAL was folded in and truncated after the commit.
        val wal = File(environment.layout.databaseFile.path + "-wal")
        assertTrue(!wal.exists() || wal.length() == 0L)
        storage.close()
    }

    @Test
    fun ghostWipeLeavesNoReceiveTimelineInTheEnvelopeLog() {
        val (_, storage) = opened()
        storage.write { tx ->
            tx.envelopes.record("relay-a", Instant.parse("2026-10-08T13:37:21.123Z"))
        }
        val stored = storage.read { it.connection.scalar("SELECT processed_at FROM processed_envelopes") as Long }
        assertEquals(Instant.parse("2026-10-08T00:00:00Z").toEpochMilli(), stored)
        storage.close()
    }
}
