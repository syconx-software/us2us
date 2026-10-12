# Invites

A user can invite someone to us2us by SMS, WhatsApp or e-mail, or through any
other app, with a short ready-made message in the app's language (Turkish or
English) that carries their username and a link. Privacy comes first: the app
never touches the address book, and nothing about the invitation goes through
the relay.

> **Türkçe özet** en sonda.

```
 7.1 “Arkadaşını davet et”  ┐                     Messages / Mail / WhatsApp /
 4.2 nothing found → “Davet et” ├─▶  Invite sheet ─▶ share sheet / pasteboard
 “Kodum” → “Davet bağlantını paylaş” ┘ (text + link)    (the user picks the recipient)

 friend taps https://us2us.app/u/selin_n
   ├─ app installed  → universal link → (unlock) → 4.2 Yeni sohbet, “selin_n” searched
   └─ not installed  → us2us.app/u/selin_n: who invites, what us2us is, how to add them
```

## The message

`Features/Invite/Invite.xcstrings` (`invite.message %@ %@`, `invite.subject`),
in the in-app language (7.1 › Dil):

| | |
|---|---|
| tr | Merhaba! Seninle us2us’ta konuşmak istiyorum: telefon numarası istemeyen, mesajları uçtan uca şifreleyen ve sohbet geçmişini yalnızca ikimizin telefonunda tutan bir uygulama. Kullanıcı adım: @selin_n<br>https://us2us.app/u/selin_n |
| en | Hi! I’d like to talk with you on us2us, an app that never asks for a phone number, encrypts messages end to end and keeps our chat history only on our two phones. My username: @selin_n<br>https://us2us.app/u/selin_n |
| e-mail subject | us2us’ta konuşalım mı? · Shall we talk on us2us? |

The sheet shows the text as the outgoing bubble the friend will get; “Düzenle /
Edit” turns it into a text field (up to 1000 characters, “Varsayılana dön /
Reset” restores the template, an empty text falls back to it). The edited text
lives only in the sheet's state and is used for every channel.

## Channels

