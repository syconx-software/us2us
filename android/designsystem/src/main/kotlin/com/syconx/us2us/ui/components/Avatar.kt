package com.syconx.us2us.ui.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.brand.BrandAvatar
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2TextStyle
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The design's avatar sizes (C12), their letter styles and online dots. */
enum class AvatarSize(
    val diameter: Dp,
    val letterStyle: U2TextStyle,
    /** The online dot (list: 15 with a 3 dp ring; header: 11 with a 2 dp ring). */
    val onlineDot: Dp,
    val onlineRing: Dp,
) {
    /** 64 — request preview profile (4.4). */
    Profile(64.dp, U2TextStyles.avatarInitialXL, 15.dp, 3.dp),

    /** 52 — chat-list rows, 4.2 result. */
    ListRow(52.dp, U2TextStyles.avatarInitialL, 15.dp, 3.dp),

    /** 50 — settings profile card. */
    Settings(50.dp, U2TextStyles.avatarInitialM50, 15.dp, 3.dp),

    /** 48 — verification line (6.1, 6.3). */
    Verify(48.dp, U2TextStyles.avatarInitialM48, 15.dp, 3.dp),

    /** 44 — request cards (4.3). */
    Request(44.dp, U2TextStyles.avatarInitialS44, 15.dp, 3.dp),

    /** 40 — own avatar on the chat list. */
    ChatsSelf(40.dp, U2TextStyles.avatarInitialXS, 15.dp, 3.dp),

    /** 38 — chat header. */
    Header(38.dp, U2TextStyles.avatarInitialXS, 11.dp, 2.dp),
}


/**
 * C12 — a circle with the username's first letter in Martian Mono 500; or, where the person chose
 * one (PROTOCOL §8.9), a preset cup ([BrandAvatar]) or their photo in the same circle. A preset id
 * this build does not know, a photo without its bytes and one that does not decode all draw the
 * letter; while a photo decodes (off the main thread, once per app session) the letter shows and
 * the photo fades in over it. TalkBack reads the person's name either way.
 *
 * The online dot is only for accepted contacts who share presence (4.1 note).
 */
@Composable
fun Avatar(
    username: String,
    palette: AvatarPalette,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.ListRow,
    showsOnlineDot: Boolean = false,
    picture: AvatarPicture = AvatarPicture.Initials,
) {
    val online = stringResource(R.string.common_a11y_online)
    Box(
        modifier
            .size(size.diameter)
            .clearAndSetSemantics {
                contentDescription = username
                if (showsOnlineDot) stateDescription = online
            },
    ) {
        AvatarFace(username, palette, size, picture)
        val fade = U2Transitions.fade(U2Motion.state)
        AnimatedVisibility(
            visible = showsOnlineDot,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fade.enter,
            exit = fade.exit,
        ) {
            PresenceDot(Modifier.then(u2TransitionBlur(fade)), diameter = size.onlineDot, ring = size.onlineRing)
        }
    }
}

/** What the circle shows: the initial, a preset or a photo (with the initial under it until it decodes). */
@Composable
private fun AvatarFace(username: String, palette: AvatarPalette, size: AvatarSize, picture: AvatarPicture) {
    when (val drawn = picture.choice.drawn) {
        is AvatarChoice.Preset -> BrandAvatar(drawn.id, size.diameter)
        AvatarChoice.Photo -> {
            val photo = picture.photo
            if (photo != null) {
                AvatarPhotoFace(photo, size.diameter) { AvatarLetter(username, palette, size) }
            } else {
                AvatarLetter(username, palette, size)
            }
        }
        AvatarChoice.Initials -> AvatarLetter(username, palette, size)
    }
}

/** C12 as drawn: the palette's circle and the first letter (palette D with its inner `accent700` ring). */
@Composable
private fun AvatarLetter(username: String, palette: AvatarPalette, size: AvatarSize) {
    val colors = U2Theme.colors
    val fill = when (palette) {
        AvatarPalette.A -> colors.accent800
        AvatarPalette.B -> colors.accent700
        AvatarPalette.C -> colors.neutral800
        AvatarPalette.D -> colors.accent900
    }
    val ink = when (palette) {
        AvatarPalette.A, AvatarPalette.B -> colors.accent100
        AvatarPalette.C -> colors.neutral100
        AvatarPalette.D -> colors.accent300
    }
    val ring = if (palette == AvatarPalette.D) Modifier.u2InnerStroke(colors.accent700, 1.dp, CircleShape) else Modifier
    Box(
        Modifier
            .size(size.diameter)
            .background(fill, CircleShape)
            .then(ring),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(avatarInitial(username), size.letterStyle, color = ink, maxLines = 1, softWrap = false)
    }
}

/** The capital first letter: usernames are ASCII lower-case, the design shows the capital. */
internal fun avatarInitial(username: String): String = username.take(1).uppercase(Locale.ROOT)

