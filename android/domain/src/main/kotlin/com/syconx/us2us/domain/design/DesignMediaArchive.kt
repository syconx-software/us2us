package com.syconx.us2us.domain.design

import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The contact screen's media in the fixtures (proposed): older photos, videos and files of the
 * design's chats, beyond what their conversations show (enough of them that the grid pages, deniz:
 * 40 photos and videos) in the design's painted stand-ins. Times run back from the design's day
 * (8 October 2026) through September and August, in both directions.
 */
object DesignMediaArchive {
    fun archive(chat: ChatId, english: Boolean): List<Message> = when (chat.value) {
        "deniz" -> visualArchive("dz", count = 40, videoEvery = 5) + documentArchive("dz", english, count = 6)
        "mert_k" -> visualArchive("mk", count = 12, videoEvery = 3) + documentArchive("mk", english, count = 9)
        else -> emptyList()
    }

    /** [count] photos and videos, one every ~19 hours back from 7 October. */
    private fun visualArchive(prefix: String, count: Int, videoEvery: Int): List<Message> {
        val start = DesignFixtures.date(day = 7, hour = 20, minute = 15)
        return (0 until count).map { index ->
            val sentAt = start.minusSeconds(index * 19L * 3_600 + (index % 4) * 7L * 60)
            val direction = if (index % 3 == 1) MessageDirection.Outgoing else MessageDirection.Incoming
            val artwork = if (index % 2 == 0) DesignArtwork.NightPier else DesignArtwork.SunsetVideo
            val content = if (index % videoEvery == videoEvery - 1) {
                MessageContent.Video(
                    MediaSource.Artwork(artwork), duration = (9 + (index * 7) % 50).seconds, byteCount = 4_200_000L + index * 310_000L,
                )
            } else {
                MessageContent.Photo(MediaSource.Artwork(artwork), caption = null)
            }
            Message(
                id = MessageId("$prefix-media-$index"), direction = direction, sentAt = sentAt, content = content,
                status = if (direction == MessageDirection.Outgoing) MessageStatus.Read else null,
            )
        }
    }

    private data class ArchiveFile(val tr: String, val en: String, val bytes: Long, val type: String, val pages: Int?)

    private val files = listOf(
        ArchiveFile("ucak-bileti.pdf", "plane-ticket.pdf", 184_000, "PDF", 2),
        ArchiveFile("toplanti-notlari.docx", "meeting-notes.docx", 42_000, "DOCX", null),
        ArchiveFile("butce-2026.xlsx", "budget-2026.xlsx", 96_000, "XLSX", null),
        ArchiveFile("tatil.mov", "holiday.mov", 18_600_000, "MOV", null),
        ArchiveFile("kira-makbuzu.pdf", "rent-receipt.pdf", 310_000, "PDF", 1),
        ArchiveFile("ev-plani.png", "floor-plan.png", 1_250_000, "PNG", null),
        ArchiveFile("fotograflar.zip", "photos.zip", 24_800_000, "ZIP", null),
        ArchiveFile("sigorta-police.pdf", "insurance-policy.pdf", 2_900_000, "PDF", 12),
        ArchiveFile("davetiye.pdf", "invitation.pdf", 640_000, "PDF", 1),
    )

    /** Files of everyday life, newest first from 6 October. */
    private fun documentArchive(prefix: String, english: Boolean, count: Int): List<Message> {
        val start: Instant = DesignFixtures.date(day = 6, hour = 11, minute = 20)
        return (0 until minOf(count, files.size)).map { index ->
            val file = files[index]
            val sentAt = start.minusSeconds(index * 4L * 86_400 + (index % 3) * 3L * 3_600)
            val direction = if (index % 2 == 0) MessageDirection.Incoming else MessageDirection.Outgoing
            Message(
                id = MessageId("$prefix-file-$index"), direction = direction, sentAt = sentAt,
                content = MessageContent.File(name = if (english) file.en else file.tr, byteCount = file.bytes, typeLabel = file.type, pageCount = file.pages),
                status = if (direction == MessageDirection.Outgoing) MessageStatus.Read else null,
            )
        }
    }

    /**
     * One page of a chat's media, newest first: photos and videos ([ChatMediaKind.Visual]) or files
     * ([ChatMediaKind.Documents]), never view-once, disappearing, deleted, system lines or a pick
     * still being prepared, older than [before] when set (an empty page when [before] is not there
     * any more).
     */
    fun page(messages: List<Message>, kind: ChatMediaKind, before: MessageId?, limit: Int): List<Message> {
        if (limit <= 0) return emptyList()
        val matching = messages.withIndex()
            .filter { (_, message) ->
                // Never disappearing items (§8.1), like the runtime's list.
                if (message.isDeleted || message.isSystem || message.isPendingMedia || message.isDisappearing) return@filter false
                when (message.content) {
                    is MessageContent.Photo, is MessageContent.Video -> kind == ChatMediaKind.Visual
                    is MessageContent.File -> kind == ChatMediaKind.Documents
                    else -> false
                }
            }
            // Newest first; the later one in the conversation first on a tie.
            .sortedWith(compareByDescending<IndexedValue<Message>> { it.value.sentAt }.thenByDescending { it.index })
            .map { it.value }
        if (before == null) return matching.take(limit)
        val index = matching.indexOfFirst { it.id == before }
        if (index < 0) return emptyList()
        return matching.drop(index + 1).take(limit)
    }
}
