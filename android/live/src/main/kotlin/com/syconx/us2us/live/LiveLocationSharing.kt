package com.syconx.us2us.live

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.runtime.facade.AppRuntime
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live-location shares this phone runs (PROTOCOL §8.3): the iOS service's `liveSessions` with
 * the location updates, plus what Android needs to keep them going in the background.
 *
 * - A share starts in the conversation, in front: one fix, the runtime's start message, the
 *   location updates and the [SharingNotice] (a foreground service with its notification), which
 *   keeps the updates and the encrypted ticks going while the app is in the background and locked.
 * - Every fix is a tick for every running share (the provider sends one every 20 s at most).
 * - A share ends when the user stops it (the bubble, the map, the notification's “Durdur”), when its
 *   time is up (also on a sleeping phone, by the notice's alarm), before its contact is blocked,
 *   when location is switched off, when a tick can no longer go out for it (its conversation wiped,
 *   the vault closed), and in the background whenever its notice cannot be seen. With the last one
 *   the location updates stop and the notice goes; the runtime then zeroes the key if the app is in
 *   the background (§5.4 step 4).
 * - In the background a share goes on only while the user can see it, as iOS always shows its
 *   location indicator: with notifications refused or turned off (the app's or the notice's
 *   channel) the shares stop when the app leaves, or as soon as they are turned off.
 * - Nothing is stored: a share the process loses is stopped by the runtime at its next start.
 *
 * Main thread only, like the service that owns it.
 */
class LiveLocationSharing(
    private val runtime: Runtime,
    private val location: Updates,
    private val notice: SharingNotice,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    /** What a share asks of the runtime ([AppRuntime]'s live-location calls, which also work while locked). */
    interface Runtime {
        suspend fun start(peer: AccountId, fix: LocationFix, duration: Duration): ByteString

        suspend fun update(sessionId: ByteString, peer: AccountId, fix: LocationFix)

        suspend fun stop(sessionId: ByteString, peer: AccountId)
    }

    /** The phone's position ([LocationProvider]): one fix, and the updates while a share runs. */
    interface Updates {
        /** One fix, asking for while-in-use access first if needed; `null` when refused or unavailable. */
        suspend fun currentLocation(): LocationFix?

        /** `false` when location is refused or unavailable; [unavailable] runs when location is switched off meanwhile. */
        suspend fun startLiveUpdates(handler: (LocationFix) -> Unit, unavailable: () -> Unit): Boolean

        fun stopLiveUpdates()
    }

    private class Share(val peer: AccountId, val sessionId: ByteString, val endsAt: Instant) {
        var expiry: Job? = null
    }

    /** By session id, in the order they started. */
    private val shares = LinkedHashMap<Bytes, Share>()

    /** Ticks the runtime took since this object was made (device tests). */
    internal var ticks = 0
        private set

    /** The app left the screen and has not come back: a share must be seen to go on. */
    private var inBackground = false

    val isSharing: Boolean get() = shares.isNotEmpty()

    fun isRunning(sessionId: ByteString): Boolean = sessionId.toBytes() in shares

    /** “+” › Canlı konum: shares this phone's position with [peer] for [duration]. Nothing happens when location is refused. */
    suspend fun start(peer: AccountId, duration: Duration) {
        val fix = location.currentLocation() ?: return
        // Once the runtime is asked, the share is this object's, not the screen's: a screen that
        // goes away meanwhile must not leave the contact a share that nothing drives or ends.
        withContext(NonCancellable) { begin(peer, fix, duration) }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun begin(peer: AccountId, fix: LocationFix, duration: Duration) {
        val sessionId = try {
            runtime.start(peer, fix, duration)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val key = sessionId.toBytes()
        val share = Share(peer, sessionId, clock().plus(duration.toJavaDuration()))
        shares[key] = share
        share.expiry = scope.launch {
            delay(until(share.endsAt))
            end(key)
        }
        // Without the updates, or without the notice that keeps them going in the background, the
        // share would freeze for the contact: it stops at once instead, and so does every share
        // when location is switched off.
        if (!location.startLiveUpdates(::deliver, ::stopAll) || !notice.show(latestEnd(), ::stopAll, ::endDue, ::noticeChanged)) end(key)
    }

    /** Stops one share (the bubble's or the map's “Durdur”); `false` when this phone does not run it. */
    suspend fun stop(sessionId: ByteString): Boolean {
        val key = sessionId.toBytes()
        if (key !in shares) return false
        end(key)
        return true
    }

    /** Stops every share with [peer], before they are blocked: nothing keeps tracking for someone blocked. */
    suspend fun stopSharingWith(peer: AccountId) {
        for ((key, share) in shares.entries.toList()) {
            if (share.peer == peer) end(key)
        }
    }

    /** The notification's “Durdur” (and the app removed from the recents, as a closed app on iOS): every share stops. */
    fun stopAll() {
        scope.launch {
            for (key in shares.keys.toList()) end(key)
        }
    }

    /**
     * The wall clock reached the latest end (the notice's alarm, which also wakes a sleeping phone):
     * every share whose time is up ends. The expiry timers count only the time the phone is awake.
     */
    fun endDue(): Job = scope.launch {
        val now = clock()
        for ((key, share) in shares.entries.toList()) {
            if (!now.isBefore(share.endsAt)) end(key)
        }
    }

    /**
     * The app left the screen. A share in the background must stay visible, as iOS always shows its
     * location indicator: here that is the notice, so with notifications refused or turned off the
     * shares stop instead of going on unseen.
     */
    fun appLeft() {
        inBackground = true
        stopsUnseen()
    }

    /** The app is in front again: its shares go on there whether or not the notice can be seen. */
    fun appReturned() {
        inBackground = false
    }

    /**
     * Notifications may have been turned off (the app's, the notice's channel or the permission):
     * the service asks on every change the system reports and every minute. In the background the
     * shares stop at once.
     */
    fun noticeChanged() {
        stopsUnseen()
    }

    /** In the background with a notice nobody can see: every share stops. `true` when they do. */
    private fun stopsUnseen(): Boolean {
        if (!inBackground || shares.isEmpty() || notice.canBeSeen()) return false
        stopAll()
        return true
    }

    /** The vault this phone held is gone (erased, replaced): its shares go without a word to the runtime. */
    fun forget() {
        for (share in shares.values) share.expiry?.cancel()
        shares.clear()
        settle()
    }

    private fun deliver(fix: LocationFix) {
        // Nothing more goes out for a share the user can no longer see.
        if (stopsUnseen()) return
        val now = clock()
        for ((key, share) in shares.entries.toList()) {
            if (!now.isBefore(share.endsAt)) {
                scope.launch { end(key) }
                continue
            }
            scope.launch {
                try {
                    runtime.update(share.sessionId, share.peer, fix)
                    ticks += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Its conversation was wiped, the contact blocked or the vault closed: it cannot go on.
                    end(key)
                }
            }
        }
    }

    private suspend fun end(key: Bytes) {
        val share = shares.remove(key) ?: return
        val expiry = share.expiry
        if (expiry != null && expiry != currentCoroutineContext()[Job]) expiry.cancel()
        try {
            // Also when the screen that asked goes away meanwhile: the runtime zeroes the key with the last share.
            withContext(NonCancellable) { runtime.stop(share.sessionId, share.peer) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            settle()
        }
    }

    private fun settle() {
        if (shares.isEmpty()) {
            location.stopLiveUpdates()
            notice.hide()
        } else {
            notice.update(latestEnd())
        }
    }

    private fun latestEnd(): Instant = shares.values.maxOf { it.endsAt }

    private fun until(end: Instant): Duration = (end.toEpochMilli() - clock().toEpochMilli()).coerceAtLeast(0L).milliseconds

    companion object {
        /** The runtime's live-location calls. */
        fun runtimeOf(runtime: AppRuntime): Runtime = object : Runtime {
            override suspend fun start(peer: AccountId, fix: LocationFix, duration: Duration): ByteString =
                runtime.startLiveLocation(peer, fix.latitude, fix.longitude, fix.accuracy, duration)

            override suspend fun update(sessionId: ByteString, peer: AccountId, fix: LocationFix) =
                runtime.updateLiveLocation(sessionId, peer, fix.latitude, fix.longitude, fix.accuracy, heading = fix.course, speed = fix.speed)

            override suspend fun stop(sessionId: ByteString, peer: AccountId) = runtime.stopLiveLocation(sessionId, peer)
        }
    }
}
