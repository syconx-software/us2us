package com.syconx.us2us.feature.media

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.syconx.us2us.ui.theme.onMediaGlass
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaExportPolicy
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.StateCrossFade
import com.syconx.us2us.ui.env.ForceDarkSystemBars
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.MediaStateGlyph
import com.syconx.us2us.ui.media.ReadyGlyph
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Toast
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.shadowBlack
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the player. */
object VideoPlayerTags {
    const val CLOSE = "player.close"
    const val VIDEO = "player.video"
    const val POSTER = "player.poster"
    const val PLAY = "player.play"
    const val MUTE = "player.mute"
    const val SCRUBBER = "player.scrubber"
    const val LOADING = "player.loading"
    const val FAILED = "player.failed"
    const val CHROME = "player.chrome"
}

/**
 * The video player (5.2 “video → player”; 5.6 “video variant, play controls” proposed), over the
 * conversation like the photo viewer: the clip aspect-fit on `#0d0e19`, 5.6's shades and top bar
 * with “Kaydet” (to the gallery; the sender is told — never for a disappearing video) and the mute
 * button (44, glass) at its end, the bubble's glass play button grown to 64 in the middle, and the
 * scrubber with the times above the bottom shade.
 *
 * It opens at once with the poster and the ring while the clip downloads and decrypts, then plays.
 * A tap shows or hides the controls (0.24 s); while playing they hide by themselves after 3 s.
 * Swipe up or down to close. The clip plays from memory and is dropped when the player closes.
 */
