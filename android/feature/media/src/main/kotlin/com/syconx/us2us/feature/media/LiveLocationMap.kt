package com.syconx.us2us.feature.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.LiveShare
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.PulseRings
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2InlinePillButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.LocalOwnAvatar
import com.syconx.us2us.ui.env.rememberU2Now
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.theme.u2ShadowMd
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.syconx.us2us.feature.media.R as MediaR

/** Test tags of the live-location map. */
object LiveMapTags {
    const val CLOSE = "liveMap.close"
    const val CENTER = "liveMap.center"
    const val FIT = "liveMap.fit"
    const val PEER_MARKER = "liveMap.marker.peer"
    const val OWN_MARKER = "liveMap.marker.own"
    const val OPEN_IN_MAPS = "liveMap.openInMaps"
    const val STOP = "liveMap.stop"
    const val PERMISSION = "liveMap.permission"
    const val HEADER = "liveMap.header"
    const val CARD = "liveMap.card"
    const val PEER_ENDED = "liveMap.peerEnded"
}

/**
 * Where the map looks: its centre and scale (metres per dp). A local equirectangular projection
 * around the centre — at the few streets the map shows, the same as the Mercator maps use.
 */
@Stable
internal class MapCamera {
    var latitude by mutableDoubleStateOf(Double.NaN)
    var longitude by mutableDoubleStateOf(Double.NaN)
    var metersPerDp by mutableDoubleStateOf(Double.NaN)

    /** The user moved the map: the camera no longer follows the positions. */
    var positionedByUser by mutableStateOf(false)
    private var animation: Job? = null

    val isPlaced: Boolean get() = !metersPerDp.isNaN()

    /** Where [position] is on a map of [widthDp] × [heightDp], in dp from its top-left. */
    fun project(latitude: Double, longitude: Double, widthDp: Float, heightDp: Float): Offset {
        val cosine = max(0.01, cos(this.latitude * PI / 180))
        var dLongitude = longitude - this.longitude
        while (dLongitude > 180) dLongitude -= 360
        while (dLongitude < -180) dLongitude += 360
        val x = dLongitude * cosine * LiveMapFraming.METERS_PER_DEGREE / metersPerDp
        val y = -(latitude - this.latitude) * LiveMapFraming.METERS_PER_DEGREE / metersPerDp
        return Offset((widthDp / 2 + x).toFloat(), (heightDp / 2 + y).toFloat())
    }

    /** The camera that shows [region] inside the part of the map between [topDp] and [bottomDp] (covered by the header and the card). */
    fun target(region: MapRegion, widthDp: Float, heightDp: Float, topDp: Float, bottomDp: Float): Triple<Double, Double, Double> {
        val visible = max(1f, heightDp - topDp - bottomDp)
        val cosine = max(0.01, cos(region.centerLatitude * PI / 180))
        val metersPerDp = max(
            region.latitudeDelta * LiveMapFraming.METERS_PER_DEGREE / visible,
            region.longitudeDelta * LiveMapFraming.METERS_PER_DEGREE * cosine / max(1f, widthDp),
        )
        val visibleCentre = topDp + visible / 2
        val latitude = region.centerLatitude - (heightDp / 2 - visibleCentre) * metersPerDp / LiveMapFraming.METERS_PER_DEGREE
        return Triple(latitude, region.centerLongitude, metersPerDp)
    }

    fun stop() {
        animation?.cancel()
        animation = null
    }

    /** Moves to [target]; a glide on the soft curve (0.6 s), or a jump. */
    fun move(scope: kotlinx.coroutines.CoroutineScope, target: Triple<Double, Double, Double>, animated: Boolean) {
        stop()
        if (!animated || !isPlaced) {
            latitude = target.first
            longitude = target.second
            metersPerDp = target.third
            return
        }
        val from = Triple(latitude, longitude, metersPerDp)
        animation = scope.launch {
            animate(0f, 1f, animationSpec = U2Motion.soft(0.6f).spec()) { t, _ ->
                latitude = from.first + (target.first - from.first) * t
                longitude = from.second + (target.second - from.second) * t
                metersPerDp = from.third + (target.third - from.third) * t
            }
        }
    }

