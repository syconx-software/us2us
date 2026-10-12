package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.tokens.U2Radius
import kotlin.math.min

/** A square grid of QR modules (`true` = dark). */
@Immutable
class QrModules(val size: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == size * size) { "a $size × $size code needs ${size * size} modules, got ${bits.size}" }
    }

    operator fun get(row: Int, column: Int): Boolean = bits[row * size + column]

    override fun equals(other: Any?): Boolean = other is QrModules && other.size == size && other.bits.contentEquals(bits)

    override fun hashCode(): Int = 31 * size + bits.contentHashCode()

    override fun toString(): String = "QrModules($size × $size)"

    companion object {
        private const val CACHE_LIMIT = 16
        private val cache = HashMap<String, QrModules>()

        /**
         * The real, scannable QR code of [payload] (error correction M), without the quiet zone: the
         * card's padding is the margin. The text's UTF-8 bytes are encoded as they are, without an
         * ECI header, as iOS does; the segments are chosen as Core Image chooses them (mixed modes,
         * the shortest bit stream), so the modules are the same as on iOS: the safety code's
         * `U2SN:` + base32 is version 5, 37 × 37; a contact code mixes byte and numeric segments.
         */
        fun of(payload: String): QrModules? {
            // ISO-8859-1 maps each UTF-8 byte to one character, which the encoder writes back as that byte.
            val raw = String(payload.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
            val code = try {
                Encoder.encode(raw, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_COMPACT to "true"))
            } catch (_: WriterException) {
                return null
            }
            val matrix = code.matrix ?: return null
            val n = matrix.width
            if (n <= 0 || n != matrix.height) return null
            val bits = BooleanArray(n * n) { matrix.get(it % n, it / n).toInt() == 1 }
            return QrModules(n, bits)
        }

        /** The code of [payload], generated once (a sheet or cover redraws many times while it animates). */
        fun cached(payload: String): QrModules? {
            synchronized(cache) { cache[payload]?.let { return it } }
            val modules = of(payload) ?: return null
            synchronized(cache) {
                if (cache.size > CACHE_LIMIT) cache.clear()
                cache[payload] = modules
            }
            return modules
        }
    }
}

/**
 * Draws [modules] as crisp squares in `qrInk` (the design: `shape-rendering: crispEdges`, each module
 * 1.02 wide so neighbours never show a seam), scaled to the smaller side.
 */
@Composable
fun QrModulesView(modules: QrModules, modifier: Modifier = Modifier) {
    val ink = U2Theme.colors.qrInk
    Box(
        modifier
            .clearAndSetSemantics {}
            .drawWithCache {
                val n = modules.size
                val unit = min(size.width, size.height) / n
                val path = Path()
                for (row in 0 until n) {
                    for (column in 0 until n) {
                        if (modules[row, column]) {
                            path.addRect(Rect(column * unit, row * unit, column * unit + unit * 1.02f, row * unit + unit * 1.02f))
                        }
                    }
                }
                val paint = Paint().apply {
                    color = ink
                    isAntiAlias = false
                }
                onDrawBehind { drawIntoCanvas { it.drawPath(path, paint) } }
            },
    )
}

/**
 * The QR card — every QR the app shows (6.1 safety number, “Kodumu göster” in 4.1 / 4.2 / 6.2, the
 * 7.1 profile code): 220 × 220, `qrPaper` (`#f3f5fe`) in both themes, r26, pad 16, ring `text·10%`
 * and glow `0 20px 50px -20px accent·60%`; modules `qrInk` (`#292b31`). The code fades in once it is
 * ready; `null` draws the empty card. [label] is what TalkBack reads.
 */
@Composable
fun QrCodeCard(modules: QrModules?, label: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.qrCard)
    Box(
        modifier
            .size(220.dp)
            .u2BoxShadow(shape, U2BoxShadow(colors.strokeCard, blur = 0.dp, spread = 1.dp), U2BoxShadows.glowQRCard)
            .background(colors.qrPaper, shape)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Image
            }
            .padding(16.dp),
    ) {
        val timing = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
        val shown = remember { ShownModules() }
        if (modules != null) shown.last = modules
        AnimatedVisibility(
            visible = modules != null,
            enter = fadeIn(timing.spec()),
            exit = fadeOut(timing.spec()),
        ) {
            shown.last?.let { QrModulesView(it, Modifier.fillMaxSize()) }
        }
    }
}

/** The card for a text payload (`ContactCode`, `SafetyQRCodec`); `null` draws the empty card. */
@Composable
fun QrCodeCard(payload: String?, label: String, modifier: Modifier = Modifier) {
    val modules = remember(payload) { payload?.let(QrModules::cached) }
    QrCodeCard(modules, label, modifier)
}

/** The last code shown, kept while the card fades it out. */
private class ShownModules {
    var last: QrModules? = null
}
