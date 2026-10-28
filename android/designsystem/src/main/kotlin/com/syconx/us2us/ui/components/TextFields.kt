package com.syconx.us2us.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalLayerMotion
import com.syconx.us2us.ui.sheet.LocalSheetSettled
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2BoxShadow
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.type.textStyle

// MARK: - C23 · Field chrome

/** How a focused field shows its focus. */
enum class U2FieldFocus {
    /** Inner 1.5 dp ring + outer glow `0 0 26px -10px` (2.1, 4.2). */
    Glow,

    /** The ring alone (7.2 password). */
    Ring,
}

/**
 * C23 field chrome: [height], r16, [fill] (`surface`; on a `surface` sheet `bg`). Focused: an inner
 * 1.5 dp [ringColor] ring and, for [U2FieldFocus.Glow], the outer glow `0 0 26px -10px`. The
 * ring fades in and out softly (0.36 s).
 */
@Composable
fun Modifier.u2FieldChrome(
    isFocused: Boolean,
    height: Dp,
    focus: U2FieldFocus = U2FieldFocus.Glow,
    fill: Color = U2Theme.colors.surface,
    ringColor: Color = U2Theme.colors.accent,
): Modifier {
    return this
        .height(height)
        .u2FieldSurface(isFocused, focus, fill, ringColor)
}

/** [u2FieldChrome] without a fixed height, for a field that grows with its text. */
@Composable
private fun Modifier.u2FieldSurface(isFocused: Boolean, focus: U2FieldFocus, fill: Color, ringColor: Color): Modifier {
    val shown by animateFloatAsState(if (isFocused) 1f else 0f, u2Spec(U2Motion.state), label = "fieldFocus")
    val shape = RoundedCornerShape(U2Radius.field)
    val ring = ringColor.copy(alpha = ringColor.alpha * shown)
    return this
        .then(
            if (focus == U2FieldFocus.Glow && shown > 0f) {
                Modifier.u2BoxShadow(shape, U2BoxShadow(ring, blur = 26.dp, spread = (-10).dp))
            } else {
                Modifier
            },
        )
        .background(fill, shape)
        .u2InnerStroke(ring, 1.5.dp, shape)
}

// MARK: - Text field

/**
 * A C23 field for free text (a name, a code, a message): [U2TextStyles.bubble] in `text` on
 * [fill], the `accent` caret (visible on both themes), the focus ring of [u2FieldChrome] and the
 * [placeholder] in `textPlaceholder`. One line at the search field's height, or with [maxLines]
 * above 1 a field that grows from that height. Edits that would pass [maxBytes] UTF-8 bytes are
 * refused, nothing is cut.
 */
@Composable
fun U2TextField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentDescription: String = placeholder,
    maxLines: Int = 1,
    maxBytes: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    fill: Color = U2Theme.colors.surface,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = U2Theme.colors
    val style = U2TextStyles.bubble
    var focused by remember { mutableStateOf(false) }
    val singleLine = maxLines <= 1
    BasicTextField(
        value = text,
        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= maxBytes) onTextChange(it) },
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = style.textStyle().copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (singleLine) {
                    Modifier.u2FieldChrome(focused, U2Size.fieldSearch, focus = U2FieldFocus.Ring, fill = fill)
                } else {
                    Modifier.heightIn(min = U2Size.fieldSearch).u2FieldSurface(focused, U2FieldFocus.Ring, fill, colors.accent)
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = if (singleLine) 0.dp else 16.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) U2Text(placeholder, style, Modifier.clearAndSetSemantics {}, color = colors.textPlaceholder, maxLines = 1)
                field()
            }
        },
    )
}

// MARK: - Username field (2.1)

/** The trailing status glyph of a username field. */
enum class UsernameFieldStatus { None, Checking, Positive, Negative }

/**
 * C23 username field (2.1: 60 high, Mono 19, pad 0 16, on `surface`): a `text·68%` “@” prefix,
 * the name in Mono, the design's accent caret, and a trailing status glyph (`check-circle` fill
 * `online` / `x-circle` fill `danger` / a softly spinning `circle-notch`) or a clear button.
 *
 * No autocorrect, no auto-capitalisation, ASCII keyboard. What is typed is shown in lower case
 * ([UsernameRules.displayForm]) and otherwise kept as it is: nothing is dropped, converted or cut,
 * so a name that breaks a rule stays visible and the screen says which.
 *
 * The design's caret (2 × 24, r1, `accent400`) is drawn while the insertion point is a caret at
 * the end of a name that fits; anywhere else the system caret shows. [focusesOnAppear] raises the
 * keyboard once the screen's push has settled.
 * [showsFocusChrome] draws the focused look without focus (keyboard-less harness states).
 */
