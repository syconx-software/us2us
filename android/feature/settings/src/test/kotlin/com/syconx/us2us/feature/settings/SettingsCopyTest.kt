package com.syconx.us2us.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What section 07 says, in both languages (iOS AccountDeletionTests.theCopySaysWhatHappens, RestoreHonestyTests.restoreCopy). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ShotHost.SDK])
class SettingsCopyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tr: U2Strings get() = U2Strings.of(context, Locale.forLanguageTag("tr-TR"))
    private val en: U2Strings get() = U2Strings.of(context, Locale.forLanguageTag("en-US"))

    @Test
    fun theEraseCopySaysWhatHappens() {
        assertEquals("Hesabın silinsin mi?", tr[R.string.settings_erase_confirm_title])
        assertEquals("Delete your account?", en[R.string.settings_erase_confirm_title])
        assertEquals("Hesabı ve her şeyi sil", tr[R.string.settings_erase_confirm_action])
        assertEquals("Delete account and everything", en[R.string.settings_erase_confirm_action])
        // The relay may have deleted the account just before the connection dropped: only this phone is known to be untouched.
        assertTrue(tr[R.string.settings_erase_failed_body].contains("Bu telefonda hiçbir şey"))
        assertTrue(en[R.string.settings_erase_failed_body].contains("Nothing on this phone"))
        val body = tr[R.string.settings_erase_confirm_body]
        assertTrue(body.contains("kullanıcı adın") && body.contains("geri alınamaz"))
        assertTrue(en[R.string.settings_erase_confirm_body].contains("No one will be able to reach you"))
        assertEquals("Hesap silinemedi.", tr[R.string.settings_erase_failed_title])
        assertEquals("Tekrar dene", tr[R.string.settings_erase_retry])
        assertEquals("Try again", en[R.string.settings_erase_retry])
        assertEquals("Yalnızca bu telefondan sil", tr[R.string.settings_erase_thisphone_title])
        assertEquals("Erase from this phone only", en[R.string.settings_erase_thisphone_title])
        assertTrue(tr[R.string.settings_erase_thisphone_body].contains("180 gün"))
        assertTrue(en[R.string.settings_erase_thisphone_body].contains("180 days"))
        // 3.3 (code forgotten) cannot sign in to the relay: its confirmation promises no account deletion.
        assertTrue(tr[R.string.common_erase_confirm_body].contains("Hesabın sunucuda kalır"))
        assertTrue(en[R.string.common_erase_confirm_body].contains("Your account stays on the server"))
    }

    @Test
    fun theRestoreCopySaysTheSameInBothLanguages() {
        for (id in listOf(R.string.settings_restore_note_account, R.string.settings_restore_note_account_replacing)) {
            assertTrue(tr[id].contains("mesaj geçmişi yedekte yok"))
            assertTrue(en[id].contains("message history isn't in the backup"))
        }
        assertTrue(tr[R.string.settings_restore_note_account_replacing].contains("bu telefondaki mesajlar silinir"))
        assertTrue(en[R.string.settings_restore_note_account_replacing].contains("the messages on this phone are erased"))
        for (id in listOf(R.string.settings_restore_error_damaged, R.string.settings_restore_error_nolongervalid, R.string.settings_restore_password_unverified)) {
            assertNotEquals(tr[id], en[id])
        }
    }

    @Test
    fun durationsAreSaidInWords() {
        assertEquals("1 dakika", SettingsFormat.duration(60.0, Locale.forLanguageTag("tr")))
        assertEquals("5 minutes", SettingsFormat.duration(300.0, Locale.ENGLISH))
        assertEquals("1 saat", SettingsFormat.duration(3_600.0, Locale.forLanguageTag("tr")))
        assertEquals("1 hour", SettingsFormat.duration(3_600.0, Locale.ENGLISH))
    }

    @Test
    fun theBackupCardSaysSizeDayAndTime() {
        val zone = ZoneId.of("Europe/Istanbul")
        val date = DesignFixtures.designBackupDate
        assertEquals("8 Ekim 2026", RestoreFormat.day(date, Locale.forLanguageTag("tr-TR"), zone))
        assertEquals("October 8, 2026", RestoreFormat.day(date, Locale.forLanguageTag("en-US"), zone))
        assertEquals("184 MB", U2Format.byteCount(DesignFixtures.backupEstimate.totalBytes, Locale.forLanguageTag("tr-TR")))
        assertEquals("us2us-yedek-2026-10-08.u2u", tr.get(R.string.settings_backup_filename, SettingsFormat.fileDate(DesignFixtures.referenceDate, zone)))
        assertEquals("us2us-backup-2026-10-08.u2u", en.get(R.string.settings_backup_filename, SettingsFormat.fileDate(DesignFixtures.referenceDate, zone)))
    }

    @Test
    fun theStrengthLabelsAndTheMaskAreNotThePassword() {
        assertEquals("Güçlü", tr[R.string.settings_backup_strength_strong])
        assertFalse(BackupExportModel.masked("v7qz-3kd9").any { it.isLetterOrDigit() })
    }
}
