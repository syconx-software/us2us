package com.syconx.us2us.feature.verification

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.syconx.us2us.ui.type.textStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.QrModules
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2GlassSurface
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.cameraVignetteCenter
import com.syconx.us2us.ui.tokens.U2FixedColors
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.roundToInt

// Pieces only 06 uses: the camera surface of 6.2, the digits, the layout helper, confirmations.

/** The test tags of 06 (the iOS accessibility identifiers). */
object VerificationTags {
    const val BACK = "safety.back"
    const val MORE = "contact.more"
    const val TITLE = "contact.title"
    const val TABS = "contact.tabs"
    const val LINE = "safety.line"
    const val QR = "safety.qr"
    const val DIGITS = "safety.digits"
    const val SCAN = "safety.scan"
    const val MARK_VERIFIED = "safety.markVerified"
    const val ACCEPT_CHANGE = "safety.acceptChange"
    const val CHANGED = "safety.changed"
    const val VERIFIED_SINCE = "safety.verifiedSince"
    const val CONFIRM = "safety.confirm"
    const val CONFIRM_CANCEL = "safety.confirm.cancel"

    /** The contact screen's block and report (iOS identifiers; the confirmation is the app's floating one). */
    const val BLOCKED_MARK = "contact.blockedMark"
    const val BLOCKED_NOTE = "contact.blocked"
    const val UNBLOCK = "contact.unblock"
    const val BLOCK_CONFIRM = "contact.block.confirm"
    const val BLOCK_REPORT = "contact.block.report"
    const val BLOCK_CANCEL = "contact.block.cancel"

    const val SCAN_SCREEN = "scan.screen"
    const val SCAN_CLOSE = "scan.close"
    const val SCAN_TITLE = "scan.title"
    const val TORCH = "scan.torch"
    const val VIEWFINDER = "scan.viewfinder"
    const val SCAN_LINE = "scan.line"
    const val HINT = "scan.hint"
    const val SHOW_MY_CODE = "scan.showMyCode"
    const val BACK_TO_SCANNING = "scan.backToScanning"
    const val WARNING = "scan.warning"
    const val SIMULATOR_MOCK = "scan.simulatorMock"
    const val DENIED = "scan.denied"
    const val OWN_CODE = "scan.ownCode"

    const val VERIFIED_SCREEN = "verified.screen"
    const val VERIFIED_CLOSE = "verified.close"
    const val VERIFIED_LINE = "verified.line"
    const val VERIFIED_TEXT = "verified.text"
    const val VERIFIED_TITLE = "verified.title"
    const val VERIFIED_BODY = "verified.body"
    const val VERIFIED_PILL = "verified.pill"
    const val VERIFIED_BACK = "verified.back"

    const val PHOTOS = "contact.photos"
    const val PHOTOS_EMPTY = "contact.photos.empty"
    const val DOCUMENTS = "contact.documents"
    const val DOCUMENTS_EMPTY = "contact.documents.empty"
    const val GALLERY = "contact.gallery"

    fun corner(index: Int) = "scan.corner.$index"

    fun digits(index: Int) = "safety.digits.$index"

    fun tab(tab: Any) = "contact.tab.$tab"

    fun tile(id: String) = "contact.tile.$id"

    fun document(id: String) = "contact.document.$id"
}

// MARK: - The design's phone mock (6.2)

/**
 * The design's placeholder pattern (the design file's `buildQr`): 29 × 29, Lehmer generator seeded
 * 20261008, three finder patterns. Not a valid QR code; used only for the phone mock on the
 * camera placeholder (6.2), so the screen matches the reference.
 */
val DesignMockQrModules: QrModules by lazy {
    val n = 29
    var seed = 20_261_008L
    fun random(): Double {
        seed = (seed * 48_271L) % 2_147_483_647L
        return seed.toDouble() / 2_147_483_647.0
    }
    val grid = Array(n) { BooleanArray(n) { random() > 0.52 } }
    fun finder(r0: Int, c0: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val rr = r0 + r
                val cc = c0 + c
                if (rr < 0 || cc < 0 || rr >= n || cc >= n) continue
                val ring = maxOf(kotlin.math.abs(r - 3), kotlin.math.abs(c - 3))
                grid[rr][cc] = r in 0..6 && c in 0..6 && (ring == 3 || ring <= 1)
            }
        }
    }
    finder(0, 0)
    finder(0, n - 7)
    finder(n - 7, 0)
    QrModules(n, BooleanArray(n * n) { grid[it / n][it % n] })
}

