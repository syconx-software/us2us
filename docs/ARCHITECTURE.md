# Architecture

```
┌──────────────────────┐                         ┌──────────────────────┐
│  iOS app (SwiftUI)   │                         │ Android app (Compose)│
│  ──── Us2usKit ───── │                         │ ── android/core ──── │
│  libsignal (Swift)   │                         │ libsignal (Java)     │
│  local encrypted DB  │                         │ local encrypted DB   │
└─────────┬────────────┘                         └────────────┬─────────┘
          │  TLS: sealed envelopes, encrypted media,          │
          │  pre-key bundles, hashed usernames                │
          ▼                                                   ▼
        ┌───────────────────────────────────────────────────────┐
        │                Relay (relay/, Nuxt 4 / Nitro)         │
        │      directory · pre-keys · mailbox · media           │
        │  MySQL: ciphertext + public keys only, TTL ≤ 14 days  │
        └───────────────────────────────────────────────────────┘
              No push: no APNs, no FCM, no third parties.
              Phones connect only while the app is open.
```

## Identifiers

| What | Value |
|---|---|
| iOS bundle id | `com.syconx.us2us` |
| iOS App Group (reserved for future extensions; none ships now that push is gone) | `group.com.syconx.us2us` |
| Android applicationId | `com.syconx.us2us` |
| Kotlin package of the protocol library | `com.syconx.us2us.core` |
| Swift module | `Us2usKit` |
| Website (landing, privacy policy, support) | `https://us2us.app` |
| Relay API | `https://relay.us2us.app` |

## Responsibilities

| Component | Knows | Cannot read |
|---|---|---|
| Phone | everything about its own conversations | — |
| Relay | account ids, username **hashes**, public keys, recipient, padded size and time of each envelope, IPs at connection time, who uploads media, who looked up whom (once per new contact), which accounts publish a picture for search, its padded size, and who fetched it | message content, the sender inside a sealed envelope, usernames in clear, contact lists, profiles, locations; a picture shown in search only without the username, which it can try to guess (what it can still infer: PROTOCOL.md §9) |
| Apple / Google | nothing (no push is used) | everything about us2us traffic |

## Components

### `relay/` — blind relay (Node.js, Nuxt 4 / Nitro, MySQL)
A single stateless-ish process (WebSocket connections are held in memory, so
v1 runs as **one instance**; scale-out would add a pub/sub bus). Everything
it stores is either public (public keys) or ciphertext, and everything has a
TTL. Runs on any Linux box behind nginx with systemd. See `relay/README.md`.

### `ios/Us2usKit` — Swift package
Protocol logic for iOS: key management, sealed envelopes, padding, PoW,
username hashing, attachment crypto, content codec (SwiftProtobuf), relay
client (URLSession + WebSocket). Session crypto delegated to libsignal's
official Swift binding.

### `android/core` — Kotlin/JVM library
The same logic in Kotlin, on libsignal's official Java binding. It is a plain
JVM module (tests run on the desktop JVM with `libsignal-client`); the Android
app swaps in `libsignal-android`.

### `spec/`
* `proto/brp.proto` — the single source of truth for E2EE content.
* `test-vectors/` — JSON vectors produced by one implementation and verified
  by the other, so Swift and Kotlin can never drift apart.
* `common-secret-codes.txt` (six-digit codes) and
  `common-backup-passwords.txt` — the blocklists both apps bundle
  (PROTOCOL.md §5.2, §5.5).

## Secret code (gizli kod)

All data on the phone is encrypted under a key that only the user's
six-digit secret code plus a device-bound secret can unlock; on iPhones with
a Secure Enclave every attempt also needs a key that never leaves it (vault
format v2). So a copied vault cannot be attacked elsewhere, and the code can
only be guessed on the phone, through the app's growing wrong-attempt
delays (for what that does not cover, see PROTOCOL.md §9); there is no reset.
The username plays no role in encryption, so it can be changed freely. Each
conversation additionally has its own key, so ghost mode can destroy a
conversation instantly by deleting one small key file, even while the app is
locked (PROTOCOL.md §5.2–5.4).

The account backup file is the only way back after a lost phone or a
forgotten code. It can be attacked offline, so it is encrypted with a
separate backup password (at least 10 characters and a strength check; the
app offers a generated one of about 99 bits), never with the secret code
(PROTOCOL.md §5.5).

## Languages

us2us ships in **Turkish and English** from day one:

| Surface | How |
|---|---|
| iOS app | String Catalog (`Localizable.xcstrings`), `tr` + `en`, follows the device language, English fallback |
| Android app | `res/values/strings.xml` (en) + `res/values-tr/strings.xml` |
| Relay errors | machine-readable `code`; phones show localized messages |
| Website `us2us.app` | Nuxt, `/tr` and `/en`, a typed TR/EN text dictionary, no client-side JavaScript (landing, privacy policy, support) |
| Store listings | TR + EN |
| Docs | developer docs in English; user-facing docs and README summary in TR + EN |

## Why no push notifications?
Push notifications go through Apple (APNs) or Google (FCM). Even a
content-free push tells them, and anyone who can compel them, that this
device uses us2us and when it receives messages, and it forces the relay to
keep a mapping from accounts to device tokens. us2us removes that link
entirely: phones receive only while the app is open, and the relay never
talks to a third party. The cost is that there are no notifications when the
app is closed; the apps explain this openly (`docs/copy/no-push.md`).

## Why not fully peer-to-peer?
iOS does not allow an app to keep a socket open in the background, so a pure
P2P messenger would only deliver while **both** apps are open at the same
moment. The blind relay holds ciphertext until the recipient opens the app
(at most 14 days), then forgets it.

## Why these crypto choices?
* **libsignal** (PQXDH + the triple ratchet: Double Ratchet with SPQR): the
  most scrutinized messaging crypto available, with official Swift and Java
  bindings. AGPL-3.0, which is why us2us is AGPL-3.0 too.
* **Sealed sender**: HPKE (RFC 9180) from libsignal itself, addressed to the
  recipient's identity key (§6 of PROTOCOL.md), instead of libsignal's
  certificate-based sealed sender, so the relay needs no signing keys and no
  libsignal dependency. Like Signal's own sealed sender it is classical
  (DHKEM(X25519)): the post-quantum protection covers message content, not
  the sender inside the envelope (PROTOCOL.md §9).
* **Token auth** (`X-Auth-Token` header): the relay stores only `SHA-256(token)`. Simpler and
  less fragile than signature auth; the token only grants access to a mailbox
  of ciphertext.
* **PBKDF2 username hash**: available natively on iOS (CommonCrypto),
  Android/JVM (javax.crypto) and Node, no extra dependency.
