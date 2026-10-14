package com.syconx.us2us.feature.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.LiveLocationSnapshot
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.LiveShare
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The live-location map's state (5.2 › harita): the open chat's live shares from the messenger
 * (observable, memory only) and this device's own position (shown on this phone only, never sent).
 * Nothing outlives the map: it all goes when the cover closes.
 */
@Stable
class LiveLocationMapModel(
    val chat: ChatId,
    /** The share the map was opened on: the map closes when it is gone (deleted, the chat closed, the app locked). */
    val focus: MessageId,
    val messenger: MessengerService,
) {
    /** What the bottom card says about the user. */
    sealed interface OwnRow {
        /** The user's own share is running: “Konumunu paylaşıyorsun” + “Paylaşımı durdur”. */
        data class Sharing(val share: LiveShare) : OwnRow

        /** This device's position, shown here only. */
        data object Device : OwnRow

        /** Allowed, no fix yet. */
        data object Locating : OwnRow

        /** The gentle hint with “İzin ver”. */
        data object AskPermission : OwnRow

        /** The gentle hint with “Ayarları aç”. */
        data object Denied : OwnRow

        /** Nothing to say (the messenger shows no own position). */
        data object Hidden : OwnRow

        /** Which row it is, whatever the share's latest values (the card cross-fades only when this changes). */
        val kind: Int
            get() = when (this) {
                is Sharing -> 0
                Device -> 1
                Locating -> 2
                AskPermission -> 3
                Denied -> 4
                Hidden -> 5
            }
    }

    var own: OwnPositionState by mutableStateOf(OwnPositionState.Unavailable)
        private set

    /** Bumped after a permission request: the position stream starts again. */
    var streamGeneration by mutableIntStateOf(0)
        private set

    /**
     * The user asked to see themselves (“Konumuma git”, “İkinizi de göster”): only then does the
     * camera take in this device's position while it is not shared, so the map shows no area around
     * the user unasked. Their marker is drawn either way.
     */
    var framesDevicePosition by mutableStateOf(false)

    val snapshot: LiveLocationSnapshot get() = messenger.liveLocations(chat).value

    val isGone: Boolean get() = snapshot.share(focus) == null

    val peerUsername: String get() = messenger.peer(chat).value?.username ?: chat.value

    val peerPalette: AvatarPalette get() = messenger.peer(chat).value?.palette ?: AvatarPalette.forUsername(peerUsername)

    /** The contact's profile picture (proposed), on their marker and row. */
    val peerAvatar: AvatarPicture get() = messenger.peer(chat).value?.avatar ?: AvatarPicture.Initials

    val ownUsername: String get() = messenger.ownUsername.value

    /** The contact's running share (its marker goes when it ends — PROTOCOL §8.3). */
    fun peerShare(now: Instant): LiveShare? = snapshot.activeShare(MessageDirection.Incoming, now)

    fun ownShare(now: Instant): LiveShare? = snapshot.activeShare(MessageDirection.Outgoing, now)

    /** The contact's marker: their share's latest position, if it is a real place. */
    fun peerPosition(now: Instant): LivePosition? = peerShare(now)?.position?.takeIf { it.isValidCoordinate }

    /** Where the user is: this device's position when it is known, else the position last shared. */
    fun ownPosition(now: Instant): LivePosition? {
        val position = (own as? OwnPositionState.Position)?.position ?: ownShare(now)?.position
        return position?.takeIf { it.isValidCoordinate }
    }

    /** Everything the camera keeps in view: the contact, and the user while they share their position or once they asked to see it. */
    fun points(now: Instant): List<LivePosition> {
        val framesOwn = ownShare(now) != null || framesDevicePosition
        return listOfNotNull(peerPosition(now), if (framesOwn) ownPosition(now) else null)
    }

    /** Whether the share's message disappears: the maps chooser then says that the chosen app may keep the place. */
    fun isDisappearing(share: LiveShare): Boolean = messenger.messages(chat).value.firstOrNull { it.id == share.id }?.isDisappearing ?: false

    /** Whether anything on the map is still live. */
    fun isLive(now: Instant): Boolean = peerShare(now) != null || ownShare(now) != null

    fun ownRow(now: Instant): OwnRow {
        ownShare(now)?.let { return OwnRow.Sharing(it) }
        return when (own) {
            is OwnPositionState.Position -> OwnRow.Device
            OwnPositionState.Locating -> OwnRow.Locating
            OwnPositionState.NeedsPermission -> OwnRow.AskPermission
            OwnPositionState.Denied -> OwnRow.Denied
            OwnPositionState.Unavailable -> OwnRow.Hidden
        }
    }

    fun receive(state: OwnPositionState) {
        own = state
    }

    /** Follows this device's position until the calling coroutine is cancelled (the map closed, or the app left the foreground). */
    suspend fun run() {
        messenger.ownPositionUpdates().collect { receive(it) }
    }

    /** “İzin ver”: the system asks once; the stream then starts again. */
    suspend fun requestAccess() {
        messenger.requestOwnPositionAccess()
        streamGeneration++
    }

    /** “Paylaşımı durdur”. */
    suspend fun stop(share: LiveShare) {
        messenger.stopLiveLocation(share.id, chat)
    }

    companion object {
        /** The whole minutes left until [end] (rounded up, never below zero). */
        fun minutesLeft(end: Instant, now: Instant): Int {
            val seconds = (end.toEpochMilli() - now.toEpochMilli()) / 1000.0
            return max(0, kotlin.math.ceil(seconds / 60.0).toInt())
        }
    }
}

