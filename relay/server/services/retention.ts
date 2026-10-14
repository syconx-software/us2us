import { randomInt } from 'node:crypto'
import { and, isNotNull, lt } from 'drizzle-orm'
import type { Database } from '../db/client'
import { usedPowTokens, usernames } from '../db/schema'
import { utcDate } from '../lib/encoding'
import type { Mailbox } from './mailbox'
import type { MediaStore } from './media'

const BATCH = 5000
const MEDIA_BATCH = 500
const ACCOUNT_BATCH = 100
const MEDIA_SUBDIRS = 256
/** Far longer than any upload stalls, so a file is never taken for an orphan while its upload still runs. */
const ORPHAN_MIN_AGE_MS = 3_600_000

export interface RetentionResult {
  envelopes: number
  media: number
  powTokens: number
  usernameHolds: number
  accounts: number
  orphans: number
}

/**
 * Deletes what has outlived PROTOCOL.md §4.10. Every step is an indexed,
 * LIMITed delete repeated until a batch comes back short, so a run is cheap,
 * never holds long locks, and is idempotent: a missed or interrupted run is
 * simply caught up by the next one. Media files without a row are looked
 * for in one subdirectory per run.
 */
export class RetentionSweeper {
  constructor(private readonly deps: {
    database: Database
    mailbox: Mailbox
    media: MediaStore
    envelopeTtlMs: number
    inactiveAccountDays: number
    /** Deletes an account with everything that belongs to it (shared with DELETE /v1/accounts/me). */
    deleteAccount: (accountId: Buffer) => Promise<void>
    inactiveAccounts: (beforeDate: string, limit: number) => Promise<Buffer[]>
  }) {}

  /** The media subdirectory the next run checks for orphaned files; a random start, so that frequent restarts still reach all of them. */
  private orphanSubdir = randomInt(MEDIA_SUBDIRS)

  async run(now = Date.now()): Promise<RetentionResult> {
    const { database: { db }, mailbox, media } = this.deps
    const result: RetentionResult = { envelopes: 0, media: 0, powTokens: 0, usernameHolds: 0, accounts: 0, orphans: 0 }

    result.envelopes = await repeat(BATCH, () => mailbox.deleteArrivedBefore(now - this.deps.envelopeTtlMs, BATCH))
    result.media = await repeat(MEDIA_BATCH, () => media.removeExpired(now, MEDIA_BATCH))
    result.powTokens = await repeat(BATCH, async () => {
      const [header] = await db.delete(usedPowTokens).where(lt(usedPowTokens.expiresAt, now)).limit(BATCH)
      return header.affectedRows
    })
    result.usernameHolds = await repeat(BATCH, async () => {
      const [header] = await db.delete(usernames)
        .where(and(isNotNull(usernames.heldUntil), lt(usernames.heldUntil, now)))
        .limit(BATCH)
      return header.affectedRows
    })

    const cutoff = utcDate(now - this.deps.inactiveAccountDays * 86_400_000)
    result.accounts = await repeat(ACCOUNT_BATCH, async () => {
      const ids = await this.deps.inactiveAccounts(cutoff, ACCOUNT_BATCH)
      for (const id of ids) await this.deps.deleteAccount(id)
      return ids.length
    })

    // Files whose row is gone, left by a crash or a failed delete. One
    // subdirectory per run keeps this cheap (a full pass takes 256 runs, about
    // 21 hours at the default interval); last, so that a failure here holds up
    // nothing else, and the next run moves on to the next subdirectory.
    const subdir = this.orphanSubdir
    this.orphanSubdir = (subdir + 1) % MEDIA_SUBDIRS
    result.orphans = await media.removeOrphans(subdir, now - ORPHAN_MIN_AGE_MS)
    return result
  }
}

async function repeat(batchSize: number, step: () => Promise<number>): Promise<number> {
  let total = 0
  for (;;) {
    const deleted = await step()
    total += deleted
    if (deleted < batchSize) return total
  }
}
