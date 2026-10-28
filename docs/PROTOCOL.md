# BRP — Blind Relay Protocol, v1 (used by us2us)

Status: **draft, normative**. This document is the contract between the relay
(`relay/`), the iOS client library (`ios/Us2usKit`) and the Android/JVM
client library (`android/core`). If code and this document disagree, one of
them is a bug — fix it in the same change.

Key words MUST, SHOULD, MAY are used as in RFC 2119.

> **Change log**
> * 2026-10-28: **Android client fixes of the security audit** (§4.2, §5,
>   §5.2, §6.3, §6.4, §7, §9; client only, no wire change). Every decrypted
>   `Content` passes the structural checks iOS already applied (16-byte ids,
>   fixed-size keys and tokens, the ghost timeout range, one valid community
>   kind) before anything acts on it; one that fails is dropped and
>   acknowledged (§6.3). A message the app could not store is left at the
>   relay while the envelopes behind it are handled, and is dropped and
>   acknowledged after 3 failed attempts; an unavailable local store never
>   counts. A blocked sender's envelope is dropped before decryption.
>   `OUTGOING_REQUEST` counts for `ACCEPT` and crossing requests only once
>   the relay accepted a visible message of ours (§6.4); the crossing rule
>   needs `sender_username`. "Delete contact" now rotates our access key when
>   the deleted contact held it (§6.4). Adding a contact from a contact code
>   uses the one directory answer the code was checked against and pins its
>   key before anything is created (§5). A return to the approved key
>   restores its verification (§5). Proof-of-work challenges above 28 bits
>   are refused and a solve gives up after 3 minutes (§4.2). Attachment
>   downloads refuse a declared size whose blob exceeds 1 GiB (§7). The
>   Android vault statement of §5.2 and §9 is corrected (format v2 binds the
>   hardware key to device unlock only from API 35).
> * 2026-10-28: **relay abuse limits** (§4.2, §4.3, §4.5, §4.6, §4.9,
>   §4.10, §4.11; relay only, additive, no client change and no new error
>   code; clients 1.1.4 and 1.1.5 work unchanged). Envelopes authorized by
>   proof-of-work may fill only a share of a mailbox (500 envelopes / 8 MiB
>   by default, at most half), so strangers cannot lock out the recipient's
>   contacts; a send past it is the same `429 rate_limited` as a full
>   mailbox. The relay stores one bit per stored envelope (authorized by
>   proof-of-work or not) and nothing about the sender. Stored sends are
>   also limited per recipient and client address (§4.11), in memory under
>   a key that exists only in the running process. Media uploads must leave
>   a reserve of free disk that only envelopes may use, are capped per
>   account (unexpired bytes stored, lower for accounts in their first 7
>   days) and per relay per day (`503` with `Retry-After`); messages are not
>   affected. The registration difficulty rises with the recent
>   registration rate; challenge tokens carry the difficulty they were
>   issued with (still opaque; clients already solve the `difficulty` of the
>   response). Registrations are limited per IPv6 /48 and per relay too.
>   The relay stores each account's registration date (UTC day only).
>   A bundle fetch beyond a per-target hourly budget carries no one-time
>   keys (as if none were left) instead of consuming them. Stored envelope
>   ids count per recipient within a millisecond, not relay-wide. Rate limits
>   use the proxy's address header only from configured proxy peers.
> * 2026-10-27: **deleting a chat or a contact on one phone** (§3.3, §6.4;
>   client only, no wire change). "Delete chat" crypto-shreds the
>   conversation (§5.3) and keeps the contact, its session and its state;
>   a running live-location share with the contact still gets its stop.
>   "Delete contact" also removes the contact (state, profile, picture,
>   access keys) and its pending sends, but **not** its session and
>   identity, and it keeps the username the contact was shown under; a
>   blocked contact keeps only `BLOCKED`, as before. Nothing is sent, the
>   contact is not blocked and the access key is not rotated (that is
>   Block). If the contact writes again, its message still decrypts on the
>   kept session and opens a new `INCOMING_REQUEST` under the kept name (an
>   unchecked claim, §3.3); a changed identity key is still flagged (§6.3
>   step 5). New receiver rules: a verified visible message carrying
>   `sender_username` from a contact we are `ACCEPTED` with means it deleted
>   us and wrote as a new request; we answer with
>   `MessageRequestResponse{ACCEPT}`, our access key and our profile, so its
>   request ends. `MessageRequestResponse{ACCEPT}` from a contact we are
>   `ACCEPTED` with means it deleted us and accepted the request our
>   message became there; we send our access key and profile again. Older
>   builds do neither. Our own directory lookup that maps a username to
>   account A marks every other contact claiming that name as a mismatch,
>   which is never shown as its name, and drops it as a kept name.
> * 2026-10-15: Android secure-item wrapping-key maintenance (local only,
>   §5.2): use unlocked-device-required only on API 35+, atomically migrate
>   all legacy items after successful unlock, and retire the old key only
>   after the new generation is verified and durable. Device secret/DEK,
>   backups, libsignal sessions and 1:1 wire messages are unchanged.
> * 2026-10-14: **communities extension v2** ([COMMUNITIES.md](COMMUNITIES.md),
>   "Extension v2"; additive, no relay or 1:1 change): `CommunityMessage`
>   fields 6–9 (`attachment`, `receipt`, `delete_target_message_id`,
>   `delete_target_sender_id`) and the new `CommunityReceipt`. Photos,
>   videos and files are uploaded once (§4.9, §7) and shared by every member
>   without a delete token; DELIVERY and READ receipts go only to the
>   original sender, READ under the read-receipt setting (§8.4); delete for
>   everyone by the sender or, for moderation, the owner. v1 community
>   builds drop the new kinds.
> * 2026-10-12: **the Android client rotates tokens as §5.5 says** (§5.5
>   steps 3–3.5; client only, no wire change): `android/core` and the
>   Android app now choose the new token, keep it as pending before the
>   request, settle a pending rotation before every connection, and treat a
>   `400` while settling as the 2026-10-11 entries below describe; their
>   "the Android client still needs" notes for §5.5 are done.
> * 2026-10-11: **saving and pictures, said precisely** (§8.1, §8.2, §8.4,
>   §8.10, §9; no wire change): SAVED covers saving *or handing on* a
>   received photo, video or file outside the app (photo library, Files, the
>   share sheet); the client MUST explain before the first such save on an
>   account that the sender will be told, and SHOULD repeat it at the save
>   controls. A list of a conversation's media kept outside the conversation
>   never shows disappearing items. Copies saved outside the app survive a
>   ghost wipe. Fetching a requester's picture after "Show picture" tells the
>   relay which account the requester probably is; the "Show in search"
>   explanation now says that turning it off does not recall copies already
>   fetched and that the relay can open a picture by guessing the username.
> * 2026-10-11: **preset catalog version 1** (§8.9): `preset_id` 1–22, the
>   cup mascot presets of the apps' `BrandAvatarCatalog`, with their keys and
>   Turkish and English labels. Only these ids may be sent; a receiver that
>   does not know an id keeps showing the initial letter. The iOS app now
>   shows pictures (chat list, chat header, 4.2 search result after an exact
>   hit, the live map, the own code) and edits the own one on 7.1 (initial,
>   preset, a photo from the system picker or the camera, cropped on the
>   phone) with the "Aramada göster" / "Show in search" switch and its
>   explanation; an incoming request shows the initial until the user taps
>   "Profil resmini göster" / "Show picture" on the opened request. Backward
>   compatible: builds 4–8 never send a preset; no relay change.
> * 2026-10-11: **saved media is acknowledged** (§8.4): new
>   `ReceiptMessage.Type` `SAVED` = 4. The recipient of a photo, video or
>   file (`Attachment` kind `IMAGE`, `VIDEO`, `FILE`; never view-once, never
>   with a disappearing timer — those cannot be saved) sends it to the
>   accepted contact when the user saves the item outside the app, whatever
>   the read-receipt setting, after being told that the sender will know.
>   Receivers apply it only to their own such messages to that contact and
>   ignore it for anything else. Backward compatible: TestFlight builds 4–8
>   ignore an unknown receipt type; no relay change. Implemented in
>   `ios/Us2usKit`, `ios/Us2usAppCore` (local schema v6 keeps when an item
>   was saved by either side) and `android/core` (the rules and the send;
>   storing it is the Android app's).
> * 2026-10-11: **profile pictures, also in search** (product decision; §1,
>   §1.1, §2.1, §3.3, §4.3, §4.10, §4.11, new §4.12, §5.5, §8.7, new §8.9
>   and §8.10, §9): a user keeps the initial letter, picks a built-in preset
>   or uploads a photo (≤ 256 × 256 JPEG or HEIC, no metadata). Accepted
>   contacts get it end-to-end encrypted in every `ProfileUpdate` (new field
>   `profile_avatar` = 6, message `ProfileAvatar`); the never-used
>   `ProfileUpdate.avatar` (field 2) is deprecated. While "Aramada göster" /
>   "Show in search" is on (the default), the client also keeps an **avatar
>   record** on the relay, AES-256-GCM under a key derived from the
>   normalized username (PBKDF2 salt `BRP/avatar/v1`, HKDF info
>   `BRP/avatar/key/v1`, account id as HKDF salt and in the AAD), padded to
>   8, 16, 32 or 48 KiB, so whoever looks up the exact username sees the
>   picture with the result (`PUT`/`DELETE /v1/accounts/me/avatar`,
>   `GET /v1/accounts/{accountId}/avatar`, new error `409 stale_version`).
>   Turning it off, choosing the initial letter, a username change and
>   account deletion remove the record. The relay cannot read a record
>   without the username but can test guessed usernames, and learns a
>   username in clear whenever an invite link is opened (§9). Stores and
>   deletes have separate budgets (§4.11), so a picture can always be taken
>   out of search at once. Readers ignore a record older than one they have
>   opened (§8.10), and a requester's photo is neither fetched nor decoded
>   before the user asks for it or accepts (§8.9).
>   `AccountBackup` fields 11–12 and `BackupContact` field 10 carry the
>   pictures. Backward compatible: TestFlight builds 4–8 ignore the new
>   fields and keep showing the initial letter; no existing endpoint changes
>   except that a username change and account deletion also delete the
>   record. Implemented in `relay/`, `ios/Us2usKit`, `ios/Us2usAppCore` and
>   `android/core` (record crypto, publishing, search, the E2EE path,
>   backups; no app UI yet on either platform); the preset catalog (§8.9) is
>   filled in with the artwork.
> * 2026-10-11: **built-in stickers** (§8.8): `Sticker.lang` (field 5) carries
>   the language the words of a built-in sticker were shown in (`tr`, `en`),
>   and the recipient draws exactly that language. The built-in catalog
>   (`us2us.cups`, `us2us.words`, `us2us.privacy`, `us2us.mood`) is listed in
>   §8.8 with stable ids. Unknown stickers fall back to `emoji`; a missing or
>   unknown `lang` to the reader's language; `us2us.text` (earlier builds)
>   keeps its text card. Backward compatible; no relay change. Implemented in
>   `ios/Us2usKit`, `ios/Us2usAppCore`, the iOS app and `android/core`
>   (sending, receiving, the catalog); the Android app still needs the
>   picker and the drawing.
> * 2026-10-11: **a `400` while settling a rotation is not "settled"** (§5.5
>   step 3.5; client only, no wire change): `400` for `T` in step 4 means
>   that the old phone still holds the account's token, so the client draws
>   a fresh pending token and rotates again instead of forgetting `N`; a
>   refusal of both tokens keeps being reported in that process. Implemented
>   in `ios/Us2usKit`; the Android client still needs the same.
> * 2026-10-11: **a lost live-location share is stopped, not resumed**
>   (§8.3; client only, no wire change): a share the app can no longer send
>   (the process ended while sharing) gets its normal stop at the next start;
>   sharing is never resumed by itself. Implemented in `ios/Us2usAppCore`;
>   the Android client still needs the same.
> * 2026-10-11: **saying what happened** (§5.2, §5.5; client UI only, no
>   wire or file change): when the optional wipe after 10 wrong codes fires,
>   the app says at once that everything was erased and offers a restore or
>   a new start (never "wrong code"). While a restore's token rotation is
>   unanswered (§5.5 step 3.4) the app says that the old phone may still
>   have access and offers to ask again; it asks before every connection
>   attempt from the first unlock after a launch. The generated backup
>   password is shown only on request, hidden while the screen is captured
>   and when the app leaves the foreground, and a copy says that it stays on
>   the device and is cleared after a minute. Exporting a backup does not
>   rotate the token (stated, with why). Implemented in the iOS app and
>   `ios/Us2usAppCore`; the Android client still needs the same.
> * 2026-10-11: **what sealed sender and post-quantum cover, stated** (§1
>   goal 4, §2, §9; no wire change): goal 4 now says the relay cannot read
>   the sender from an envelope, not that it never learns who sends to whom.
>   §9 adds that sealed sends and media downloads can be linked by IP address
>   and timing, that `X-Access-Key` vs `X-PoW` shows contact vs stranger, and
>   that v1 post-quantum protection covers content only (the envelope is
>   DHKEM(X25519) to the long-term identity key). §2 names the libsignal
>   commit and the iOS vendor manifest checked before every release.
> * 2026-10-11: **identity verification bound to keys** (§5, §9; client
>   only, no wire change): verifying, a matching safety-number scan and
>   accepting a change apply only to the key whose number the user compared
>   or saw, checked and stored in one step; accepting without verifying is
>   a separate action. The contact code (`us2us:@name#fingerprint`, now
>   specified) carries the owner's own key and pins the scanned key as
>   verified before the first message; a code whose fingerprint differs from
>   the directory's key is reported as a key mismatch and adds nobody; a
>   code without a fingerprint verifies nothing. A key change found while
>   sending warns like one found on receive. A return to the previously
>   approved key while a change is pending keeps that key's verification.
>   §9 states the trust-on-first-use limit. Implemented in `ios/Us2usKit`
>   and `ios/Us2usAppCore` (local schema v4 keeps when and how a key was
>   verified, and the previous key's verification while a change is
>   pending); `android/core` still needs the same.
> * 2026-10-11: **backup passwords: a generator and an honest strength
>   check** (§5.5, no file format change): the export UI SHOULD offer a
>   generated password (20 symbols from a 31-symbol alphabet by rejection
>   sampling, five groups of four joined by `-`, ≈ 99.1 bits; a copy stays
>   on the device and expires within 60 s) and SHOULD refuse typed passwords
>   a guesser tries early (names, words, years, dates, keyboard walks; the
>   iOS app refuses less than 50 estimated bits). Every refusal and failed
>   export is explained. The export UI states what a backup holds (account,
>   identity key, contacts; never messages or media) and offers no switches
>   for them. A memory-hard v2 (Argon2id via libsignal `PinHash`) is
>   described as planned. Implemented in `ios/Us2usKit`
>   (`BackupPassword.generate()`) and the iOS app; the Android client still
>   needs the generator and the strength check.
> * 2026-10-11: **vault format v2: a Secure Enclave step for every guess**
>   (§5.2, §9): header version `0x02`, `hw = SHA-256(ECDH(k, H2C(codeKey)))`
>   with a non-exportable Secure Enclave key `k`, and
>   `KEK = HKDF(codeKey ‖ deviceSecret ‖ hw, salt, "BRP/vault/v2")`, so the
>   vault file and the device secret extracted from a phone no longer allow
>   guessing the code anywhere else. New iOS vaults are v2 where the phone
>   has a Secure Enclave (v1 where there is none); a v1 vault (TestFlight
>   builds 4–6) moves to v2 at its next successful unlock with the code (a
>   biometric unlock does not open the vault file) with the same DEK, in one
>   atomic write after a second hardware evaluation agreed, so a failure
>   leaves v1 in place. Hardware and Keychain errors are not wrong codes,
>   and the lock screen does not show them as such. On a phone that can be
>   unlocked and controlled, the remaining cost is one Secure Enclave
>   operation per guess (§9). v1 stays readable; the Android HWPRF is specified, not implemented (Android
>   writes v1). Client only; the relay is not involved.
> * 2026-10-11: **a restore erases nothing before the backup is known to
>   work** (§5.2, §5.5): restoring on a phone that already holds a vault (a
>   forgotten code) verifies the whole backup first, then asks the relay,
>   read-only (`GET /v1/keys/status` with the backup's token), whether the
>   token still works, and only then erases the old vault. `401`/`404`
>   means the backup is no longer valid, and nothing is erased. Before
>   that the app says that a backup brings back the account and contacts,
>   never the message history. A `200 {"authToken"}` answer to a rotation
>   with `newAuthToken` (a relay older than client-chosen tokens) is
>   committed as the account's token. Implemented in `ios/Us2usKit` and
>   `ios/Us2usAppCore` together with the client steps of §5.5 step 3; the
>   Android client still needs both. No relay change.
> * 2026-10-11: **uploads racing an account deletion** (§4.9): an upload
>   whose account is deleted before it completes is `401` and leaves no
>   blob behind; before, it could succeed and keep a file nobody could
>   reach or delete. Relay only, no client change.
> * 2026-10-11: **fast-path envelopes bounded** (§1.1, §4.6, §4.7): an
>   envelope held in memory for a connected recipient counts against the
>   recipient's mailbox limits like a stored one, and is stored at the
>   latest 40 s after the relay accepted it, however often the recipient
>   reconnects. A full mailbox is `429 rate_limited`, a relay holding too
>   much in memory `503`, both with `Retry-After` and the same whether or
>   not the recipient is online. Rows stored before a relay restart count
>   too. Relay only; clients already retry these answers.
> * 2026-10-11: **token rotation can no longer lose the account** (§4.3,
>   §5.5): the client chooses the new token, stores it in the vault as
>   pending and sends it as `{"newAuthToken"}`; the relay answers `204` and
>   never returns a token. A lost answer is resolved by asking again with
>   the new token, which the relay answers `204` without changing anything.
>   Only the token that authenticated the request can be replaced, the
>   old token gets no grace period, and a token that is plainly not random
>   (e.g. all zeros) is refused. The empty-body form, in which the relay
>   chooses the token and returns it, stays for older clients (iOS
>   TestFlight builds 4 and 5) and is deprecated. The relay and the iOS
>   client implement it; the Android client still needs the §5.5 steps.
> * 2026-10-11: **voice note format** (§7): a `VOICE` attachment is AAC-LC,
>   mono, in an MPEG-4 audio container (`content_type = audio/mp4`), with
>   `duration_ms` set and a `waveform` of up to 100 bytes, each the mean
>   level of an equal stretch, linear in dB from −50 dBFS (0) to full scale
>   (255). This is what the iOS app sends; receivers that cannot decode a
>   voice note show it as unplayable.
> * 2026-10-11: **voice notes played** (§8.4): the first play of a received
>   voice note MAY be acknowledged with `ReceiptMessage{VIEWED}` (as
>   brp.proto's comment always said), under the read-receipt rules —
>   accepted contacts only, only while read receipts are on. On a message
>   that is not view-once it only marks the sender's copy as played and
>   destroys nothing. Implemented in `ios/Us2usAppCore`; a client that
>   ignores it loses nothing.
> * 2026-10-08: **privacy review of the iOS app core** (implemented in
>   `ios/Us2usAppCore`; the Android client still needs the same):
>   crossing message requests become `ACCEPTED` (§6.4; interoperable
>   meanwhile, the iOS side's `ACCEPT` ends the request); settings only from
>   accepted contacts, far-future `updated_at` clamped (§8.2); live location
>   follows the disappearing timer (§8.3); removed messages stay removed and
>   unsent ones are never sent (§8.1); view-once carries no preview and is
>   destroyed when opened, quote excerpts only of messages that stay (§8.5).
> * 2026-10-08: **six-digit secret code and a separate backup password**
>   (product decision, iOS design 02/03/07). §5.2: the secret code is exactly
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
>   supersedes any earlier draft that mentioned push. Mailbox
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
   server-side profile, no server-side contact list. The one piece of
   profile the relay may hold is the profile picture of a user who leaves
   "Show in search" on, encrypted under a key derived from the username
   (§8.10; what that does not protect against: §9).
4. **The relay cannot read who sends to whom from an envelope** (sealed
   sender, §6). This is not anonymity against a relay that logs IP addresses
   and timing, and in v1 it is not post-quantum (§9).
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

The additive [communities extension](COMMUNITIES.md) defines opt-in group
messaging (text; since v2 also photos, videos, files, receipts and delete
for everyone) using separate APIs/mailboxes. Its explicit membership and
authenticated transport expose more metadata to the relay; the one-to-one
guarantees and wire format in this document remain unchanged.

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
| Link previews, search, message history, profiles, avatars, sticker packs | phone (shared with contacts only via E2EE; a picture shown in search is one opaque, padded record of ≤ 48 KiB per account, §4.12) |
| Avatar photo cropping, scaling, re-encoding, metadata stripping | phone |
| Ghost / disappearing timers and wipes | phone |
| Backup / device transfer | phone ↔ phone directly, never via relay |
| Relay | verify, store-and-forward small envelopes, temporary media blobs |

Concrete rules that keep the relay light:

* **Online fast path:** an envelope for a recipient with an open WebSocket is
  forwarded from memory and is written to MySQL only if it is not
  acknowledged within 10 seconds (or the socket closes, or the relay shuts
  down gracefully). Conversations between two online users therefore cause
  no database writes. A hard crash can lose such envelopes (§9). An
  envelope stays in memory at most 40 seconds after the relay accepted it,
  also when the recipient reconnects meanwhile (a reconnect does not restart
  that time), and while it is there it counts against the recipient's
  mailbox limits (§4.6).
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
  attachments. No sticker store on the relay. Built-in stickers are drawn on
  the phone from their ids (§8.8); nothing is downloaded.

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

libsignal version: **v0.105.0** on both platforms (bump both together). The
iOS build also pins the commit the tag points to
(`4beb029d8a941f81e7d9c6d8af1ed25a677569a8`) and the SHA-256 of every
vendored file (`ios/scripts/libsignal-vendor.sha256`, checked before every
release archive; ios/README.md).

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
| 409 | `stale_version` | avatar write whose version is not above the account's highest (§4.12); with `X-Avatar-Version`. Also community state and delivery conflicts ([COMMUNITIES.md](COMMUNITIES.md)) |
| 409 | `community_full` | community admission or roster over 100 members ([COMMUNITIES.md](COMMUNITIES.md)) |
| 413 | `payload_too_large` | body exceeds limit |
| 416 | — | unsatisfiable `Range` (§4.9), empty body |
| 426 | `bad_request` | `GET /v1/ws` without a WebSocket upgrade |
| 429 | `rate_limited` | with `Retry-After` header (seconds) |
| 429 | `community_inbox_full` | a recipient's community inbox, or one sender's share of it, is full ([COMMUNITIES.md](COMMUNITIES.md)); with `Retry-After` |
| 500 | `internal` | — |
| 503 | `internal` | the relay cannot store anything right now (§4.6, §4.9); with `Retry-After` |

## 3. Usernames

### 3.1 Rules

A username is 3–24 characters matching `^[a-z][a-z0-9_]{2,23}$` after
normalization. Normalization: remove leading and trailing characters with
the Unicode `White_Space` property (not a platform's own notion of
whitespace: U+0085 and U+3000 are trimmed, U+001C is not), then Unicode
NFKC, then lowercase with the locale-independent Unicode default case
mapping (never a Turkish or other locale; Turkish dotted/dotless i are not
allowed characters, so `I`→`i` is the only case mapping that matters).
The apps' input checks (one leading `@` dropped before normalization, the
reasons a name is invalid) are tested against the shared case table
`spec/username-rules.json`; it adds no rule.

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
pair**. The username is only a findable pointer to the account id, and
nothing is derived from it except the key of the account's avatar record
for search (§8.10), which is made again after every change. Therefore:

* A user can change the username at any time (`PUT /v1/accounts/me/username`).
  Conversations, sessions, safety numbers and local data are unaffected.
  The relay deletes the avatar record with the change, and the client
  publishes it again under the new username (§8.10). A new name does not
  hide the account from anyone who knows its account id (a former contact,
  someone blocked): they can still fetch the new record and test guesses of
  the new name against it offline (§9). The change screen SHOULD suggest
  turning "Show in search" off first when the user changes the name to get
  away from someone.
* After a change the client sends a complete `ProfileUpdate` (§8.7) to its
  contacts (E2EE) so their contact list shows the new name.
* A username received in a `ProfileUpdate` is only a claim. Clients show it
  as verified only after a directory lookup (§4.4) of its hash returns the
  sender's account id; the lookup is done lazily (e.g. when the contact is
  opened) and its result cached, so a contact cannot use up the receiver's
  directory quota by announcing random names.
* The relay keeps the **old** username hash reserved for the same account for
  14 days, so nobody can grab it right away and impersonate the user.
* A chat is found by account id, never by the name it shows. When our own
  lookup (§4.4) maps a username to an account, every other contact still
  claiming that name (its account was deleted, or it changed the name and
  someone else took it) is a mismatch and is no longer shown under it.
* The name a deleted contact was shown under (§6.4 "Delete contact") is
  kept only as such a claim: when the account becomes a contact again it is
  shown unchecked and looked up again when the chat is opened, and a lookup
  that maps it to another account drops it. A name already a mismatch is
  not kept.

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
token lifetime 10 minutes. The `register` difficulty rises by one bit each
time the registrations of the last hour double past a threshold (default
120 per hour), up to 26 bits; it falls back as the rate does. Clients MUST
solve the `difficulty` of the response, never a remembered one. Token format
is relay-internal; clients treat it as opaque. Reference format (since
2026-10-28; it carries the difficulty the challenge was issued with, so a
difficulty that changes before redemption never fails a correct solution,
and the HMAC keeps it from being lowered):
`v2.<purpose>.<target|->.<expiresAtSeconds>.<difficulty>.<random16 b64u>.<hmac b64u>`.
Tokens of the earlier format `v1.<purpose>.<target|->.<expiresAtSeconds>.<random16 b64u>.<hmac b64u>`
are accepted at the configured difficulty until they expire.

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
(the PoW is still consumed, §4.2) · `403 pow_invalid` · `429 rate_limited`
(§4.11: per address, per IPv6 /48, or for the whole relay; the relay-wide
limit is spent only by registrations with a valid PoW). The relay records
the UTC date of registration (the day, as for last-seen) for the lower
limits of new accounts (§4.9).

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
A change also deletes the account's avatar record, in the same transaction
(§4.12); a `409` changes nothing.

**Rotate auth token** — `POST /v1/accounts/me/token` (auth)
`{"newAuthToken": "<b64u 32>"}` → `204`. The client chooses the new token:
32 bytes from a cryptographically secure random generator, fresh for every
rotation and never the token it replaces, stored before the request is sent
(§5.5). The relay replaces the hash of the token that authenticated the
request with `SHA-256(newAuthToken)` and never sends a token back. The old
token stops working immediately (an open WebSocket is closed with `4401`).
Used right after restoring a backup on a new phone (§5.5), so the old phone
loses access.

* Only the account's current token can be replaced. A request whose token
  another rotation replaced while it ran gets `401`, so of two phones
  restoring the same backup at once exactly one succeeds.
* If the account's token already is `newAuthToken`, the answer is `204` and
  nothing changes (no `4401`). Repeating a rotation authenticated with the
  new token therefore succeeds once the rotation has happened; this is how a
  client whose answer was lost finds out (§5.5).
* `400`, and nothing changes, for a body without a valid `newAuthToken` (an
  empty body is the deprecated form below; any other body is not), with a
  token that is plainly not random (fewer than 16 different byte values, such
  as all zeros from a random generator whose failure went unchecked), or
  with a token that already belongs to another account (a random token
  never does either).

There is no grace period for the old token. None is needed, because the
client holds both tokens until it knows which one works, and one would let
the phone that the restore is meant to lock out keep its access for that
long.

*Deprecated:* an empty body makes the relay choose the token and answer
`200 {"authToken": "<b64u 32>"}`, as clients before 2026-10-11 (iOS
TestFlight builds 4 and 5) expect. If that answer is lost, the account is
lost with it: the old token no longer works and the new one existed only in
the answer. Clients MUST NOT use this form; the relay accepts it only while
such clients are supported.

**Set access key** — `PUT /v1/accounts/me/access-key`
`{"accessKeyHash": "<b64u 32>" | null}` → `204`. `accessKeyHash =
SHA-256(accessKey)`; `accessKey` is 16 random bytes. `null` disables
access-key delivery (everyone must use PoW).

**Delete account** — `DELETE /v1/accounts/me` → `204`. Deletes the account,
all its keys, its mailbox, the media it uploaded and its avatar record with
its version (§4.12), releases its current
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
one-time key is left, `oneTimePreKey` is `null`. One-time keys of one
account are handed out at most at a per-target rate (§4.11), whoever asks;
beyond it the relay answers as if none were left (last-resort Kyber key,
`oneTimePreKey: null`) and consumes nothing, so many accounts together
cannot drain them. Clients already handle such a bundle.

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

Each mailbox has limits (relay defaults: 10 000 envelopes, 50 MiB). They
count every envelope accepted for the recipient and not yet acknowledged,
whether it is stored or still held in memory on the fast path (§1.1), so a
send to a full mailbox is `429 rate_limited` with `Retry-After` whether or
not the recipient is online. Envelopes authorized by PoW rather than the
access key may take only a share of the mailbox (relay defaults: 500
envelopes, 8 MiB; at most half of each limit), so that anyone who can solve
a PoW cannot fill it and lock out the recipient's contacts; a PoW send past
that share gets the same `429`. The relay stores, per stored envelope,
whether it was authorized by PoW, and nothing about its sender. Stored
sends are also limited per recipient and client address (§4.11); the
relay keeps that pair only in memory, as a MAC under a key that never
leaves the running process. The relay does not raise the message PoW
difficulty as a mailbox fills: the difficulty would tell anyone how much
mail waits for an account, i.e. how long it has been offline. A relay that cannot take more right now (disk
almost full, too many envelopes waiting in memory, or, right after a
restart, its database not answering) answers `503` with
`Retry-After`, also for every recipient alike. Ephemeral sends are never
refused for these reasons.

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
held in memory, persisted if not acknowledged within 10 s, on
disconnect/shutdown, and at the latest 40 s after the relay accepted them).
Unacknowledged messages stay stored and are re-sent on the next connection;
one persisted while the connection is still open may only arrive on the
next connection. Frames may repeat (envelopes unacknowledged on a
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
even if the upload then fails. An account may have at most 2 GiB of
unexpired, undeleted blobs stored (256 MiB in its first 7 days); an upload
past it is `429 rate_limited` with `Retry-After` until older blobs are
deleted or expire. The relay also has a daily upload budget of its own,
and keeps a reserve of free disk that only envelopes may use; past either,
uploads are `503` with `Retry-After` while messages are still accepted. An upload whose account is deleted (§4.3)
before it completes is `401 unauthorized`, and nothing of it is kept.

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
| Whether a stored envelope came with a PoW (one bit, §4.6) | with the envelope |
| Account registration date (UTC day) | with the account |
| Account without any connection | deleted after 180 days (only a coarse last-seen *date* is stored) |
| Avatar record (§4.12) | until replaced or deleted, or the account is deleted (also by the 180-day rule) |

The relay MUST NOT log request bodies, auth tokens or access keys, and SHOULD
NOT log IP addresses (`LOG_IPS=false` by default). For the avatar endpoints
(§4.12) it MUST NOT log account ids, versions or `ETag`s either (a route
template such as `/v1/accounts/:id/avatar` is fine), and it SHOULD NOT keep
avatar records in backups of its database. A website served by the same
operator for invite links (`/u/<name>`, docs/INVITES.md) receives usernames
in clear (§9); it MUST NOT log, store or look up the name.

### 4.11 Rate limits (defaults, per IP unless noted)

| Endpoint | Limit |
|---|---|
| `GET /v1/pow` | 30 / min |
| `POST /v1/accounts` | 5 / hour; 20 / hour per IPv6 /48; 1000 / hour for the whole relay |
| `PUT /v1/accounts/me/username` | 5 / day / account |
| `GET /v1/directory/*` | 60 / hour / account |
| `GET /v1/accounts/{id}/avatar` | 60 / hour / account (a `304` counts) |
| `PUT /v1/accounts/me/avatar` | 30 / day / account |
| `DELETE /v1/accounts/me/avatar` | 200 / day / account, a budget of its own: a used-up `PUT` budget never delays taking a picture out of search |
| `GET /v1/keys/{id}` | 120 / hour / account; one-time keys of one target: 60 / hour, whoever asks (then a bundle without them, §4.5) |
| `PUT /v1/messages/*` | 600 / min; per recipient and IP: 60 PoW sends / hour and 32 MiB of stored envelopes / hour |
| `POST /v1/messages/ephemeral` | 3000 envelopes / min (each item counts) |
| `GET /v1/ws` (upgrades) | 60 / min |
| WebSocket frames, C→S | 1200 / min / account (then close `1008`) |
| `POST /v1/media` | 60 / hour / account, 2 GiB / day / account (512 MiB in the account's first 7 days); 24 GiB / day for the whole relay (then `503`) |
| `GET` / `DELETE /v1/media/{id}` (together) | 600 / min |
| any authenticated request | 1200 / min / account |
| failed `X-Auth-Token` checks | 30 / min |

IPv6 clients are limited per /64 (and registrations also per /48). The
relay takes the client address from its own proxy's header only when the
connection comes from a configured proxy address. When the limiter's memory
is full, the buckets closest to full are forgotten first, so a client
cannot shed a penalty by touching many other keys.

A request over a limit gets `429 rate_limited` with `Retry-After`. Every
request answered `401` (and every WebSocket upgrade refused for its token)
counts as a failed `X-Auth-Token` check; while an IP is over that limit, the
relay answers its authenticated requests with `429` before looking the
token up.

### 4.12 Avatar records

An account whose user leaves "Show in search" on (§8.10) keeps at most one
**avatar record** on the relay: an opaque blob that anyone who knows the
exact username can decrypt, and the relay cannot (§8.10 defines the key and
the plaintext; §9 the limits). The relay stores it, hands it out and deletes
it. It reads only the header and the length:

```
record  = header ‖ nonce(12) ‖ ciphertext ‖ tag(16)
header  = "BRPA" (4 ASCII bytes) ‖ 0x01 ‖ uint64_be(version)          // 13 bytes
length  = 8192, 16384, 32768 or 49152 bytes, nothing else
version = 1 .. 2^63 − 1, chosen by the client
```

**Versions.** Every write of an account (store or delete) carries a version,
and the relay keeps the account's **highest version**: the largest version
it accepted in a store or a delete, as long as the account exists — also
while no record is stored (after a delete or a username change). A write
whose version is not above it is refused with `409 stale_version` and
`X-Avatar-Version: <highest version>` (decimal) and changes nothing. So a
late retry of an older write never undoes a newer one, and a client never
needs to know what is stored: it writes with its next version and, after a
`409`, once more with `X-Avatar-Version + 1` (§8.10).

**Store** — `PUT /v1/accounts/me/avatar` (auth), body = the record
(`application/octet-stream`) → `204` with `ETag: "<version>"`.

* `Content-Length` is required (a chunked body is `400`). A body over 49152
  bytes is `413 payload_too_large` (refused before it is read); any other
  length that is not one of the four, another magic or format byte, or a
  version of 0 or ≥ 2^63 is `400`.
* The version comes from the header. If it is not above the highest
  version: `409 stale_version` as above. Otherwise, in one transaction, the
  record replaces the stored one (if any) and its version becomes the
  highest version.
* An upload whose account is deleted (§4.3) before it completes is `401
  unauthorized`, and nothing of it is kept (as §4.9).

**Delete** — `DELETE /v1/accounts/me/avatar` (auth) with
`X-Avatar-Version: <version>` → `204`. If the version is above the highest
version, the stored record (if any) is deleted and the version becomes the
highest version, in one transaction; also `204` when nothing was stored.
Otherwise `409 stale_version` and nothing is deleted. A missing or
malformed header (not a decimal in 1..2^63 − 1) is `400`.

**Fetch** — `GET /v1/accounts/{accountId}/avatar` (auth, rate limited per
account like the directory, §4.11) → `200` with the record as the body,
`Content-Type: application/octet-stream`, `ETag: "<version>"` and
`Cache-Control: no-store`. With `If-None-Match: "<version>"` naming the
stored version the answer is `304` with an empty body (and the same
`ETag`); any other `If-None-Match` value is ignored. An account without a record and an account that does not exist
both get the same `404 not_found`. No `Range` support. Any authenticated
account may fetch any account's record, its own included; so whoever knows
an account id sees what the relay sees of the record — whether there is
one, its size and its version — without the username (§9).

Other rules:

* A username change (§4.3) deletes the record in the same transaction; the
  highest version stays. Account deletion (§4.3), also by the 180-day rule
  (§4.10), deletes the record and the highest version.
* `/v1/accounts/me/avatar` takes `PUT` and `DELETE`;
  `/v1/accounts/{accountId}/avatar` takes `GET` (other methods: `405`,
  §2.1); an `accountId` that is not a UUID is `400`.
* The relay MUST NOT interpret the record beyond the header and MUST NOT log
  account ids, versions or bodies of these requests (§4.10).
* These requests are authenticated and are not part of the unlinkable group
  of §4.6. A fetch tells the relay which account looked at which (§9).
* A relay that does not know these paths answers `404` or `405`. Clients
  then treat avatars as unavailable, show the initial letter and try to
  publish again later, without showing an error.

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
  show a "safety number changed" warning until the user accepts — whether
  the change is found on receive or while sending (a fetched bundle carries
  another key). Receiving continues (§6.3). The warning SHOULD stay visible
  until the user verifies or accepts the new key. Accepting without
  verifying MUST be a separate action from verifying: it unblocks sending
  and leaves the contact unverified.
* Safety number: libsignal numeric fingerprint (iterations 5200, version 2),
  stable identifier = account id bytes (16), keys = identity `PublicKey`s.
  Shown as digits and QR.
* Verification is bound to a key. Marking a contact verified (after
  comparing the digits), a matching safety-number QR scan and accepting a
  change MUST apply only to the identity key whose safety number the user
  compared or was shown: the client compares that number with the one of
  the currently stored key and stores the decision in one atomic step, and
  refuses (showing the current number) if the key changed in between.
  Verifying also accepts the key. A change of the key clears the
  verification; while that change awaits approval, a return to the
  previously approved key MUST restore that key's verification (and its
  record), so a key injected once — by a relay serving the directory, for
  example — cannot strip the seal remotely. Clients MAY keep when and how a
  key was verified with it; a verification without that record shows no
  date.
* Contact code (the "add by QR" code, shown side by side):
  `us2us:@<username>#<fingerprint>`, where `<fingerprint>` is the lower-case
  hex of the first 16 bytes of SHA-256 over the code owner's **own** 33-byte
  serialized identity key (the key on the device, never what the relay
  serves). The scanning client looks the username up (§4.4) and MUST verify
  only if the directory's key has that fingerprint (compared in constant
  time) and it holds no other key for the account; it then **pins** that
  key: stored as verified and approved before any session exists, so a
  bundle or message under another key is a change (blocked, warned) rather
  than a first use. A fingerprint that differs from the directory's key, or
  from a key the client already holds for the account, MUST be reported as a
  key mismatch (what a relay swapping keys looks like), never as an unknown
  code, and MUST NOT add the contact; a lookup that fails is not reported as
  an unknown user either. A code without `#<fingerprint>` (older clients)
  MAY add the contact but MUST NOT mark it verified.
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
vault key also depends on a `deviceSecret` that never leaves the device and,
in vault format v2, on a key in the phone's security hardware that cannot
leave it at all. A copied vault file cannot be attacked elsewhere, and on
the phone every guess goes through the wrong-attempt delays below. (See §9
for what this does not protect against.)

Key hierarchy:

```
DEK          = 32 random bytes                       // data encryption key
deviceSecret = 32 random bytes                       // Keychain ...ThisDeviceOnly / Android Keystore-wrapped;
                                                     // never exported, never in backups
salt         = 16 random bytes
codeKey      = PBKDF2-HMAC-SHA256(UTF-8(code), salt, iterations = 600000, dkLen = 32)   // the six ASCII bytes

v1: KEK      = HKDF-SHA256(ikm = codeKey ‖ deviceSecret, salt = salt, info = "BRP/vault/v1", L = 32)
v2: hw       = HWPRF(codeKey)                        // 32 bytes, in hardware, once per attempt (below)
    KEK      = HKDF-SHA256(ikm = codeKey ‖ deviceSecret ‖ hw, salt = salt, info = "BRP/vault/v2", L = 32)

vault file   = header ‖ nonce(12) ‖ AES-256-GCM(KEK, nonce, aad = header, DEK) ‖ tag(16)
header       = "BRPV" (4 ASCII bytes) ‖ version ‖ salt(16) ‖ uint32_be(iterations)   // 25 bytes
version      = 0x01 (v1) or 0x02 (v2)
```

**Vault format v2: a hardware step for every guess.** In v1 the code is only
as safe as `deviceSecret`: someone who extracts both the Keychain item and
the vault file from the phone (§9) can try all codes offline. v2 adds `hw`,
a pseudorandom function of `codeKey` under a key that is generated inside
the phone's security hardware and cannot be exported, so every guess, right
or wrong, needs one evaluation by that hardware, on that phone, while it is
unlocked. Each platform defines its own `HWPRF`; vaults never move between
phones or platforms (a new phone always goes through the backup, §5.5).

iOS (`SecureEnclave.isAvailable`), a 2HashDH construction over P-256:

```
k      = Secure Enclave P-256 key-agreement key, access control
         kSecAttrAccessibleWhenUnlockedThisDeviceOnly with [.privateKeyUsage] only
P      = H2C(codeKey): for ctr = 0, 1, …, 63 (one byte)
           x = SHA-256("BRP/vault/h2c/v2" ‖ ctr ‖ codeKey)
           if x < p (the P-256 field prime) and 0x02 ‖ x is a valid compressed
           point (SEC 1), P = that point; stop
         no point after 64 counters: fail (not a wrong code; probability 2⁻⁶⁴)
hw     = SHA-256(Z), Z = the x-coordinate of k·P (32 bytes), computed by the Secure Enclave
```

* `P` MUST come from hashing, so that nobody knows its discrete logarithm. A
  point `s·G` with `s` derived from the code would let anyone compute
  `k·(s·G) = s·(k·G)` from the public key, without the hardware.
* The key has no biometry or user-presence flag (the code path must not
  depend on the enrolled faces or fingers, whose change would destroy the
  key) and is not `WhenPasscodeSetThisDeviceOnly` (turning the device
  passcode off would delete the key and with it the vault).
* Only the key's `dataRepresentation`, a blob that only this phone's Secure
  Enclave can use, is stored: a Keychain item `WhenUnlockedThisDeviceOnly`,
  never synchronized, next to `deviceSecret`. Deleting the Keychain items
  ("erase everything", the optional wipe) destroys it with the device secret.

Android:
`hw = HMAC-SHA256(k, codeKey)` with an AndroidKeyStore HMAC-SHA256 key
(StrongBox where available, else the TEE; the actual `KeyInfo` security level
MUST be checked before v2 is sealed; software-only keys MUST NOT be used).
The key uses `setUnlockedDeviceRequired(true)` on API 35+ only, with no
user-authentication requirement or biometric-enrollment binding. Android
documents key deletion and unlock bugs for this flag on API 31–34; earlier
versions therefore rely on app lifecycle locking for this additional key.
The key has an installation-specific alias separate from the secure-item
wrapping and biometric keys, and is deleted on wipe/erase. Its output and
the PIN-derived input are never persisted. An unavailable/failed hardware
upgrade leaves v1 usable and is retried after a later successful code unlock.
*2026-10-28:* below API 35 the HMAC key is usable while the phone is locked,
so v2 there protects against a copied vault file, not against code running as
the app on a phone that was unlocked once since boot: that code can evaluate
the key once per guess without the app's delays (the binding is not used on
API 28–30 either, because a key keeps its flags through an upgrade to
31–34). The v2 candidate MUST be opened with the same code, device secret and
hardware key, giving back the same DEK, before it replaces the v1 file. The
app shows what the hardware adds in Settings: nothing when v2 and API 35+,
"device protection: hardware" with this limit below API 35, and "device
protection: basic" while the vault is v1 (upgrade failed or impossible) or
the device-secret wrapping key is not in secure hardware.

