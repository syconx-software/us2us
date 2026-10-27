# scripts

## `interop.sh` — live interoperability run

Checks, in one command, that the relay, the Kotlin library and the Swift
library work together for real: one relay build, a scratch MySQL database,
real network traffic, no fakes.

```sh
scripts/interop.sh             # everything (needs Xcode for the iOS steps)
scripts/interop.sh --no-ios    # relay + Kotlin only, e.g. on Linux
scripts/interop.sh --no-build  # reuse the existing builds
```

| Step | What runs |
|---|---|
| 1 | Builds the relay (`pnpm build`), the Kotlin bot and tests, and the iOS test bundles (`Us2usKit`, `Us2usAppCore`); creates and migrates a scratch database (`us2us_interop_<time>_<pid>`); starts the relay on a free port with low proof-of-work, rate limits off and a long `WS_ACK_TIMEOUT_MS` |
| 2 | Kotlin `LiveRelayTest` and Swift `LiveRelayTests` against it |
| 3 | The Kotlin `us2us-bot` as an echo bot, then **3a** iOS → bot (`LiveEchoBotTests`: message request with proof-of-work, ACCEPT and access key, Turkish text, attachment, location, receipts, profiles, presence, ghost settings, `ConversationWipe`, account deletion), **3b** Kotlin → bot and **3c** Kotlin → iOS (`LiveEchoBotTest` against the Swift `LiveEchoResponderTests`) |
| 3d | Communities (`docs/COMMUNITIES.md`, v1 and Extension v2: media, receipts, delete for everyone) between the Kotlin `LiveCommunityInteropTest` (two Android app runtimes on Robolectric, `android/runtime`) and the Swift `LiveCommunityInteropTests` (the iOS runtime, `Us2usAppCore`); see below |
| 4 | `relay/scripts/restart-check.mjs` streams envelopes to an online recipient that never acknowledges and to an offline one; the relay gets SIGTERM in the middle of it and is started again on the same database: every envelope it answered `202` must come back (the fast-path flush). **4b**: the bot, connected throughout, still answers |
| 5 | Stops everything, checks the relay's exit status and that its logs have no error lines, drops the database |

Step 3d plays one script on both sides; they meet in `<work>/communities/`,
where each writes one JSON file per step (`kotlin-<step>.json`,
`ios-<step>.json`) and waits for the other's; a side that fails writes
`<side>-failed.json` and the other stops waiting. Kotlin plays the owner K1
and a third account K3, iOS plays S:

1. K1 creates the public community "Aile 👨‍👩‍👧"; S finds it by search and
   asks to join; K1 approves (signed revision 2); S checks the roster
   against the owner key from the directory; S and K1 text each other.
2. S creates a private community and two invitations; K3 and K1 redeem
   them and S approves both; K3 also joins the public community. Texts go
   both ways, and nobody became anybody's personal contact.
3. K1 and S start a one-to-one conversation (request, acceptance, reply)
   on the ratchet the communities already use.
3v. Extension v2, in S's private community (S, K1, K3) and K1's public one,
   each item from both platforms:
   * media: a photo with a caption and a file from K1, then from S, each
     uploaded once; every recipient checks the pointer it got (kind, media
     id, digest, size, caption, file name, no delete token), downloads the
     blob and compares the SHA-256 of the plaintext with the sender's;
   * receipts: the sender waits for DELIVERY and READ per member. K3 keeps
     read receipts off for the whole run and S reads one text with them
     off: those recipients show a delivered time and never a read time,
     also after their later READs;
   * delete for everyone by the sender (K1's photo, S's photo): the other
     members hold the placeholder, the file is gone, and `GET /v1/media/{id}`
     answers `404` because the sender deleted the blob with its token;
   * moderation: S (owner) removes K1's file, K1 (owner) removes S's public
     text; the members see "removed by the owner", and the author's phone
     deletes the blob (`404` again);
   * a forged delete (K3 of K1's text, S of K3's public text — neither the
     sender nor the owner) is dropped, checked after a later text from the
     forger arrived;
   * a delete that arrives before its message (sent straight to the relay
     by each platform) leaves the message stored as its placeholder;
   * a one-to-one text and reply in between.
4. S removes K3: the copy queued for K3 is purged, K3 can no longer read
   the state or send (`404`), S and K1 go on.
5. K3 leaves the public community (`DELETE …/members/me`, `204`); S's next
   message still reaches K1; K1 publishes revision 4 without K3; S and K1
   go on, the one-to-one conversation too, and neither path shows the
   other's texts.

Community rate limits are off as well (`RATE_LIMIT_COMMUNITY_*`). To run
3d alone against a relay of your own, start
`xcodebuild test-without-building … -only-testing:Us2usRuntimeTests/LiveCommunityInteropTests`
with `TEST_RUNNER_RELAY_URL` and `TEST_RUNNER_COMMUNITY_DIR`, and
`RELAY_URL=… COMMUNITY_DIR=… ./gradlew :runtime:testDebugUnitTest --tests '*LiveCommunityInteropTest'`
with the same (empty) directory.

