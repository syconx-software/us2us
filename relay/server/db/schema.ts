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
 * Everything the relay stores (PROTOCOL.md §4.10, ARCHITECTURE.md): public
 * keys, ciphertext, hashes of secrets, and timestamps. No usernames, no
 * contacts, no content, no device or push tokens. All times are UTC
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
