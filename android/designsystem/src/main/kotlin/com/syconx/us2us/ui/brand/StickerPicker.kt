package com.syconx.us2us.ui.brand

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerPack
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2PrimaryButtonSize
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** Test tags of the sticker picker. */
object StickerPickerTags {
    const val PICKER = "sticker.picker"
    const val CLOSE = "sticker.picker.close"
    const val LANGUAGE = "sticker.picker.language"
    const val PREVIEW = "sticker.picker.preview"
    const val PREVIEW_SEND = "sticker.picker.preview.send"

    fun tab(packId: String) = "sticker.picker.tab.$packId"

    fun languageOption(language: StickerLanguage) = "sticker.picker.language.${language.wireCode}"

    /** A sticker of the pack's grid (`BrandStickerSelection.id`: “101.tr”, “1.-”). */
    fun cell(selectionId: String) = "sticker.picker.cell.$selectionId"

    fun recent(selectionId: String) = "sticker.picker.recent.$selectionId"
}

/**
 * The sticker picker sheet content (5.1 › stickers), in a content sheet (`U2Sheet(style =
 * Content)`): the title, the TR/EN switch and close on top, the pack tabs, “Son kullanılanlar”
 * ([recents]) and the pack's grid. A tap sends ([onPick], with the language shown).
 */
@Composable
fun StickerPickerSheet(
    onPick: (StickerSelection) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    recents: List<BrandStickerSelection> = emptyList(),
) {
    BrandStickerPicker(
        recents = recents,
        onSend = { onPick(StickerSelection(it.sticker.id, it.language.wireCode)) },
        // The 468 dp picker reaches the sheet's bottom edge, under the home indicator, as on iOS
        // (its grid scrolls there); the content sheet adds no inset of its own.
        modifier = modifier,
        onClose = onDismiss,
    )
}

/** Where the picker is shown. */
@Immutable
sealed interface StickerPickerPresentation {
    /** Inside a content sheet: the header carries the title, the TR/EN switch and close. */
    data object Sheet : StickerPickerPresentation

    /** In place of the keyboard under the composer, [height] high: `surface`, r20 top corners, the TR/EN switch at the end of the pack tabs. */
    data class Panel(val height: Dp) : StickerPickerPresentation
}

/**
 * The sticker picker (proposed: the design has the composer's `ph:sticker` button, C21, but no
 * picker). From the top: pack tabs (C22 chips with each pack's cover sticker), “Son kullanılanlar”
 * ([recents], kept by the caller, each in the language it was sent in) and the pack's grid: 4
 * columns of stickers, 3 at the largest font sizes, 2 beyond.
 *
 * Text stickers are shown in the app's language; the TR/EN switch (only over a pack with words)
 * shows them in the other one. Tap sends ([onSend], with the language shown); touch and hold opens
 * a large preview with “Gönder” ([onPreview] is told). TalkBack: each sticker is a button with an
 * “Önizle” action; the switch toggles on a double tap.
 */