**Android secure-item wrapping (separate from the HWPRF).** New wrapping
keys use unlocked-device-required only on API 35+. Existing items are moved
after successful create, PIN/biometric unlock or PIN change to a separate
non-exportable wrapping key, preserving the exact device secret and all other
item values. Items retain AES-256-GCM and the existing name-bound AAD. A
file lock serializes operations across instances/processes. All items are
copied and verified in a separate generation; synced files/directories and
one atomically replaced, directory-synced marker commit the entire store
before the legacy key is retired. A precommit failure keeps the legacy store
usable; cleanup failures preserve the active store and are retried. Optional
maintenance failures do not consume PIN attempts or reject an otherwise
successful unlock. Temporary decrypted copies MUST be zeroed.

After commit, reads/deletes/writes MUST use only the active generation, with
no per-item legacy fallback. Missing keys/invalid markers MUST NOT cause
silent replacement or downgrade; a missing marker after legacy retirement
is an error. Wipe attempts removal of both wrapping aliases and all item
generations. An already-deleted legacy key cannot be reconstructed; the
existing recovery path applies. Older app binaries cannot open the migrated
local store after its legacy key is retired. This local downgrade limit
does not change peer wire compatibility or existing backup decoding.
Release verification MUST cover restart and interrupted-commit recovery on
physical devices. A device that cannot create the legacy flagged key does not
verify migration from that key form; flagged-key, vendor and OS coverage
remains required.

