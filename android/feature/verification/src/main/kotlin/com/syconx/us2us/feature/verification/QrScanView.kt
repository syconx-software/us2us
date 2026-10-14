package com.syconx.us2us.feature.verification

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.common.MyCodeView
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.FloatingBanner
import com.syconx.us2us.ui.components.FloatingBannerKind
import com.syconx.us2us.ui.components.QrModulesView
import com.syconx.us2us.ui.components.U2CameraPillButton
import com.syconx.us2us.ui.env.ForceDarkSystemBars
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.L10n
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.mediaBottomShade
import com.syconx.us2us.ui.theme.mediaTopShade
import com.syconx.us2us.ui.theme.qrPaperScan
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 6.2 · QR tarama: always dark, whatever the theme.
 *
 * The camera preview full screen (the design's placeholder where there is no camera), the top bar
 * (close · “Kodu okut” · torch), the 256 dp viewfinder with four corner brackets and the sweeping
 * scan line, the hint and “Kendi kodumu göster”. A rejected code shows a warning card under the top
 * bar (fade + 8 dp drop) and scanning goes on; an accepted one hands over to the owner (6.3).
 * “Kendi kodumu göster” cross-fades to the user's own code and stops the camera.
 *
 * [chromeDark] is the app's theme outside the cover: the design draws the camera buttons' 0.5 dp
 * hairline with the themed `text·18%` (light in dark, dark in light); everything else is fixed.
 * [forcesDenied] shows the permission panel (harness).
 */
@Composable
internal fun QrScanView(scanner: QrScannerModel, chromeDark: Boolean, modifier: Modifier = Modifier, forcesDenied: Boolean = false) {
    val backend = LocalQrCameraBackend.current
    val camera = remember(backend) { QrCameraModel(backend) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val scope = rememberCoroutineScope()
    val previewView = remember(camera) { if (camera.isPlaceholder) null else makePreviewView(context) }
    var pendingPermission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val permission = rememberGuardedLauncher(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }
    val currentScanner by rememberUpdatedState(scanner)

    LaunchedEffect(camera) {
        camera.onCode = { code -> scope.launch { currentScanner.handle(code) } }
        if (forcesDenied) camera.forceStatus(QrCameraModel.Status.Denied)
    }
    // The camera runs only while scanning, presented and the app is in front (never under the lock or the cover).
    val visible = LocalIsLayerVisible.current
    val shouldRun = scanner.isActive && scanner.stage == QrScannerModel.Stage.Scanning && visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(shouldRun) {
        if (shouldRun) {
            camera.start(context, owner, previewView?.surfaceProvider) {
                val answer = CompletableDeferred<Boolean>()
                pendingPermission = answer
                permission.launch(android.Manifest.permission.CAMERA)
                answer.await()
            }
            if (scanner.torchOn) camera.setTorch(true)
        } else {
            camera.stop()
        }
    }
    LaunchedEffect(scanner.torchOn) { camera.setTorch(scanner.torchOn) }
    LaunchedEffect(camera) {
        snapshotFlow { camera.torchAvailable }.drop(1).collect { available -> if (!available) scanner.setTorch(false) }
    }
    DisposableEffect(camera) { onDispose { camera.stop() } }

    // Light status bar over the camera; released as soon as the cover starts to leave.
    if (scanner.isActive) ForceDarkSystemBars()
    U2Theme(dark = true) {
        U2Screen(modifier.testTag(VerificationTags.SCAN_SCREEN), background = U2Theme.colors.cameraBg) {
            ScanBackdrop(camera, scanner, previewView)
            Crossfade(scanner.stage, animationSpec = stateFade(), label = "scanStage") { stage ->
                when (stage) {
                    QrScannerModel.Stage.Scanning -> ScanningContent(scanner, camera)
                    QrScannerModel.Stage.MyCode -> OwnCodeContent(scanner)
                }
            }
            U2Theme(dark = chromeDark) {
                CameraTopBar(
                    title = stringResource(
                        if (scanner.stage == QrScannerModel.Stage.Scanning) R.string.verification_scan_title else R.string.verification_scan_mycode_title,
                    ),
                    onClose = scanner::close,
                ) {
                    val showsTorch = scanner.stage == QrScannerModel.Stage.Scanning &&
                        camera.status != QrCameraModel.Status.Denied && camera.status != QrCameraModel.Status.Unavailable
                    Crossfade(showsTorch, animationSpec = stateFade(), label = "torch") { shows ->
                        if (shows) {
                            val enabled = camera.torchAvailable || camera.status == QrCameraModel.Status.Idle || camera.status == QrCameraModel.Status.Requesting
                            CameraTorchButton(isOn = scanner.torchOn, isEnabled = enabled, onClick = scanner::toggleTorch)
                        } else {
                            HeaderSpacer()
                        }
                    }
                }
            }
            WarningOverlay(scanner)
        }
    }
}

// MARK: Backdrop

@Composable
private fun ScanBackdrop(camera: QrCameraModel, scanner: QrScannerModel, previewView: androidx.camera.view.PreviewView?) {
    val live = camera.status == QrCameraModel.Status.Running && scanner.stage == QrScannerModel.Stage.Scanning
    val shown by animateFloatAsState(if (live) 1f else 0f, u2Spec(U2Motion.state), label = "preview")
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        CameraPlaceholderBackdrop()
        if (previewView != null && scanner.stage == QrScannerModel.Stage.Scanning) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown })
        }
        if (shown > 0f) {
            // Legibility of the white header and hint over a live image (the 5.6 media shades).
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
                Box(Modifier.fillMaxWidth().height(190.dp).background(U2Theme.colors.mediaTopShade))
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(220.dp).background(U2Theme.colors.mediaBottomShade))
            }
        }
    }
}