/** The green presence dot with its `bg` cut-out ring (C12). Inline status lines use a 7 dp dot without a ring. */
@Composable
fun PresenceDot(modifier: Modifier = Modifier, diameter: Dp = 15.dp, ring: Dp = 3.dp) {
    val colors = U2Theme.colors
    Box(
        modifier
            .size(diameter)
            .u2OuterRing(colors.bg, ring, CircleShape)
            .background(colors.online, CircleShape),
    )
}

// MARK: - Photos

/**
 * A profile photo in C12's circle: decoded off the main thread, in memory, once per app session
 * ([AvatarImageCache]); [placeholder] (the initial) shows until it is ready and stays when it never
 * decodes. The photo fades in over it (0.24 s; reduce motion: the short cross-fade).
 */
@Composable
fun AvatarPhotoFace(
    photo: AvatarPhotoData,
    diameter: Dp,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    AvatarImageCache.watch(LocalContext.current)
    var decoded by remember(photo.cacheKey) { mutableStateOf(AvatarImageCache.cached(photo)) }
    LaunchedEffect(photo.cacheKey) {
        if (decoded == null) decoded = AvatarImageCache.image(photo)
    }
    val image = decoded
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        placeholder()
        val fade = U2Transitions.fade(U2Motion.micro)
        AnimatedVisibility(visible = image != null, enter = fade.enter, exit = fade.exit) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .size(diameter)
                        .then(u2TransitionBlur(fade))
                        .clip(CircleShape)
                        // A hairline keeps light and dark photos from melting into `bg`.
                        .u2InnerStroke(U2Theme.colors.text.copy(alpha = 0.08f), 0.5.dp, CircleShape),
                )
            }
        }
    }
}

/**
 * Decoded profile photos, in memory only and only while the app is in the foreground: emptied when
 * its UI is hidden (the app locks) and on memory pressure. Keyed by the bytes' digest, so the same
 * photo in the chat list, the header and the map decodes once. Photos that do not decode keep
 * drawing the initial and are not tried again.
 */
object AvatarImageCache {
    private const val CAPACITY = 96

    private val images = object : LinkedHashMap<String, ImageBitmap>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > CAPACITY
    }
    private val refused = HashSet<String>()

    @Volatile
    private var watching = false

    /** The decoded photo, when it is already here. */
    fun cached(photo: AvatarPhotoData): ImageBitmap? = synchronized(this) { images[photo.cacheKey] }

    /**
     * The decoded photo; `null` when it is not a profile photo this app draws (§8.9: JPEG or HEIC,
     * at most 256 × 256 by the decoder's own reading of the header).
     */
    suspend fun image(photo: AvatarPhotoData): ImageBitmap? {
        val key = photo.cacheKey
        synchronized(this) {
            images[key]?.let { return it }
            if (key in refused) return null
        }
        val bitmap = withContext(Dispatchers.Default) {
            AvatarPhotoDecoder.decode(photo.data.toByteArray(), photo.contentType)
        }?.asImageBitmap()
        synchronized(this) {
            if (bitmap != null) images[key] = bitmap else refused += key
        }
        return bitmap
    }

    /** Forgets every decoded photo (the app left the foreground, or memory is short). */
    fun removeAll() {
        synchronized(this) {
            images.clear()
            refused.clear()
        }
    }

    /** Empties the cache whenever the app's UI is hidden or memory runs low (registered once). */
    internal fun watch(context: Context) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        context.applicationContext.registerComponentCallbacks(
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) removeAll()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() = removeAll()
            },
        )
    }
}

/**
 * The receiver's side of §8.9 before anything is drawn: the type and the file's signature, the byte
 * limit, then the decoder's own reading of the size (at most 256 a side), then the decode, in memory.
 */
internal object AvatarPhotoDecoder {
    /** The serialized `ProfileAvatar` limit; the photo's bytes alone are below it. */
    const val MAX_BYTES = 49_110
    const val MAX_PIXELS = 256

    fun decode(data: ByteArray, contentType: String): Bitmap? {
        if (data.isEmpty() || data.size > MAX_BYTES || !hasSignature(data, contentType)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..MAX_PIXELS || bounds.outHeight !in 1..MAX_PIXELS) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    /** JPEG starts with an SOI marker; HEIC with an `ftyp` box naming a `heic`/`heix` brand. */
    internal fun hasSignature(data: ByteArray, contentType: String): Boolean = when (contentType) {
        "image/jpeg" -> data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        "image/heic" -> {
            val text = String(data, 0, minOf(data.size, 64), Charsets.ISO_8859_1)
            data.size >= 12 && text.substring(4, 8) == "ftyp" && ("heic" in text || "heix" in text)
        }
        else -> false
    }
}