Rules for v2:

* **New vaults** are v2 where the platform's hardware is available, and v1
  where it is not (e.g. an iOS Simulator without one). A writer MUST NOT
  seal a real vault with a software stand-in for the hardware. It
  evaluates `hw` twice and writes nothing unless both agree. If creating or using the hardware key
  fails, the vault is written as v1 and moves to v2 later.
* **Moving from v1**: after a successful unlock of a v1 vault with the code
  (a biometric unlock does not open the vault file, so it moves at the next
  code entry, at the latest when quick unlock asks for the code after 7
  days),
  where the hardware is available, the app replaces any leftover hardware key with a
  new one, seals the same DEK as v2 (new salt and nonce), checks it against a
  second hardware evaluation and only then replaces the v1 file in one
  atomic write. Until that write the v1 file stays as it was; any failure
  leaves it in place for the next unlock to try again, and a hardware key
  left behind by an interrupted move is replaced then. Only a v1 file
  exists while the key is replaced, so nothing depends on the old key. The
  DEK does not change, so key slots, the database and the biometric wrap stay
  valid. A wrong code changes nothing.
* **Change code** keeps the format: a v2 vault is sealed again with the same
  hardware key.
* **Errors**: a missing hardware key (or a v2 vault and no hardware key at
  all) means the vault can never be opened on this phone again, like a
  missing `deviceSecret`; other hardware or Keychain errors are temporary.
  Neither is a wrong code (see "Wrong attempts"), and the UI MUST NOT
  present either as one: a temporary error says the code may be right and
  to try again shortly; a vault that can never be opened here says so and
  points to restoring a backup.