// MARK: Scanning

/** The viewfinder (or the permission panel), the hint and “Kendi kodumu göster”: bottom stack pad 0 24 46, gap 16. */
@Composable
private fun ScanningContent(scanner: QrScannerModel, camera: QrCameraModel) {
    val locale = currentAppLocale()
    val denied = camera.status == QrCameraModel.Status.Denied
    Column(Modifier.fillMaxSize().u2Top(BELOW_TOP_BAR)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(denied, animationSpec = stateFade(), label = "denied") { isDenied ->
                if (isDenied) CameraDeniedPanel() else Viewfinder(showsMock = camera.status == QrCameraModel.Status.Placeholder, scanner = scanner)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutterL)
                .u2Bottom(46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!denied) {
                val hint = when (val purpose = scanner.purpose) {
                    is QrScannerModel.Purpose.SafetyNumber -> stringResource(R.string.verification_scan_hint, L10n.possessive(purpose.peer, locale))
                    QrScannerModel.Purpose.AddContact -> stringResource(R.string.verification_scan_contact_hint)
                }
                CameraHint(hint, Modifier.testTag(VerificationTags.HINT))
            }
            U2CameraPillButton(
                title = stringResource(R.string.verification_scan_showmycode),
                onClick = scanner::showOwnCode,
                glyph = PhosphorGlyph.QrCode,
                modifier = Modifier.testTag(VerificationTags.SHOW_MY_CODE),
            )
        }
    }
}

/** `cameraHint` in `#f5f4ff·80%`, centred, at most 300 wide. */
@Composable
private fun CameraHint(text: String, modifier: Modifier = Modifier) {
    U2Text(
        text,
        U2TextStyles.cameraHint,
        color = U2Theme.colors.onMedia.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 300.dp).fillMaxWidth(),
    )
}

/** Where the content under the 6.2 top bar starts (design y 100). */
private val BELOW_TOP_BAR = 100.dp

// MARK: Viewfinder

/**
 * 256 × 256: four 46 dp corner brackets (3 dp `#d2cefd`, outer r18) and the 220 × 2 scan line (r2,
 * glow `0 0 18px 3px #9184d9@70%`), static at 47 % in the references. Without a camera the design's
 * phone mock sits in the frame (inset 42, pad 12, r16, `#f3f5fe@88%`, rotated −5°).
 */
