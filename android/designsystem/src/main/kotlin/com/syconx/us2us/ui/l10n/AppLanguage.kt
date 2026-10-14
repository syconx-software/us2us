package com.syconx.us2us.ui.l10n

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The in-app language setting (7.1 › Dil). Turkish first, English second. Stored as [raw].
 *
 * Applied with [apply] (AppCompat's per-app language, `res/xml/locales_config.xml`), which also
 * keeps it across launches; `values/` is English, so any other device language reads English, as
 * on iOS.
 */
enum class AppLanguage(val raw: String) {
    /**
     * Follow the device: Turkish or English, whichever the user prefers first; English for any
     * other device language.
     */
    System("system"),
    Turkish("tr"),
    English("en"),
    ;

    /**
     * The locale the whole UI uses: strings, upper-casing, dates and numbers. Keeps the device's
     * region for formatting (“en_TR” for English on a Turkish-region phone).
     */
    fun resolvedLocale(
        preferredLanguages: List<String> = systemPreferredLanguages(),
        current: Locale = systemLocale(),
    ): Locale {
        val code = when (this) {
            Turkish -> "tr"
            English -> "en"
            System ->
                preferredLanguages
                    .map { Locale.forLanguageTag(it.replace('_', '-')).language }
                    .firstOrNull { it == "tr" || it == "en" } ?: "en"
        }
        return L10n.locale(code, current.country)
    }

    /** What `AppCompatDelegate.setApplicationLocales` takes: empty (follow the device) for [System]. */
    val localeList: LocaleListCompat
        get() = if (this == System) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(raw)

    /**
     * Switches the app's language (call on the main thread). Activities are recreated in the new
     * language; the lock and navigation state live in the Application and survive it.
     */
    fun apply() {
        if (AppCompatDelegate.getApplicationLocales() != localeList) AppCompatDelegate.setApplicationLocales(localeList)
    }

    companion object {
        fun fromRaw(raw: String?): AppLanguage? = entries.firstOrNull { it.raw == raw }

        /** The language the app is set to (AppCompat's per-app locale): [System] when none is set. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return System
            return when (locales[0]?.language) {
                "tr" -> Turkish
                "en" -> English
                else -> System
            }
        }

        /** The device's preferred languages (BCP-47), whatever language the app is set to. */
        fun systemPreferredLanguages(): List<String> {
            val locales = ConfigurationCompat.getLocales(Resources.getSystem().configuration)
            return (0 until locales.size()).mapNotNull { locales[it]?.toLanguageTag() }
        }

        /** The device's first locale (its region is kept for formatting). */
        fun systemLocale(): Locale = ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0] ?: Locale.getDefault()
    }
}