@Composable
fun UsernameField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    status: UsernameFieldStatus = UsernameFieldStatus.None,
    showsClearButton: Boolean = false,
    prompt: String? = null,
    focusesOnAppear: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showsFocusChrome: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    val colors = U2Theme.colors
    val style = U2TextStyles.monoInputL
    var focused by remember { mutableStateOf(false) }
    val editor = rememberFieldEditor(text)
    val looksFocused = focused || showsFocusChrome
    val description = stringResource(R.string.common_a11y_username)

    Row(
        modifier
            .fillMaxWidth()
            .u2FieldChrome(looksFocused, U2Size.fieldUsername, fill = colors.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) { requestFocusSafely(focusRequester) }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both 1 dp higher than the centred line box: the design's glyph rows (“@” 294.33–314.33,
        // the name from 293.67).
        U2Text("@", style, color = colors.textSecondary, modifier = Modifier.offset(y = (-1).dp).clearAndSetSemantics {})
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = editor.value(text),
                onValueChange = { value -> editor.change(value, UsernameRules.displayForm(value.text), text, onTextChange) },
                textStyle = style.textStyle().copy(color = colors.text),
                cursorBrush = SolidColor(if (looksFocused && editor.caretAtEnd(text) && editor.fits) Color.Transparent else colors.accent400),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { onSubmit() }, onDone = { onSubmit() }),
                onTextLayout = editor::onLayout,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-1).dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onSizeChanged { editor.inputWidth = it.width.toFloat() }
                    .semantics {
                        contentDescription = description
                        contentType = ContentType.Username
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty() && prompt != null) U2Text(prompt, style, color = colors.textPlaceholder)
                        inner()
                    }
                },
            )
            if (looksFocused && editor.caretAtEnd(text) && editor.fits) {
                // Where the design's renderer painted it (147.0, 291.0: its 147.42 / 291.44 snapped
                // to the device pixels).
                DesignCaret(editor.textWidth, nudge = DpOffset((-1f / 3f).dp, (-1f / 3f).dp))
            }
        }
        UsernameTrailing(text, status, showsClearButton) { onTextChange("") }
    }
    // Raise the keyboard once the push has arrived (2.1): setting the keyboard up mid-push costs
    // the transition a long frame.
    val settled = LocalLayerMotion.current?.isSettled ?: true
    var didAutoFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusesOnAppear, settled) {
        if (focusesOnAppear && settled && !didAutoFocus) {
            didAutoFocus = true
            requestFocusSafely(focusRequester)
        }
    }
}

@Composable
private fun UsernameTrailing(text: String, status: UsernameFieldStatus, showsClearButton: Boolean, onClear: () -> Unit) {
    val colors = U2Theme.colors
    val shown = if (showsClearButton && text.isNotEmpty()) null else status
    val fade = U2Transitions.fade(U2Motion.micro)
    AnimatedContent(
        targetState = shown,
        transitionSpec = { fade.enter togetherWith fade.exit },
        contentAlignment = Alignment.Center,
        label = "usernameTrailing",
    ) { state ->
        val blur = Modifier.then(u2TransitionBlur(fade))
        when (state) {
            null -> {
                val clear = stringResource(R.string.common_a11y_clear)
                Box(
                    blur
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClear)
                        .semantics { contentDescription = clear },
                ) {
                    PhosphorIcon(PhosphorGlyph.XCircle, size = 20.dp, weight = PhosphorWeight.Fill, tint = colors.textTertiary)
                }
            }
            UsernameFieldStatus.None -> Box(blur)
            UsernameFieldStatus.Checking -> SpinningGlyph(size = 22.dp, tint = colors.textSecondary, modifier = blur)
            UsernameFieldStatus.Positive -> PhosphorIcon(PhosphorGlyph.CheckCircle, size = 22.dp, weight = PhosphorWeight.Fill, tint = colors.online, modifier = blur)
            UsernameFieldStatus.Negative -> PhosphorIcon(PhosphorGlyph.XCircle, size = 22.dp, weight = PhosphorWeight.Fill, tint = colors.danger, modifier = blur)
        }
    }
}

// MARK: - 4.2 search field

