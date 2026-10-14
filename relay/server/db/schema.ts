import {
  bigint,
  customType,
  date,
  index,
  int,
  mysqlTable,
  primaryKey,
  smallint,
  tinyint,
  uniqueIndex,
} from 'drizzle-orm/mysql-core'

/*
 * One-to-one storage (PROTOCOL.md §4.10): public keys, ciphertext,
 * hashes of secrets, and timestamps. No usernames, personal contacts,
 * plaintext message content, device or push tokens. The opt-in community
 * extension additionally stores public names, signed membership, requests
 * and authenticated delivery metadata (COMMUNITIES.md). All times are UTC
 * milliseconds in BIGINT columns, except the deliberately coarse last-seen
 * DATE.
 *
 * drizzle-orm 0.45 has no BLOB builders and maps binary/varbinary to
 * strings, so binary columns are custom types that keep Buffers intact.
 */

type BytesConfig = { data: Buffer, driverData: Buffer, config: { length: number }, configRequired: true }

const binary = customType<BytesConfig>({ dataType: ({ length }) => `binary(${length})` })
const varbinary = customType<BytesConfig>({ dataType: ({ length }) => `varbinary(${length})` })
const mediumblob = customType<{ data: Buffer, driverData: Buffer }>({ dataType: () => 'mediumblob' })
const blob = customType<{ data: Buffer, driverData: Buffer }>({ dataType: () => 'blob' })
/** Text pinned to utf8mb4 whatever the server's default character set: community names may hold any code point, emoji included. */
const utf8mb4 = customType<{ data: string, driverData: string, config: { length: number }, configRequired: true }>({
  dataType: ({ length }) => `varchar(${length}) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`,
})

export const accounts = mysqlTable('accounts', {
  id: binary('id', { length: 16 }).primaryKey(),
  identityKey: binary('identity_key', { length: 33 }).notNull(),
  registrationId: smallint('registration_id', { unsigned: true }).notNull(),
  authTokenHash: binary('auth_token_hash', { length: 32 }).notNull(),
  accessKeyHash: binary('access_key_hash', { length: 32 }),
  signedPreKeyId: int('signed_pre_key_id', { unsigned: true }).notNull(),
  signedPreKey: binary('signed_pre_key', { length: 33 }).notNull(),
  signedPreKeySignature: binary('signed_pre_key_signature', { length: 64 }).notNull(),
  kyberLastResortKeyId: int('kyber_last_resort_key_id', { unsigned: true }).notNull(),
  kyberLastResortKey: varbinary('kyber_last_resort_key', { length: 2048 }).notNull(),
  kyberLastResortSignature: binary('kyber_last_resort_signature', { length: 64 }).notNull(),
  lastSeen: date('last_seen', { mode: 'string' }).notNull(),
}, table => [
  uniqueIndex('accounts_auth_token_hash').on(table.authTokenHash),
  index('accounts_last_seen').on(table.lastSeen),
])

/**
 * Username hashes. One row per hash, so the primary key alone guarantees
 * that a hash belongs to at most one account. `heldUntil` is NULL for an
 * account's current username; a previous username stays reserved for the
 * same account until `heldUntil` (§3.3).
 */
export const usernames = mysqlTable('usernames', {
  usernameHash: binary('username_hash', { length: 32 }).primaryKey(),
  accountId: binary('account_id', { length: 16 }).notNull(),
  heldUntil: bigint('held_until', { mode: 'number', unsigned: true }),
}, table => [
  index('usernames_account').on(table.accountId),
  index('usernames_held_until').on(table.heldUntil),
])

export const PRE_KEY_EC = 1
export const PRE_KEY_KYBER = 2

/**
 * One-time pre-keys of both kinds. Clustered by account, so status counts,
 * replaceAll and the atomic claim in a bundle fetch touch only that
 * account's rows.
 */
export const oneTimePreKeys = mysqlTable('one_time_pre_keys', {
  accountId: binary('account_id', { length: 16 }).notNull(),
  kind: tinyint('kind', { unsigned: true }).notNull(),
  keyId: int('key_id', { unsigned: true }).notNull(),
  publicKey: varbinary('public_key', { length: 2048 }).notNull(),
  signature: binary('signature', { length: 64 }),
}, table => [
  primaryKey({ name: 'one_time_pre_keys_pk', columns: [table.accountId, table.kind, table.keyId] }),
])

/**
 * Mailbox. Ids sort by arrival (see envelope-id.ts), so fetching a mailbox
 * in order is a primary-key range scan. Envelopes are at most 64 KiB, one
 * byte more than BLOB holds, hence MEDIUMBLOB.
 */
export const envelopes = mysqlTable('envelopes', {
  recipient: binary('recipient', { length: 16 }).notNull(),
  id: binary('id', { length: 16 }).notNull(),
  serverTimestamp: bigint('server_ts', { mode: 'number', unsigned: true }).notNull(),
  body: mediumblob('body').notNull(),
}, table => [
  primaryKey({ name: 'envelopes_pk', columns: [table.recipient, table.id] }),
  index('envelopes_server_ts').on(table.serverTimestamp),
])

