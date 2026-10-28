# Communities extension v1 and v2 — threat model and wire contract

Status: implementation contract, 2026-10-14. This is an additive extension
to BRP v1, not a replacement for its one-to-one protocol. First release:
text conversations, one owner/administrator, at most 100 members including
the owner. Public discovery and private invitations both require approval.
Extension v2 (below) adds photos, videos and files, delivery and read
receipts, and delete for everyone, without changing anything else here.

> **Change log**
> * 2026-10-28: **relay limits and re-admission** (relay only, additive; no
>   wire change for existing calls, no new error code). A leaver that the
>   owner's next state still lists is no longer re-admitted by that state,
>   even with a new pending request: only an account new to the roster is
>   admitted, so taking a leaver back needs a state without it and then one
>   with it. Pending requests expire after 14 days and one account may
>   have at most 10 pending at a time; `DELETE /v1/communities/{id}/requests`
>   rejects all pending requests at once. Inbox quotas gain a share per
>   community (a quarter of the inbox) and per sender (8 MiB per community,
>   1 000 envelopes / 16 MiB over all communities), and a daily delivery
>   budget per sender and recipient. Retry tombstones are swept 72 hours
>   after the send and never slow quota checks. A message ID re-sent with
>   the current revision after a roster change replaces the obsolete row
>   instead of conflicting for ever.

## Security and privacy boundary

Message text is encrypted separately to each recipient through the existing
libsignal PQXDH/triple-ratchet session and sealed-envelope implementation.
There is no group cipher or shared message key. Members are not automatically
personal contacts and never receive another member's personal access key.

The relay learns community IDs, public names, owner, membership, requests,
and authenticated sender/recipient delivery metadata. This differs from
one-to-one sealed sending. Private means unlisted and invitation-only, not
anonymity from the relay. A private name is kept locally and carried in the
invitation code, never uploaded as a directory name. Public searches are
visible to the relay. The UI must disclose these differences before creation
or joining. No push, phone number, email, message-content logging, or server
plaintext history is added.

Threats covered: unauthorised approval/send/read, forged roster, roster
rollback/equivocation at an already-observed revision, identity substitution,
invitation guessing/replay/expiry, admission races, oversized fanout, duplicate
delivery and partial delivery, plaintext local storage, and accidental 1:1
contact/access-key creation. An owner signs every roster using the existing
libsignal identity-signature API. Clients pin the owner key on creation or
discovery/invitation, verify signatures and identities, and remember the
highest accepted revision. First discovery uses the same first-use trust
limitation as the account directory; a malicious first directory can lie.

A malicious relay can withhold a newer signed roster or fork clients' views.
Signatures alone do not prove freshness: immediate revocation against that
adversary is NOT promised. Against the ordinary relay, membership revision
checks and revocation are transactional. Removing a member prevents new
deliveries to/from them, purges their queued community messages, and invalidates
old-revision sends. A fetch already in flight before revocation can still
complete; previously fetched, decrypted or copied text cannot be recalled. A
malicious current member can always disclose plaintext outside the app.

Leaving is the member's own revocation. It ends the member's transport
authorization at once and purges queued mail to and from it in that
community, but it cannot recall anything already delivered: earlier messages
stay on the devices that received them, including the leaver's. The relay
learns who left and when. Until the owner publishes the next revision, the
signed roster still lists the leaver; other members keep encrypting to it
and get the same answer as for a removed member. A member that left is
never silently re-admitted: only a new pending request and an owner-signed
state that adds it anew (after one that dropped it) bring it back.

The relay verifies the owner's signature on every state it accepts, so a
stolen auth token alone cannot rewrite or brick a roster; that needs the
owner's identity private key. This does not replace client verification:
a malicious relay can still serve forged, stale or forked states.

## Compatibility and first-release scope

Existing /v1/messages, WebSocket framing, account access keys, existing proto
field numbers, 1:1 storage and byte vectors remain unchanged. Communities
have their own mailbox, polling, acknowledgements and local repositories.
Old apps never fetch this mailbox. New clients treat missing community routes
as unsupported without interrupting 1:1 delivery. Community content encountered
on a 1:1 path is ignored before contact/request/receipt/profile processing.

