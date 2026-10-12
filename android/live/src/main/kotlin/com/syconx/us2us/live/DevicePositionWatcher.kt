package com.syconx.us2us.live

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.OwnPositionState
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * This device's own position for the live-location map (5.2 › harita).
 *
 * - Shown on this phone only: never sent, never stored, never logged.
 * - Its own listener, separate from [LocationProvider] (which sends): closing the map never touches
 *   a running share, and these updates never run in the background (the map stops collecting when
 *   the app leaves the foreground).
 * - Never asks for permission by itself: [requestAccess] does, when the user taps “İzin ver”.
 */
class DevicePositionWatcher(
    private val context: Context,
    private val access: LocationAccess,
) {
    /** Bumped when [requestAccess] got an answer: watching maps evaluate again. */
    private val decisions = MutableStateFlow(0)

    /** The device's position while the flow is collected; updates stop when collection ends. */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<OwnPositionState> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        var listener: FixListener? = null
        var last: LivePosition? = null

        fun pause() {
            listener?.let { manager?.removeUpdates(it) }
            listener = null
        }

        // Reports what the map can show, and runs the updates only while allowed.
        fun evaluate() {
            when (access.authorization) {
                LocationAuthorization.NotDetermined -> {
                    pause()
                    trySend(OwnPositionState.NeedsPermission)
                }
                LocationAuthorization.Denied -> {
                    pause()
                    trySend(OwnPositionState.Denied)
                }
                LocationAuthorization.Allowed -> {
                    val provider = manager?.let(LocationProvider::bestProvider)
                    if (manager == null || provider == null) {
                        pause()
                        trySend(OwnPositionState.Unavailable)
                        return
                    }
                    trySend(last?.let(OwnPositionState::Position) ?: OwnPositionState.Locating)
                    if (listener == null) {
                        val updates = FixListener { fix ->
                            if (!fix.hasAccuracy() || fix.accuracy >= 0) {
                                val position = LivePosition(
                                    latitude = fix.latitude,
                                    longitude = fix.longitude,
                                    accuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else 0.0,
                                    heading = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                                    updatedAt = Instant.ofEpochMilli(fix.time),
                                )
                                last = position
                                trySend(OwnPositionState.Position(position))
                            }
                        }
                        listener = updates
                        manager.requestLocationUpdates(provider, UPDATE_MILLIS, DISTANCE_METRES, updates, Looper.getMainLooper())
                    }
                }
            }
        }

        evaluate()
        // A decision made on the map, or in the system settings while the map stays open.
        val watcher = launch {
            var seen = decisions.value
            while (true) {
                delay(RECHECK_MILLIS)
                val current = decisions.value
                if (current != seen || (listener == null)) {
                    seen = current
                    evaluate()
                }
            }
        }
        awaitClose {
            watcher.cancel()
            pause()
            last = null
        }
    }.distinctUntilChanged().conflate()

    /** Asks for while-in-use permission once (only while it is undecided). `true` when location is allowed. */
    suspend fun requestAccess(): Boolean {
        val allowed = access.authorize()
        decisions.value += 1
        return allowed
    }

    private companion object {
        const val UPDATE_MILLIS = 5_000L
        const val DISTANCE_METRES = 10f
        const val RECHECK_MILLIS = 1_000L
    }
}