* Readers MUST treat any other version byte as a corrupt file, before
  deriving anything. App versions that predate v2 cannot read a v2 vault, so
  going back to such a version after the move is not supported. Account
  backups (§5.5) never contain the vault and are not affected.
* The hardware counts nothing: the attempt delays and the optional wipe stay
  in the app, and someone in control of the unlocked phone (an operating
  system exploit) can still have the hardware evaluate guesses, but only on
  that phone and at its speed.

Implementations MUST refuse to create or open a vault with input that is not
a secret code (rule 1) before deriving anything. (For six ASCII digits
`UTF-8(NFKC(code)) = UTF-8(code)`, so the KDF input is unchanged from earlier
drafts.)

* **Unlock** = derive KEK from the code (in v2 with one hardware evaluation)
  and open the vault. A wrong code is a GCM failure. Because `deviceSecret`
  never leaves the phone, a copied vault file alone cannot be brute-forced
  elsewhere; for a v1 vault whose `deviceSecret` is extracted too, see §9.
  Readers MUST reject a header whose `iterations` is outside 1000..10000000
  before deriving anything.
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
  a wrong code (missing device secret or hardware key, a hardware or
  Keychain error, corrupt file).
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
  in a row crypto-shreds everything (§5.3). The app MUST then say at once
  that everything on the phone was erased — never "wrong code" — and offer
  only what is real: restore from a backup (§5.5) or start over. Where the
  code was asked outside the lock (a code change), the same applies.
