import { randomInt } from 'node:crypto'
import { and, asc, count, eq, gte, lt, max, min, type SQL } from 'drizzle-orm'
import type { Database, Db } from '../db/client'
import { accounts, oneTimePreKeys, PRE_KEY_EC, PRE_KEY_KYBER } from '../db/schema'
import type { KeyUploadRequest, OneTimePreKey, SignedKey } from '../lib/validation'

/** The relay keeps at most this many one-time keys of each kind per account (§4.5). */
export const MAX_STORED_ONE_TIME_KEYS = 200

export interface KeyStatus {
  oneTimePreKeys: number
  kyberOneTimePreKeys: number
  signedPreKeyId: number
  kyberLastResortKeyId: number
}

export interface PreKeyBundle {
  registrationId: number
  identityKey: Buffer
  signedPreKey: SignedKey
  kyberPreKey: SignedKey
  oneTimePreKey: OneTimePreKey | null
}

/**
 * Inserts one-time keys, skipping key ids that are already stored or repeat
 * within the request, and stopping at the per-kind cap. Callers hold the
 * account row lock (or create the account in the same transaction), so the
 * stored ids and counts cannot change underneath.
 */
export async function insertOneTimePreKeys(
  tx: Db,
  accountId: Buffer,
  ecKeys: OneTimePreKey[],
  kyberKeys: SignedKey[],
  storedEc: Set<number>,
  storedKyber: Set<number>,
): Promise<void> {
  const rows = [
    ...fresh(ecKeys, storedEc).map(key => ({ accountId, kind: PRE_KEY_EC, keyId: key.keyId, publicKey: key.publicKey, signature: null })),
    ...fresh(kyberKeys, storedKyber).map(key => ({ accountId, kind: PRE_KEY_KYBER, keyId: key.keyId, publicKey: key.publicKey, signature: key.signature })),
  ]
  if (rows.length > 0) await tx.insert(oneTimePreKeys).values(rows)
}

function fresh<T extends { keyId: number }>(keys: T[], stored: Set<number>): T[] {
  const seen = new Set(stored)
  const result: T[] = []
  for (const key of keys) {
    if (seen.size >= MAX_STORED_ONE_TIME_KEYS) break
    if (seen.has(key.keyId)) continue
    seen.add(key.keyId)
    result.push(key)
  }
  return result
}

export class PreKeyStore {
  constructor(private readonly database: Database) {}

  /** Applies a `PUT /v1/keys` request; returns false if the account is gone. */
  async upload(accountId: Buffer, request: KeyUploadRequest): Promise<boolean> {
    return this.database.transaction(async (tx) => {
      const [account] = await tx.select({ id: accounts.id }).from(accounts)
        .where(eq(accounts.id, accountId)).for('update')
      if (!account) return false

      if (request.replaceAll) {
        await tx.delete(oneTimePreKeys).where(eq(oneTimePreKeys.accountId, accountId))
      }
      const { signedPreKey: signed, kyberLastResortKey: lastResort } = request
      if (signed || lastResort) {
        await tx.update(accounts).set({
          ...(signed && { signedPreKeyId: signed.keyId, signedPreKey: signed.publicKey, signedPreKeySignature: signed.signature }),
          ...(lastResort && { kyberLastResortKeyId: lastResort.keyId, kyberLastResortKey: lastResort.publicKey, kyberLastResortSignature: lastResort.signature }),
        }).where(eq(accounts.id, accountId))
      }
      if (request.oneTimePreKeys.length > 0 || request.kyberOneTimePreKeys.length > 0) {
        const stored = await tx.select({ kind: oneTimePreKeys.kind, keyId: oneTimePreKeys.keyId })
          .from(oneTimePreKeys).where(eq(oneTimePreKeys.accountId, accountId))
        const ids = (kind: number) => new Set(stored.filter(row => row.kind === kind).map(row => row.keyId))
        await insertOneTimePreKeys(tx, accountId, request.oneTimePreKeys, request.kyberOneTimePreKeys, ids(PRE_KEY_EC), ids(PRE_KEY_KYBER))
      }
      return true
    })
  }