/** A part of the map: its centre and how much of it shows each way (degrees). */
@Immutable
data class MapRegion(val centerLatitude: Double, val centerLongitude: Double, val latitudeDelta: Double, val longitudeDelta: Double)

/** The camera of the live-location map: both people in view, never closer than a few streets. */
object LiveMapFraming {
    /** The smallest span shown (metres): a single point shows its street. */
    const val MINIMUM_SPAN_METERS = 400.0

    /** Room around the points: the header and the bottom card cover the map's top and bottom, so the height gets more of it. */
    const val LATITUDE_MARGIN = 2.2
    const val LONGITUDE_MARGIN = 1.5

    /** An accuracy circle is drawn only when it means something. */
    val accuracyRange: ClosedFloatingPointRange<Double> = 1.0..500.0
    const val METERS_PER_DEGREE = 111_320.0

    fun showsAccuracy(accuracy: Double): Boolean = accuracy in accuracyRange

    /** The region that shows every point with its margin; `null` without points. */
    fun region(points: List<LivePosition>): MapRegion? {
        val valid = points.filter { it.isValidCoordinate }
        val first = valid.firstOrNull() ?: return null
        var minLatitude = first.latitude
        var maxLatitude = first.latitude
        var minLongitude = first.longitude
        var maxLongitude = first.longitude
        for (point in valid) {
            minLatitude = min(minLatitude, point.latitude)
            maxLatitude = max(maxLatitude, point.latitude)
            // Unwrapped around the first point: two points either side of ±180° stay neighbours.
            var longitude = point.longitude
            while (longitude - first.longitude > 180) longitude -= 360
            while (longitude - first.longitude < -180) longitude += 360
            minLongitude = min(minLongitude, longitude)
            maxLongitude = max(maxLongitude, longitude)
        }
        val centerLatitude = (minLatitude + maxLatitude) / 2
        var centerLongitude = (minLongitude + maxLongitude) / 2
        if (centerLongitude > 180) centerLongitude -= 360
        if (centerLongitude < -180) centerLongitude += 360
        val cosine = max(0.01, cos(centerLatitude * PI / 180))
        val latitudeDelta = min(170.0, max((maxLatitude - minLatitude) * LATITUDE_MARGIN, MINIMUM_SPAN_METERS / METERS_PER_DEGREE))
        val longitudeDelta = min(
            360.0,
            max((maxLongitude - minLongitude) * LONGITUDE_MARGIN, MINIMUM_SPAN_METERS / (METERS_PER_DEGREE * cosine)),
        )
        return MapRegion(centerLatitude, centerLongitude, latitudeDelta, longitudeDelta)
    }
}
