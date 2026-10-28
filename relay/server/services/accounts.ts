import { and, asc, eq, inArray, isNotNull, isNull, lt, lte, ne } from 'drizzle-orm'
import { isDuplicateKey, retryOnDeadlock, type Database, type Db } from '../db/client'
import { accounts, avatars, communities, envelopes, media, oneTimePreKeys, usernames } from '../db/schema'
import { constantTimeEqual, sha256 } from '../lib/crypto'
import type { RegistrationRequest } from '../lib/validation'
import { insertOneTimePreKeys } from './prekeys'

export interface Recipient {
  accessKeyHash: Buffer | null
}

/**
 * §4.6: `SHA-256(accessKey) == accessKeyHash` in constant time. The hash is
 * computed even when the recipient has no access key, so that the time taken
 * does not depend on the account.
 */
export function accessKeyMatches(recipient: Recipient, accessKey: Buffer): boolean {
  const hash = sha256(accessKey)
  return recipient.accessKeyHash !== null && constantTimeEqual(hash, recipient.accessKeyHash)
}

export interface DirectoryEntry {
  accountId: Buffer
  identityKey: Buffer
}

/** `gone`: the account was deleted while the change waited for it. */
export type UsernameChange = 'changed' | 'unchanged' | 'taken' | 'gone'

/**
 * `stale`: the account's token is no longer the one the request was
 * authenticated with. `taken`: the new token is another account's.
 */
export type TokenRotation = 'replaced' | 'unchanged' | 'stale' | 'taken' | 'gone'

/** Accounts and username hashes (PROTOCOL.md §3.3, §4.3, §4.4). */
export class AccountStore {
  constructor(private readonly database: Database) {}

  /** Creates an account; returns false if the username hash is taken. */
  async create(id: Buffer, authTokenHash: Buffer, request: RegistrationRequest, today: string, now: number): Promise<boolean> {
    try {
      await this.database.transaction(async (tx) => {
        // A hold that has run out but has not been swept yet must not block a new owner.
        await tx.delete(usernames).where(and(
          eq(usernames.usernameHash, request.usernameHash),
          isNotNull(usernames.heldUntil),
          lte(usernames.heldUntil, now),
        ))
        await tx.insert(usernames).values({ usernameHash: request.usernameHash, accountId: id, heldUntil: null })
        await tx.insert(accounts).values({
          id,
          identityKey: request.identityKey,
          registrationId: request.registrationId,
          authTokenHash,
          accessKeyHash: null,
          signedPreKeyId: request.signedPreKey.keyId,
          signedPreKey: request.signedPreKey.publicKey,
          signedPreKeySignature: request.signedPreKey.signature,
          kyberLastResortKeyId: request.kyberLastResortKey.keyId,
          kyberLastResortKey: request.kyberLastResortKey.publicKey,
          kyberLastResortSignature: request.kyberLastResortKey.signature,
          lastSeen: today,
          createdOn: today,
        })
        await insertOneTimePreKeys(tx, id, request.oneTimePreKeys, request.kyberOneTimePreKeys, new Set(), new Set())
      })
      return true
    } catch (error) {
      if (isDuplicateKey(error)) return false
      throw error
    }
  }

  async findByAuthTokenHash(authTokenHash: Buffer): Promise<Buffer | null> {
    const [row] = await this.database.db.select({ id: accounts.id }).from(accounts)
      .where(eq(accounts.authTokenHash, authTokenHash)).limit(1)
    return row?.id ?? null
  }

  async recipient(accountId: Buffer): Promise<Recipient | null> {
    const [row] = await this.database.db.select({ accessKeyHash: accounts.accessKeyHash }).from(accounts)
      .where(eq(accounts.id, accountId)).limit(1)
    return row ?? null
  }

  async recipients(accountIds: Buffer[]): Promise<Array<Recipient & { id: Buffer }>> {
    if (accountIds.length === 0) return []
    return this.database.db.select({ id: accounts.id, accessKeyHash: accounts.accessKeyHash }).from(accounts)
      .where(inArray(accounts.id, accountIds))
  }

  async identityKey(accountId: Buffer): Promise<Buffer | null> {
    const [row] = await this.database.db.select({ identityKey: accounts.identityKey }).from(accounts)
      .where(eq(accounts.id, accountId)).limit(1)
    return row?.identityKey ?? null
  }

  async currentUsernameHash(accountId: Buffer): Promise<Buffer | null> {
    const [row] = await this.database.db.select({ usernameHash: usernames.usernameHash }).from(usernames)
      .where(and(eq(usernames.accountId, accountId), isNull(usernames.heldUntil))).limit(1)
    return row?.usernameHash ?? null
  }

  /** Exact-match lookup of a current username; held (previous) names are not findable. */
  async lookup(usernameHash: Buffer): Promise<DirectoryEntry | null> {
    const [row] = await this.database.db
      .select({ accountId: accounts.id, identityKey: accounts.identityKey })
      .from(usernames)
      .innerJoin(accounts, eq(accounts.id, usernames.accountId))
      .where(and(eq(usernames.usernameHash, usernameHash), isNull(usernames.heldUntil)))
      .limit(1)
    return row ?? null
  }

