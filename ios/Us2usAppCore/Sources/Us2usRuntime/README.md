# Us2usRuntime

The UI-free app runtime of us2us: vault lifecycle, relay connection,
send/receive semantics, timers and privacy guards, behind one `@Observable`
facade (`AppRuntime`) the SwiftUI app binds to. Built on `Us2usKit`
(protocol, crypto, relay client) and `Us2usStorage` (SQLCipher, key slots,
crypto-shredding). Normative behaviour: `docs/PROTOCOL.md`.

## Wiring it into the app

```swift
@main struct Us2usApp: App {
    @State private var runtime = AppRuntime(environment: try! .production())
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(runtime)
                .overlay { if runtime.isPrivacyCoverVisible { PrivacyCover() } }
        }
        .onChange(of: scenePhase) { _, phase in
            runtime.scenePhaseChanged(phase == .active ? .active : phase == .inactive ? .inactive : .background)
        }
        .backgroundTask(.appRefresh("com.syconx.us2us.wipe")) {
            AppRuntime.runBackgroundWipes(environment: try! .production())   // no DEK, no network
        }
    }
}
```

* Schedule the refresh task for `AppRuntime.nextBackgroundWipeDate(environment:)`.
* Info.plist: `NSFaceIDUsageDescription` (TR/EN). `UIBackgroundModes = location`
  only if live location ships (§8.3); nothing else runs in the background.
* Entitlement `com.apple.developer.default-data-protection = NSFileProtectionComplete`.
* Hide conversation content while `runtime.isScreenCaptured` is true.
* Bind to `lockState`, `unlockStatus` ("N more wrong codes → wait X"),
  `quickUnlock`, `connection.line()` (the "hat" line), `chats`, `requests`,
  `openMessages`, `presence`, `typing`, `banner`, `settings`.
* Media: prefer `mediaData(for:peer:)` and `openViewOnceData(_:peer:)`
  (bytes in memory) for images; `mediaFile(for:peer:)` / `openViewOnce` write a
  staging file only for players and the share sheet. A view-once item is
  destroyed (and VIEWED queued) as it opens; it takes no caption
  (`EngineError.notAllowed`) and shows no thumbnail before it is opened.
* `prepareFile` is `async`: photos and videos picked as documents lose their
  metadata too (§7).
* "Delete my account" is `deleteAccountAndEraseEverything()` (throws, and
  erases nothing, when the relay does not confirm); `eraseEverything()` only
  erases this phone.

## Layout

| Area | Files |
|---|---|
| Facade, lifecycle, grace period | `Facade/AppRuntime*.swift`, `RuntimeEnvironment.swift`, `Providers.swift` |
| Vault, Face ID / Touch ID | `Vault/VaultController.swift`, `Biometrics.swift`, `QuickUnlockPolicy.swift` |
| Keychain (device secret) | `Keychain/SecureItemStore.swift` |
| Relay connection | `Relay/ConnectionSupervisor.swift`, `Backoff.swift`, `MessagingBackend.swift` |
| Messaging semantics, outbox, presence, timers | `Messaging/MessagingEngine*.swift` |
| Notices, encrypted runtime state | `Messaging/RuntimeState.swift` |
| Media (resize, metadata stripping) | `Messaging/MediaPreparation.swift` |
| Privacy guards | `Privacy/*`, `Support/NetworkPolicy.swift`, `Support/RuntimeLog.swift`, `Support/SecretKeyMaterial.swift`, `Support/ProtectedFiles.swift` |

## Tests

```sh
cd ios/Us2usAppCore
xcodebuild test -scheme Us2usAppCore-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:Us2usRuntimeTests
# against a local relay (scripts/interop.sh shows how to start one):
TEST_RUNNER_RELAY_URL=http://127.0.0.1:3000 xcodebuild test … -only-testing:Us2usRuntimeTests
```

The Keychain test is skipped inside Apple's xctest runner (no keychain
entitlement on the Simulator), like Us2usKit's.