Existing account backups do not contain community pins or history. After
restore, clients recover only groups with a locally pinned/requested owner
identity, or groups owned by their own verified identity. Other memberships
require rediscovering and requesting the public group or redeeming a fresh
private invite. An arbitrary `/mine` record never silently enrolls a user.

Content gains oneof field 6, CommunityMessage: community_id bytes(16), revision
uint64, message_id bytes(16), sent_at uint64 milliseconds, text UTF-8 <=8192
bytes. Field numbers are respectively 1..5. No media, typing, presence,
receipts, timers, ghost mode, reactions, or history transfer in this first
extension. In particular, legacy media delete tokens cannot safely be shared
by 100 recipients. New members receive no earlier messages. Extension v2
adds media without delete tokens, receipts and delete for everyone; the rest
of this list still applies.

## Signed state (identical bytes on Swift, Kotlin and relay)

`state` and `signature` below are unpadded canonical base64url. Signature is
64 bytes produced by libsignal identity privateKey signing the EXACT state.
All integers are unsigned big-endian. State is at most 8192 bytes:

1. ASCII `BRP/CommunityState/v1` followed by one NUL byte.
2. Community ID: 16 raw UUID bytes.
3. Revision: u64, 1..9007199254740991; initial state is 1.
4. Visibility: u8, 0 private, 1 public (immutable).
5. Owner account ID: 16 bytes; owner identity key: serialized 33 bytes.
6. Public name: u16 byte length then UTF-8, a canonical name (see Names
   below) for public, zero bytes for private. Names need not be unique (the
   ID distinguishes them). Name is immutable in first release.
7. Member count: u16, 1..100.
8. Each member: account ID 16 bytes, serialized identity key 33 bytes;
   sorted strictly by unsigned raw account ID bytes, no duplicates. Owner
   must occur with the same identity. Keys must begin with 0x05.

No trailing bytes. Owner/visibility/name cannot change. Each update increments
revision by exactly one. Existing members' identity keys cannot silently
change. Adding an identity requires its pending request and the owner
reviewing that key. Clients compare prekey identity with signed roster before
encryption and fail closed on mismatch. This is not a new signature scheme;
it is domain-separated application data signed using libsignal.

`spec/test-vectors/communities.json` is a signed state made with libsignal;
`spec/test-vectors/communities-invalid.json` lists states every decoder must
refuse (unsorted, duplicate or ownerless rosters, trailing bytes, a private
name, a key without 0x05, revision 0, 101 members and more) and two it must
accept, one proving unsigned byte order (an ID starting 0x80 sorts after one
starting 0x00).

## Names

One rule for public names in signed states and for private names carried in
invitations (`name` in the invitation code, which may also be absent):

1. Trim leading and trailing code points from exactly this set: U+0020,
   U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000
   (Unicode `White_Space` without its controls). Nothing else is trimmed, so
   do not use a platform trim function: TAB, LF, U+001C–U+001F, U+0085 and
   U+FEFF stay and are judged by the next steps.
2. The result must be 1..120 UTF-8 bytes.
3. REJECT any code point in: U+0000–U+001F, U+007F–U+009F (Cc), U+061C,
   U+200E, U+200F, U+202A–U+202E, U+2066–U+2069 (bidi marks, embeddings,
   overrides and isolates).
4. Everything else is allowed, including U+200D ZWJ, variation selectors and
   emoji sequences. No Unicode normalization is applied.

The trimmed text is the canonical name. A signed state or invitation carries
exactly the canonical name; a state whose name differs from its own
canonical form is invalid. Work on Unicode scalar values (code points), not
on UTF-16 units or grapheme clusters. `spec/test-vectors/communities-names.json`
lists inputs with `valid` and the `canonical` result. The relay applies the
same rule to public names and to search queries (which may be empty).

## HTTP API

