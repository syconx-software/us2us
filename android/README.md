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
│       ├── username/             normalization, validation, PBKDF2 hash (§3)
│       ├── pow/                  parallel, cancellable proof-of-work solver + verifier (§4.2)
│       ├── keys/                 identity, signed / Kyber / one-time pre-keys, rotation and refill (§5)
│       ├── store/                ProtocolStore (libsignal stores with the §5 rules) + ProtocolStorage SPI
│       ├── session/              content padding, sessions, sealed envelope, sender verification (§5.1, §6)
│       ├── contacts/             contact model and message-request rules (§6.4)
│       ├── presence/             "active now" rules and tracker (§8.6)
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
`build-artifacts.signal.org` on first build. The Gradle wrapper pins Gradle 9.2.1.

```sh
cd android
./gradlew test                        # all unit and end-to-end tests (core + cli)
./gradlew :core:generateTestVectors   # rewrite spec/test-vectors/kotlin/*.json only
./gradlew :cli:installDist            # build/install/us2us-bot/bin/us2us-bot
```

What `./gradlew test` covers:

* every component on its own (encodings, PBKDF2/HKDF against RFC vectors, usernames, PoW,
  padding, keys and their JSON, stores, sealed envelopes, attachments, vault, safety numbers,
  `RelayClient` against MockWebServer including the WebSocket);
* two in-memory parties talking through libsignal: pre-key message → reply → many rounds,
  out-of-order delivery, duplicates, identity change detection, one-time Kyber deletion,
  last-resort reuse rejection, tampered bundles;
* the `Messenger` end to end against `FakeRelay`: registration with PoW, username lookup,
  message request → accept → access-key exchange, texts both ways, attachment
  upload/download/delete, receipts (also split for large backlogs), typing, presence, blocking
  with key rotation, HTTP fetch, backup and restore (also with a failing network mid-way),
  username change, pre-key refill. `FakeRelay` fails the test if the client ever sends its auth
  token on a request that hides the sender (sealed sends, ephemeral batches, message PoW
  challenges, media download/delete) or sends such a request over a connection that also carried
  authenticated requests, and it can inject failures to test recovery;
* the echo bot against `FakeRelay`: the same conversation `LiveEchoBotTest` has with a live
  responder, and the exact answer formats;
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
* **Presence (§8.6)** runs inside the receive loop: after the backlog, one
  `POST /v1/messages/ephemeral` announces ONLINE to every eligible contact (accepted, both share,
  key known, session exists), replies are sent once, contacts seen online are refreshed every
  240 s, and `announceOffline()` sends OFFLINE. `presence(peer)` gives the status to display and
  `presenceUpdates` tells the UI when it changed; `setPresenceSharing(false)` turns it off both
  ways. Ephemeral sends always need the contact's access key (`AccessKeyRequiredException`).
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
* **Strings:** `core` has no user-facing text. Error codes (`RelayErrorCode`,
  `SecretCodeProblem`, `BackupPasswordProblem`, `DropReason`) map to `res/values/strings.xml` (en) and
  `res/values-tr/strings.xml` (tr) in the app.

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

**Neler var:**

* Kullanıcı adı normalizasyonu ve PBKDF2 özeti; relay kullanıcı adını hiçbir zaman açık görmez.
* Çok çekirdekli, iptal edilebilir proof-of-work çözücü (ağır iş telefonda, relay tek SHA-256 ile doğrular).
* libsignal ile PQXDH + Double Ratchet oturumları, kimlik değişikliği algılama, tek kullanımlık
  Kyber anahtarlarının silinmesi ve son çare (last-resort) anahtarın tekrar kullanımının reddi.
* Gönderenin relay'den gizlendiği mühürlü zarf (HPKE) ve gönderen doğrulaması.
* Ekler için akış halinde AES-CBC + HMAC şifreleme ve boyut gizleyen dolgu.
* 6 haneli gizli kod kuralları, ayrı yedek parolası, kasa (vault), konuşma anahtarı yuvaları, öğe
  şifreleme ve yedek dosyası.
  Kullanıcı adı şifrelemede hiçbir rol oynamaz; bu yüzden kullanıcı adı serbestçe değiştirilebilir.
* Güvenlik numarası (safety number), tüm relay uç noktaları, WebSocket ve hepsini birleştiren
  `Messenger` cephesi.
* "Aktif" durumu (presence): yalnızca kabul edilmiş ve iki tarafın da paylaştığı kişiler arasında,
  uçtan uca şifreli ve hiçbir zaman relay'de saklanmadan gönderilir.
* Gönderenin kimliğini gizleyen istekler (mühürlü gönderim, geçici toplu gönderim, PoW, medya
  indirme) kimlik doğrulamalı isteklerle aynı bağlantıyı veya TLS oturumunu paylaşmaz.
* Kayıt ve yedekten geri yükleme ağ hatasında hesabı kaybetmez: relay'in verdiği bilgiler hemen
  kaydedilir, yarım kalan adımlar (anahtarların yenilenmesi, ardından tüm kişilere profil
  gönderimi) bağlantı sürdükçe artan aralıklarla yeniden denenir. Yedek dosyası her alanıyla
  doğrulanmadan relay'e hiç gidilmez; kabul edilmemiş bir güvenlik numarası değişikliği yeni
  telefonda da gönderimi engellemeye devam eder.
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
`values/strings.xml` (İngilizce) ve `values-tr/strings.xml` (Türkçe) ile yerelleştirilir.