@Composable
private fun Viewfinder(showsMock: Boolean, scanner: QrScannerModel) {
    val label = stringResource(R.string.verification_scan_a11y_viewfinder)
    Box(
        Modifier
            .size(256.dp)
            .testTag(VerificationTags.VIEWFINDER)
            .semantics { contentDescription = label },
    ) {
        if (showsMock) {
            SimulatorQrMock(scanner, Modifier.offset(42.dp, 42.dp).size(172.dp))
        }
        ScanCorner(0f, Modifier.testTag(VerificationTags.corner(1)))
        ScanCorner(90f, Modifier.offset(x = 210.dp).testTag(VerificationTags.corner(2)))
        ScanCorner(270f, Modifier.offset(y = 210.dp).testTag(VerificationTags.corner(3)))
        ScanCorner(180f, Modifier.offset(210.dp, 210.dp).testTag(VerificationTags.corner(4)))
        ScanLine()
    }
}

/**
 * The scan line sweeps softly up and down inside the frame (2.4 s per sweep on the design curve,
 * ping-pong). Static at 47 % under reduce motion, with the frozen design clock, or while the app is
 * not in front.
 */
@Composable
private fun ScanLine() {
    val reduceMotion = LocalReduceMotion.current
    val clock = LocalU2Clock.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moves = !reduceMotion && !clock.isFrozen
    val running = moves && LocalIsLayerVisible.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val base = elapsed
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { frame -> elapsed = base + (frame - start) / 1_000_000_000f }
        }
    }
    val shape = RoundedCornerShape(2.dp)
    val glow = U2BoxShadows.glowScanLine
    val color = U2Theme.colors.scanCorner
    Box(
        Modifier
            .offset(x = 18.dp)
            .graphicsLayer { translationY = 256.dp.toPx() * (if (moves) ScanLinePath.position(elapsed) else ScanLinePath.REST) }
            .width(220.dp)
            .height(2.dp)
            .testTag(VerificationTags.SCAN_LINE)
            .u2BoxShadow(shape, glow)
            .background(color, shape),
    )
}

/** Where the scan line is after a time (iOS `ScanLine.position`). */
internal object ScanLinePath {
    const val REST = 0.47f
    private const val LOW = 0.14f
    private const val HIGH = 0.80f
    private const val SWEEP = 2.4f
    private val curve = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    /** Starts at 47 % moving down (half-way through a sweep), then ping-pongs. */
    fun position(elapsedSeconds: Float): Float {
        val half = (REST - LOW) / (HIGH - LOW)
        val phase = (elapsedSeconds / SWEEP + half) % 2f
        val leg = if (phase < 1f) phase else 2f - phase
        return LOW + (HIGH - LOW) * curve.transform(leg)
    }
}

/**
 * The phone with the peer's code (no camera here). Tap to read the matching code, touch and hold
 * for a different one; both are also accessibility actions.
 */
@Composable
private fun SimulatorQrMock(scanner: QrScannerModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(U2Radius.qrCardScan)
    fun simulate(match: Boolean) {
        val (matching, mismatching) = scanner.simulatedCodes()
        val code = if (match) matching else mismatching
        if (code != null) scope.launch { scanner.handle(code) }
    }
    val label = stringResource(R.string.verification_scan_a11y_simulatormock)
    val matchLabel = stringResource(R.string.verification_scan_a11y_simulatematch)
    val mismatchLabel = stringResource(R.string.verification_scan_a11y_simulatemismatch)
    Box(
        modifier
            .rotate(-5f)
            .u2BoxShadow(shape, U2BoxShadows.qrScanCard)
            .background(U2Theme.colors.qrPaperScan, shape)
            .pointerInput(scanner) {
                detectTapGestures(onTap = { simulate(true) }, onLongPress = { simulate(false) })
            }
            .clearAndSetSemantics {
                contentDescription = label
                testTag = VerificationTags.SIMULATOR_MOCK
                customActions = listOf(
                    CustomAccessibilityAction(matchLabel) { simulate(true); true },
                    CustomAccessibilityAction(mismatchLabel) { simulate(false); true },
                )
            }
            .padding(12.dp),
    ) {
        QrModulesView(DesignMockQrModules, Modifier.fillMaxSize())
    }
}

// MARK: Torch