    /** A pan by [dx], [dy] dp and a pinch by [zoom] about [focus] (dp from the centre). */
    fun gesture(dx: Float, dy: Float, zoom: Float, focus: Offset) {
        stop()
        positionedByUser = true
        val cosine = max(0.01, cos(latitude * PI / 180))
        // The point under the fingers stays under them while the scale changes.
        val newScale = (metersPerDp / zoom).coerceIn(0.05, 50_000.0)
        val focusEast = focus.x * metersPerDp
        val focusNorth = -focus.y * metersPerDp
        val shift = 1 - newScale / metersPerDp
        val east = focusEast * shift - dx * newScale
        val north = focusNorth * shift + dy * newScale
        latitude = (latitude + north / LiveMapFraming.METERS_PER_DEGREE).coerceIn(-85.0, 85.0)
        longitude += east / (LiveMapFraming.METERS_PER_DEGREE * cosine)
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        metersPerDp = newScale
    }
}

/**
 * 5.2 › Canlı konum haritası (proposed; the design draws only the bubbles): a tap on an active
 * live-location bubble opens this full-screen map (fade cover, as 5.6 — no swipe dismissal, which
 * would fight the map).
 *
 * - The contact's latest live position and the user's own: this device's position (shown here
 *   only, never sent), else the position last shared. The contact's marker goes when their share
 *   ends (PROTOCOL §8.3); without location access only the contact shows, with a gentle hint.
 * - Header (C8 strip, `bg·80 %` blur, dotted rule): close · username over “Canlı konum · Son konum
 *   18:51” · “Konumuma git” · “İkinizi de göster”.
 * - Bottom card in the 5.3 menu material: the contact (remaining time, “Haritada aç” → the floating
 *   chooser), the user (sharing + “Paylaşımı durdur”, this device only, or the hint), and a
 *   footnote about the map.
 * - The map is drawn on this phone (a dotted map, no tiles: nothing about the area leaves the app).
 *   The camera keeps both in view and follows every tick until the user moves the map; under reduce
 *   motion it jumps instead of gliding. This device's own position, while it is not shared, is taken
 *   in only once the user asks (“Konumuma git”, “İkinizi de göster”).
 * - The positions are in memory only; nothing here is stored or logged.
 */
@Composable
fun LiveLocationMap(chat: ChatId, focus: MessageId, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val model = remember(chat, focus, messenger) { LiveLocationMapModel(chat, focus, messenger) }
    val haptics = LocalHaptics.current
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    // Observed: the card, the markers and the camera follow them.
    remember(chat) { messenger.liveLocations(chat) }.collectAsState().value
    remember(chat) { messenger.peer(chat) }.collectAsState().value
    remember(chat) { messenger.messages(chat) }.collectAsState().value
    messenger.ownUsername.collectAsState().value
    val now = rememberU2Now(30.seconds)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isActive = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // The own-position stream runs only while the app is active, and starts again after a permission request.
    LaunchedEffect(model, isActive, model.streamGeneration) {
        if (isActive) model.run()
    }
    // The share went (deleted, the chat closed, the app locked): so does the map.
    val isGone = model.isGone
    LaunchedEffect(isGone) { if (isGone) currentOnClose() }

    val camera = remember { MapCamera() }
    var follow by remember { mutableStateOf(Follow.Both) }
    var peerWasShown by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<MapChoice?>(null) }
    val density = LocalDensity.current
    var headerHeight by remember { mutableFloatStateOf(0f) }
    var bottomHeight by remember { mutableFloatStateOf(0f) }
    val peerShare = model.peerShare(now)
    if (peerShare != null) peerWasShown = true

    U2Sheet(item = choice, onDismissRequest = { choice = null }, style = U2SheetStyle.Floating) { shown ->
        LocationChooser(shown, onDismiss = { choice = null })
    }

    U2Screen(modifier, background = U2Theme.colors.bg) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthDp = maxWidth.value
            val heightDp = maxHeight.value
            fun moveCamera(animated: Boolean) {
                val region = when (follow) {
                    Follow.Both -> LiveMapFraming.region(model.points(now))
                    Follow.Me -> model.ownPosition(now)?.let { LiveMapFraming.region(listOf(it)) }
                } ?: return
                val target = camera.target(region, widthDp, heightDp, headerHeight / density.density, bottomHeight / density.density)
                camera.move(scope, target, animated && !reduceMotion)
            }
            // A tick (or the header and card measured): the camera follows — unless the user has moved the map.
            val points = model.points(now)
            LaunchedEffect(points, follow, headerHeight, bottomHeight, widthDp, heightDp) {
                if (headerHeight == 0f || bottomHeight == 0f) return@LaunchedEffect
                if (!camera.positionedByUser) moveCamera(animated = camera.isPlaced)
            }
            val source = rememberU2BackdropSource()
            DottedMapView(
                camera = camera,
                model = model,
                now = now,
                widthDp = widthDp,
                heightDp = heightDp,
                modifier = Modifier.fillMaxSize().u2BackdropSource(source),
            )
            Column(Modifier.fillMaxSize()) {
                MapHeader(
                    model = model,
                    now = now,
                    source = source,
                    onClose = onClose,
                    onCenter = {
                        haptics.selection()
                        follow = Follow.Me
                        model.framesDevicePosition = true
                        camera.positionedByUser = false
                        moveCamera(animated = true)
                    },
                    onFit = {
                        haptics.selection()
                        follow = Follow.Both
                        model.framesDevicePosition = true
                        camera.positionedByUser = false
                        moveCamera(animated = true)
                    },
                    modifier = Modifier.onSizeChanged { headerHeight = it.height.toFloat() },
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .onSizeChanged { bottomHeight = it.height.toFloat() }
                        .padding(horizontal = 12.dp)
                        .u2Bottom(36.dp),
                ) {
                    MapCard(
                        model = model,
                        now = now,
                        showsPeer = peerShare != null || peerWasShown,
                        source = source,
                        onOpenInMaps = { share ->
                            if (share.position.isValidCoordinate) {
                                haptics.selection()
                                choice = MapLauncher.choice(
                                    context,
                                    MapPlace("", share.position.latitude, share.position.longitude),
                                    isDisappearing = model.isDisappearing(share),
                                )
                            }
                        },
                        onStop = { share ->
                            haptics.selection()
                            val keepsOpen = model.peerShare(now) != null
                            MediaWork.scope.launch { model.stop(share) }
                            if (!keepsOpen) onClose()
                        },
                        onAllow = { scope.launch { model.requestAccess() } },
                        onSettings = { AppSettings.open(context, guard) },
                    )
                }
            }
        }
    }
}

