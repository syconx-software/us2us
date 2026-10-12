package com.syconx.us2us.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.syconx.us2us.ui.tokens.U2ColorScheme
import com.syconx.us2us.ui.tokens.U2FixedColors

// Fixed colours that tokens.json writes as a range or a derived value, and the gradients
// (tokens.md › Gradients & patterns).

/** QR card paper on the scanner mock (#f3f5fe@88%). */
val U2ColorScheme.qrPaperScan: Color get() = qrPaper.copy(alpha = 0.88f)

/** Round buttons and pills over media: #0c0d16 at 40 / 45 / 55 %. */
fun U2ColorScheme.onMediaGlass(alpha: Float): Color = onMediaGlassBase.copy(alpha = alpha)

/** Pure black at an opacity — only for the design's drop shadows (`0 2px 6px #000@25%` …). */
fun U2ColorScheme.shadowBlack(alpha: Float): Color = Color.Black.copy(alpha = alpha)

/** 3.4 app-icon mock gradient: #423a6a → #161826, and the mark #b5abfc. */
val U2FixedColors.appIconTop: Color get() = Color(0xFF423A6A)
val U2FixedColors.appIconBottom: Color get() = Color(0xFF161826)
val U2FixedColors.appIconMark: Color get() = Color(0xFFB5ABFC)

/** `cameraVignette` stops (6.2): #2a2d3e → #0c0d14 at 80 %. */
val U2FixedColors.cameraVignetteCenter: Color get() = Color(0xFF2A2D3E)

/** `lineGradient`: accent400 → accent·30 % at 50 % → accent400 (the bar of the line mark). */
val U2ColorScheme.lineGradient: Brush
    get() = Brush.horizontalGradient(0f to accent400, 0.5f to accentLineMid, 1f to accent400)

/** `linePacket`: transparent → accent200 → transparent. */
val U2ColorScheme.linePacketGradient: Brush
    get() = Brush.horizontalGradient(listOf(accent200.copy(alpha = 0f), accent200, accent200.copy(alpha = 0f)))

/** `composerFade`: transparent → bg at 30 % (and below). */
val U2ColorScheme.composerFade: Brush
    get() = Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.3f to bg, 1f to bg)

/** 5.6 top shade: #05060c@70 % → transparent (190 dp). */
val U2ColorScheme.mediaTopShade: Brush
    get() = Brush.verticalGradient(listOf(scrimBase.copy(alpha = 0.7f), scrimBase.copy(alpha = 0f)))

/** 5.6 bottom shade: transparent → #05060c@80 % (220 dp). */
val U2ColorScheme.mediaBottomShade: Brush
    get() = Brush.verticalGradient(listOf(scrimBase.copy(alpha = 0f), scrimBase.copy(alpha = 0.8f)))
