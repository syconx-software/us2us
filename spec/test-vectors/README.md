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
| `avatar-record.json` | `username` (normalized), `accountId` (16 B), `version` (decimal string), `nonce` (12 B), `profileAvatar` (protobuf), `seed`, `avatarKey`, `record`, `valid` | PROTOCOL.md §8.10: PBKDF2 and HKDF give `seed` and `avatarKey`; for `"valid": true`, sealing `profileAvatar` with `version` and `nonce` gives `record`, and opening `record` with `username` and `accountId` gives `profileAvatar`; opening fails with another username or another account id. Include `ProfileAvatar{preset_id: 1}`; photos whose serialized `ProfileAvatar` is 8150, 8151, 16342, 16343, 32726, 32727 and 49110 bytes long (records of 8192, 16384, 16384, 32768, 32768, 49152 and 49152 bytes); a version ≥ 2^32; and one `"valid": false` (a flipped ciphertext byte) that must fail to open. Sealers must also refuse a `ProfileAvatar` of 49111 bytes (no vector) |

## Shared community vectors

Four files at the top level are shared instead of being generated per
platform: the first three by Swift, Kotlin and the relay
(`relay/test/unit/community.test.ts`), `communities-content.json` by Swift
and Kotlin only (the relay never sees content); see
[COMMUNITIES.md](../../docs/COMMUNITIES.md):

| File | Vector fields | Verifier checks |
|---|---|---|
| `communities.json` | one signed state: `stateHex`, `state`, `signature`, `ownerIdentityKey`, `contentHex`, ... | the state decodes and the libsignal signature verifies with the owner key |
| `communities-invalid.json` | `reason`, `stateHex`, `valid` | the canonical state decoder accepts exactly the `"valid": true` states |
| `communities-names.json` | `description`, `name`, `valid`, `canonical` (null if invalid) | the name rule turns `name` into `canonical`, or refuses it |
| `communities-content.json` | `case` (table below), `reason`, `valid`, `communityMessage` (protobuf), `content` (the same as `Content{community}`) | building the named case yields identical bytes for both fields, parsing them yields an equal message, and the structural validation of COMMUNITIES.md "Extension v2" accepts exactly the `"valid": true` cases |

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
| `sticker_lang` | `Content{data: DataMessage{message_id: id(13), sent_at: 1760000000008, sticker: Sticker{pack_id: "us2us.words", sticker_id: 101, emoji: "👋", lang: "tr"}}}` |
| `reaction` | `Content{data: DataMessage{message_id: id(12), sent_at: 1760000000006, reaction: Reaction{target_message_id: id(1), emoji: "❤️"}}}` |
| `receipt` | `Content{receipt: ReceiptMessage{type: READ, message_ids: [id(1), id(2)]}}` |
| `receipt_saved` | `Content{receipt: ReceiptMessage{type: SAVED, message_ids: [id(3)]}}` |
| `typing` | `Content{typing: TypingMessage{started: true}}` |
| `presence` | `Content{presence: PresenceMessage{state: ONLINE, valid_for_seconds: 300, reply: true}}` |
| `ghost_settings` | `Content{control: ControlMessage{settings: ConversationSettings{updated_at: 1760000000007, default_expire_timer_seconds: 0, ghost: GhostMode{enabled: true, timeout_seconds: 3600, wipe_contact: false}}}}` |
| `wipe` | `Content{control: ControlMessage{wipe: ConversationWipe{requested_at: 1760000000008, reason: GHOST_TIMEOUT}}}` |
| `access_key` | `Content{control: ControlMessage{access_key: AccessKeyUpdate{access_key: id(13)}}}` |
| `profile` | `Content{control: ControlMessage{profile: ProfileUpdate{display_name: "Kağan", updated_at: 1760000000009, username: "kagan_y", presence_sharing: true}}}` |
| `profile_avatar_preset` | `Content{control: ControlMessage{profile: ProfileUpdate{display_name: "Kağan", updated_at: 1760000000010, username: "kagan_y", presence_sharing: true, profile_avatar: ProfileAvatar{preset_id: 1}}}}` |
| `profile_avatar_photo` | `Content{control: ControlMessage{profile: ProfileUpdate{updated_at: 1760000000011, username: "kagan_y", profile_avatar: ProfileAvatar{photo: AvatarPhoto{content_type: "image/jpeg", data: 0xffd8ffd9}}}}}` |
| `sealed_content` | `SealedContent{sender_account_id: id(14), sender_device_id: 1, type: 3, ciphertext: 0x0102030405, sender_username: "alice_01"}` (not a `Content`) |

## Community content cases (`communities-content.json`)

Every case is a `CommunityMessage` with `community_id: 11111111222243338444555555555555`
and `revision: 2` (the signed state of `communities.json`) plus the fields
below; `id(n)` as in the content cases, `m2` =
`00000000000040008000000000000002` (an account id), `img` =
`Attachment{kind: IMAGE, media_id: id(0x23), key: 64×0x06, digest: 32×0x07, size: 123456, content_type: "image/jpeg", width: 1920, height: 1080, thumbnail: 0xffd8ffd9}`.
The structural checks need no roster: sender, revision freshness and
delete authorization are checked against local state and are not covered.

