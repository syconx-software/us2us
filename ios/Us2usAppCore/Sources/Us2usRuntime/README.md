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
* File protection is set explicitly on every file and directory the app
  writes (complete for the vault, its attempt counter and settings and the
  app's directories, whose new files inherit it; another class only where
  background work needs one, §5.3). The entitlement
  `com.apple.developer.default-data-protection = NSFileProtectionComplete`,
  which would also make "complete" the default for files created by system
  frameworks, is **not** set: the App ID `com.syconx.us2us` does not have the
  Data Protection capability (its development and App Store profiles do not
  carry the entitlement), so adding it means enabling that capability for the
  App ID first (Certificates, Identifiers & Profiles › Identifiers ›
  com.syconx.us2us › Data Protection › Complete Protection), then adding the
  key to `ios/App/project.yml` (`entitlements.properties`) and
  `ios/App/Config/us2us.entitlements`.
* Vault format v2 (§5.2) needs no entitlement: the Secure Enclave key is a
  CryptoKit key whose blob is a Keychain item of `KeychainItemStore`, next to
  the device secret.
* Hide conversation content while `runtime.isScreenCaptured` is true.
* Bind to `lockState`, `unlockStatus` ("N more wrong codes → wait X"),
  `quickUnlock`, `connection.line()` (the "hat" line), `chats`, `requests`,
  `blockedContacts` (everyone blocked, also without a conversation),
  `openMessages`, `downloadProgress` (attachments of the open conversation
  downloading: fraction received, at most every 2 %), `presence`, `typing`,
  `banner`, `settings`, `lastBackupAt` (7.1's last backup; call
  `recordBackupSaved(at:)` once the share sheet reports the exported file
  saved: it is kept in the encrypted runtime state, `nil` while locked).
* `tokenRotationPending`: a restore's token rotation got no answer yet
  (§5.5 step 3.4), so the old phone may still have access and this phone
  does not connect. Every connection attempt asks the relay first (from the
  first unlock after a launch on); `checkPendingTokenRotation()` asks at once
  (`.settled`, `.unanswered`, `.refused`: the line then ends as `.revoked`).
  `.settled` only when the relay confirmed this phone's token: a `400`
  keeps the notice (a fresh token is pending, step 3.5), and a refusal is
  reported as `.refused` also after the connection's own attempt saw it.
* `unlock(code:)` / `verifyCode(_:)` / `changeCode` with the optional wipe
  after 10 wrong codes: the error is `UnlockError.vault(.wiped)` (or
  `VaultError.wiped`) and everything is already erased (`lockState ==
  .needsSetup`); say so, never "wrong code" (§5.2).
* Live location: a share this process can no longer send (the previous
  process ended while sharing) gets its normal stop when the engine starts
  (§8.3); sharing is never resumed by itself. Only rows that carry a
  live-location session are read for this (`outgoingLiveLocations`), so the
  check costs nothing noticeable at unlock.
* `quickUnlockAvailable`: Face ID / Touch ID can be turned on now (enrolled,
  not locked out); ask before asking for the code. `enableQuickUnlock(code:)`
  is a counted attempt and refreshes `unlockStatus` either way.
* Media: prefer `mediaData(for:peer:)` and `openViewOnceData(_:peer:)`
  (bytes in memory) for images and video (the app plays video from memory);
  `mediaFile(for:peer:)` / `openViewOnce` write a staging file only for
  players and the share sheet that need one. A view-once item is
  destroyed (and VIEWED queued) as it opens; it takes no caption
  (`EngineError.notAllowed`) and shows no thumbnail before it is opened.
* `prepareFile` is `async`: photos and videos picked as documents lose their
  metadata too (§7).
* Voice notes: `prepareVoiceNote(_:durationMs:waveform:)` takes the
  recording from memory (AAC in M4A, up to 100 waveform samples) and stages
  it for `sendMedia`, which moves it into encrypted storage at once. The
  first play of a received one is `markVoiceNotePlayed(_:peer:)`: its
  `MessageItem.viewedAt` is set, and VIEWED goes to the sender only where a
  read receipt would (§8.4); the sender's item then has `viewedAt` too.
  VIEWED destroys only view-once items.
* Saved media (§8.4): after a photo, video or file was saved to Photos or
  Files, call `markMediaSaved(_:peer:)`. It sets `MessageItem.savedByMeAt`
  once and, for a received item from an accepted contact, sends SAVED —
  always, also with read receipts off, so tell the user before saving. The
  sender's item then has `savedByPeerAt`. View-once, disappearing and
  deleted items cannot be saved (`EngineError.invalidMessage`); a SAVED for
  anything but our own permanent photo, video or file is ignored.
* Media of a conversation: `mediaMessages(with:kind:before:limit:)` lists
  photos and videos (`.visual`) or files (`.documents`), newest first; pass
  the last item's id as `before` for the next page. View-once,
  disappearing, deleted and expired items are never listed (a list kept
  outside the conversation must never show one after its deadline).
  `messageRevisions[peer]` grows whenever that conversation's messages
  change, also while it is not open: read the list again then.
* Built-in stickers (§8.8): `sendSticker(packId:stickerId:emoji:lang:quote:to:)`,
  with `lang` (“tr”, “en”) only for a sticker with words; a received
  `StickerInfo` carries `lang` as it came (empty from older senders).
  `recentStickers()` / `setRecentStickers(_:)` / `noteRecentSticker(_:)`
  keep the picker's recents (at most 12 short ids the app chose) in the
  encrypted runtime state — never in UserDefaults. Resending an old
  `us2us.text` sticker sends `words.ok` (109) instead.
* "Delete my account" is `deleteAccountAndEraseEverything()` (throws, and
  erases nothing, when the relay does not confirm); `eraseEverything()` only
  erases this phone. `deleteAccountOnRelay()` is its first half alone, for a
  UI that erases once its confirmation has gone (the app's 7.1).

## Layout

| Area | Files |
|---|---|
| Facade, lifecycle, grace period | `Facade/AppRuntime*.swift`, `RuntimeEnvironment.swift`, `Providers.swift` |
| Vault, Face ID / Touch ID | `Vault/VaultController.swift`, `Biometrics.swift`, `QuickUnlockPolicy.swift` |
| Vault format v2 (Secure Enclave step per guess) | `Vault/SecureEnclaveVaultKey.swift` |
| Keychain (device secret, the v2 key's blob) | `Keychain/SecureItemStore.swift` |
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