/**
 * The 4.2 exact-username field (C23 at 56 dp, Mono 18, on the sheet's `bg`): a `text·68%` “@”,
 * the name, the accent caret, and either the soft spinner (while the exact lookup runs) or the
 * clear button.
 *
 * Unlike [UsernameField] (the user's own name) the keyboard offers nothing here: no username
 * autofill, no suggestions, no autocorrection, no auto-capitalisation, ASCII keyboard, a search
 * action. The text is never rewritten: the lookup normalizes it. [showsFocusChrome] draws the
 * focused chrome and the design's caret without raising the keyboard (harness states).
 */
@Composable
fun UsernameSearchField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSearching: Boolean = false,
    focusesOnAppear: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showsFocusChrome: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    val colors = U2Theme.colors
    val style = U2TextStyles.monoInput
    var focused by remember { mutableStateOf(false) }
    val editor = rememberFieldEditor(text)
    val description = stringResource(R.string.common_a11y_username)
    val placeholder = stringResource(R.string.chats_newchat_placeholder)

    Row(
        modifier
            .fillMaxWidth()
            .u2FieldChrome(focused || showsFocusChrome, U2Size.fieldSearch, fill = colors.bg)
            .clickable(remember { MutableInteractionSource() }, indication = null) { requestFocusSafely(focusRequester) }
            .padding(start = 16.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            U2Text("@", style, color = colors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = editor.value(text),
                    onValueChange = { value -> editor.change(value, value.text, text, onTextChange) },
                    textStyle = style.textStyle().copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent400),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    onTextLayout = editor::onLayout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("newChat.field")
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onSizeChanged { editor.inputWidth = it.width.toFloat() }
                        .semantics { contentDescription = description },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) U2Text(placeholder, style, color = colors.textPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                if (showsFocusChrome && !focused) DesignCaret(editor.textWidth)
            }
        }
        SearchTrailing(text, isSearching) {
            onTextChange("")
            requestFocusSafely(focusRequester)
        }
    }
    // Raise the keyboard only once the sheet has finished rising: setting up the keyboard mid-rise
    // stalls the frame and the sheet would freeze, then jump.
    val settled = LocalSheetSettled.current
    var didAutoFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusesOnAppear, settled) {
        if (focusesOnAppear && settled && !didAutoFocus) {
            didAutoFocus = true
            requestFocusSafely(focusRequester)
        }
    }
}

@Composable
private fun SearchTrailing(text: String, isSearching: Boolean, onClear: () -> Unit) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade(U2Motion.micro)
    val state = when {
        isSearching -> SearchTrailingState.Searching
        text.isNotEmpty() -> SearchTrailingState.Clear
        else -> SearchTrailingState.None
    }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fade.enter togetherWith fade.exit },
            contentAlignment = Alignment.Center,
            label = "searchTrailing",
        ) { shown ->
            val blur = Modifier.then(u2TransitionBlur(fade))
            when (shown) {
                SearchTrailingState.Searching -> {
                    val searching = stringResource(R.string.chats_newchat_searching)
                    SpinningGlyph(size = 20.dp, tint = colors.textSecondary, modifier = blur.semantics { contentDescription = searching })
                }
                SearchTrailingState.Clear -> {
                    val clear = stringResource(R.string.common_a11y_clear)
                    // A 32 × 44 touch area around the 20 dp glyph.
                    Box(
                        blur
                            .requiredSize(32.dp, 44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClear)
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhosphorIcon(PhosphorGlyph.XCircle, size = 20.dp, weight = PhosphorWeight.Fill, tint = colors.textTertiary)
                    }
                }
                SearchTrailingState.None -> Box(blur.size(20.dp))
            }
        }
    }
}

private enum class SearchTrailingState { None, Searching, Clear }

// MARK: - Password field (7.2, 7.3)

/** What a password field shows at its end. */
enum class PasswordFieldTrailing {
    /** `eye` / `eye-slash` 20 `text·68%`: reveal. */
    Reveal,

    /** `check-circle` fill 20 `online` (7.3 accepted, 7.2 confirmation matches). */
    Accepted,

    /** A softly spinning `circle-notch` (7.3 checking). */
    Checking,
}

