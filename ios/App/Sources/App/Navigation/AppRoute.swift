import Foundation

/// Screens pushed on the main stack (rooted at 4.1 Sohbetler) — flows.md › Map.
enum AppRoute: Hashable, Sendable {
    /// 5.1 Sohbet.
    case conversation(ChatID)
    /// 4.3 Mesaj istekleri.
    case requests
    /// 4.4 İstek önizleme.
    case requestPreview(RequestID)
    /// 6.1 Güvenlik numarası.
    case safetyNumber(ChatID)
    /// 7.1 Ayarlar.
    case settings
    /// 7.1 › a picker row (proposed: C13 cards with a check mark).
    case settingsPicker(SettingsPicker)
    /// 7.2 Yedeği dışa aktar.
    case backupExport
    /// 7.1 › Gizli kodu değiştir, Hakkında, (DEBUG) 7.3.
    case settingsPage(SettingsPage)

    /// Edge-swipe back is allowed everywhere on the main stack (flows.md › Back behaviour).
    var allowsSwipeBack: Bool { true }
}

/// Screens of onboarding (01–02), pushed after the paged welcome.
enum OnboardingRoute: Hashable, Sendable {
    /// 2.1 Kullanıcı adı.
    case username
    /// 2.2 Gizli kod.
    case secretCode
    /// 2.3 Geri dönüş yok.
    case noWayBack
    /// 7.3 Yedekten geri yükle (new device).
    case restore
}

/// The pages of section 07 beyond 7.1 itself and its pickers.
enum SettingsPage: Hashable, Sendable {
    /// 7.2 Yedeği dışa aktar (pushed as `AppRoute.backupExport`).
    case backupExport
    /// 7.1 › Gizli kodu değiştir: current code (3.2 layout) → new code ×2 (2.2 layout).
    case changeCode
    /// 7.1 › Hakkında: version, privacy, licences.
    case about
    /// 7.3 Yedekten geri yükle. Production entry points are 1.1 and 3.3;
    /// inside settings only the DEBUG harness opens it.
    case restore
}

/// The 7.1 rows that open a picker.
enum SettingsPicker: String, Hashable, Sendable, CaseIterable {
    case presence
    case autoLock
    case defaultGhost
    case defaultDisappearing
    case theme
    case language
}
