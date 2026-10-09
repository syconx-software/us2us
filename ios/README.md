# iOS — Us2usKit

`Us2usKit` is the Swift package with everything protocol- and crypto-related
for the us2us iOS app: BRP (docs/PROTOCOL.md) on top of libsignal's official
Swift binding, the relay client, the secret-code vault and local storage
crypto. The SwiftUI app (`ios/App`) is a thin layer on top of it, through
`ios/Us2usAppCore`.

> **Türkçe özet** bu dosyanın sonunda.

```
ios/
  scripts/fetch-libsignal.sh   vendors libsignal (prebuilt FFI + matching Swift sources)
  scripts/gen-proto.sh         regenerates Proto/brp.pb.swift and the bundled secret-code list
  scripts/keychain-smoke.sh    checks the Keychain device secret on a Simulator (see "Keychain test")
  scripts/tls-probe.py         TLS server for the unlinkability test (see "TLS isolation test")
  Vendor/                      created by fetch-libsignal.sh, git-ignored
  Us2usKit/
    Package.swift
    Vendor -> ../Vendor        committed symlink (SwiftPM forbids target paths outside the package)
    Sources/Us2usKit/          the kit (see "Module tour")
    Tests/Us2usKitTests/       unit, session, tamper, fake-relay, live-relay and test-vector tests
```

## Requirements

