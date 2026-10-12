package com.syconx.us2us.ui.bubbles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Reaction
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.VoicePlaybackRate
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.media.LocalMediaClock
import com.syconx.us2us.ui.theme.U2Theme
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the bubbles do when touched, and how they change state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "tr-rTR-w402dp-h874dp-xxhdpi")
class BubbleInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now: Instant get() = DesignFixtures.referenceDate

    private fun show(clock: DesignClock = DesignClock.frozen(DesignFixtures.referenceDate), content: @Composable () -> Unit) {
        compose.setContent {
            U2Theme(dark = true, reduceMotion = false) {
                CompositionLocalProvider(LocalMediaClock provides clock) { Box { content() } }
            }
        }
    }

    private fun message(content: MessageContent, direction: MessageDirection = MessageDirection.Incoming, availability: MediaAvailability = MediaAvailability.Ready) =
        Message(
            id = MessageId("t1"),
            direction = direction,
            sentAt = now,
            content = content,
            status = if (direction == MessageDirection.Outgoing) MessageStatus.Sent else null,
            mediaAvailability = availability,
        )

    private val photo = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption = null)

    @Test
    fun aPhotoOpensTheViewer() {
        var opened = 0
        show { MessageBubbleView(message(photo), onOpenMedia = { opened++ }) }
        compose.onNodeWithContentDescription("Fotoğraf").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aPhotoBeingPreparedOpensNothing() {
        var opened = 0
        show { MessageBubbleView(message(photo, MessageDirection.Outgoing, MediaAvailability.Preparing), onOpenMedia = { opened++ }) }
        compose.onNodeWithContentDescription("Fotoğraf").performClick()
        assertEquals(0, opened)
    }

    @Test
    fun aLongPressOpensTheActionsAndReportsThePress() {
        var actions = 0
        val presses = mutableListOf<Boolean>()
        show {
            MessageBubbleView(message(MessageContent.Text("Bu akşam boş musun?")), onLongPress = { actions++ }, onPressedChange = { presses += it })
        }
        compose.onNodeWithText("Bu akşam boş musun?").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, actions)
        assertEquals(listOf(true, false), presses)
    }

    @Test
    fun aLongPressOnAPhotoDoesNotOpenIt() {
        var opened = 0
        var actions = 0
        show { MessageBubbleView(message(photo), onOpenMedia = { opened++ }, onLongPress = { actions++ }) }
        compose.onNodeWithContentDescription("Fotoğraf").performTouchInput { longClick() }
        assertEquals(0, opened)
        assertEquals(1, actions)
    }

    @Test
    fun onlyAnUnopenedViewOnceOpens() {
        var opened = 0
        var state by mutableStateOf(ViewOnceState.Unopened)
        show { MessageBubbleView(message(MessageContent.ViewOnce(isVideo = false, state = state)), onOpenViewOnce = { opened++ }) }
        compose.onNodeWithText("Tek görüntülemelik · açmak için dokun").performClick()
        assertEquals(1, opened)
        state = ViewOnceState.Opened
        compose.waitForIdle()
        compose.onNodeWithText("Açıldı").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aLiveLocationOpensTheMapAndStopsFromItsPill() {
        var map = 0
        var stopped = 0
        val live = MessageContent.LiveLocation(expiresAt = now.plusSeconds(47 * 60), isActive = true)
        show { MessageBubbleView(message(live, MessageDirection.Outgoing), onOpenLiveMap = { map++ }, onStopLiveLocation = { stopped++ }) }
        compose.onNodeWithText("47 dk kaldı").assertExistsCompat()
        compose.onNodeWithText("Durdur").performClick()
        assertEquals(1, stopped)
        assertEquals(0, map)
        compose.onNodeWithText("Canlı konum").performClick()
        assertEquals(1, map)
    }

    @Test
    fun anExpiredLiveLocationShowsItsEnd() {
        val live = MessageContent.LiveLocation(expiresAt = now.minusSeconds(60), isActive = true)
        show { MessageBubbleView(message(live)) }
        compose.onNodeWithText("Canlı konum paylaşımı bitti").assertExistsCompat()
        compose.onAllNodesWithText("Durdur").assertCountEquals(0)
    }

    @Test
    fun theVoicePlayButtonToggles() {
        var toggled = 0
        val voice = MessageContent.Voice(42.seconds, DesignFixtures.voiceWaveform, 0.3)
        show { MessageBubbleView(message(voice), onToggleVoice = { toggled++ }) }
        compose.onNodeWithContentDescription("Sesli mesajı oynat").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun theActiveVoiceMessageShowsItsPositionAndSpeed() {
        val playback = FakePlayback(MessageId("t1"))
        val voice = MessageContent.Voice(42.seconds, DesignFixtures.voiceWaveform, 0.0)
        show {
            CompositionLocalProvider(LocalVoicePlayback provides playback) { MessageBubbleView(message(voice)) }
        }
        compose.onNodeWithTag("voice.waveform", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:21"))
        val speed = compose.onNodeWithContentDescription("Oynatma hızı")
        speed.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 kat hız"))
        speed.performClick()
        assertEquals(1, playback.rateChanges)
        speed.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1,5 kat hız"))
        compose.onNodeWithContentDescription("duraklat", substring = true, ignoreCase = true).assertExistsCompat()
    }

    @Test
    fun aKindChangeMorphsAndReportsTheEnd() {
        val before = message(MessageContent.Text("Silinecek mesaj"))
        val after = before.copy(content = MessageContent.Deleted)
        var replaced = 0
        compose.mainClock.autoAdvance = false
        show { MessageBubbleView(after, replacing = before, onReplaced = { replaced++ }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText("Silinecek mesaj", useUnmergedTree = true).assertExistsCompat()
        compose.onNodeWithText("Bu mesaj herkesten silindi", useUnmergedTree = true).assertExistsCompat()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(1, replaced)
        compose.onAllNodesWithText("Silinecek mesaj", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theReactionChipCountsAndTheSavedChipIsOnlyOnOwnSavedMedia() {
        val reacted = message(MessageContent.Text("Güzel")).copy(reactions = listOf(Reaction("👍", count = 3)))
        val ownSaved = message(photo, MessageDirection.Outgoing).copy(id = MessageId("t2"), savedByPeer = true)
        val theirs = message(photo).copy(id = MessageId("t3"), savedByPeer = true)
        show {
            androidx.compose.foundation.layout.Column {
                MessageBubbleView(reacted)
                MessageBubbleView(ownSaved)
                MessageBubbleView(theirs)
            }
        }
        compose.onNodeWithContentDescription("👍 3").assertExistsCompat()
        compose.onAllNodesWithTag("bubble.saved", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun aFailedSentenceOffersResend() {
        var resent = 0
        show { MessageStatusSentence(MessageStatus.Failed, onResend = { resent++ }) }
        compose.onNodeWithText("Tekrar gönder").performClick()
        assertEquals(1, resent)
    }

    @Test
    fun stateCrossFadeShowsBothWhileItFades() {
        var value by mutableStateOf("eski")
        compose.mainClock.autoAdvance = false
        show { StateCrossFade(value) { androidx.compose.foundation.text.BasicText(it) } }
        compose.mainClock.advanceTimeByFrame()
        value = "yeni"
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithText("eski", useUnmergedTree = true).assertExistsCompat()
        compose.onNodeWithText("yeni", useUnmergedTree = true).assertExistsCompat()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onAllNodesWithText("eski", useUnmergedTree = true).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() {
        assertExists()
    }

    /** A paused voice message half way through. */
    private class FakePlayback(override val activeId: MessageId) : VoicePlaybackState {
        var rateChanges = 0
        override var rate by mutableStateOf(VoicePlaybackRate.Normal)
        override val isPlaying = true
        override val isLoading = false
        override val isScrubbing = false
        override val duration: Duration = 42.seconds
        override val currentTime: Duration = 21.seconds

        override fun shownFraction(id: MessageId, fallback: Double): Double = if (id == activeId) 0.5 else fallback

        override fun beginScrub(id: MessageId) = Unit

        override fun scrub(id: MessageId, fraction: Double) = Unit

        override fun endScrub(id: MessageId) = Unit

        override fun skip(id: MessageId, by: Duration) = Unit

        override fun cycleRate() {
            rateChanges++
            rate = rate.next
        }
    }
}
