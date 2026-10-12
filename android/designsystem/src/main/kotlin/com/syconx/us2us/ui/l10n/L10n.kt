package com.syconx.us2us.ui.l10n

import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.os.ConfigurationCompat
import com.syconx.us2us.ui.R
import java.util.Locale

/**
 * The resource of a catalogue key: a plain string or a plural (`variations.plural` on iOS).
 * Resources are named `<table prefix>_<key>` ([StringTable]); see `scripts/xcstrings_to_android.py`.
 */
sealed interface StringRef {
    /** A `<string>`: read with `stringResource(id, …)` or [U2Strings.get]. */
    data class Text(@param:StringRes val id: Int) : StringRef

    /** A `<plurals>` (one / other): read with `pluralStringResource(id, count, …)` or [U2Strings.plural]. */
    data class Plural(@param:PluralsRes val id: Int) : StringRef
}

/**
 * One key of an iOS catalogue: [key] as written there (`"lock.code.wrong.warning %lld %@"`), its
 * resource, and its arguments in order (`d` a number, `s` a string).
 */
data class StringEntry(val key: String, val ref: StringRef, val argumentTypes: String)

/**
 * Language rules for text built outside a resource lookup (iOS `L10n`). Every function takes the
 * language explicitly: the in-app language (7.1 › Dil), never the process default.
 */
object L10n {
    private val argumentSuffix = Regex("""(?:\s+%\S*)+$""")

    /** An iOS key without its trailing argument tokens: `"a11y.typing %@"` → `"a11y.typing"`. */
    fun baseKey(key: String): String = argumentSuffix.replace(key, "")

    /**
     * Upper-cases with an explicit language, never without one (inventory 9.3: “istekler” →
     * “İSTEKLER” in Turkish, not “ISTEKLER”).
     */
    fun uppercased(text: String, locale: Locale): String = text.uppercase(locale)

    /** Lower-cases with an explicit language (“İLETİLEMEDİ” → “iletilemedi” in Turkish). */
    fun lowercased(text: String, locale: Locale): String = text.lowercase(locale)

    /** “deniz’in” / “deniz’s”: the possessive of a username in a sentence (6.2 “deniz’in telefonundaki kodu okut”). */
    fun possessive(name: String, locale: Locale): String =
        if (isTurkish(locale)) TurkishSuffix.genitive(name) else name + "’s"

    /** Whether [locale] is Turkish (the design's language). */
    fun isTurkish(locale: Locale): Boolean = locale.language == "tr"

    /**
     * The language the string resources of [resources] resolved to: `tr` or `en`. A device language
     * other than these two reads the English resources (`values/`), as iOS falls back to English.
     */
    fun uiLanguage(resources: Resources): String = resources.getString(R.string.l10n_language)

    /**
     * The UI's locale (iOS `\.locale`): the language the strings are shown in, with the region of
     * [configuration] (the device's, or the one the app language set). Dates, numbers and casing use
     * it, so they always match the strings, whatever other language the device prefers.
     */
    fun uiLocale(resources: Resources, configuration: Configuration = resources.configuration): Locale {
        val language = uiLanguage(resources)
        val region = ConfigurationCompat.getLocales(configuration)[0]?.country.orEmpty()
        return locale(language, region)
    }

    /** `language_REGION`, or just the language when [region] is empty or not a region code. */
    internal fun locale(language: String, region: String): Locale {
        val builder = Locale.Builder().setLanguage(language)
        if (region.isNotEmpty()) runCatching { builder.setRegion(region) }
        return builder.build()
    }
}

/** Upper-cases in [locale]'s language (Turkish dotted İ): `"istekler".u2Uppercase(tr)` → `"İSTEKLER"`. */
fun String.u2Uppercase(locale: Locale): String = L10n.uppercased(this, locale)
