package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.VoicePlaybackRate
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.theme.U2Theme
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Specimens of every bubble kind, incoming and outgoing, and of the proposed states (media on the
 * relay, downloading, gone, being prepared; ended and opened kinds; typing; system lines; chips;
 * sentences and legend rows), in dark and light. For review: they have no drawn reference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class BubbleSpecimenShotTest {
    @get:Rule
    val host = ShotHost(environment = BubbleShotEnvironment, outputDir = BubbleShotDir)

    @Test
    fun kindsDark() = kinds(ShotTheme.Dark)

    @Test
    fun kindsLight() = kinds(ShotTheme.Light)

    @Test
    fun statesDark() = states(ShotTheme.Dark)

    @Test
    fun statesLight() = states(ShotTheme.Light)

    private fun kinds(theme: ShotTheme) {
        for (direction in MessageDirection.entries) {
            val side = if (direction == MessageDirection.Incoming) "in" else "out"
            host.render(theme, "tr") { ConversationSpecimen(kindsPage1(direction), top = 40.dp, showsComposer = false) }
            host.capture("bubbles-kinds-$side-1", design = null)
            host.render(theme, "tr") { ConversationSpecimen(kindsPage2(direction), top = 40.dp, showsComposer = false) }
            host.capture("bubbles-kinds-$side-2", design = null)
        }
    }

    private fun states(theme: ShotTheme) {
        host.render(theme, "tr") { ConversationSpecimen(mediaStates(), top = 40.dp, showsComposer = false) }
        host.capture("bubbles-states-media", design = null)
        host.render(theme, "tr") {
            CompositionLocalProvider(LocalVoicePlayback provides PlayingHalfway(MessageId("f9"))) {
                ConversationSpecimen(fileAndVoiceStates(), top = 40.dp, showsComposer = false)
            }
        }
        host.capture("bubbles-states-files", design = null)
        host.render(theme, "tr") { MiscStates() }
        host.capture("bubbles-states-misc", design = null)
        host.render(theme, "en") { ConversationSpecimen(DesignFixtures.mertMessages(english = true).drop(4), top = 20.66.dp) }
        host.capture("bubbles-5.2-en", design = null)
    }

    private val at: Instant get() = DesignFixtures.date(day = 8, hour = 18, minute = 30)

    private fun message(
        id: String,
        direction: MessageDirection,
        content: MessageContent,
        availability: MediaAvailability = MediaAvailability.Ready,
        reactions: List<Reaction> = emptyList(),
        disappearing: DisappearingTimer = DisappearingTimer.Off,
        reply: QuotedMessage? = null,
        played: Boolean = false,
        saved: Boolean = false,
        status: MessageStatus? = MessageStatus.Delivered,
    ) = Message(
        id = MessageId(id),
        direction = direction,
        sentAt = at,
        content = content,
        status = if (direction == MessageDirection.Outgoing) status else null,
        replyTo = reply,
        reactions = reactions,
        disappearing = disappearing,
        mediaAvailability = availability,
        isVoicePlayed = played,
        savedByPeer = saved,
    )

    private fun kindsPage1(d: MessageDirection): List<Message> = listOf(
        message("k1", d, MessageContent.Text("Bu akşam boş musun?")),
        message("k2", d, MessageContent.Text("Kapı kodu 4471, kısa süreli"), disappearing = DisappearingTimer.FiveMinutes),
        message(
            "k3", d, MessageContent.Text("Uzun bir mesaj: iskelede buluşalım, sonra sahil boyunca yürürüz ve vapura bineriz."),
            reactions = listOf(Reaction("👍", count = 2)),
        ),
        message(
            "k4", d, MessageContent.Text("Tamam, geliyorum"),
            reply = QuotedMessage(author = "deniz", kind = QuotedMessage.Kind.Text("Bu akşam boş musun?")),
        ),
        message(
            "k5", d, MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption = null),
            reactions = listOf(Reaction("❤️")), saved = d == MessageDirection.Outgoing,
        ),
        message("k6", d, MessageContent.Voice(42.seconds, DesignFixtures.voiceWaveform, DesignFixtures.VOICE_PLAYED_FRACTION), played = false),
    )

    private fun kindsPage2(d: MessageDirection): List<Message> = listOf(
        message("k7", d, MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), 18.seconds, 12_400_000)),
        message("k8", d, MessageContent.File("sunum-notlari.docx", 845_000, "DOCX", null)),
        message("k9", d, MessageContent.Location("Moda İskelesi", "Kadıköy, İstanbul", 40.98, 29.02)),
        message("k10", d, MessageContent.LiveLocation(DesignFixtures.referenceDate.plusSeconds(47 * 60), isActive = true)),
        message("k11", d, MessageContent.Deleted),
        message("k12", d, MessageContent.ViewOnce(isVideo = true, state = ViewOnceState.Unopened)),
        message("k13", d, MessageContent.TextSticker("tamam!")),
    )

    private fun mediaStates(): List<Message> {
        val photo = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption = "Uzaktaki fotoğraf")
        val video = MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), 18.seconds, 12_400_000)
        return listOf(
            message("s1", MessageDirection.Incoming, photo, MediaAvailability.Remote),
            message("s2", MessageDirection.Outgoing, video, MediaAvailability.Preparing),
            message("s3", MessageDirection.Incoming, video, MediaAvailability.Downloading(0.4)),
            message("s4", MessageDirection.Outgoing, video, MediaAvailability.PreparationFailed, status = MessageStatus.Failed),
        )
    }

    private fun fileAndVoiceStates(): List<Message> {
        val file = MessageContent.File("kira-sozlesmesi.pdf", 2_100_000, "PDF", 4)
        val voice = MessageContent.Voice(42.seconds, DesignFixtures.voiceWaveform, 0.0)
        return listOf(
            message("f1", MessageDirection.Incoming, file, MediaAvailability.Remote),
            message("f2", MessageDirection.Incoming, file, MediaAvailability.Downloading(0.62)),
            message("f3", MessageDirection.Incoming, file, MediaAvailability.Unavailable),

            message("f5", MessageDirection.Incoming, voice, MediaAvailability.Remote),
            message("f6", MessageDirection.Incoming, voice, MediaAvailability.Downloading(null)),
            message("f7", MessageDirection.Outgoing, voice, played = true, status = MessageStatus.Read),
            message("f9", MessageDirection.Incoming, voice, played = true),
            message("f8", MessageDirection.Incoming, MessageContent.LiveLocation(DesignFixtures.referenceDate.minusSeconds(60), isActive = true)),
        )
    }

    @Composable
    private fun MiscStates() {
        val events = listOf(
            SystemEvent.GhostEnabled(GhostPeriod.SevenDays),
            SystemEvent.GhostDisabled,
            SystemEvent.DisappearingChanged(DisappearingTimer.OneHour),
            SystemEvent.ScreenshotTaken("deniz"),
            SystemEvent.SafetyNumberChanged,
            SystemEvent.SafetyNumberAccepted,
            SystemEvent.SafetyNumberVerified,
        )
        Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
            Column(Modifier.padding(start = 14.dp, top = 40.dp, end = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                events.forEachIndexed { index, event ->
                    MessageBubbleView(message("e$index", MessageDirection.Incoming, MessageContent.System(event)))
                }
                TypingIndicatorBubble()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    MessageBubbleView(message("v1", MessageDirection.Incoming, MessageContent.ViewOnce(false, ViewOnceState.Opened)))
                }
                MessageBubbleView(message("v2", MessageDirection.Incoming, MessageContent.ViewOnce(false, ViewOnceState.Expired)))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    MessageBubbleView(message("x1", MessageDirection.Incoming, MessageContent.Sticker("us2us.unknown", 9999, "🦊", "")))
                    MessageBubbleView(message("x2", MessageDirection.Incoming, MessageContent.Sticker("us2us.unknown", 9998, "", "")))
                    MessageBubbleView(message("x3", MessageDirection.Outgoing, MessageContent.Sticker("us2us.cups", 1, "☕", ""), status = MessageStatus.Read))
                }
                MessageStatusSentence(MessageStatus.Failed, onResend = {})
                MessageStatusSentence(MessageStatus.Delivered)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    for (status in listOf(MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read, MessageStatus.Failed)) {
                        MessageStatusLegendRow(status)
                    }
                }
            }
        }
    }

    /** The voice message [activeId] playing at 1,5×, half way through. */
    private class PlayingHalfway(override val activeId: MessageId) : VoicePlaybackState {
        override val isPlaying = true
        override val isLoading = false
        override val isScrubbing = false
        override val duration: Duration = 42.seconds
        override val currentTime: Duration = 21.seconds
        override val rate = VoicePlaybackRate.OneAndHalf

        override fun shownFraction(id: MessageId, fallback: Double): Double = if (id == activeId) 0.5 else fallback

        override fun beginScrub(id: MessageId) = Unit

        override fun scrub(id: MessageId, fraction: Double) = Unit

        override fun endScrub(id: MessageId) = Unit

        override fun skip(id: MessageId, by: Duration) = Unit

        override fun cycleRate() = Unit
    }
}
