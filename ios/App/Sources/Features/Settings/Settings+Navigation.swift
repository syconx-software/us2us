import SwiftUI

/// Shared state of the settings feature (one per app model): the service
/// and the confirmation toast shown on 7.1.
@Observable
@MainActor
final class SettingsFlow {
    let messenger: any MessengerService
    /// A short confirmation on 7.1 (“Gizli kodun değişti.”, “Yedek kaydedildi.”).
    var toast: Text?
    /// 7.1 › “Her şeyi sil”: the account on the relay, then this phone.
    let erasure: EraseAccountModel
    /// The notice after a restore whose token rotation is unanswered (7.1, 4.1).
    let restoreAccess: RestoreAccessModel

    private init(messenger: any MessengerService) {
        self.messenger = messenger
        erasure = EraseAccountModel(messenger: messenger)
        restoreAccess = RestoreAccessModel(messenger: messenger)
    }

    private static var flows: [ObjectIdentifier: SettingsFlow] = [:]

    static func of(_ model: AppModel) -> SettingsFlow {
        let key = ObjectIdentifier(model)
        if let flow = flows[key] { return flow }
        let flow = SettingsFlow(messenger: model.messenger)
        flows[key] = flow
        return flow
    }

    /// Pushes a settings page on the main stack with the soft `screenPush`.
    func open(_ page: SettingsPage, in model: AppModel) {
        model.router.push(page == .backupExport ? .backupExport : .settingsPage(page))
    }
}

// MARK: - DEBUG screen harness

#if DEBUG
/// Opens any 07 screen in an exact state for screenshots:
///
///     -U2Route settings:<state> [-U2Theme light] [-U2Language en] [-U2FrozenClock YES]
///
/// pushes 7.1 and then (with the normal soft push) the page in `state`.
/// `setPath` is avoided on purpose: it re-creates every entry, which would
/// reset 7.1's state.
///
/// | state | screen |
/// |---|---|
/// | `7.1` | 7.1 Ayarlar (= plain `settings`) · `7.1-end` scrolled to the end (Hakkında, Her şeyi sil) · `7.1-access` with the notice of an unanswered token rotation after a restore (`7.1-access-checking`, `7.1-access-unanswered`) · `7.1-blocked` with bora and lale blocked (Engellenenler · 2) |
/// | `blocked` · `blocked-empty` | 7.1 › Engellenenler (proposed): bora blocked (chat in 4.1) and lale (no chat) / nobody blocked |
/// | `qr` · `erase` · `erase-failed` | 7.1 with the own-QR sheet / the erase confirmation / “Hesap silinemedi.” (the fixtures' relay stays unreachable) |
/// | `avatar` · `avatar-crop` | 7.1 with “Profil görseli” (proposed) / and the framing screen with the night-pier painting |
/// | `presence` `autolock` `ghost` `disappearing` `theme` `language` | the 7.1 pickers |
/// | `7.2` | 7.2 as drawn (14-character password, “Güçlü”, ring) |
/// | `7.2-empty` `7.2-weak` `7.2-generated` `7.2-revealed` `7.2-copied` `7.2-confirm` `7.2-mismatch` `7.2-exporting` | 7.2 states (`generated`: the password hidden; `revealed`: shown; `copied`: shown, with the copy confirmation) |
/// | `7.3` | 7.3 as drawn (62 %, messages decrypting) |
/// | `7.3-pick` `7.3-password` `7.3-wrong` `7.3-done` `7.3-cancel` | 7.3 states |
/// | `code` `code-wrong` `code-new` `code-confirm` `code-weak` `code-mismatch` | code change steps |
/// | `faceid` | 7.1 › “Face ID ile aç” turned on while off: the code once (proposed) |
/// | `about` | Hakkında |
enum SettingsHarness {
    static let state: String? = LaunchRoute.current?.state(for: .settings)

    /// The harness acts once per launch.
    @MainActor static var didApply = false

    /// Initial state for a page's view model (consumed once).
    @MainActor static func take(prefix: String) -> String? {
        guard let state, state == prefix || state.hasPrefix(prefix + "-") else { return nil }
        guard !consumed.contains(prefix) else { return nil }
        consumed.insert(prefix)
        return state
    }

    @MainActor private static var consumed: Set<String> = []

    /// Opens the requested page from 7.1 (called once 7.1 is on screen).
    @MainActor static func apply(model: AppModel, flow: SettingsFlow) {
        guard !didApply, let state else { return }
        didApply = true
        let base = state.split(separator: "-").first.map(String.init) ?? state
        switch base {
        case "presence": model.router.push(.settingsPicker(.presence))
        case "autolock": model.router.push(.settingsPicker(.autoLock))
        case "ghost": model.router.push(.settingsPicker(.defaultGhost))
        case "disappearing": model.router.push(.settingsPicker(.defaultDisappearing))
        case "theme": model.router.push(.settingsPicker(.theme))
        case "language": model.router.push(.settingsPicker(.language))
        case "7.2": flow.open(.backupExport, in: model)
        case "7.3": flow.open(.restore, in: model)
        case "code": flow.open(.changeCode, in: model)
        case "faceid": flow.open(.enableFaceID, in: model)
        case "about": flow.open(.about, in: model)
        case "blocked":
            if state == "blocked" { (flow.messenger as? DesignFixturesService)?.loadBlockedPeople() }
            flow.open(.blocked, in: model)
        default: break
        }
    }
}
#endif
