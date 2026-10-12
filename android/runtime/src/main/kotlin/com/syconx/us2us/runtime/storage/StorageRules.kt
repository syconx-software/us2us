package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.ConversationSettings
import com.syconx.us2us.core.proto.DataMessage
import kotlin.time.Duration.Companion.hours

/** The content limits and merge rules the storage applies (PROTOCOL.md §8, brp.proto). */
internal object StorageRules {
    /** `ProfileUpdate.display_name`, in UTF-8 bytes. */
    const val MAX_DISPLAY_NAME_BYTES = 64

    /** Ghost-mode timeouts the protocol allows, in seconds. */
    val GHOST_TIMEOUT_SECONDS = 60L..604_800L

    /** Ids per `ReceiptMessage` (§8.4). */
    const val MAX_RECEIPT_IDS = 1000

    /**
     * How far ahead of our clock a contact's `updated_at` may be. Anything later is clamped: a
     * far-future stamp (up to the uint64 maximum) would otherwise win last-writer-wins forever, so
     * the user could never change the conversation's privacy settings again.
     */
    val MAX_CLOCK_SKEW = 24.hours

    fun isValidGhostTimeout(seconds: Int): Boolean = seconds.unsigned() in GHOST_TIMEOUT_SECONDS

    /** Lexicographic order of the 16 raw bytes; breaks last-writer-wins ties. */
    fun compare(lhs: AccountId, rhs: AccountId): Int {
        val a = lhs.toBytes()
        val b = rhs.toBytes()
        for (i in a.indices) {
            val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return 0
    }

    /**
     * Last-writer-wins merge (brp.proto): the later `updated_at` wins; on a tie the settings written
     * by the lexicographically smaller account id win.
     */
    fun resolve(
        lhs: ConversationSettings,
        lhsAuthor: AccountId,
        rhs: ConversationSettings,
        rhsAuthor: AccountId,
    ): ConversationSettings {
        if (lhs.updatedAt != rhs.updatedAt) {
            return if (java.lang.Long.compareUnsigned(lhs.updatedAt, rhs.updatedAt) > 0) lhs else rhs
        }
        return if (compare(lhsAuthor, rhsAuthor) <= 0) lhs else rhs
    }
}

/**
 * A view-once item carries nothing that can be seen without opening it: no inline thumbnail, no
 * caption, no waveform. Applied to what is sent and to what is stored, in both directions.
 */
fun DataMessage.strippingViewOncePreview(): DataMessage {
    if (!viewOnce) return this
    fun Attachment.stripped(): Attachment =
        toBuilder().setThumbnail(ByteString.EMPTY).setCaption("").setWaveform(ByteString.EMPTY).build()
    return when {
        hasAttachment() -> toBuilder().setAttachment(attachment.stripped()).build()
        hasSticker() && sticker.hasCustom() ->
            toBuilder().setSticker(sticker.toBuilder().setCustom(sticker.custom.stripped())).build()
        else -> this
    }
}