// MARK: - Digits (6.1, 6.2 own code)

/**
 * The 12 groups of 5 digits: 4 columns, column gap 18, row gap 10, Mono 400 15/23.25
 * `text·88%`, centred. TalkBack reads them group by group.
 */
@Composable
fun SafetyDigitsGrid(groups: List<String>, modifier: Modifier = Modifier, color: Color = U2Theme.colors.textSafetyDigits) {
    val label = stringResource(R.string.verification_safety_a11y_digits)
    val spoken = remember(groups) { VerificationText.spokenDigits(groups) }
    // Four groups a row as drawn; with a large system font or a narrow phone, three or two, so no
    // digit of the number ever runs off the screen (the order reads the same, row by row).
    val measurer = rememberTextMeasurer()
    val digitStyle = U2TextStyles.monoSafetyDigits.textStyle()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val gap = 18.dp
        val groupWidth = with(LocalDensity.current) { measurer.measure(groups.firstOrNull() ?: "00000", digitStyle).size.width.toDp() }
        val perRow = listOf(4, 3, 2).firstOrNull { groupWidth * it + gap * (it - 1) <= maxWidth } ?: 1
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = spoken
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.chunked(perRow).forEachIndexed { row, cells ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                cells.forEachIndexed { column, group ->
                    U2Text(
                        group,
                        U2TextStyles.monoSafetyDigits,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag(VerificationTags.digits(row * perRow + column)),
                    )
                }
            }
        }
    }
    }
}

// MARK: - Layout helper

/**
 * The design's fixed layout when it fits (402 × 874 at the default text size); a scroll view on
 * shorter screens or with large text (iOS `ViewThatFits`). Weighted children share what is left of
 * the screen's height, as SwiftUI spacers do.
 */