private enum class Follow { Both, Me }

/** The map itself: a lattice of dots that moves and scales with the camera, the accuracy circles and the two markers. Pan and pinch move it. */
@Composable
private fun DottedMapView(camera: MapCamera, model: LiveLocationMapModel, now: Instant, widthDp: Float, heightDp: Float, modifier: Modifier) {
    val colors = U2Theme.colors
    val description = stringResource(R.string.conversation_a11y_livelocation_map)
    val ownPicture = LocalOwnAvatar.current
    val youLabel = stringResource(R.string.conversation_livemap_you)
    Box(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (!camera.isPlaced) return@detectTransformGestures
                    val dp = density
                    val focus = Offset(centroid.x / dp - widthDp / 2, centroid.y / dp - heightDp / 2)
                    camera.gesture(pan.x / dp, pan.y / dp, zoom, focus)
                }
            },
    ) {
        if (!camera.isPlaced) return@Box
        val peer = model.peerPosition(now)
        val own = model.ownPosition(now)
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val dp = density
            drawMapDots(camera, widthDp, heightDp, colors.dotRule, dp)
            for (position in listOfNotNull(peer, own)) {
                if (!LiveMapFraming.showsAccuracy(position.accuracy)) continue
                val center = camera.project(position.latitude, position.longitude, widthDp, heightDp) * dp
                val radius = (position.accuracy / camera.metersPerDp).toFloat() * dp
                drawCircle(colors.accent.copy(alpha = 0.12f), radius, center)
                drawCircle(colors.accent.copy(alpha = 0.3f), radius, center, style = Stroke(1.dp.toPx()))
            }
        }
        if (peer != null) {
            MapMarker(
                position = camera.project(peer.latitude, peer.longitude, widthDp, heightDp),
                username = model.peerUsername,
                palette = model.peerPalette,
                picture = model.peerAvatar,
                pulses = true,
                label = model.peerUsername,
                tag = LiveMapTags.PEER_MARKER,
            )
        }
        if (own != null) {
            MapMarker(
                position = camera.project(own.latitude, own.longitude, widthDp, heightDp),
                username = model.ownUsername,
                palette = AvatarPalette.D,
                picture = ownPicture,
                pulses = model.ownShare(now) != null,
                label = youLabel,
                tag = LiveMapTags.OWN_MARKER,
            )
        }
    }
}

