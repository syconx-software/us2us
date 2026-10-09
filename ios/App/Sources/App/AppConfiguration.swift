import Foundation

/// Launch configuration. Every flag is a launch argument so previews, UI tests
/// and design comparisons can start the app in an exact, repeatable state
/// (`xcrun simctl launch … com.syconx.us2us -U2Route conversation:deniz/5.3 -U2Theme light`).
///
/// | argument | values | effect |
/// |---|---|---|
/// | `-U2Route` | see `LaunchRoute` | opens any screen, in any drawn state (states: DEBUG) |
/// | `-U2Start` | `welcome` · `locked` · `chats` | first screen (default: from the route, else `welcome` on a fresh fixture install) |
/// | `-U2Theme` | `system` · `dark` · `light` | overrides the saved theme for this launch |
/// | `-U2Language` | `system` · `tr` · `en` | overrides the saved language for this launch |
/// | `-U2FrozenClock` | `YES` | freezes time at the design's moment (8 Oct 2026, 18:52) — counters show 23:41:07, rings and packets pause like the references |
/// | `-U2GhostUrgent` | `YES` | the design's “Ghost — last hour” variant (deniz 00:42:10) |
/// | `-U2Gallery` | `YES` | opens the design-system gallery (DEBUG builds) |
/// | `-U2ResetPreferences` | `YES` | forgets saved theme / language |
/// | `-U2Fixtures` | `YES` | DEBUG: the design's data (`DesignFixturesService`) instead of the live service — previews, screenshots, UI tests |
/// | `-U2RelayURL` | `<url>` | DEBUG: a local relay (`http` only for loopback) instead of the production relay |
struct AppConfiguration: Sendable {
    enum Start: String, Sendable {
        case welcome
        case locked
        case chats
    }

    var start: Start = .welcome
    var theme: ThemePreference?
    var language: AppLanguage?
    var frozenClock = false
    var ghostUrgent = false
    /// `-U2Route`, parsed.
    var launchRoute: LaunchRoute?
    var showsGallery = false
    var resetPreferences = false
    /// The design's data instead of the live service (DEBUG only; Release
    /// always runs the live service).
    var usesFixtures = false
    /// The relay the live service talks to (the only network endpoint).
    var relayURL: URL = RelayEndpoint.production

    /// The main-stack screens the route opens (pushed without animation at
    /// start, or after the first unlock).
    var routePath: [AppRoute] { launchRoute?.path ?? [] }

    /// Release (App Store) builds read no launch argument at all: the live
    /// service, the production relay, the wall clock and the saved
    /// preferences only.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> AppConfiguration {
        #if DEBUG
        var config = AppConfiguration()
        config.launchRoute = defaults.string(forKey: "U2Route").flatMap(LaunchRoute.init(argument:))
        if let start = defaults.string(forKey: "U2Start").flatMap(Start.init(rawValue:)) {
            config.start = start
        } else if let implied = config.launchRoute?.impliedStart {
            config.start = implied
        }
        // The lock harness always starts locked (its states live in the lock).
        if config.launchRoute?.feature == .lock { config.start = .locked }
        config.theme = defaults.string(forKey: "U2Theme").flatMap(ThemePreference.init(rawValue:))
        config.language = defaults.string(forKey: "U2Language").flatMap { value in
            switch value {
            case "tr": .turkish
            case "en": .english
            case "system": .system
            default: nil
            }
        }
        config.frozenClock = defaults.bool(forKey: "U2FrozenClock")
        config.ghostUrgent = defaults.bool(forKey: "U2GhostUrgent")
        config.showsGallery = defaults.bool(forKey: "U2Gallery")
        config.resetPreferences = defaults.bool(forKey: "U2ResetPreferences")
        config.usesFixtures = defaults.bool(forKey: "U2Fixtures")
        config.relayURL = RelayEndpoint.debugOverride(defaults.string(forKey: "U2RelayURL")) ?? RelayEndpoint.production
        return config
        #else
        return AppConfiguration()
        #endif
    }
}