@Composable
fun FitOrScroll(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        val viewport = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0)) {
            // A minimum height with an open maximum: weights fill up to the viewport, and a taller
            // content scrolls.
            Column(Modifier.fillMaxWidth().heightIn(min = viewport), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

// MARK: - Announcements

/** What TalkBack says next (an iOS `AccessibilityNotification.Announcement`). */
@Stable
class Announcer {
    var text: String by mutableStateOf("")
        private set
    private var count by mutableIntStateOf(0)

    fun say(text: String) {
        count++
        // A repeated sentence is still a change of the live region.
        this.text = if (count % 2 == 0) text else "$text​"
    }
}

/** A polite live region that speaks the [announcer]'s sentences; it draws nothing. */
@Composable
fun AnnouncementRegion(announcer: Announcer, modifier: Modifier = Modifier) {
    val text = announcer.text
    Box(
        modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    )
}

// MARK: - Camera surface (6.2)

/**
 * The 6.2 top bar: 44 high at design y 56, pad 0 16, close and torch as camera-surface C4 buttons,
 * `navTitle` in `#f5f4ff` centred.
 */
@Composable
fun CameraTopBar(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.headerTopButtons)
            .padding(horizontal = U2Spacing.screenGutterS)
            .height(U2Size.iconButton),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(
            title,
            U2TextStyles.navTitle,
            color = U2Theme.colors.onMedia,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 250.dp)
                .semantics { heading() }
                .testTag(VerificationTags.SCAN_TITLE),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            U2GlassIconButton(
                PhosphorGlyph.X,
                contentDescription = stringResource(R.string.common_a11y_close),
                onClick = onClose,
                surface = U2GlassSurface.Camera,
                modifier = Modifier.testTag(VerificationTags.SCAN_CLOSE),
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * One viewfinder corner: 46 × 46, a 3 dp `#d2cefd` border on two sides with an 18 dp outer radius
 * (CSS border + border-radius), drawn top-left and turned by [rotation] degrees about its centre.
 */
@Composable
fun ScanCorner(rotation: Float, modifier: Modifier = Modifier, lineWidth: Dp = 3.dp, radius: Dp = U2Radius.scanCorner) {
    val color = U2Theme.colors.scanCorner
    Canvas(modifier.size(46.dp)) {
        val inset = lineWidth.toPx() / 2f
        val r = radius.toPx()
        val path = Path().apply {
            moveTo(inset, size.height)
            lineTo(inset, r)
            arcTo(Rect(Offset(r, r), r - inset), 180f, 90f, false)
            lineTo(size.width, inset)
        }
        rotate(rotation, center) { drawPath(path, color, style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Butt)) }
    }
}

/**
 * The 6.2 camera placeholder (design): `#0c0d14` with a radial `#2a2d3e → #0c0d14` vignette
 * (60 % × 45 % at 50 % / 45 %, ending at 80 %) and a 6 dp grid of `#e9e9ed@8%` dots (r 1, soft
 * to 1.5).
 */
@Composable
fun CameraPlaceholderBackdrop(modifier: Modifier = Modifier) {
    val background = U2Theme.colors.cameraBg
    val density = LocalDensity.current
    val tile = remember(density) { dotTile(with(density) { 6.dp.toPx() }.roundToInt().coerceAtLeast(2), density.density) }
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawWithCache {
                val cx = size.width * 0.5f
                val cy = size.height * 0.45f
                val rx = size.width * 0.6f
                val ry = size.height * 0.45f
                val vignette = Brush.radialGradient(
                    0f to U2FixedColors.cameraVignetteCenter,
                    0.8f to background,
                    1f to background,
                    center = Offset(cx, cy),
                    radius = ry,
                )
                val dots = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
                onDrawBehind {
                    drawRect(background)
                    // The ellipse: a circle of radius ry stretched sideways by rx / ry about the centre.
                    scale(scaleX = rx / ry, scaleY = 1f, pivot = Offset(cx, cy)) {
                        val stretch = rx / ry
                        drawRect(vignette, topLeft = Offset(cx - cx / stretch, 0f), size = Size(size.width / stretch, size.height))
                    }
                    drawRect(dots)
                }
            },
    )
}

/** One 6 × 6 dp tile with a soft dot in its centre (`#e9e9ed@8%`, solid to r 1, gone at 1.5). */
private fun dotTile(sidePx: Int, density: Float): ImageBitmap {
    val bitmap = ImageBitmap(sidePx, sidePx)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val dot = Color(0xFFE9E9ED).copy(alpha = 0.08f)
    val radius = 1.5f * density
    val centre = Offset(sidePx / 2f, sidePx / 2f)
    CanvasDrawScope().draw(androidx.compose.ui.unit.Density(density), LayoutDirection.Ltr, canvas, Size(sidePx.toFloat(), sidePx.toFloat())) {
        drawCircle(
            Brush.radialGradient(0f to dot, (1f / 1.5f) to dot, 1f to dot.copy(alpha = 0f), center = centre, radius = radius),
            radius = radius,
            center = centre,
        )
    }
    return bitmap
}

// MARK: - Confirmations (6.1)

/**
 * A confirmation that changes nothing irreversible (“Doğrulandı olarak işaretle”, “Doğrulamadan
 * devam et”) inside a floating sheet, in the destructive confirmation's layout: the Doto sheet
 * title, the `sheetBody` paragraph, then C1 and the C3 cancel. The host sheet pads 18 18 28 18.
 */
@Composable
fun VerificationConfirmation(
    title: String,
    message: String,
    confirmTitle: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmGlyph: PhosphorGlyph? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(title, U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(message, U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2PrimaryButton(confirmTitle, onClick = onConfirm, glyph = confirmGlyph, modifier = Modifier.testTag(VerificationTags.CONFIRM))
            U2TextButton(
                title = stringResource(R.string.common_cancel),
                onClick = onCancel,
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                modifier = Modifier.testTag(VerificationTags.CONFIRM_CANCEL),
            )
        }
    }
}

/** The floating sheet's padding around a confirmation (18 18 28 18). */
val ConfirmationPadding: Modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp)

/** The 0.36 s soft cross-fade (the short one under reduce motion), for `Crossfade`. */
@Composable
internal fun stateFade(): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
    com.syconx.us2us.ui.motion.U2Motion.resolve(com.syconx.us2us.ui.motion.U2Motion.state, com.syconx.us2us.ui.motion.LocalReduceMotion.current).spec()

/** A full-screen box in [color] (the camera's background behind a model that is not ready yet). */
@Composable
internal fun FilledScreen(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(color))
}