All endpoints below require X-Auth-Token. UUIDs use the normal BRP UUID string
representation. No endpoint accepts X-Access-Key as community authorization.
StateResponse = `{state, signature}`. Summary = `{id, name, visibility,
ownerAccountId, ownerIdentityKey, memberCount, revision}` where visibility is
`public` or `private`. Private summary name is empty. Bodies reject unknown
security-critical shape/invalid sizes. Error bodies use the PROTOCOL.md §2.1
format; community-specific codes are listed under Errors below.

* `POST /v1/communities` `{state,signature}` -> 201 StateResponse. Owner must
  be authenticated account; initial roster contains only owner at revision
  1, with the account's registered identity key, and a valid owner signature
  (otherwise 400). Repeating the create with identical state bytes (a retry,
  even with a fresh signature) returns the stored StateResponse again with
  201; an existing ID with other bytes is 409 `stale_version`.
* `GET /v1/communities?query=<name>&after=<uuid>` -> `{communities:[Summary],
  nextCursor:uuid|null}`. Public only, case- and accent-insensitive name
  prefix (the query follows the name rule but may be empty; otherwise 400),
  ordered by ID, up to 20 rows. Empty query browses all public groups.
* `GET /v1/communities/mine` -> `{communities:[StateResponse]}` active memberships.
* `GET /v1/communities/{id}` -> StateResponse, active members only.
* `PUT /v1/communities/{id}/state` `{state,signature}` -> StateResponse, owner
  only, current+1 revision, valid owner signature. Membership follows the
  relay's own member set: an account in the new roster without transport
  membership is admitted only if it was not in the previous roster and has
  an unexpired pending request (otherwise 403). An account already in the
  previous roster that left or was deleted may stay listed, but is never
  re-admitted by such a state, even if it has filed a new request (its
  request stays pending); to take it back the owner signs a state without
  it, then one with it (since 2026-10-28; before, an unrelated roster change
  re-admitted it silently). Accounts dropped from the roster
  lose authorization atomically, with their queued mail and redeemed
  invitations. 409 `stale_version` for a stale revision, 409
  `community_full` for more than 100 members.
* `POST /v1/communities/{id}/requests` `{}` -> 202 (public only). Idempotent
  pending request; an existing member gets success. Private requires redeem.
* `GET /v1/communities/{id}/requests` -> `{requests:[{accountId,identityKey,
  createdAt}]}`, owner only, at most 100 pending requests. A request expires
  14 days after `createdAt`: it is no longer listed or admitted, and the
  requester may request again (a new `createdAt`).
* `DELETE /v1/communities/{id}/requests` -> 204, owner rejects every pending
  request at once (and the invitations those accounts redeemed); 404 for
  anyone else.
* `DELETE /v1/communities/{id}/requests/{accountId}` -> 204, owner rejects
  (and the invitation that account redeemed, if any, is deleted).
* `DELETE /v1/communities/{id}/requests/me` -> 204, the caller withdraws its
  own pending request. Idempotent: 204 also when there is no request or no
  such community. The invitation the caller redeemed for this community is
  deleted, so it cannot be replayed; a public community can be requested
  again.
* `DELETE /v1/communities/{id}/members/me` -> 204, a member other than the
  owner leaves. In one transaction the relay removes the caller's transport
  authorization (it can no longer send, fetch or read state for that
  community), deletes queued envelopes to and from it in that community
  (tombstones included) and the invitation it redeemed, and records nothing
  else. 409 `bad_request` for the owner (the owner deletes the community
  instead); 404 `not_found` when the caller is not a member or the community
  does not exist. Clients drop their local membership after 204 or 404.
* `POST /v1/communities/{id}/invites` `{expiresInSeconds}` -> 201
  `{token,expiresAt}`, owner only. 32 random bytes, 60..604800 seconds, one
  redemption, SHA-256 hash stored, at most 20 unexpired invites per group.
* `DELETE /v1/communities/{id}/invites` -> 204, owner revokes all outstanding invites.
* `POST /v1/communities/invites/redeem` `{communityId,token}` -> 202 Summary.
  Atomically consumes invitation AND creates pending request. Same-account
  retry remains successful; another account cannot replay it. An expired or
  revoked token is refused, including after prior redemption, after the
  owner rejected the request, after the redeemer withdrew it, and after the
  redeemer left or was removed.
