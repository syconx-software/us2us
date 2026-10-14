# Communities extension v1 — threat model and wire contract

Status: implementation contract, 2026-10-14. This is an additive extension
to BRP v1, not a replacement for its one-to-one protocol. First release:
text conversations, one owner/administrator, at most 100 members including
the owner. Public discovery and private invitations both require approval.

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
state bring it back.

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
by 100 recipients. New members receive no earlier messages.

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
  membership is admitted only with a pending request (otherwise 403), except
  that an account already in the previous roster that left or was deleted may
  stay listed without being re-admitted. Accounts dropped from the roster
  lose authorization atomically, with their queued mail and redeemed
  invitations. 409 `stale_version` for a stale revision, 409
  `community_full` for more than 100 members.
* `POST /v1/communities/{id}/requests` `{}` -> 202 (public only). Idempotent
  pending request; an existing member gets success. Private requires redeem.
* `GET /v1/communities/{id}/requests` -> `{requests:[{accountId,identityKey,
  createdAt}]}`, owner only, at most 100 pending requests.
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
  idempotent retry; different bytes or revision conflict. Text and all inner
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
ciphertext. Per community, sender and recipient: 500 live envelopes, so one
member cannot fill another's inbox for everybody else. Either quota is 429
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

## Required verification

Shared canonical state/message vectors must decode identically: the signed
state of `communities.json`, `communities-invalid.json` and the name rule
vectors of `communities-names.json` (all in `spec/test-vectors/`). Tests cover
tampered signatures, duplicate/unsorted members, owner substitution, rollback,
same-revision different state, non-admin updates, concurrent admission at99,
removed sender/recipient and stale revision, expired/revoked/reused invitations,
private search non-disclosure, 99-recipient load, retry/duplicate handling,
atomic receive, leaving and withdrawing, live-only inbox quotas, and absence
of direct-contact side effects. Existing 1:1
crypto, mailbox, request, storage, and UI suites must continue passing.
Implementation tests do not replace independent cryptographic review.