@Composable
fun VideoPlayer(
    item: ViewerItem,
    chat: ChatId,
    saver: MediaSaver,
    appDark: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoplay: Boolean = item.startTime == null,
) {
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val factory = LocalVideoEngineFactory.current
    val haptics = LocalHaptics.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val guard = LocalExternalActivityGuard.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val playback = remember(item) { VideoPlaybackModel(item.duration, factory.create(context)) }
    val messages by remember(chat) { messenger.messages(chat) }.collectAsState()
    val message = item.messageId?.let { id -> messages.firstOrNull { it.id == id } }
    val saving = remember(item) { ViewerSaveState() }

    var poster by remember(item) { mutableStateOf<ImageBitmap?>(null) }
    var controlsVisible by remember(item) { mutableStateOf(true) }
    var isClosing by remember(item) { mutableStateOf(false) }
    var hideRequest by remember(item) { mutableStateOf(0) }
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val currentOnClose by rememberUpdatedState(onClose)

    LaunchedEffect(item) {
        poster = MediaImages.load(context, item.media, 1_280)
        val id = item.messageId
        if (id == null) {
            playback.start(null)
            return@LaunchedEffect
        }
        val payload = messenger.mediaPayload(id, chat)
        if (isClosing) return@LaunchedEffect
        playback.start(payload, item.startTime, autoplay)
    }
    // The position, 30 times a second while it plays.
    LaunchedEffect(playback.isPlaying, playback.phase) {
        playback.refresh()
        while (playback.isPlaying) {
            delay(33)
            playback.refresh()
        }
    }
    // Playing: the controls fade after 3 s without a touch, or after the user's “time to take
    // action” (Accessibility settings) when that is longer. With TalkBack they stay: a control that
    // disappears cannot be found by touch exploration.
    val accessibility = LocalAccessibilityManager.current
    val exploresByTouch = remember(context) {
        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    LaunchedEffect(playback.isPlaying, hideRequest) {
        if (!playback.isPlaying || exploresByTouch) return@LaunchedEffect
        delay(accessibility?.calculateRecommendedTimeoutMillis(3_000, containsIcons = true, containsControls = true) ?: 3_000)
        if (playback.isPlaying && !playback.isScrubbing) controlsVisible = false
    }
    DisposableEffect(playback) { onDispose { playback.close() } }

    /** The cover fades out on the paused frame; everything decrypted goes once it is gone. */
    fun close() {
        if (isClosing) return
        controlsVisible = controlsVisible || !playback.isPlaying
        isClosing = true
        playback.pause()
        currentOnClose()
        MediaWork.scope.launch {
            delay((U2Motion.Duration.state * 1000).toLong() + 100)
            playback.close()
        }
    }

    // The privacy cover comes up: pause. To the background: stop now, not after the cover's fade.
    DisposableEffect(lifecycle, guard) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> playback.pause()
                Lifecycle.Event.ON_STOP -> if (!guard.isActive) {
                    playback.close()
                    close()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val controlsShown = controlsVisible || (!playback.isPlaying && !isClosing)
    val controlsAlpha by animateFloatAsState(if (controlsShown) 1f else 0f, u2Spec(U2Motion.micro), label = "controls")

    fun scheduleHide() {
        hideRequest++
    }

    ForceDarkSystemBars()
    AlwaysDark {
        val colors = U2Theme.colors
        BoxWithConstraints(modifier.fillMaxSize()) {
            val heightDp = maxHeight.value
            val progress = MediaDismissGesture.progress(drag.value.y / density.density, heightDp)
            U2Screen(background = Color.Transparent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = MediaDismissGesture.backdropOpacity(progress) }
                        .background(colors.mediaViewerBg),
                )
                VideoMedia(
                    playback = playback,
                    poster = poster,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = MediaDismissGesture.scale(progress)
                            scaleX = scale
                            scaleY = scale
                            translationX = drag.value.x
                            translationY = drag.value.y
                        },
                )
                // Taps toggle the controls; a vertical drag closes.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(playback) {
                            detectTapGestures {
                                if (!isClosing) {
                                    controlsVisible = !controlsVisible
                                    if (controlsVisible) scheduleHide()
                                }
                            }
                        }
                        .pointerInput(playback) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (isClosing || playback.isScrubbing) return@awaitEachGesture
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var vertical: Boolean? = null
                                var translation = Offset.Zero
                                val slop = 12.dp.toPx()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    translation = change.position - down.position
                                    if (vertical == null && translation.getDistance() > slop) {
                                        vertical = abs(translation.y) > abs(translation.x) * 1.2f
                                    }
                                    if (vertical == true && !playback.isScrubbing) {
                                        change.consume()
                                        scope.launch { drag.snapTo(translation) }
                                    }
                                }
                                if (vertical != true || isClosing) return@awaitEachGesture
                                val velocity = tracker.calculateVelocity()
                                val dp = density.density
                                if (MediaDismissGesture.shouldDismiss(translation.y / dp, velocity.y / dp)) {
                                    val direction = if (velocity.y + translation.y >= 0f) 1f else -1f
                                    if (!reduceMotion) {
                                        scope.launch {
                                            drag.animateTo(
                                                Offset(translation.x, translation.y + 160f * dp * direction),
                                                U2Motion.out(U2Motion.Duration.state).spec(),
                                            )
                                        }
                                    }
                                    close()
                                } else {
                                    val remaining = abs(translation.y)
                                    val relative = U2Motion.relativeVelocity(-velocity.y, remaining)
                                    scope.launch {
                                        drag.animateTo(
                                            Offset.Zero,
                                            if (reduceMotion) tween(300) else U2Motion.spring(),
                                            initialVelocity = Offset(0f, -relative * remaining),
                                        )
                                    }
                                }
                            }
                        },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(VideoPlayerTags.CHROME)
                        .graphicsLayer { alpha = controlsAlpha * MediaDismissGesture.chromeOpacity(progress) },
                ) {
                    // Closing: the controls stay as they were while the cover fades out.
                    if (controlsAlpha * MediaDismissGesture.chromeOpacity(progress) > 0.01f) {
                        PlayerChrome(
                            item = item,
                            message = message,
                            playback = playback,
                            appDark = appDark,
                            saving = saving,
                            onSave = { savable ->
                                saving.save(savable, saver, haptics)
                                scheduleHide()
                            },
                            onClose = ::close,
                            onInteraction = ::scheduleHide,
                        )
                    }
                }
                U2Toast(saving.toast?.text(), onDismiss = { saving.toast = null })
            }
        }
    }
}