| `case` | Fields | `valid` |
|---|---|---|
| `text` | `message_id: id(0x21), sent_at: 1760000000000, text: "Merhaba topluluk 👋"` | true |
| `media_caption` | `message_id: id(0x22), sent_at: 1760000000001, text: "Gün batımı", attachment: img` | true |
| `media_file_no_caption` | `message_id: id(0x24), sent_at: 1760000000002, attachment: Attachment{kind: FILE, media_id: id(0x25), key: 64×0x08, digest: 32×0x09, size: 5000, content_type: "application/pdf", file_name: "plan.pdf"}` | true |
| `receipt_read` | `message_id: id(0x26), sent_at: 1760000000003, receipt: CommunityReceipt{type: READ, message_ids: [id(0x21), id(0x22)]}` | true |
| `receipt_delivery` | `message_id: id(0x27), sent_at: 1760000000004, receipt: CommunityReceipt{type: DELIVERY, message_ids: [id(0x24)]}` | true |
| `delete` | `message_id: id(0x28), sent_at: 1760000000005, delete_target_message_id: id(0x22), delete_target_sender_id: m2` | true |
| `invalid_empty` | `message_id: id(0x29), sent_at: 1760000000006` | false |
| `invalid_whitespace_text` | `message_id: id(0x2a), sent_at: 1760000000007, text: " \n\u3000"` | false |
| `invalid_receipt_with_text` | `message_id: id(0x2b), sent_at: 1760000000008, text: "x", receipt: CommunityReceipt{type: READ, message_ids: [id(0x21)]}` | false |
| `invalid_receipt_no_ids` | `message_id: id(0x2c), sent_at: 1760000000009, receipt: CommunityReceipt{type: DELIVERY}` | false |
| `invalid_receipt_short_id` | `message_id: id(0x2d), sent_at: 1760000000010, receipt: CommunityReceipt{type: READ, message_ids: [id(0x21), 15×0x22]}` | false |
| `invalid_receipt_unknown_type` | `message_id: id(0x2e), sent_at: 1760000000011, receipt: CommunityReceipt{type: 3, message_ids: [id(0x21)]}` | false |
| `invalid_media_delete_token` | `message_id: id(0x2f), sent_at: 1760000000012, attachment: img with delete_token: 32×0x05` | false |
| `invalid_media_attachment_caption` | `message_id: id(0x30), sent_at: 1760000000013, attachment: img with caption: "Foto"` | false |
| `invalid_media_voice` | `message_id: id(0x31), sent_at: 1760000000014, attachment: img with kind: VOICE, content_type: "audio/mp4", no thumbnail` | false |
| `invalid_media_and_receipt` | `message_id: id(0x32), sent_at: 1760000000015, attachment: img, receipt: CommunityReceipt{type: DELIVERY, message_ids: [id(0x21)]}` | false |
| `invalid_delete_without_sender` | `message_id: id(0x33), sent_at: 1760000000016, delete_target_message_id: id(0x22)` | false |
| `invalid_delete_short_target` | `message_id: id(0x34), sent_at: 1760000000017, delete_target_message_id: 15×0x22, delete_target_sender_id: m2` | false |
| `invalid_delete_with_text` | `message_id: id(0x35), sent_at: 1760000000018, text: "silindi", delete_target_message_id: id(0x22), delete_target_sender_id: m2` | false |
| `invalid_zero_sent_at` | `message_id: id(0x36), sent_at: 0, text: "Merhaba"` | false |

## Upstream privacy feasibility (separate from community content versions)

`communities-privacy-probe.json` contains **public synthetic test keys** and
serialized zkgroup credentials/proofs made by pinned libsignal 0.105.0. It is
not a community v3 message, a BRP transport, a production credential or a new
cryptographic construction. `Us2usPrivateGroupProbe.java` generates it through
the upstream Java API using fixed randomness **for test data only**; use the
pinned libsignal-client and matching kotlin-stdlib JARs on the classpath:

```sh
java --class-path "$TEST_LIBSIGNAL_JAR:$TEST_KOTLIN_STDLIB_JAR" \
  spec/test-vectors/Us2usPrivateGroupProbe.java /tmp/new-privacy-probe.json
```

The destination must not exist. Review regenerated bytes before replacing a
fixture. Swift/Kotlin `PrivateGroupUpstreamProbe` tests verify the same Java
credential response, presentation and encrypted member identifier, with
wrong-account/salt/group/issuer/expiry and encrypted-blob boundary cases.
The reserved blob trailer is deliberately ignored by upstream; application
versions must not rely on it for authentication. These probes do not establish
private membership or hidden routing in the current service. A full protocol
review remains required before changing the advertised metadata guarantees.

## Communities v3

`communities-v3.json` contains 27 shared Swift/Kotlin wire vectors for built-in
stickers and bounded AAC/M4A voice pointers: valid bounds, unknown sticker
fallback, oversized/malformed fields, custom stickers and mixed-kind rejection.
The existing 20 v2 vectors are unchanged.