/** Media metadata; the encrypted blobs live on disk under MEDIA_DIR. */
export const media = mysqlTable('media', {
  id: binary('id', { length: 16 }).primaryKey(),
  uploader: binary('uploader', { length: 16 }).notNull(),
  deleteTokenHash: binary('delete_token_hash', { length: 32 }).notNull(),
  size: bigint('size', { mode: 'number', unsigned: true }).notNull(),
  expiresAt: bigint('expires_at', { mode: 'number', unsigned: true }).notNull(),
}, table => [
  index('media_uploader').on(table.uploader),
  index('media_expires_at').on(table.expiresAt),
])

/**
 * Avatar records for search (PROTOCOL.md §4.12): at most one per account, an
 * opaque, padded ciphertext of at most 48 KiB (BLOB holds 64 KiB) whose
 * version is in its own header. `highestVersion` is the largest version the
 * account has written, stored or deleted; it stays while no record is
 * stored (after a delete or a username change), so that a late retry of an
 * older write is refused. Every write locks the account row first, and the
 * row is deleted with the account.
 */
export const avatars = mysqlTable('avatars', {
  accountId: binary('account_id', { length: 16 }).primaryKey(),
  highestVersion: bigint('highest_version', { mode: 'bigint', unsigned: true }).notNull(),
  record: blob('record'),
})

/** SHA-256 of proof-of-work tokens already redeemed, kept until they expire. */
export const usedPowTokens = mysqlTable('used_pow_tokens', {
  tokenHash: binary('token_hash', { length: 32 }).primaryKey(),
  expiresAt: bigint('expires_at', { mode: 'number', unsigned: true }).notNull(),
}, table => [
  index('used_pow_tokens_expires_at').on(table.expiresAt),
])

/** Opt-in communities expose membership metadata; see docs/COMMUNITIES.md. */
export const communities = mysqlTable('communities', {
  id: binary('id', { length: 16 }).primaryKey(),
  owner: binary('owner', { length: 16 }).notNull().references(() => accounts.id, { onDelete: 'cascade' }),
  visibility: tinyint('visibility', { unsigned: true }).notNull(),
  name: utf8mb4('name', { length: 120 }).notNull(),
  searchName: utf8mb4('search_name', { length: 120 }).notNull(),
  revision: bigint('revision', { mode: 'number', unsigned: true }).notNull(),
  state: blob('state').notNull(),
  signature: binary('signature', { length: 64 }).notNull(),
}, table => [index('communities_directory').on(table.visibility, table.searchName), index('communities_owner').on(table.owner)])

export const communityMembers = mysqlTable('community_members', {
  communityId: binary('community_id', { length: 16 }).notNull().references(() => communities.id, { onDelete: 'cascade' }),
  accountId: binary('account_id', { length: 16 }).notNull().references(() => accounts.id, { onDelete: 'cascade' }),
}, table => [primaryKey({ columns: [table.communityId, table.accountId] }), index('community_members_account').on(table.accountId)])

export const communityRequests = mysqlTable('community_requests', {
  communityId: binary('community_id', { length: 16 }).notNull().references(() => communities.id, { onDelete: 'cascade' }),
  accountId: binary('account_id', { length: 16 }).notNull().references(() => accounts.id, { onDelete: 'cascade' }),
  createdAt: bigint('created_at', { mode: 'number', unsigned: true }).notNull(),
}, table => [primaryKey({ columns: [table.communityId, table.accountId] })])

export const communityInvites = mysqlTable('community_invites', {
  tokenHash: binary('token_hash', { length: 32 }).primaryKey(),
  communityId: binary('community_id', { length: 16 }).notNull().references(() => communities.id, { onDelete: 'cascade' }),
  expiresAt: bigint('expires_at', { mode: 'number', unsigned: true }).notNull(),
  redeemedBy: binary('redeemed_by', { length: 16 }),
}, table => [index('community_invites_group').on(table.communityId), index('community_invites_expiry').on(table.expiresAt)])

/**
 * Community deliveries. `size` is the stored body's length, and 0 once the
 * body is gone (acknowledged, or obsolete after a roster change); the row
 * stays as a retry tombstone until it expires. Quotas count only live rows
 * (size > 0, not expired) from the two covering indexes, without reading
 * bodies: per recipient (`community_inbox`) and per community, sender and
 * recipient (`community_sender`, which also serves the sender's foreign key).
 */
export const communityEnvelopes = mysqlTable('community_envelopes', {
  id: binary('id', { length: 16 }).primaryKey(),
  communityId: binary('community_id', { length: 16 }).notNull().references(() => communities.id, { onDelete: 'cascade' }),
  sender: binary('sender', { length: 16 }).notNull().references(() => accounts.id, { onDelete: 'cascade' }),
  recipient: binary('recipient', { length: 16 }).notNull().references(() => accounts.id, { onDelete: 'cascade' }),
  messageId: binary('message_id', { length: 16 }).notNull(),
  revision: bigint('revision', { mode: 'number', unsigned: true }).notNull(),
  serverTimestamp: bigint('server_ts', { mode: 'number', unsigned: true }).notNull(),
  digest: binary('digest', { length: 32 }).notNull(),
  size: int('size', { unsigned: true }).notNull(),
  body: mediumblob('body'),
}, table => [
  uniqueIndex('community_delivery').on(table.communityId, table.sender, table.recipient, table.messageId),
  index('community_inbox').on(table.recipient, table.serverTimestamp, table.size),
  index('community_sender').on(table.sender, table.communityId, table.recipient, table.serverTimestamp, table.size),
  index('community_expiry').on(table.serverTimestamp),
])
