package com.syconx.us2us.domain

import java.time.Duration

/** Presentation only: each photo keeps its own encrypted message, actions and lifetime. */
object ConversationPhotos {
    const val ALBUM_LIMIT = 10
    private const val ALBUM_SECONDS = 60L

    fun isBrowsable(message: Message): Boolean =
        message.content is MessageContent.Photo && !message.isPendingMedia

    /** An album may show Read only after every outgoing photo is read. */
    fun status(photos: List<Message>): MessageStatus? = photos.mapNotNull { it.status }.minByOrNull { it.ordinal }

    /** Never hide replies, reactions, saved marks, failures or disappearing timers inside an album. */
    private fun canGroup(message: Message): Boolean = isBrowsable(message) &&
        !message.isDisappearing && message.replyTo == null && message.reactions.isEmpty() &&
        !message.savedByPeer && message.status != MessageStatus.Failed

    fun canAppend(album: List<Message>, next: Message): Boolean {
        val first = album.firstOrNull() ?: return false
        if (album.size >= ALBUM_LIMIT || !canGroup(first) || !canGroup(next)) return false
        if (first.direction != next.direction || first.community?.sender?.account != next.community?.sender?.account) return false
        // A second caption starts another album; nothing a sender wrote is hidden.
        if (!(next.content as MessageContent.Photo).caption.isNullOrBlank()) return false
        val elapsed = Duration.between(first.sentAt, next.sentAt)
        return !elapsed.isNegative && elapsed <= Duration.ofSeconds(ALBUM_SECONDS)
    }

    /** A single linear pass, preserving conversation order and every message ID. */
    fun groups(messages: List<Message>): List<List<Message>> {
        val result = ArrayList<MutableList<Message>>()
        for (message in messages) {
            val last = result.lastOrNull()
            if (last != null && canAppend(last, message)) last += message else result += mutableListOf(message)
        }
        return result
    }
}