/** The poster until the first frame, then the clip (aspect-fit). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoMedia(playback: VideoPlaybackModel, poster: ImageBitmap?, modifier: Modifier) {
    val label = stringResource(R.string.common_media_video)
    val showsVideo by animateFloatAsState(if (playback.isShowingVideo) 1f else 0f, u2Spec(U2Motion.micro), label = "video")
    Box(
        modifier
            .testTag(VideoPlayerTags.VIDEO)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null && showsVideo < 1f) {
            Image(
                poster,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(1f - showsVideo).testTag(VideoPlayerTags.POSTER),
            )
        }
        val player = playback.player
        if (player != null) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize().alpha(showsVideo),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                keepContentOnReset = false,
                shutter = {},
            )
        }
    }
}

@Composable
private fun PlayerChrome(
    item: ViewerItem,
    message: Message?,
    playback: VideoPlaybackModel,
    appDark: Boolean,
    saving: ViewerSaveState,
    onSave: (Message) -> Unit,
    onClose: () -> Unit,
    onInteraction: () -> Unit,
) {
    MediaViewerShades()
    Column(Modifier.fillMaxSize()) {
        MediaViewerTopBar(item.sender, item.sentAt, appDark, VideoPlayerTags.CLOSE, onClose) {
            AppThemed(appDark) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val savable = message?.takeIf(MediaExportPolicy::savesToGallery)
                    if (savable != null) {
                        MediaSaveButton(saving.phase(savable), MediaExportPolicy.tellsSender(savable), onClick = { onSave(savable) })
                    }
                    MediaGlassIconButton(
                        glyph = if (playback.isMuted) MediaGlyph.SpeakerX else MediaGlyph.SpeakerHigh,
                        contentDescription = stringResource(if (playback.isMuted) R.string.conversation_player_unmute else R.string.conversation_player_mute),
                        onClick = {
                            playback.toggleMute()
                            onInteraction()
                        },
                        modifier = Modifier.testTag(VideoPlayerTags.MUTE),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val interactive = playback.phase == VideoPlaybackModel.Phase.Ready || playback.phase == VideoPlaybackModel.Phase.Ended
        VideoScrubber(
            playback = playback,
            onInteraction = onInteraction,
            enabled = interactive,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .u2Bottom(50.dp)
                // Faded without an offscreen layer: one would clip the knob at the track's start.
                .graphicsLayer {
                    alpha = if (interactive) 1f else 0.4f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CentreButton(item, message, playback, onInteraction)
    }
}

private enum class CentreKind { Loading, Failed, Controls }

@Composable
private fun CentreButton(item: ViewerItem, message: Message?, playback: VideoPlaybackModel, onInteraction: () -> Unit) {
    val colors = U2Theme.colors
    val kind = when (playback.phase) {
        VideoPlaybackModel.Phase.Loading -> CentreKind.Loading
        VideoPlaybackModel.Phase.Failed -> CentreKind.Failed
        else -> CentreKind.Controls
    }
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            val fade = U2Motion.resolve(U2Motion.state, reduceMotion)
            fadeIn(fade.spec()) togetherWith fadeOut(fade.spec())
        },
        contentAlignment = Alignment.Center,
        label = "centre",
    ) { shown ->
        when (shown) {
            CentreKind.Loading -> Column(
                Modifier.testTag(VideoPlayerTags.LOADING).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MediaGlassCircle(diameter = 64.dp) {
                    MediaStateGlyph(loadingState(message), diameter = 64.dp, readyGlyph = ReadyGlyph.None)
                }
                U2Text(stringResource(R.string.conversation_player_preparing), U2TextStyles.caption, Modifier.onMediaCaption(), color = colors.onMedia)
            }
            CentreKind.Failed -> Column(
                Modifier.testTag(VideoPlayerTags.FAILED).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MediaGlassCircle(diameter = 64.dp) {
                    PhosphorIcon(PhosphorGlyph.WarningCircle, 26.dp, Modifier.alpha(0.72f), tint = colors.onMedia)
                }
                U2Text(stringResource(R.string.conversation_player_failed), U2TextStyles.label15Emphasis, Modifier.onMediaCaption(), color = colors.onMedia)
            }
            CentreKind.Controls -> {
                val label = stringResource(
                    when {
                        playback.isPlaying -> R.string.conversation_player_pause
                        playback.phase == VideoPlaybackModel.Phase.Ended -> R.string.conversation_player_replay
                        else -> R.string.conversation_player_play
                    },
                )
                val source = remember { MutableInteractionSource() }
                MediaGlassCircle(
                    modifier = Modifier
                        .testTag(VideoPlayerTags.PLAY)
                        .u2PressEffect(source)
                        .clickable(source, indication = null, role = Role.Button) {
                            playback.togglePlay()
                            if (playback.isPlaying) onInteraction()
                        }
                        .semantics { contentDescription = label },
                    diameter = 64.dp,
                ) {
                    StateCrossFade(playback.isPlaying) { playing ->
                        if (playing) {
                            MediaGlyphIcon(MediaGlyph.Pause, 26.dp, tint = colors.onMedia)
                        } else {
                            PhosphorIcon(PhosphorGlyph.Play, 26.dp, weight = PhosphorWeight.Fill, tint = colors.onMedia)
                        }
                    }
                }
            }
        }
    }
}

/** While the clip is on its way: the bubble's state (the ring fills while it downloads). */
private fun loadingState(message: Message?): MediaAvailability =
    (message?.mediaAvailability as? MediaAvailability.Downloading) ?: MediaAvailability.Downloading(null)

