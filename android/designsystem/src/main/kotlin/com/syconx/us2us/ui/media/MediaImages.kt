package com.syconx.us2us.ui.media

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes pictures for display at the size they are shown (iOS ImageIO thumbnails): never larger
 * than `maxPixel` on the long side, oriented by their EXIF data. Call off the main thread.
 */
object MediaImageDecoder {
    /** An image file (`file:` URI, or a `content:` URI through [context]); `null` if it is not an image. */
    fun decode(context: Context, uri: URI, maxPixel: Int): ImageBitmap? = runCatching {
        val source = if (uri.scheme == null || uri.scheme == "file") {
            ImageDecoder.createSource(File(uri.path))
        } else {
            ImageDecoder.createSource(context.contentResolver, Uri.parse(uri.toString()))
        }
        decode(source, maxPixel)
    }.getOrNull()

    /** In-memory image bytes; `null` if they are not an image. */
    fun decode(data: Bytes, maxPixel: Int): ImageBitmap? {
        if (data.isEmpty()) return null
        return runCatching { decode(ImageDecoder.createSource(ByteBuffer.wrap(data.toByteArray())), maxPixel) }.getOrNull()
    }

    private fun decode(source: ImageDecoder.Source, maxPixel: Int): ImageBitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            val limit = max(1, maxPixel)
            if (long > limit) {
                val scale = limit.toFloat() / long
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
            }
        }.asImageBitmap()
}

/** Media from a [MediaSource]: painted design artwork, or a picture. Fills its box (crop). */
@Composable
fun MediaView(source: MediaSource, modifier: Modifier = Modifier) {
    when (source) {
        is MediaSource.Artwork -> DesignArtworkView(source.artwork, modifier)
        is MediaSource.File -> FileImageView(source.uri, modifier)
        is MediaSource.Memory -> MemoryImageView(source.data, modifier)
    }
}

/** An image file, decoded off the main thread and faded in over `surface`. */
@Composable
fun FileImageView(uri: URI, modifier: Modifier = Modifier, maxPixel: Int = 2_048) {
    val context = LocalContext.current
    DecodedImage(key = uri to maxPixel, modifier = modifier) { MediaImageDecoder.decode(context, uri, maxPixel) }
}

/** In-memory image bytes (live media), decoded off the main thread and shown over `surface`; nothing moves when they arrive. */
@Composable
fun MemoryImageView(data: Bytes, modifier: Modifier = Modifier, maxPixel: Int = 2_048) {
    DecodedImage(key = Triple(data.size, data.hashCode(), maxPixel), modifier = modifier) { MediaImageDecoder.decode(data, maxPixel) }
}

/**
 * `surface`, with [decode]'s picture fading in over it (0.24 s) once it is decoded on a
 * background thread. [initial] is shown at once (a picture already in a cache).
 */
@Composable
internal fun DecodedImage(
    key: Any,
    modifier: Modifier = Modifier,
    initial: ImageBitmap? = null,
    decode: () -> ImageBitmap?,
) {
    val reduceMotion = LocalReduceMotion.current
    var image by remember(key) { mutableStateOf(initial) }
    val alpha = remember(key) { Animatable(if (initial != null) 1f else 0f) }
    LaunchedEffect(key) {
        if (image != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decode() } ?: return@LaunchedEffect
        image = decoded
        alpha.animateTo(1f, U2Motion.resolve(U2Motion.micro, reduceMotion).spec())
    }
    Box(modifier.clipToBounds().background(U2Theme.colors.surface).clearAndSetSemantics {}) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
                contentScale = ContentScale.Crop,
            )
        }
    }
}
