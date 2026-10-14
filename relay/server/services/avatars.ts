import { eq } from 'drizzle-orm'
import type { Database } from '../db/client'
import { avatars } from '../db/schema'
import { AVATAR_HEADER_BYTES } from '../lib/avatar'
import { lockAccount } from './accounts'

export interface StoredAvatar {
  /** The version in the record's header, which is also its `ETag`. */
  version: bigint
  record: Buffer
}

/**
 * `stale`: the version is not above the account's highest version, and
 * nothing changed. `gone`: the account was deleted while the write waited
 * for it.
 */
export type AvatarWrite =
  | { outcome: 'written' }
  | { outcome: 'stale', highest: bigint }
  | { outcome: 'gone' }

/**
 * Avatar records for search (PROTOCOL.md §4.12). The relay keeps at most one
 * opaque record per account and the account's highest version: every store
 * or delete must carry a version above it, so a late retry of an older write
 * is refused instead of undoing a newer one.
 *
 * Every write locks the account row first, like the other writes that add
 * rows belonging to an account. Writes of one account therefore run one
 * after the other, also against a username change (which drops the record)
 * and an account deletion (which drops the row), and a write that waited
 * for a deletion finds the account gone instead of leaving a row behind.
 */
export class AvatarStore {
  constructor(private readonly database: Database) {}

  /** Stores `record`, whose header carries `version`, in place of the stored one. */
  store(accountId: Buffer, version: bigint, record: Buffer): Promise<AvatarWrite> {
    return this.write(accountId, version, record)
  }

  /** Deletes the stored record, if any; the version still becomes the highest. */
  remove(accountId: Buffer, version: bigint): Promise<AvatarWrite> {
    return this.write(accountId, version, null)
  }

  /**
   * The account's record, or null if it has none. Only this table is read,
   * by its primary key, so an account without a record and an account that
   * does not exist take the same path and look the same (§4.12).
   */
  async find(accountId: Buffer): Promise<StoredAvatar | null> {
    const [row] = await this.database.db.select({ record: avatars.record }).from(avatars)
      .where(eq(avatars.accountId, accountId)).limit(1)
    const record = row?.record
    // A stored record was checked when it arrived; this only guards against a damaged row.
    if (!record || record.length < AVATAR_HEADER_BYTES) return null
    return { version: record.readBigUInt64BE(AVATAR_HEADER_BYTES - 8), record }
  }

  private async write(accountId: Buffer, version: bigint, record: Buffer | null): Promise<AvatarWrite> {
    return this.database.transaction(async (tx) => {
      if (!await lockAccount(tx, accountId)) return { outcome: 'gone' } as const
      const [row] = await tx.select({ highest: avatars.highestVersion }).from(avatars)
        .where(eq(avatars.accountId, accountId))
      if (row && version <= row.highest) return { outcome: 'stale', highest: row.highest } as const
      await (row
        ? tx.update(avatars).set({ highestVersion: version, record }).where(eq(avatars.accountId, accountId))
        : tx.insert(avatars).values({ accountId, highestVersion: version, record }))
      return { outcome: 'written' } as const
    })
  }
}
