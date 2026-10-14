package com.syconx.us2us.domain

import java.net.URI
import kotlinx.coroutines.Deferred

/**
 * One photo or video picked in “+” › Fotoğraf ve video (the system photo picker, out of process:
 * no photo-library permission). A pick holds up to [PickedMediaPlan.SELECTION_LIMIT] of them, in
 * the order they were selected.
 */
data class PickedMediaItem(val kind: Kind, val source: Source) {
    enum class Kind { Photo, Video }

    sealed interface Source {
        /**
         * The live picker's copy in the app's private pick folder, still being handed over when the
         * pick returns: a long or cloud item takes a while. `null`: it could not be read.
         */
        data class Copy(val file: Deferred<URI?>) : Source

        /** One of the design's pictures (the fixtures send the design's content). */
        data class Artwork(val artwork: DesignArtwork) : Source
    }
}

/** One message of a pick: the item and, on the first photo only, the composer's text as its caption. */
data class PickedMediaMessage(val item: PickedMediaItem, val caption: String?)

/**
 * How a pick leaves: every item as a message of its own, in the order it was selected, never an
 * album, so each one can fail, be sent again or be deleted on its own, and an older app on the
 * other side shows them all.
 *
 * * At most [SELECTION_LIMIT] items (the picker's own limit; anything beyond it is dropped).
 * * The composer's text, if any, becomes the caption of the first photo: trimmed, and only while it
 *   fits the protocol's caption limit ([ContentLimits.MAX_CAPTION_BYTES]); otherwise it stays in the
 *   composer. A video carries none: its bubble has no caption.
 * * View-once is a single item without a caption (a caption would be visible before it is opened;
 *   the runtime refuses one).
 */
object PickedMediaPlan {
    /** Ten at a time. */
    const val SELECTION_LIMIT = 10

    /** The picker's limit: one for view-once. */
    fun limit(viewOnce: Boolean): Int = if (viewOnce) 1 else SELECTION_LIMIT

    /** The messages of a pick, in the order the items were selected. */
    fun messages(items: List<PickedMediaItem>, caption: String?, viewOnce: Boolean = false): List<PickedMediaMessage> {
        val kept = items.take(limit(viewOnce))
        val text = if (viewOnce) null else usableCaption(caption)
        val captioned = if (text == null) -1 else kept.indexOfFirst { it.kind == PickedMediaItem.Kind.Photo }
        return kept.mapIndexed { index, item -> PickedMediaMessage(item, if (index == captioned) text else null) }
    }

    /** The composer's text as a caption, or `null` when there is none or it is too long for one (it then stays in the composer). */
    fun usableCaption(text: String?): String? {
        val trimmed = text?.let(UnicodeText::trimWhitespace) ?: return null
        if (trimmed.isEmpty() || trimmed.encodeToByteArray().size > ContentLimits.MAX_CAPTION_BYTES) return null
        return trimmed
    }
}
