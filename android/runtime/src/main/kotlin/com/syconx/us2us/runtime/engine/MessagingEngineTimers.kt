package com.syconx.us2us.runtime.engine

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.runtime.storage.Conversation
import com.syconx.us2us.runtime.storage.ConversationId
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.storage.WipeReason
import com.syconx.us2us.runtime.storage.WipeReport
import com.syconx.us2us.runtime.vault.SlotId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

// MARK: Ghost mode and disappearing messages (§8.1, §8.2)

/**
 * Sleeps until the next deadline (a disappearing message, a ghost timeout, the end of a
 * live-location session) and executes it; woken early whenever a deadline may have moved.
 */
internal suspend fun MessagingEngine.schedulerLoop() {
    while (running && currentCoroutineContext().isActive) {
        runDueWipes()
        expireLiveSessions()
        val next = nextDeadline()
        val wait = next?.let { maxOf(50L, it.toEpochMilli() - clock.now().toEpochMilli()) } ?: 3_600_000L
        schedulerWake.wait(minOf(wait, 3_600_000L).milliseconds, clock)
    }
}

internal fun MessagingEngine.nextDeadline(): Instant? {
    val storageDeadline = try {
        storage.nextDeadline()
    } catch (_: Exception) {
        null
    }
    val live = liveSessions.values.minOfOrNull { it.expiresAt }
    return listOfNotNull(storageDeadline, live).minOrNull()
}

/** Ghost timeouts (slot first, rows, files; a `ConversationWipe` to the contact is queued by the storage) and expired disappearing messages. */
internal fun MessagingEngine.runDueWipes() {
    val report = try {
        storage.performDueWipes(clock.now(), collectGarbage = false)
    } catch (_: Exception) {
        return
    }
    handleWipeReport(report)
}

internal fun MessagingEngine.handleWipeReport(report: WipeReport) {
    if (report.isEmpty) return
    for (wiped in report.conversations) {
        forgetNotices(wiped.conversationId)
        presence.forget(wiped.contact)
        typing.remove(wiped.contact)
        emit(EngineChange.Wiped(wiped.contact))
    }
    for (conversationId in report.expiredMessages.keys) {
        val peer = try {
            read { it.conversations.get(conversationId)?.contact }
        } catch (_: Exception) {
            null
        }
        if (peer != null) emit(EngineChange.Messages(peer))
    }
    emit(EngineChange.Conversations)
    outboxWake.signal()
}

private fun MessagingEngine.expireLiveSessions() {
    val now = clock.now()
    for ((session, value) in liveSessions.entries.toList()) {
        if (value.expiresAt.isAfter(now)) continue
        liveSessions.remove(session)
        emit(EngineChange.Messages(value.peer))
    }
}

/** "Delete for both" (§8.2 `USER_REQUEST`): wipe here first, then ask the contact to wipe too. */
internal fun MessagingEngine.deleteConversationForBothNow(peer: AccountId) {
    val now = clock.now()
    write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        storage.wipeConversation(conversation.id, WipeReason.REQUESTED, now)
        forgetNotices(conversation.id)
        val contact = tx.contacts.get(peer)
        if (contact != null && contact.requestState == ContactState.ACCEPTED) tx.outbox.enqueue(userWipe(now), peer, now = now)
    }
    emit(EngineChange.Wiped(peer))
    emit(EngineChange.Conversations)
    outboxWake.signal()
}

/**
 * Deletes the conversation on this phone only (crypto-shredded, §5.3); the contact stays. A live
 * location we share with the contact still stops on its phone (its stop goes without the
 * conversation, [enqueueLiveLocationStops]); nothing goes to someone blocked.
 */
internal fun MessagingEngine.deleteConversationLocallyNow(peer: AccountId) {
    val now = clock.now()
    val stops = write { tx ->
        val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        val stops = if (tx.contacts.requestState(peer) != ContactState.BLOCKED) enqueueLiveLocationStops(peer, conversation.id, tx, now) else 0
        storage.wipeConversation(conversation.id, WipeReason.REQUESTED, now)
        forgetNotices(conversation.id)
        stops
    }
    typing.remove(peer)
    // The shares end here (the Wiped change drops them as well).
    endLiveSessions(peer)
    emit(EngineChange.Wiped(peer))
    emit(EngineChange.Conversations)
    if (stops > 0) outboxWake.signal()
}

/**
 * "Kişiyi sil": deletes the contact on this phone only, nothing is sent. Its conversation is
 * crypto-shredded first (§5.3), then pending sends to it and the contact itself. Deleting is not
 * blocking (§6.4): the session and identity stay, with the username it was shown under, so if it
 * writes again its message still decrypts and appears as a new incoming request under that name
 * (an identity change is still flagged); writing to the username again starts a new outgoing
 * request. A blocked contact keeps the block and nothing else, no session either (7.1 ›
 * Engellenenler still lists it).
 *
 * A deleted contact that held our access key loses it: the key is rotated (as for a block) and the
 * new one goes to every other accepted contact. It can still write again, but only as a stranger
 * does, with proof-of-work, so its messages arrive as a new request and count against the
 * relay's request share of the mailbox, not the contacts' share (audit 2026-10-28 L-2 / M-1).
 * Trade-off: until the new key reaches them, other contacts' sends fall back to proof-of-work.
 */
