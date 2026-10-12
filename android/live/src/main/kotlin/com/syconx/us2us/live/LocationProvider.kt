package com.syconx.us2us.live

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A listener for fixes, and for its provider being switched off ([onDisabled]). Every method is
 * implemented: before API 30 the platform's `LocationListener` has no default methods, and a lambda
 * would fail when the system calls one.
 */
class FixListener(private val onDisabled: () -> Unit = {}, private val onFix: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onFix(location)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = onDisabled()
}

/** One position fix, as the live service sends it (never stored here). */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Metres; 0 when unknown. */
    val accuracy: Float,
    /** Degrees from true north; negative when unknown. */
    val course: Float,
    /** Metres per second; negative when unknown. */
    val speed: Float,
)

/**
 * The platform `LocationManager` for “+” › Konum and live location (PROTOCOL §8.3); no Play
 * services.
 *
 * - While in use only, asked the first time the user shares a place ([LocationAccess]).
 * - Live updates run while live location is being shared and stop the moment sharing ends; one
 *   tick at most every [LIVE_INTERVAL_MILLIS]. In the background they go on only while the share's
 *   foreground service runs ([LiveSharingService]), started from the conversation, so while-in-use
 *   access is enough.
 * - Nothing is stored; fixes go straight to the runtime, encrypted to the chat.
 */
class LocationProvider(
    private val context: Context,
    private val access: LocationAccess,
) : LiveLocationSharing.Updates {
    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)
    private var liveListener: FixListener? = null
    private var lastLiveTick = 0L

    val isSharingLive: Boolean get() = liveListener != null

    /** One fix, asking for while-in-use permission first if needed; `null` when refused or unavailable. Main thread. */
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): LocationFix? {
        if (!access.authorize()) return null
        val manager = manager ?: return null
        val provider = bestProvider(manager) ?: return null
        recentFix(manager)?.let { return it }
        val location = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = CancellationSignal()
                    continuation.invokeOnCancellation { cancel.cancel() }
                    manager.getCurrentLocation(provider, cancel, context.mainExecutor) { fix ->
                        if (continuation.isActive) continuation.resume(fix)
                    }
                } else {
                    val listener = FixListener { location -> if (continuation.isActive) continuation.resume(location) }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return location?.let(::fix)
    }

    /**
     * Starts continuous updates for live location; `false` when location is refused or unavailable.
     * When their provider is switched off they go on with the next one, and [unavailable] runs once
     * location is off altogether. Main thread.
     */
    @SuppressLint("MissingPermission")
    override suspend fun startLiveUpdates(handler: (LocationFix) -> Unit, unavailable: () -> Unit): Boolean {
        if (!access.authorize()) return false
        val manager = manager ?: return false
        val provider = bestProvider(manager) ?: return false
        stopLiveUpdates()
        lastLiveTick = 0L
        val listener = FixListener(onDisabled = { providerDisabled(unavailable) }) { location ->
            val now = SystemClock.elapsedRealtime()
            if (lastLiveTick != 0L && now - lastLiveTick < LIVE_INTERVAL_MILLIS) return@FixListener
            lastLiveTick = now
            handler(fix(location))
        }
        liveListener = listener
        manager.requestLocationUpdates(provider, LIVE_REQUEST_MILLIS, 0f, listener, Looper.getMainLooper())
        return true
    }

    /** The live updates' provider was switched off: on with the next one, or [unavailable] when none is left (or access went). */
    @SuppressLint("MissingPermission")
    private fun providerDisabled(unavailable: () -> Unit) {
        val listener = liveListener ?: return
        val manager = manager
        val next = manager?.let(::bestProvider)
        if (manager == null || next == null || !access.isGranted()) {
            unavailable()
            return
        }
        manager.removeUpdates(listener)
        manager.requestLocationUpdates(next, LIVE_REQUEST_MILLIS, 0f, listener, Looper.getMainLooper())
    }

    override fun stopLiveUpdates() {
        val listener = liveListener ?: return
        liveListener = null
        manager?.removeUpdates(listener)
    }

    @SuppressLint("MissingPermission")
    private fun recentFix(manager: LocationManager): LocationFix? {
        val now = SystemClock.elapsedRealtimeNanos()
        val recent = manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.elapsedRealtimeNanos < RECENT_NANOS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        return recent?.let(::fix)
    }

    companion object {
        /** The runtime asks for a tick every 15–30 s. */
        const val LIVE_INTERVAL_MILLIS = 20_000L
        private const val LIVE_REQUEST_MILLIS = 5_000L
        private const val FIX_TIMEOUT_MILLIS = 30_000L
        private const val RECENT_NANOS = 30_000_000_000L

        /**
         * GPS while it is on (the accuracy the share asks for, about ten metres), else the fused
         * provider where the phone has one, else the network.
         */
        fun bestProvider(manager: LocationManager): String? {
            val enabled = manager.getProviders(true)
            if (LocationManager.GPS_PROVIDER in enabled) return LocationManager.GPS_PROVIDER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled) return LocationManager.FUSED_PROVIDER
            return enabled.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: enabled.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        }

        fun fix(location: Location): LocationFix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) maxOf(0f, location.accuracy) else 0f,
            course = if (location.hasBearing()) location.bearing else -1f,
            speed = if (location.hasSpeed()) location.speed else -1f,
        )
    }
}