@Composable
fun BrandStickerPicker(
    recents: List<BrandStickerSelection>,
    onSend: (BrandStickerSelection) -> Unit,
    modifier: Modifier = Modifier,
    presentation: StickerPickerPresentation = StickerPickerPresentation.Sheet,
    onPreview: (BrandStickerSelection) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val uiLanguage = StickerLanguage.forLocale(currentAppLocale())
    var packId by remember { mutableStateOf(StickerCatalog.packs[0].id) }
    // The TR/EN switch; null follows the app's language.
    var chosenLanguage by remember { mutableStateOf<StickerLanguage?>(null) }
    var preview by remember { mutableStateOf<BrandStickerSelection?>(null) }
    val language = chosenLanguage ?: uiLanguage
    val pack = StickerCatalog.pack(packId) ?: StickerCatalog.packs[0]
    val stickers = StickerCatalog.stickers(pack.id)
    val packHasWords = stickers.any { it.isText }
    val isSheet = presentation == StickerPickerPresentation.Sheet
    val height = when (presentation) {
        StickerPickerPresentation.Sheet -> SHEET_HEIGHT
        is StickerPickerPresentation.Panel -> presentation.height
    }
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnPreview by rememberUpdatedState(onPreview)

    fun send(selection: BrandStickerSelection) {
        preview = null
        currentOnSend(selection)
    }

    fun showPreview(selection: BrandStickerSelection) {
        haptics.longPress()
        preview = selection
        currentOnPreview(selection)
    }

    fun choose(option: StickerLanguage) {
        if (option == language) return
        haptics.selection()
        chosenLanguage = if (option == uiLanguage) null else option
    }

    // Back closes the preview first (the sheet's own back closes the sheet).
    BackHandler(enabled = preview != null) { preview = null }

    val switch: @Composable (Modifier) -> Unit = { switchModifier ->
        LanguageSwitch(language, packHasWords, ::choose, switchModifier)
    }

    Box(modifier.fillMaxWidth().testTag(StickerPickerTags.PICKER)) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .then(if (isSheet) Modifier else Modifier.panelBackground()),
        ) {
            if (isSheet) PickerHeader(switch, onClose)
            PackTabs(
                selectedId = pack.id,
                language = language,
                onSelect = { id ->
                    if (id != packId) {
                        haptics.selection()
                        packId = id
                    }
                },
                trailing = if (isSheet) null else switch,
                modifier = Modifier.padding(top = if (isSheet) 0.dp else 12.dp, bottom = 4.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = U2Spacing.screenGutterS, top = 10.dp, end = U2Spacing.screenGutterS, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val unique = remember(recents) { recents.distinctBy { it.id } }
                if (unique.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.conversation_sticker_picker_recent), horizontalPadding = 0.dp)
                    val side = 60.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (selection in unique) {
                            StickerCell(selection, ::send, ::showPreview, Modifier.size(side).testTag(StickerPickerTags.recent(selection.id)))
                        }
                    }
                    SectionLabel(packTitle(pack), Modifier.padding(top = 6.dp), horizontalPadding = 0.dp)
                }
                StickerGrid(stickers.map { BrandStickerSelection(it, language) }, ::send, ::showPreview)
            }
        }
        val pop = U2Transitions.pop(U2Motion.state)
        AnimatedVisibility(visible = preview != null, modifier = Modifier.matchParentSize(), enter = pop.enter, exit = pop.exit) {
            var shown by remember { mutableStateOf(preview) }
            preview?.let { shown = it }
            shown?.let { selection ->
                PreviewOverlay(
                    selection = selection,
                    side = if (isSheet) 176.dp else 128.dp,
                    caption = previewCaption(selection, uiLanguage),
                    onSend = { send(selection) },
                    onDismiss = { preview = null },
                    modifier = Modifier.then(u2TransitionBlur(pop)),
                )
            }
        }
    }
}

/** The content height inside the sheet (the sheet adds its grabber and the home indicator). */
private val SHEET_HEIGHT = 468.dp

@Composable
private fun Modifier.panelBackground(): Modifier {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(topStart = U2Radius.cardL, topEnd = U2Radius.cardL)
    return this
        .background(colors.surface, shape)
        .border(0.5.dp, colors.strokeField, shape)
}

/** The sheet's header: the title, the TR/EN switch and close. */
@Composable
private fun PickerHeader(switch: @Composable (Modifier) -> Unit, onClose: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = U2Spacing.screenGutterS, top = 6.dp, end = U2Spacing.screenGutterS, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        U2Text(
            stringResource(R.string.conversation_sticker_picker_title),
            U2TextStyles.navTitle,
            color = U2Theme.colors.text,
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        switch(Modifier)
        if (onClose != null) {
            U2GlassIconButton(
                glyph = PhosphorGlyph.X,
                contentDescription = stringResource(R.string.common_a11y_close),
                onClick = onClose,
                diameter = U2Size.iconButtonSheet,
                glyphSize = 17.dp,
                modifier = Modifier.testTag(StickerPickerTags.CLOSE),
            )
        }
    }
}