* `DELETE /v1/communities/{id}` -> 204, owner deletes community and all its
  relay state/queued envelopes. Owner account deletion (explicit, or after
  180 days without a connection) also deletes owned communities; deleted
  member accounts lose transport access and their queued community mail.
  Owner can remove them from the next signed state. No unsigned roster
  rewrite.

Transport is separate from the 1:1 mailbox:

* `PUT /v1/communities/{id}/messages/{recipientId}/{messageId}` with sealed
  envelope bytes (1..65536; 0 is 400, more is 413), `X-Community-Revision:
  <decimal revision>` (no sign, no leading zero, at most 2^53 - 1; otherwise
  400) -> 202 `{id:<b64u envelope id>,serverTimestamp}`. Sender and recipient
  must be current distinct members; otherwise 404 `not_found`, the same for a
  recipient that was removed, left or deleted its account as for a community
  the sender cannot see. A sender skips such a recipient and refreshes the
  state. A full inbox is 429 `community_inbox_full` with `Retry-After` (see
  Limits); the sender keeps the ciphertext and retries that recipient later.
  Client fetches/validates current signed state,
  encrypts separately for each other member, and persists each ciphertext
  BEFORE sending. Maximum 99 recipients, bounded client concurrency. A 409
  requires refresh and recomputing recipients, never merely changing a header.
  The same group/sender/recipient/message ID and identical envelope is an
  idempotent retry; different bytes at the same revision conflict. A row of
  an older revision (its body already dropped by the roster change) gives
  way to a send of the same message ID at the current revision, which is
  stored as a new delivery. Text and all inner
  IDs/revision must be checked by recipients (relay cannot inspect ciphertext).
* `GET /v1/communities/inbox` -> `{envelopes:[{id,communityId,revision,
  serverTimestamp,envelope}],more:boolean}`, oldest first, <=100 envelopes,
  <=1MiB raw ciphertext per page (at least one envelope). `more` means fetch
  again after acknowledging this page (it can be true with an empty page).
  Only current memberships and revisions. Envelopes expire after 14 days; ACK
  deletes bodies. Fetch occurs only while app is unlocked/open.
* `POST /v1/communities/inbox/ack` `{ids:[b64u envelope ID]}` -> 204, at most
  100 (more is 400), deletes only authenticated recipient's envelope bodies.

Before decrypt, clients fetch/verify current signed state. First release
rejects queued messages from older revisions (including legitimate in-flight
messages) rather than processing content under an obsolete roster. UI must
represent stale/partial send failures honestly. A receive commits ratchet
state and community message atomically before ACK; dedupe key is community,
sender, message ID. Incoming sender key must match the signed roster. A group
message never creates a direct contact or exchanges profile/access-key data.

Invitations are pasted/scanned codes `us2us-community:v1:<base64url JSON>`
containing `{communityId,token,ownerIdentityKey,name}`. The name of a private
community travels only here and in encrypted local storage. Never place
tokens in HTTP URLs or analytics. The invite grants permission to request;
it grants neither membership nor decryption. Client pins the included owner
key and checks it against every fetched signed state.

Limits: 100 members (more is 409 `community_full`), 100 pending requests,
20 live invites, 20 owned groups and 50 memberships per account (each 429
`rate_limited`); bounded per-account/IP rates distinct from 1:1 send budget
(429 `rate_limited`). Delivery quotas count only live envelopes: stored, not
acknowledged, not made obsolete by a new revision, and younger than 14 days.
ACK retry tombstones and expired rows not swept yet never count. Per
recipient over all communities: 10000 live envelopes and 50MiB of live
ciphertext; of these, one community may hold at most 2500 envelopes and
12.5MiB, and one sender at most 1000 envelopes and 16MiB over all
communities. Per community, sender and recipient: 500 live envelopes and
8MiB, so one member cannot fill another's inbox for everybody else.
Pending requests: 100 per community and 10 per requesting account, each
expiring after 14 days. Deliveries per sender and recipient over all
communities: 5000 per day (each new delivery counts, also once it is
acknowledged), then 429 `rate_limited`. Tombstones stay 72 hours after the
send, long enough for a sender's retry, and are then swept. Either quota is 429
`community_inbox_full` with `Retry-After` (seconds); it clears as the
recipient fetches and acknowledges. Removing a member, a member leaving, and
deleting an account delete the envelopes it sent as well as those sent to
it. The relay deletes expired envelopes (tombstones included) and
invitations in bounded batches on every retention run.

