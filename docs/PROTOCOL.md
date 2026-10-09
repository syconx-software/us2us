# BRP — Blind Relay Protocol, v1 (used by us2us)

Status: **draft, normative**. This document is the contract between the relay
(`relay/`), the iOS client library (`ios/Us2usKit`) and the Android/JVM
client library (`android/core`). If code and this document disagree, one of
them is a bug — fix it in the same change.

Key words MUST, SHOULD, MAY are used as in RFC 2119.

> **Change log**
> * 2026-10-08: **privacy review of the iOS app core** (implemented in
>   `ios/Us2usAppCore`; the Android client still needs the same):
>   crossing message requests become `ACCEPTED` (§6.4; interoperable
>   meanwhile, the iOS side's `ACCEPT` ends the request); settings only from
>   accepted contacts, far-future `updated_at` clamped (§8.2); live location
>   follows the disappearing timer (§8.3); removed messages stay removed and
>   unsent ones are never sent (§8.1); view-once carries no preview and is
>   destroyed when opened, quote excerpts only of messages that stay (§8.5).
> * 2026-10-08: **six-digit secret code and a separate backup password**
>   (owner decision, iOS design 02/03/07). §5.2: the secret code is exactly
>   six ASCII digits; repeated digits, runs (also wrapping, `890123`) and
>   `spec/common-secret-codes.txt` (now a six-digit list) are refused; the
>   KDF is unchanged (PBKDF2 600000 over the digits, then HKDF with the
>   device secret). Wrong-attempt delays now start after two wrong codes
>   (1 min, 5 min, 15 min, 30 min, then 1 h) and are measured with the time
>   since boot, so changing the clock gains nothing; "erase everything" needs
>   a second confirmation; still no reset. §5.5: the backup file uses its own
>   backup password (≥ 10 characters, the former secret-code rules, list
>   `spec/common-backup-passwords.txt`). §5.4: the app locks at every launch
>   and every return from the background. §9: what six digits do not
>   protect against. Test vectors: `secret-code.json` has the new rules,
>   `backup-password.json` is new, `vault.json` uses six-digit codes and
>   `backup.json` a `password` field.
> * 2026-10-07: **push notifications removed from the product.**
>   Implementations MUST NOT contain push endpoints, device-token storage,
>   APNs/FCM clients or push-related query parameters (`push=0`). This
>   supersedes any earlier instruction or draft that mentioned push. Mailbox
>   and media retention raised to 14 days. Delivery receipts are always sent.
> * 2026-10-07: **"active now" presence added** (§8.6, `PresenceMessage`,
>   `ProfileUpdate.presence_sharing`, `POST /v1/messages/ephemeral`).
>   Ephemeral sends now require an access key and MUST NOT reveal whether the
>   recipient is online (no `stored` field, identical responses), otherwise
>   strangers could probe anyone's online status.
> * 2026-10-07: **WebSocket keep-alive and close codes** (§4.7): `ping` at
>   least every 30 s, 90 s idle close, close-code table, repeated frames,
>   ephemeral acks ignored.
> * 2026-10-07: **sender-hiding requests isolated** (§1, §4.6): they never
>   share a connection or a resumable TLS session with authenticated requests.
> * 2026-10-07: **relay edge cases pinned** (§1.1, §2.1, §4.2–§4.6, §4.9):
>   ephemeral and batch errors, PoW consumed on `409`, held and deleted
>   username hashes, key size bounds, media `Content-Length`/`Range`/delete.
> * 2026-10-07: **rate-limit table completed** (§4.11), with a new limit on
>   failed `X-Auth-Token` checks.
> * 2026-10-07: **text and secret-code rules made exact** (§3.1, §5.2, §5.5):
>   `White_Space` trimming, default casing, "simple sequence" defined,
>   untrimmed KDF input, iteration bounds, attempt counting.
> * 2026-10-07: **restore made crash-safe** (§5.5): verify first, persist the
>   rotated token, retry the key upload; `BackupContact` fields 8 and 9.
> * 2026-10-07: **sender verification and message requests** (§6.2–§6.4):
>   changed identities, username check for every type, lookup failures
>   retried, exact acceptance state machine.
> * 2026-10-07: **attachment size bounds** (§7).
> * 2026-10-07: **receipts and profiles** (§3.3, §8.4, §8.6, new §8.7):
>   receipted payloads, ≤ 1000 ids per receipt, complete `ProfileUpdate`s,
>   usernames verified via the directory, presence expiry.
> * 2026-10-07: **known limits extended** (§9): lookup metadata, crash loss of
>   unacknowledged fast-path envelopes.
> * 2026-10-07: **test vectors** (`spec/test-vectors/README.md`):
>   `secret-code.json` added; `sealed.json` is verify-only.

---

## 1. Design goals

1. **The relay is blind.** It stores only ciphertext addressed to an account
   id, for as short a time as possible, and deletes it once delivered.
2. **No phone numbers, no e-mail, no contact upload.** Accounts are a
   keypair plus a *hashed* username.
3. **Conversation data lives only on the two phones.** No cloud backup, no
   server-side profile, no server-side contact list.
4. **The relay does not learn who sends to whom** (sealed sender, §6).
   Requests that hide the sender MUST NOT share a connection or a resumable
   TLS session with authenticated requests (§4.6).
5. **Audited crypto only.** Session encryption is libsignal (PQXDH + Double
   Ratchet with SPQR, the triple ratchet; §5). We only add thin, standard
   constructions (HKDF, AES-GCM, AES-CBC+HMAC, SHA-256, PBKDF2) on top.
6. **Phones do the work, the relay stays cheap.** See §1.1.

The protocol is called **BRP (Blind Relay Protocol)**. All protocol-internal
constants (`BRP/...` strings, protobuf package `brp.v1`) are independent of
the app's brand name, so renaming the app never changes the wire format.

Non-goals for v1: groups, multiple devices per account, calls.

**No push notifications, by design.** us2us does not use Apple Push
Notification service or Firebase Cloud Messaging at all. Messages are
received only while the app is open. The relay therefore talks to no third
party, holds no device tokens, and Apple/Google learn nothing about who uses
us2us or when they receive messages. See §5.4.

### 1.1 Load budget

Every relay operation MUST be O(1), cheap and bounded. Anything that costs
CPU, memory or storage in proportion to *content* happens on the phones.

| Work | Where |
|---|---|
| All encryption / decryption, key generation, session state | phone |
| Proof-of-work solving (anti-spam) | phone (relay verifies with one SHA-256) |
| Username hashing (PBKDF2, 100k iterations) | phone |
| Image/video compression, thumbnails, EXIF stripping, blurhash | phone |
| Link previews, search, message history, profiles, avatars, sticker packs | phone (shared with contacts only via E2EE) |
| Ghost / disappearing timers and wipes | phone |
| Backup / device transfer | phone ↔ phone directly, never via relay |
| Relay | verify, store-and-forward small envelopes, temporary media blobs |

Concrete rules that keep the relay light:

* **Online fast path:** an envelope for a recipient with an open WebSocket is
  forwarded from memory and is written to MySQL only if it is not
  acknowledged within 10 seconds (or the socket closes, or the relay shuts
  down gracefully). Conversations between two online users therefore cause
  no database writes. A hard crash can lose such envelopes (§9).
* **Connections only while the app is open.** A phone holds one WebSocket
  while the app is in the foreground and closes it when the app goes to the
  background. There is no push and no background polling, so idle users cost
  the relay nothing.
* **Ephemeral traffic is never stored** (typing, live-location ticks).
* **Receipts are batched** (one `ReceiptMessage` may carry many ids).
* **Media is compressed on the phone before upload** (images ≤ 2048 px long
  edge JPEG/HEIC q≈0.8; video ≤ 720p H.264/HEVC ~1.5 Mbit/s). Blobs are
  deleted as soon as the recipient has downloaded them completely and sends
  `DELETE /v1/media/{id}` (§4.9); hard TTL 14 days; default max blob size
  64 MiB.
* **Direct transfer (planned, v1.1):** when both phones are online, large
  media goes peer-to-peer over a WebRTC data channel, signalled through
  E2EE messages; the relay is used only as a fallback.
* Stickers ship inside the app; custom stickers travel as ordinary E2EE
  attachments. No sticker store on the relay.

## 2. Conventions

| Item | Encoding |
|---|---|
| Binary values in JSON | base64url **without padding** (RFC 4648 §5) |
| Account id | UUID v4, lowercase hyphenated string in JSON; 16 raw bytes inside protobuf |
| Timestamps | integer milliseconds since Unix epoch (UTC) |
| Identity / EC public keys | libsignal `PublicKey` serialization, 33 bytes (`0x05` ‖ X25519) |
| Kyber public keys | libsignal `KEMPublicKey` serialization (type byte ‖ key) |
| Signatures | libsignal XEdDSA, 64 bytes, made with the identity key |
| Big integers in JSON | decimal **strings** (JavaScript cannot represent > 2^53) |
| libsignal address | built from the account id as an ACI ServiceId, device id 1: Swift `ProtocolAddress(Aci(fromUUID: id), deviceId: 1)`, Kotlin `SignalProtocolAddress(id.toString(), 1)` |

**Why ACI ServiceIds:** since libsignal v0.91 every `SignalMessage` MACs the
sender and recipient ServiceId + device id. Addresses that are not valid
ServiceIds make decryption fail, so account ids MUST be UUIDs and both sides
MUST build addresses as above. The name string is only a local store key
(Swift uses the uppercase form, Kotlin lowercase); the MAC compares the
binary form, so the two platforms interoperate. Each platform MUST use one
constructor consistently.

libsignal version: **v0.105.0** on both platforms (bump both together).

All HTTP traffic MUST use TLS in production. JSON bodies use
`Content-Type: application/json`; binary bodies use
`application/octet-stream`.

### 2.1 Error format

Every non-2xx response except `416` (§4.9) has the body:

```json
{ "error": { "code": "username_taken", "message": "human readable, English" } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `bad_request` | malformed input / validation failure |
| 401 | `unauthorized` | missing/invalid auth token |
| 403 | `access_denied` | sealed send without valid access key or PoW; wrong media delete token |
| 403 | `pow_invalid` | PoW missing, wrong, expired or reused |
| 404 | `not_found` | account / media / bundle not found |
| 405 | `bad_request` | wrong method for a `/v1` path; `Allow` lists the right ones |
| 409 | `username_taken` | username hash already registered |
| 413 | `payload_too_large` | body exceeds limit |
| 416 | — | unsatisfiable `Range` (§4.9), empty body |
| 426 | `bad_request` | `GET /v1/ws` without a WebSocket upgrade |
| 429 | `rate_limited` | with `Retry-After` header (seconds) |
| 500 | `internal` | — |

## 3. Usernames

### 3.1 Rules

A username is 3–24 characters matching `^[a-z][a-z0-9_]{2,23}$` after
normalization. Normalization: remove leading and trailing characters with
the Unicode `White_Space` property (not a platform's own notion of
whitespace: U+0085 and U+3000 are trimmed, U+001C is not), then Unicode
NFKC, then lowercase with the locale-independent Unicode default case
mapping (never a Turkish or other locale; Turkish dotted/dotless i are not
allowed characters, so `I`→`i` is the only case mapping that matters).

### 3.2 Username hash

The relay never sees usernames, only:

```
usernameHash = PBKDF2-HMAC-SHA256(
    password   = UTF-8(normalizedUsername),
    salt       = UTF-8("BRP/username/v1"),
    iterations = 100000,
    dkLen      = 32)
```

This is deliberately slow so that a leaked relay database cannot be turned
into a username list cheaply. Lookup is **exact match only** — there is no
prefix or fuzzy search, so the directory cannot be enumerated by typing.

### 3.3 A username is a label, not an identity

An account's identity is its random **account id** plus its **identity key
pair**. The username is only a findable pointer to the account id, and no
key or data is derived from it. Therefore:

* A user can change the username at any time (`PUT /v1/accounts/me/username`).
  Conversations, sessions, safety numbers and local data are unaffected.
* After a change the client sends a complete `ProfileUpdate` (§8.7) to its
  contacts (E2EE) so their contact list shows the new name.
* A username received in a `ProfileUpdate` is only a claim. Clients show it
  as verified only after a directory lookup (§4.4) of its hash returns the
  sender's account id; the lookup is done lazily (e.g. when the contact is
  opened) and its result cached, so a contact cannot use up the receiver's
  directory quota by announcing random names.
* The relay keeps the **old** username hash reserved for the same account for
  14 days, so nobody can grab it right away and impersonate the user.

## 4. Relay HTTP API

Base path `/v1`. Authenticated endpoints require the header
`X-Auth-Token: <authToken>` (base64url, 32 bytes). The relay stores only
`SHA-256(authToken)`. We do not use `Authorization` because Apple documents it
as a reserved header that URLSession may drop or overwrite.

### 4.1 Health

`GET /v1/health` → `200 {"ok": true, "version": "x.y.z"}`

### 4.2 Proof-of-work

Anti-spam without identity. The relay issues a stateless, HMAC-signed
challenge token.

`GET /v1/pow?purpose=register`
`GET /v1/pow?purpose=message&to=<accountId>`

```json
200 { "token": "<opaque ASCII>", "difficulty": 22, "expiresAt": 1760000000000 }
```

Client solves: find an unsigned 64-bit `nonce` such that

```
SHA-256( UTF-8(token) ‖ uint64_big_endian(nonce) )
```

has at least `difficulty` leading zero **bits**. A solution is submitted as
`{"token": "...", "nonce": "123456"}` (nonce as decimal string in
0..2^64−1; leading zeros are accepted) or, in a header,
`X-PoW: <token>:<nonce>`.

The relay MUST verify: HMAC, purpose, target (`to` for message purpose),
expiry, leading zero bits, and that the token was not used before (single
use; the relay remembers `SHA-256(token)` until expiry). A `register` PoW
is consumed even when registration then fails with `409 username_taken`, so
usernames cannot be probed for free.

Defaults (configurable): `register` difficulty 22, `message` difficulty 18,
token lifetime 10 minutes. Token format is relay-internal; clients treat it
as opaque. Reference format:
`v1.<purpose>.<target|->.<expiresAtSeconds>.<random16 b64u>.<hmac b64u>`.

### 4.3 Accounts

**Register** — `POST /v1/accounts` (no auth)

```json
{
  "usernameHash": "<b64u 32>",
  "identityKey": "<b64u 33>",
  "registrationId": 1234,
  "signedPreKey":       { "keyId": 1, "publicKey": "<b64u 33>", "signature": "<b64u 64>" },
  "kyberLastResortKey": { "keyId": 1, "publicKey": "<b64u>",    "signature": "<b64u 64>" },
  "oneTimePreKeys":      [ { "keyId": 1, "publicKey": "<b64u 33>" } ],
  "kyberOneTimePreKeys": [ { "keyId": 2, "publicKey": "<b64u>", "signature": "<b64u 64>" } ],
  "pow": { "token": "...", "nonce": "123" }
}
```

`201 {"accountId": "<uuid>", "authToken": "<b64u 32>"}` · `409 username_taken`
(the PoW is still consumed, §4.2) · `403 pow_invalid`.

Validation: `registrationId` 1..16380; key ids 1..16777215 (24 bit); at most
100 entries per one-time list; identity key 33 bytes starting with `0x05`;
Kyber public keys 2..2048 bytes (1569 for `KYBER_1024`); signatures 64
bytes. The relay cannot verify XEdDSA signatures; **clients MUST**
(libsignal does it when processing a bundle).

**Me** — `GET /v1/accounts/me` → `{"accountId","usernameHash"}`

**Change username** — `PUT /v1/accounts/me/username` `{"usernameHash"}` →
`204` · `409`. The previous hash stays reserved for this account for 14 days
(§3.3); during that time only this account may take it back, and the
directory does not return it (§4.4). At most 5 changes per day (§4.11).

**Rotate auth token** — `POST /v1/accounts/me/token` (auth) →
`200 {"authToken": "<b64u 32>"}`. The old token stops working immediately
(an open WebSocket is closed with `4401`). Used right after restoring a
backup on a new phone (§5.5), so the old phone loses access.

**Set access key** — `PUT /v1/accounts/me/access-key`
`{"accessKeyHash": "<b64u 32>" | null}` → `204`. `accessKeyHash =
SHA-256(accessKey)`; `accessKey` is 16 random bytes. `null` disables
access-key delivery (everyone must use PoW).

**Delete account** — `DELETE /v1/accounts/me` → `204`. Deletes the account,
all its keys, its mailbox and the media it uploaded, releases its current
and held username hashes immediately, and closes its WebSocket with `4401`.
Irreversible.

**Identity lookup** — `GET /v1/accounts/{accountId}/identity` (auth) →
`{"accountId","identityKey"}`. Used to verify sealed senders (§6.3).

### 4.4 Directory

`GET /v1/directory/{usernameHash}` (auth) → `{"accountId","identityKey"}` or
`404`. Only current username hashes are returned; a held hash (§4.3) is
`404`, so a message request sent right after a username change fails §6.3.
Rate limited per account (default 60/hour).

### 4.5 Pre-keys

**Upload/replace** — `PUT /v1/keys` (auth). Any subset of
`signedPreKey`, `kyberLastResortKey`, `oneTimePreKeys`,
`kyberOneTimePreKeys` (same shapes as registration). One-time keys are
appended (duplicates by keyId are ignored); signed and last-resort keys
replace the previous ones. The relay keeps at most 200 of each one-time type;
further one-time keys of that type are silently ignored.
With `"replaceAll": true` the relay first deletes all stored one-time keys
of both types (used after a restore, when the old private keys are gone).
→ `204`

**Status** — `GET /v1/keys/status` (auth) →
`{"oneTimePreKeys": 57, "kyberOneTimePreKeys": 57, "signedPreKeyId": 3, "kyberLastResortKeyId": 3}`

**Fetch bundle** — `GET /v1/keys/{accountId}` (auth, rate limited) →

```json
{
  "accountId": "<uuid>", "deviceId": 1, "registrationId": 1234,
  "identityKey": "<b64u>",
  "signedPreKey": { "keyId", "publicKey", "signature" },
  "kyberPreKey":  { "keyId", "publicKey", "signature" },
  "oneTimePreKey": { "keyId", "publicKey" } | null
}
```

The relay MUST consume one EC one-time pre-key and one Kyber one-time pre-key
atomically (each handed out at most once). If no Kyber one-time key is left,
it returns the Kyber last-resort key (which is not consumed). If no EC
one-time key is left, `oneTimePreKey` is `null`.

### 4.6 Messages (mailbox)

**Send (sealed, unauthenticated)** — `PUT /v1/messages/{recipientAccountId}`

The sender does **not** authenticate — otherwise the relay would learn who
sends to whom. Spam is limited by one of:

* `X-Access-Key: <b64u 16>` — the recipient's access key, which the
  recipient gives to accepted contacts inside E2EE
  (`ControlMessage.access_key`). The relay checks
  `SHA-256(accessKey) == accessKeyHash` in constant time.
* `X-PoW: <token>:<nonce>` — a `message` PoW bound to this recipient. Used
  for first contact (message requests).

Query parameters:
* `ephemeral=1` — deliver only if the recipient is connected right now,
  never store (typing indicators, live-location ticks, presence). Ephemeral
  sends MUST use `X-Access-Key` (PoW is rejected with `access_denied`), so only
  accepted contacts can send them. The response MUST NOT reveal whether the
  envelope was delivered. A missing or malformed `X-Access-Key` is
  `403 access_denied` and an unknown recipient is `404` (neither depends on
  whether the recipient is online); only a well-formed access key is
  answered `202` with the same body shape, whether it is right or wrong
  (a wrong key is silently dropped) and whether or not the recipient is
  connected. Otherwise anyone could probe whether a user is online.

Body: the sealed envelope bytes (§6.1), max 64 KiB.
→ `202 {"id": "<b64u 16>", "serverTimestamp": 1760000000000}`. `404` if the
recipient does not exist, `403 access_denied` / `pow_invalid` (for ephemeral
sends see above).

**Ephemeral batch (unauthenticated)** — `POST /v1/messages/ephemeral`

```json
{ "messages": [ { "to": "<uuid>", "accessKey": "<b64u 16>", "envelope": "<b64u>" } ] }
```

1–200 items, each envelope ≤ 4 KiB, body ≤ 1 MiB (the body limit wins;
presence envelopes are far smaller). Shape errors (item count outside
1..200, an invalid uuid, an access key that is not 16 bytes, invalid
base64url, an envelope over 4 KiB) reject the whole batch with
`400 bad_request`; a body over 1 MiB is `413`. Otherwise, for each item the
relay checks the access key in constant time and forwards the envelope over
the recipient's WebSocket if it is connected; unknown recipients, wrong keys
and offline recipients are dropped silently. Nothing is ever stored.
Per-recipient outcomes are never reported: always `202 {}`. Used for
presence fan-out (§8.6), so one app start costs one request.

**Unlinkability.** Requests that hide the sender — sealed and ephemeral
sends (`PUT /v1/messages/*`, `POST /v1/messages/ephemeral`), message PoW
challenges (`GET /v1/pow?purpose=message`) and media download and delete
(`GET`/`DELETE /v1/media/{id}`) — MUST NOT share a connection (HTTP
keep-alive or HTTP/2 multiplexing) or a resumable TLS session (session ID,
session ticket, 0-RTT) with authenticated requests (those with
`X-Auth-Token`, including the WebSocket). Otherwise the relay could link
the request to the sender's account.

**Fetch** — `GET /v1/messages?limit=100` (auth, limit ≤ 500) →

```json
{ "messages": [ { "id": "<b64u 16>", "serverTimestamp": 1760000000000, "envelope": "<b64u>" } ], "more": false }
```

Ordered by arrival. Messages stay until acknowledged or expired. Envelopes
in flight on the account's WebSocket (sent there, not yet acknowledged or
stored) are not included.

**Acknowledge** — `POST /v1/messages/ack` (auth) `{"ids": ["..."]}` (≤ 500) →
`204`. The relay deletes them immediately.

### 4.7 WebSocket

`GET /v1/ws` with header `X-Auth-Token: <authToken>` (the token is never put
in the URL). Text frames, JSON:

| Direction | Frame |
|---|---|
| S→C | `{"type":"message","id":"…","serverTimestamp":…,"envelope":"<b64u>"}` |
| S→C | `{"type":"queue_empty"}` — after the backlog at connect time was sent |
| S→C | `{"type":"pong"}` |
| C→S | `{"type":"ack","ids":["…"]}` |
| C→S | `{"type":"ping"}` |

On connect the relay sends all stored messages (oldest first), then
`queue_empty`, then forwards new messages live (online fast path, §1.1:
held in memory, persisted only if not acknowledged within 10 s or on
disconnect/shutdown). Unacknowledged messages stay stored and are re-sent on
the next connection. Frames may repeat (envelopes unacknowledged on a
replaced connection are re-sent on the new one, and a stored envelope may be
sent twice when a connect races with its storage); clients MUST
de-duplicate by `id`. Clients MAY acknowledge ephemeral frames; the relay
ignores those acks. One connection per account: a new connection closes the
previous one with close code `4001` ("replaced"). An invalid token closes
the upgrade with HTTP 401 (or close code `4401` if the platform cannot
reject the upgrade). Unknown C→S frame types are ignored.

**Keep-alive.** Clients MUST send `{"type":"ping"}` at least every 30 s
while connected. The relay MAY close a socket that sent no application
frame for 90 s; WebSocket control-frame pings do not count.

Close codes sent by the relay:

| Code | Meaning |
|---|---|
| `1001` | relay restarting (pending envelopes are stored first) |
| `1007` | malformed frame (not a JSON object, or an invalid `ack`) |
| `1008` | frame rate limit exceeded (§4.11) |
| `1009` | frame too large (default limit 32 KiB) |
| `1011` | internal error, e.g. sending the backlog failed |
| `1013` | slow consumer: the client does not read the backlog fast enough |
| `4001` | replaced by a newer connection of the same account |
| `4401` | auth token rotated or account deleted |

### 4.8 Push notifications

None. The relay has no push endpoints, stores no device tokens and never
contacts Apple, Google or any other third party (§1, §5.4).

### 4.9 Media

**Upload** — `POST /v1/media` (auth), body = encrypted blob (§7), max 64 MiB (configurable `MEDIA_MAX_BYTES`)
→ `201 {"mediaId": "<b64u 16>", "deleteToken": "<b64u 32>", "expiresAt": …}`.
`Content-Length` is required (a chunked body is `400`). The daily byte
quota (§4.11) is charged with the declared length when the upload starts,
even if the upload then fails.

**Download** — `GET /v1/media/{mediaId}` (no auth; the id is a 128-bit random
capability and the content is ciphertext). A single `Range: bytes=…` is
answered `206`; a header with several ranges is ignored (`200`, full body);
an unsatisfiable range is `416` with `Content-Range: bytes */<size>` and an
empty body.

**Delete** — `DELETE /v1/media/{mediaId}` with `X-Delete-Token: <b64u 32>`
(compared in constant time) → `204`; a wrong token is `403 access_denied`,
a missing or malformed one `400`, an unknown id `404`. The relay cannot tell
when a (possibly resumed) download is complete, so a blob is deleted only by
this request or its TTL: the recipient MUST send it once its download is
complete; senders MAY send it to unsend.

### 4.10 Retention (relay)

| Data | Lifetime |
|---|---|
| Mailbox envelope | until acknowledged, max 14 days |
| Media blob | until deleted, max 14 days |
| Used PoW tokens | until token expiry |
| Account without any connection | deleted after 180 days (only a coarse last-seen *date* is stored) |

The relay MUST NOT log request bodies, auth tokens or access keys, and SHOULD
NOT log IP addresses (`LOG_IPS=false` by default).

### 4.11 Rate limits (defaults, per IP unless noted)

| Endpoint | Limit |
|---|---|
| `GET /v1/pow` | 30 / min |
| `POST /v1/accounts` | 5 / hour |
| `PUT /v1/accounts/me/username` | 5 / day / account |
| `GET /v1/directory/*` | 60 / hour / account |
| `GET /v1/keys/{id}` | 120 / hour / account |
| `PUT /v1/messages/*` | 600 / min |
| `POST /v1/messages/ephemeral` | 3000 envelopes / min (each item counts) |
| `GET /v1/ws` (upgrades) | 60 / min |
| WebSocket frames, C→S | 1200 / min / account (then close `1008`) |
| `POST /v1/media` | 60 / hour / account, 2 GiB / day / account |
| `GET` / `DELETE /v1/media/{id}` (together) | 600 / min |
| any authenticated request | 1200 / min / account |
| failed `X-Auth-Token` checks | 30 / min |

A request over a limit gets `429 rate_limited` with `Retry-After`. Every
request answered `401` (and every WebSocket upgrade refused for its token)
counts as a failed `X-Auth-Token` check; while an IP is over that limit, the
relay answers its authenticated requests with `429` before looking the
token up.

## 5. Keys and sessions (client)

* Identity: libsignal `IdentityKeyPair`, generated once on the device. It
  never leaves the device except inside an account backup the user exports
  (§5.5). At rest it is protected by the secret-code vault (§5.2).
* `registrationId`: random 1..16380.
* Signed pre-key (EC) and Kyber last-resort key (`KYBER_1024`): rotate every
  30 days; keep the previous ones for 30 more days to decrypt late messages.
* One-time pre-keys: upload 100 EC + 100 Kyber; refill to 100 when the
  status endpoint reports fewer than 20.
* Sessions: `processPreKeyBundle` with the bundle from §4.5, then
  libsignal `signalEncrypt` / `signalDecrypt(PreKey)`.
* Identity change of a known contact (TOFU violation) MUST block sending and
  show a "safety number changed" warning until the user accepts. Receiving
  continues (§6.3).
* Safety number: libsignal numeric fingerprint (iterations 5200, version 2),
  stable identifier = account id bytes (16), keys = identity `PublicKey`s.
  Shown as digits and QR.
* Signatures: signed pre-key signature = identity private key XEdDSA over the
  33-byte serialized EC public key; Kyber signature = over the serialized
  `KEMPublicKey`. Key ids are 1..16777215 (never `0xFFFFFFFF`, which
  libsignal uses as a sentinel).
* Kyber pre-keys are **mandatory** (libsignal ≥ 0.73 rejects X3DH); SPQR
  (triple ratchet) is always on in v0.105.
* libsignal's `InMemorySignalProtocolStore` does not delete one-time Kyber
  keys; our stores MUST delete a one-time Kyber key in `markKyberPreKeyUsed`
  and keep the last-resort key (tracking `(kyberId, signedId, baseKey)` to
  reject reuse).

### 5.1 Content padding

Before session encryption the serialized `Content` protobuf is padded:
append `0x80`, then `0x00` bytes until the total length is a multiple of
160. (If the length is already a multiple of 160 a full block is still
added.) Receivers strip trailing zeros and then exactly one `0x80`; anything
else is a decryption failure.

### 5.2 Secret code and the local vault

When creating an account the user chooses a **secret code** (gizli kod) of
**six digits**. It protects **all** data on the phone: identity keys,
sessions, contacts, messages, media, settings. It is never sent anywhere;
the relay never sees it or anything derived from it. It is independent of
the username, so changing the username changes nothing here. It is asked
for at every launch and on every return from the background (§5.4).

**Format.** A secret code is exactly six ASCII digits `0`–`9`
(U+0030..U+0039), e.g. `472913`; a leading `0` is allowed. Nothing is
trimmed, normalized or case-mapped: any other input (five or seven
characters, spaces, fullwidth `１` or other scripts' digits such as `١`) is
not a secret code. The apps enter it with their own digit keypad.

**Rules for a new code.** The first rule that applies is the reported
problem (named as in the `secret-code.json` test vectors):

1. `format`: the input is not exactly six ASCII digits.
2. `repeated`: all six digits are equal (`000000` … `999999`, 10 codes).
3. `sequence`: every step between consecutive digits is +1 modulo 10, or
   every step is −1 modulo 10: a run up or down the digit circle
   `0123456789`, which wraps from `9` to `0`. These are exactly 20 codes:
   `012345` … `567890`, `678901`, `789012`, `890123`, `901234` and their
   reverses (`654321`, `321098`, `098765`, …).
4. `common`: the code is in `spec/common-secret-codes.txt` (bundled in both
   apps): a short list of frequent six-digit PINs and trivial patterns such as
   `121212`, `112233`, `123123`, `696969`, `111222`, `123321`, `159753`,
   `147258`, `102030`. Every entry is six ASCII digits and none is already
   covered by rules 2–3, so the order of the rules never matters for a
   listed code.

Everything else is accepted, dates included (`200590`, `200000`): dates only
help someone who knows the user, and that person still meets the delays
below. The UI moves on by itself once the sixth digit is entered, asks for the
code a second time to confirm it, and makes the user confirm that a
forgotten code cannot be recovered.

Six digits are enough only because guessing is confined to the phone: the
vault key also depends on a `deviceSecret` that never leaves the device, so
a copied vault file cannot be attacked elsewhere, and on the phone every
guess goes through the wrong-attempt delays below. (See §9 for what this
does not protect against.)

Key hierarchy:

```
DEK          = 32 random bytes                       // data encryption key
deviceSecret = 32 random bytes                       // Keychain ...ThisDeviceOnly / Android Keystore-wrapped;
                                                     // never exported, never in backups
salt         = 16 random bytes
codeKey      = PBKDF2-HMAC-SHA256(UTF-8(code), salt, iterations = 600000, dkLen = 32)   // the six ASCII bytes
KEK          = HKDF-SHA256(ikm = codeKey ‖ deviceSecret, salt = salt, info = "BRP/vault/v1", L = 32)

vault file   = header ‖ nonce(12) ‖ AES-256-GCM(KEK, nonce, aad = header, DEK) ‖ tag(16)
header       = "BRPV" (4 ASCII bytes) ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)     // 25 bytes
```

Implementations MUST refuse to create or open a vault with input that is not
a secret code (rule 1) before deriving anything. (For six ASCII digits
`UTF-8(NFKC(code)) = UTF-8(code)`, so the KDF input is unchanged from earlier
drafts.)

* **Unlock** = derive KEK from the code and open the vault. A wrong code is
  a GCM failure. Because `deviceSecret` never leaves the phone, a copied
  vault file cannot be brute-forced elsewhere. Readers MUST reject a header
  whose `iterations` is outside 1000..10000000 before deriving anything.
* **Biometric quick unlock** (optional, "Face ID ile aç"): the DEK is
  additionally wrapped by a hardware key that requires biometrics (iOS
  Secure Enclave key with `.biometryCurrentSet`; Android Keystore key with
  `setUserAuthenticationRequired(true)` + `BiometricPrompt`). At launch the
  biometric prompt starts by itself; when it fails or is cancelled the code
  keypad opens. The code is still required at setup, after 7 days without
  entering it, after the enrolled biometrics change, and after 5 failed
  biometric attempts.
* **Change code** = re-wrap the DEK (instant; no data is re-encrypted). The
  new code follows the rules above.
* **Wrong attempts**: after the N-th consecutive wrong code the next attempt
  is allowed only after D(N):

  | N (wrong codes in a row) | 1 | 2 | 3 | 4 | 5 | 6 | ≥ 7 |
  |---|---|---|---|---|---|---|---|
  | D(N), wait before the next attempt | 0 | 0 | 1 min | 5 min | 15 min | 30 min | 1 h |

  The unlock screen says in advance what the next wrong codes cost: with
  N wrong codes so far, the next delay is reached after k more, where k is
  the smallest number with D(N + k) > 0 ("Kod yanlış. 2 yanlış daha olursa
  1 dakika beklemen gerekir." / "Wrong code. 2 more wrong attempts and
  you'll have to wait 1 minute." after the first one).
  An attempt MUST be recorded as a failure before the code is checked and
  cleared only after a successful unlock, so killing the app during the slow
  derivation gains nothing. Input that is not a secret code (rule 1) is
  refused before that and is not counted; neither are failures that are not
  a wrong code (missing device secret, corrupt file).
  The delay MUST be measured with a clock the user cannot set: the time
  since boot including sleep `t` (iOS `CLOCK_MONOTONIC`, Android
  `SystemClock.elapsedRealtime()`). A failure is stored with `waited = 0`
  and the current reading `t₀`. Whenever the app checks the delay it adds
  `t − t₀` to `waited` if `t ≥ t₀`, otherwise `t` (the phone has restarted
  and only the time since boot counts), then stores `waited` and `t₀ = t`.
  The next attempt is allowed once `waited ≥ D(N)`. `waited` is a lower
  bound of the real time that has passed and never decreases, so changing
  the date or restarting the phone never shortens a delay. The wall clock is
  used only to show when the next attempt is allowed.
  Optional "wipe after 10 wrong attempts" (default off): the 10th wrong code
  in a row crypto-shreds everything (§5.3).
* **Duress code** (optional): a second six-digit code, following the same
  rules and different from the secret code; entering it silently
  crypto-shreds everything (§5.3) and shows a fresh install.
* **Forgotten code**: the data is unrecoverable, and there is no reset of any
  kind (no reset link, support request or security question). The app offers
  only what is real: restore from an account backup (§5.5), or **erase
  everything** and start over, which asks for a second confirmation and then
  crypto-shreds the vault, the device secret and all key slots (§5.3).

### 5.3 Local storage and crypto-shredding

* The database is SQLCipher with `dbKey = HKDF-SHA256(DEK, salt = empty,
  info = "BRP/db/v1", 32)`. Files are AES-256-GCM encrypted.
* Every conversation has its own **conversation key** `CK` (32 random bytes).
  Message bodies, attachments and thumbnails of that conversation are
  encrypted with `CK` (AES-256-GCM, random 12-byte nonce per item) before
  they are written to the database or the file system.
* `CK` lives in a **key slot**: a small file named by a random 16-byte slot id
  (hex), with
  ```
  slotKey  = HKDF-SHA256(DEK, salt = empty, info = "BRP/slot/v1", L = 32)
  slotFile = "BRPS" ‖ 0x01 ‖ nonce(12) ‖ AES-256-GCM(slotKey, nonce, aad = "BRPS" ‖ 0x01 ‖ slotId(16), CK) ‖ tag(16)
  ```
* Items encrypted with `CK` (message bodies, media files, thumbnails):
  ```
  item = 0x01 ‖ nonce(12) ‖ AES-256-GCM(CK, nonce, aad = 0x01 ‖ itemId(16), plaintext) ‖ tag(16)
  ```
  `itemId` is the message id (or a random id for files), so items cannot be
  swapped between rows.
* **Deleting the slot file destroys the conversation instantly**, without
  needing the DEK. Ghost-mode timeouts, `ConversationWipe` and the duress
  code therefore work even while the app is locked or closed: a local OS
  timer (Android `AlarmManager` exact alarm, iOS `BGTaskScheduler`) or the
  next app start just deletes slot files whose deadline passed. The leftover
  ciphertext is garbage-collected at the next unlock.
* Ghost deadlines are kept in a background-readable schedule file containing
  only `{slotId, deadline}` (no names, no content).

### 5.4 Receiving only while the app is open

There are no push notifications (§1). The phone talks to the relay only while
the app is open and unlocked:

1. On open: unlock the vault (secret code or biometrics), execute overdue
   wipes (§5.3, §8.2), connect the WebSocket, receive the backlog until
   `queue_empty`, acknowledge as messages are stored locally.
2. While open: new messages arrive live over the WebSocket (online fast
   path, §1.1). Messages for other conversations show as in-app banners.
3. On background: keep the connection for a **30-second grace period**
   (iOS `beginBackgroundTask`; Android simply keeps the process), so quickly
   switching to another app and back loses nothing. Messages arriving during
   the grace period are received and stored normally; the app MAY show a
   local "New message" notification for them (no push service is involved).
   After the grace period: send presence OFFLINE (§8.6) and close the
   WebSocket. Envelopes that were in flight are persisted by the relay
   (fast path, §1.1), so nothing is lost; they arrive on the next open.
   After that nothing runs in the background except local wipe timers (no
   network) and, only while the user is actively sharing live location,
   location updates (§8.3).
4. App lock: the app is locked at every launch and every time it goes to the
   background ("Otomatik kilit: Hemen" / "Auto-lock: Immediately"); returning
   asks for biometrics or the secret code again (§5.2). The app-switcher
   snapshot is always covered, so no content is readable in it. The DEK MAY
   stay in memory until the end of the grace period (step 3) so that
   messages arriving meanwhile can be stored; it MUST be erased from memory
   then.

Consequences the apps MUST explain to users (onboarding, settings, website;
TR + EN, see `docs/copy/no-push.md`):

* There is no notification when the app is closed. Open the app to check.
* A message waits on the relay until the recipient opens the app, at most
  **14 days**; after that it is deleted undelivered.
* Senders see a clear status: *Sent* (accepted by the relay, waiting for the
  recipient to open the app) → *Delivered* (delivery receipt received) →
  *Read* (optional). After 14 days without delivery: *Not delivered*.
* Delivery receipts are therefore always sent (except for message requests);
  read receipts and typing indicators remain optional.

### 5.5 Account backup and restore (no server involved)

The user can export an **account backup file** and keep it wherever they want
(Files, AirDrop, iCloud Drive, Google Drive, a USB stick). It is never
uploaded to the relay. Because the secret code cannot be reset, this file is
the user's only safety net.

The file is encrypted with a separate **backup password** (yedek parolası),
never with the secret code. A backup file can be attacked offline wherever it
is stored, without any device secret, so it needs a long password; the six
digits of §5.2 would fall to a brute-force search at once.

**Backup password rules** are checked on `p = NFKC(trim(password))`
(trimming Unicode `White_Space` as in §3.1) and, except for the length, on
`lower(p)` (locale-independent default casing, §3.1), so every comparison
is case-insensitive. The first rule that applies is the reported problem
(named as in the `backup-password.json` test vectors):

1. `too_short`: `p` has fewer than 10 code points.
2. `common`: `lower(p)` is in `spec/common-backup-passwords.txt` (bundled in
   both apps; each entry NFKC-normalized and lowercased the same way).
3. `repeated`: all code points of `lower(p)` are equal (so `AAAAAaaaaa` too).
4. `sequence`: `lower(p)` is a simple sequence: every step between
   consecutive code points is +1, or every step is −1 (`abcdefghij`,
   `jihgfedcba`); or it is a contiguous run, read in either direction, of one
   of these keyboard rows of the Q (US and Turkish) and Turkish F layouts:
   `1234567890`, `qwertyuiop`, `asdfghjkl`, `zxcvbnm`, `qwertyuıopğü`,
   `asdfghjklşi`, `zxcvbnmöç`, `fgğıodrnhpqw`, `uieaütkmlyşx`, `jövcçzsb`.

With at least 10 characters a backup password can never be the six-digit
secret code ("Gizli kodundan farklı olmalı"). The export UI asks for it twice
and says that without it the backup cannot be opened by anyone, us included.
The restore UI asks for it once. Rules are checked only when a password is
chosen, never when a backup is opened.

```
backupKey = PBKDF2-HMAC-SHA256(UTF-8(NFKC(password)), salt(16 random), iterations = 1000000, dkLen = 32)
file      = header ‖ nonce(12) ‖ AES-256-GCM(backupKey, nonce, aad = header, AccountBackup protobuf) ‖ tag(16)
header    = "BRPB" ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)                          // 25 bytes
```

The KDF input is the password exactly as entered, NFKC-normalized and
**never trimmed**: the rules above see the trimmed form, the KDF does not.
Readers MUST reject `iterations` outside 1000..10000000 before deriving
anything.

It contains the account id, auth token, identity key pair, registration id,
username, display name, access key and contacts (`AccountBackup` in
`brp.proto`). It does **not** contain messages; message history moves only by
direct phone-to-phone transfer (planned). (Planned: the iOS design's backup
screens also carry message history and media, never view-once media or
disappearing messages. That needs a streaming, chunked file format and is
not part of v1.) A contact whose identity change the
user has not accepted is exported with `identity_unaccepted = true` and
restored as changed (sending blocked until accepted, §6.3); every other
identity key is restored as trusted.

Restore on a new phone:

1. Decrypt with the backup password and verify the **whole** backup (version,
   every field's size and format) before contacting the relay. A backup that
   fails here changes nothing.
2. Create a new vault (§5.2) for the restored account, under a secret code
   the user chooses on this phone (it need not be the old phone's code).
3. `POST /v1/accounts/me/token` (rotate; the old phone loses access). The
   client MUST persist the rotated token before uploading keys; the
   backup's token no longer works.
4. Generate new signed, Kyber and one-time pre-keys and `PUT /v1/keys` with
   `replaceAll: true`. The client MUST retry this upload, also after a
   restart, until it succeeds: until then the relay still hands out pre-keys
   whose private keys are gone.
5. The client SHOULD send a `ProfileUpdate` (§8.7) to every contact. The
   restored phone has no sessions, and contacts keep encrypting to the old
   phone's sessions (which it cannot decrypt) until the restored phone
   writes first.

Because the identity key is the same, contacts see **no** safety-number
change; their sessions are replaced by the restored phone's next message.

## 6. Sealed sender envelope

### 6.1 Format

```
envelope = 0x01 ‖ HPKE-Seal(pkR = recipient identity PublicKey,
                             info = "BRP/sealed/v1",
                             aad  = 0x01,
                             pt   = SealedContent protobuf)
```

### 6.2 Encryption

HPKE (RFC 9180) as implemented inside libsignal, which both platforms call:

* Swift: `recipientIdentityKey.publicKey.seal(sealedContent, info: "BRP/sealed/v1", associatedData: Data([0x01]))`;
  open with `identityKeyPair.privateKey.open(ct, info: "BRP/sealed/v1", associatedData: Data([0x01]))`.
* Kotlin: `ECPublicKey.seal(sealedContent, "BRP/sealed/v1", byteArrayOf(0x01))`;
  open with `ECPrivateKey.open(ct, "BRP/sealed/v1", byteArrayOf(0x01))`.

Each envelope uses a fresh HPKE encapsulation, so two envelopes are never
linkable by their bytes. Any failure of the envelope itself (unknown
version, HPKE open failure, protobuf failure, a failed check in §6.3) →
silently drop the envelope and acknowledge it. A relay lookup that could not
be completed is not such a failure (§6.3 step 4).

### 6.3 Sender verification

1. Open the envelope → `SealedContent`.
2. If `type == 3` (PreKeySignalMessage) and its identity key differs from the
   one stored for the sender (or none is stored): fetch
   `GET /v1/accounts/{sender}/identity` (cache it) and require that it equals
   the identity key inside the PreKeySignalMessage. If it equals the stored
   key, no lookup is made.
3. For any `type`: if `sender_username` is not empty, require that it equals
   its own normalization (§3.1) and that the directory entry for
   `usernameHash(sender_username)` has the sender's account id (cache it).
4. A failed check in steps 2–3 drops the envelope, and so does a `404` from
   either lookup. A lookup that fails with a network error, `429` or `5xx`
   is not a verdict: the envelope is left unacknowledged and processed again
   later.
5. Decrypt with libsignal for `ProtocolAddress(sender, 1)`. libsignal's
   identity store enforces TOFU, the Signal way: a message from a known
   contact under a changed identity key is accepted for receiving, the
   contact is flagged CHANGED ("safety number changed"), and sending to it
   is blocked until the user accepts the new key (§5).
6. Unpad (§5.1) and parse `Content`.

### 6.4 Message requests

Each phone keeps one state per contact:

| State | Meaning |
|---|---|
| `OUTGOING_REQUEST` | we wrote first; the contact has not accepted yet |
| `INCOMING_REQUEST` | the contact wrote first; we have not accepted yet |
| `ACCEPTED` | both sides may talk freely |
| `BLOCKED` | messages from the contact are dropped locally |

Transitions:

* Sending to an account without state creates `OUTGOING_REQUEST`; a verified
  message (§6.3) from an account without state creates `INCOMING_REQUEST`.
* `OUTGOING_REQUEST` → `ACCEPTED` when `MessageRequestResponse{ACCEPT}`
  from that contact is received, or when both sides wrote first ("crossing
  requests"): a verified visible message (`text`, `attachment`, `location`,
  `sticker`) that carries `sender_username` from an account we are in
  `OUTGOING_REQUEST` with — only an `OUTGOING_REQUEST` carries it — means
  the contact is in `OUTGOING_REQUEST` with us too, and neither side could
  ever accept. The receiving client then enters `ACCEPTED` and puts
  `MessageRequestResponse{ACCEPT}` first in its queue to that contact
  (followed by the access-key exchange below), so the other phone ends its
  request too even if it does not apply this rule. No other message ends a
  request, so both phones always agree on whether the conversation is
  still one.
* `INCOMING_REQUEST` → `ACCEPTED` when the user accepts: the client sends
  `MessageRequestResponse{ACCEPT}`, its first message to the requester, and
  enters `ACCEPTED` once the relay has answered `202`; if the send fails,
  the state is unchanged and accepting can be retried.
* Any state → `BLOCKED` on **Block**: the client rotates its access key (new
  key to all other accepted contacts) and drops further messages from that
  account locally.

Delivery: in `OUTGOING_REQUEST` every message includes `sender_username`
and is delivered with PoW, even if the contact's access key has already
arrived. In every other state `sender_username` is omitted, and messages are
delivered with `X-Access-Key` once the contact's key is held, with PoW until
then. Receipts, typing indicators and presence are sent only in `ACCEPTED`
(§8.4, §8.6).

Access-key exchange: the accepting side sends `ACCEPT`, then
`AccessKeyUpdate{its access key}`. The requester, after receiving `ACCEPT`,
sends `AccessKeyUpdate{its access key}` at the latest before its next other
message to that contact (it SHOULD do so as soon as it holds the accepting
side's key). After the exchange each side sends its `ProfileUpdate` (§8.7).

## 7. Attachments

```
key    = 64 random bytes: aesKey = key[0..32), macKey = key[32..64)
iv     = 16 random bytes
padded = plaintext ‖ 0x00 * (P(n) − n)
P(n)   = 541                                   if n ≤ 541
       = smallest s_i ≥ n of the sequence s_0 = 541, s_{i+1} = s_i + max(1, floor(s_i / 20))
         (integer arithmetic only, so every platform pads identically)
blob   = iv ‖ AES-256-CBC-PKCS7(aesKey, iv, padded) ‖ HMAC-SHA256(macKey, iv ‖ ciphertext)
digest = SHA-256(blob)
```

The sender uploads `blob` (§4.9) and sends an `Attachment` with `media_id`,
`delete_token`, `key`, `digest` and the real `size`. The receiver MUST check
`digest` and the HMAC (constant time) before decrypting, then truncate to
`size`; a `size` larger than the decrypted padded plaintext is an integrity
failure. Receivers SHOULD stop a download after
`blobSize(size) = 16 + 16·(floor(P(size)/16) + 1) + 32` bytes and MAY refuse
sizes above 2^40. EXIF/GPS metadata MUST be stripped from images and videos
before encryption unless the user explicitly chooses to keep it.

## 8. Message semantics (client)

### 8.1 Disappearing messages
`DataMessage.expire_timer_seconds = T > 0`: the sender deletes its copy T
seconds after sending; the recipient deletes T seconds after the message was
first displayed (read). Attachments and thumbnails are deleted with it, and
so are decrypted copies made for display.

A message removed entirely (expired, "delete for me") stays removed: the
client remembers its `message_id` for at least 30 days (a sender retries for
up to 14 days and the relay keeps a retransmission 14 more), so a
retransmission is a duplicate. An outgoing message removed before the relay
accepted it (and not being handed over at that moment) is not sent at all;
"delete for everyone" of such a message sends no `Delete` either.

### 8.2 Ghost mode
A conversation-level setting (`ConversationSettings.ghost`), synchronized with
last-writer-wins. `ConversationSettings` are applied only from accepted
contacts (never from a message request), and an `updated_at` more than 24 h
ahead of the local clock is clamped to that bound, so a far-future stamp
cannot win forever. Settings written while the conversation was our
outgoing request are sent again once it is accepted. With ghost mode on and
timeout G:

* Each side keeps `lastActivity` = local time of the most recent sent or
  received `DataMessage`. Live-location ticks do not count; reactions and
  deletes do.
* When `now ≥ lastActivity + G`, the device wipes the **entire conversation**:
  every message, attachment, thumbnail, draft and the thread itself. With
  `wipe_contact = true` it also deletes the contact and the libsignal session.
* The side whose timer fires first also sends `ConversationWipe{GHOST_TIMEOUT}`
  so the other phone wipes even if its clock or scheduler lagged.
* Every new message resets the timer on both sides.
* The UI shows the remaining time in the conversation header.

### 8.3 Live location
`LiveLocation` messages share a `session_id`. The start and the stop messages
are sent normally; intermediate updates (every 15–30 s) are sent with
`ephemeral=1`. Receivers update one map bubble per session in place and
stop showing it after `expires_at` or `stopped = true`. Because sharing is an
explicit user action with a visible OS indicator, the sharer's app keeps
sending updates in the background (iOS background location mode, Android
foreground service with a persistent notification) until `expires_at` or
stop. Receiving still happens only while the app is open.

In a conversation with a disappearing timer T (§8.1) the start, the updates
and the stop carry `expire_timer_seconds = T`. The bubble, which ends up
holding the last precise position, disappears like any message, but not
before its sharing ends: at `max(start + T, end)`, where `start` is the time
it was sent (sharer) or first displayed (receiver) and `end` the stop or
`expires_at` (receivers cap `expires_at` at 9 h after receipt).

### 8.4 Receipts and typing
Never sent for message requests. Delivery receipts are always sent for
`text`, `attachment`, `location` and `sticker` messages from accepted
contacts (they are the sender's only signal that the recipient opened the
app, §5.4), and for nothing else. They are batched per contact; one
`ReceiptMessage` carries at most 1000 ids. Read receipts and typing
indicators are optional and user-controllable. Typing uses `ephemeral=1`.

### 8.5 Delete for everyone / reactions / replies
`Delete`, `Reaction` and `Quote` reference `message_id` values. A `Delete`
from the original sender removes the message on the recipient's device.

`Quote.preview_text` is taken from the stored target, never typed by the
caller, and only from a target that stays: no timer, not view-once, not
deleted. A client stores and shows a quote's excerpt only while its target
is still present on the phone, and clears it in replies when the target
expires, is deleted or is opened (view-once).

A `view_once` attachment carries no `thumbnail`, `caption` or `waveform`
(senders leave them empty, receivers drop them): nothing of it is visible
before it is opened. Opening it destroys it on the phone (body, file,
descriptor) and queues `ReceiptMessage{VIEWED}` in the same transaction, so
it cannot be opened twice, also not after a crash while it is shown.

### 8.6 Presence ("active now")

End-to-end encrypted and event-driven; the relay keeps no presence state and
learns no contact list from it.

* **Who:** only accepted contacts (never message requests, never blocked
  accounts), and only when **both** sides have `presence_sharing = true`
  (shared via `ProfileUpdate`, §8.7). A contact's `presence_sharing` counts
  as false until its first `ProfileUpdate` arrives. It is reciprocal:
  whoever hides their status cannot see others'. Default for the own
  setting: on. Settings: "Show when I'm active" (TR: "Aktif olduğumu
  göster").
* **Coming online:** after connecting and receiving the backlog, the app sends
  `PresenceMessage{ONLINE, valid_for_seconds: 300}` to all eligible contacts
  in **one** `POST /v1/messages/ephemeral` request. Contacts whose app is open
  receive it and answer once with `PresenceMessage{ONLINE, reply: true}` (an
  ephemeral send to that contact only). Contacts whose app is closed receive
  nothing, and nothing is stored.
* **Staying online:** every 240 s the app refreshes ONLINE only to the
  contacts it currently sees as online (usually a handful).
* **Going offline:** at the end of the background grace period (§5.4) or when
  the app closes, the app sends `PresenceMessage{OFFLINE}` to the contacts it
  sees as online. If it crashes or loses network, `valid_for_seconds` expires.
* **Display:** a green dot and "Aktif" / "Active" while ONLINE is valid; after
  OFFLINE: "Az önce aktifti" / "Active just now" (< 5 min), then
  "N dk önce aktifti" / "Active N min ago" up to 60 minutes, then nothing.
  No long-term "last seen" is kept or shown. Receivers cap
  `valid_for_seconds` at 300; an ONLINE that expires without OFFLINE counts
  as going offline at the time that ONLINE was received.
* Typing indicators (§8.4) remain separate and optional.

### 8.7 Profile updates

* Every `ProfileUpdate` is complete: it sets every field (`username`,
  `display_name`, `presence_sharing`, `updated_at`, and `avatar` if there is
  one), and the receiver replaces its stored profile of the sender with it.
  An empty field means "none" (e.g. no avatar).
* It is sent once to each accepted contact after the access-key exchange
  (§6.4), and again to every accepted contact on each change.
* `username` is a claim; it is shown as verified only as described in §3.3.

## 9. Known limits (stated honestly)

* A modified client or a camera can keep messages that "disappear". Android
  blocks screenshots with `FLAG_SECURE`; iOS can only detect them
  (`ScreenshotNotice`).
* The relay sees: IP addresses at connection time (use Tor/VPN to hide), the
  recipient account id of each envelope, envelope sizes (padded) and timing,
  and who uploads media. It cannot see content, senders of sealed messages,
  usernames in clear, contacts or profiles.
* Bundle, identity and directory lookups (§4.3–§4.5) are authenticated, so
  the relay learns, once per new contact, which account looked up which.
* A hard crash of the relay (SIGKILL, power loss) can lose fast-path
  envelopes (§1.1) that were held in memory and not yet acknowledged. The
  sender then sees "Gönderildi" / "Sent" without "İletildi" / "Delivered"
  and can resend. A graceful shutdown loses nothing.
* Apple and Google are not involved at all: no push tokens, no push payloads.
* Presence (§8.6) is end-to-end encrypted and the relay stores nothing about
  it, but each app start sends one batch of envelopes to all eligible
  contacts. A relay operator who logs IP addresses and timing could use those
  batches to reconstruct contact lists faster than from normal messages. Our
  relay does not log; users who need protection against a hostile relay can
  turn presence off (or use Tor/VPN).
* Phones only run when the OS lets them. A ghost/disappearing deletion
  happens at the first moment after the deadline that the app or its local
  wipe timer runs (Android exact alarms are reliable; iOS background tasks are
  best-effort, so on iOS it may happen at the next app start). Until then the data stays encrypted at
  rest under the secret code, and it is never displayed after its deadline.
* The secret code has only 10^6 values. It protects the data because every
  guess needs this phone's device secret (§5.2) and so goes through the app
  and its delays. Someone who can read the device secret and the vault file
  out of the phone itself (a jailbroken or rooted phone, a forensic exploit
  of the operating system) can try every code offline within minutes. A strong
  phone passcode and an up-to-date OS are the defence against that. The
  backup file (§5.5) does not have this limit: it uses a long, separate
  backup password.
* Forgetting the secret code means losing the data. There is no reset; only
  a backup (and its password) brings the account back.