/** The pack tabs, scrolling sideways; the panel puts the TR/EN switch after them. */
@Composable
private fun PackTabs(
    selectedId: String,
    language: StickerLanguage,
    onSelect: (String) -> Unit,
    trailing: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = U2Spacing.screenGutterS),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (pack in StickerCatalog.packs) PackTab(pack, pack.id == selectedId, language) { onSelect(pack.id) }
        }
        trailing?.invoke(Modifier.padding(end = U2Spacing.screenGutterS))
    }
}

/** C22's chip, with the pack's cover sticker. */
@Composable
private fun PackTab(pack: StickerPack, isSelected: Boolean, language: StickerLanguage, onClick: () -> Unit) {
    val colors = U2Theme.colors
    val shape = RoundedCornerShape(U2Radius.chip)
    val progress by animateFloatAsState(if (isSelected) 1f else 0f, u2Spec(U2Motion.micro), label = "packTab")
    val ink by animateColorAsState(if (isSelected) colors.accent300 else colors.text, u2Spec(U2Motion.micro), label = "packTabInk")
    val title = packTitle(pack)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .u2CardPress(onClick)
            .background(colors.accent900.copy(alpha = colors.accent900.alpha * progress), shape)
            .u2InnerStroke(colors.divider.copy(alpha = colors.divider.alpha * (1f - progress)), 1.dp, shape)
            .u2InnerStroke(colors.accent.copy(alpha = colors.accent.alpha * progress), 1.5.dp, shape)
            .padding(start = 6.dp, end = 12.dp)
            .testTag(StickerPickerTags.tab(pack.id))
            .semantics {
                selected = isSelected
                role = Role.Tab
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StickerCatalog.sticker(pack.coverId)?.let { cover -> BrandStickerCanvas(cover, language, Modifier.size(28.dp)) }
        U2Text(title, U2TextStyles.chip, color = ink, maxLines = 1)
    }
}