* Xcode 27 (Swift 6.x toolchain, Swift 6 language mode, strict concurrency).
* iOS only: the package declares iOS 15 (libsignal's minimum); Xcode may raise
  the effective minimum to the oldest iOS it still supports.
* No Rust toolchain: libsignal comes from Signal's official iOS prebuild.

## 1. Vendor libsignal

```sh
ios/scripts/fetch-libsignal.sh            # download, verify, assemble (idempotent)
ios/scripts/fetch-libsignal.sh --force    # rebuild even if the stamp matches
```

The script pins **libsignal v0.105.0** (the same version as `android/core`,
PROTOCOL.md §2) and the SHA-256 of
`https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.105.0.tar.gz`.
It then

1. verifies the tarball checksum (cached in `~/Library/Caches/org.signal.libsignal`,
   the same cache Signal's CocoaPods integration uses);
2. clones `signalapp/libsignal` at tag `v0.105.0` and checks the podspec version;
3. `lipo`-merges the arm64 and x86_64 simulator slices;
4. assembles `Vendor/SignalFfi.xcframework` (device + simulator) with the
   `SignalFfi` headers and `module.modulemap` of the same tag. The Info.plist is
   written directly because `xcodebuild -create-xcframework` cannot read the
   LLVM-bitcode members Signal's LTO build leaves in the archives;
5. copies the `LibSignalClient` Swift sources of the same tag to
   `Vendor/LibSignalClient`, plus `LICENSE.libsignal` and
   `acknowledgments-ios.plist` (the app must show both);
6. writes `Vendor/libsignal.version`.

Offline or behind a proxy, point it at local copies:

```sh
LIBSIGNAL_TARBALL=/path/libsignal-client-ios-build-v0.105.0.tar.gz \
LIBSIGNAL_SRC=/path/to/libsignal-checkout-at-v0.105.0 \
ios/scripts/fetch-libsignal.sh
```

There is no macOS slice (Signal does not ship one), so `swift build` /
`swift test` on the Mac **do not work**; build and test for the iOS Simulator.

## 2. Generated sources

`Sources/Us2usKit/Proto/brp.pb.swift` and
`Sources/Us2usKit/Resources/common-secret-codes.txt` are generated from `spec/`
and committed. After changing `spec/proto/brp.proto` or
`spec/common-secret-codes.txt` run:

```sh
ios/scripts/gen-proto.sh
```

It uses `protoc` / `protoc-gen-swift` from `$PROTOC` / `$PROTOC_GEN_SWIFT` or
`PATH`; otherwise it downloads protoc 36.2 from Maven Central (SHA-1 checked)
and builds `protoc-gen-swift` from apple/swift-protobuf at the version pinned in
`Package.resolved`, so generator and runtime always match. The secret-code list
is copied rather than symlinked because SwiftPM resources must live inside the
package; `SecretCodeTests.bundledListMatchesSpec` fails if the copy drifts.

## 3. Tests

```sh
cd ios/Us2usKit
xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0'
```

(Name the OS when several runtimes have an "iPhone 17". Add
`-collect-test-diagnostics never` to skip the multi-minute `simctl diagnose`
that xcodebuild runs after a failure.) The suite uses Swift Testing and covers:

* every component in isolation (encodings, usernames, PoW, padding,
  attachments, vault, key slots, items, backups, store, safety numbers,
  localization, relay client against a stub server);
* full Swift↔Swift sessions without network: bundle → PreKeySignalMessage →
  replies, out-of-order delivery, replays, identity change on send and on
  receive, one-time Kyber key deletion, last-resort reuse rejection;
* tamper tests for sealed envelopes, attachments, vault, key slots, items and backups;
* `Messenger` end to end against an in-memory relay (`FakeRelay`) that enforces
  the request shapes of §4 (e.g. a sealed send carrying `X-Auth-Token` fails);
* **cross-implementation test vectors**: the tests write
  `spec/test-vectors/swift/*.json` (simulator processes can write to the host
  file system, so no copy step is needed) and verify every category present in
  `spec/test-vectors/kotlin/`; missing Kotlin categories are reported as skipped.
  Files are only rewritten when their content changes; `sealed.json` (HPKE is
  randomized) is kept while it still verifies. A failing Kotlin category after
  a spec change usually means one side has not regenerated yet; that is the
  point of the check.

**Live relay test.** `LiveRelayTests` runs registration, message request,
acceptance, HTTP and WebSocket delivery, typing and presence (ephemeral),
attachments, a username change, backup and restore on a "new phone" (token
rotation, unchanged safety number) and account deletion against a real relay.
It is skipped unless `RELAY_URL` is set. xcodebuild only forwards variables
with the `TEST_RUNNER_` prefix (and strips it):

```sh
TEST_RUNNER_RELAY_URL=http://127.0.0.1:3000 \
  xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0'
```

**Live interop with Kotlin.** Two more live suites talk to a client on the
other platform through a real relay, using the echo contract of the Kotlin
`us2us-bot` (android/README.md; `EchoContractTests` pins the formats offline):

* `LiveEchoBotTests` is the initiator: it registers, finds the bot by
  username, sends a message request with proof-of-work, receives ACCEPT and
  the bot's access key, sends Turkish text, an attachment and a location and
  checks the echoes and delivery receipts, exchanges profiles and presence,
  sends ghost-mode settings and a `ConversationWipe`, and deletes its
  account. Skipped unless `BOT_STATE` (the bot's `--state` file) is set too.
* `LiveEchoResponderTests` is the responder for the reverse direction: it
  registers, writes `{"accountId", "username"}` to `ECHO_STATE` and answers
  the Kotlin `LiveEchoBotTest` until its `ConversationWipe`
  (`ECHO_TIMEOUT_SECONDS`, default 300).

```sh
TEST_RUNNER_RELAY_URL=http://127.0.0.1:3000 TEST_RUNNER_BOT_STATE=/tmp/echo-bot.json \
  xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0' \
  -only-testing:Us2usKitTests/LiveEchoBotTests
```

`scripts/interop.sh` at the repository root runs both, with the relay and
the bot, in one command (root `README.md`).

**TLS isolation test.** `TLSIsolationTests` checks PROTOCOL.md §4.6
"Unlinkability" with the session factory `RelayClient` uses: anonymous
requests must never share a connection or resume a TLS session of the
authenticated session. It needs a local TLS server that reports connection
reuse and resumption per request, and is skipped otherwise:

```sh
ios/scripts/tls-probe.py 8443 &
TEST_RUNNER_TLS_PROBE_URL=https://127.0.0.1:8443 \
  xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0' \
  -only-testing:Us2usKitTests/TLSIsolationTests
```

Measured on iOS 27: connections are pooled per `URLSession`; TLS 1.2 sessions
are resumed within a `URLSession` and never by another one (with and without a
delegate, ephemeral and default configurations alike); TLS 1.3 sessions were
not resumed at all, so the probe speaks TLS 1.2.

**Keychain test.** Package tests run inside Apple's `xctest` runner, which has
no keychain entitlement in the Simulator (every `SecItem*` call returns
`errSecMissingEntitlement`, -34018). `KeychainDeviceSecretTests` probes for
that and is skipped there; it runs in app-hosted test targets and on devices.
To check the Keychain provider on the Simulator anyway, run

```sh
ios/scripts/keychain-smoke.sh            # optional argument: simulator name
```

It compiles `KeychainDeviceSecretProvider` with `scripts/keychain-smoke/main.swift`
into a minimal app that carries a keychain entitlement, runs it on the
Simulator and checks store/replace/delete, `AfterFirstUnlockThisDeviceOnly` and
that the item is not synchronizable (exit status 0 = pass). The vault logic
itself is fully tested with `InMemoryDeviceSecretProvider`.

## Module tour

| Area | Files | Spec |
|---|---|---|
| Encodings, account ids, libsignal addresses | `Support/Encoding.swift`, `Account/AccountId.swift` | §2 |
| Usernames (normalize, validate, PBKDF2 hash) | `Username/NormalizedUsername.swift` | §3 |
| Proof-of-work (all cores, cancellable) | `ProofOfWork/ProofOfWork.swift` | §4.2 |
| Relay client, WebSocket, JSON models, errors | `Relay/*` | §2.1, §4 |
| Pre-key generation, upload entities | `Keys/PreKeys.swift` | §4.3, §4.5, §5 |
| Protocol store protocol + in-memory reference | `Store/*` | §5 |
| Content padding, builders, limits, LWW settings | `Content/*` | §5.1, brp.proto |
| Sessions, sealed envelope, sender verification, message requests | `Session/*` | §5, §6 |
| Presence ("active now": eligibility, tracker, labels) | `Session/Presence.swift` | §8.6 |
| Contact profiles (complete updates, username claims) | `Session/ContactProfile.swift` | §3.3, §8.7 |
| Delivery receipts (which messages, batching, ≤ 1000 ids) | `Session/DeliveryReceipts.swift` | §8.4 |
| Attachments (streaming AES-CBC + HMAC, P(n)) | `Attachments/AttachmentCipher.swift` | §7 |
| Secret code, vault, device secret, attempt delays | `Vault/SecretCode.swift`, `VaultFormat.swift`, `LocalVault.swift`, `DeviceSecretProvider.swift` | §5.2 |
| dbKey, key slots, items, ghost wipe schedule | `Vault/ConversationKeys.swift` | §5.3, §8.2 |
| Account backup file | `Vault/AccountBackupFile.swift` | §5.5 |
| Safety numbers | `SafetyNumber/SafetyNumber.swift` | §5 |
| High-level facade | `Messenger/*` | all of the above |
| Turkish/English strings | `Localization/Localizer.swift`, `Resources/Localizable.xcstrings` | ARCHITECTURE "Languages" |

`NormalizedUsername` is deliberately not called `Username`: LibSignalClient
exports a `Username` type, and apps that implement a persistent store import
both modules.

## Consuming the kit from the app

* Add `ios/Us2usKit` as a local package and link the `Us2usKit` library.
  Run `scripts/fetch-libsignal.sh` once per checkout (CI: before building).
* **Startup order** (§5.4): unlock `LocalVault` → `KeySlotStore.destroyExpiredSlots()`
  → open the database with `LocalKeyDerivation.databaseKey(dek:)` →
  `Messenger.performMaintenance()` (also finishes any `LocalAccount.pendingSetup`
  left by registration or restore) → `relay.connect()` + `Messenger.run(_:deliver:)`
  until `queue_empty`, then live → `Messenger.sendPresence(PresencePolicy.online(), to:)`
  for eligible contacts (one request, §8.6). Pass an `AccountSaver` to
  `register`, `restore` and `init`: the messenger saves `LocalAccount` through it
  whenever it changes, before relying on the change. `run` retries unfinished
  relay setup after the backlog and with every `pong`.
* **Restore** (§5.5): `AccountBackupFile.import` → `Messenger.restore(from:…)`,
  which validates every field first, writes the contacts' identity keys as they
  were (an unaccepted change stays blocked), rotates the token, saves the
  account and uploads fresh keys. Then create the app's contacts from
  `backup.contacts` (`presence_sharing` included) and call
  `announceProfile(_:to:)` with every accepted contact. `makeBackup` takes the
  app's contacts and fills in their identity fields from the store.
* **Profiles and receipts**: keep a `ContactProfile` per contact and replace it
  with `applying(_:)` for every `ProfileUpdate` (§8.7); show its username as
  verified only after `usernameOwner(_:)` confirmed it, checked lazily when the
  conversation opens (§3.3). Feed stored messages to a `DeliveryReceiptBatch`
  and send what `drain()` returns (per contact, ≤ 1000 ids per receipt, §8.4).
* **Background** (§5.4): keep the socket for the 30-second grace period
  (`beginBackgroundTask`), then send `PresencePolicy.offline()` to
  `PresenceTracker.onlineContacts()` and close the connection. Run CPU-heavy
  calls (vault unlock, backup export) off the main thread.
* **Ephemeral sends** (typing, live-location ticks, presence) require the
  contact's access key; `Messenger.send(…, ephemeral: true)` refuses
  proof-of-work locally, so nothing ephemeral goes to message requests.
* **Persistent store**: implement `ProtocolStore` on SQLCipher with exactly the
  semantics of `InMemoryProtocolStore` (its tests are the contract). Commit
  the session-state changes of a decryption in the same transaction as the
  message stored by `deliver`; otherwise a crash between the two loses the
  message (libsignal rejects the redelivered copy as a duplicate).
* **No push, no Notification Service Extension.** PROTOCOL.md (change log
  2026-10-07) removed push notifications: the app receives only while open.
  Overdue ghost-mode wipes run from a `BGTaskScheduler` task or at the next
  launch via `KeySlotStore.destroyExpiredSlots()`, which needs no DEK.
  In-app banners use `Localizer.current.string(.newMessage)`.
* **Extensions and the App Group.** The App Group `group.com.syconx.us2us` is
  reserved for future extensions (e.g. share extension, widget). If one ever
  links the kit, do not link the static library into both binaries: create one
  embedded dynamic framework target in the app project that links `Us2usKit`,
  and link that framework from the app and every extension, so libsignal is in
  the bundle once (Signal uses one framework for its app and extensions for the
  same reason). Pass the shared keychain access group to
  `KeychainDeviceSecretProvider(accessGroup:)` and put vault and key-slot
  files in the group container.
* **Localization**: every user-facing string the kit returns (error
  descriptions, safety-number warnings, ghost-mode notices, `NEW_MESSAGE`)
  exists in `en` and `tr`; English is the fallback. The app keeps its own
  String Catalog for its UI.
* **Licences**: ship `Vendor/LICENSE.libsignal` and
  `Vendor/acknowledgments-ios.plist` in the app's acknowledgements (AGPL-3.0-only).

## Upgrading libsignal

libsignal has no stability guarantees (≈2 breaking releases a month), so
upgrade deliberately and together with Android:

1. Pick a tag that has the `libsignal-client-ios-build-v<ver>.tar.gz.sha256`
   release asset (not every tag has an iOS prebuild); read `RELEASE_NOTES.md`
   and the annotated tag message for Swift and store-protocol changes.
2. Update `LIBSIGNAL_VERSION` and `LIBSIGNAL_TARBALL_SHA256` (first field of the
   `.sha256` asset) in `scripts/fetch-libsignal.sh`.
3. `scripts/fetch-libsignal.sh --force`, fix compile errors, run the test suite.
4. Bump `android/core` to the same version, update "libsignal version" in
   PROTOCOL.md §2, and make sure both test-vector directories still verify.
5. If the Xcode used by Signal's release runner is newer than yours, linking
   may fail on the LTO bitcode; use a current Xcode.

---

## Türkçe özet

`Us2usKit`, us2us iOS uygulamasının protokol ve kriptografi katmanıdır: BRP
(docs/PROTOCOL.md), libsignal'in resmi Swift bağlaması, relay istemcisi, gizli
kod kasası ve yerel depolama şifrelemesi.

* **libsignal'i getir:** `ios/scripts/fetch-libsignal.sh`. Signal'in resmi iOS
  derlemesini (v0.105.0) indirir, SHA-256'yı doğrular, simülatör dilimlerini
  birleştirir, `Vendor/SignalFfi.xcframework` ve aynı etiketin Swift
  kaynaklarını oluşturur. İnternet yoksa `LIBSIGNAL_TARBALL` ve `LIBSIGNAL_SRC`
  ile yerel kopyalar verilebilir. `Vendor/` git'e girmez.
* **Kod üretimi:** `ios/scripts/gen-proto.sh`, `brp.proto`'dan Swift kodunu
  üretir ve `common-secret-codes.txt` listesini pakete kopyalar. İkisi de
  commit'lenir; liste kayarsa bir test başarısız olur.
* **Testler:** yalnızca iOS Simülatörü'nde çalışır (macOS dilimi yok, `swift test`
  çalışmaz):
  `xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0'`.
  Testler `spec/test-vectors/swift/` altına vektör yazar ve
  `spec/test-vectors/kotlin/` altındaki her dosyayı doğrular. Gerçek relay testi
  için `TEST_RUNNER_RELAY_URL=http://127.0.0.1:3000` verin. Kotlin yankı
  botuyla iki yönlü canlı test (`LiveEchoBotTests`, `LiveEchoResponderTests`)
  depo kökündeki `scripts/interop.sh` ile tek komutta çalışır. Keychain testi
  simülatördeki host'suz paket testlerinde yetki olmadığı için atlanır; bunun
  yerine `ios/scripts/keychain-smoke.sh` Keychain sağlayıcısını simülatörde
  yetkili küçük bir uygulamayla dener.
* **Gizli kod:** tüm yerel veriler kullanıcının hesap açarken belirlediği gizli
  koddan (ve cihaza bağlı bir sırdan) türetilen anahtarla şifrelenir; kullanıcı
  adı hiçbir anahtarda kullanılmaz, bu yüzden kullanıcı adı istenildiği zaman
  değiştirilebilir.
* **Yedekten geri yükleme:** yedek her alanıyla doğrulanır, yeni oturum
  anahtarı relay'den alınıp anahtar yüklemesinden önce kaydedilir
  (`AccountSaver`), yükleme başarılı olana kadar yeniden denenir; ardından
  `announceProfile` ile tüm kişilere profil gönderilir. Kabul edilmemiş bir
  güvenlik numarası değişikliği yeni telefonda da gönderimi engeller.
* **Bağlantı ayrımı:** gönderenin kimliğini gizleyen istekler ayrı bir
  `URLSession` üzerinden gider; bağlantı ve TLS oturumu kimlik doğrulamalı
  isteklerle paylaşılmaz (`TLSIsolationTests`, `ios/scripts/tls-probe.py`).
* **Uygulamada kullanım:** yerel paket olarak ekleyin. Push bildirimi ve
  Notification Service Extension yoktur; mesajlar yalnızca uygulama açıkken
  alınır. İleride bir uzantı kütüphaneyi kullanırsa, uygulama ve uzantılar tek
  bir dinamik framework üzerinden bağlanmalı (App Group: `group.com.syconx.us2us`).
* **Diller:** kütüphanenin gösterdiği her metin Türkçe ve İngilizcedir;
  varsayılan İngilizcedir.
* **libsignal güncellemesi:** betikteki sürüm ve SHA-256'yı değiştirin,
  `--force` ile çalıştırın, testleri koşun, Android tarafını aynı sürüme çekin.