  /**
   * Switches the current username. The previous hash stays reserved for this
   * account until `now + holdMs`, and the account may take back any hash it
   * still holds (§3.3).
   *
   * The account row is the per-account lock: concurrent changes of one
   * account, and a deletion racing with a change, run one after the other,
   * and a change that waited for a deletion finds the account gone instead
   * of leaving a name behind that nobody could ever take again.
   *
   * A change also deletes the account's avatar record, whose key the old
   * username gave, and keeps its highest version (§4.12); a hash that is
   * taken, or the current one, changes nothing.
   */
  async changeUsername(accountId: Buffer, usernameHash: Buffer, now: number, holdMs: number): Promise<UsernameChange> {
    try {
      return await this.database.transaction(async (tx) => {
        if (!await lockAccount(tx, accountId)) return 'gone'
        const current = await tx.select({ usernameHash: usernames.usernameHash }).from(usernames)
          .where(and(eq(usernames.accountId, accountId), isNull(usernames.heldUntil)))
        if (current.length === 1 && current[0]!.usernameHash.equals(usernameHash)) return 'unchanged'

        const [existing] = await tx.select().from(usernames)
          .where(eq(usernames.usernameHash, usernameHash))
          .for('update')
        if (existing && existing.accountId.equals(accountId)) {
          await tx.update(usernames).set({ heldUntil: null }).where(eq(usernames.usernameHash, usernameHash))
        } else if (existing && existing.heldUntil !== null && existing.heldUntil <= now) {
          await tx.update(usernames).set({ accountId, heldUntil: null }).where(eq(usernames.usernameHash, usernameHash))
        } else if (existing) {
          return 'taken'
        } else {
          await tx.insert(usernames).values({ usernameHash, accountId, heldUntil: null })
        }

        await tx.update(avatars).set({ record: null }).where(eq(avatars.accountId, accountId))

        // Every other current name of the account (normally exactly one) is now a previous one.
        const previous = and(eq(usernames.accountId, accountId), isNull(usernames.heldUntil), ne(usernames.usernameHash, usernameHash))
        await (holdMs === 0
          ? tx.delete(usernames).where(previous)
          : tx.update(usernames).set({ heldUntil: now + holdMs }).where(previous))
        return 'changed'
      })
    } catch (error) {
      // Another account inserted the same hash concurrently.
      if (isDuplicateKey(error)) return 'taken'
      throw error
    }
  }

  /**
   * Replaces the auth token hash `current` with `next` (§4.3). Under the
   * account row lock, only the token that authenticated the request can be
   * replaced: of two rotations started with the same token (two phones
   * restoring one backup), one wins and the other finds it `stale`. A
   * rotation to the token the account already has is `unchanged`, so a
   * repeated request succeeds without changing anything.
   */
  async rotateAuthTokenHash(accountId: Buffer, current: Buffer, next: Buffer): Promise<TokenRotation> {
    try {
      return await this.database.transaction(async (tx) => {
        const [row] = await tx.select({ authTokenHash: accounts.authTokenHash }).from(accounts)
          .where(eq(accounts.id, accountId)).for('update')
        if (!row) return 'gone'
        if (row.authTokenHash.equals(next)) return 'unchanged'
        if (!row.authTokenHash.equals(current)) return 'stale'
        await tx.update(accounts).set({ authTokenHash: next }).where(eq(accounts.id, accountId))
        return 'replaced'
      })
    } catch (error) {
      // `next` is another account's token.
      if (isDuplicateKey(error)) return 'taken'
      throw error
    }
  }

  async setAccessKeyHash(accountId: Buffer, accessKeyHash: Buffer | null): Promise<void> {
    await this.database.db.update(accounts).set({ accessKeyHash }).where(eq(accounts.id, accountId))
  }

  /** Records the coarse last-seen date; a no-op if it is already today. */
  async touch(accountId: Buffer, today: string): Promise<void> {
    await this.database.db.update(accounts).set({ lastSeen: today })
      .where(and(eq(accounts.id, accountId), lt(accounts.lastSeen, today)))
  }

  async inactiveSince(date: string, limit: number): Promise<Buffer[]> {
    const rows = await this.database.db.select({ id: accounts.id }).from(accounts)
      .where(lt(accounts.lastSeen, date)).limit(limit)
    return rows.map(row => row.id)
  }

  /**
   * Deletes the account and everything stored for it (§4.3). Returns the ids
   * of the media it uploaded so the caller can remove the files after the
   * rows are gone. The account row is locked first, so that a username
   * change, key upload or media upload running at the same time either
   * finishes before (and is deleted here) or finds the account gone.
   *
   * The foreign keys cascade to the account's community rows, including the
   * communities it owns. Community transactions lock a community row before
   * account rows, so the owned communities are locked first here too; a
   * remaining deadlock (on rows the cascade reaches) is retried.
   */
  async delete(accountId: Buffer): Promise<Buffer[]> {
    return retryOnDeadlock(() => this.database.transaction(async (tx) => {
      await tx.select({ id: communities.id }).from(communities)
        .where(eq(communities.owner, accountId)).orderBy(asc(communities.id)).for('update')
      if (!await lockAccount(tx, accountId)) return []
      const uploads = await tx.select({ id: media.id }).from(media).where(eq(media.uploader, accountId))
      await tx.delete(media).where(eq(media.uploader, accountId))
      await tx.delete(envelopes).where(eq(envelopes.recipient, accountId))
      await tx.delete(oneTimePreKeys).where(eq(oneTimePreKeys.accountId, accountId))
      await tx.delete(avatars).where(eq(avatars.accountId, accountId))
      await tx.delete(usernames).where(eq(usernames.accountId, accountId))
      await tx.delete(accounts).where(eq(accounts.id, accountId))
      return uploads.map(row => row.id)
    }))
  }
}

/**
 * Locks an account row for the rest of the transaction; false if the account
 * does not exist (any more). Every transaction that adds rows belonging to an
 * account takes this lock first.
 */
export async function lockAccount(tx: Db, accountId: Buffer): Promise<boolean> {
  const [row] = await tx.select({ id: accounts.id }).from(accounts).where(eq(accounts.id, accountId)).for('update')
  return row !== undefined
}
