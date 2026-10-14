package com.syconx.us2us.feature.media

import androidx.compose.runtime.Immutable
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a full-screen viewer shows: view-once media (5.6, its own viewer), an ordinary photo
 * (“tap photo → media viewer”) or a video (“video → player”). The iOS `ViewerItem`.
 */
@Immutable
data class ViewerItem(
    val kind: Kind,
    val messageId: MessageId?,
    val sender: String,
    val sentAt: Instant,
    /** What shows at once: the view-once media, the bubble's photo (its thumbnail or already the full photo), the video's poster. */
    val media: MediaSource,
    val isVideo: Boolean,
    val caption: String? = null,
    /** Video: the length the message announced (shown while it loads). */
    val duration: Duration = Duration.ZERO,
    /** Harness: start with the screenshot warning shown (the 5.6 reference). */
    val showsScreenshotWarning: Boolean = false,
    /** Harness: start in the screen-recording state. */
    val simulatesCapture: Boolean = false,
    /** Harness: the photo starts zoomed in (double-tap zoom). */
    val startsZoomed: Boolean = false,
    /** Harness: the video starts paused at this point. */
    val startTime: Duration? = null,
) {
    enum class Kind { ViewOnce, Photo, Video }

    companion object {
        /**
         * The viewer a tap on [message] opens (iOS `ConversationViewModel.openMedia`): a photo, a
         * video, or a video or picture sent as a file; `null` for anything else. [sender] is the
         * name the top bar shows (the own name for outgoing messages).
         */
        fun forMessage(message: Message, sender: String): ViewerItem? = when (val content = message.content) {
            is MessageContent.Photo -> ViewerItem(
                kind = Kind.Photo, messageId = message.id, sender = sender, sentAt = message.sentAt, media = content.source,
                isVideo = false, caption = content.caption,
            )
            is MessageContent.Video -> ViewerItem(
                kind = Kind.Video, messageId = message.id, sender = sender, sentAt = message.sentAt, media = content.source,
                isVideo = true, duration = content.duration,
            )
            is MessageContent.File -> when {
                MediaFileKind.of(content.name) == MediaFileKind.Movie -> ViewerItem(
                    kind = Kind.Video, messageId = message.id, sender = sender, sentAt = message.sentAt, media = EmptyMedia,
                    isVideo = true,
                )
                MediaFileKind.opensInPhotoViewer(content.name) -> ViewerItem(
                    kind = Kind.Photo, messageId = message.id, sender = sender, sentAt = message.sentAt, media = EmptyMedia,
                    isVideo = false,
                )
                else -> null
            }
            else -> null
        }

        /** Nothing to show at once (a file's bytes come with its payload). */
        internal val EmptyMedia: MediaSource = MediaSource.Memory(Bytes.Empty)

        /** The 5.6 reference: deniz's view-once photo of 18:40 (the night-pier painting). */
        fun designViewOnce(showsScreenshotWarning: Boolean, simulatesCapture: Boolean): ViewerItem = ViewerItem(
            kind = Kind.ViewOnce, messageId = null, sender = "deniz", sentAt = DesignFixtures.date(day = 8, hour = 18, minute = 40),
            media = MediaSource.Artwork(DesignArtwork.NightPier), isVideo = false,
            showsScreenshotWarning = showsScreenshotWarning, simulatesCapture = simulatesCapture,
        )
    }
}

/**
 * The media states the conversation harness opens directly (`conversation:<chat>/<state>`): the
 * viewers read the harness state id from `LocalHarnessState` and start as the iOS harness has them.
 */
object MediaHarnessStates {
    /** `5.1-viewer-zoomed`: the photo starts zoomed in 2.5×. */
    const val VIEWER_ZOOMED = "5.1-viewer-zoomed"

    /** `5.1-save-notice`: the photo viewer with the one-time save explanation up. */
    const val SAVE_NOTICE = "5.1-save-notice"

    /** `5.2-player`: the video paused at 0:07, the controls shown. */
    const val PLAYER = "5.2-player"

    /** `5.2-player-loading`: the clip still on its way. */
    const val PLAYER_LOADING = "5.2-player-loading"

    /** `5.6`: the view-once viewer after a screenshot. */
    const val VIEW_ONCE = "5.6"
    const val VIEW_ONCE_CLEAN = "5.6-clean"
    const val VIEW_ONCE_CAPTURED = "5.6-captured"

    /** The player's start in the `5.2-player` state. */
    val playerStartTime: Duration = 7.seconds

    /** Whether [state] is one of the drawn 5.6 states (the design's own photo, not a message's). */
    fun isDesignViewOnce(state: String?): Boolean = state == VIEW_ONCE || state == VIEW_ONCE_CLEAN || state == VIEW_ONCE_CAPTURED
}
