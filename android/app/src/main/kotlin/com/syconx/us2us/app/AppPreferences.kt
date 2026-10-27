package com.syconx.us2us.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.ThemePreference
import com.syconx.us2us.ui.env.U2Preferences
import com.syconx.us2us.ui.l10n.AppLanguage

/**
 * Device-level preferences that must be readable before unlocking (the lock screen is themed and
 * localized too): theme, language, screen-awake opt-in and the save notice's acknowledgement. Not sensitive, so
 * they live in plain shared preferences (excluded from backups like everything else); everything
 * else is in the vault. The keys are the iOS ones.
 *
 * The language is also applied as the app's per-app locale ([AppLanguage.apply]), which recreates
 * the activity; the app's state lives in the application and survives it.
 */
class AppPreferences(private val store: SharedPreferences, private val appliesLanguage: Boolean = true) : U2Preferences {
    private var themeState by mutableStateOf(store.getString(THEME_KEY, null)?.let(ThemePreference::fromRaw) ?: ThemePreference.System)
    private var languageState by mutableStateOf(AppLanguage.fromRaw(store.getString(LANGUAGE_KEY, null)) ?: AppLanguage.System)
    private var saveNoticeState by mutableStateOf(store.getBoolean(SAVE_NOTICE_KEY, false))
    private var keepScreenOnState by mutableStateOf(store.getBoolean(KEEP_SCREEN_ON_KEY, false))

    override var keepScreenOn: Boolean
        get() = keepScreenOnState
        set(value) {
            keepScreenOnState = value
            store.edit().putBoolean(KEEP_SCREEN_ON_KEY, value).apply()
        }

    override var theme: ThemePreference
        get() = themeState
        set(value) {
            themeState = value
            store.edit().putString(THEME_KEY, value.raw).apply()
        }

    override var language: AppLanguage
        get() = languageState
        set(value) {
            languageState = value
            store.edit().putString(LANGUAGE_KEY, value.raw).apply()
            if (appliesLanguage) value.apply()
        }

    override var hasAcknowledgedSaveNotice: Boolean
        get() = saveNoticeState
        set(value) {
            saveNoticeState = value
            store.edit().apply { if (value) putBoolean(SAVE_NOTICE_KEY, true) else remove(SAVE_NOTICE_KEY) }.apply()
        }

    /** `U2ResetPreferences`: forgets the saved theme, language and save notice. */
    fun reset() {
        store.edit().remove(THEME_KEY).remove(LANGUAGE_KEY).remove(SAVE_NOTICE_KEY).remove(KEEP_SCREEN_ON_KEY).apply()
        themeState = ThemePreference.System
        saveNoticeState = false
        keepScreenOnState = false
        language = AppLanguage.System
    }

    /** Makes the per-app locale match the saved language (a launch after the system lost it, or a restore of the app). */
    fun applyLanguage() {
        if (appliesLanguage) languageState.apply()
    }

    companion object {
        const val THEME_KEY = "u2.theme"
        const val LANGUAGE_KEY = "u2.language"
        const val SAVE_NOTICE_KEY = "u2.saveNotice.acknowledged"
        const val KEEP_SCREEN_ON_KEY = "u2.keepScreenOn"

        /** The file the app's preferences are kept in. */
        const val FILE = "us2us.preferences"

        fun of(context: Context): AppPreferences = AppPreferences(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