* **Duress code** (optional): a second six-digit code, following the same
  rules and different from the secret code; entering it silently
  crypto-shreds everything (§5.3) and shows a fresh install.
* **Forgotten code**: the data is unrecoverable, and there is no reset of any
  kind (no reset link, support request or security question). The app offers
  only what is real: restore from an account backup (§5.5), which brings
  back the account and its contacts but never the messages on this phone
  (they are erased with the vault, and no backup holds them), or **erase
  everything** and start over, which asks for a second confirmation and then
  crypto-shreds the vault, the device secret, the vault's hardware key (v2)
  and all key slots (§5.3).

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

**Generated backup passwords.** The export UI SHOULD offer to generate the
password. A generated password is 20 symbols drawn independently and
uniformly from the 31-symbol alphabet `abcdefghjkmnpqrstuvwxyz23456789`
(lower-case letters and digits without `0 o 1 l i`) with the platform's
cryptographically secure random generator, by rejection sampling: a random
byte `b` is used only if `b < 248`, as `alphabet[b mod 31]` (taking
`b mod 31` of every byte would favour the first eight symbols). It is
written as five groups of four joined by `-`, like `k7mq-x2pd-9tfa-wn3h-b8ze`
(24 characters, 20 · log2 31 ≈ 99.1 bits); the dashes are part of the
password. It always satisfies the rules above. The UI shows it and offers to
copy it; a copy MUST stay on the device (no clipboard sync to other devices)
and SHOULD expire within 60 s. Unlike other copied codes it is not cleared
when the app leaves the foreground or locks, since that is when it is pasted
into a password manager; the UI SHOULD advise against a screenshot of it,
which would outlive the copy. The UI SHOULD show the generated password only
when asked (and never while the screen is recorded or mirrored), SHOULD hide
it again when the app leaves the foreground, and SHOULD say after a copy that
it stays on this device and when it is cleared. The confirmation is still
entered, from a password manager or by hand, so no backup is made with a
password nobody kept.

**Strength estimate.** The rules above are the minimum both clients enforce.
Before asking for the confirmation, the export UI SHOULD also refuse a typed
password that a guesser tries early. The iOS app estimates the guesses
needed: names, surnames, places, clubs and common words (Turkish and
English, also in leet spelling), years 1900–2039 and dates, runs, keyboard
walks and repeats count as the few guesses they are, and every other
character at the size of the character classes used. It refuses less than
50 bits and labels 50–59 bits “Orta”, 60–79 “Güçlü” and 80 or more “Çok
güçlü”; a password it generated counts exactly 99.1 bits. Every refused
password and every failed export MUST be explained to the user; an export
never fails silently. The estimate is a UI rule, not part of the file
format.

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
username, display name, access key, the profile picture and the "Show in
search" setting (§8.9, §8.10), and contacts with the profile picture each
last sent (`AccountBackup` in `brp.proto`). A backup without the picture
fields (older builds) restores the initial letter and the setting on. It does **not** contain messages; message history moves only by
direct phone-to-phone transfer (planned). The export UI MUST say so: it lists
what the file holds and that messages and media are not in it, and offers no
switches for them. (A later format may carry message history and media,
never view-once media or disappearing messages. That needs a streaming,
chunked file format and is not part of v1.) A contact whose identity change the
user has not accepted is exported with `identity_unaccepted = true` and
restored as changed (sending blocked until accepted, §6.3); every other
identity key is restored as trusted.

Exporting does not change the auth token: every backup file of an account
restores, and works alike, until one of them is restored (step 3 below
rotates the token, and then none of the others works any more) or the
account is deleted. An old copy is therefore protected only by its password.
(Rotating the token at every export was considered and not adopted: a copy
the user still relies on would stop working before the new file is known to
be saved, and an export would need the network.)

Restore on a new phone:

