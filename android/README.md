# us2us — Android / JVM

The Kotlin side of us2us: **`core`**, the protocol and crypto library that the Android app
will be built on, and **`cli`**, a small echo bot (`us2us-bot`) for live interop tests.
Everything here implements [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) (BRP v1); the Swift
counterpart is `ios/Us2usKit`, and the two are kept byte-compatible by the shared test vectors in
[`spec/test-vectors/`](../spec/test-vectors/README.md).

> **Türkçe özet** aşağıda: [Türkçe](#türkçe).

## Layout

```
android/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml   one version catalog
├── core/                         Kotlin/JVM library, package com.syconx.us2us.core
│   └── src/main/kotlin/com/syconx/us2us/core/
│       ├── AccountId.kt          UUID <-> 16 bytes, libsignal address (§2)
│       ├── Credentials.kt        AuthToken, AccessKey (redacted toString)
│       ├── encoding/             base64url without padding, hex
│       ├── crypto/               internal: PBKDF2, HKDF, AES-GCM, HMAC, constant-time compare
│       ├── username/             normalization, validation, PBKDF2 hash (§3); UsernameRules: the §3.1 rule
│       │                         as the apps check typed names (violations, no conversion)
│       ├── pow/                  parallel, cancellable proof-of-work solver + verifier (§4.2)
│       ├── keys/                 identity, signed / Kyber / one-time pre-keys, rotation and refill (§5)
│       ├── store/                ProtocolStore (libsignal stores with the §5 rules) + ProtocolStorage SPI
│       ├── session/              content padding, sessions, sealed envelope, sender verification (§5.1, §6)
│       ├── contacts/             contact model and message-request rules (§6.4)
│       ├── presence/             "active now" rules and tracker (§8.6)
│       ├── sticker/              built-in sticker catalog: ids, packs, TR/EN words and labels, `lang` rules (§8.8)
│       ├── profile/              profile pictures: preset and photo rules, avatar record key and format (§8.9, §8.10)
│       ├── text/                 internal: Unicode White_Space trimming shared by usernames and codes
│       ├── attachment/           streaming AES-CBC + HMAC with integer padding P(n) (§7)
│       ├── vault/                six-digit secret code, backup password, vault, key slots, items, backup file (§5.2–5.5)
│       ├── safety/               safety numbers (libsignal fingerprint v2)
│       ├── relay/                RelayClient (every §4 endpoint), RelaySocket (§4.7), JSON models
│       └── messenger/            Messenger: the high-level facade used by the bot and the app
│   ├── src/testFixtures/         FakeRelay (in-memory §4 relay on MockWebServer), TestClient
│   └── src/test/                 unit, session, end-to-end and test-vector tests
└── cli/                          us2us-bot (application plugin)
```

The protobuf schema is not copied: `core` generates Java-lite and Kotlin-lite classes from
`../spec/proto/brp.proto`, and `spec/common-secret-codes.txt` (six-digit codes) and
`spec/common-backup-passwords.txt` are copied into the library's resources at build time.

## Build and test

Requirements: a JDK ≥ 21 to run Gradle (libsignal's Gradle metadata requires JVM 21 consumers;
the build emits Java 21 bytecode), and network access to Maven Central, Google and
`build-artifacts.signal.org` on first build. `org.signal` artifacts are resolved only from
Signal's repository (`exclusiveContent` in `settings.gradle.kts`), never from Maven Central or
Google. The Gradle wrapper pins Gradle 9.2.1.

```sh
cd android
./gradlew test                        # all unit and end-to-end tests (core + cli)
./gradlew :core:generateTestVectors   # rewrite spec/test-vectors/kotlin/*.json only
./gradlew :cli:installDist            # build/install/us2us-bot/bin/us2us-bot
```

What `./gradlew test` covers:

* every component on its own (encodings, PBKDF2/HKDF against RFC vectors, usernames (`UsernameRules`
  against the case table `spec/username-rules.json` shared with the iOS app), PoW,
  padding, keys and their JSON, stores, sealed envelopes, attachments, vault, safety numbers,
  `RelayClient` against MockWebServer including the WebSocket);
* two in-memory parties talking through libsignal: pre-key message → reply → many rounds,
  out-of-order delivery, duplicates, identity change detection, one-time Kyber deletion,
  last-resort reuse rejection, tampered bundles;
* the `Messenger` end to end against `FakeRelay`: registration with PoW, username lookup,
  message request → accept → access-key exchange, texts both ways, attachment
  upload/download/delete, stickers with their `lang`, receipts (also split for large backlogs; SAVED
  for saved photos, videos and files through `sendSavedReceipt` — only to the account the item came
  from; the app checks that it still holds the item undeleted — and `SavedMedia`), typing, presence, blocking
  with key rotation and unblocking, HTTP fetch, backup and restore (also with a failing network mid-way),
  username change, pre-key refill, profile pictures (in `ProfileUpdate`s and through the avatar
  record for search: "Show in search", a username change, a restore answered `409 stale_version`,
  `429`, a relay without avatar endpoints, the largest photo). `FakeRelay` fails the test if the client ever sends its auth
  token on a request that hides the sender (sealed sends, ephemeral batches, message PoW
  challenges, media download/delete) or sends such a request over a connection that also carried
  authenticated requests, and it can inject failures to test recovery;
* the echo bot against `FakeRelay`: the same conversation `LiveEchoBotTest` has with a live
  responder, and the exact answer formats; with `--data`, the owner-only backup of the first start,
  a restart that keeps the account, its contacts and the answers, the rotated token saved before a
  restore returns (also when the key upload after it fails), and a backup of another account refused;
* test vectors: generates all categories into `spec/test-vectors/kotlin/` (deterministic; sealed
  envelopes are randomized by HPKE, so an existing, still-valid `sealed.json` is kept) and verifies
  every file in `spec/test-vectors/swift/`. A missing Swift file is reported as *skipped*.

### Live test against a real relay

`LiveRelayTest` runs the same conversation scenario against a running relay and is skipped unless
`RELAY_URL` is set:

```sh
RELAY_URL=http://127.0.0.1:3000 ./gradlew :core:test --tests '*LiveRelayTest'
```

Each run registers two new accounts, so a local test relay should run with
`RATE_LIMIT_REGISTER=off` (the default allows 5 registrations per hour and IP). Lower PoW
difficulties (`POW_DIFFICULTY_REGISTER`, `POW_DIFFICULTY_MESSAGE`) speed it up but are not required.

### us2us-bot

```sh
./gradlew :cli:installDist
RELAY_URL=http://127.0.0.1:3000 cli/build/install/us2us-bot/bin/us2us-bot \
    --username echo_bot --state /tmp/echo-bot.json
```

It registers a new account (keys in memory only), atomically writes
`{"accountId": "...", "username": "..."}` to `--state` and accepts every message request. Each
message is answered with one text (the echo contract, `Echo` in `EchoBot.kt`; the iOS
`LiveEchoResponderTests` answers the same way):

| Received | Answer |
|---|---|
| text | `echo: <text>` |
| attachment | `echo-attachment: <sha256 hex of the plaintext>`, after downloading, verifying, decrypting and deleting it |
| location | `echo-location: <lat>,<lon>` with six decimals, then ` <name>` if it has one |
| `ConversationSettings` | `echo-settings: ghost=<on\|off>,timeout=<s>,wipe_contact=<true\|false>,expire=<s>` |
| `ConversationWipe` | `echo-wipe: <reason as in brp.proto>`; the bot keeps no history, so there is nothing to erase |

Receipts, access keys, profiles and presence are handled by `Messenger` (an eligible contact's
ONLINE is answered once). Logs go to stderr and never contain message contents. A single failed
answer is logged and the bot keeps running.

`LiveEchoBotTest` drives such a running responder through the relay: a message request in Turkish,
ACCEPT and access key, profiles, an attachment and a location with their delivery receipts,
presence, ghost settings, a wipe, then it deletes its account. It is skipped unless both variables
are set:

```sh
RELAY_URL=http://127.0.0.1:3000 US2US_BOT_STATE=/tmp/echo-bot.json ./gradlew :cli:test --tests '*LiveEchoBotTest'
```

`US2US_BOT_STATE` may also be the state file of the iOS responder. `scripts/interop.sh` at the
repository root runs both directions, iOS ↔ Kotlin, against one relay (root `README.md`).

#### A bot that survives restarts: `--data`

Without `--data` every run is a new account, so after a restart the username still belongs to the
old one and registration fails with `username_taken`. A bot that runs for good (the one App Store
review talks to, deployed with [`scripts/deploy-bot.sh`](../scripts/README.md)) keeps its account
in a directory:

```sh
cli/build/install/us2us-bot/bin/us2us-bot --username us2us_echo --data ~/us2us-echo --relay https://relay.us2us.app
```

* **What is kept** is the app's own account backup (§5.5, `Messenger.exportBackup`): `account.brpb`,
  encrypted under a random password in `backup-password` that the first start generates (256 bits,
  never logged). The directory is `0700`, the files `0600`; every write goes to a temporary file
  that is synced and atomically renamed. A `lock` file keeps a second bot off the directory.
* **The first start** registers and writes the backup before the receive loop starts. **Later
  starts** restore it (`--username` may then be left out); a backup of another account than
  `--username` is refused (exit status 2) before the relay is asked, so nothing changes. `--state`
  is optional with `--data`.
* **The token**: a restore rotates the auth token and revokes the backup's. The bot is the
  `AccountStore` of its messenger, so the new token (like any change to what the backup holds of the
  account, e.g. a rotated access key) is written before the messenger goes on; setup steps and the
  avatar version are not in the backup and cost no write.
* **Contacts**: an accepted request, a contact's new access key or profile, a block, are written
  two seconds later, several changes in one write (each takes about a second of PBKDF2), and on
  shutdown (SIGINT/SIGTERM). A shutdown while the relay is creating the account or rotating its
  token waits until the account is written. A failed write is logged and retried; the bot keeps
  running.
* **After a restart** the bot has no sessions (a backup never holds them): once the backlog is in,
  it sends its profile to every contact, which starts new ones. Messages sent to it while it was
  down, or before that profile arrived, are not answered; the next one is.
* **Exit status** 2 when starting again cannot help: a usage error, a taken username, a backup of
  another account, one that does not open, or a token the relay refuses (the account was restored
  elsewhere). 1 when it may, e.g. the relay is unreachable or another bot still holds the directory
  (the bot waits 15 s for it first). `scripts/deploy-bot.sh` tells pm2 to restart on 1 only.

## Using `core`

```kotlin
val alice = Messenger.register(Username("alice"), RelayClient("https://relay.us2us.app"), accountStore = vault::save)
val bob = alice.findByUsername("bob") ?: error("no such user")
alice.sendText(bob.accountId, "Merhaba!")          // a message request, delivered with PoW
alice.runReceiveLoop { message ->                  // ack happens after this returns
    store(message)                                 // persist before returning
}
// At the end of the 30 s background grace period (§5.4):
alice.announceOffline()                            // then cancel the receive loop
```

Design points worth knowing:

* **Stores are swappable.** `ProtocolStore` implements libsignal's identity, pre-key, signed
  pre-key, Kyber and session stores and owns every rule of §5 (delete one-time Kyber keys on use,
  keep last-resort keys, reject a reused `(kyberId, signedId, baseKey)`, trust on first use).
  Persistence goes through the small `ProtocolStorage` interface, which only stores bytes, so the
  app's SQLCipher implementation cannot get the semantics wrong. `ContactStore` and `AccountStore`
  follow the same idea.
* **Identity changes** are accepted for receiving but block sending (`IdentityChangedException`)
  until `acceptIdentityChange`; `identityChanges` emits the affected contacts.
* **The relay never links sender and recipient through the client**: sealed sends, ephemeral
  batches, message PoW challenges and media download/delete carry no auth token and go through a
  separate OkHttp connection pool with its own `SSLContext`, so neither a kept-alive connection
  nor a resumed TLS session ties them to the authenticated account.
* **Nothing the relay handed out is lost to a bad network.** `register` and `restore` save the
  account through `AccountStore` the moment the relay created it (or rotated its token); the steps
  after that (publishing the access key, replacing all pre-keys after a restore, then a complete
  `ProfileUpdate` to every contact) are kept in `LocalAccount.pendingSetup` and finished by
  `performMaintenance`, which the receive loop runs after every backlog and then retries with
  backoff while connected.
* **Restore follows §5.5 exactly**: every field of the backup is validated before anything is
  written or the relay is contacted (a malformed contact refuses the whole file); identity keys
  come back as they were (`BackupContact.identity_unaccepted` keeps an unaccepted safety-number
  change blocked), `presence_sharing` is restored too.
* **Usernames from `ProfileUpdate`s are claims (§3.3).** `Contact.usernameStatus` is `VERIFIED`
  only for a name our own lookup or a checked `sender_username` confirmed; `verifyUsername(peer)`
  checks a claim lazily (e.g. when the conversation opens) and remembers the result, so a contact
  cannot burn our directory quota. Every `ProfileUpdate` replaces the whole stored profile (§8.7).
* **The secret code is six ASCII digits (§5.2)**: `SecretCodePolicy.check` refuses repeated digits,
  runs up or down the digits (also wrapping, `890123`) and the bundled list; `Vault.create` and
  `changeCode` enforce it, and anything that is not six digits is refused before deriving a key.
* **Wrong secret codes (§5.2)**: `VaultUnlocker` records each attempt as a failure before the slow
  key derivation and clears it only after a successful unlock, then enforces D(N) (free twice,
  then 1 min, 5 min, 15 min, 30 min, 1 h) and the optional wipe; the app persists the count
  through `UnlockAttemptStore`, outside the vault. Delays are measured with a `BootClock` (pass
  `SystemClock.elapsedRealtimeNanos()`), so changing the date or restarting the phone never
  shortens them. `status()` gives the unlock screen's "2 more wrong codes and you wait 1 minute".
* **The backup file has its own password (§5.5)**: `BackupFile.create` and
  `Messenger.exportBackup` take a backup password (≥ 10 characters, `BackupPasswordPolicy`),
  never the secret code; a wrong one is `WrongBackupPasswordException`.
* **Message requests (§6.4)** end only with an explicit `ACCEPT`, exactly as on iOS, so both
  phones always agree. Accepting sends `ACCEPT`, then our access key; once a contact's key is
  known, ours and our profile (`ProfileUpdate` with username, display name and
  `presence_sharing`) are sent with it, never with extra proof-of-work.
* **Blocking and unblocking (§6.4)**, as on iOS: `block` drops the contact's messages and rotates
  our access key (the new one goes to every other accepted contact); `unblock` returns it to its
  state before the block (`Contact.stateBeforeBlock`; an incoming request for a stranger, or for a
  contact restored blocked, since a backup holds only the flag). Nothing tells the contact; an
  accepted one gets our current key and our profile again. The app persists `stateBeforeBlock`
  with the contact.
* **Presence (§8.6)** runs inside the receive loop: after the backlog, one
  `POST /v1/messages/ephemeral` announces ONLINE to every eligible contact (accepted, both share,
  key known, session exists), replies are sent once, contacts seen online are refreshed every
  240 s, and `announceOffline()` sends OFFLINE. `presence(peer)` gives the status to display and
  `presenceUpdates` tells the UI when it changed; `setPresenceSharing(false)` turns it off both
  ways. Ephemeral sends always need the contact's access key (`AccessKeyRequiredException`).
* **Built-in stickers (§8.8)** are drawn on the phone from their ids; nothing is downloaded.
  `sendSticker(peer, OutgoingSticker.of(selection))` sends `pack_id`, `sticker_id`, `emoji` and,
  only for a sticker with words, the `lang` they were shown in (a wordless sticker with a language
  is refused). `IncomingMessage.sticker` (or `DataMessage.stickerInfo` for stored messages) keeps
  `lang` as received, and `StickerInfo.resolve(appLanguage)` says what to draw: the sender's
  language, else the reader's; an unknown sticker falls back to its emoji. `StickerCatalog` is the
  same 38-sticker set as the iOS app.
* **Profile pictures (§8.9, §8.10)**: the initial letter (`null`), a preset
  (`ProfilePicture.preset(id)`, only from `AvatarPresets`: catalog version 1, ids 1–22 as listed in
  §8.9; an unknown received id shows the initial) or a photo (`ProfilePicture.photo(contentType, bytes)`: square JPEG or HEIC of at most
  256 × 256, no EXIF/GPS/XMP/IPTC, comments, thumbnails or extra images, serialized at most 49110
  bytes; `InvalidProfilePhotoException.problem` says which rule failed). Core reads only headers
  (JPEG SOF, HEIF `ispe`; a sender's JPEG is walked to `EOI`, so nothing may hide between scans or
  after the end); decoding and encoding are the app's job. `setProfilePicture` sends a
  `ProfileUpdate` (field 6, never the deprecated field 2) to every accepted contact and, while
  `setShowPictureInSearch(true)` (the default), keeps the avatar record on the relay in line:
  AES-256-GCM under a key from the normalized username (PBKDF2 `BRP/avatar/v1`, HKDF with the
  account id), padded to 8/16/32/48 KiB, written one at a time with rising versions kept in
  `LocalAccount.avatarVersion` (a `409` is answered once with `X-Avatar-Version + 1`). A username
  change, a restore and every change of the picture or the setting mark
  `PendingSetup.SYNC_AVATAR`; a relay without the endpoints (`404`/`405`) is asked again after the
  next start, a `429` after `Retry-After` (stores and deletes wait separately, so turning the
  setting off is never held up by a refused store). `searchPicture(peer)` gives the picture for a
  search result or our own request: an accepted contact's own copy, else the record, fetched
  without an HTTP cache, revalidated with `If-None-Match` and kept in memory only; a record older
  than one opened before is a replay and is ignored. For an incoming request it is always `null`:
  `requestPicture(peer, userConfirmed = true)` fetches it only after the user taps for it (§8.9),
  never for a list row. A `ProfileUpdate` inside a
  message request carries no picture, and a malformed one is the initial letter without costing
  the rest of the profile. The backup carries the own picture, the setting and every contact's
  picture.
* **Responses are bounded**: JSON bodies are read with size limits, media downloads stop at the
  blob size the attachment's declared size implies, and receipts are split to fit one envelope.
* **PBKDF2 is implemented on `javax.crypto.Mac`** rather than `SecretKeyFactory`, because the
  factory's `char[]`-to-bytes conversion differs between providers (and Turkish backup passwords
  are not ASCII) and the algorithm is missing on old Android versions.