## Errors

Besides the PROTOCOL.md §2.1 codes, community routes answer:

| HTTP | `code` | Meaning |
|---|---|---|
| 409 | `community_full` | a request, redemption or roster would exceed 100 members |
| 409 | `stale_version` | stale revision, existing community ID, or a message ID reused with other ciphertext or revision; refresh the state |
| 409 | `bad_request` | `DELETE /v1/communities/{id}/members/me` by the owner |
| 429 | `community_inbox_full` | the recipient's live community mail, or this sender's share of it, is full; with `Retry-After` |

Every other failure uses its §2.1 code. A client that does not know a code
handles it by HTTP status.

## Extension v2: media, receipts, delete for everyone

Additive to v1: same mailbox, routes, signed state, revisions, limits and
relay. The relay is unchanged and cannot tell v2 content from v1 content
except by the metadata described below. Section numbers (§) refer to
PROTOCOL.md.

### Wire

`CommunityMessage` gains four fields; fields 1..5 keep their meaning:

| # | Field | Type |
|---|---|---|
| 6 | `attachment` | `Attachment` (the 1:1 message, community rules below) |
| 7 | `receipt` | `CommunityReceipt{type = 1, message_ids = 2}`, type `DELIVERY = 1`, `READ = 2` |
| 8 | `delete_target_message_id` | bytes(16) |
| 9 | `delete_target_sender_id` | bytes(16), account ID of the target's original sender |

Every `CommunityMessage` is exactly one of four kinds. `message_id` is always
fresh and random; for a receipt or a delete it names that event itself (it
is the `{messageId}` of the delivery route), never the target.

* **text**: `text` only.
* **media**: `attachment`, with `text` as its optional caption.
* **receipt**: `receipt` only; sent to one member, the original sender.
* **delete**: `delete_target_message_id` and `delete_target_sender_id` only;
  sent to every current member.

### Validation

A receiver first applies the v1 checks unchanged (sender is a current member
other than itself, identity matches the signed roster, `community_id` is the
community, `revision` is the current accepted revision, `message_id` is 16
bytes). Then, with A = `attachment` present, R = `receipt` present, D =
field 8 or field 9 non-empty, T = `text` non-empty (presence of a message
field is presence on the wire, also when it is empty):

1. `sent_at` > 0.
2. Kind: R → receipt, requires not A, not D, not T. Otherwise D → delete,
   requires not A, not T. Otherwise A → media. Otherwise → text.
3. **text**: `text` is 1..8192 UTF-8 bytes and contains at least one code
   point that is not Unicode `White_Space` (that property includes TAB, LF
   and CR). An empty message (no text, attachment, receipt or delete) is
   therefore invalid.
4. **media**: `text` 0..8192 UTF-8 bytes, taken as-is (senders trim a
   caption and send whitespace-only as empty). `attachment.kind` is `IMAGE`,
   `VIDEO` or `FILE` (`KIND_UNSPECIFIED`, `VOICE`, `STICKER_IMAGE` and
   unknown values are invalid); `media_id` 16 bytes; `key` 64 bytes;
   `digest` 32 bytes; `delete_token`, `caption` and `waveform` empty;
   `thumbnail` 0..10240 bytes; `content_type` and `file_name` at most 255
   UTF-8 bytes each. `file_name` is read only for `FILE`; it is a display
   name, never a path. `size`, `width`, `height`, `duration_ms` as in 1:1
   (§7).
5. **receipt**: `type` is `DELIVERY` or `READ` (`TYPE_UNSPECIFIED` and
   unknown values are invalid); 1..1000 `message_ids`, each exactly 16
   bytes. A repeated id counts once.
