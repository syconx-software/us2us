package com.syconx.us2us.ui.l10n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.util.Locale

/**
 * The UI's locale (iOS `@Environment(\.locale)`): the language the strings resolved to (`tr` or
 * `en`) with the configuration's region. Format dates, numbers and casing with it, so they always
 * match the strings, also when the device prefers a language the app does not have.
 */
@Composable
fun currentAppLocale(): Locale {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(configuration, resources) { L10n.uiLocale(resources, configuration) }
}

/** Strings and formats in the UI's language, for [U2Format] and for text built in code. */
@Composable
fun rememberU2Strings(): U2Strings {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(configuration, resources) { U2Strings.wrap(resources, L10n.uiLocale(resources, configuration)) }
}

/** A plural in the UI's language: `u2Plural(R.plurals.common_a11y_unread, n, n)`. */
@Composable
fun u2Plural(@PluralsRes id: Int, count: Int, vararg args: Any): String {
    LocalConfiguration.current
    return LocalResources.current.getQuantityString(id, count, *args)
}

/**
 * The value of an iOS catalogue key in the UI's language, for keys chosen at run time
 * (`Text(LocalizedStringKey(pack.titleKey), table: .conversation)`). Prefer the `R.string` id
 * wherever the key is fixed.
 */
@Composable
fun u2String(table: StringTable, key: String, vararg args: Any): String = rememberU2Strings().get(table, key, *args)

/**
 * A string resource formatted with [args], the arguments at the 1-based positions of [styles] set
 * in those styles (4.2 `newChat.info`: `mapOf(2 to SpanStyle(color = U2Theme.colors.text))`).
 */
@Composable
fun annotatedStringResource(@StringRes id: Int, args: List<Any>, styles: Map<Int, SpanStyle>): AnnotatedString {
    val strings = rememberU2Strings()
    return remember(strings, id, args, styles) { Emphasis.format(strings[id], args, styles, strings.locale) }
}