/** The lattice: a dot every “nice” distance (at least 18 dp apart), the fifth ones stronger. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapDots(
    camera: MapCamera,
    widthDp: Float,
    heightDp: Float,
    color: androidx.compose.ui.graphics.Color,
    dp: Float,
) {
    val step = MapGrid.step(camera.metersPerDp)
    val spacing = (step / camera.metersPerDp).toFloat()
    val cosine = max(0.01, cos(camera.latitude * PI / 180))
    val east = camera.longitude * cosine * LiveMapFraming.METERS_PER_DEGREE
    val north = camera.latitude * LiveMapFraming.METERS_PER_DEGREE
    // The dot nearest the top-left corner, in metres east and north.
    val left = east - widthDp / 2 * camera.metersPerDp
    val top = north + heightDp / 2 * camera.metersPerDp
    val firstColumn = floor(left / step).toLong()
    val firstRow = floor(top / step).toLong() + 1
    val columns = (widthDp / spacing).toInt() + 2
    val rows = (heightDp / spacing).toInt() + 2
    val minor = color.copy(alpha = color.alpha * 0.55f)
    for (c in 0..columns) {
        val column = firstColumn + c
        val x = ((column * step - left) / camera.metersPerDp).toFloat() * dp
        for (r in 0..rows) {
            val row = firstRow - r
            val y = ((top - row * step) / camera.metersPerDp).toFloat() * dp
            val major = Math.floorMod(column, 5L) == 0L && Math.floorMod(row, 5L) == 0L
            drawCircle(if (major) color else minor, (if (major) 1.4f else 1f) * dp, Offset(x, y))
        }
    }
}

/** The map lattice's spacing. */
internal object MapGrid {
    /** The smallest 1-2-5 distance (metres) that is at least 18 dp apart at [metersPerDp]. */
    fun step(metersPerDp: Double): Double {
        val minimum = max(1e-3, 18 * metersPerDp)
        val magnitude = Math.pow(10.0, floor(kotlin.math.log10(minimum)))
        for (factor in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) {
            if (magnitude * factor >= minimum) return magnitude * factor
        }
        return magnitude * 10
    }
}

/** A person on the live map: the C12 avatar (38) with a 3 dp `bg` ring and the live dot's glow; the 5.2 pulse rings behind while their share runs. */
@Composable
private fun MapMarker(
    position: Offset,
    username: String,
    palette: AvatarPalette,
    picture: AvatarPicture,
    pulses: Boolean,
    label: String,
    tag: String,
) {
    val colors = U2Theme.colors
    val ring = U2Size.faceIdDisc
    Box(
        Modifier
            .offset { IntOffset(((position.x - ring.value / 2) * density).toInt(), ((position.y - ring.value / 2) * density).toInt()) }
            .size(ring)
            .testTag(tag)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (pulses) PulseRings()
        Avatar(
            username = username,
            palette = palette,
            size = AvatarSize.Header,
            picture = picture,
            modifier = Modifier
                .u2BoxShadow(CircleShape, U2BoxShadows.glowLiveDot)
                .u2OuterRing(colors.bg, 3.dp, CircleShape),
        )
    }
}

// MARK: - Header

