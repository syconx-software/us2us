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
| 1 | Builds the relay (`pnpm build`), the Kotlin bot and the iOS test bundle; creates and migrates a scratch database (`us2us_interop_<time>_<pid>`); starts the relay on a free port with low proof-of-work, rate limits off and a long `WS_ACK_TIMEOUT_MS` |
| 2 | Kotlin `LiveRelayTest` and Swift `LiveRelayTests` against it |
| 3 | The Kotlin `us2us-bot` as an echo bot, then **3a** iOS → bot (`LiveEchoBotTests`: message request with proof-of-work, ACCEPT and access key, Turkish text, attachment, location, receipts, profiles, presence, ghost settings, `ConversationWipe`, account deletion), **3b** Kotlin → bot and **3c** Kotlin → iOS (`LiveEchoBotTest` against the Swift `LiveEchoResponderTests`) |
| 4 | `relay/scripts/restart-check.mjs` streams envelopes to an online recipient that never acknowledges and to an offline one; the relay gets SIGTERM in the middle of it and is started again on the same database: every envelope it answered `202` must come back (the fast-path flush). **4b**: the bot, connected throughout, still answers |
| 5 | Stops everything, checks the relay's exit status and that its logs have no error lines, drops the database |

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
> hayalet mod, konuşma silme) ve trafik sürerken relay'in SIGTERM ile
> durdurulup yeniden başlatılması (kabul edilen hiçbir zarf kaybolmamalı).
> Geçici veritabanını kendisi oluşturur ve sonunda siler.

## `deploy-relay.sh` — relay deployment

Uploads `relay/` to a Linux server as an immutable release, builds it there,
runs the migrations, switches a symlink to the new release, reloads the pm2
app and checks `/v1/health`; a failed health check switches back to the
previous release. See `relay/README.md` ("Deploying") for the server layout.
