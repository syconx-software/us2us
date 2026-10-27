package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.presence.PresencePolicy
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.proto.LiveLocation
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.runtime.storage.ConversationId
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.storage.StorageTransaction
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// MARK: Presence (§8.6)

/** Eligible contacts with the key their ephemeral sends need. */
internal fun MessagingEngine.presenceTargets(filter: ((AccountId) -> Boolean)? = null): List<Recipient> {
    if (!settings.presenceSharing) return emptyList()
    val contacts = try {
        read { it.contacts.accepted() }
    } catch (_: Exception) {
        return emptyList()
    }
    return contacts.mapNotNull { contact ->
        val key = contact.theirAccessKey ?: return@mapNotNull null
        if (!isPresenceEligible(contact) || contact.identityChanged || filter?.invoke(contact.accountId) == false) return@mapNotNull null
        Recipient(contact.accountId, key)
    }
}

/** After `queue_empty`: ONLINE to every eligible contact in **one** ephemeral request. */
internal suspend fun MessagingEngine.announceOnline() {
    if (backend.runsPresence) return
    val targets = presenceTargets()
    if (targets.isEmpty()) return
    sendPresenceQuietly(PresencePolicy.online(), targets)
}

/** End of the grace period: OFFLINE only to the contacts we see online. */
internal suspend fun MessagingEngine.announceOffline() {
    if (backend.runsPresence) {
        try {
            backend.announceOffline()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return
    }
    val online = presence.onlineContacts(clock.now()).toSet()
    val targets = presenceTargets { it in online }
    if (targets.isEmpty()) return
    sendPresenceQuietly(PresencePolicy.offline(), targets)
}

private suspend fun MessagingEngine.sendPresenceQuietly(presence: com.syconx.us2us.core.proto.Content, targets: List<Recipient>) {
    try {
        backend.sendPresence(presence, targets)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/** Answers ONLINE announcements once, batched: contacts that came online together share one ephemeral request. */
internal fun MessagingEngine.queuePresenceReply(peer: AccountId) {
    if (backend.runsPresence) return
    pendingPresenceReplies += peer
    if (presenceReplyTask != null) return
    presenceReplyTask = scope.launch {
        clock.sleep(configuration.presenceReplyDelay)
        sendPresenceReplies()
    }
}

private suspend fun MessagingEngine.sendPresenceReplies() {
    presenceReplyTask = null
    val peers = pendingPresenceReplies.toSet()
    pendingPresenceReplies.clear()
    if (!running) return
    val targets = presenceTargets { it in peers }
    if (targets.isEmpty()) return
    sendPresenceQuietly(PresencePolicy.online(reply = true), targets)
}

/** Every 240 s: ONLINE again to the contacts currently seen online. */
internal suspend fun MessagingEngine.presenceRefreshLoop() {
    while (running && currentCoroutineContext().isActive) {
        clock.sleep(PresencePolicy.REFRESH_INTERVAL)
        if (!running || !connected) continue
        val now = clock.now()
        presence.prune(now)
        if (!backend.runsPresence) {
            val online = presence.onlineContacts(now).toSet()
            val targets = presenceTargets { it in online }
            if (targets.isNotEmpty()) sendPresenceQuietly(PresencePolicy.online(), targets)
        }
        emit(EngineChange.Presence)
    }
}

internal fun MessagingEngine.presenceStatusNow(now: Instant): Map<AccountId, PresenceStatus> {
    if (!settings.presenceSharing) return emptyMap()
    val contacts = try {
        read { it.contacts.accepted() }
    } catch (_: Exception) {
        return emptyMap()
    }
    val result = LinkedHashMap<AccountId, PresenceStatus>()
    for (contact in contacts) {
        if (!isPresenceEligible(contact)) continue
        val status = presence.status(contact.accountId, now)
        if (status != PresenceStatus.None) result[contact.accountId] = status
    }
    return result
}

// MARK: Pre-keys (§5)

/** Refills one-time pre-keys and rotates signed / last-resort keys, at most every few hours while connected; finishes a pending access-key rotation. */
internal suspend fun MessagingEngine.maintainKeysIfDue() {
    rotateAccessKeyIfPending()
    val now = clock.now()
    val last = lastMaintenance
    if (last != null && now.isBefore(last.plusMillis(configuration.maintenanceInterval.inWholeMilliseconds))) return
    try {
        backend.performMaintenance()
        lastMaintenance = now
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RuntimeLog.messaging.info("key maintenance postponed", e.logLabel)
    }
}

// MARK: Live location (§8.3)

/** The shortest and longest sharing the app offers (§8.3). */
private val LIVE_LOCATION_MINIMUM: Duration = 60.seconds
private val LIVE_LOCATION_MAXIMUM: Duration = 8.hours

/**
 * Starts sharing; the start message goes through the outbox (stored by the relay), ticks are
 * ephemeral, the stop message is normal again. The conversation's disappearing timer applies like
 * to any message (§8.1).
 */
internal fun MessagingEngine.startLiveLocationNow(
    peer: AccountId,
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    duration: Duration,
): ByteString {
    val now = clock.now()
    val sessionId = RandomIds.make()
    val length = duration.coerceIn(LIVE_LOCATION_MINIMUM, LIVE_LOCATION_MAXIMUM)
    val expiresAt = now.plusMillis(length.inWholeMilliseconds)
    val live = liveLocation {
        this.sessionId = sessionId
        this.latitude = latitude
        this.longitude = longitude
        accuracyMeters = accuracy
        headingDegrees = -1f
        speedMps = -1f
        this.expiresAt = expiresAt.toEpochMilli()
        sequence = 0
    }
    val id = RandomIds.make()
    write { tx ->
        if (tx.contacts.requestState(peer) != ContactState.ACCEPTED) engineFailure(EngineError.NOT_ALLOWED)
        val conversation = tx.conversations.ensure(peer, now)
        val data = dataMessage {
            messageId = id
            sentAt = now.toEpochMilli()
            expireTimerSeconds = conversation.settings.defaultExpireTimerSeconds
            liveLocation = live
        }
        tx.messages.storeOutgoing(data, conversation.id, now)
        tx.outbox.enqueue(content { this.data = data }, peer, conversation.id, messageId = id, now = now)
    }
    liveSessions[sessionId] = MessagingEngine.LiveSession(peer, expiresAt)
    afterLocalChange(peer)
    return sessionId
}

/** One intermediate update (every 15–30 s): the bubble here changes in place, and the tick reaches the contact only while their app is open. */
internal suspend fun MessagingEngine.updateLiveLocationNow(
    sessionId: ByteString,
    peer: AccountId,
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    heading: Float,
    speed: Float,
) {
    val now = clock.now()
    val session = liveSessions[sessionId]
    if (session == null || session.peer != peer || !session.expiresAt.isAfter(now)) engineFailure(EngineError.INVALID_MESSAGE)
    val (data, contact) = write { tx ->
        val contact = tx.contacts.get(peer)
        val conversation = tx.conversations.forContact(peer)
        if (contact == null || contact.requestState != ContactState.ACCEPTED || conversation == null) engineFailure(EngineError.NOT_ALLOWED)
        val (current, timer) = liveLocationOf(sessionId, conversation.id, tx, now)
        val live = current.toBuilder()
            .setLatitude(latitude)
            .setLongitude(longitude)
            .setAccuracyMeters(accuracy)
            .setHeadingDegrees(heading)
            .setSpeedMps(speed)
            .setSequence(current.sequence + 1)
            .build()
        // The bubble's timer travels along: a tick that reaches a phone without the bubble (the
        // start was lost) starts one that disappears too.
        val data = dataMessage {
            messageId = RandomIds.make()
            sentAt = now.toEpochMilli()
            expireTimerSeconds = timer
            liveLocation = live
        }
        tx.messages.storeOutgoing(data, conversation.id, now)
        data to contact
    }
    emit(EngineChange.Messages(peer))
    val key = contact.theirAccessKey ?: return
    if (contact.identityChanged) return
    try {
        backend.send(content { this.data = data }, peer, contact.requestState, key, ephemeral = true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

internal fun MessagingEngine.stopLiveLocationNow(sessionId: ByteString, peer: AccountId) {
    val now = clock.now()
    try {
        write { tx ->
            val conversation = tx.conversations.forContact(peer) ?: engineFailure(EngineError.UNKNOWN_CONTACT)
            val (current, timer) = liveLocationOf(sessionId, conversation.id, tx, now)
            val live = current.toBuilder().setStopped(true).setSequence(current.sequence + 1).build()
            val data = dataMessage {
                messageId = RandomIds.make()
                sentAt = now.toEpochMilli()
                expireTimerSeconds = timer
                liveLocation = live
            }
            tx.messages.storeOutgoing(data, conversation.id, now)
            tx.outbox.enqueue(content { this.data = data }, peer, conversation.id, now = now)
        }
    } finally {
        // Ended also when its stop cannot be stored (the bubble or the conversation is gone): the
        // store stays open in the background only for running shares (§5.4 step 4).
        liveSessions.remove(sessionId)
    }
    afterLocalChange(peer)
}

/**
 * “Sohbeti sil” while we share a live location with [peer]: each running share still stops on the
 * other phone (§8.3). Its stop is built from the share's latest bubble, as [stopLiveLocationNow]
 * does, but queued without the conversation (whose items are about to be crypto-shredded) and not
 * stored as a message. Runs inside the deletion's write, before the wipe. Returns how many were queued.
 */
internal fun MessagingEngine.enqueueLiveLocationStops(peer: AccountId, conversationId: ConversationId, tx: StorageTransaction, now: Instant): Int {
    var queued = 0
    for ((sessionId, session) in liveSessions) {
        if (session.peer != peer) continue
        val (current, timer) = try {
            liveLocationOf(sessionId, conversationId, tx, now)
        } catch (_: EngineException) {
            continue
        }
        val live = current.toBuilder().setStopped(true).setSequence(current.sequence + 1).build()
        val data = dataMessage {
            messageId = RandomIds.make()
            sentAt = now.toEpochMilli()
            expireTimerSeconds = timer
            liveLocation = live
        }
        tx.outbox.enqueue(content { this.data = data }, peer, now = now)
        queued++
    }
    return queued
}

/**
 * Our live-location bubbles that still look live although this engine sends nothing for them: the
 * process ended while sharing or the vault was locked, so the session's memory is gone. Each one
 * gets its normal stop message now (§8.3); sharing is never resumed by itself.
 */
internal fun MessagingEngine.stopOrphanedLiveLocations() {
    val now = clock.now()
    val orphans = try {
        read { tx ->
            // Only the rows that carry a session are read and decrypted: this runs at every unlock.
            val candidates = tx.messages.outgoingLiveLocations(now)
            if (candidates.isEmpty()) return@read emptyList()
            val contacts = tx.conversations.all().associate { it.id to it.contact }
            val seen = HashSet<ByteString>()
            candidates.mapNotNull { message ->
                if (!message.isOutgoing || message.isDeleted) return@mapNotNull null
                val live = message.content?.takeIf { it.hasLiveLocation() }?.liveLocation ?: return@mapNotNull null
                val peer = contacts[message.conversationId] ?: return@mapNotNull null
                val expires = if (live.expiresAt < 0) Long.MAX_VALUE else live.expiresAt
                if (live.stopped || expires <= now.toEpochMilli() || live.sessionId in liveSessions || !seen.add(live.sessionId)) {
                    return@mapNotNull null
                }
                live.sessionId to peer
            }
        }
    } catch (e: Exception) {
        RuntimeLog.messaging.error("orphaned live locations not checked", e.logLabel)
        return
    }
    for ((session, peer) in orphans) {
        try {
            stopLiveLocationNow(session, peer)
            RuntimeLog.messaging.info("stopped a live location this process no longer shares")
        } catch (e: Exception) {
            RuntimeLog.messaging.error("orphaned live location not stopped", e.logLabel)
        }
    }
}

/** The session's bubble and its disappearing timer. */
private fun liveLocationOf(sessionId: ByteString, conversationId: ConversationId, tx: StorageTransaction, now: Instant): Pair<LiveLocation, Int> {
    // The session's bubble is one of the latest messages.
    val page = tx.messages.page(conversationId, limit = 500, now = now)
    for (message in page.asReversed()) {
        if (!message.isOutgoing || message.liveSessionId != sessionId) continue
        val content = message.content ?: continue
        if (content.hasLiveLocation()) return content.liveLocation to message.expireTimerSeconds.toInt()
    }
    engineFailure(EngineError.INVALID_MESSAGE)
}