Every step prints PASS, FAIL or SKIP and the exit status is 1 if one failed.
Logs, state files and `summary.txt` stay in the work directory it prints.
Requirements: Node.js 24 and pnpm, a JDK ≥ 21, Xcode with an iOS Simulator
and the vendored libsignal (`ios/scripts/fetch-libsignal.sh`), and a MySQL
server. Settings: `INTEROP_MYSQL_URL` (a user that may create and drop
databases, default `mysql://root@127.0.0.1:3307`), `INTEROP_DESTINATION`
(default `platform=iOS Simulator,name=iPhone 17,OS=27.0`) and
`INTEROP_WORK_DIR`.

> **Türkçe:** `scripts/interop.sh` tek komutla gerçek bir relay derlemesi
> üzerinden Kotlin ve Swift kütüphanelerinin birlikte çalıştığını sınar:
> canlı testler, Kotlin yankı botu ile iOS arasında iki yönlü konuşma (mesaj
> isteği, Türkçe metin, ek, konum, okundu/iletildi bilgisi, "aktif" durumu,
> hayalet mod, konuşma silme), iki platform arasında topluluklar (herkese
> açık topluluğu arama ve katılma isteği, onay, davetle özel topluluk,
> üye çıkarma, ayrılma, yanında birebir sohbet; v2: iki yönde başlıklı
> fotoğraf ve dosya — bir kez yüklenir, her alıcı indirip özetini
> karşılaştırır —, üye başına iletildi/okundu bilgisi ve okundu bilgisi
> kapalıyken READ gönderilmemesi, gönderenin ve yöneticinin herkesten
> silmesi — blob relay'den silinir, `404` —, yetkisiz silmenin düşürülmesi,
> mesajından önce gelen silme) ve trafik sürerken relay'in SIGTERM ile
> durdurulup yeniden başlatılması (kabul edilen hiçbir zarf kaybolmamalı).
> Geçici veritabanını kendisi oluşturur ve sonunda siler.

## `deploy-relay.sh` — relay deployment

Runs the relay's dependency audit (`pnpm run audit`; any nonzero result stops
the deploy) and build locally. Uploads only a runtime release (`.output/`,
`drizzle/`, the deployment template and the small ORM migration runner).
No install or build runs on the server. Requires separate private
`~/us2us-migrate.env` (only `DATABASE_URL`) and `~/us2us.env`, verifies different
database principals and DML-only relay grants, then migrates with the isolated
DDL principal. Switches the live symlink, reloads pm2 and checks `/v1/health`;
failed startup/health/runtime-mode checks restore the previous code release.
Schema migrations are not automatically reversed. The live and rollback
release are protected during retention cleanup.
The server's pm2 file is never replaced; the script says when it differs
from `relay/deploy/ecosystem.config.cjs`. See `relay/README.md`
("Dependencies", "Deploying") for the details and the server layout.

`scripts/deploy-relay.sh --prepare-only /path/to/fresh-release` performs only
the audit, local build and packaging; it never reads deployment configuration
or contacts SSH. `node scripts/prepare-relay-release.mjs relay /fresh/path`
packages an already-built tree without running an audit/build; this is useful
for tests and is not release approval. `--verify /release/path` checks its
file list, checksums and Node major. Hashes detect corruption, not signer identity.

## `deploy-bot.sh` — echo bot deployment

Runs the Kotlin echo bot (`us2us-bot`, `android/README.md`) for good on the
relay's server, as pm2 app `us2us-echo`, so that anyone (App Store review
included) can write to `us2us_echo` and get answers. It never touches the
relay's pm2 app or files.

```sh
US2US_DEPLOY_TARGET=us2us_user@<server> scripts/deploy-bot.sh
```

1. Builds `android/cli` locally (`./gradlew :cli:installDist`, a JDK ≥ 21).
2. Checks the server: x86_64 (libsignal carries a Linux library for nothing
   else), Java 21 at `US2US_BOT_JAVA_HOME`, pm2 on the user's PATH (nvm)
   and running `us2us-relay` (so the bot joins the relay user's pm2, its
   boot unit and its `pm2 save`), and that `US2US_BOT_ROOT` is not the
   relay's app root.
3. Uploads `build/install/us2us-bot` to `$US2US_BOT_ROOT/releases/<time>/`;
   files unchanged since the live release are hard-linked, not sent again.
4. Switches the `current` symlink and starts the app anew (`pm2 delete`, then
   `pm2 start`, so changed arguments and settings always apply):
   `current/bin/us2us-bot --username us2us_echo --data /home/us2us_user/us2us-echo --relay https://relay.us2us.app`
   with `JAVA_HOME` and `PATH` of the system OpenJDK 21. pm2 stops the old bot
   with SIGINT, waiting up to 15 s, so it saves its backup and frees the
   directory first.
