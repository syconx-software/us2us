package com.syconx.us2us.domain

/** What the one-time save explanation names (“fotoğrafı / videoyu / dosyayı”). */
enum class SaveNoticeKind { Photo, Video, File }

/** What may leave the app from one of its viewers (“Kaydet”, “Dosyalara kaydet”, the share sheet). */
object MediaExportPolicy {
    /**
     * A photo, video or file that is here (or can be fetched): never view-once (its own kind, never
     * here), disappearing (a saved copy would outlive it, as copying is off for them, 5.3), deleted,
     * from a request that is still held, gone, or a pick still being prepared.
     */
    fun allowsExport(message: Message): Boolean {
        if (message.isDisappearing || message.isDeleted || message.isPendingMedia || message.isSystem) return false
        when (message.content) {
            is MessageContent.Photo, is MessageContent.Video, is MessageContent.File -> Unit
            else -> return false
        }
        return when (message.mediaAvailability) {
            MediaAvailability.Ready, MediaAvailability.Remote, is MediaAvailability.Downloading -> true
            MediaAvailability.Held, MediaAvailability.Unavailable, MediaAvailability.Preparing,
            MediaAvailability.PreparationFailed,
            -> false
        }
    }

    /** “Kaydet” saves to the gallery: photos and videos (a video sent as a file too). */
    fun savesToGallery(message: Message): Boolean {
        if (!allowsExport(message)) return false
        return when (val content = message.content) {
            is MessageContent.Photo, is MessageContent.Video -> true
            is MessageContent.File -> MediaFileKind.of(content.name).let { it == MediaFileKind.Image || it == MediaFileKind.Movie }
            else -> false
        }
    }

    /** Saved as a video. */
    fun isVideo(message: Message): Boolean = when (val content = message.content) {
        is MessageContent.Video -> true
        is MessageContent.File -> MediaFileKind.of(content.name) == MediaFileKind.Movie
        else -> false
    }

    /** The sender is told (SAVED): something received. The user's own photo saved again is only noted here, so it never asks first. */
    fun tellsSender(message: Message): Boolean = !message.isOutgoing

    /** What the explanation names. */
    fun noticeKind(message: Message): SaveNoticeKind = when (val content = message.content) {
        is MessageContent.Photo -> SaveNoticeKind.Photo
        is MessageContent.Video -> SaveNoticeKind.Video
        is MessageContent.File -> when (MediaFileKind.of(content.name)) {
            MediaFileKind.Image -> SaveNoticeKind.Photo
            MediaFileKind.Movie -> SaveNoticeKind.Video
            else -> SaveNoticeKind.File
        }
        else -> SaveNoticeKind.File
    }
}