/** The torch as a camera-surface C4 button: `ph:lightning` 21, filled while the torch is on, the two glyphs cross-fading. */
@Composable
private fun CameraTorchButton(isOn: Boolean, isEnabled: Boolean, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val on by animateFloatAsState(if (isOn) 1f else 0f, u2Spec(U2Motion.state), label = "torchGlyph")
    val label = stringResource(R.string.verification_scan_a11y_torch)
    val value = stringResource(if (isOn) R.string.common_a11y_on else R.string.common_a11y_off)
    Box(
        Modifier
            .size(U2Size.iconButton)
            .alpha(if (isEnabled) 1f else U2Opacity.disabled)
            .u2PressEffect(source)
            .background(colors.onMedia.copy(alpha = if (pressed) 0.2f else 0.12f), CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .clickable(source, indication = null, enabled = isEnabled, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = value
                selected = isOn
                role = Role.Button
            }
            .testTag(VerificationTags.TORCH),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(PhosphorGlyph.Lightning, size = 21.dp, tint = colors.onMedia, modifier = Modifier.alpha(1f - on))
        LightningFill(Modifier.size(21.dp).alpha(on))
    }
}

/**
 * Phosphor 2.1.1 `lightning-fill` (MIT), drawn from its SVG path on the 256 × 256 grid: the bundled
 * icons have the regular weight only for this glyph (the design draws the torch off).
 */
@Composable
private fun LightningFill(modifier: Modifier = Modifier) {
    val ink = U2Theme.colors.onMedia
    Canvas(modifier) {
        val scale = minOf(size.width, size.height) / 256f
        val path = LightningFillPath.path(scale)
        drawPath(path, ink)
    }
}

internal object LightningFillPath {
    /** `M213.85,125.46 l-112,120 a8,8,0,0,1-13.69-7 l14.66-73.33 L45.19,143.49 a8,8,0,0,1-3-13 l112-120 a8,8,0,0,1,13.69,7 L153.18,90.9 l57.63,21.61 a8,8,0,0,1,3,12.95 Z`. */
    fun path(scale: Float): Path {
        val path = Path()
        var current = Offset(213.85f, 125.46f)
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x: Float, y: Float) {
            current = Offset(x, y)
            path.lineTo(x * scale, y * scale)
        }
        fun arc(x: Float, y: Float) {
            val end = Offset(x, y)
            svgArc(path, current, end, 8f, scale)
            current = end
        }
        path.moveTo(p(213.85f, 125.46f).x, p(213.85f, 125.46f).y)
        line(101.85f, 245.46f)
        arc(88.16f, 238.46f)
        line(102.82f, 165.13f)
        line(45.19f, 143.49f)
        arc(42.19f, 130.49f)
        line(154.19f, 10.49f)
        arc(167.88f, 17.49f)
        line(153.18f, 90.9f)
        line(210.81f, 112.51f)
        arc(213.81f, 125.46f)
        path.close()
        return path
    }

    /** SVG `a r,r,0,0,1` (small arc, positive sweep) from [start] to [end]: SVG 1.1 F.6.5 with rx = ry and no rotation. */
    private fun svgArc(path: Path, start: Offset, end: Offset, radius: Float, scale: Float) {
        val x1 = (start.x - end.x) / 2f
        val y1 = (start.y - end.y) / 2f
        val d2 = x1 * x1 + y1 * y1
        val r = max(radius, sqrt(d2))
        val coefficient = sqrt(max(0f, (r * r - d2) / d2))
        val centre = Offset(coefficient * y1 + (start.x + end.x) / 2f, -coefficient * x1 + (start.y + end.y) / 2f)
        val from = Math.toDegrees(atan2(start.y - centre.y, start.x - centre.x).toDouble()).toFloat()
        val to = Math.toDegrees(atan2(end.y - centre.y, end.x - centre.x).toDouble()).toFloat()
        var sweep = to - from
        if (sweep < 0f) sweep += 360f
        // A positive sweep on a y-down grid is an increasing angle (clockwise on screen).
        path.arcTo(Rect(Offset(centre.x * scale, centre.y * scale), r * scale), from, sweep, false)
    }
}

// MARK: Warning card

