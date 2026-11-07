package com.syconx.us2us.ui.common

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.ChatBackground
import com.syconx.us2us.domain.ChatBackgroundPreset
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.MessageBubble
import com.syconx.us2us.ui.components.MonoKicker
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2ThemedColor
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.tokens.U2TextStyles
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chat background ready to draw: a photo is decoded before it reaches the screen, off the main thread. */
@Immutable
sealed interface ChatBackgroundLook {
    data object Standard : ChatBackgroundLook
    data class Preset(val preset: ChatBackgroundPreset) : ChatBackgroundLook
    data class Photo(val image: ImageBitmap) : ChatBackgroundLook
}

/** The chat draws its own background: nothing may paint the theme's over it (the composer's fade). */
val LocalChatHasBackground = staticCompositionLocalOf { false }

/** The look of [background]. Until a photo is decoded, and if it no longer decodes, the app's own background. */
@Composable
fun rememberChatBackgroundLook(background: ChatBackground): ChatBackgroundLook {
    val look by produceState<ChatBackgroundLook>(
        initialValue = (background as? ChatBackground.Preset)?.let { ChatBackgroundLook.Preset(it.preset) } ?: ChatBackgroundLook.Standard,
        background,
    ) {
        value = when (background) {
            ChatBackground.Standard -> ChatBackgroundLook.Standard
            is ChatBackground.Preset -> ChatBackgroundLook.Preset(background.preset)
            is ChatBackground.Photo -> withContext(Dispatchers.Default) { background.decoded() }
        }
    }
    return look
}

private fun ChatBackground.Photo.decoded(): ChatBackgroundLook =
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { ChatBackgroundLook.Photo(it.asImageBitmap()) }
        ?: ChatBackgroundLook.Standard

/**
 * Top and bottom of a preset's gradient, soft enough in both themes for the bubbles and the
 * floating labels to keep their contrast. The same colours as iOS.
 */
internal val ChatBackgroundPreset.stops: Pair<U2ThemedColor, U2ThemedColor>
    get() = when (this) {
        ChatBackgroundPreset.Mist -> U2ThemedColor(dark = 0x27303a, light = 0xe4e9ef) to U2ThemedColor(dark = 0x171c22, light = 0xcfd8e2)
        ChatBackgroundPreset.Sage -> U2ThemedColor(dark = 0x24372b, light = 0xdcebdc) to U2ThemedColor(dark = 0x121d16, light = 0xc2d8c2)
        ChatBackgroundPreset.Sand -> U2ThemedColor(dark = 0x3a3123, light = 0xf1e6d3) to U2ThemedColor(dark = 0x1e1913, light = 0xe2d0b2)
        ChatBackgroundPreset.Rose -> U2ThemedColor(dark = 0x40262d, light = 0xf6dfe3) to U2ThemedColor(dark = 0x211519, light = 0xecc4cc)
        ChatBackgroundPreset.Dusk -> U2ThemedColor(dark = 0x32294a, light = 0xe5dcf4) to U2ThemedColor(dark = 0x1a1428, light = 0xcfc0e9)
        ChatBackgroundPreset.Ocean -> U2ThemedColor(dark = 0x18394a, light = 0xd4e8f2) to U2ThemedColor(dark = 0x0e1d25, light = 0xb5d6e8)
        ChatBackgroundPreset.Moss -> U2ThemedColor(dark = 0x323a22, light = 0xe2e9cf) to U2ThemedColor(dark = 0x1a1f11, light = 0xcbd7a9)
        ChatBackgroundPreset.Peach -> U2ThemedColor(dark = 0x452e1e, light = 0xf9e2d1) to U2ThemedColor(dark = 0x22170f, light = 0xf2cbae)
    }

/** How much of the theme's background covers a photo, so what floats directly on it stays readable. */
private const val PHOTO_VEIL = 0.35f

/** Fills its place with the look; a photo is cropped to fill. */
@Composable
fun ChatBackgroundLayer(look: ChatBackgroundLook, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    when (look) {
        ChatBackgroundLook.Standard -> Box(modifier.background(colors.bg))
        is ChatBackgroundLook.Preset -> {
            val dark = U2Theme.isDark
            val (top, bottom) = look.preset.stops
            Box(modifier.background(Brush.verticalGradient(listOf(top.resolve(dark), bottom.resolve(dark)))))
        }
        is ChatBackgroundLook.Photo -> Box(modifier) {
            Image(look.image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = PHOTO_VEIL)))
        }
    }
}