1. Decrypt with the backup password and verify the **whole** backup (version,
   every field's size and format) before contacting the relay. A backup that
   fails here changes nothing.
2. Create a new vault (§5.2) for the restored account, under a secret code
   the user chooses on this phone (it need not be the old phone's code).
3. Rotate the auth token (§4.3) so that the old phone loses access. The
   client MUST hold a token that works at every moment, also when an answer
   is lost or the app is killed:
   1. Generate the new token `N` (32 random bytes) and write it to the new
      vault as the *pending token*, next to the backup's token `T`, before
      sending anything.
   2. `POST /v1/accounts/me/token` with `X-Auth-Token: T` and
      `{"newAuthToken": "<b64u N>"}`.
   3. On `204`, commit: in one vault write, `N` becomes the account's token
      and `T` and the pending mark are removed. Only then may the client
      upload keys (step 4) or open the WebSocket. (A relay older than
      client-chosen tokens ignores the body and answers
      `200 {"authToken"}` with a token it chose; that token is committed
      instead of `N`.)
   4. If no answer arrives (timeout, lost connection, `5xx`), or the app
      starts with a pending token, the outcome is unknown. Repeat the same
      request with `X-Auth-Token: N` and, on `401`, with `T`; the first
      `204` commits (3). On `401` for `T`, here or in 2, ask with `N` once
      more, since an earlier attempt may have completed in between: `204`
      commits; `401` means that another phone restored the same backup and
      rotated first, so the backup is no longer valid, and only then may the
      client forget `N` and `T`. On no answer or `429`, repeat this step
      later; the relay sets no deadline for it.
   5. While the outcome is unknown, the client MUST NOT replace the pending
      token with a new one and MUST NOT use `T` or `N` for anything else.
      (`400` means that nothing changed; a correct client never gets it.
      If it does get it for `T` in step 4, the outcome is known: `T` — which
      the old phone also holds — is still the account's token. The client
      MUST NOT report the old phone as shut out; in one vault write it
      replaces `N` with a fresh pending token and settles that one like an
      unanswered rotation (step 4) at its next attempt. A client SHOULD
      keep reporting a refusal of both tokens for as long as it runs, also
      once nothing is pending any more, rather than "settled".)

   Every `401` counts as a failed token check (§4.11); finding out the
   outcome costs at most three.

   While the outcome is unknown, the old phone may still hold a token the
   relay accepts. The app SHOULD say so plainly and persistently — the old
   phone may still have access to the account, and this phone does not
   connect, until it is confirmed — and SHOULD offer to ask again at once
   (step 3.4). It asks again before every connection attempt, from the first
   unlock after a launch on (the pending token is inside the vault, so not
   before). The notice goes as soon as the outcome is known; if it is that
   the backup is no longer valid, the app says that this phone is signed out.
4. Generate new signed, Kyber and one-time pre-keys and `PUT /v1/keys` with
   `replaceAll: true`. The client MUST retry this upload, also after a
   restart, until it succeeds: until then the relay still hands out pre-keys
   whose private keys are gone.
5. The client SHOULD send a `ProfileUpdate` (§8.7) to every contact. The
   restored phone has no sessions, and contacts keep encrypting to the old
   phone's sessions (which it cannot decrypt) until the restored phone
   writes first. It also brings its avatar record in line with the restored
   picture and setting (§8.10, "Publishing"); the backup holds no avatar
   version, so the first write may be answered `409` and is repeated.

Restore on a phone that already holds a vault (the secret code was
forgotten, §5.2) follows the same steps, but the existing vault MUST NOT be
touched before step 1 has passed and the relay has accepted the backup's
token in one read-only authenticated request (`GET /v1/keys/status` with
`X-Auth-Token: T`). `401` (or `404`) there means that the backup is no
longer valid; no answer, `429` or `5xx` means that nothing could be
checked. Either way nothing is erased. Only after the check is the old
vault crypto-shredded (§5.3) and step 2 begins. A refused token counts once
as a failed token check (§4.11). "Read-only" means it changes nothing but
what any authenticated request does: the relay notes the account's
last-seen day (§4.10), which extends its retention, and sees the request's
IP address. The check cannot exclude that another
phone rotates the token between it and step 3; the old vault is gone then,
and step 3 ends with "no longer valid". Before the user confirms, the app
MUST say that the backup brings back the account and contacts but no
message history, and that the messages on this phone are erased.

Because the identity key is the same, contacts see **no** safety-number
change; their sessions are replaced by the restored phone's next message.

**Future: backup format v2 (planned, not implemented, not normative).**
PBKDF2 is not memory-hard: at 1,000,000 iterations a stolen file can be
guessed on GPUs at roughly 10^4 passwords per second per GPU, so a backup is
only as safe as its password (hence the generator and the strength estimate
above). A later version is planned to derive the key with Argon2id, which
libsignal already provides (`PinHash`: Argon2id v1.3, m = 16 MiB, t = 32,
p = 1, 32-byte salt), so no new dependency is needed:

```
header    = "BRPB" ‖ 0x02 ‖ kdf_id(1) = 0x01 ‖ salt(32)                            // 38 bytes
ph        = PinHash(UTF-8(NFKC(password)), salt)
backupKey = HKDF-SHA256(ikm = ph.encryptionKey ‖ ph.accessKey, salt = empty, info = "BRP/backup/v2")
file      = header ‖ nonce(12) ‖ AES-256-GCM(backupKey, nonce, aad = header, AccountBackup protobuf) ‖ tag(16)
```

The backup would get a header type of its own (today it shares the vault's
header layout). Rollout: both clients first ship a reader for version `0x02`
(with a cross-platform test vector that pins the derived key, so a libsignal
parameter change is caught) and keep writing `0x01`; a later release writes
`0x02`. Version `0x01` files keep opening, and a reader that meets a version
it does not know SHOULD ask the user to update the app instead of calling the
file damaged. Until this section becomes normative, backups are written and
read as version `0x01` only.

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
   `usernameHash(sender_username)` has the sender's account id. Cache the
   answer only for a limited time (one hour in both clients), `404` included,
   because a username can move to another account (§3.3); if the cached
   owner differs from the sender, look it up again before dropping.
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
* **Delete contact** (local only, nothing is sent, not a block) removes the
  state with the conversation, the contact's profile, picture and access
  keys, and pending sends to it, but keeps its libsignal session and
  identity and the username it was shown under (§3.3); a blocked contact
  keeps only `BLOCKED` (no session, identity or name), and its messages
  stay dropped. A message from a deleted contact that is not blocked
  starts from no state: it still decrypts on the kept session (the contact may still be `ACCEPTED` with us and keeps
  writing on it, without `sender_username`) and opens `INCOMING_REQUEST`
  under the kept name; a new identity key in it is flagged as a change
  (§6.3 step 5), never trusted as new. Accepting it sends `ACCEPT` and the
  access key as for any request.
* If the other phone is still `ACCEPTED` and receives a verified visible
  message with `sender_username` from that account (we deleted it and
  wrote first), it stays `ACCEPTED` and queues
  `MessageRequestResponse{ACCEPT}`, then `AccessKeyUpdate` and its
  `ProfileUpdate`, so the new request ends on both phones.
* If it is `ACCEPTED` and receives `MessageRequestResponse{ACCEPT}` (it
  deleted us, we wrote, and it accepted that request), it stays `ACCEPTED`
  and sends its `AccessKeyUpdate` and `ProfileUpdate` again, which the
  deleting phone no longer holds — once its own key and profile exchange
  with that contact had finished; an `ACCEPT` crossing an exchange still
  under way changes nothing. This rule never answers with `ACCEPT`, and the
  one above answers only a message with `sender_username`, which an
  `ACCEPTED` phone never sends, so the two cannot loop.

*2026-10-28 (Android and iOS):* opening a handle (search, contact
code, invite link) may create an `OUTGOING_REQUEST` row before anything is
sent. Such a request counts only once the relay answered `202` for a visible
message of ours to that contact: before that, `MessageRequestResponse{ACCEPT}`
from it changes nothing, and a verified visible message from it opens
`INCOMING_REQUEST` (it wrote first) instead of the crossing rule, which in any
case needs `sender_username`. "Delete contact" rotates our access key (new key
to all other accepted contacts, as for Block) when the deleted contact held
it: the contact can still write, but only with proof-of-work, so its messages
arrive as a new request and count against the relay's proof-of-work share of
the mailbox (§4.6), not the contacts' share. Other contacts' sends fall back
to proof-of-work until the new key reaches them.

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

Voice notes (`kind = VOICE`) are AAC-LC, mono, in an MPEG-4 audio container
(`content_type = audio/mp4`), so every client can play what another
records. Senders SHOULD record at 16–48 kHz and 24–64 kbit/s, and at most
5 minutes (the iOS app: 24 kHz, about 32 kbit/s), and MUST set
`duration_ms`. `waveform` holds up to 100 bytes, oldest first, each the
mean input level of an equal stretch of the recording: 0 is −50 dBFS or
quieter, 255 is full scale, linear in dB in between. A receiver that cannot
decode a voice note shows it as unplayable; it MAY refuse to load one far
larger than any recording (the iOS app: above 16 MiB).

## 8. Message semantics (client)

### 8.1 Disappearing messages
`DataMessage.expire_timer_seconds = T > 0`: the sender deletes its copy T
seconds after sending; the recipient deletes T seconds after the message was
first displayed (read). Attachments and thumbnails are deleted with it, and
so are decrypted copies made for display. A client that lists a
conversation's media outside the conversation (a gallery of its photos,
videos or files) MUST NOT list disappearing items there at all: such a list
is kept apart from the conversation's own timers and could otherwise show
an item after its deadline. Disappearing items cannot be saved (§8.4).

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
* The wipe covers what is inside the app. A photo, video or file the user
  saved or handed on outside the app (§8.4, which tells the sender), and
  text copied out of it, survive it; only disappearing items (§8.1) can
  never leave the app.

### 8.3 Live location
`LiveLocation` messages share a `session_id`. The start and the stop messages
are sent normally; intermediate updates (every 15–30 s) are sent with
`ephemeral=1`. Receivers update one map bubble per session in place and
stop showing it after `expires_at` or `stopped = true`. Because sharing is an
explicit user action with a visible OS indicator, the sharer's app keeps
sending updates in the background (iOS background location mode, Android
foreground service with a persistent notification) until `expires_at` or
stop. Receiving still happens only while the app is open.

A sharer that loses a running share without stopping it (the process ended
in the background, a crash, the vault closed) MUST NOT resume sharing by
itself: that needs the user again. It SHOULD send the share's normal stop at
its next start instead, so the contact's bubble does not keep claiming to be
live with a frozen position until `expires_at`.

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

The first play of a received voice note MAY be acknowledged with
`ReceiptMessage{VIEWED}` under the same rules as read receipts (accepted
contacts only, only while the user has read receipts on). On a message that
is not view-once, VIEWED only tells the sender that it was played; it
destroys nothing.

`ReceiptMessage{SAVED}` tells the sender of a photo, video or file that the
recipient saved or handed on the item outside the app (the phone's photo
library, Files, or the system share sheet — another app, AirDrop, copying,
printing):

* **Who and what.** Only the recipient of a message sends SAVED, only to an
  accepted contact (never for message requests), and only for a
  `DataMessage` whose payload is an `Attachment` of kind `IMAGE`, `VIDEO` or
  `FILE`, with `view_once = false` and `expire_timer_seconds = 0`. A
  view-once or disappearing item cannot be saved at all (§8.1, §8.5), so it
  never gets SAVED; nor do voice notes, custom stickers, text, locations or
  live locations.
* **When.** When the user saves or hands on such an item, the client sends
  SAVED **whatever the read-receipt setting**: saving is an explicit act.
  The client MUST explain, before the first such save on an account, that
  the sender will be told, and SHOULD repeat it at the save controls (the
  iOS app: a one-time sheet, then a line under "Dosyalara kaydet" / "Save
  to Files" and the VoiceOver hint of "Kaydet" / "Save"). It SHOULD be sent
  once per message: saving the same item again sends nothing more.
* **Receiving.** A client applies SAVED only from an accepted contact and
  only to its own messages to that contact that pass the check above and
  are not deleted; it records the first one and ignores every other id
  (unknown, received rather than sent, another kind, view-once,
  disappearing, deleted) and every later SAVED for the same message.
* SAVED is a courtesy, not a guarantee: a modified client, a screenshot or
  another camera keeps a copy without it (§9).
* Clients that predate SAVED (iOS TestFlight builds 4–8) read an unknown
  receipt type and ignore it; nothing else changes on the wire.

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
  `display_name`, `presence_sharing`, `updated_at`, and `profile_avatar` if
  the user chose a preset or a photo, §8.9), and the receiver replaces its
  stored profile of the sender with it. An empty field means "none" (no
  `profile_avatar`: the initial letter).
* It is sent once to each accepted contact after the access-key exchange
  (§6.4), and again to every accepted contact on each change. A new profile
  picture is a change. Turning "Show in search" (§8.10) on or off is not:
  contacts get the picture either way.
* `username` is a claim; it is shown as verified only as described in §3.3.
* `profile_avatar` follows §8.9. A malformed one is shown as the initial
  letter and never makes the receiver drop the `ProfileUpdate`; the other
  fields apply.
* `avatar` (field 2, an `Attachment`) is deprecated. No client ever sent it;
  senders MUST NOT set it, and receivers MUST ignore it (no validation, no
  download). Builds that predate `profile_avatar` (TestFlight 4–8) ignore
  field 6 and keep showing the initial letter.
* With a photo a `ProfileUpdate` is up to about 49 KiB, which still fits
  one sealed envelope (64 KiB, §4.6) with the session and sealing overhead.

### 8.8 Stickers

Built-in stickers ship inside the app and are drawn on the phone; which one
was sent is visible only inside the E2EE `DataMessage` (§1.1). A built-in
sticker is `Sticker{pack_id, sticker_id, emoji, lang}`; a custom sticker sets
`custom` (a `STICKER_IMAGE` attachment, §7) and `emoji` instead.

* `pack_id` and `sticker_id` name a sticker of the catalog below.
  `sticker_id` is unique across all built-in packs and never reused: a
  removed sticker's id is retired, and new stickers are only appended.
* `emoji` is always set: what a client that does not know the sticker
  shows, and its accessibility fallback.
* `lang` is set only for a sticker with words: the language its words were
  shown in when it was sent, as a lowercase BCP-47 primary language subtag
  (`tr`, `en`). It is empty for a wordless sticker. The recipient draws the
  words in exactly that language, never in its own, so both ends see the
  same sticker. The words differ per language on purpose (a natural phrase
  in each, not a translation). Senders MUST NOT send more than 35
  characters; receivers treat a longer value as unknown.

A receiver draws:

| received | drawn |
|---|---|
| known `pack_id`/`sticker_id`, `lang` `tr` or `en` (any case or region) | the sticker, its words in `lang` |
| known sticker, `lang` empty or another language | the sticker, its words in the reader's app language |
| unknown `pack_id`, or a `sticker_id` that is not in that pack | `emoji`, large |
| `pack_id = "us2us.text"` (builds before the catalog) | `emoji` as the words of a plain text sticker |
| nothing known, `emoji` empty or blank | a neutral “Sticker” placeholder |
| `custom` set | the `STICKER_IMAGE` attachment (§7); a client that cannot show it falls back to `emoji` |

Senders do not send `us2us.text` any more; receivers keep drawing it.

Built-in catalog, version 1 (words separated by “/” break onto two lines):

| `sticker_id` | `pack_id` | `emoji` | words `tr` | words `en` |
|---:|---|---|---|---|
| 1 | `us2us.cups` | 👋 | — | — |
| 2 | `us2us.cups` | 🥰 | — | — |
| 3 | `us2us.cups` | 😂 | — | — |
| 4 | `us2us.cups` | 😭 | — | — |
| 5 | `us2us.cups` | 😮 | — | — |
| 6 | `us2us.cups` | 😍 | — | — |
| 7 | `us2us.cups` | 😉 | — | — |
| 8 | `us2us.cups` | 🤔 | — | — |
| 9 | `us2us.cups` | 🤗 | — | — |
| 10 | `us2us.cups` | 😵‍💫 | — | — |
| 11 | `us2us.cups` | 👂 | — | — |
| 101 | `us2us.words` | 👋 | Merhaba! | Hi! |
| 102 | `us2us.words` | 🟢 | Hat açık | Line's / open |
| 103 | `us2us.words` | 🫂 | Seni / duyuyorum | I hear / you |
| 104 | `us2us.words` | 🤐 | Aramızda / kalsın | Just / between us |
| 105 | `us2us.words` | 🕒 | Sonra / konuşuruz | Talk / later |
| 106 | `us2us.words` | 🥺 | Özledim | Miss you |
| 107 | `us2us.words` | ☀️ | Günaydın | Good / morning |
| 108 | `us2us.words` | 🌙 | İyi / geceler | Good / night |
| 109 | `us2us.words` | 👌 | Tamam! | OK! |
| 110 | `us2us.words` | 🙏 | Teşekkürler | Thank you |
| 111 | `us2us.words` | 😆 | Haha | Haha |
| 112 | `us2us.words` | 📞 | Alo? | Hello? |
| 201 | `us2us.privacy` | 🔒 | — | — |
| 202 | `us2us.privacy` | 👻 | — | — |
| 203 | `us2us.privacy` | 🔑 | — | — |
| 204 | `us2us.privacy` | 🙈 | — | — |
| 205 | `us2us.privacy` | 🫥 | İz yok | No / trace |
| 206 | `us2us.privacy` | 🤫 | Şşş | Shh |
| 207 | `us2us.privacy` | 🔐 | — | — |
| 208 | `us2us.privacy` | ✅ | — | — |
| 301 | `us2us.mood` | ☕ | — | — |
| 302 | `us2us.mood` | 🏃 | Yoldayım! | On my way! |
| 303 | `us2us.mood` | ⏳ | Meşgul | Busy |
| 304 | `us2us.mood` | 🎉 | — | — |
| 305 | `us2us.mood` | 😴 | — | — |
| 306 | `us2us.mood` | 😎 | — | — |
| 307 | `us2us.mood` | 🥂 | — | — |

### 8.9 Profile pictures

A user's profile picture is one of:

| choice | encoding |
|---|---|
| the initial letter of the username (the default; all that builds before this section show) | no `ProfileAvatar` |
| a built-in preset | `ProfileAvatar{preset_id}` |
| a photo | `ProfileAvatar{photo: AvatarPhoto{content_type, data}}` |

The picture reaches accepted contacts end-to-end encrypted in every
`ProfileUpdate` (`profile_avatar`, §8.7), whatever the "Show in search"
setting, and, while that setting is on, anyone who looks up the exact
username, through the avatar record (§8.10). Nothing else carries it: not
`SealedContent`, not a message request, not the directory.

**Presets.** `preset_id` names a picture of the built-in catalog below. It
ships inside the app and is drawn on the phone, like a sticker (§8.8);
nothing is downloaded. Ids are unique, never reused (a removed preset's id
is retired), new presets are only appended, and 0 is not an id. A receiver
that does not know an id shows the initial letter.

Catalog version 1 (the apps' `BrandAvatarCatalog`): the stickers' cup
mascot (§8.8) on a coloured disc, each with something that changes its
outline, so presets stay apart at 28 pt. `key` is a readable name for code
and documentation and never travels; the labels are what screen readers
say, in the app's language. A client MUST NOT send an id that this table
does not list. Rows are only appended; a removed preset's id is retired
here, never handed out again.

| `preset_id` | key | label (tr) | label (en) |
|---:|---|---|---|
| 1 | `cup.classic` | Klasik bardak | The classic cup |
| 2 | `cup.headphones` | Kulaklıklı bardak | Cup with headphones |
| 3 | `cup.beanie` | Bereli bardak | Cup in a beanie |
| 4 | `cup.glasses` | Gözlüklü bardak | Cup with round glasses |
| 5 | `cup.flower` | Çiçekli bardak | Cup with a flower |
| 6 | `cup.cap` | Şapkalı bardak | Cup in a cap |
| 7 | `cup.scarf` | Atkılı bardak | Cup in a scarf |
| 8 | `cup.ghost` | Hayalet bardak | Ghost cup |
| 9 | `cup.cool` | Güneş gözlüklü bardak | Cup in sunglasses |
| 10 | `cup.party` | Parti şapkalı bardak | Cup in a party hat |
| 11 | `cup.stringHeart` | İpi kalp olmuş bardak | Cup with a string heart |
| 12 | `cup.sleepy` | Uykucu bardak | Sleepy cup in a nightcap |
| 13 | `cup.wink` | Göz kırpan bardak | Winking cup |
| 14 | `cup.crown` | Taçlı bardak | Cup with a crown |
| 15 | `cup.bow` | Fiyonklu bardak | Cup with a bow |
| 16 | `cup.coffee` | Kahve bardağı | Coffee cup |
| 17 | `cup.curious` | Meraklı bardak | Curious cup |
| 18 | `cup.twin` | Açık renk ikiz bardak | The light twin cup |
| 19 | `cup.catEars` | Kedi kulaklı bardak | Cup with cat ears |
| 20 | `cup.chef` | Aşçı bardak | Chef cup |
| 21 | `cup.antennae` | Uzaylı bardak | Alien cup with antennae |
| 22 | `cup.laugh` | Kahkaha atan bardak | Laughing cup |

Retired ids: none.

**Photos.** The sender prepares the photo on the phone:

* The user crops it to a square. It is scaled to 256 × 256 pixels (smaller
  only when the source is smaller; never enlarged); width and height MUST
  be 1..256.
* It is encoded as one still image, `image/jpeg` (JPEG) or `image/heic`
  (HEIF with one HEVC-coded image), with the orientation applied to the
  pixels.
* It MUST NOT carry metadata: no EXIF (camera, dates, location), GPS, XMP,
  IPTC, maker notes, embedded thumbnails, depth maps or other auxiliary
  images. An ICC colour profile MAY remain; senders SHOULD leave it out when
  the pixels are sRGB, which receivers assume without one (a profile names
  the platform that wrote it). The encoder is given only pixels, never the
  source file's properties.
* Both client libraries check this on the bytes themselves and refuse to
  send (in a `ProfileUpdate` or an avatar record) a photo that breaks it,
  whoever built it. A JPEG may hold, besides the tables, frame header,
  restart intervals and scans, only these APP segments: `APP0` JFIF without
  a thumbnail, `APP2` ICC profile and `APP14` Adobe — no other `APPn` and no
  comment, before or between scans — and nothing after `EOI`. A HEIC's
  `meta` lists exactly one item, of type `hvc1`, and no `iref`. It is square.
* The serialized `ProfileAvatar` MUST NOT exceed 49110 bytes, so that the
  avatar record stays within 48 KiB (§8.10). Senders start at a quality of
  about 0.8 and lower the quality (down to about 0.4), then the size, until
  it fits.

Receivers check before decoding that `content_type` is one of the two and
matches the file's signature, and that the pixel size in the image header
is at most 256 × 256; they decode nothing larger and show the picture
cropped to the circle (centred, if it is not square). Header parsers and
decoders can disagree, so receivers also check the size the decoder itself
reports before it allocates pixels (iOS: `CGImageSource` properties;
Android: `ImageDecoder.OnHeaderDecodedListener`) and refuse more than 256
pixels on a side. Both platforms MUST
decode JPEG and HEIC (iOS: ImageIO; Android: `ImageDecoder`, API 28+).

**Validation.** A `ProfileAvatar` without a kind, with `preset_id` 0 or an
unknown id, another `content_type`, an image that does not decode or is
larger than 256 pixels on a side, or a serialization over 49110 bytes is
shown as the initial letter. It never makes a receiver drop the message or
record around it.

**Storage.** Pictures exist on the phone only inside the encrypted local
store (§5.3): the user's own choice, each contact's last one and search
results (§8.10). A photo is never written unencrypted (no temporary file, no
image disk cache), and the uncropped original is not kept. A contact's
picture is deleted with the contact (also by ghost mode's `wipe_contact`,
§8.2).

**Which picture is shown.**

| for | picture |
|---|---|
| the user | the user's own choice |
| an accepted contact | the contact's last `ProfileUpdate.profile_avatar`; until the first `ProfileUpdate` arrives, MAY the copy found in search |
| a search result, and the account of the user's own message request | the avatar record (§8.10) |
| an incoming message request | the initial letter, or a neutral placeholder. A stranger's photo is content nobody asked for, and decoding it hands attacker-chosen bytes to the image decoders, so the client MUST NOT fetch the requester's record, nor decode a photo from it, before the user explicitly asks for it (e.g. taps "Show picture" on the opened request) or accepts the request. After such a tap it MAY fetch the record once, under the verified `sender_username` (§6.3), and show it. Never for a list row or any other automatic display |
| anyone else | the initial letter |

Clients SHOULD NOT fetch the avatar record of an accepted contact: its E2EE
copy is current, and every fetch tells the relay who looked at whom.

### 8.10 Avatar records for search

By design, when someone looks up the exact username (§4.4), the result
shows the user's picture, also before any conversation. The picture is kept
on the relay encrypted under a key that only someone who knows the username
can derive. That keeps it from the relay and from a copy of its database,
but **not** from anyone who knows or guesses the username (§9).

**Setting.** "Aramada göster" / "Show in search", default **off for new
accounts**, one per account, kept in the vault and in the backup (§5.5).
Existing stored preferences are unchanged. Backups and pre-avatar account
records retain their historical decoding defaults; restoring them does not
silently reset a preference. The settings screen
SHOULD explain it — TR: "Açıkken kullanıcı adını tam olarak yazan herkes
profil resmini görebilir. Kapalıyken yalnızca kişilerin görür. Kapatman,
daha önce görenlerdeki kopyaları silmez; sunucu da kullanıcı adını tahmin
ederek resmi açabilir." EN: "When on, anyone who types your exact username
can see your profile picture. When off, only your contacts see it. Turning
it off doesn't remove copies others already fetched, and the server can
open the picture by guessing your username." — and the photo picker SHOULD
say the same while the setting is on. With the initial letter nothing is published; the
setting is kept for the next choice.

**Key.**

```
seed      = PBKDF2-HMAC-SHA256(
                password   = UTF-8(normalizedUsername),             // §3.1
                salt       = UTF-8("BRP/avatar/v1"),
                iterations = 100000,
                dkLen      = 32)
avatarKey = HKDF-SHA256(ikm  = seed,
                        salt = accountId (16 raw bytes),
                        info = UTF-8("BRP/avatar/key/v1"),
                        L    = 32)
```

* The salt is not the username hash's (`BRP/username/v1`, §3.2), so the
  relay cannot compute `seed` from the `usernameHash` it stores; it needs
  the username itself.
* 100 000 iterations cost a phone about as much as the username hash, and a
  lookup computes both (in parallel). More iterations would hardly slow the
  relay, which can test guessed usernames against its stored hashes first
  and then needs one more derivation per hit (§9).
* The account id, in the HKDF salt and in the AAD, binds a record to its
  account: a record of one account never opens as another's, also when a
  released username is taken by someone else later (§3.3).

**Record.**

```
header    = "BRPA" ‖ 0x01 ‖ uint64_be(version)                      // 13 bytes (§4.12)
plaintext = ProfileAvatar protobuf ‖ 0x80 ‖ 0x00 * k                // exactly len(record) − 41 bytes
record    = header ‖ nonce(12)
            ‖ AES-256-GCM(avatarKey, nonce, aad = header ‖ accountId(16), plaintext) ‖ tag(16)
len(record) = the smallest of 8192, 16384, 32768, 49152 with len(protobuf) + 42 ≤ len(record)
```

* The four sizes hold a `ProfileAvatar` of at most 8150, 16342, 32726 and
  49110 bytes. A writer MUST use the smallest size that fits. Every preset
  and small photos (up to about 8 KB) share the smallest; the relay sees
  only the size.
* `nonce` is 12 fresh random bytes for every record. A key serves one
  username of one account, and the write limit (§4.11) keeps it far below
  2^32 records.
* `version` (1..2^63 − 1) orders the account's writes (§4.12). The client
  keeps the last version it used in the vault (raised to `X-Avatar-Version`
  after a `409`) and writes with that + 1.
* Readers MUST check before deriving anything that the length is one of the
  four and the header starts with `"BRPA" ‖ 0x01`. After decryption they
  strip trailing zeros and then exactly one `0x80` (as §5.1). A wrong
  length or header, a GCM failure, bad padding, a protobuf that does not
  parse or a `ProfileAvatar` that fails §8.9 all mean "no picture": the
  initial letter, no error shown.

**Publishing.** The client keeps the desired state of its record — a record
of the current picture under the current username while the setting is on
and the picture is a preset or a photo, otherwise none — and brings the
relay in line whenever it changes (a new picture, the setting, the
username, a restore) and while an earlier attempt's outcome is unknown:

* desired record: `PUT /v1/accounts/me/avatar` with the next version;
* desired none: `DELETE /v1/accounts/me/avatar` with `X-Avatar-Version` =
  the next version;
* `409 stale_version`: the same again with `X-Avatar-Version + 1` (a
  record is sealed again, since the version is in its AAD and header; a
  correct client needs at most one repeat);
* no answer, `429` or `5xx`: again after `Retry-After` or at the next
  connection; `404` or `405` (a relay without §4.12): again after the next
  app start. Stores and deletes have separate budgets (§4.11), so a
  `Retry-After` holds back only writes of its own kind: when the desired
  state becomes none while a refused `PUT` waits, the `DELETE` is made at
  once.

Writes are made one at a time, in order, and only after the vault is open.
Since every write carries a higher version than all before it, a retry of
an older write that reaches the relay late is refused instead of undoing a
newer one; in particular, a publish still under way cannot outlive turning
the setting off.

**Username change.** The relay deletes the record with the change (§4.3).
Once the client knows the change happened (`204`, or `GET /v1/accounts/me`
returns the new hash) it derives the new username's key and publishes as
above. Until then a lookup of the new username shows the initial letter;
the old username is held (§3.3) and finds nobody.

**Deleting.** Turning the setting off, choosing the initial letter and
deleting the account remove the record (§4.12). Copies that others fetched
before stay with them, and so do copies in the relay database's binary and
undo logs or backups until those are rotated (§9).

**Search.**

1. Normalize the typed username (§3.1) and compute `usernameHash` (§3.2);
   `seed` MAY be computed at the same time.
2. `GET /v1/directory/{usernameHash}` (§4.4). On `404` there is no result,
   and no avatar request is made.
3. Show the result at once, with the initial letter. If the account is an
   accepted contact whose `ProfileUpdate` has arrived, show the picture from
   it (§8.9) and stop.
4. `GET /v1/accounts/{accountId}/avatar`, with `If-None-Match` if a copy
   for this account and username is cached. `304`: show the cached copy.
   `404`: keep the initial letter. `200`: check the length and header,
   derive `avatarKey` with the account id from step 2, open the record with
   `aad = header ‖ accountId`, unpad, parse and validate (§8.9), and show
   the picture; any failure keeps the initial letter. No answer, `429` or
   `5xx`: keep the initial letter; the next search tries again.
5. **No rollback.** The version of an account only grows (§4.12), so a
   record older than one already opened can only be a replay. Readers keep,
   per account id and normalized username, the highest version they have
   opened (as long as they keep copies, below; a `404` or a record that
   fails to open does not lower it), and MUST ignore a `200` whose header
   version is below it: they keep the cached copy if there is one, else the
   initial letter, and do not derive a key for it.

The avatar request is made only after an exact directory hit, never while
the user is still typing, and never for lookups the client makes on its own
(username checks, §3.3; sender verification, §6.3).

**Caching.** A copy from search is kept in memory or in the encrypted local
store (§5.3), never in a platform HTTP cache (iOS `URLCache`, OkHttp
`Cache`: off for these requests) or a plain file. It is keyed by account id
and normalized username, stores the version (`ETag`) it was opened from,
and is revalidated with `If-None-Match` at the next search; a copy that was
fetched or revalidated less than 60 seconds ago MAY be shown again from
memory without a request (the fetch limit is 60 an hour, §4.11). It is
dropped when a lookup of that username returns another account or `404`;
copies in memory, and the highest versions of step 5, are dropped when the
DEK leaves memory (§5.4). Rollback protection therefore lasts only as long
as that memory (§9).

## 9. Known limits (stated honestly)

* A modified client or a camera can keep messages that "disappear". Android
  blocks screenshots with `FLAG_SECURE`; iOS can only detect them
  (`ScreenshotNotice`).
* The relay sees: IP addresses at connection time (use Tor/VPN to hide), the
  recipient account id of each envelope, envelope sizes (padded) and timing,
  and who uploads media. It cannot read content or the sender inside a
  sealed envelope. The apps never send it usernames in clear (but invite
  links do, see below), contact lists or display names; the one piece of a
  profile it holds is a picture shown in search, stored encrypted under the
  username (see below). What it can still infer is stated in the next
  bullets.
* Bundle, identity and directory lookups (§4.3–§4.5) are authenticated, so
  the relay learns, once per new contact, which account looked up which.
  Avatar fetches (§4.12) are authenticated too: the relay learns which
  account looked at whose picture. Tapping "Profil resmini göster" / "Show
  picture" on a message request therefore tells the relay which account the
  requester probably is, which sealed sender otherwise hides from it; the
  app says so next to the button.
* A profile picture shown in search (§8.10) is protected only by the
  username. Anyone who knows the exact username — and usernames exist to be
  passed on — can fetch and decrypt the picture of an account that leaves
  "Show in search" on, without being a contact and without the owner
  learning of it. The relay cannot open a record without the username, but
  it stores every account's username hash (§3.2) under one salt shared by
  all accounts: it can hash guessed usernames (names, words, short or
  common ones) offline at 100 000 iterations per guess for all accounts at
  once, and for every guess that matches derive that account's avatar key
  with one more PBKDF2. A long, unusual username makes this slower, not
  impossible; only turning the setting off keeps the picture away from the
  relay (and from anyone who copies its database). Whoever can derive the
  key — the relay, for a username it has guessed — can also replace the
  picture searchers see with one of its own; contacts are not affected, as
  they use the E2EE copy (§8.9).
* **Invite links tell the username in clear.** Opening or previewing an
  invite link (`https://us2us.app/u/<name>`, docs/INVITES.md) sends the
  username to us2us.app, which is served by the same process, operator and
  host as the relay. From it the server can compute the username hash, find
  the account id and derive the avatar key, and so open that account's
  picture in search without guessing. Our server does not log, store or
  look up the name (§4.10), but that is a policy, not a protection.
* **Rollback.** Without any key, the relay (or anyone who copied its
  database) can serve any earlier record of the same account and username
  instead of the current one: an older picture, one the owner later
  replaced with the initial letter or took out of search, or one from an
  earlier time the account held the same username. It can also answer
  `404` to hide a record, or `304` to keep an old copy in place. The AAD
  binds a record to its account and version, but a searcher has no
  reference for the current version: readers ignore a record older than
  one they opened before (§8.10 "Search" step 5), which protects only
  within the memory they keep, not a first search.
* The relay sees which accounts publish a picture for search, when they
  change it (and the version counter of their writes, i.e. how many changes
  were made), and the record's size: 8, 16, 32 or 48 KiB. The smallest
  holds every preset and small photos; a larger one is certainly a photo,
  and the size says roughly how detailed it is. Since any authenticated
  account may fetch any record (§4.12), anyone who knows an account id —
  a contact, a former contact, someone blocked — sees the same by fetching
  it up to 60 times an hour: whether "Show in search" is on (`200` against
  `404`), the size, the version and when it changes. After a username
  change made to get away from someone who knows the account id, they can
  fetch the new record and test guesses of the new name against it offline
  at PBKDF2 speed, instead of 60 directory lookups an hour; turning "Show in
  search" off before the change avoids this (§3.3).
* Deleting a record (setting off, the initial letter, a username change,
  account deletion) stops further fetches. It cannot recall copies that
  others already fetched, nor records kept in backups of the relay's
  database made before, nor copies in its binary and undo logs until they
  are rotated or purged (`relay/deploy/mysql/us2us.cnf` turns the binary
  log off). Blocking someone does not keep them from a picture shown in
  search if they know the username, and contacts keep the last picture
  they received.
* Sealed sender is not anonymity against IP addresses and timing. A sealed
  send (§4.6) leaves from the same IP address as the sender's authenticated
  WebSocket (§4.7), and media is uploaded with authentication but downloaded
  anonymously from the recipient's IP address (§4.9). A relay that logged IP
  addresses and timing could link most sealed sends to their sender and
  every attachment's uploader to its recipient. The isolation of §4.6 removes
  only the connection and TLS-session link; a VPN or a Tor circuit shared by
  both kinds of request hides the real IP address but does not unlink them.
  The relay SHOULD NOT log IP addresses (§4.10), and ours does not.
* A sealed send carries either `X-Access-Key` or `X-PoW` (§4.6), so the
  relay learns whether the sender is an accepted contact of the recipient or
  a stranger (a message request), but not which contact.
* Post-quantum protection in v1 covers message content only. A sealed
  envelope (§6) is HPKE with DHKEM(X25519) to the recipient's long-term
  identity key, which is never rotated and is part of the account backup
  (§5.5). Anyone who records envelopes (the relay operator, someone who
  breaches the relay while envelopes are stored, or a network observer if
  TLS used a classical key exchange) and later obtains that identity private
  key (from the phone, or from the backup file together with its password)
  or has a cryptographically relevant quantum computer learns
  `sender_account_id`, `sender_username` (while a request is pending) and
  `type` of those envelopes. The libsignal ciphertext inside stays protected
  (PQXDH and SPQR). PQXDH authentication and safety numbers are classical.
  libsignal v0.105 offers no post-quantum sealing to an identity key;
  Signal's own sealed sender is classical too. A v2 envelope with an
  ML-KEM layer is possible later and changes nothing about IP and timing.
* Trust on first use: the first identity key of a contact comes from the
  relay (account ids are assigned by the relay and not derived from keys).
  A malicious or compromised relay could substitute its own keys in both
  directions at first contact and read along, as long as it keeps
  re-encrypting; this is detectable only by comparing safety numbers (§5)
  or by scanning the contact's code side by side, which pins the key before
  the first message. Closing this gap without meeting needs key
  transparency or key-derived account ids (not part of v1).
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
  and its delays. With a vault in format v1, someone who can read the device
  secret and the vault file out of the phone itself (a jailbroken or rooted
  phone, a forensic exploit of the operating system) can try every code
  offline within minutes. Format v2 (§5.2; iPhones with a Secure Enclave,
  where existing vaults move to it at their next unlock with the code)
  closes that: every guess also needs a key that never leaves the phone's
  Secure Enclave, so guessing has to happen on that phone, unlocked and under
  the attacker's control, without the app's delays. That is no hard limit:
  the salt and iteration count are in the vault header, so the PBKDF2 step
  for all 10^6 codes can be computed anywhere beforehand, and the cost left
  on the phone is one Secure Enclave operation per guess — the whole code
  space in hours, not years, on a compromised, unlocked phone. iOS has no
  hardware attempt counter for an app's code. Android (corrected 2026-10-28):
  vaults move to v2 where a TEE or StrongBox key is available, but only from
  API 35 is that key unusable while the phone is locked; below API 35, and for
  vaults that stayed v1 (the app then says "device protection: basic"), the
  remaining cost is the PBKDF2 step plus, for v2, one TEE operation per guess
  from code running as the app. A strong phone passcode
  and an up-to-date OS are the defence. The backup file (§5.5) needs no
  device secret at all: it is protected only by its separate backup password
  (at least 10 characters and a strength check; the app offers a generated
  one of about 99 bits). Anyone holding the file can guess offline without
  limit, so its safety is that password's.
* Forgetting the secret code means losing the data. There is no reset; only
  a backup (and its password) brings the account back.
