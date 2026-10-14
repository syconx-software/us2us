package com.syconx.us2us.ui.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.CatalogSticker
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerResolution
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.media.MediaProgressRing
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

// Review pages for the drawn brand art (debug `U2Gallery stickers` / `avatars`): developer tools,
// so their labels are the verbatim ids and keys, as on iOS.

/** Test tags of the galleries. */
object BrandGalleryTags {
    const val STICKERS = "gallery.stickers"
    const val AVATARS = "gallery.avatars"
}

/**
 * Every built-in sticker in both languages and both themes, for reviewing the art: size chips (72 =
 * a picker cell … 160 = the largest in the chat), a replay of the entrance, the picker in both
 * presentations and the fallbacks of a received sticker.
 */
@Composable
fun BrandStickerGallery(modifier: Modifier = Modifier) {
    val sizes = listOf(72.dp, 96.dp, 132.dp, 160.dp)
    var side by remember { mutableStateOf(132.dp) }
    var replay by remember { mutableIntStateOf(0) }
    var showsSheet by remember { mutableStateOf(false) }
    var lastSent by remember { mutableStateOf<BrandStickerSelection?>(null) }
    U2Screen(modifier.testTag(BrandGalleryTags.STICKERS)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 70.dp, end = 16.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text("Stickers · ${StickerCatalog.all.size}", U2TextStyles.displayTitle)
                SegmentedChips(options = sizes, selection = side, onSelect = { side = it }) { "${it.value.toInt()} pt" }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    U2SecondaryButton(title = "Replay entrance", onClick = { replay += 1 }, modifier = Modifier.weight(1f))
                    U2SecondaryButton(title = "Picker sheet", onClick = { showsSheet = true }, modifier = Modifier.weight(1f))
                }
                lastSent?.let { sent ->
                    U2Text(
                        "Sent: ${sent.sticker.packId} / ${sent.sticker.id} / lang “${sent.wireLanguage}”",
                        U2TextStyles.monoMeta,
                        color = U2Theme.colors.textSecondary,
                    )
                }
            }
            for (pack in StickerCatalog.packs) {
                val stickers = StickerCatalog.stickers(pack.id)
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    U2Text("${pack.id} · ${stickers.size}", U2TextStyles.monoKicker, color = U2Theme.colors.accent300)
                    for (sticker in stickers) StickerRow(sticker, side, replay)
                }
            }
            Fallbacks()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text("Picker · keyboard panel", U2TextStyles.monoKicker, color = U2Theme.colors.accent300)
                BrandStickerPicker(
                    recents = listOf(1, 103, 302).mapNotNull { id -> StickerCatalog.sticker(id)?.let { BrandStickerSelection(it, StickerLanguage.English) } },
                    onSend = { lastSent = it },
                    presentation = StickerPickerPresentation.Panel(336.dp),
                    modifier = Modifier.bleed(16.dp),
                )
            }
        }
    }
    U2Sheet(isPresented = showsSheet, onDismissRequest = { showsSheet = false }, style = U2SheetStyle.Content) {
        StickerPickerSheet(
            onPick = { pick ->
                lastSent = StickerCatalog.selection(pick.stickerId, pick.lang)
                showsSheet = false
            },
            onDismiss = { showsSheet = false },
            recents = listOfNotNull(lastSent),
        )
    }
}

