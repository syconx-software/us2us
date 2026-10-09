import SwiftUI

/// The single place where onboarding mounts 7.3 · Yedekten geri yükle
/// (`RestoreBackupView`, built by the settings feature): 1.1 “Yedekten geri
/// yükle” pushes it; it opens the system document picker by itself, asks for
/// the backup password and restores on the device. When it finishes,
/// onboarding continues with a new secret code (2.2 ×2 → 2.3 → 4.1).
struct OnboardingRestoreHook: View {
    let onCancel: () -> Void
    let onFinished: () -> Void

    var body: some View {
        RestoreBackupView(opensPickerOnAppear: true, onClose: onCancel, onFinished: onFinished)
    }
}
