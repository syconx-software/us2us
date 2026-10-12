package com.syconx.us2us.ui.brand

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarChoice
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** Test tags of “Profil görseli” (the iOS accessibility identifiers). */
object AvatarPickerTags {
    const val PICKER = "avatarPicker"
    const val CLOSE = "avatarPicker.close"
    const val INITIALS = "avatarPicker.initials"
    const val UPLOAD = "avatarPicker.upload"
    const val CAMERA = "avatarPicker.camera"
    const val ALERT = "avatarPicker.alert"
    const val FOOTNOTE = "avatarPicker.footnote"
    const val GRID = "avatarPicker.grid"

    fun preset(id: Int) = "avatarPicker.preset.$id"
}

/**
 * “Profil görseli” (proposed: the design has no avatar picker): opened from the 7.1 profile card as
 * a full-screen page on `bg`, in the app's fade cover (never a half-height sheet). A C7 header: the
 * close button (44, `ph:x`) at the left, the title centred.
 *
 * Then, scrolling, a C13 group (rows ≥ 56, 38 dp leading, gap 13): “Baş harfim kalsın” with the
 * user's own initial avatar (C12), then “Fotoğraf yükle” and “Kamera” (`ph:image`, `ph:camera`
 * tiles). Those two only call back ([onUploadPhoto], [onTakePhoto]): the caller opens the photo
 * picker or the camera and keeps the bytes. Then “Hazır avatarlar” and the presets' grid: 5
 * columns, 4 at the largest font sizes.
 *
 * The current choice is marked: a check on its row, or an `accent` ring and check badge round its
 * preset. A tap on the initial or a preset chooses it at once ([onChoose], selection haptic) and
 * the mark moves; the caller saves it and may close the page. TalkBack: every option is a button,
 * the chosen one is selected; presets read their names.
 *
 * Under the group, an optional [footnote] (with “Aramada göster” on: who can see the picture,
 * PROTOCOL §8.10) and, in `danger`, an [alert] when a choice could not be saved.
 */
@Composable
fun AvatarPresetPicker(
    username: String,
    current: AvatarChoice,
    onChoose: (AvatarChoice) -> Unit,
    onUploadPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier,
    /** The initial avatar's palette (D for the user's own, C12). */
    palette: AvatarPalette = AvatarPalette.D,
    onClose: (() -> Unit)? = null,
    footnote: String? = null,
    alert: String? = null,
    /** Off while a choice is being saved or the page is closing: taps do nothing. */
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    var selection by remember { mutableStateOf(current.drawn) }
    LaunchedEffect(current) { selection = current.drawn }

    fun choose(choice: AvatarChoice) {
        if (!enabled) return
        if (choice != selection) haptics.selection()
        selection = choice
        onChoose(choice)
    }

    U2Screen(modifier) {
        Column(Modifier.fillMaxSize().testTag(AvatarPickerTags.PICKER)) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_avatar_picker_title),
                leading = {
                    if (onClose != null) {
                        U2GlassIconButton(
                            glyph = PhosphorGlyph.X,
                            contentDescription = stringResource(R.string.common_a11y_close),
                            onClick = onClose,
                            enabled = enabled,
                            modifier = Modifier.testTag(AvatarPickerTags.CLOSE),
                        )
                    } else {
                        HeaderSpacer()
                    }
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                    .u2Bottom(U2Spacing.ctaBottom),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                    Sources(username, palette, selection, enabled, ::choose, onUploadPhoto, onTakePhoto)
                    Notes(footnote, alert)
                }
                Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                    SectionLabel(stringResource(R.string.settings_avatar_picker_presets))
                    PresetGrid(selection, enabled) { choose(it) }
                }
            }
        }
    }
}

private val LeadingSide = 38.dp

/** The initial, the photo and the camera. */
@Composable
private fun Sources(
    username: String,
    palette: AvatarPalette,
    selection: AvatarChoice,
    enabled: Boolean,
    choose: (AvatarChoice) -> Unit,
    onUploadPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val separatorInset = 14.dp + LeadingSide + U2Spacing.rowGap
    GroupedCard {
        SourceRow(
            title = stringResource(R.string.settings_avatar_picker_initials),
            isSelected = selection == AvatarChoice.Initials,
            showsChevron = false,
            enabled = enabled,
            tag = AvatarPickerTags.INITIALS,
            leading = { Avatar(username = username, palette = palette, size = AvatarSize.Header) },
            onClick = { choose(AvatarChoice.Initials) },
        )
        GroupedSeparator(inset = separatorInset)
        SourceRow(
            title = stringResource(R.string.settings_avatar_picker_upload),
            isSelected = selection == AvatarChoice.Photo,
            showsChevron = true,
            enabled = enabled,
            tag = AvatarPickerTags.UPLOAD,
            leading = { SourceTile(PhosphorGlyph.Image) },
            onClick = onUploadPhoto,
        )
        GroupedSeparator(inset = separatorInset)
        SourceRow(
            title = stringResource(R.string.settings_avatar_picker_camera),
            isSelected = false,
            showsChevron = true,
            enabled = enabled,
            tag = AvatarPickerTags.CAMERA,
            leading = { SourceTile(PhosphorGlyph.Camera) },
            onClick = onTakePhoto,
        )
    }
}