/** A picked photo as a chat background keeps it: upright, at most [MAX_SIDE], and a JPEG with no properties. */
object ChatBackgroundPhoto {
    const val MAX_SIDE = 1_600

    /** The storage's limit (`ChatBackgroundChoice.MAX_PHOTO_BYTES`). */
    const val MAX_BYTES = 2_500_000

    /** The most of a picked file that is read: larger files are refused, not decoded. */
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024

    /** `null` when the bytes are not an image or stay over the limit. */
    fun prepare(data: ByteArray): ByteArray? {
        val photo = decode(data) ?: return null
        try {
            for (quality in listOf(80, 60, 40)) {
                val output = ByteArrayOutputStream()
                if (photo.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_BYTES) {
                    return output.toByteArray()
                }
            }
            return null
        } finally {
            photo.recycle()
        }
    }

    /** The picked file's bytes, or `null` when it cannot be read or is too large. */
    fun read(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_SOURCE_BYTES) return@use null
            }
            output.toByteArray()
        }
    }.getOrNull()

    /** The system decoder applies the orientation and never decodes more than [MAX_SIDE]. */
    private fun decode(data: ByteArray): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val factor = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * factor).roundToInt(), (info.size.height * factor).roundToInt())
            }
        }
    }.getOrNull() ?: decodeWithFactory(data)

    private fun decodeWithFactory(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val photo = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = max(photo.width, photo.height)
        if (side <= MAX_SIDE) return photo
        val factor = MAX_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(photo, (photo.width * factor).roundToInt(), (photo.height * factor).roundToInt(), true)
        if (scaled !== photo) photo.recycle()
        return scaled
    }
}

object ChatBackgroundTags {
    const val PREVIEW = "background.preview"
    const val PHOTO = "background.photo"
    const val SAVE = "background.save"
    const val NOTE = "background.note"
    const val STANDARD = "background.swatch.standard"
    fun swatch(preset: ChatBackgroundPreset) = "background.swatch.${preset.id}"
}

private enum class ChatBackgroundProblem { UnusablePhoto, NotSaved }

/**
 * “Sohbet arka planı” (proposed, in 5.5's language; iOS `ChatBackgroundSheet`): this chat's own
 * background. A choice is only previewed until “Kaydet”; closing the sheet leaves the chat as it was.
 */
@Composable
fun ChatBackgroundSheet(isPresented: Boolean, chat: ChatId, messenger: MessengerService, onDismiss: () -> Unit) {
    U2Sheet(isPresented = isPresented, onDismissRequest = onDismiss, style = U2SheetStyle.Content) {
        ChatBackgroundContent(chat, messenger, onDismiss)
    }
}