/** The 5.6 floating card under the top bar, pad 16 12 0 12; tap to dismiss. */
@Composable
private fun WarningOverlay(scanner: QrScannerModel) {
    val warning = scanner.warning
    val shown = remember { WarningMemory() }
    if (warning != null) shown.last = warning
    val visible = warning != null && scanner.stage == QrScannerModel.Stage.Scanning
    val drop = U2Transitions.drop()
    Box(Modifier.fillMaxSize()) {
        // A new warning drops in again (iOS gives the card the warning as its identity).
        androidx.compose.runtime.key(scanner.warningGeneration) {
            AnimatedVisibility(
                visible = visible,
                enter = drop.enter,
                exit = drop.exit,
                modifier = Modifier
                    .u2Top(BELOW_TOP_BAR)
                    .padding(start = 12.dp, top = 16.dp, end = 12.dp),
            ) {
                shown.last?.let { WarningCard(it, onDismiss = scanner::dismissWarning) }
            }
        }
    }
}

private class WarningMemory {
    var last: QrScannerModel.Warning? = null
}

/**
 * The 5.6 floating card (`surface·92%` + blur, r20, inner `danger·45%`, `0 14px 40px #000@45%`, pad
 * 14 16, gap 12) with `ph:warning-circle` 22 `danger`, `noteTitle` and `captionTighter`.
 */
@Composable
private fun WarningCard(warning: QrScannerModel.Warning, onDismiss: () -> Unit) {
    val (title, message) = QrScannerModel.texts(warning)
    val colors = U2Theme.colors
    FloatingBanner(
        FloatingBannerKind.Warning,
        Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics(mergeDescendants = true) {}
            .testTag(VerificationTags.WARNING),
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhosphorIcon(PhosphorGlyph.WarningCircle, size = 22.dp, tint = colors.danger)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                U2Text(stringResource(title), U2TextStyles.noteTitle, color = colors.text)
                U2Text(stringResource(message), U2TextStyles.captionTighter, color = colors.textSecondary)
            }
        }
    }
}

// MARK: Camera permission denied

/** “Kamera izni gerekli”: the message and a pill that opens the app's settings. */
@Composable
private fun CameraDeniedPanel() {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val colors = U2Theme.colors
    Column(
        Modifier
            .padding(horizontal = U2Spacing.screenGutterL)
            .testTag(VerificationTags.DENIED),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(colors.onCameraGlass, CircleShape)
                .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(PhosphorGlyph.Camera, size = 28.dp, tint = colors.onMedia)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2Text(
                stringResource(R.string.verification_scan_denied_title),
                U2TextStyles.navTitle,
                color = colors.onMedia,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            CameraHint(stringResource(R.string.verification_scan_denied_message))
        }
        U2CameraPillButton(
            title = stringResource(R.string.verification_scan_denied_opensettings),
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { guard.launching { context.startActivity(intent) } }
            },
            glyph = PhosphorGlyph.GearSix,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// MARK: Own code

/**
 * “Kendi kodumu göster” (the 6.1 layout without the peer): own avatar and name, the QR card, the
 * digits (safety number only), a hint and “Kodu okut” back to the camera. The camera is off while
 * it shows.
 */
@Composable
private fun OwnCodeContent(scanner: QrScannerModel) {
    Column(
        Modifier
            .fillMaxSize()
            .u2Top(BELOW_TOP_BAR)
            .testTag(VerificationTags.OWN_CODE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        scanner.ownCode?.let { code ->
            MyCodeView(code.username, code.payload)
            code.groups?.let { SafetyDigitsGrid(it, Modifier.padding(top = 24.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutterL)
                .u2Bottom(46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val hint = when (val purpose = scanner.purpose) {
                is QrScannerModel.Purpose.SafetyNumber -> stringResource(R.string.verification_scan_mycode_hint, purpose.peer)
                QrScannerModel.Purpose.AddContact -> stringResource(R.string.verification_scan_contact_mycode_hint)
            }
            CameraHint(hint)
            U2CameraPillButton(
                title = stringResource(R.string.verification_scan_mycode_scan),
                onClick = scanner::showScanning,
                glyph = PhosphorGlyph.Scan,
                modifier = Modifier.testTag(VerificationTags.BACK_TO_SCANNING),
            )
        }
    }
}
