package com.syconx.us2us.runtime.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.text.BreakIterator
import java.util.Locale

/**
 * User-facing strings the runtime produces itself: the biometric prompt, local system lines and
 * the connection "hat" line. The runtime holds no copy: each key is resolved from the app's string
 * resources (Turkish in `values-tr`, English in `values`), named after [resourceName], through a
 * [RuntimeTexts] the app supplies.
 */
enum class RuntimeString {
    /** Biometric prompt title. */
    BIOMETRIC_REASON,

    /** Negative button of the biometric prompt: falls back to the code keypad. */
    BIOMETRIC_USE_CODE,

    /** Local system line after the user took a screenshot (the contact was told). */
    SCREENSHOT_TAKEN_BY_ME,

    /** Local system line when screen recording started in a conversation. */
    SCREEN_RECORDING_BY_ME,

    /** System line when the contact's safety number changed (sending is blocked). */
    SAFETY_NUMBER_CHANGED_LINE,

    /** System line when the user accepted a changed safety number (without verifying it). */
    SAFETY_NUMBER_ACCEPTED_LINE,

    /** System line when the user verified the safety number. */
    SAFETY_NUMBER_VERIFIED_LINE,

    /** System line when a message request was accepted. */
    REQUEST_ACCEPTED_LINE,

    /** Tombstone of a message deleted for everyone. */
    DELETED_FOR_EVERYONE,

    /** Connection "hat" lines on the chat list. */
    LINE_OPEN,
    LINE_CONNECTING,
    LINE_SYNCING,
    LINE_OFFLINE,
    LINE_WAITING_FOR_NETWORK,
    LINE_REVOKED,
    LINE_LOCKED,
    ;

    /** The string resource that holds this text: `runtime_` and the key in lower case. */
    val resourceName: String get() = "runtime_" + name.lowercase(Locale.ROOT)
}

/** Where the texts of [RuntimeString]s come from. */
fun interface RuntimeTexts {
    /** The text of [key] in [languageCode] (`"tr"` or `"en"`). */
    fun text(key: RuntimeString, languageCode: String): String
}

/**
 * Resolves [RuntimeString]s in the app language: Turkish or English, anything else falls back to
 * English, like the kit's `Localizer`.
 */
class RuntimeStrings(languageCode: String, private val texts: RuntimeTexts) {
    val languageCode: String = if (languageCode.lowercase(Locale.ROOT).startsWith("tr")) "tr" else "en"

    fun string(key: RuntimeString): String = texts.text(key, languageCode)

    companion object {
        /**
         * The strings of the app's resources in [languageCode]; [resource] maps every key to its
         * string resource id (the resource named [RuntimeString.resourceName]).
         */
        fun fromResources(
            context: Context,
            languageCode: String,
            resource: (RuntimeString) -> Int,
        ): RuntimeStrings = RuntimeStrings(languageCode, ResourceTexts(context.applicationContext ?: context, resource))

        /** [fromResources] in the language the app currently shows. */
        fun current(context: Context, resource: (RuntimeString) -> Int): RuntimeStrings {
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            return fromResources(context, locale.language, resource)
        }
    }
}

/** [RuntimeTexts] from string resources, one localized context per language. */
private class ResourceTexts(
    private val context: Context,
    private val resource: (RuntimeString) -> Int,
) : RuntimeTexts {
    private val localized = mutableMapOf<String, Context>()

    override fun text(key: RuntimeString, languageCode: String): String {
        @StringRes val id = resource(key)
        return localizedContext(languageCode).getString(id)
    }

    // Language splits of an app bundle are off (the app switches its language itself).
    @SuppressLint("AppBundleLocaleChanges")
    @Synchronized
    private fun localizedContext(languageCode: String): Context = localized.getOrPut(languageCode) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageCode))
        context.createConfigurationContext(configuration)
    }
}

/**
 * The longest prefix whose UTF-8 encoding fits in [maxBytes], cut at a character boundary (a
 * user-perceived character: an emoji with its modifiers, a letter with its accents).
 */
fun String.utf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0 || isEmpty()) return ""
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(this) }
    var bytes = 0
    var end = 0
    var start = characters.first()
    var next = characters.next()
    while (next != BreakIterator.DONE) {
        val size = substring(start, next).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) break
        bytes += size
        end = next
        start = next
        next = characters.next()
    }
    return substring(0, end)
}
