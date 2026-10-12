package com.syconx.us2us.feature.conversation

import androidx.compose.runtime.Immutable
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.nav.MessageId

/** One row of the 5.1 column: the message and how the column draws it. */
@Immutable
data class ConversationRow(
    val message: Message,
    /** The last bubble of a run (C15 tail). */
    val isTail: Boolean,
    /** The state spelled out under the bubble (5.1 item 8; failed messages). */
    val showsStatusSentence: Boolean,
) {
    val id: MessageId get() = message.id
}

/** A removed message whose row is still collapsing in the column (5.3 “Benden sil”), with the row it followed, so it keeps its place. */
@Immutable
data class LeavingRow(val row: ConversationRow, val after: MessageId?)

/** The rules that turn a conversation into the rows of its column (tails, status sentences, leaving rows). */
object ConversationRows {
    fun rows(messages: List<Message>): List<ConversationRow> {
        val sentences = statusSentenceIds(messages)
        return messages.mapIndexed { index, message ->
            ConversationRow(message, isTail = isTail(index, messages), showsStatusSentence = message.id in sentences)
        }
    }

    /** Only the newest outgoing message shows its state as a sentence (5.1). */
    fun lastOutgoingId(messages: List<Message>): MessageId? =
        messages.lastOrNull { it.isOutgoing && !it.isSystem && !it.isDeleted }?.id

    /**
     * Messages that spell out their state under the bubble: the newest outgoing one while nothing
     * has come back after it (once the other side has written, its state is evident: 5.2 shows no
     * sentence), and every message that could not be delivered (“İletilemedi” + “Tekrar gönder”).
     */
    fun statusSentenceIds(messages: List<Message>): Set<MessageId> {
        val ids = messages.filter { it.isOutgoing && it.status == MessageStatus.Failed && !it.isDeleted }.mapTo(HashSet()) { it.id }
        val last = lastOutgoingId(messages) ?: return ids
        val index = messages.indexOfLast { it.id == last }
        val answered = messages.subList(index + 1, messages.size).any { !it.isSystem && !it.isOutgoing }
        if (!answered) ids += last
        return ids
    }

    /** The last bubble of a run of the same direction gets the tail (C15). */
    fun isTail(index: Int, messages: List<Message>): Boolean {
        val message = messages[index]
        if (message.isSystem) return false
        val next = messages.getOrNull(index + 1) ?: return true
        if (next.isSystem) return true
        return next.direction != message.direction
    }

    /** [rows] with the [leaving] rows at their places. */
    fun displayed(rows: List<ConversationRow>, leaving: List<LeavingRow>): List<ConversationRow> {
        if (leaving.isEmpty()) return rows
        val result = rows.toMutableList()
        for (item in leaving) {
            val after = item.after
            if (after != null) {
                val index = result.indexOfLast { it.id == after }.let { if (it >= 0) it + 1 else result.size }
                result.add(index, item.row)
            } else {
                result.add(0, item.row)
            }
        }
        return result
    }

    /** What changed between two versions of the rows (both in conversation order). */
    data class Changes(
        /** Rows that left, each with the row it followed. */
        val leaving: List<LeavingRow>,
        /** Rows whose kind changed, with the message as it was. */
        val kindChanges: Map<MessageId, Message>,
        /** Rows after the last one that was already there. */
        val arriving: List<MessageId>,
    )

    /**
     * One pass over the old and the new rows: removed rows start leaving, rows whose kind changed
     * start morphing, rows after the last one that was already there start arriving.
     */
    fun changes(old: List<ConversationRow>, new: List<ConversationRow>): Changes {
        val current = new.mapTo(HashSet()) { it.id }
        val leaving = ArrayList<LeavingRow>()
        val kinds = LinkedHashMap<MessageId, Message>()
        var previous: MessageId? = null
        var j = 0
        var lastKept = -1
        for (row in old) {
            if (row.id !in current) {
                leaving += LeavingRow(row, previous)
            } else {
                while (j < new.size && new[j].id != row.id) j++
                if (j < new.size) {
                    if (new[j].message.contentKind != row.message.contentKind) kinds[row.id] = row.message
                    lastKept = j
                    j++
                }
            }
            previous = row.id
        }
        val firstNew = when {
            lastKept >= 0 -> lastKept + 1
            old.isEmpty() -> 0
            else -> new.size
        }
        return Changes(leaving, kinds, new.subList(firstNew, new.size).map { it.id })
    }
}
