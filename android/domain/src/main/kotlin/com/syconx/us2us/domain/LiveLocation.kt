package com.syconx.us2us.domain

import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.math.abs

// 5.2 › Konum: what the “Haritada aç” chooser and the live-location map need. Plain values (no
// platform location types here), held in memory only: nothing in this file is stored, logged or
// sent.

/** A point on the map: a live share's latest position, or this device's own. */
data class LivePosition(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in metres; 0 when unknown. */
    val accuracy: Double = 0.0,
    /** Course in degrees from true north; `null` when unknown. */
    val heading: Double? = null,
    /** When this phone saw the position change; `null` when that is not known. */
    val updatedAt: Instant? = null,
) {
    /** A real place on Earth (a sender's position is whatever its app sent). */
    val isValidCoordinate: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && abs(latitude) <= 90 && abs(longitude) <= 180
}

/** One live-location share of the open chat, with its latest position. */
data class LiveShare(
    /** The live-location message. */
    val id: MessageId,
    val direction: MessageDirection,
    val startedAt: Instant,
    val position: LivePosition,
    val expiresAt: Instant,
    /** Stopped by its sender (“Durdur”). */
    val isStopped: Boolean,
) {
    val isOutgoing: Boolean get() = direction == MessageDirection.Outgoing

    /** Still running at [now]; after that, receivers stop showing it (PROTOCOL §8.3). */
    fun isActive(now: Instant): Boolean = !isStopped && now < expiresAt
}

/**
 * The live shares of the open chat, oldest first, with their latest positions. Memory only: empty
 * while the chat is not open and while the app is locked.
 */
data class LiveLocationSnapshot(val shares: List<LiveShare> = emptyList()) {
    fun share(id: MessageId): LiveShare? = shares.lastOrNull { it.id == id }

    /** The newest share in [direction] that is still running at [now]. */
    fun activeShare(direction: MessageDirection, now: Instant): LiveShare? =
        shares.lastOrNull { it.direction == direction && it.isActive(now) }

    companion object {
        val Empty = LiveLocationSnapshot()
    }
}

/** This device's own position for the live-location map: shown on this phone only, never sent. */
sealed interface OwnPositionState {
    /** Location access has not been asked yet: the map offers “İzin ver”. */
    data object NeedsPermission : OwnPositionState

    /** Refused or restricted: the map offers “Ayarları aç”. */
    data object Denied : OwnPositionState

    /** Allowed; waiting for the first fix. */
    data object Locating : OwnPositionState

    data class Position(val position: LivePosition) : OwnPositionState

    /** This messenger shows no own position. */
    data object Unavailable : OwnPositionState
}

/**
 * Android: what the live-location share sheet's “Bildirimlere izin ver” came to. A share goes on in
 * the background only while its notification can be seen; asking is never required to share.
 */
enum class NoticePermission {
    /** The notification can be seen: shares go on in the background. */
    Allowed,

    /** Refused in the system's question: shares run while the app is in front. */
    Refused,

    /** The system no longer asks (refused for good, or turned off there): only the app's notification settings can allow it. */
    InSettings,
}