/** id · key · emoji, both labels, then dark (TR, EN) over light (TR, EN). */
@Composable
private fun StickerRow(sticker: CatalogSticker, side: Dp, replay: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        U2Text("${sticker.id} · ${sticker.key} · ${sticker.emoji}", U2TextStyles.monoUsername)
        U2Text(
            "TR “${sticker.label.turkish}” · EN “${sticker.label.english}”",
            U2TextStyles.captionTight,
            color = U2Theme.colors.textSecondary,
        )
        for (dark in listOf(true, false)) {
            U2Theme(dark = dark) {
                Row(
                    Modifier
                        .background(U2Theme.colors.bg, RoundedCornerShape(U2Radius.card))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (language in StickerLanguage.entries) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            key(replay) {
                                BrandStickerView(BrandStickerSelection(sticker, language), Modifier.size(side), animatesEntrance = true)
                            }
                            U2Text(
                                "${language.wireCode} · ${if (dark) "dark" else "light"}",
                                U2TextStyles.monoTag,
                                color = U2Theme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a received sticker falls back to (PROTOCOL.md › Stickers). */
@Composable
private fun Fallbacks() {
    val cases = listOf(
        "words.ok, no lang (older sender)" to StickerCatalog.resolve("us2us.words", 109, "👌", "", StickerLanguage.English),
        "words.hello, lang “de”" to StickerCatalog.resolve("us2us.words", 101, "👋", "de", StickerLanguage.Turkish),
        "unknown id 999" to StickerCatalog.resolve("us2us.cups", 999, "🫶", "tr", StickerLanguage.Turkish),
        "legacy us2us.text" to StickerCatalog.resolve("us2us.text", 0, "tamam!", "", StickerLanguage.Turkish),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        U2Text("Received fallbacks", U2TextStyles.monoKicker, color = U2Theme.colors.accent300)
        for ((title, resolution) in cases) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    when (resolution) {
                        is StickerResolution.Sticker -> BrandStickerView(resolution.selection, Modifier.size(96.dp))
                        is StickerResolution.Emoji -> StickerEmojiFallback(resolution.emoji)
                        is StickerResolution.LegacyText -> LegacyTextSticker(resolution.text)
                        StickerResolution.Unavailable -> U2Text("—", U2TextStyles.captionTight)
                    }
                }
                U2Text(title, U2TextStyles.captionTight, color = U2Theme.colors.textSecondary)
            }
        }
    }
}

/**
 * Every preset avatar at 28, 44 and 96 dp in both themes, next to the initial avatar at C12's
 * sizes; `Avatar` drawing a preset, a photo and the fallbacks, with a search result's loading ring;
 * how a stored choice is read back; and the full-screen picker.
 */
@Composable
fun BrandAvatarGallery(modifier: Modifier = Modifier) {
    var showsPicker by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<AvatarChoice>(AvatarChoice.Initials) }
    var lastAction by remember { mutableStateOf("") }
    U2Screen(modifier.testTag(BrandGalleryTags.AVATARS)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 70.dp, end = 16.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                U2Text("Avatars · ${BrandAvatarCatalog.all.size}", U2TextStyles.displayTitle)
                U2SecondaryButton(title = "Picker", onClick = { showsPicker = true })
                U2Text(
                    "Choice: “${choice.storageValue}”${if (lastAction.isEmpty()) "" else " · last: $lastAction"}",
                    U2TextStyles.monoMeta,
                    color = U2Theme.colors.textSecondary,
                )
            }
            for (dark in listOf(true, false)) AvatarGrid(dark)
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Kicker("Presets · 28 / 44 / 96 pt")
                for (preset in BrandAvatarCatalog.all) PresetRow(preset)
            }
            AvatarSizes()
            InUse()
            StoredChoices()
        }
    }
    U2FadeCover(isPresented = showsPicker, onEscape = { showsPicker = false }) {
        AvatarPresetPicker(
            username = "selin_n",
            current = choice,
            onChoose = { choice = it },
            onUploadPhoto = { lastAction = "upload a photo" },
            onTakePhoto = { lastAction = "camera" },
            onClose = { showsPicker = false },
        )
    }
}

/** Reaches [amount] past the parent's padding on both sides (iOS `.padding(.horizontal, -16)`). */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun Kicker(title: String) {
    U2Text(title, U2TextStyles.monoKicker, color = U2Theme.colors.accent300)
}