@Composable
private fun MapHeader(
    model: LiveLocationMapModel,
    now: Instant,
    source: com.syconx.us2us.ui.theme.U2BackdropSource,
    onClose: () -> Unit,
    onCenter: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val showsCenter = model.ownPosition(now) != null
    val ended = stringResource(R.string.common_livelocation_ended)
    val live = stringResource(R.string.common_location_live)
    val updated = model.peerShare(now)?.position?.updatedAt
    val lastUpdate = updated?.let { stringResource(R.string.conversation_livemap_lastupdate, U2Format.clockTime(it, locale)) }
    val subtitle = when {
        !model.isLive(now) -> ended
        lastUpdate == null -> live
        else -> "$live · $lastUpdate"
    }
    Box(
        modifier
            .fillMaxWidth()
            .testTag(LiveMapTags.HEADER)
            .u2Backdrop(U2BackdropStyle.Header, source = source),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .u2Top(U2Spacing.headerTopButtons)
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            U2GlassIconButton(
                PhosphorGlyph.X,
                stringResource(R.string.common_a11y_close),
                onClick = onClose,
                glyphSize = 21.dp,
                modifier = Modifier.testTag(LiveMapTags.CLOSE),
            )
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                U2Text(model.peerUsername, U2TextStyles.monoUsername, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                U2Text(subtitle, U2TextStyles.meta, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val fade = U2Transitions.fade()
            AnimatedVisibility(showsCenter, enter = fade.enter, exit = fade.exit) {
                U2GlassIconButton(
                    PhosphorGlyph.NavigationArrow,
                    stringResource(R.string.conversation_livemap_center),
                    onClick = onCenter,
                    glyphSize = 21.dp,
                    modifier = Modifier.testTag(LiveMapTags.CENTER),
                )
            }
            U2GlassIconButton(
                PhosphorGlyph.Scan,
                stringResource(R.string.conversation_livemap_fit),
                onClick = onFit,
                glyphSize = 21.dp,
                modifier = Modifier.testTag(LiveMapTags.FIT),
            )
        }
        DottedRule(Modifier.align(Alignment.BottomCenter), color = colors.dotRuleHeader)
    }
}

// MARK: - Card

private val RowPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)

