import { and, isNotNull, lt } from 'drizzle-orm'
import type { Database } from '../db/client'
import { usedPowTokens, usernames } from '../db/schema'
import { utcDate } from '../lib/encoding'
import type { Mailbox } from './mailbox'
import type { MediaStore } from './media'

const BATCH = 5000
const MEDIA_BATCH = 500
const ACCOUNT_BATCH = 100

export interface RetentionResult {
  envelopes: number
  media: number
  powTokens: number
  usernameHolds: number
  accounts: number
}

/**
 * Deletes what has outlived PROTOCOL.md §4.10. Every step is an indexed,
 * LIMITed delete repeated until a batch comes back short, so a run is cheap,
 * never holds long locks, and is idempotent: a missed or interrupted run is
 * simply caught up by the next one.
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

  async run(now = Date.now()): Promise<RetentionResult> {
    const { database: { db }, mailbox, media } = this.deps
    const result: RetentionResult = { envelopes: 0, media: 0, powTokens: 0, usernameHolds: 0, accounts: 0 }

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
