# Cross-implementation test vectors

The Kotlin (`android/core`) and Swift (`ios/Us2usKit`) implementations each
**generate** vectors from their own code into their own folder and **verify**
the other implementation's folder in their test suites. If either side
changes behavior, the other side's tests fail.

```
spec/test-vectors/
  kotlin/<category>.json    written by android/core tests (generator)
  swift/<category>.json     written by ios/Us2usKit tests (generator)
```

Generators must be deterministic where the algorithm allows it (fixed inputs,
fixed salts/nonces/IVs). `sealed.json` cannot be: every seal uses a fresh
HPKE encapsulation, so it is verify-only, and a generator MAY keep an
existing file whose vectors still verify. Every file has the shape:

```json
{ "version": 1, "generator": "kotlin" | "swift", "category": "<name>", "vectors": [ ... ] }
```

All binary values are **lowercase hex**. Strings are UTF-8 JSON strings.

## Categories

| File | Vector fields | Verifier checks |
|---|---|---|
| `username.json` | `input`, `normalized` (null if invalid), `hash` (null if invalid) | same normalization, validity and PBKDF2 hash. Include ≥ 12 cases: plain, uppercase, surrounding spaces, surrounding U+0085 and U+3000 (trimmed) and U+001C (not `White_Space`, so invalid), fullwidth chars (NFKC), too short, too long, leading digit, invalid chars (`-`, `.`, `ş`, `ı`), exactly 3 and 24 chars |
| `pow.json` | `token`, `nonce` (decimal string), `difficulty`, `valid` | leading-zero-bit check gives `valid`. Include valid solutions for difficulty 0, 1, 8, 12, 16 and invalid ones (nonce off by one, difficulty + 1) |
| `padding.json` | `plaintext`, `padded` | pad(plaintext) == padded and unpad(padded) == plaintext; lengths 0, 1, 158, 159, 160, 161, 319, 320, 1000 |
| `attachment-padding.json` | `size`, `paddedSize` | P(n) for n = 0, 1, 541, 542, 568, 569, 1000, 10000, 1048576, 52428800 |
| `attachment.json` | `plaintext`, `key` (64 B), `iv` (16 B), `blob`, `digest` | encrypt with given key/iv gives blob/digest; decrypt(blob) gives plaintext. Plaintext lengths 0, 1, 541, 600, 5000 |
| `sealed.json` | `recipientIdentityKeyPair` (libsignal serialization), `envelope`, `sealedContent` {`senderAccountId` (16 B hex), `senderDeviceId`, `type`, `ciphertext`, `senderUsername`}, `valid` | open(envelope) with the key pair gives exactly those fields. Every vector carries `valid`; include one with `"valid": false` (a flipped byte) that must fail. An absent `senderUsername` is written as `""`; verifiers accept `""` or `null` |
| `content.json` | `case` (name from the table below), `protobuf` | building the named case yields identical bytes, and parsing the bytes yields an equal message |
| `vault.json` | `code`, `deviceSecret`, `salt`, `iterations`, `nonce`, `dek`, `vault` | create(code, deviceSecret, salt, iterations, nonce, dek) == vault; open(vault, code, deviceSecret) == dek; open with another six-digit code fails. Every `code` is six ASCII digits (PROTOCOL.md §5.2), one with a leading `0`. Use iterations 1000 for most vectors plus one with 600000 |
| `backup.json` | `password`, `salt`, `iterations`, `nonce`, `accountBackup` (protobuf hex), `file` | create == file; open(file, password) == accountBackup and parses as `AccountBackup`; open with a wrong password fails. Passwords follow §5.5; iterations 1000 plus one with 1000000, and one password with surrounding `White_Space` and fullwidth characters (e.g. U+00A0, `ｙ`), which the KDF uses NFKC-normalized but untrimmed |
| `secret-code.json` | `code`, `problem` (`null` \| `"format"` \| `"repeated"` \| `"sequence"` \| `"common"`) | the rules of PROTOCOL.md §5.2 give `problem` (the first rule that applies). Include: `format` for the empty string, 5 and 7 digits, a letter, surrounding or inner spaces (never trimmed), fullwidth `１２３４５６` and Arabic-Indic digits; all 10 `repeated` codes; all 20 `sequence` codes (wrapping ones such as `890123` and `098765` included); every entry of `spec/common-secret-codes.txt` as `common`; acceptable codes (`null`): a date, a code with a leading `0`, runs broken by one digit (`123457`, `012346`) and steps of 2 that are not listed |
| `backup-password.json` | `password`, `problem` (`null` \| `"too_short"` \| `"common"` \| `"repeated"` \| `"sequence"`) | the rules of PROTOCOL.md §5.5 give `problem`. Include: 9 code points, also padded with `White_Space` (still `too_short`); a list entry in upper case, fullwidth and inside `White_Space`; `AAAAAaaaaa`; ascending and descending code-point runs not on the list; a run of every keyboard row with ≥ 10 code points, forward and reversed; acceptable passwords (`null`), e.g. a row run broken by one character, steps of 2, and a code point that is not `White_Space` (U+001C) counting towards the length |
| `keyslot.json` | `dek`, `slotId`, `ck`, `nonce`, `slotFile` | create == slotFile, open == ck |
| `item.json` | `ck`, `itemId`, `nonce`, `plaintext`, `item` | create == item, open == plaintext |
| `fingerprint.json` | `localId`, `localKey`, `remoteId`, `remoteKey`, `displayable`, `scannable` | libsignal NumericFingerprintGenerator(5200), version 2 |