/**
 * The 7.2 / 7.3 password field: 56 high, r16, `surface`, pad 0 16, gap 10. While hidden, 9 × 9
 * `text` dots (gap 7) are drawn over the input; the ring (`inset 0 0 0 1.5px accent`, no glow)
 * shows while focused or [isHighlighted], in `danger` on [isError].
 *
 * A shown password is hidden again when the app leaves the foreground, and cannot be shown while
 * [revealAllowed] is off (the screen is recorded or mirrored). Report focus with [onFocusChange];
 * move focus with [focusRequester].
 */
@Composable
fun PasswordField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String? = null,
    trailing: PasswordFieldTrailing = PasswordFieldTrailing.Reveal,
    isError: Boolean = false,
    isHighlighted: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    contentType: ContentType = ContentType.Password,
    revealAllowed: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val colors = U2Theme.colors
    val style = U2TextStyles.rowTitle
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val showsText = revealed && revealAllowed
    val editor = rememberFieldEditor(text)
    val description = stringResource(R.string.common_a11y_password)

    // Hidden again before the app-switcher snapshot and the lock, and as soon as a recording starts.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) revealed = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revealAllowed) { if (!revealAllowed) revealed = false }

    val ringVisible = isError || focused || isHighlighted
    val ringShown by animateFloatAsState(if (ringVisible) 1f else 0f, u2Spec(U2Motion.state), label = "passwordRing")
    val ringColor = if (isError) colors.danger else colors.accent
    val shape = RoundedCornerShape(U2Radius.field)

    Row(
        modifier
            .fillMaxWidth()
            .height(U2Size.fieldPassword)
            .background(colors.surface, shape)
            .u2InnerStroke(ringColor.copy(alpha = ringColor.alpha * ringShown), 1.5.dp, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { requestFocusSafely(focusRequester) }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = editor.value(text),
                onValueChange = { value -> editor.change(value, value.text, text, onTextChange) },
                enabled = enabled,
                textStyle = style.textStyle().copy(color = if (showsText) colors.text else Color.Transparent),
                cursorBrush = SolidColor(if (showsText) colors.accent400 else Color.Transparent),
                singleLine = true,
                visualTransformation = if (showsText) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange(it.isFocused)
                    }
                    .semantics {
                        this.contentDescription = description
                        this.contentType = contentType
                        if (!showsText) password()
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty() && prompt != null) U2Text(prompt, style, color = colors.textPlaceholder)
                        inner()
                    }
                },
            )
            if (!showsText) MaskedDots(count = text.length, showsCaret = focused)
        }
        PasswordTrailing(trailing, showsText, revealAllowed) {
            if (revealAllowed) {
                revealed = !revealed
                requestFocusSafely(focusRequester)
            }
        }
    }
}

@Composable
private fun PasswordTrailing(trailing: PasswordFieldTrailing, showsText: Boolean, revealAllowed: Boolean, onReveal: () -> Unit) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade(U2Motion.micro)
    val pop = U2Transitions.pop(U2Motion.micro)
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = trailing,
            transitionSpec = {
                val t = if (targetState == PasswordFieldTrailing.Accepted) pop else fade
                t.enter togetherWith fade.exit
            },
            contentAlignment = Alignment.Center,
            label = "passwordTrailing",
        ) { shown ->
            when (shown) {
                PasswordFieldTrailing.Reveal -> {
                    val label = stringResource(if (showsText) R.string.common_a11y_password_hide else R.string.common_a11y_password_show)
                    Box(
                        Modifier
                            .then(u2TransitionBlur(fade))
                            .requiredSize(44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = revealAllowed, role = Role.Button, onClick = onReveal)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhosphorIcon(if (showsText) PhosphorGlyph.EyeSlash else PhosphorGlyph.Eye, size = 20.dp, tint = colors.textSecondary)
                    }
                }
                PasswordFieldTrailing.Accepted -> {
                    val label = stringResource(R.string.common_a11y_password_accepted)
                    PhosphorIcon(
                        PhosphorGlyph.CheckCircle,
                        size = 20.dp,
                        weight = PhosphorWeight.Fill,
                        tint = colors.online,
                        contentDescription = label,
                        modifier = Modifier.then(u2TransitionBlur(pop)),
                    )
                }
                PasswordFieldTrailing.Checking -> SpinningGlyph(size = 20.dp, tint = colors.textSecondary, modifier = Modifier.then(u2TransitionBlur(fade)))
            }
        }
    }
}