6. **delete**: both fields exactly 16 bytes.

A message failing any step is dropped: the ratchet state is committed, no
message is stored, the envelope is acknowledged, nothing is sent back. The
1:1 `Attachment` checks (32-byte `delete_token`, 2048-byte caption) do NOT
apply here. The v1 dedupe key (community, sender, `message_id`) covers every
kind; receipts and deletes are idempotent, so remembering their ids is
optional. `spec/test-vectors/communities-content.json` holds valid and
invalid encodings of every kind.

### Media

The sender strips metadata and encrypts the file once as in §7
(fresh key and IV), uploads the blob ONCE with `POST /v1/media` (§4.9) and
puts the same `media_id`, `key`, `digest`, `size` and optional thumbnail
(inline JPEG, ≤ 10 KiB) in the separately sealed envelope of every
recipient. The upload counts toward the sender's media quota (§4.11:
64 MiB per blob, 60 uploads/hour, 2 GiB/day), once, whatever the member
count. The upload finishes before any envelope is sent; a failed upload
fails the message. A fanout repeated after a `409` reuses the blob.

The `deleteToken` from the upload stays on the sender's phone and never
enters a `CommunityMessage`. Recipients download with `GET /v1/media/{id}`,
verify `digest` and the HMAC and truncate to `size` (§7), stop at
`blobSize(size)`, and never send `DELETE`. Clients MAY download
automatically or on demand. A `404` is shown as "no longer available".