/// `-U2Route <feature>[:<target>][/<state>]` — one argument opens every
/// screen of the app, in every drawn state:
///
/// | route | opens |
/// |---|---|
/// | `onboarding:<state>` | 1.1–2.3, 7.3 from 1.1 (`1.1` … `2.3-error`, `7.3`, `restore-2.2`, `demo` …) |
/// | `lock:<state>` | 3.1–3.4 (`faceid` `touchid` `code` `typing` `wrong` `lastchance` `throttled` `forgot` `erase` `restore` `cover` `switcher`) |
/// | `chats` · `chats:<state>` | 4.1–4.4 (`4.1` `4.1-empty` `4.2` `4.2-notfound` `4.3` `4.4-link` …) |
/// | `newChat` · `newChat:<username>` | the 4.2 sheet (with that query) |
/// | `requests` · `request:<id>` | 4.3 · 4.4 over 4.3 |
/// | `conversation:<chat>[/<state>]` | 5.1–5.6 (`deniz/5.3`, `mert.k/5.2-ended` …) |
/// | `safety:<chat>[/<state>]` | 6.1–6.3, add by QR (`deniz/scan`, `deniz/verified`, `deniz/contact-scan` …) |
/// | `settings` · `settings:<state>` | 7.1–7.3, pickers, code change, Hakkında (`7.2`, `7.3-wrong`, `code-new`, `theme` …) |
/// | `backup` | 7.2 over 7.1 |
/// | `invite` · `invite:<state>` | the invite sheet over 4.1 (`edit` `edited` `copied` `sent` · `link` `link-unknown`: an invite link arrives; see `InviteHarness`) |
///
/// Without `-U2Start` the route picks the start: `onboarding` → welcome,
/// `lock` → locked, everything else → chats. The full state lists are in
/// README › Launch arguments and in each feature's harness. States are read
/// only by DEBUG builds; Release opens the screen in its plain state.
struct LaunchRoute: Equatable, Sendable {
    enum Feature: String, Sendable, CaseIterable {
        case onboarding, lock, chats, newChat, requests, request, conversation, safety, settings, backup, invite
    }

    let feature: Feature
    /// The chat, request or username a route is about.
    let target: String?
    /// The drawn state (a harness id such as `5.3` or `7.2-empty`).
    let state: String?

    /// The process's route (`-U2Route`), parsed once.
    /// Always `nil` in Release builds (no launch arguments there).
    static let current: LaunchRoute? = {
        #if DEBUG
            UserDefaults.standard.string(forKey: "U2Route").flatMap(LaunchRoute.init(argument:))
        #else
            nil
        #endif
    }()

    init(feature: Feature, target: String? = nil, state: String? = nil) {
        self.feature = feature
        self.target = target
        self.state = state
    }

    init?(argument: String) {
        let parts = argument.split(separator: ":", maxSplits: 1).map(String.init)
        guard let name = parts.first, let feature = Feature(rawValue: name) else { return nil }
        let rest = parts.count == 2 && !parts[1].isEmpty ? parts[1] : nil
        switch feature {
        case .conversation, .safety, .request:
            guard let rest else { return nil }
            let pieces = rest.split(separator: "/", maxSplits: 1).map(String.init)
            self.init(feature: feature, target: pieces[0], state: pieces.count == 2 ? pieces[1] : nil)
        case .newChat:
            self.init(feature: feature, target: rest ?? "")
        case .onboarding, .lock, .chats, .requests, .settings, .backup, .invite:
            self.init(feature: feature, state: rest)
        }
    }

    /// The harness state when the route is `feature`'s.
    func state(for feature: Feature) -> String? {
        self.feature == feature ? state : nil
    }

    var impliedStart: AppConfiguration.Start {
        switch feature {
        case .onboarding: .welcome
        case .lock: .locked
        default: .chats
        }
    }

    /// The main-stack screens under 4.1.
    var path: [AppRoute] {
        switch feature {
        case .conversation: target.map { [.conversation($0)] } ?? []
        case .safety: target.map { [.safetyNumber($0)] } ?? []
        case .requests: [.requests]
        case .request: target.map { [.requests, .requestPreview($0)] } ?? []
        case .settings: [.settings]
        case .backup: [.settings, .backupExport]
        case .onboarding, .lock, .chats, .newChat, .invite: []
        }
    }
}

/// Device-level preferences that must be readable before unlocking (the lock
/// screen is themed and localized too): theme and language. Not sensitive,
/// so they live in UserDefaults; everything else is in the vault.
@Observable
@MainActor
final class AppPreferences {
    private let defaults: UserDefaults
    private static let themeKey = "u2.theme"
    private static let languageKey = "u2.language"

    var theme: ThemePreference {
        didSet { defaults.set(theme.rawValue, forKey: Self.themeKey) }
    }

    var language: AppLanguage {
        didSet { defaults.set(language.rawValue, forKey: Self.languageKey) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = defaults.string(forKey: Self.themeKey).flatMap(ThemePreference.init(rawValue:)) ?? .system
        language = defaults.string(forKey: Self.languageKey).flatMap(AppLanguage.init(rawValue:)) ?? .system
    }

    func reset() {
        defaults.removeObject(forKey: Self.themeKey)
        defaults.removeObject(forKey: Self.languageKey)
        theme = .system
        language = .system
    }
}