@Composable
private fun MapCard(
    model: LiveLocationMapModel,
    now: Instant,
    showsPeer: Boolean,
    source: com.syconx.us2us.ui.theme.U2BackdropSource,
    onOpenInMaps: (LiveShare) -> Unit,
    onStop: (LiveShare) -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.menu)
    val peer = model.peerShare(now)
    val row = model.ownRow(now)
    val showsOwn = row != LiveLocationMapModel.OwnRow.Hidden
    val reduceMotion = LocalReduceMotion.current
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(LiveMapTags.CARD)
            .u2ShadowMd(shape)
            .clip(shape)
            .u2Backdrop(U2BackdropStyle.Menu, shape, source),
    ) {
        if (showsPeer) {
            AnimatedContent(
                targetState = peer?.id,
                transitionSpec = {
                    val timing = U2Motion.resolve(U2Motion.state, reduceMotion)
                    fadeIn(timing.spec()) togetherWith fadeOut(timing.spec())
                },
                label = "peerRow",
            ) { id ->
                if (id != null && peer != null) PeerRow(model, peer, now, onOpenInMaps) else PeerEndedRow()
            }
        }
        if (showsPeer && showsOwn) DottedRule(color = colors.dotRule)
        if (showsOwn) {
            AnimatedContent(
                targetState = row.kind,
                transitionSpec = {
                    val timing = U2Motion.resolve(U2Motion.state, reduceMotion)
                    fadeIn(timing.spec()) togetherWith fadeOut(timing.spec())
                },
                label = "ownRow",
            ) { _ -> OwnRowView(model, model.ownRow(now), now, onStop, onAllow, onSettings) }
        }
        if (showsPeer || showsOwn) DottedRule(color = colors.dotRule)
        U2Text(
            stringResource(MediaR.string.media_android_livemap_tiles),
            U2TextStyles.footnote,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun PeerRow(model: LiveLocationMapModel, peer: LiveShare, now: Instant, onOpenInMaps: (LiveShare) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(RowPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(model.peerUsername, model.peerPalette, size = AvatarSize.Header, picture = model.peerAvatar)
        RowText(
            title = model.peerUsername,
            detail = stringResource(R.string.common_location_live_remaining, LiveLocationMapModel.minutesLeft(peer.expiresAt, now)),
            detailStyle = U2TextStyles.monoFileMeta,
            modifier = Modifier.weight(1f),
        )
        if (model.peerPosition(now) != null) {
            U2GlassIconButton(
                PhosphorGlyph.Export,
                stringResource(R.string.conversation_location_open_title),
                onClick = { onOpenInMaps(peer) },
                diameter = U2Size.iconButtonSheet,
                glyphSize = 18.dp,
                modifier = Modifier.testTag(LiveMapTags.OPEN_IN_MAPS),
            )
        }
    }
}

/** Their share ended while the map was open: the marker is gone. */
@Composable
private fun PeerEndedRow() {
    val colors = U2Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(RowPadding)
            .testTag(LiveMapTags.PEER_ENDED)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(PhosphorGlyph.NavigationArrow, 15.dp, tint = colors.textSecondary)
        U2Text(stringResource(R.string.common_livelocation_ended), U2TextStyles.bubbleTitleS.copy(weight = 400), color = colors.textSecondary, maxLines = 2)
    }
}

@Composable
private fun OwnRowView(
    model: LiveLocationMapModel,
    row: LiveLocationMapModel.OwnRow,
    now: Instant,
    onStop: (LiveShare) -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
) {
    when (row) {
        is LiveLocationMapModel.OwnRow.Sharing -> TrailingActionRow(
            leading = { OwnAvatar(model) },
            text = { modifier ->
                RowText(
                    title = stringResource(R.string.conversation_livemap_sharing),
                    detail = stringResource(R.string.common_location_live_remaining, LiveLocationMapModel.minutesLeft(row.share.expiresAt, now)),
                    detailStyle = U2TextStyles.monoFileMeta,
                    modifier = modifier,
                )
            },
            action = {
                U2InlinePillButton(
                    stringResource(R.string.conversation_livemap_stop),
                    onClick = { onStop(row.share) },
                    modifier = Modifier.testTag(LiveMapTags.STOP),
                )
            },
        )
        LiveLocationMapModel.OwnRow.Device, LiveLocationMapModel.OwnRow.Locating -> Row(
            Modifier.fillMaxWidth().padding(RowPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnAvatar(model)
            RowText(
                title = stringResource(R.string.conversation_livemap_you),
                detail = stringResource(
                    if (row == LiveLocationMapModel.OwnRow.Device) R.string.conversation_livemap_deviceonly else R.string.conversation_livemap_locating,
                ),
                detailStyle = U2TextStyles.captionTighter,
                modifier = Modifier.weight(1f),
            )
        }
        LiveLocationMapModel.OwnRow.AskPermission -> PermissionRow(
            message = stringResource(R.string.conversation_livemap_permission_ask),
            buttonTitle = stringResource(R.string.conversation_livemap_permission_allow),
            onClick = onAllow,
        )
        LiveLocationMapModel.OwnRow.Denied -> PermissionRow(
            message = stringResource(R.string.conversation_livemap_permission_denied),
            buttonTitle = stringResource(R.string.conversation_livemap_permission_settings),
            onClick = onSettings,
        )
        LiveLocationMapModel.OwnRow.Hidden -> Unit
    }
}

@Composable
private fun OwnAvatar(model: LiveLocationMapModel) {
    Avatar(model.ownUsername, AvatarPalette.D, size = AvatarSize.Header, picture = LocalOwnAvatar.current)
}

@Composable
private fun RowText(title: String, detail: String, detailStyle: U2TextStyle, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        U2Text(title, U2TextStyles.rowTitleEmphasis, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        U2Text(detail, detailStyle, color = colors.textSecondary)
    }
}

/** The gentle hint: without location access the map shows only the contact. */
@Composable
private fun PermissionRow(message: String, buttonTitle: String, onClick: () -> Unit) {
    val colors = U2Theme.colors
    TrailingActionRow(
        leading = {
            Box(Modifier.size(AvatarSize.Header.diameter), contentAlignment = Alignment.Center) {
                PhosphorIcon(PhosphorGlyph.NavigationArrow, 20.dp, tint = colors.accent300)
            }
        },
        text = { modifier -> U2Text(message, U2TextStyles.captionTighter, color = colors.textSecondary, modifier = modifier) },
        action = {
            U2TextButton(
                title = buttonTitle,
                onClick = onClick,
                tone = U2TextButtonTone.Accent,
                height = U2Size.iconButtonSheet,
                fillsWidth = false,
                modifier = Modifier.testTag(LiveMapTags.PERMISSION),
            )
        },
    )
}

/**
 * Leading mark, text and an action on one row as drawn. With a large system font the action moves
 * under the text (aligned to its end), so the text keeps the row's width instead of wrapping a
 * word or a letter per line.
 */
@Composable
private fun TrailingActionRow(
    leading: @Composable () -> Unit,
    text: @Composable (Modifier) -> Unit,
    action: @Composable () -> Unit,
) {
    if (LocalDensity.current.fontScale < STACKED_FONT_SCALE) {
        Row(
            Modifier.fillMaxWidth().padding(RowPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            text(Modifier.weight(1f))
            action()
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(RowPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                text(Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
        }
    }
}

/** From this system font size the live-map rows put their action under the text. */
private const val STACKED_FONT_SCALE = 1.3f
