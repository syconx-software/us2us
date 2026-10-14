package com.syconx.us2us.ui.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.util.Locale
import java.util.WeakHashMap

/**
 * The strings and formats of one language (iOS `L10n.string(_:locale:)` / `StringTable.string`),
 * for text built outside a composable's `stringResource`: models, announcements, the formatting
 * in [U2Format], notifications.
 *
 * In a composable take it from `rememberU2Strings()` (the UI's language); elsewhere from
 * [U2Strings.of] with an explicit locale, never the process default.
 */
@Immutable
class U2Strings private constructor(
    /** Resources resolved in [locale]'s language. */
    val resources: Resources,
    /** The locale the text is written and formatted in. */
    val locale: Locale,
) {
    /** The string [id]. */
    operator fun get(@StringRes id: Int): String = resources.getString(id)

    /** The string [id] formatted with [args] (`%1$s`, `%d` …). */
    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    /** The plural [id] for [count], formatted with [args] (usually [count] itself first). */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)

    /**
     * The value of an iOS catalogue [key] in [table], with [args] filling its `%…` in order, as
     * `String(localized: "key \(a) \(b)", table:)` does. A plural takes its count from the first
     * number among [args]. Throws for a key the catalogue does not have.
     */
    fun get(table: StringTable, key: String, vararg args: Any): String =
        when (val ref = table.find(key) ?: throw IllegalArgumentException("no key '$key' in ${table.catalogue}")) {
            is StringRef.Text -> if (args.isEmpty()) get(ref.id) else get(ref.id, *args)
            is StringRef.Plural -> {
                val count = args.firstNotNullOfOrNull { (it as? Number)?.toInt() }
                    ?: throw IllegalArgumentException("'$key' is a plural: pass its count")
                plural(ref.id, count, *args)
            }
        }

    override fun equals(other: Any?): Boolean =
        other is U2Strings && other.locale == locale && other.resources === resources

    override fun hashCode(): Int = 31 * System.identityHashCode(resources) + locale.hashCode()

    override fun toString(): String = "U2Strings($locale)"

    companion object {
        private val cache = WeakHashMap<Context, MutableMap<Locale, U2Strings>>()

        /**
         * The strings of [locale]'s language: Turkish for `tr`, English for anything else (the
         * `values/` fallback). Contexts are made once per application and locale.
         */
        fun of(context: Context, locale: Locale): U2Strings {
            val app = context.applicationContext ?: context
            synchronized(cache) {
                return cache.getOrPut(app) { HashMap() }.getOrPut(locale) {
                    val configuration = Configuration(app.resources.configuration)
                    configuration.setLocale(locale)
                    U2Strings(app.createConfigurationContext(configuration).resources, locale)
                }
            }
        }

        /** The strings of [resources] as they are (already in the UI's language), formatted in [locale]. */
        fun wrap(resources: Resources, locale: Locale = L10n.uiLocale(resources)): U2Strings = U2Strings(resources, locale)
    }
}
