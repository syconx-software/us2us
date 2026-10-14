package com.syconx.us2us.ui.brand

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.U2Theme

/**
 * A preset profile picture (catalogue version 1, ids 1–22), drawn on the phone at [size]. An id this
 * build does not know draws nothing: the caller (`Avatar`) draws the initial for it.
 */
@Composable
fun BrandAvatar(presetId: Int, size: Dp, modifier: Modifier = Modifier) {
    val preset = BrandAvatarCatalog.preset(presetId)
    if (preset == null) {
        Box(modifier.size(size))
        return
    }
    BrandAvatarView(preset, size, modifier)
}

/**
 * A preset profile avatar (proposed: C12 draws only the initial): the stickers' cup mascot on a
 * disc, in the same circle as `Avatar` and at any of its sizes (28 dp and up). Drawn in code like a
 * sticker, so it is crisp at every size; under 40 dp the little extras around the cup are left out
 * ([BrandAvatarDetail]) and the cup, its face and what it wears stay.
 *
 * The disc follows the theme (deep in the dark one, pale in the light one, like the initial
 * avatars' palettes); the cup is printed in the sticker inks, the same in both.
 *
 * TalkBack reads the preset's name in the app's language (“Kulaklıklı bardak”), what the picker
 * needs. Where the avatar stands for a person (`Avatar`), the person's name replaces it.
 */
@Composable
fun BrandAvatarView(preset: BrandAvatarPreset, size: Dp, modifier: Modifier = Modifier) {
    val label = preset.label[StickerLanguage.forLocale(currentAppLocale())]
    BrandAvatarCanvas(
        preset,
        modifier
            .size(size)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Image
            },
    )
}

/** Draws a preset avatar: the disc, then the cup on it, clipped to the circle. No accessibility of its own. */
@Composable
internal fun BrandAvatarCanvas(preset: BrandAvatarPreset, modifier: Modifier = Modifier) {
    val dark = U2Theme.isDark
    Spacer(
        modifier
            .aspectRatio(1f)
            .drawBehind { renderBrandAvatar(preset, dark, minOf(size.width, size.height) / density) },
    )
}
