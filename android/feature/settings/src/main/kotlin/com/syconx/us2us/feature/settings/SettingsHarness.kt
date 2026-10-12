package com.syconx.us2us.feature.settings

import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.domain.nav.StartScreen

/**
 * The state the settings harness hands to section 07's screens (iOS `SettingsHarness.take`): each
 * screen takes the part that is its own once, when it first appears (7.1 its sheets, a page its
 * model's state), so a page opened again later starts as usual.
 */
class SettingsUiState(override val state: String) : HarnessUiState {
    private val consumed = mutableSetOf<String>()

    /** [state] when it is [prefix] or `prefix-…` and not taken yet; `null` otherwise. */
    fun take(prefix: String): String? {
        if (state != prefix && !state.startsWith("$prefix-")) return null
        return if (consumed.add(prefix)) state else null
    }

    override fun toString(): String = "SettingsUiState($state)"
}

/**
 * Debug harness for 07 · Ayarlar: every screen in an exact state for screenshots
 * (`U2Route settings:<state>`). 7.1 is the root of the pushed screens; the page in the state is
 * pushed over it (without animation, at launch).
 *
 * | state | screen |
 * |---|---|
 * | `7.1` | 7.1 Ayarlar (= plain `settings`) · `7.1-end` scrolled to the end (Hakkında, Her şeyi sil) · `7.1-access` with the notice of an unanswered token rotation after a restore (`7.1-access-checking`, `7.1-access-unanswered`) · `7.1-blocked` with bora and lale blocked (Engellenenler · 2) |
 * | `blocked` · `blocked-empty` | 7.1 › Engellenenler (proposed): bora blocked (chat in 4.1) and lale (no chat) / nobody blocked |
 * | `qr` · `erase` · `erase-failed` | 7.1 with the own-QR sheet / the erase confirmation / “Hesap silinemedi.” (the fixtures' relay stays unreachable) |
 * | `avatar` · `avatar-crop` | 7.1 with “Profil görseli” (proposed) / and the framing screen |
 * | `presence` `autolock` `ghost` `disappearing` `theme` `language` | the 7.1 pickers |
 * | `7.2` | 7.2 as drawn (14-character password, “Güçlü”, ring) |
 * | `7.2-empty` `7.2-weak` `7.2-generated` `7.2-revealed` `7.2-copied` `7.2-confirm` `7.2-mismatch` `7.2-exporting` | 7.2 states |
 * | `7.3` | 7.3 as drawn (62 %, messages decrypting) |
 * | `7.3-pick` `7.3-password` `7.3-wrong` `7.3-done` `7.3-cancel` | 7.3 states |
 * | `code` `code-wrong` `code-new` `code-confirm` `code-weak` `code-mismatch` | code change steps |
 * | `faceid` | 7.1 › “Face ID ile aç” turned on while off: the code once (proposed) |
 * | `about` | Hakkında |
 */
object SettingsHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Settings

    override val states: List<String> = listOf(
        "7.1", "7.1-end", "7.1-access", "7.1-access-checking", "7.1-access-unanswered", "7.1-blocked", "blocked",
        "blocked-empty", "qr", "erase",
        "erase-failed", "avatar", "avatar-crop", "presence", "autolock", "ghost", "disappearing", "theme",
        "language", "7.2", "7.2-empty", "7.2-weak", "7.2-generated", "7.2-revealed", "7.2-copied", "7.2-confirm",
        "7.2-mismatch", "7.2-exporting", "7.3", "7.3-pick", "7.3-password", "7.3-wrong", "7.3-done", "7.3-cancel",
        "code", "code-wrong", "code-new", "code-weak", "code-confirm", "code-mismatch", "faceid", "about",
    )

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        state ?: return null
        val page = page(state)
        val prepare: (suspend () -> Unit)? = when {
            state.startsWith("7.1-access") -> {
                {
                    controls?.setRestoreAccessPending(true)
                    if (state == "7.1-access-unanswered") controls?.restoreAccessAnswer = RestoreAccessCheck.Unanswered
                }
            }
            state == "erase-failed" -> {
                { controls?.failsAccountDeletion = true }
            }
            state == "7.1-blocked" || state == "blocked" -> {
                { controls?.loadBlockedPeople() }
            }
            else -> null
        }
        return HarnessPlan(
            start = StartScreen.Chats,
            stack = listOfNotNull(AppRoute.Settings, page),
            uiState = SettingsUiState(state),
            prepare = prepare,
        )
    }

    /** The screen [state] opens over 7.1, `null` for 7.1 itself. */
    fun page(state: String): AppRoute? = when (state.substringBefore('-')) {
        "presence" -> AppRoute.Picker(SettingsPicker.Presence)
        "autolock" -> AppRoute.Picker(SettingsPicker.AutoLock)
        "ghost" -> AppRoute.Picker(SettingsPicker.DefaultGhost)
        "disappearing" -> AppRoute.Picker(SettingsPicker.DefaultDisappearing)
        "theme" -> AppRoute.Picker(SettingsPicker.Theme)
        "language" -> AppRoute.Picker(SettingsPicker.Language)
        "7.2" -> AppRoute.BackupExport
        "7.3" -> AppRoute.Page(SettingsPage.Restore)
        "code" -> AppRoute.Page(SettingsPage.ChangeCode)
        "faceid" -> AppRoute.Page(SettingsPage.EnableFaceId)
        "about" -> AppRoute.Page(SettingsPage.About)
        "blocked" -> AppRoute.Page(SettingsPage.Blocked)
        else -> null
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val settingsHarnesses: List<FeatureHarness> = listOf(SettingsHarness)
