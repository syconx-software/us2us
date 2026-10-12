package com.syconx.us2us.ui.bubbles

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.ui.media.DecodedImage
import com.syconx.us2us.ui.media.DesignArtworkView
import com.syconx.us2us.ui.media.MediaImageDecoder
import com.syconx.us2us.ui.media.MemoryImageView
import java.net.URI
import kotlin.math.ceil
import kotlin.math.max

/**
 * A bubble's photo, video poster or quote thumbnail in the fixed frame the design gives it (so
 * nothing moves when the picture arrives): the design's painted artwork, or a picture decoded off
 * the main thread at the pixel size it is shown, fading in over `surface`. Decoded pictures stay
 * in memory only ([BubbleImageCache]).
 */
@Composable
fun BubbleMediaView(source: MediaSource, size: DpSize, modifier: Modifier = Modifier) {
    val sized = modifier.size(size)
    val pixels = with(LocalDensity.current) { ceil(max(size.width.toPx(), size.height.toPx())).toInt() }
    when (source) {
        is MediaSource.Artwork -> DesignArtworkView(source.artwork, sized)
        is MediaSource.Memory -> MemoryImageView(source.data, sized, maxPixel = pixels)
        is MediaSource.File -> {
            val context = LocalContext.current
            BubbleImageCache.install(context)
            DecodedImage(
                key = BubbleImageCache.key(source.uri, pixels),
                modifier = sized,
                initial = BubbleImageCache.image(source.uri, pixels),
            ) { BubbleImageCache.decode(context, source.uri, pixels) }
        }
    }
}

/**
 * Decoded bubble pictures, in memory only (never on disk): at most 120 of them and 96 MB. Emptied
 * when the app's interface is hidden (it went to the background or locked) and when memory runs
 * low; [clear] empties it at once (the lock).
 */
object BubbleImageCache {
    private const val COUNT_LIMIT = 120
    private const val BYTE_LIMIT = 96L * 1024 * 1024

    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    @Volatile
    private var installed = false

    /** The cache key of [uri] decoded to [maxPixel]. */
    fun key(uri: URI, maxPixel: Int): String = "$maxPixel|$uri"

    /** Already decoded (no work). */
    @Synchronized
    fun image(uri: URI, maxPixel: Int): ImageBitmap? = entries[key(uri, maxPixel)]

    /** Decodes [uri] (call off the main thread) and keeps it; `null` if the file is not a picture. */
    fun decode(context: Context, uri: URI, maxPixel: Int): ImageBitmap? {
        image(uri, maxPixel)?.let { return it }
        val decoded = MediaImageDecoder.decode(context, uri, maxPixel) ?: return null
        put(key(uri, maxPixel), decoded)
        return decoded
    }

    /** Drops every decoded picture. */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    /** The number of pictures held (tests). */
    @get:Synchronized
    val size: Int get() = entries.size

    @Synchronized
    private fun put(key: String, image: ImageBitmap) {
        entries.remove(key)?.let { bytes -= it.byteCount }
        entries[key] = image
        bytes += image.byteCount
        val iterator = entries.entries.iterator()
        while ((entries.size > COUNT_LIMIT || bytes > BYTE_LIMIT) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            bytes -= eldest.value.byteCount
            iterator.remove()
        }
    }

    private val ImageBitmap.byteCount: Long get() = width.toLong() * height * 4

    /** Listens for the app going to the background and for low memory (once per process). */
    internal fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    clear()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    clear()
                }
            },
        )
    }
}