Keys used by generators (`recipientIdentityKeyPair`, `localKey`, ...) are
generated once and written into the vector, so verifiers never need the
generator's randomness.

## Content cases (`content.json`)

Both implementations build these exact messages (all bytes fields given in
hex; `id(n)` means 16 bytes all equal to `n`, e.g. `id(1)` =
`01010101010101010101010101010101`):

| `case` | Message |
|---|---|
| `text` | `Content{data: DataMessage{message_id: id(1), sent_at: 1760000000000, text: Text{body: "Merhaba 👋 dünya"}}}` |
| `text_expiring_quote` | `Content{data: DataMessage{message_id: id(2), sent_at: 1760000000001, expire_timer_seconds: 3600, quote: Quote{target_message_id: id(1), preview_text: "Merhaba"}, text: Text{body: "Hi"}}}` |
| `attachment` | `Content{data: DataMessage{message_id: id(3), sent_at: 1760000000002, view_once: true, attachment: Attachment{kind: IMAGE, media_id: id(4), delete_token: 32×0x05, key: 64×0x06, digest: 32×0x07, size: 123456, content_type: "image/jpeg", width: 1920, height: 1080, caption: "Foto"}}}` |
| `location` | `Content{data: DataMessage{message_id: id(8), sent_at: 1760000000003, location: Location{latitude: 41.0082, longitude: 28.9784, accuracy_meters: 12.5, name: "İstanbul"}}}` |
| `live_location` | `Content{data: DataMessage{message_id: id(9), sent_at: 1760000000004, live_location: LiveLocation{session_id: id(10), latitude: 39.9334, longitude: 32.8597, accuracy_meters: 5, heading_degrees: -1, speed_mps: -1, expires_at: 1760003600000, sequence: 7}}}` |
| `sticker` | `Content{data: DataMessage{message_id: id(11), sent_at: 1760000000005, sticker: Sticker{pack_id: "cb.basic", sticker_id: 3, emoji: "😂"}}}` |
| `reaction` | `Content{data: DataMessage{message_id: id(12), sent_at: 1760000000006, reaction: Reaction{target_message_id: id(1), emoji: "❤️"}}}` |
| `receipt` | `Content{receipt: ReceiptMessage{type: READ, message_ids: [id(1), id(2)]}}` |
| `typing` | `Content{typing: TypingMessage{started: true}}` |
| `presence` | `Content{presence: PresenceMessage{state: ONLINE, valid_for_seconds: 300, reply: true}}` |
| `ghost_settings` | `Content{control: ControlMessage{settings: ConversationSettings{updated_at: 1760000000007, default_expire_timer_seconds: 0, ghost: GhostMode{enabled: true, timeout_seconds: 3600, wipe_contact: false}}}}` |
| `wipe` | `Content{control: ControlMessage{wipe: ConversationWipe{requested_at: 1760000000008, reason: GHOST_TIMEOUT}}}` |
| `access_key` | `Content{control: ControlMessage{access_key: AccessKeyUpdate{access_key: id(13)}}}` |
| `profile` | `Content{control: ControlMessage{profile: ProfileUpdate{display_name: "Kağan", updated_at: 1760000000009, username: "kagan_y", presence_sharing: true}}}` |
| `sealed_content` | `SealedContent{sender_account_id: id(14), sender_device_id: 1, type: 3, ciphertext: 0x0102030405, sender_username: "alice_01"}` (not a `Content`) |
