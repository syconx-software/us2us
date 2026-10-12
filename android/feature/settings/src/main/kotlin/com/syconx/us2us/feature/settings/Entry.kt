package com.syconx.us2us.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.nav.RestoreMode
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.ui.nav.LocalRouter
import java.net.URI

/** 7.1 Ayarlar. */
@Composable
fun SettingsRootScreen(modifier: Modifier = Modifier) {
    SettingsRootView(modifier)
}

/** 7.1 › the picker for [kind]. */
@Composable
fun SettingsPickerScreen(kind: SettingsPicker, modifier: Modifier = Modifier) {
    SettingsPickerView(kind, modifier)
}

/** 7.1 › [kind]: Gizli kodu değiştir, Face ID, Hakkında, Engellenenler, (debug) 7.3. */
@Composable
fun SettingsPageScreen(kind: SettingsPage, modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    when (kind) {
        SettingsPage.ChangeCode -> ChangeCodeScreen(ChangeCodeModel.Purpose.ChangeCode, modifier)
        SettingsPage.EnableFaceId -> ChangeCodeScreen(ChangeCodeModel.Purpose.EnableBiometrics, modifier)
        SettingsPage.About -> AboutScreen(modifier)
        SettingsPage.Blocked -> BlockedPeopleScreen(modifier)
        SettingsPage.BackupExport -> BackupExportView(modifier)
        SettingsPage.Restore -> RestoreBackupView(onClose = router::pop, onFinished = router::pop, modifier = modifier)
    }
}

/** 7.2 Yedeği dışa aktar. */
@Composable
fun BackupExportScreen(modifier: Modifier = Modifier) {
    BackupExportView(modifier)
}

/**
 * 7.3 Yedekten geri yükle, opened from [mode] (1.1, 3.3, or the settings harness). With [file] (3.3
 * picked the backup first, as on iOS) it goes straight to the password; without, it opens the system
 * document picker by itself. [onFinished] continues with a new secret code (2.2 ×2 → 2.3).
 */
@Composable
fun RestoreBackupScreen(
    mode: RestoreMode,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    file: URI? = null,
) {
    // Every entry point behaves the same: what a restore replaces follows from the service (an account on this phone or not).
    // In the lock, 3.3 picks the backup itself first; 7.3 there never opens the picker on its own.
    RestoreBackupView(
        onClose = onClose,
        onFinished = onFinished,
        modifier = modifier,
        opensPickerOnAppear = file == null && mode != RestoreMode.Lock,
        file = file,
    )
}