* **Heavy work stays on the phone** (§1.1): PoW runs on all cores, PBKDF2 and key generation on
  `Dispatchers.Default`, attachments are streamed through temp files.

## In the Android app

The app (built later) depends on `:core` and adds the Android flavour of libsignal. The
`libsignal-client` jar that `core` uses on the desktop carries the Java classes plus desktop
native libraries; `libsignal-android` depends on that same jar and adds the Android `.so` files.
So the app only adds `libsignal-android`, and keeps the desktop binaries out of the APK.

```kotlin
// app/build.gradle.kts
android {
    defaultConfig { minSdk = 23 }                       // libsignal's minimum
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21    // libsignal and core are Java 21 bytecode
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true            // java.time below API 26 (core and libsignal use it)
    }
    ndkVersion = "28.0.13004108"                         // lets AGP strip libsignal's unstripped .so files
    packaging {
        // libsignal-client carries desktop binaries at its jar root (≈640 MB uncompressed):
        // libsignal_jni[_testing]_{aarch64,amd64}.dylib, signal_jni[_testing]_amd64.dll and
        // libsignal_jni[_testing]_amd64.so. The Android .so files come from the AAR's jni/ folders.
        jniLibs { excludes += setOf("**/*.dylib", "**/*.dll") }
        resources { excludes += setOf("**/*.dylib", "**/*.dll", "libsignal_jni*.so") }
    }
}
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Otherwise the loader prefers the testing library over the production one.
        variant.packaging.jniLibs.excludes.add("**/libsignal_jni_testing.so")
        variant.androidResources.ignoreAssetsPatterns.add("libsignal-testing.md")
    }
}
dependencies {
    implementation(project(":core"))
    implementation("org.signal:libsignal-android:0.105.0")   // same version as core (gradle/libs.versions.toml)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

* **R8 / ProGuard:** no extra rules are expected. libsignal ships
  `META-INF/proguard/libsignal.pro` in its jar (native methods, `@CalledFromNative`, store
  interfaces, records, exception constructors); protobuf-javalite, OkHttp and
  kotlinx.serialization ship their own consumer rules. Check a minified release build with the
  live test anyway.
* **Device secret:** implement `DeviceSecretProvider` with an Android Keystore AES-GCM key
  (`setUnlockedDeviceRequired(true)`, not exportable) that wraps 32 random bytes stored in
  app-private storage. Never include it in backups (`android:allowBackup="false"`).
* **Storage:** implement `ProtocolStorage` and `ContactStore` on SQLCipher keyed with
  `DataKey.databaseKey()`; message bodies and files go through `KeySlot` and `ItemCipher`.
  Pictures (`LocalAccount.profilePicture`, `Contact.picture`) are stored there as
  `toProto().toByteArray()` and read back with `ProfilePicture.parse`; never as image files or in
  an image cache.
* **Profile photos:** crop to a square, scale to at most 256 × 256 and encode JPEG (or HEIC) from
  the pixels alone (quality about 0.8, lowered to about 0.4, then a smaller size, until
  `ProfilePicture.photo` accepts it). Decode received photos with `ImageDecoder`, which HEIC
  needs (API 28+): with `minSdk` 23 the app either raises `minSdk` or shows a HEIC picture as the
  initial letter below API 28. Before decoding, check the size `ImageDecoder` reports in
  `OnHeaderDecodedListener` (at most 256 × 256) as well. An incoming message request shows the
  initial letter and a "Show picture" button that calls `requestPicture(peer, userConfirmed =
  true)`; nothing is fetched or decoded before that tap or acceptance.
* **Strings:** `core` has no user-facing text. Error codes (`RelayErrorCode`,
  `SecretCodeProblem`, `BackupPasswordProblem`, `DropReason`) map to `res/values/strings.xml` (en) and
  `res/values-tr/strings.xml` (tr) in the app. The one exception is `StickerCatalog`: the words
  and labels of built-in stickers are data in both languages, because a received sticker is drawn
  in the sender's language, not the reader's. Pack titles (`StickerPack.titleKey`) and the
  picker's text are app strings.

---

## Türkçe

Bu klasör us2us'ın Kotlin tarafıdır: Android uygulamasının üzerine kurulacağı protokol ve kripto
kütüphanesi **`core`** ve canlı uyumluluk testleri için küçük bir yankı botu olan **`cli`**
(`us2us-bot`). Kod, [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) (BRP v1) belgesini uygular; iOS
karşılığı `ios/Us2usKit`'tir ve ikisi `spec/test-vectors/` altındaki ortak test vektörleriyle bayt
düzeyinde uyumlu tutulur.

**Derleme ve test** (JDK 21 veya üstü gerekir):

```sh
cd android
./gradlew test                        # core ve cli için tüm testler
./gradlew :core:generateTestVectors   # yalnızca spec/test-vectors/kotlin/*.json dosyalarını yeniden üretir
./gradlew :cli:installDist            # us2us-bot çalıştırılabilir dosyası
RELAY_URL=http://127.0.0.1:3000 ./gradlew :core:test --tests '*LiveRelayTest'   # gerçek relay ile uçtan uca test
RELAY_URL=http://127.0.0.1:3000 US2US_BOT_STATE=/tmp/echo-bot.json ./gradlew :cli:test --tests '*LiveEchoBotTest'   # çalışan bota karşı test
```

Yankı botu metne `echo: …`, eke `echo-attachment: <sha256>`, konuma `echo-location: …`, hayalet mod
ayarına `echo-settings: …` ve konuşma silme isteğine `echo-wipe: …` ile cevap verir. iOS ile iki
yönlü canlı uyumluluk testi için depo kökündeki `scripts/interop.sh` kullanılır.

`--data <klasör>` ile bot hesabını yeniden başlatmalar arasında korur: ilk çalıştırmada kayıt olur
ve uygulamanın kendi hesap yedeğini (`account.brpb`, rastgele üretilen `backup-password` ile
şifreli; klasör `0700`, dosyalar `0600`) yazar, sonraki çalıştırmalarda bu yedekten geri yüklenir.
Geri yükleme sırasında yenilenen kimlik doğrulama jetonu, mesajlaşma devam etmeden önce yedeğe
yazılır; kişi değişiklikleri birkaç saniye içinde toplu olarak yazılır. Yedek başka bir kullanıcı
adına aitse bot başlamaz. Sunucuya kurulum: `scripts/deploy-bot.sh` (`scripts/README.md`).

**Neler var:**

* Kullanıcı adı normalizasyonu ve PBKDF2 özeti; relay kullanıcı adını hiçbir zaman açık görmez.
  `UsernameRules`, yazılan adı PROTOCOL.md §3.1'e göre denetler (3–24 karakter, harfle başlar,
  yalnızca a-z, 0-9 ve alt çizgi): adı başka bir ada çevirmez, kırdığı kuralları bildirir; iOS
  uygulamasıyla aynı `spec/username-rules.json` tablosuyla test edilir.
* Çok çekirdekli, iptal edilebilir proof-of-work çözücü (ağır iş telefonda, relay tek SHA-256 ile doğrular).
* libsignal ile PQXDH + Double Ratchet oturumları, kimlik değişikliği algılama, tek kullanımlık
  Kyber anahtarlarının silinmesi ve son çare (last-resort) anahtarın tekrar kullanımının reddi.
* Gönderenin relay'den gizlendiği mühürlü zarf (HPKE) ve gönderen doğrulaması.
* Ekler için akış halinde AES-CBC + HMAC şifreleme ve boyut gizleyen dolgu.
* 6 haneli gizli kod kuralları, ayrı yedek parolası, kasa (vault), konuşma anahtarı yuvaları, öğe
  şifreleme ve yedek dosyası.
  Kullanıcı adından yalnızca aramada gösterilen profil resminin anahtarı türetilir ve her
  değişiklikte yeniden üretilir; bu yüzden kullanıcı adı serbestçe değiştirilebilir.
* Güvenlik numarası (safety number), tüm relay uç noktaları, WebSocket ve hepsini birleştiren
  `Messenger` cephesi.
* "Aktif" durumu (presence): yalnızca kabul edilmiş ve iki tarafın da paylaştığı kişiler arasında,
  uçtan uca şifreli ve hiçbir zaman relay'de saklanmadan gönderilir.
* Hazır sticker'lar (§8.8): iOS ile aynı 38 sticker'lık katalog (kimlikler, paketler, Türkçe ve
  İngilizce sözler ve etiketler). Sözlü bir sticker gösterildiği dili (`lang`) taşır ve karşı
  tarafta aynı dilde çizilir; sözsüz sticker dil bilgisi taşımaz. Hiçbir şey indirilmez.
* Gönderenin kimliğini gizleyen istekler (mühürlü gönderim, geçici toplu gönderim, PoW, medya
  indirme) kimlik doğrulamalı isteklerle aynı bağlantıyı veya TLS oturumunu paylaşmaz.
* Kayıt ve yedekten geri yükleme ağ hatasında hesabı kaybetmez: relay'in verdiği bilgiler hemen
  kaydedilir, yarım kalan adımlar (anahtarların yenilenmesi, ardından tüm kişilere profil
  gönderimi) bağlantı sürdükçe artan aralıklarla yeniden denenir. Yedek dosyası her alanıyla
  doğrulanmadan relay'e hiç gidilmez; kabul edilmemiş bir güvenlik numarası değişikliği yeni
  telefonda da gönderimi engellemeye devam eder.
* Profil resmi (§8.9, §8.10): baş harf, hazır avatar (katalog çizimlerle birlikte eklenir) ya da
  en fazla 256 × 256, kare ve meta verisiz (EXIF/GPS yok) bir JPEG veya HEIC fotoğraf. Kabul
  edilmiş kişilere uçtan uca şifreli `ProfileUpdate` ile gider. "Aramada göster" açıkken (varsayılan)
  relay'de kullanıcı adından türetilen bir anahtarla şifrelenmiş, 8/16/32/48 KiB'a doldurulmuş bir
  kayıt tutulur; kullanıcı adını tam yazan herkes resmi görür, relay kullanıcı adını bilmeden
  açamaz (ama tahmin edebilir ve davet bağlantısı açıldığında öğrenir, §9). Kapatmak, baş harfe
  dönmek, kullanıcı adı değişikliği ve hesap silme kaydı siler; silme, yüklemelerden ayrı bir sınıra
  tabidir. Gelen bir mesaj isteğinin resmi yalnızca kullanıcı dokunup istediğinde
  (`requestPicture(peer, userConfirmed = true)`) indirilir.
* Bir kişinin `ProfileUpdate` ile bildirdiği kullanıcı adı yalnızca bir iddiadır:
  `verifyUsername` dizinden bir kez kontrol eder ve sonucu saklar (`Contact.usernameStatus`).
* Yanlış gizli kod denemeleri (`VaultUnlocker`) anahtar türetmeden önce kaydedilir; uygulamayı
  türetme sırasında kapatmak bekleme süresini atlatmaz. İki yanlıştan sonra bekleme süresi artar
  (1 dk, 5 dk, 15 dk, 30 dk, 1 sa) ve açılıştan beri geçen süreyle ölçülür; saati değiştirmek ya
  da telefonu yeniden başlatmak süreyi kısaltmaz.

**Android uygulamasında kullanım:** uygulama `:core`'a ve `org.signal:libsignal-android:0.105.0`
kütüphanesine bağımlı olur; minSdk 23, Java 21 ve core library desugaring açık olmalıdır.
Masaüstü yerel kütüphaneleri (`*.dylib`, `*.dll`, masaüstü `.so` dosyaları) APK'dan hariç
tutulur, sürüm derlemelerinde `libsignal_jni_testing.so` da çıkarılır (yukarıdaki İngilizce
bölümdeki Gradle örneğine bakın). R8 için ek kural gerekmez; libsignal kurallarını kendi jar'ı
içinde getirir. `core` kullanıcıya gösterilen metin içermez: hata kodları uygulamada
`values/strings.xml` (İngilizce) ve `values-tr/strings.xml` (Türkçe) ile yerelleştirilir. Tek
istisna `StickerCatalog`'dur: hazır sticker'ların üzerindeki sözler ve erişilebilirlik etiketleri
iki dilde veri olarak buradadır, çünkü gelen sticker okuyanın değil gönderenin dilinde çizilir.
Paket başlıkları ve sticker seçicinin metinleri uygulamanın metinleridir.
