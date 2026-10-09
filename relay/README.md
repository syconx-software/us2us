# us2us relay

The *blind relay* of [us2us](https://us2us.app): it stores and forwards
end-to-end encrypted envelopes, hands out pre-key bundles and keeps encrypted
media blobs for at most 14 days. It cannot read content, does not learn who
sends sealed messages, and never sees usernames, contacts, profiles or
locations. There are no push notifications (PROTOCOL.md §1, §4.8): the relay
holds no device tokens and talks to no third party; phones receive while the
app is open. The same process serves the small public website (`/tr`, `/en`,
privacy policy, support).

The contract is [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) (§4 is the relay
API). This README covers running it. [Türkçe özet aşağıda.](#türkçe)

- [How it works](#how-it-works)
- [Website](#website)
- [Local development](#local-development)
- [Tests](#tests)
- [Migrations](#migrations)
- [Deploying on Ubuntu 24.04](#deploying-on-ubuntu-2404)
- [Backups](#backups)
- [Configuration reference](#configuration-reference)
- [Türkçe](#türkçe)

## How it works

Nuxt 4 (Nitro v2, `node-server` preset), TypeScript, MySQL through drizzle-orm.
Everything heavy happens on the phones (PROTOCOL.md §1.1); every relay
operation is a few indexed queries at most.

| Path | What |
|---|---|
| `server/routes/v1/` | One file per API path; `defineApiRoute({ GET, PUT, ... })` dispatches methods so that every `/v1` response, errors included, uses the §2.1 JSON format |
| `server/realtime/` | WebSocket hooks, live sessions, the online fast path and the persister |
| `server/services/` | MySQL-backed stores (accounts, pre-keys, mailbox, media) and the retention sweep |
| `server/lib/` | Framework-free pieces: config, PoW, rate limits, encodings, validation |
| `server/db/schema.ts` | The schema; binary values are `BINARY`/`VARBINARY`/`MEDIUMBLOB`, times are UTC milliseconds |
| `drizzle/` | Generated, committed SQL migrations |
| `app/`, `public/` | The website (see [Website](#website)): static pages rendered at build time plus our own small stylesheet and scripts, nothing from other origins. The production server has no Vue renderer (`noVueServer`); every other path gets one static 404 page (`server/middleware/not-found.ts`, markup in `app/content/not-found.ts`) |
| `deploy/` | systemd unit, nginx site, MySQL setup |

Things worth knowing:

- **Online fast path.** An envelope for a recipient with an open WebSocket is
  sent from memory and written to MySQL only if it is not acknowledged within
  `WS_ACK_TIMEOUT_MS` (10 s), when the socket closes, or on shutdown. Two
  online users chatting cause no database writes. An envelope for an offline
  recipient goes to the same persister, which writes it a moment later (in
  batches); a recipient that connects first gets it live. Either way a send
  is answered once the envelope is in memory, so its timing does not tell a
  stranger whether the recipient is online. `scripts/load-check.mjs`
  measures both paths (see [Tests](#tests)).
- **Backpressure.** Socket writes happen after the request that caused them
  has been answered, and a socket holding 1 MiB of unsent data gets nothing
  more: live envelopes wait for their ack timeout and then go to the
  mailbox, ephemeral frames are dropped, and the backlog is read in 2 MiB
  slices and written frame by frame. A socket that stays over that budget
  for 30 s, or a replaced one that is not reading, is dropped. An HTTP
  request whose connection makes no progress for 15–30 s (60 s for uploads)
  is dropped too, and one account can have at most 64 requests (and one
  mailbox fetch) running at once.
- **Storage limits.** One mailbox holds at most `MAILBOX_MAX_ENVELOPES` /
  `MAILBOX_MAX_BYTES` (10 000 / 50 MiB); further sends get `429` until the
  recipient acknowledges. Below `DISK_MIN_FREE_MB` of free disk, uploads and
  stored envelopes get `503`. These are counted in memory since start-up.
- **Failed tokens.** Every `401` counts against the client address
  (`RATE_LIMIT_AUTH_FAIL`, 30/min, §4.11); an address over the limit gets
  `429` for all its authenticated requests and WebSocket upgrades before
  any token is looked up, and a token lookup in progress already counts, so
  a burst of concurrent made-up tokens cannot get past the limit.
- **Ephemeral traffic.** `?ephemeral=1` sends and `POST /v1/messages/ephemeral`
  batches (typing, presence, live-location ticks) are forwarded to connected
  recipients and never stored. They need the recipient's access key, and the
  answer is the same whether the key was right and whether the recipient is
  online, so nobody can probe who is online (§4.6).
- **Ordering.** Mailbox ids are `ms timestamp ‖ counter ‖ random`, so
  `(recipient, id)` is both the primary key and arrival order. Ephemeral ids
  are random (with a flag bit), so a presence batch does not number its
  recipients. One-time pre-keys are handed out in random order, so their ids
  do not tell other accounts how many sessions someone started.
- **Single process.** Sessions, rate limits and caches are in memory. Do not
  cluster; scale-out would need a pub/sub bus (ARCHITECTURE.md).
- **Graceful shutdown.** On SIGTERM the relay closes sockets with 1001,
  writes whatever it still holds in memory and closes the pool. A hard
  crash can lose envelopes that were in memory and not yet acknowledged or
  written (fast-path envelopes, and for a few milliseconds envelopes for
  offline recipients); clients de-duplicate by id, so the reverse case (a
  duplicate) is harmless.
- **Logging.** Never request bodies, tokens, keys, account or envelope ids.
  Database errors are logged by error code only, because drizzle puts query
  parameters into its messages. IPs are not logged (`LOG_IPS=0`).
- **WebSocket close codes.** 1001 relay restart, 1007 malformed frame, 1008
  frame rate limit, 1009 frame too large, 1011 backlog failure, 1013 slow
  consumer (backlog or live frames not read), 4001 replaced by a newer
  connection, 4401 token rotated or account deleted. Sockets silent for
  `WS_IDLE_TIMEOUT_MS` (90 s) are dropped, so clients ping about every 30 s.

## Website

`/tr` and `/en` (plus `/tr/gizlilik`, `/en/privacy`, `/tr/destek`,
`/en/support`) follow the us2us web design ("us2us Web", Nocturne design
system). All text, both languages, is in `app/content/site.ts`; the pages
are Vue components in `app/components/` rendered once at build time. The
privacy policy follows PROTOCOL.md §4.10 and §9 (including the known
limits), the "no notifications" texts `docs/copy/no-push.md`.

- **Nothing from other origins.** Fonts (Inter, Doto, Martian Mono; latin and
  latin-ext woff2 from Google Fonts, SIL OFL, licences next to them) are in
  `public/fonts/`, plus a few-KB `-tr` cut of each latin-ext file with only
  ğ Ğ ş Ş İ, which Turkish pages preload instead of the whole latin-ext file
  (`scripts/fetch-fonts.py`, needs `pip install fonttools brotli`). Icons
  are inline SVG paths from Phosphor 2.1.1 (MIT, `app/content/icons.ts`);
  three.js is the pinned `three` dependency, whose
  `build/three.module.min.js` is copied unmodified at build time
  (`nuxt.config.ts`) to `/js/vendor/three-0.160.0.module.min.js`. No
  analytics, no cookies; the theme and motion choices stay in the visitor's
  `localStorage` (the privacy policy says so). The support page lists the
  third-party licences, and the footer's "AGPL-3.0" note links there.
- **Strict CSP.** `script-src 'self'`, `style-src 'self'`, `font-src 'self'`,
  `img-src 'self'`, `default-src 'none'`, `frame-ancestors 'none'` and
  Trusted Types with no policy (`server/lib/website.ts`). There are no
  inline styles or scripts: the stylesheet is `public/css/site.css` (Nuxt's
  CSS pipeline would inline it under `noScripts`), Nuxt's own client bundle
  is off (`features.noScripts`), and its inline speculation rules are
  removed (`app/plugins/no-speculation-rules.server.ts`). The page headers
  (with `cache-control: no-cache`) also cover `/tr/**` and `/en/**`, so
  `/tr/index.html` and the `.br`/`.gz` copies get them too.
- **Caching.** Fonts and three.js have versioned names; our stylesheet and
  scripts are referenced as `?v=<hash of their content>` (`ASSET_VERSION`
  in `nuxt.config.ts`; the 404 page gets it through `runtimeConfig`). All of
  them are `immutable`; the HTML is revalidated on every visit.
- **Progressive enhancement.** Every page is complete without JavaScript.
  `public/js/theme.js` (a classic script in `<head>`) applies the saved
  light/dark choice before the first paint and wires the toggle;
  `public/js/site.js` adds the section-keeping language switch, slow
  scrolling for same-page section links (about 0.4 s per screen, 1–5 s, so
  the 3D scene's transitions play out; any wheel/touch/key takes over; a
  plain jump with `prefers-reduced-motion`), the status steps and the
  countdown, and loads `public/js/scene.js` + three.js at idle time only with
  WebGL, no `prefers-reduced-motion`, no Save-Data, at least 4 GB of
  device memory (where the browser says) and a visible tab. The scene
  renders at most 1.5× device pixels, at 30 fps unless the visitor is
  scrolling, and not at all while the tab is hidden or no scene section is
  on screen; it is disposed on `pagehide`. A section pins only when its
  content fits between the header and the bottom of the smallest viewport
  (`.is-pinned`, set by site.js). Our own JavaScript stays under 36 KB
  (about 11 KB compressed; a test checks it).
- **Departures from the design (on purpose).** The store buttons are not
  links and carry a "Yakında / Coming soon" tag (on screens under 600 px it
  sits on the button's top edge, so both buttons keep the design's size);
  the CTA says "Yakında / Coming soon" too. While stepping through the statuses, the inactive ones fade
  only their icon and turn their text a muted colour that keeps WCAG AA,
  instead of the whole row at 40% opacity. The light theme raises the muted
  text steps (66/60/55% → 76/74/72%) and uses `#5d5294` for small accent
  text (badge, selected segment, "Scan QR code"), because the design's
  values measure 3.4–4.2:1 there. Below 420 px the verification card is
  more compact (20 px padding, 112 px QR, 11.5 px digits), where the design
  cut off the third digit column. Controls keep the design's size, with
  invisible ~44 px touch targets. The footer ends with the licence note.
- `test/integration/website.test.ts` checks both languages, the copy,
  the CSP and headers, caching, content types, the 404 page, the fonts,
  and that no built HTML, CSS, JS or SVG file names another origin.

## Local development

Requirements: Node.js 24 LTS (≥ 24.15), pnpm 10, MySQL 8.4 LTS or 9.x.

```sh
pnpm install
cp .env.example .env              # set DATABASE_URL and POW_SECRET
sudo mysql < deploy/mysql/setup.sql   # or create a database/user yourself
DATABASE_URL=mysql://us2us_migrate:...@127.0.0.1:3306/us2us pnpm db:migrate
pnpm dev                          # http://localhost:3000/en, API under /v1
```

`pnpm build` produces the deployable `.output/`; `pnpm start` runs it.
`pnpm typecheck` runs vue-tsc over the app and the server.

## Tests

```sh
pnpm test        # builds, then runs all tests
pnpm test:only   # runs the tests against the existing build
```

Integration tests spawn the built server (one process per test file, with
low PoW difficulty, small limits and short timeouts) against
`TEST_DATABASE_URL`, default
`mysql://us2us:us2us_dev@127.0.0.1:3307/us2us_test`. **All tables in that
database are dropped** and recreated from `drizzle/` at the start of a run.

Unit tests (`test/unit/`) cover the framework-free code, and check the
relay's PoW verification against the Kotlin/Swift vectors in
`spec/test-vectors/*/pow.json` when they exist.

Load sanity check (2000 sequential sealed sends to a WebSocket client that
acks each one, 2000 ephemeral envelopes in batches of 200 to the same client,
then the sealed sends again with the recipient offline):

```sh
pnpm build
DATABASE_URL=mysql://us2us:us2us_dev@127.0.0.1:3307/us2us_test node scripts/load-check.mjs 2000
```

It prints throughput, send-to-socket latency and InnoDB rows inserted per
phase; the online and ephemeral phases must insert zero rows, the offline
phase one row per envelope.

Restart check (no envelope is lost when the relay gets SIGTERM in the middle
of traffic): `scripts/restart-check.mjs` streams envelopes to an online
recipient that never acknowledges and to an offline one against a running
relay, prints `READY`, and expects the relay to be stopped with SIGTERM and
started again on the same database; then every envelope answered `202` must
come back. `scripts/scratch-db.mjs` creates and drops throw-away databases.
Both are used by `scripts/interop.sh` at the repository root, which runs the
whole cross-language live interop (root `README.md`).

## Migrations

The schema lives in `server/db/schema.ts`. After changing it:

```sh
pnpm db:generate --name describe_the_change   # writes drizzle/NNNN_*.sql
```

Review and commit the generated SQL together with the schema change. Apply
with `pnpm db:migrate` (drizzle-kit, using `DATABASE_URL`; run it with the
`us2us_migrate` user). MySQL DDL is not transactional, so take a backup
before migrating production. Stay on drizzle-orm 0.45 / drizzle-kit 0.31.

## Deploying on Ubuntu 24.04

### Shared host with pm2 (how relay.us2us.app runs)

The production relay shares a server with other sites, so it runs as its own
user with a per-user Node 24 (nvm) under pm2, and is updated with
`scripts/deploy-relay.sh` from the repository root:

```sh
US2US_DEPLOY_TARGET=<app-user>@<server> US2US_APP_ROOT=/var/www/<app-user> US2US_PORT=<port> scripts/deploy-relay.sh
```

The script uploads `relay/` as an immutable release
(`$US2US_APP_ROOT/releases/<timestamp>`), runs `pnpm install`, `pnpm build`
and the migrations there, points the `us2us-relay` symlink at it, reloads the
pm2 app and checks `/v1/health`; if that fails it switches back to the
previous release. It keeps the newest three releases. Settings and secrets live
in `~/us2us.env` (mode 600, loaded with `node --env-file`), the pm2 app in
`$US2US_APP_ROOT/ecosystem.config.cjs` (one process, `kill_timeout` 15 s
so shutdown can flush in-memory envelopes), and pm2 is started at boot by the
`pm2-<app-user>` systemd unit (`pm2 startup`). nginx uses `deploy/nginx/us2us.conf` adapted to
the shared host: the upstream port from `US2US_PORT`, no `http2` flag (on nginx 1.24 it would
change the shared 443 socket for every site) and a us2us-specific
`$us2us_connection_upgrade` map variable. The website and the API are served by
the same process; the API answers only on `relay.us2us.app`.

### Dedicated VM with systemd

One small VM is enough. Layout: code in `/opt/us2us`, settings in
`/etc/us2us/relay.env`, media in `/var/lib/us2us/media`.

1. **Packages.** Node.js 24 LTS (NodeSource or the official binaries, so that
   `/usr/bin/node` is 24.x), `corepack enable` for pnpm, `nginx`, `certbot`,
   and MySQL: Ubuntu's `mysql-server` (8.0) works; 8.4 LTS is available from
   the MySQL APT repository.
2. **MySQL.** Copy `deploy/mysql/us2us.cnf` to `/etc/mysql/mysql.conf.d/`
   (turns off the binary log, which would otherwise keep copies of deleted
   envelopes) and restart MySQL. Set passwords in `deploy/mysql/setup.sql`,
   then `sudo mysql < deploy/mysql/setup.sql`. The relay's user can only read
   and write rows; schema changes need `us2us_migrate`.
3. **User and code.**
   ```sh
   sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin us2us
   sudo git clone <repo> /opt/us2us && cd /opt/us2us/relay
   pnpm install --frozen-lockfile && pnpm build
   DATABASE_URL='mysql://us2us_migrate:...@127.0.0.1:3306/us2us' pnpm db:migrate
   ```
4. **Settings.** `sudo install -d -m 0750 -g us2us /etc/us2us`, then create
   `/etc/us2us/relay.env` (mode 0640, `root:us2us`) from `.env.example` with
   at least `DATABASE_URL` (the `us2us` user) and `POW_SECRET`
   (`openssl rand -base64 48`). The unit already sets `HOST=127.0.0.1`,
   `PORT=3000`, `TRUST_PROXY=1` and `MEDIA_DIR`.
5. **Service.**
   ```sh
   sudo cp deploy/us2us-relay.service /etc/systemd/system/
   sudo systemctl daemon-reload && sudo systemctl enable --now us2us-relay
   journalctl -u us2us-relay -f      # "Listening on http://127.0.0.1:3000"
   ```
6. **TLS and nginx.** Point the DNS of `us2us.app`, `www.us2us.app` and
   `relay.us2us.app` at the host. Enable only the port-80 server block of
   `deploy/nginx/us2us.conf` first (or a stock config), get one certificate
   for all three names with
   `sudo certbot certonly --webroot -w /var/www/letsencrypt -d us2us.app -d www.us2us.app -d relay.us2us.app`,
   then install the full site into `/etc/nginx/sites-enabled/` and
   `sudo nginx -t && sudo systemctl reload nginx`. Certbot's timer renews the
   certificate; add `--deploy-hook "systemctl reload nginx"` when issuing it.
   Keep `client_max_body_size` of `/v1/media` equal to `MEDIA_MAX_BYTES`.
   The site turns off TLS session resumption for `relay.us2us.app` (a
   resumed session could link a sealed send to an account, §1, §4.6),
   streams uploads to the relay unbuffered, never spools responses to disk
   (`proxy_max_temp_file_size 0`) and limits concurrent media transfers per
   address.
7. **Updates.** `git pull && pnpm install --frozen-lockfile && pnpm build`,
   migrate if `drizzle/` changed, then `sudo systemctl restart us2us-relay`.
   Restarts are graceful: clients reconnect and nothing acknowledged-pending
   is lost.

The privacy policy promises that there are no access logs and describes
the error log: the nginx site turns access logs off and keeps every server
block's error log at `crit` (at the default `error` level nginx logs every
failed upstream connection, e.g. during each restart, with the client
address and the request line, which names recipient accounts), and Ubuntu's
default logrotate configuration deletes error logs after 14 days. Keep it
that way; raise the level only temporarily while debugging.

## Backups

**Do not back up message data.** Envelopes, media blobs, used PoW tokens and
one-time pre-keys are short-lived by design; a backup would
keep ciphertext the relay promised to forget (privacy policy, PROTOCOL.md
§4.10), and restoring them would re-deliver acknowledged messages and hand out
one-time keys a second time.

What is worth keeping for disaster recovery is the *directory*, so that
people do not lose their accounts and usernames if the database is lost:

```sh
mysqldump --single-transaction --no-tablespaces us2us accounts usernames \
  | gzip | age -r <recipient-public-key> > /var/backups/us2us/directory-$(date +%F).sql.gz.age
find /var/backups/us2us -name 'directory-*' -mtime +7 -delete
```

Encrypt them and keep at most 7 days, as the privacy policy says. After a
restore: one-time pre-keys are gone (clients refill when
`GET /v1/keys/status` reports fewer than 20), mailboxes are empty, and a
token rotated after the backup is valid again until the account rotates it
once more.

## Configuration reference

All settings are environment variables; see `.env.example` for a commented
template. Only `DATABASE_URL` and `POW_SECRET` are required. Invalid values
stop the relay at startup with a list of every problem.

| Variable | Default | Meaning |
|---|---|---|
| `HOST`, `PORT` | all interfaces, 3000 | Listen address (Nitro). Use `127.0.0.1` behind nginx. |
| `DATABASE_URL` | — | `mysql://user:pass@host:port/db` |
| `DB_POOL_SIZE` | 10 | MySQL connections |
| `POW_SECRET` | — | HMAC key for PoW challenges, ≥ 32 characters |
| `POW_DIFFICULTY_REGISTER` / `_MESSAGE` | 22 / 18 | Leading zero bits required |
| `POW_TTL_SECONDS` | 600 | Challenge lifetime |
| `ENVELOPE_MAX_BYTES` | 65536 | Max sealed envelope |
| `ENVELOPE_TTL_DAYS` | 14 | Mailbox retention |
| `MAILBOX_MAX_ENVELOPES` / `MAILBOX_MAX_BYTES` | 10000 / 52428800 | Per recipient; beyond, sends get 429 |
| `WS_ACK_TIMEOUT_MS` | 10000 | Fast path: store if not acked within this |
| `WS_IDLE_TIMEOUT_MS` | 90000 | Drop sockets silent this long |
| `WS_MAX_FRAME_BYTES` | 32768 | Max client frame |
| `MEDIA_DIR` | `./.data/media` | Blob directory (256 subdirectories) |
| `MEDIA_MAX_BYTES` | 67108864 | Max blob (64 MiB) |
| `MEDIA_TTL_DAYS` | 14 | Media retention |
| `DISK_MIN_FREE_MB` | 1024 | Free space floor of `MEDIA_DIR`'s file system; below it uploads and stored envelopes get 503 (0: off) |
| `USERNAME_HOLD_DAYS` | 14 | Old username reserved after a change |
| `INACTIVE_ACCOUNT_DAYS` | 180 | Delete accounts without connection |
| `RETENTION_INTERVAL_SECONDS` | 300 | Sweep interval |
| `RATE_LIMIT_POW` | 30/min | per IP |
| `RATE_LIMIT_REGISTER` | 5/hour | per IP, failed attempts included |
| `RATE_LIMIT_SEND` | 600/min | per IP |
| `RATE_LIMIT_EPHEMERAL` | 3000/min | per IP, envelopes in ephemeral batches (at least 200) |
| `RATE_LIMIT_MEDIA_FETCH` | 600/min | per IP, media download and delete |
| `RATE_LIMIT_WS_CONNECT` | 60/min | per IP |
| `RATE_LIMIT_DIRECTORY` | 60/hour | per account |
| `RATE_LIMIT_KEY_FETCH` | 120/hour | per account |
| `RATE_LIMIT_MEDIA_UPLOADS` | 60/hour | per account |
| `RATE_LIMIT_MEDIA_BYTES` | 2147483648/day | per account, bytes |
| `RATE_LIMIT_USERNAME_CHANGES` | 5/day | per account (each change holds a name for 14 days) |
| `RATE_LIMIT_WS_FRAMES` | 1200/min | per account |
| `RATE_LIMIT_ACCOUNT` | 1200/min | per account, any authenticated request |
| `RATE_LIMIT_AUTH_FAIL` | 30/min | per IP, failed `X-Auth-Token` checks; over it, every authenticated request gets 429 |
| `TRUST_PROXY` | 0 | Take the client IP from `X-Forwarded-For` (right-most entry) |
| `LOG_IPS` | 0 | Log the client IP of requests refused by a per-IP rate limit (abuse investigations); otherwise IPs are never logged |
| `LOG_LEVEL` | info | error, warn, info, debug |

Rates are written `<count>/<sec|min|hour|day>`, or `off`. IPv6 clients are
limited per /64.

## Türkçe

**us2us aktarıcısı** (blind relay), uçtan uca şifreli zarfları saklayıp
iletir, ön anahtar paketlerini dağıtır ve şifreli dosyaları en fazla 14 gün
tutar. İçeriği okuyamaz, mühürlü mesajların göndericisini bilmez; kullanıcı
adlarını, kişileri, profilleri ve konumları hiç görmez. Bildirim (push)
yoktur: aktarıcı cihaz belirteci tutmaz ve hiçbir üçüncü tarafla
konuşmaz; telefonlar mesajları uygulama açıkken alır. Aynı süreç küçük web sitesini de (`/tr`, `/en`, gizlilik
politikası, destek) sunar. Site başka hiçbir kaynaktan dosya yüklemez: yazı
tipleri, ikonlar, betikler ve three.js us2us.app üzerinden gelir; JavaScript
kapalıyken de tamamen okunur (bkz. [Website](#website)). Protokolün tamamı `docs/PROTOCOL.md` dosyasındadır.

**Nasıl çalışır.** Nuxt 4 (Nitro v2), TypeScript ve MySQL (drizzle-orm). Ağır
işlerin hepsi telefonlarda yapılır. Alıcının WebSocket bağlantısı açıksa zarf
doğrudan bellekten iletilir; yalnızca `WS_ACK_TIMEOUT_MS` (10 sn) içinde
onaylanmazsa, bağlantı kapanırsa ya da sunucu kapanırken MySQL'e yazılır.
Yani çevrimiçi iki kullanıcının yazışması veritabanına hiç yazmaz. Çevrimdışı
alıcının zarfı da aynı yoldan, hemen ardından toplu olarak yazılır; bu yüzden
gönderimin yanıt süresi alıcının çevrimiçi olup olmadığını belli etmez. Geçici
trafik (yazıyor göstergesi, “aktif” durumu, canlı konum) hiç saklanmaz ve
yanıtı da bunu belli etmez. Okumayan bir bağlantıya 1 MiB'tan fazla veri
biriktirilmez; bir posta kutusu en fazla 10 000 zarf / 50 MiB tutar, disk
dolmak üzereyken yükleme kabul edilmez. Hatalı `X-Auth-Token` denemeleri IP
başına dakikada 30 ile sınırlıdır. Tek süreç
olarak çalışır (cluster kullanmayın). SIGTERM ile kapanırken bağlantıları
1001 koduyla kapatır ve bellekteki zarfları kaydeder. İstek gövdeleri,
belirteçler, anahtarlar, kimlikler ve IP adresleri kayıtlara yazılmaz.

**Geliştirme.** Node.js 24 LTS, pnpm 10, MySQL 8.4 ya da 9.x gerekir.
`pnpm install`, `.env.example` dosyasını `.env` olarak kopyalayıp
`DATABASE_URL` ve `POW_SECRET` değerlerini girin, `pnpm db:migrate` ile
tabloları oluşturun ve `pnpm dev` ile başlatın.

**Testler.** `pnpm test` önce derler, sonra tüm testleri çalıştırır. Testler
derlenmiş sunucuyu `TEST_DATABASE_URL` veritabanına karşı başlatır (varsayılan
`us2us_test`; **bu veritabanındaki tüm tablolar silinip yeniden oluşturulur**).
Yük kontrolü için:
`node scripts/load-check.mjs 2000`. Trafik sürerken SIGTERM ile yeniden
başlatmada zarf kaybolmadığını `scripts/restart-check.mjs` sınar; depo
kökündeki `scripts/interop.sh` bunu ve tüm canlı uyumluluk testlerini tek
komutta çalıştırır.

**Şema değişiklikleri.** Şema `server/db/schema.ts` dosyasındadır. Değişiklikten
sonra `pnpm db:generate --name aciklama` ile SQL üretin, şemayla birlikte
commit edin ve `pnpm db:migrate` ile uygulayın (`us2us_migrate` kullanıcısıyla).

**Canlı sunucuya güncelleme (pm2).** `relay.us2us.app` başka sitelerle aynı
sunucuda, kendi kullanıcısıyla (nvm ile Node 24) pm2 altında çalışır. Güncelleme
depo kökünden tek komuttur:
`US2US_DEPLOY_TARGET=<kullanıcı>@<sunucu> US2US_APP_ROOT=<klasör> scripts/deploy-relay.sh` (ayarlar git'e girmeyen `scripts/deploy.local.env` dosyasında da tutulabilir). Betik kodu
yeni bir sürüm klasörüne yükler, orada `pnpm install`, `pnpm build` ve
migration'ları çalıştırır, `us2us-relay` sembolik linkini yeni sürüme çevirir,
pm2'yi yeniden yükler ve `/v1/health` kontrolü yapar; kontrol başarısızsa önceki
sürüme geri döner. Ayarlar ve gizli bilgiler `~/us2us.env` dosyasındadır (izin 600).

**Ubuntu 24.04 kurulumu.** Node.js 24, pnpm, nginx, certbot ve MySQL kurun.
`deploy/mysql/us2us.cnf` ile binary log'u kapatın (silinen zarfların
kopyalarını tutmasın), `deploy/mysql/setup.sql` ile veritabanını ve en az
yetkili kullanıcıları oluşturun. Kodu `/opt/us2us` altına alıp
`pnpm install --frozen-lockfile && pnpm build` çalıştırın, ayarları
`/etc/us2us/relay.env` dosyasına yazın (izin 0640, `root:us2us`),
`deploy/us2us-relay.service` dosyasını systemd'ye ekleyin ve
`deploy/nginx/us2us.conf` ile nginx'i yapılandırın. Sertifika için tek komut
yeterlidir:
`certbot certonly --webroot -w /var/www/letsencrypt -d us2us.app -d www.us2us.app -d relay.us2us.app`.
nginx erişim kaydı tutmaz ve hata kaydı yalnızca `crit` düzeyindedir
(varsayılan düzeyde her yeniden başlatmada IP adresi ve alıcı hesabını içeren
istek satırları yazılırdı); gizlilik politikası bunu anlatır.

**Yedekleme.** Mesaj verisini (zarflar, dosyalar, kullanılmış PoW
belirteçleri, tek kullanımlık ön anahtarlar) **asla yedeklemeyin**: aktarıcı bunları unutmayı vaat eder ve geri yüklemek onaylanmış
mesajları yeniden teslim eder. Felaket kurtarma için yalnızca hesap dizini
(`accounts`, `usernames` tabloları) şifreli olarak yedeklenebilir; bu yedekler
en fazla 7 gün tutulmalıdır (yukarıdaki İngilizce bölümde örnek komut var).

**Yapılandırma.** Tüm ayarlar ortam değişkenleridir; açıklamalı liste
`.env.example` dosyasında, tam tablo yukarıdaki *Configuration reference*
bölümündedir. Yalnızca `DATABASE_URL` ve `POW_SECRET` zorunludur; geçersiz bir
değer sunucunun açılmasını engeller ve tüm sorunları listeler.