  async status(accountId: Buffer): Promise<KeyStatus | null> {
    const { db } = this.database
    const [account] = await db.select({
      signedPreKeyId: accounts.signedPreKeyId,
      kyberLastResortKeyId: accounts.kyberLastResortKeyId,
    }).from(accounts).where(eq(accounts.id, accountId)).limit(1)
    if (!account) return null
    const counts = await db.select({ kind: oneTimePreKeys.kind, total: count() }).from(oneTimePreKeys)
      .where(eq(oneTimePreKeys.accountId, accountId)).groupBy(oneTimePreKeys.kind)
    const total = (kind: number) => counts.find(row => row.kind === kind)?.total ?? 0
    return {
      oneTimePreKeys: total(PRE_KEY_EC),
      kyberOneTimePreKeys: total(PRE_KEY_KYBER),
      signedPreKeyId: account.signedPreKeyId,
      kyberLastResortKeyId: account.kyberLastResortKeyId,
    }
  }

  /**
   * Builds a bundle and consumes one EC and one Kyber one-time key. Each key
   * is handed out at most once even under concurrent fetches: the claim
   * locks the row it takes (see `claim`). Without a Kyber one-time key the
   * Kyber last-resort key is returned and kept. With `oneTime` false (the
   * target's budget of one-time keys is spent) nothing is consumed: the
   * bundle has the signed and last-resort keys only, as when none are left.
   */
  async claimBundle(accountId: Buffer, oneTime = true): Promise<PreKeyBundle | null> {
    return this.database.transaction(async (tx) => {
      const [account] = await tx.select().from(accounts).where(eq(accounts.id, accountId)).limit(1)
      if (!account) return null
      const ec = oneTime ? await claim(tx, accountId, PRE_KEY_EC) : null
      const kyber = oneTime ? await claim(tx, accountId, PRE_KEY_KYBER) : null
      return {
        registrationId: account.registrationId,
        identityKey: account.identityKey,
        signedPreKey: {
          keyId: account.signedPreKeyId,
          publicKey: account.signedPreKey,
          signature: account.signedPreKeySignature,
        },
        kyberPreKey: kyber?.signature
          ? { keyId: kyber.keyId, publicKey: kyber.publicKey, signature: kyber.signature }
          : { keyId: account.kyberLastResortKeyId, publicKey: account.kyberLastResortKey, signature: account.kyberLastResortSignature },
        oneTimePreKey: ec ? { keyId: ec.keyId, publicKey: ec.publicKey } : null,
      }
    })
  }
}

/**
 * Takes one one-time key of a kind, chosen at random. Handing keys out in
 * key id order (clients number them sequentially) would let any account
 * that fetches a user's bundle twice count how many bundles others fetched
 * in between, i.e. how many sessions the user started. The claim starts at a
 * random key id between the smallest and the largest stored one and takes
 * the first unlocked key from there, wrapping around, so it reads and locks
 * a single row however many keys are stored.
 */
async function claim(tx: Db, accountId: Buffer, kind: number) {
  const scope = and(eq(oneTimePreKeys.accountId, accountId), eq(oneTimePreKeys.kind, kind))!
  const [range] = await tx.select({ low: min(oneTimePreKeys.keyId), high: max(oneTimePreKeys.keyId) })
    .from(oneTimePreKeys).where(scope)
  if (range?.low == null || range.high == null) return null
  const pivot = range.low + randomInt(range.high - range.low + 1)
  const row = await firstUnlocked(tx, and(scope, gte(oneTimePreKeys.keyId, pivot))!)
    ?? await firstUnlocked(tx, and(scope, lt(oneTimePreKeys.keyId, pivot))!)
  if (!row) return null
  await tx.delete(oneTimePreKeys).where(and(scope, eq(oneTimePreKeys.keyId, row.keyId)))
  return row
}

/** SKIP LOCKED makes a concurrent fetch take another key instead of waiting for this one. */
async function firstUnlocked(tx: Db, where: SQL) {
  const [row] = await tx.select().from(oneTimePreKeys)
    .where(where)
    .orderBy(asc(oneTimePreKeys.keyId))
    .limit(1)
    .for('update', { skipLocked: true })
  return row
}
