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

 friend taps https://us2us.app/u/selin.n
   ├─ app installed  → universal link → (unlock) → 4.2 Yeni sohbet, “selin.n” searched
   └─ not installed  → us2us.app/u/selin.n: who invites, what us2us is, how to add them
```

## The message

`Features/Invite/Invite.xcstrings` (`invite.message %@ %@`, `invite.subject`),
in the in-app language (7.1 › Dil):

| | |
|---|---|
| tr | Merhaba! Seninle us2us’ta konuşmak istiyorum: telefon numarası istemeyen, mesajları yalnızca ikimizin telefonunda tutan bir uygulama. Kullanıcı adım: @selin.n<br>https://us2us.app/u/selin.n |
| en | Hi! I’d like to talk with you on us2us, an app that never asks for a phone number and keeps our messages only on our two phones. My username: @selin.n<br>https://us2us.app/u/selin.n |
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
  (`https`, exactly `us2us.app`, no user/port, `/u/<name>` already normalized,
  no percent-encoding); anything else is ignored.
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

## Username rules

The link uses the app's rules (`Domain/UsernameRules`, design 2.1:
`[a-z0-9._]`, 3–20) plus at least one letter or digit, normalized by ASCII
case mapping only (never a Turkish İ/ı mapping; anything outside the alphabet
gives no link rather than another name). The website accepts the union of
those rules and PROTOCOL.md §3.1 — `^(?=.*[a-z0-9])[a-z0-9._]{3,24}$`, exact,
nothing decoded or case-folded — so every link the app can build works while
the owner settles the dot question (ios/App/README.md › Open points 1). When
the protocol and design agree, narrow `INVITE_USERNAME`
(`relay/app/content/invite.ts`) and `InviteLink.normalizedUsername` together.

## Website (`relay/`)

| Path | What |
|---|---|
| `GET /u/<name>` | `app/content/invite.ts` via `server/middleware/invite.ts`: “us2us’ta @name seninle konuşmak istiyor” / “@name wants to talk to you on us2us”, what us2us is in two lines, the “Yakında / Coming soon” store buttons, how to add them (open us2us → Yeni sohbet → @name), and the privacy note. Turkish or English by `Accept-Language` (`vary: accept-language`); the TR/EN switch links to `?lang=tr|en`. The site's CSP and headers, `noindex, nofollow` (meta and `x-robots-tag`), `no-cache`, no cookie, no client JavaScript needed (only theme.js for the saved theme), Open Graph title/description for link previews. About 9 KB (3.4 KB gzip), rendered in ~3 µs. |
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
listesi çıkarmak için kullanılamaz; geçersiz adlar normal 404'ü alır.
Uygulama yüklüyse bağlantı (yalnızca evrensel bağlantı, özel şema yok) kilit
açıldıktan sonra 4.2 Yeni sohbet'i adı aranmış olarak açar; “Mesaj yaz”a
basılana kadar hiçbir şey gönderilmez.