@Composable
internal fun ChatBackgroundContent(chat: ChatId, messenger: MessengerService, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(chat) { mutableStateOf<ChatBackground>(ChatBackground.Standard) }
    var selection by remember(chat) { mutableStateOf<ChatBackground>(ChatBackground.Standard) }
    var isBusy by remember(chat) { mutableStateOf(false) }
    var problem by remember(chat) { mutableStateOf<ChatBackgroundProblem?>(null) }
    LaunchedEffect(chat) {
        saved = messenger.chatBackground(chat)
        selection = saved
    }
    val photos = rememberGuardedLauncher(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberGuardedLauncher
        scope.launch {
            problem = null
            isBusy = true
            try {
                val prepared = withContext(Dispatchers.IO) {
                    ChatBackgroundPhoto.read(context.contentResolver, uri)?.let(ChatBackgroundPhoto::prepare)
                }
                if (prepared == null) problem = ChatBackgroundProblem.UnusablePhoto else selection = ChatBackground.Photo(prepared)
            } finally {
                isBusy = false
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 20.dp, end = 18.dp).u2Bottom(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MonoKicker(stringResource(R.string.contact_background_kicker), glyph = PhosphorGlyph.Image)
        ChatBackgroundPreview(rememberChatBackgroundLook(selection))
        ChatBackgroundSwatches(selection, enabled = !isBusy) {
            selection = it
            problem = null
        }
        U2SecondaryButton(
            title = stringResource(R.string.contact_background_photo),
            onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth().testTag(ChatBackgroundTags.PHOTO),
            glyph = PhosphorGlyph.Image,
            enabled = !isBusy,
        )
        val (note, noteColor) = when (problem) {
            ChatBackgroundProblem.UnusablePhoto -> stringResource(R.string.contact_background_problem_photo) to U2Theme.colors.danger
            ChatBackgroundProblem.NotSaved -> stringResource(R.string.contact_background_problem_save) to U2Theme.colors.danger
            null -> stringResource(R.string.contact_background_note) to U2Theme.colors.textSecondary
        }
        U2Text(note, U2TextStyles.captionCompact, Modifier.testTag(ChatBackgroundTags.NOTE), color = noteColor, textAlign = TextAlign.Center)
        U2PrimaryButton(
            title = stringResource(R.string.common_save),
            onClick = {
                if (selection == saved) {
                    onClose()
                } else {
                    scope.launch {
                        problem = null
                        isBusy = true
                        val stored = try {
                            messenger.setChatBackground(selection, chat)
                        } finally {
                            isBusy = false
                        }
                        if (stored) onClose() else problem = ChatBackgroundProblem.NotSaved
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(ChatBackgroundTags.SAVE),
            enabled = !isBusy,
            isWorking = isBusy,
        )
    }
}

@Composable
private fun ChatBackgroundPreview(look: ChatBackgroundLook) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .clip(shape)
            .border(1.dp, U2Theme.colors.divider, shape)
            .testTag(ChatBackgroundTags.PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        ChatBackgroundLayer(look, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MessageBubble(MessageDirection.Incoming, Modifier.align(Alignment.Start)) {
                U2Text(stringResource(R.string.contact_background_sample_incoming), U2TextStyles.bubble, color = U2Theme.colors.text)
            }
            MessageBubble(MessageDirection.Outgoing, Modifier.align(Alignment.End)) {
                U2Text(stringResource(R.string.contact_background_sample_outgoing), U2TextStyles.bubble, color = U2Theme.colors.onBubbleOutgoing)
            }
        }
    }
}

/** The app's own background first, then the presets; a picked photo has no swatch of its own (the preview shows it). */
@Composable
private fun ChatBackgroundSwatches(selection: ChatBackground, enabled: Boolean, onSelect: (ChatBackground) -> Unit) {
    val items = listOf<ChatBackground>(ChatBackground.Standard) + ChatBackgroundPreset.entries.map { ChatBackground.Preset(it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    ChatBackgroundSwatch(item, isSelected = item == selection, enabled = enabled, onClick = { onSelect(item) }, Modifier.weight(1f))
                }
                repeat(SWATCHES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val SWATCHES_PER_ROW = 5

@Composable
private fun ChatBackgroundSwatch(background: ChatBackground, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(14.dp)
    val preset = (background as? ChatBackground.Preset)?.preset
    val name = stringResource(preset?.nameResource ?: R.string.contact_background_standard)
    ChatBackgroundLayer(
        preset?.let { ChatBackgroundLook.Preset(it) } ?: ChatBackgroundLook.Standard,
        modifier
            .height(52.dp)
            .clip(shape)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accent else colors.divider, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = name
                selected = isSelected
            }
            .testTag(preset?.let(ChatBackgroundTags::swatch) ?: ChatBackgroundTags.STANDARD),
    )
}

private val ChatBackgroundPreset.nameResource: Int
    get() = when (this) {
        ChatBackgroundPreset.Mist -> R.string.contact_background_preset_mist
        ChatBackgroundPreset.Sage -> R.string.contact_background_preset_sage
        ChatBackgroundPreset.Sand -> R.string.contact_background_preset_sand
        ChatBackgroundPreset.Rose -> R.string.contact_background_preset_rose
        ChatBackgroundPreset.Dusk -> R.string.contact_background_preset_dusk
        ChatBackgroundPreset.Ocean -> R.string.contact_background_preset_ocean
        ChatBackgroundPreset.Moss -> R.string.contact_background_preset_moss
        ChatBackgroundPreset.Peach -> R.string.contact_background_preset_peach
    }