/** TR | EN: the language of the words on text stickers. */
@Composable
private fun LanguageSwitch(language: StickerLanguage, packHasWords: Boolean, choose: (StickerLanguage) -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val label = stringResource(R.string.conversation_sticker_picker_language)
    val value = stringResource(
        if (language == StickerLanguage.Turkish) R.string.conversation_sticker_picker_language_tr else R.string.conversation_sticker_picker_language_en,
    )
    val hint = stringResource(R.string.conversation_sticker_picker_language_hint)
    val visibility by animateFloatAsState(if (packHasWords) 1f else 0f, u2Spec(U2Motion.micro), label = "languageSwitch")
    Row(
        modifier
            .testTag(StickerPickerTags.LANGUAGE)
            .alpha(visibility)
            .background(colors.fillSubtle, CircleShape)
            .border(0.5.dp, colors.divider, CircleShape)
            .padding(2.dp)
            .then(
                if (packHasWords) {
                    Modifier.clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = value
                        onClick(hint) {
                            choose(language.other)
                            true
                        }
                    }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in StickerLanguage.entries) {
            val isSelected = option == language
            val fill by animateFloatAsState(if (isSelected) 1f else 0f, u2Spec(U2Motion.micro), label = "languageOption")
            Box(
                Modifier
                    .widthIn(min = 34.dp)
                    .heightIn(min = 28.dp)
                    .background(colors.accent900.copy(alpha = colors.accent900.alpha * fill), CircleShape)
                    .border(1.dp, colors.accent.copy(alpha = colors.accent.alpha * fill), CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = packHasWords) { choose(option) }
                    .testTag(StickerPickerTags.languageOption(option)),
                contentAlignment = Alignment.Center,
            ) {
                U2Text(
                    if (option == StickerLanguage.Turkish) "TR" else "EN",
                    U2TextStyles.monoMeta,
                    color = if (isSelected) colors.accent300 else colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** The pack's grid: 4 columns, 3 at the largest font sizes, 2 beyond; gap 6. */
@Composable
private fun StickerGrid(selections: List<BrandStickerSelection>, onSend: (BrandStickerSelection) -> Unit, onPreview: (BrandStickerSelection) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val columns = when {
        fontScale >= 2.3f -> 2
        fontScale >= 1.6f -> 3
        else -> 4
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in selections.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (selection in row) {
                    StickerCell(selection, onSend, onPreview, Modifier.weight(1f).testTag(StickerPickerTags.cell(selection.id)))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One sticker of the picker: tap sends, touch and hold previews (with the press scale of the design system, 0.92). */
@Composable
private fun StickerCell(
    selection: BrandStickerSelection,
    onSend: (BrandStickerSelection) -> Unit,
    onPreview: (BrandStickerSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = StickerLanguage.forLocale(currentAppLocale())
    val label = selection.sticker.accessibilityLabel(selection.language, ui)
    val previewTitle = stringResource(R.string.conversation_sticker_picker_preview)
    var pressed by remember { mutableStateOf(false) }
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnPreview by rememberUpdatedState(onPreview)
    val base = LocalViewConfiguration.current
    val configuration = remember(base) { StickerPressConfiguration(base) }
    CompositionLocalProvider(LocalViewConfiguration provides configuration) {
        Box(
            modifier
                .aspectRatio(1f)
                .u2PressEffect(pressed, scale = 0.92f)
                .pointerInput(selection) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onLongPress = { currentOnPreview(selection) },
                        onTap = { currentOnSend(selection) },
                    )
                }
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        currentOnSend(selection)
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction(previewTitle) { currentOnPreview(selection); true })
                }
                .padding(3.dp),
        ) {
            BrandStickerCanvas(selection.sticker, selection.language, Modifier.fillMaxSize())
        }
    }
}

/** The design's touch and hold: 0.35 s. */
private class StickerPressConfiguration(private val base: ViewConfiguration) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = 350L
}

/** The pack's title in the conversation strings. */
@Composable
private fun packTitle(pack: StickerPack): String = stringResource(
    when (pack.id) {
        StickerCatalog.WORDS_PACK_ID -> R.string.conversation_sticker_pack_words
        StickerCatalog.PRIVACY_PACK_ID -> R.string.conversation_sticker_pack_privacy
        StickerCatalog.MOOD_PACK_ID -> R.string.conversation_sticker_pack_mood
        else -> R.string.conversation_sticker_pack_cups
    },
)

/** What the preview says under the sticker: what it shows, in the app's language; nothing for words already in it. */
private fun previewCaption(selection: BrandStickerSelection, ui: StickerLanguage): String? {
    val sticker = selection.sticker
    if (sticker.isText && selection.language == ui && sticker.packId == StickerCatalog.WORDS_PACK_ID) return null
    return sticker.label[ui]
}

/** The long-press preview: the sticker large over `surface·94%`, its caption and “Gönder”; a tap outside closes it. */
@Composable
private fun PreviewOverlay(
    selection: BrandStickerSelection,
    side: Dp,
    caption: String?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = U2Theme.colors
    Box(
        modifier
            .fillMaxSize()
            .testTag(StickerPickerTags.PREVIEW)
            .semantics { isTraversalGroup = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.surface.copy(alpha = 0.94f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics {},
        )
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandStickerView(selection, Modifier.size(side), animatesEntrance = true)
            if (caption != null) {
                U2Text(
                    caption,
                    U2TextStyles.captionTight,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            U2PrimaryButton(
                title = stringResource(R.string.conversation_sticker_preview_send),
                onClick = onSend,
                glyph = PhosphorGlyph.PaperPlaneTilt,
                size = U2PrimaryButtonSize.InCard,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth()
                    .testTag(StickerPickerTags.PREVIEW_SEND),
            )
        }
    }
}