/**
 * The scrubber: a 44 dp touch strip around a 4 dp track (`#f5f4ff` at 28 %, what is loaded at
 * 45 %, what has played solid), a 14 dp knob that grows to 18 while dragged, and the times in Mono
 * 11 at 75 % (elapsed left, length right). Accessibility: adjustable by 5 s.
 */
@Composable
internal fun VideoScrubber(playback: VideoPlaybackModel, onInteraction: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val elapsed = U2Format.voiceDuration(playback.currentTime.seconds)
    val total = U2Format.voiceDuration(playback.duration.seconds)
    val positionLabel = stringResource(R.string.conversation_player_position)
    val knob by animateDpAsState(if (playback.isScrubbing) 18.dp else 14.dp, u2Spec(U2Motion.micro), label = "knob")
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag(VideoPlayerTags.SCRUBBER)
                .semantics {
                    contentDescription = positionLabel
                    stateDescription = "$elapsed / $total"
                    progressBarRangeInfo = ProgressBarRangeInfo(playback.currentTime.toFloat(), 0f..max(0.001f, playback.duration.toFloat()))
                    setProgress { target ->
                        playback.skip(if (target > playback.currentTime) 5.0 else -5.0)
                        true
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val width = size.width.toFloat()
                        playback.beginScrub()
                        playback.scrub(if (width > 0) (down.position.x / width).toDouble() else 0.0)
                        currentOnInteraction()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            playback.scrub(if (width > 0) (change.position.x / width).toDouble() else 0.0)
                            currentOnInteraction()
                        }
                        playback.endScrub()
                        currentOnInteraction()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            val track = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().height(4.dp).background(colors.onMedia.copy(alpha = 0.28f), track))
            Box(Modifier.width(width * playback.bufferedFraction.toFloat()).height(4.dp).background(colors.onMedia.copy(alpha = 0.45f), track))
            Box(Modifier.width(width * playback.fraction.toFloat()).height(4.dp).background(colors.onMedia, track))
            val knobOffset = with(LocalDensity.current) { (width * playback.fraction.toFloat() - knob / 2).roundToPx() }
            Box(
                Modifier
                    .offset { IntOffset(knobOffset, 0) }
                    .size(knob)
                    .u2BoxShadow(CircleShape, U2BoxShadow(colors.shadowBlack(0.35f), y = 1.dp, blur = 8.dp))
                    .background(colors.onMedia, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            U2Text(elapsed, U2TextStyles.monoMeta, color = colors.onMedia.copy(alpha = 0.75f))
            Spacer(Modifier.weight(1f).width(8.dp))
            U2Text(total, U2TextStyles.monoMeta, color = colors.onMedia.copy(alpha = 0.75f))
        }
    }
}

/**
 * A caption over the video's frame: on the media glass, so it stays readable over a bright picture
 * (white at 72 % straight on a sunset measured 1.2 : 1).
 */
@Composable
private fun Modifier.onMediaCaption(): Modifier {
    val colors = U2Theme.colors
    return background(colors.onMediaGlass(0.4f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
}
