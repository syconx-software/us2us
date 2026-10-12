package com.syconx.us2us.live

import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.LiveLocationSnapshot
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.LiveShare
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessageDirection as RuntimeDirection
import com.syconx.us2us.runtime.engine.MessageItem
import java.time.Instant

// 5.2 › Canlı konum haritası in the live service: the open conversation's live shares with their
// latest positions, read from the runtime's open messages (memory only: the app adds no copy of its
// own), and this device's position for the map (DevicePositionWatcher, never sent).
//
// The runtime itself keeps only each share's latest position, sealed inside the bubble in SQLCipher
// and replaced in place (PROTOCOL §8.3); the relay never stores ticks.

/** One live-location message of the open conversation, field by field (LiveMapping decides what it means). */
data class LiveLocationRecord(
    val id: Bytes,
    val outgoing: Boolean,
    val sentAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    /** Degrees; negative when unknown. */
    val heading: Float,
    val expiresAt: Instant,
    val sequence: Long,
    val stopped: Boolean,
) {
    companion object {
        fun of(item: MessageItem): LiveLocationRecord? {
            if (item.direction == RuntimeDirection.SYSTEM) return null
            val live = (item.body as? MessageBody.LiveLocation)?.location ?: return null
            return LiveLocationRecord(
                id = item.messageId.toBytes(),
                outgoing = item.direction == RuntimeDirection.OUTGOING,
                sentAt = item.sentAt,
                latitude = live.latitude,
                longitude = live.longitude,
                accuracy = live.accuracyMeters,
                heading = live.headingDegrees,
                expiresAt = live.expiresAt,
                sequence = live.sequence,
                stopped = live.stopped,
            )
        }
    }
}

/** When a share's position last changed, as this app saw it (`LiveLocationInfo` carries no time of its own). */
data class LiveTick(val sequence: Long, val at: Instant?)

/**
 * Keeps, per live share, the sequence last seen and when it changed: a share still at sequence 0 is
 * its start (`sentAt`); a later sequence seen for the first time has no known time; a new sequence
 * is [now]. Shares that are gone are dropped.
 */
fun LiveMapping.liveTicks(records: List<LiveLocationRecord>, previous: Map<Bytes, LiveTick>, now: Instant): Map<Bytes, LiveTick> {
    val ticks = HashMap<Bytes, LiveTick>(records.size)
    for (record in records) {
        val old = previous[record.id]
        ticks[record.id] = when {
            old != null -> if (old.sequence == record.sequence) old else LiveTick(record.sequence, now)
            else -> LiveTick(record.sequence, if (record.sequence == 0L) record.sentAt else null)
        }
    }
    return ticks
}

/** The conversation's live shares, oldest first. */
fun LiveMapping.liveLocationSnapshot(records: List<LiveLocationRecord>, ticks: Map<Bytes, LiveTick>): LiveLocationSnapshot = LiveLocationSnapshot(
    shares = records.map { record ->
        LiveShare(
            id = messageId(record.id),
            direction = if (record.outgoing) MessageDirection.Outgoing else MessageDirection.Incoming,
            startedAt = record.sentAt,
            position = LivePosition(
                latitude = record.latitude,
                longitude = record.longitude,
                accuracy = if (record.accuracy.isFinite()) maxOf(0.0, record.accuracy.toDouble()) else 0.0,
                heading = if (record.heading >= 0 && record.heading.isFinite()) record.heading.toDouble() else null,
                updatedAt = ticks[record.id]?.at,
            ),
            expiresAt = record.expiresAt,
            isStopped = record.stopped,
        )
    },
)
