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
| 3d | Communities (`docs/COMMUNITIES.md`) between the Kotlin `LiveCommunityInteropTest` (two Android app runtimes on Robolectric, `android/runtime`) and the Swift `LiveCommunityInteropTests` (the iOS runtime, `Us2usAppCore`); see below |
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
> üye çıkarma, ayrılma, yanında birebir sohbet) ve trafik sürerken relay'in SIGTERM ile
> durdurulup yeniden başlatılması (kabul edilen hiçbir zarf kaybolmamalı).
> Geçici veritabanını kendisi oluşturur ve sonunda siler.

## `deploy-relay.sh` — relay deployment

Runs the relay's dependency audit (`pnpm run audit`; an advisory not
recorded in `relay/package.json` stops the deploy), uploads `relay/` to a
Linux server as an immutable release, builds it there, runs the migrations,
switches a symlink to the new release, reloads the pm2 app and checks
`/v1/health`; a failed health check switches back to the previous release.
The server's pm2 file is never replaced; the script says when it differs
from `relay/deploy/ecosystem.config.cjs`. See `relay/README.md`
("Dependencies", "Deploying") for the details and the server layout.

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