The blob lives until the sender deletes it or the relay's 14-day TTL
(§4.10). The sender deletes it ONLY with delete for everyone (its own, or
the owner's on its message, below); "delete for me" never deletes the blob,
on any side.

### Receipts

* **DELIVERY** is always sent, whatever the read-receipt setting, for every
  text and media message stored (not for drops, duplicates, receipts or
  deletes). At the end of each sync pass (fetching the inbox until `more` is
  false), the receiver sends, per community and original sender, one
  receipt with the ids stored in that pass; more than 1000 ids are split.
* **READ** follows the user's global read-receipt setting with the 1:1 rule
  (§8.4, and the apps' message info, which shows no Read step
  while it is off): a user with read receipts off sends no READ and is
  shown no READ state for its own messages; incoming READ MAY be stored
  but is not displayed while the setting is off. READ is sent once per
  message, when the message is displayed in the open community
  conversation, only for others' text and media messages that are not
  deleted; nothing is sent later for messages read while the setting was
  off. Clients coalesce READ per original sender for a few seconds.
* A receipt goes ONLY to the original sender of its ids, as a
  single-recipient community envelope (`PUT
  /v1/communities/{id}/messages/{originalSenderId}/{messageId}`), under the
  current revision, persisted before sending, retried and refreshed on
  `409` exactly like a text. If the original sender is no longer a member
  (`404`), the receipt is dropped. A `429` delays it; it never blocks
  receiving.
* **Applying.** The receiver of a receipt applies each id only if it names
  a text or media message that the receiver itself sent in that community,
  that is not deleted, and whose recorded recipient set contains the
  receipt's sender (who passed the current-member check). Every other id is
  ignored. DELIVERY sets the member's delivered time; READ sets the read
  time and, if unset, the delivered time. Times are the local time the
  receipt was applied (as in 1:1); they are set once and never move back.
* Other members never learn who received or read what: there is no
  receipt fanout and no aggregate on the wire.

### Delete for everyone

A delete is valid when its sender D is the target's original sender
(`delete_target_sender_id` = D), OR D is the owner of the current signed
roster (moderation; the owner is immutable). Anything else is dropped. The
deleter fans it out to every current member except itself, recomputed on
`409` like a text; members that joined after the target simply remember it
(below). The deleter replaces its own copy at once, and no longer sends
envelopes of the target that the relay has not accepted yet.

A receiver looks up (community, `delete_target_sender_id`,
`delete_target_message_id`):

* a text or media message: replaced by a placeholder that keeps only sender,
  `message_id`, `sent_at` and who deleted it (sender or owner). Text,
  attachment descriptor (key, digest, `media_id`), thumbnail, downloaded and
  decrypted files and running downloads are removed. Receipts for it are
  ignored from then on.
* already a placeholder: nothing.
* not found: remembered as a pending delete (community, target sender,
  target id, deleter role) for 30 days, at most 1000 per community (the
  oldest goes first). A text or media message arriving later with that key
  is stored directly as the placeholder (DELIVERY is still sent, READ never,
  its blob never downloaded).

Media: when the deleter is the original sender, it also sends `DELETE
/v1/media/{id}` with its delete token (`204` or `404` are final; it retries
after network failures, then forgets the token). When the owner deletes
another member's media message, the author's own client, on receiving
that delete, SHOULD delete the blob the same way. Otherwise the blob lives
until its TTL. The UI distinguishes "This message was deleted" (by its
sender) from "Removed by the owner".

"Delete for me" is local only: the message, its files and receipt state are
removed from this phone, the key (community, sender, `message_id`) is kept
for 30 days so a retransmission is a duplicate (as §8.1), nothing is sent
and no blob is deleted.

### Security and privacy (in addition to the v1 threat model)

* **Shared blob.** Every member that received the pointer, including one
  removed or gone later, can download and decrypt the media until the
  sender deletes it or 14 days pass; anyone given `media_id` and `key` can
  too. Delete for everyone removes the blob and honest members' copies, but
  cannot recall a copy already downloaded, saved or forwarded. Owner
  moderation removes the blob only through the author's honest client.
* **Relay metadata, media.** The relay learns the uploader account, the
  padded blob size (§7), the time, and the IP and time of each download of
  that blob (how many members fetched it and when), and can link the upload
  to the community fanout that follows it.
* **Relay metadata, receipts.** Each receipt is an authenticated
  single-recipient envelope from a member to the original sender, about one
  per member, original sender and kind per batch. Padded content (§5.1)
  makes a small receipt as large as a short text, but its single recipient
  tells it apart from a fanout. The relay can thus infer when a member
  fetched (it already sees the fetch) and roughly when it read a given
  member's messages. It never learns which messages.
* **Integrity.** Encryption stays pairwise with no transcript consistency: a
  malicious sender can show members different media, or delete for some
  members only. Receipts are a courtesy, not proof: a malicious member can
  claim READ or withhold it, but only for messages it was a recipient of.
  A malicious client can ignore deletes. v2 has no SAVED receipt: saving
  community media outside the app is not reported.
* Like texts, receipts and deletes queued under an older revision are never
  processed: a roster change can silently lose them. The UI must not
  promise that a delete reached everyone.

### Client storage

Everything below is encrypted at rest like other community data (§5.3):

* own text and media messages: the recipient set fixed at send time and,
  per member, transport state (pending, accepted by the relay, skipped or
  failed), delivered time and read time;
* own media: the plaintext file (no re-download) and the delete token until
  the blob is deleted or 14 days old;
* received media: the descriptor and, once downloaded, the decrypted file;
* placeholders, pending deletes and "delete for me" keys (30 days each);
* the outgoing receipt queue (persisted before sending).

Leaving, removal and community deletion remove all of it, files included.

### UI

* The chats list shows communities like conversations, ordered by last text
  or media activity, with a community marker, unread count and a preview
  ("Photo", "Video", "File" and the caption, or the deleted placeholder).
  Receipts never reorder it; deletes only change the preview.
* Own messages show an aggregate state: Sent (accepted for all recipients),
  Delivered (all delivered), Read (all read, only while read receipts are
  on). Long-press → Info lists every recipient with delivered and read
  times (read only while read receipts are on), pending or failed sends,
  and members who have left with their last state.
* Long-press → "Delete for me" on every message; "Delete for everyone" on
  own messages, and for the owner on every message. Delete for everyone
  confirms first and says that copies already downloaded or saved cannot
  be recalled.

### Compatibility

Fields 6..9 and `CommunityReceipt` are new; nothing is renumbered. v1 builds
keep them as unknown fields and require a non-empty text, so they drop
media without caption, receipts and deletes, and show media with a caption
as its caption only. v1 builds were internal test builds and are superseded
by v2: there is no negotiation, capability flag or placeholder text, and v2
senders never add one. v2 clients drop a `CommunityMessage` with neither
text nor attachment (nor receipt or delete). A later extension adding kinds
v2 rejects (another attachment kind or receipt type) must accept that v2
drops them. The largest v2 message (8192-byte caption, 10 KiB thumbnail;
or 1000 receipt ids) fits the 65536-byte envelope limit.

## Required verification

Shared canonical state/message vectors must decode identically: the signed
state of `communities.json`, `communities-invalid.json`, the name rule
vectors of `communities-names.json` and the v2 content kinds of
`communities-content.json` (all in `spec/test-vectors/`). Tests cover
tampered signatures, duplicate/unsorted members, owner substitution, rollback,
same-revision different state, non-admin updates, concurrent admission at99,
removed sender/recipient and stale revision, expired/revoked/reused invitations,
private search non-disclosure, 99-recipient load, retry/duplicate handling,
atomic receive, leaving and withdrawing, live-only inbox quotas, and absence
of direct-contact side effects. For v2: every invalid kind combination is
dropped and acknowledged; receipts only from recipients and only for own
messages; READ neither sent nor shown with read receipts off; deletes by a
non-sender non-owner refused, owner deletes applied, deletes before their
target remembered; the blob uploaded once and deleted only by its sender. Existing 1:1
crypto, mailbox, request, storage, and UI suites must continue passing.
Implementation tests do not replace independent cryptographic review.

## Extension v3: built-in stickers and voice notes

### Threat model (written before implementation)

The relay and other members remain untrusted. A message must pass the existing
signed-roster, revision, sender identity and membership checks before its content
is accepted. Stickers and voice notes do not grant membership or use a 1:1 access
key as group authority. Removed members receive no future fanout. Previously
received audio keys and saved copies cannot be revoked. Voice size, duration and
waveform are bounded before decoding; audio is never played automatically.
Malformed or mixed content is dropped and acknowledged. Built-in stickers load
no remote artwork, scripts or URLs. Custom sticker uploads are outside v3.

### Wire contract and compatibility

`CommunityMessage.sticker = 10` reuses `Sticker` from the 1:1 protocol. It is
exclusive with attachment, receipt and delete. Its `text` MUST equal its nonblank
emoji fallback (at most 64 UTF-8 bytes). `pack_id` is nonblank and at most 128
UTF-8 bytes, sticker_id is positive, lang at most 35 UTF-8 bytes, custom absent.
Unknown catalog ids render through the existing emoji fallback. v1/v2 readers
ignore field 10 and display the fallback as ordinary text.

`attachment.kind = VOICE` reuses the existing AAC/M4A recording, attachment cipher
and upload/download pipeline. `content_type` MUST be `audio/mp4`, size 1..16 MiB,
duration_ms 1000..300000, waveform at most 100 bytes; width, height, thumbnail,
file_name and message text MUST be empty/zero. Non-VOICE attachments continue to
reject waveform. The upload delete token never leaves the sender. A voice pointer
retains its waveform; encryption/upload happens once, followed by one libsignal
ciphertext per current recipient. No PLAYED/SAVED receipt is introduced: only the
existing DELIVERY and optional READ apply. Playback state is local to the screen.

Older v1/v2 community readers cannot play VOICE and drop it safely; upgrading is
required for audio. This is additive protocol compatibility, not a promise that
old clients render new media. Existing text/photo/video/file/receipt/delete fields,
1:1 wire format, ratchets and mailboxes remain unchanged. Relay routes need no
change because they carry opaque ciphertext and the existing encrypted media.
Local schema v9 appends a nullable encrypted sticker column; old rows are unchanged.

### Verification gates

Test shared Swift/Kotlin vectors (including malformed mixed kinds and bounds),
local persistence/reopen/delete/retry, real encrypted receive/fanout including
100-member rosters and removed members, sticker and voice UI flows, and existing
1:1 regressions. Report environment skips and device-only recording/playback
checks separately; simulator fixture tests alone do not establish release readiness.