| Row | How | Where it cannot work |
|---|---|---|
| Mesaj / Messages | `MFMessageComposeViewController`, body only, no recipient | `canSendText()` false (iPad without Messages, simulator) → share sheet |
| WhatsApp | `https://wa.me/?text=<text>` (WhatsApp's click-to-chat link without a number: WhatsApp opens and the user picks the chat). No SDK and no `LSApplicationQueriesSchemes`: it is a universal link, so without WhatsApp it opens wa.me in the browser | the open fails → share sheet |
| E-posta / Email | `MFMailComposeViewController` with subject and body, no recipient | `canSendMail()` false → `mailto:?subject=…&body=…` (RFC 6068, CRLF line breaks) → if no mail app opens it, share sheet |
| Diğer / More | `UIActivityViewController` with an item source: the text, the subject for Mail, and a header built locally (subject + link) | — |
| Bağlantıyı kopyala / Copy link | `UIPasteboard` with the link only (`public.url` + `public.plain-text`), `localOnly` (no Universal Clipboard) and `expirationDate` = now + 2 minutes; the row says so | — |

Every URL is built with RFC 3986 unreserved characters only (`A–Z a–z 0–9 - . _ ~`);
everything else, Turkish letters and `’` included, is UTF-8 percent-encoded
(`InviteChannelURL`, unit-tested). The sheets are presented from the main
window's top view controller, above the invite sheet. After Messages or Mail
reports “sent”, or a copy, the row shows a check and a short status for 3 s
(VoiceOver announces it).

## Privacy

* **No address book.** No Contacts API, no `NSContactsUsageDescription`, no
  upload. The recipient is always picked in another app's or the system's own
  UI; the app does not learn whom the user invited (Messages and Mail only
  report sent / cancelled).
* **The link carries only the username** — no account id, key, campaign or
  tracking parameter. The username is already public to anyone the user writes to.
* **The website checks nothing.** `/u/<name>` is a string template with no
  relay, directory or database access, so its answer is the same for every
  well-formed name: it cannot be used to find out which accounts exist (the
  relay only knows username hashes anyway, PROTOCOL.md §3.2). The page says so.
* **Universal links only** (Associated Domains `applinks:us2us.app`, path
  `/u/*`); no custom URL scheme, so no other app can hand the app a link that
  did not pass Apple's domain check. The app accepts only the canonical form
  (`https`, exactly `us2us.app`, no user/port, `/u/<name>` already valid by
  PROTOCOL.md §3.1, no percent-encoding); anything else is ignored.
* **Nothing happens on its own.** A link is kept in memory only
  (`InviteLinkInbox`: the latest one, at most 10 minutes), waits for the
  unlock (the app locks on every launch and return), then opens 4.2 with the
  name searched — the same exact lookup as typing it, which the relay sees as
  any other lookup (PROTOCOL.md §4.4). Nothing is sent until the user taps
  “Mesaj yaz”, which starts an ordinary message request with PoW (§6.4).
* **Known, accepted leaks, all by channels the user chooses:** without
  WhatsApp installed, `wa.me` opens in the browser with the text in the URL
  (Meta's servers see it, as they would in WhatsApp); messengers that build
  link previews fetch `us2us.app/u/<name>` (our site logs nothing — nginx
  access logs are off — and the page holds no data beyond the name).
* **The website learns the username.** Opening or previewing an invite link
  tells us2us.app the username in clear — the same process, operator and
  host as the relay, which otherwise sees only username hashes (PROTOCOL.md
  §3.2, §9). With the name it can compute the hash, find the account and
  derive the key of its picture in search, so it can open that picture
  while the owner leaves "Show in search" on (§8.10). The page never logs,
  stores or looks the name up, but this is a policy, not a protection. A
  link that carries the name in the URL fragment (`/u/#name`, never sent to
  the server) would avoid it at the cost of a generic link-preview title; it
  needs an app and AASA change and is not done.

## Username rules

Links follow the one username rule of the whole app, PROTOCOL.md §3.1, on
both sides: `^[a-z][a-z0-9_]{2,23}$` — 3–24 characters, a letter `a-z`
first, then only `a-z`, `0-9` and `_` (no dot). It is the rule the app
registers and looks names up by (`Domain/UsernameRules`; the shared case
table `spec/username-rules.json`).

* **Building a link from the own name** (`InviteLink`, through
  `UsernameRules.normalize`): trim Unicode `White_Space` at both ends, drop
  one leading `@`, then the §3.1 normalization (`White_Space` trim, NFKC,
  locale-independent lower case — Us2usKit's `NormalizedUsername`), so the
  name in the link is byte for byte the one the username hash is made from.
  If the result breaks §3.1 there is no link rather than another name:
  nothing is dropped, cut or converted (no Turkish İ/ı mapping).
* **Incoming links must already be canonical.** The app
  (`InviteLink.username(from:)`) and the website (`INVITE_USERNAME`,
  `relay/app/content/invite.ts`) take the path segment as it is — nothing
  decoded, case-folded or trimmed, one optional trailing slash — and accept
  it only if it matches §3.1 unchanged. `/u/Deniz`, `/u/selin.n`, `/u/1ab`
  or `/u/%64eniz` are ignored by the app and get the website's normal 404,
  so a name has exactly one URL.

## Website (`relay/`)

| Path | What |
|---|---|
| `GET /u/<name>` | `app/content/invite.ts` via `server/middleware/invite.ts`: “us2us’ta @name seninle konuşmak istiyor” / “@name wants to talk to you on us2us”, what us2us is in two lines, the “Yakında / Coming soon” store buttons with the closed-beta line under them (as on the landing page), how to add them (open us2us → Yeni sohbet → @name), and the privacy note. Turkish or English by `Accept-Language` (`vary: accept-language`); the TR/EN switch links to `?lang=tr|en`. The site's CSP and headers, `noindex, nofollow` (meta and `x-robots-tag`), `no-cache`, no cookie, no client JavaScript needed (only theme.js for the saved theme), Open Graph title/description for link previews. About 9 KB (3.4 KB gzip), rendered in ~3 µs. |
| `/u/<anything else>`, other methods | the site's normal static 404 |
| `GET /.well-known/apple-app-site-association` | `server/lib/app-links.ts` via `server/middleware/app-site-association.ts`: `application/json`, no redirect, `applinks` for `C8QW8MBYQX.com.syconx.us2us`, component `/u/*` |

Both middlewares run before `not-found.ts` (Nitro runs middleware in file-name
order). nginx already proxies every path of `us2us.app` to the relay over HTTPS
(`deploy/nginx/us2us.conf`), which is what Apple's CDN needs; `www.us2us.app`
redirects and is not claimed. Tests: `test/unit/invite.test.ts` (paths,
language, rendering, escaping, identical pages for any name, an import-graph
check that the page reaches no database, relay state or network module, the
AASA content) and `test/integration/invite.test.ts` (the built server: both
languages, headers, 404s, identical pages, MySQL statement counters unchanged
by 40 page views, AASA content type and no redirect).

## App (`ios/App/Sources/Features/Invite`)

| File | |
|---|---|
| `InviteLink.swift` | build / parse / normalize the link |
| `InviteMessage.swift` | the template, channel URLs (`wa.me`, `mailto:`), pasteboard options |
| `InviteChannels.swift` | `InviteSender`: Messages, Mail, WhatsApp, share sheet, copy, with fallbacks |
| `InviteView.swift` | the sheet (`InviteView`, `InviteSheet`), channel rows, tiles |
| `InviteEntryPoints.swift` | `InviteNotFoundButton` (4.2), `InviteSettingsRow` (7.1), `InviteShareButton` (Kodum) |
| `InviteLinkInbox.swift` | pending links; `.u2InviteLinks()` (root) and `.u2OnInviteLink(isReady:)` (4.1) |
| `InviteHarness.swift` | DEBUG: `-U2Route invite[:edit|edited|copied|sent|link|link-unknown]`, `-U2OpenURL <url>` |
| `Invite.xcstrings`, `Invite.xcassets` | strings (tr + en); `ph-whatsapp-logo`, `ph-envelope-simple` (Phosphor 2.1.1 regular, MIT) |

The entitlement is `com.apple.developer.associated-domains = [applinks:us2us.app]`
(`project.yml` › `entitlements`). Universal links need the AASA to be live on
`us2us.app` (Apple's CDN fetches it at install); for development before that,
add `applinks:us2us.app?mode=developer` on a device with Developer Mode, or use
`-U2OpenURL https://us2us.app/u/deniz` (DEBUG), which goes through the same
inbox. Tests: `Tests/UnitTests/InviteTests.swift`, `Tests/UITests/InviteUITests.swift`.

## Türkçe özet

Kullanıcı birini SMS, WhatsApp, e-posta ya da paylaşma menüsüyle us2us'a davet
edebilir; mesaj uygulamanın dilinde hazır gelir, gönderilmeden önce görülür ve
düzenlenebilir, içinde yalnızca kullanıcı adı ve `https://us2us.app/u/<ad>`
bağlantısı vardır. Rehbere hiç dokunulmaz; alıcıyı kullanıcı sistemin kendi
ekranında seçer, uygulama kimin davet edildiğini öğrenmez. “Bağlantıyı kopyala”
bağlantıyı yalnızca bu cihazın panosuna, 2 dakikalığına koyar. Web sayfası
kullanıcı adının var olup olmadığına bakmaz (hiçbir sorgu yok), bu yüzden hesap
listesi çıkarmak için kullanılamaz; geçersiz adlar normal 404'ü alır. Ancak
bağlantının açılması ya da önizlenmesi kullanıcı adını us2us.app'e (aktarıcıyla
aynı süreç ve sunucu) açıkça bildirir; bu adla “Aramada göster” açık olan
hesabın profil resmi açılabilir. Sayfa adı kaydetmez ve sorgulamaz, ama bu bir
koruma değil, bir kuraldır.
Bağlantıdaki ad, uygulamanın her yerde kullandığı tek kurala (PROTOCOL.md §3.1:
3–24 karakter, harfle başlar, yalnızca a-z, 0-9 ve alt çizgi) zaten uymalıdır;
uymayan bağlantılar uygulamada yok sayılır, sitede normal 404'ü alır. Uygulama
kendi adından bağlantı kurarken baştaki ve sondaki boşlukları ve baştaki tek bir
“@”i atar, adı §3.1'e göre normalize eder; kurala uymayan ad için bağlantı
kurmaz, adı başka bir ada çevirmez.
Uygulama yüklüyse bağlantı (yalnızca evrensel bağlantı, özel şema yok) kilit
açıldıktan sonra 4.2 Yeni sohbet'i adı aranmış olarak açar; “Mesaj yaz”a
basılana kadar hiçbir şey gönderilmez.