@Composable
private fun SourceTile(glyph: PhosphorGlyph) {
    IconTile(IconTileContent.Glyph(glyph), size = LeadingSide, cornerRadius = U2Radius.iconTileXL, glyphSize = 20.dp)
}

/** A C13 row: leading 38 · `rowTitle` · the check when chosen · a chevron for the rows that open something. */
@Composable
private fun SourceRow(
    title: String,
    isSelected: Boolean,
    showsChevron: Boolean,
    enabled: Boolean,
    tag: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val check by animateFloatAsState(if (isSelected) 1f else 0f, u2Spec(U2Motion.micro), label = "sourceCheck")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag)
            .groupedRowPress(source)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                role = Role.Button
                selected = isSelected
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LeadingSide), contentAlignment = Alignment.Center) { leading() }
        U2Text(title, U2TextStyles.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
        if (check > 0f) PhosphorIcon(PhosphorGlyph.Check, size = 18.dp, tint = colors.accent, modifier = Modifier.alpha(check))
        if (showsChevron) PhosphorIcon(PhosphorGlyph.CaretRight, size = 15.dp, tint = colors.textTertiary)
    }
}

/** The footnote and the alert, as 7.1's footnotes (16 dp in, `footnote`). */
@Composable
private fun Notes(footnote: String?, alert: String?) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade(U2Motion.state)
    var shownAlert by remember { mutableStateOf(alert) }
    if (alert != null) shownAlert = alert
    AnimatedVisibility(visible = alert != null, enter = fade.enter, exit = fade.exit) {
        U2Text(
            shownAlert.orEmpty(),
            U2TextStyles.footnote,
            color = colors.danger,
            modifier = Modifier
                .then(u2TransitionBlur(fade))
                .padding(horizontal = 16.dp)
                .testTag(AvatarPickerTags.ALERT),
        )
    }
    if (footnote != null) {
        U2Text(
            footnote,
            U2TextStyles.footnote,
            color = colors.textSecondary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(AvatarPickerTags.FOOTNOTE),
        )
    }
}

/** The presets: 5 columns (4 at the largest font sizes), gap 10. */
@Composable
private fun PresetGrid(selection: AvatarChoice, enabled: Boolean, choose: (AvatarChoice) -> Unit) {
    val columns = if (LocalDensity.current.fontScale >= 1.6f) 4 else 5
    Column(
        Modifier
            .padding(horizontal = 2.dp)
            .testTag(AvatarPickerTags.GRID),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in BrandAvatarCatalog.all.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (preset in row) {
                    val choice = AvatarChoice.Preset(preset.id)
                    PresetCell(preset, selection == choice, enabled, Modifier.weight(1f)) { choose(choice) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One preset: the avatar with room for the selection ring, which sits round it, and the check badge at its lower right. */
@Composable
private fun PresetCell(preset: BrandAvatarPreset, isSelected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val label = preset.label[StickerLanguage.forLocale(currentAppLocale())]
    val source = remember { MutableInteractionSource() }
    val ring by animateFloatAsState(if (isSelected) 1f else 0f, u2Spec(U2Motion.micro), label = "presetRing")
    val pop = U2Transitions.pop(U2Motion.micro)
    Box(
        modifier
            .testTag(AvatarPickerTags.preset(preset.id))
            .aspectRatio(1f)
            .u2PressEffect(source, scale = 0.92f)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                selected = isSelected
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            },
    ) {
        BrandAvatarCanvas(preset, Modifier.fillMaxSize().padding(6.dp))
        Box(
            Modifier
                .fillMaxSize()
                .alpha(ring)
                .border(2.5.dp, colors.accent, CircleShape),
        )
        AnimatedVisibility(
            visible = isSelected,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = pop.enter,
            exit = pop.exit,
        ) {
            Box(
                Modifier
                    .then(u2TransitionBlur(pop))
                    .size(20.dp)
                    .u2OuterRing(colors.bg, 2.5.dp, CircleShape)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PhosphorIcon(PhosphorGlyph.Check, size = 12.dp, tint = colors.accent900)
            }
        }
    }
}