5. The bot has to stay online for 20 s without a restart: a failed start
   (relay refused, a backup of another account) exits at once. Otherwise the
   previous release is started again; on a first deploy the app is stopped,
   so it does not retry for ever. Then `pm2 save` and the newest
   `US2US_KEEP_RELEASES` (3) releases are kept.

The first start registers the account and writes its backup to
`/home/us2us_user/us2us-echo` (`0700`); every later start, deploys included,
restores it. Each start rotates the auth token and revokes the one before, so
only the directory's current backup works: an older copy, or a second bot on
the same directory, locks the account out. Without the directory the
username is lost for good. Keep it out of the web root.

| Setting | Default |
|---|---|
| `US2US_DEPLOY_TARGET`, `US2US_DEPLOY_KEY` | as for `deploy-relay.sh` |
| `US2US_BOT_ROOT` | `/var/www/us2us_user/us2us-bot` (releases, `current`) |
| `US2US_BOT_DATA` | `/home/us2us_user/us2us-echo` (`--data`) |
| `US2US_BOT_USERNAME` | `us2us_echo` |
| `US2US_BOT_RELAY_URL` | `https://relay.us2us.app` |
| `US2US_BOT_JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk-amd64` (`apt install openjdk-21-jre-headless`) |
| `US2US_BOT_JVM_OPTS` | `-Xmx512m -XX:+ExitOnOutOfMemoryError` (an attachment is held in memory, up to the relay's limit, 64 MiB by default) |

They can also live in `scripts/deploy.local.env`. On the server:
`pm2 logs us2us-echo` (one line per message, never its contents),
`pm2 describe us2us-echo`.

> **Türkçe:** `scripts/deploy-bot.sh` yankı botunu relay sunucusuna kurar ve
> pm2 uygulaması `us2us-echo` olarak sürekli çalıştırır (App Store
> incelemesi `us2us_echo` kullanıcısına yazabilir). Betik botu yerelde
> derler, sunucuyu denetler (x86_64, Java 21, pm2), yeni sürümü
> `/var/www/us2us_user/us2us-bot/releases/<zaman>` altına yükler, `current`
> bağlantısını değiştirir, uygulamayı yeniden başlatır ve 20 saniye ayakta
> kalmasını bekler; kalmazsa önceki sürüme döner. Hesap
> `/home/us2us_user/us2us-echo` klasöründe uygulamanın kendi yedeği olarak
> durur; bu klasör kaybolursa kullanıcı adı da kaybolur. Relay'in pm2
> uygulamasına ve dosyalarına dokunmaz.

## Community UI tests

The native UI suites are `ios/App/Tests/UITests/CommunityFlowUITests.swift`
and `android/app/src/androidTest/kotlin/com/syconx/us2us/app/flows/CommunityFlowTest.kt`.
They use a real **loopback** relay and a dedicated scratch database. Never
point the relay at a production database. Use the existing `scratch-db.mjs`
and migrations; set register PoW difficulty to 0 only on this isolated relay.

Create an API-only applicant:

```sh
node scripts/community-ui-peer.mjs http://127.0.0.1:33318 > /tmp/community-ui-peer.json
```

This applicant's pre-key signatures are placeholders. It tests discovery,
membership access and approval, and must not be used as a Signal messaging
peer. Cryptographic delivery is covered by the runtime/interop suites.

For iOS, create a fresh simulator/container and seed one photo with
`xcrun simctl addmedia <test-device> relay/public/apple-touch-icon.png`.
Pass `TEST_RUNNER_U2COMMUNITY_RELAY`, `TEST_RUNNER_U2COMMUNITY_PEER_TOKEN`
and `TEST_RUNNER_U2COMMUNITY_PEER_ID` (the latter two from the JSON), then run
`xcodebuild test -project ios/App/us2us.xcodeproj -scheme us2us -destination
'platform=iOS Simulator,id=<test-device>' -parallel-testing-enabled NO
-only-testing:us2usUITests/CommunityFlowUITests`. Re-runs require a fresh
test container; never erase or uninstall an existing user's app.

For Android, use `adb -s <device> reverse tcp:33318 tcp:33318`, set
`ANDROID_SERIAL=<device>`, and run `:app:connectedDebugAndroidTest` from
`android/` with these Gradle properties:

- `android.testInstrumentationRunnerArguments.class=com.syconx.us2us.app.flows.CommunityFlowTest`
- `android.testInstrumentationRunnerArguments.relayUrl=http://127.0.0.1:33318`
- `android.testInstrumentationRunnerArguments.communityPeerToken=<authToken>`
- `android.testInstrumentationRunnerArguments.communityPeerId=<accountId>`

The Android suite injects a real runtime with a unique vault directory and
Keystore aliases. It never opens the installed user's vault. Media enters
through the real picker-copy bridge; iOS also drives the native photo picker.
Both suites record failure diagnostics (Xcode attachments; Android window
hierarchy in instrumentation logs, plus screenshots where supported).
Missing relay/applicant configuration is reported as a skip, not a pass.
Record physical-device runs separately from emulator/simulator runs.