/** Password dots: 9 × 9 `text`, gap 7, popping in and out, with the accent caret after the last. */
@Composable
private fun MaskedDots(count: Int, showsCaret: Boolean) {
    val colors = U2Theme.colors
    val pop = U2Transitions.pop(U2Motion.micro)
    Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        repeat(MAX_MASKED_DOTS) { index ->
            AnimatedVisibility(visible = index < count, enter = pop.enter, exit = pop.exit) {
                Box(Modifier.padding(end = 7.dp)) {
                    Box(Modifier.size(9.dp).background(colors.text, CircleShape))
                }
            }
        }
        if (showsCaret) BlinkingCaret()
    }
}

private const val MAX_MASKED_DOTS = 40

// MARK: - Carets and spinners

/** The 2 × 24 r1 `accent400` caret, blinking softly (steady under reduce motion and in design comparisons). */
@Composable
fun BlinkingCaret(modifier: Modifier = Modifier) {
    val color = U2Theme.colors.accent400
    val held = ornamentsHeld()
    val alpha = if (held) {
        1f
    } else {
        val blink = rememberInfiniteTransition(label = "caret")
        val value by blink.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(550, easing = U2Motion.EaseInOutEasing), RepeatMode.Reverse),
            label = "caretBlink",
        )
        value
    }
    Box(
        modifier
            .size(2.dp, 24.dp)
            .alpha(alpha)
            .background(color, RoundedCornerShape(U2Radius.caret)),
    )
}

/** `circle-notch` turning once per 1.2 s, linear (7.3); still under reduce motion and in design comparisons. */
@Composable
fun SpinningGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    tint: Color = U2Theme.colors.text,
) {
    val held = ornamentsHeld()
    val turns = if (held) {
        0f
    } else {
        val spin = rememberInfiniteTransition(label = "spinner")
        val value by spin.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "spinnerTurn",
        )
        value
    }
    PhosphorIcon(PhosphorGlyph.CircleNotch, size = size, tint = tint, modifier = modifier.rotate(turns * 360f))
}

// MARK: - Editing state

/**
 * The selection and layout of a field whose text the caller owns. The field shows the caller's
 * text with the last selection (clamped); an edit is reported in the form [change] is given
 * (lower-cased for the username), and the caret follows when the form changes the length.
 */
private class FieldEditor(initial: String) {
    var selection by mutableStateOf(TextRange(initial.length))
    var composition by mutableStateOf<TextRange?>(null)
    var textWidth by mutableFloatStateOf(0f)
    var inputWidth by mutableFloatStateOf(Float.MAX_VALUE)
    var density = 1f

    /** The text this editor last showed or produced; a different one came from outside. */
    private var known: String = initial

    fun value(text: String): TextFieldValue {
        if (text != known) {
            // Set from outside (a harness, an invite link, “clear”): the caret goes after it, as UIKit puts it.
            known = text
            selection = TextRange(text.length)
            composition = null
        }
        return TextFieldValue(
            text = text,
            selection = TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length)),
            composition = composition?.takeIf { it.max <= text.length },
        )
    }

    fun change(value: TextFieldValue, shown: String, current: String, onTextChange: (String) -> Unit) {
        selection = if (shown.length == value.text.length) value.selection else TextRange(shown.length)
        composition = if (shown == value.text) value.composition else null
        known = shown
        if (shown != current) onTextChange(shown)
    }

    fun onLayout(layout: TextLayoutResult) {
        textWidth = if (layout.layoutInput.text.isEmpty()) 0f else layout.getLineRight(0)
    }

    fun caretAtEnd(text: String): Boolean = selection.collapsed && selection.start >= text.length

    /** The design's caret fits after the name (the name plus the caret's 5 dp are inside the input). */
    val fits: Boolean get() = textWidth + 5f * density <= inputWidth
}

@Composable
private fun rememberFieldEditor(text: String): FieldEditor {
    val density = LocalDensity.current.density
    return remember { FieldEditor(text) }.also { it.density = density }
}

/** The design's caret after the typed name: 3 dp past its end, centred on the field, moved by [nudge]. */
@Composable
private fun DesignCaret(textWidthPx: Float, nudge: DpOffset = DpOffset.Zero) {
    val x = with(LocalDensity.current) { textWidthPx.toDp() } + 3.dp
    BlinkingCaret(Modifier.offset(x = x + nudge.x, y = nudge.y).clearAndSetSemantics {})
}

private fun requestFocusSafely(requester: FocusRequester) {
    // A requester not attached yet (the field is still being placed) has nothing to focus.
    runCatching { requester.requestFocus() }
}