internal suspend fun MessagingEngine.deleteContactNow(peer: AccountId) {
    val now = clock.now()
    var heldOurKey = false
    val forgotten = write { tx ->
        val contact = tx.contacts.get(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
        val conversation = tx.conversations.forContact(peer)
        conversation?.let { forgetNotices(it.id) }
        if (contact.requestState == ContactState.BLOCKED) {
            conversation?.let { storage.wipeConversation(it.id, WipeReason.REQUESTED, now) }
            tx.contacts.stripToBlock(peer)
            false
        } else {
            heldOurKey = contact.ourAccessKeySent != null
            tx.contacts.removeKeepingSession(peer)
            true
        }
    }
    if (heldOurKey) {
        runtime = runtime.copy(pendingAccessKeyRotation = true)
        saveRuntime()
    }
    presence.forget(peer)
    typing.remove(peer)
    lastTypingSent.remove(peer)
    held.remove(peer)
    pendingPresenceReplies.remove(peer)
    emit(EngineChange.Wiped(peer))
    emit(EngineChange.Conversations)
    emit(EngineChange.Presence)
    // The core's memory of the account (its search pictures, presence); the rows are gone already,
    // the session and identity stay.
    if (forgotten) {
        try {
            backend.forgetContactKeepingSession(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    // Retried after every connection until the relay took the new key, like a block's.
    if (heldOurKey) rotateAccessKeyIfPending()
}

// MARK: Notices

private fun ConversationId.key(): String = Hex.encode(raw.toByteArray())

/** Appends a local system line to the contact's conversation (created if needed). */
internal fun MessagingEngine.addNotice(notice: SystemNotice, peer: AccountId, time: Instant) {
    val conversation: Conversation = try {
        write { tx ->
            if (tx.contacts.get(peer) == null) null else tx.conversations.ensure(peer, time)
        }
    } catch (_: Exception) {
        null
    } ?: return
    val key = conversation.id.key()
    val existing = runtime.noticeSlots[key]?.let(SlotId::parse)
    val record = NoticeRecord(RandomIds.make(), time, notice)
    val slot = try {
        notices.append(record, existing, conversation.ghostDeadline)
    } catch (_: Exception) {
        return
    }
    if (existing != slot) {
        runtime = runtime.copy(noticeSlots = runtime.noticeSlots + (key to slot.hex))
        saveRuntime()
    }
    emit(EngineChange.Messages(peer))
}

internal fun MessagingEngine.noticesOf(conversationId: ConversationId): List<NoticeRecord> {
    val slot = runtime.noticeSlots[conversationId.key()]?.let(SlotId::parse) ?: return emptyList()
    return notices.notices(slot)
}

/** The notice slot follows the conversation's ghost deadline, so the background wipe shreds both at the same time. */
internal fun MessagingEngine.mirrorNoticeDeadline(peer: AccountId) {
    val conversation = try {
        read { it.conversations.forContact(peer) }
    } catch (_: Exception) {
        null
    } ?: return
    val slot = runtime.noticeSlots[conversation.id.key()]?.let(SlotId::parse) ?: return
    notices.setDeadline(conversation.ghostDeadline, slot)
}

internal fun MessagingEngine.forgetNotices(conversationId: ConversationId) {
    val key = conversationId.key()
    val slot = runtime.noticeSlots[key]?.let(SlotId::parse)
    if (!runtime.noticeSlots.containsKey(key)) return
    runtime = runtime.copy(noticeSlots = runtime.noticeSlots - key)
    slot?.let(notices::destroy)
    saveRuntime()
}

/** Drops notices of conversations that no longer exist (wiped while locked, crash leftovers) and re-mirrors every deadline. */
internal fun MessagingEngine.collectNoticeGarbage() {
    val conversations = try {
        read { it.conversations.all() }
    } catch (_: Exception) {
        emptyList()
    }
    val existing = conversations.associateBy { it.id.key() }
    val keep = HashSet<SlotId>()
    val slots = LinkedHashMap(runtime.noticeSlots)
    var changed = false
    for ((key, slotHex) in runtime.noticeSlots) {
        val conversation = existing[key]
        val slot = SlotId.parse(slotHex)
        if (conversation == null || slot == null) {
            slots.remove(key)
            changed = true
            continue
        }
        keep += slot
        notices.setDeadline(conversation.ghostDeadline, slot)
    }
    notices.collectGarbage(keep)
    if (changed) {
        runtime = runtime.copy(noticeSlots = slots)
        saveRuntime()
    }
}