/** The whole set at the chat list's size, side by side. */
@Composable
private fun AvatarGrid(dark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker("All · 52 pt · ${if (dark) "dark" else "light"}")
        U2Theme(dark = dark) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(U2Theme.colors.bg, RoundedCornerShape(U2Radius.card))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in BrandAvatarCatalog.all.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in row) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                BrandAvatarView(preset, AvatarSize.ListRow.diameter)
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** id · key · backdrop, both labels, then dark over light at 28 / 44 / 96. */
@Composable
private fun PresetRow(preset: BrandAvatarPreset) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        U2Text("${preset.id} · ${preset.key} · ${preset.backdrop.name.replaceFirstChar { it.lowercase() }}", U2TextStyles.monoUsername)
        U2Text(
            "TR “${preset.label.turkish}” · EN “${preset.label.english}”",
            U2TextStyles.captionTight,
            color = U2Theme.colors.textSecondary,
        )
        for (dark in listOf(true, false)) {
            U2Theme(dark = dark) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(U2Theme.colors.bg, RoundedCornerShape(U2Radius.card))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    for (side in listOf(28.dp, 44.dp, 96.dp)) BrandAvatarView(preset, side)
                    Spacer(Modifier.weight(1f))
                    U2Text(if (dark) "dark" else "light", U2TextStyles.monoTag, color = U2Theme.colors.textSecondary)
                }
            }
        }
    }
}

private val GallerySizes = listOf(
    AvatarSize.Header, AvatarSize.ChatsSelf, AvatarSize.Request, AvatarSize.Verify, AvatarSize.Settings, AvatarSize.ListRow, AvatarSize.Profile,
)

/** A preset and the initial at every C12 size. */
@Composable
private fun AvatarSizes() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Kicker("C12 sizes · 38 … 64")
        for (preset in listOf(BrandAvatarCatalog.all[1], BrandAvatarCatalog.all[7])) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                for (size in GallerySizes) BrandAvatarView(preset, size.diameter)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for (size in GallerySizes) Avatar(username = "selin_n", palette = AvatarPalette.D, size = size)
        }
    }
}

/**
 * `Avatar` with a picture, as the screens draw it: a preset, the fixtures' photo, an unknown preset
 * and a photo that does not decode (both the initial), and 4.2's loading ring.
 */
@Composable
private fun InUse() {
    val pictures = listOf(
        "preset 2" to AvatarPicture.preset(2),
        "photo" to DesignAvatars.photoAvatar,
        "preset 999" to AvatarPicture.preset(999),
        "bad photo" to AvatarPicture.photo(AvatarPhotoData(Bytes.wrap(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)), "image/jpeg")),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Kicker("Avatar · picture: · 38 … 64")
        for ((name, picture) in pictures) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Avatar(username = "selin_n", palette = AvatarPalette.D, size = AvatarSize.Header, picture = picture)
                Avatar(username = "selin_n", palette = AvatarPalette.D, size = AvatarSize.ListRow, showsOnlineDot = true, picture = picture)
                Avatar(username = "selin_n", palette = AvatarPalette.D, size = AvatarSize.Profile, picture = picture)
                U2Text(name, U2TextStyles.captionTight, color = U2Theme.colors.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(username = "lale", palette = AvatarPalette.forUsername("lale"), size = AvatarSize.ListRow)
                MediaProgressRing(
                    fraction = null,
                    modifier = Modifier.size(AvatarSize.ListRow.diameter + 6.dp),
                    tint = U2Theme.colors.accent,
                    track = U2Theme.colors.accent.copy(alpha = 0.18f),
                )
            }
            U2Text("4.2 · loading", U2TextStyles.captionTight, color = U2Theme.colors.textSecondary)
        }
    }
}

/** How stored values read back (a newer build's preset falls back to the initial). */
@Composable
private fun StoredChoices() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker("Stored choices")
        for (stored in listOf("initials", "preset:2", "preset:999", "photo", "preset:abc", "")) {
            val choice = AvatarChoice.fromStorageValue(stored)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    val drawn = choice.drawn
                    val preset = (drawn as? AvatarChoice.Preset)?.let { BrandAvatarCatalog.preset(it.id) }
                    if (preset != null) {
                        BrandAvatarView(preset, AvatarSize.Request.diameter)
                    } else {
                        Avatar(username = "selin_n", palette = AvatarPalette.D, size = AvatarSize.Request)
                    }
                }
                U2Text(
                    "“$stored” → ${choice.storageValue} → draws ${choice.drawn.storageValue}",
                    U2TextStyles.captionTight,
                    color = U2Theme.colors.textSecondary,
                )
            }
        }
    }
}
