import { and, asc, eq, gt, inArray, lt, sql } from 'drizzle-orm'
import type { Database } from '../db/client'
import { envelopes } from '../db/schema'

export interface StoredEnvelope {
  id: Buffer
  serverTimestamp: number
  body: Buffer
}

export interface MailboxRow extends StoredEnvelope {
  recipient: Buffer
  /** Authorized by proof-of-work, not by the access key (§4.6). */
  viaPow?: boolean
}

export interface MailboxLimits {
  maxEnvelopes: number
  maxBytes: number
  /** The share proof-of-work envelopes may take (at most half of the above); no share limit when left out. */
  powMaxEnvelopes?: number
  powMaxBytes?: number
}

/** What a recipient holds; `pow*` is the part that came with proof-of-work. */
export interface Usage {
  count: number
  bytes: number
  powCount?: number
  powBytes?: number
}

interface StoredUsage {
  count: number
  bytes: number
  powCount: number
  powBytes: number
}

/** The largest envelope the protocol allows (§4.6). */
const ENVELOPE_LIMIT = 64 * 1024
/** A page is read in slices that hold at most this much, whatever the envelopes weigh. */
const SLICE_ROWS = Math.floor(4 * 1024 * 1024 / ENVELOPE_LIMIT)

/**
 * Stored envelopes (PROTOCOL.md §4.6): ciphertext addressed to an account id.
 *
 * It also keeps, in memory, how many envelopes and bytes each recipient has
 * stored, so that one mailbox cannot fill the disk (`admits`). Every mailbox
 * is counted in the database once, at start-up (`load`), so rows from before
 * a restart count too; nothing is admitted before that, and later sends
 * never touch the database for it, whoever the recipient is. From then on
 * every write goes through here (the relay is a single process), except an
 * account deletion, which calls `forgetUsage`. The count and the writes take
 * turns: a write waits while the count runs, and the count waits for the
 * writes already running, so each write is either in the count or applied
 * to it. Deletes report only a row count; their bytes are estimated from the
 * mailbox's average, and are exact again whenever a mailbox is emptied; the
 * count limit holds regardless.
 *
 * Envelopes authorized by proof-of-work (first contacts, `viaPow`) may take
 * only `powMaxEnvelopes` / `powMaxBytes` of a mailbox, so that strangers
 * cannot fill it and block the recipient's contacts. They are counted
 * exactly: a delete in a mailbox that holds any reads which of the deleted
 * rows were such envelopes first (a primary-key lookup), so the common case,
 * a mailbox without message requests, costs nothing extra.
 */
export class Mailbox {
  private readonly usage = new Map<string, StoredUsage>()
  private counted = false
  /** The count, while it runs; writes wait for it. */
  private counting: Promise<void> | null = null
  /** Writes running; the count waits until there are none. */
  private writes = 0
  private writesDone: (() => void) | null = null
  /** Accounts deleted while the count runs, which it may still have seen. */
  private readonly forgotten = new Set<string>()

  constructor(
    private readonly database: Database,
    private readonly limits: MailboxLimits,
  ) {}

  /** Whether every mailbox has been counted, so that `admits` can answer. */
  get loaded(): boolean {
    return this.counted
  }

  /**
   * Counts what every mailbox holds, unless that is done already; joins a
   * count that is running, and a failed one is tried again on the next call.
   * One read of the whole table, at start-up.
   */
  load(): Promise<void> {
    if (this.counted) return Promise.resolve()
    this.counting ??= this.count().finally(() => {
      this.counting = null
    })
    return this.counting
  }

  /**
   * Whether a recipient may store one more envelope of `bytes`, on top of
   * `pending` ones not written yet; with `viaPow` it must also fit the
   * proof-of-work share. Refuses until `load` has finished.
   */
  admits(recipientKey: string, bytes: number, pending: Usage = { count: 0, bytes: 0 }, viaPow = false): boolean {
    if (!this.counted) return false
    const usage = this.usage.get(recipientKey) ?? { count: 0, bytes: 0, powCount: 0, powBytes: 0 }
    if (usage.count + pending.count >= this.limits.maxEnvelopes || usage.bytes + pending.bytes + bytes > this.limits.maxBytes) return false
    if (!viaPow) return true
    return usage.powCount + (pending.powCount ?? 0) < (this.limits.powMaxEnvelopes ?? Number.POSITIVE_INFINITY)
      && usage.powBytes + (pending.powBytes ?? 0) + bytes <= (this.limits.powMaxBytes ?? Number.POSITIVE_INFINITY)
  }

  /** What a recipient's mailbox holds, as counted (for tests and diagnostics). */
  usageOf(recipientKey: string): StoredUsage {
    return { ...(this.usage.get(recipientKey) ?? { count: 0, bytes: 0, powCount: 0, powBytes: 0 }) }
  }

  /**
   * Idempotent insert: a retried write whose first attempt did commit must
   * not fail on its own duplicate.
   */
  async insert(rows: MailboxRow[]): Promise<void> {
    if (rows.length === 0) return
    await this.write(async () => {
      await this.database.db.insert(envelopes)
        .values(rows.map(row => ({ recipient: row.recipient, id: row.id, serverTimestamp: row.serverTimestamp, body: row.body, viaPow: row.viaPow ? 1 : 0 })))
        .onDuplicateKeyUpdate({ set: { id: sql`${envelopes.id}` } })
      for (const row of rows) this.added(row.recipient.toString('hex'), row.body.length, row.viaPow === true)
    })
  }

  /** Oldest first; `after` continues from the last id of the previous page. */
  async fetch(recipient: Buffer, limit: number, after?: Buffer): Promise<StoredEnvelope[]> {
    return this.database.db
      .select({ id: envelopes.id, serverTimestamp: envelopes.serverTimestamp, body: envelopes.body })
      .from(envelopes)
      .where(after ? and(eq(envelopes.recipient, recipient), gt(envelopes.id, after)) : eq(envelopes.recipient, recipient))
      .orderBy(asc(envelopes.id))
      .limit(limit)
  }

  /**
   * Oldest first: at most `maxRows` envelopes whose bodies add up to at most
   * `maxBytes` (at least one envelope, though); `more` tells whether
   * anything is left. Read in fixed slices, so memory stays bounded by the
   * byte limit plus one slice.
   */
  async page(recipient: Buffer, maxRows: number, maxBytes: number): Promise<{ envelopes: StoredEnvelope[], more: boolean }> {
    const envelopes: StoredEnvelope[] = []
    let bytes = 0
    let after: Buffer | undefined
    for (;;) {
      const want = Math.min(SLICE_ROWS, maxRows - envelopes.length)
      const slice = await this.fetch(recipient, want + 1, after)
      for (const envelope of slice.slice(0, want)) {
        if (envelopes.length > 0 && bytes + envelope.body.length > maxBytes) return { envelopes, more: true }
        envelopes.push(envelope)
        bytes += envelope.body.length
      }
      if (slice.length <= want) return { envelopes, more: false }
      if (envelopes.length === maxRows) return { envelopes, more: true }
      after = envelopes[envelopes.length - 1]!.id
    }
  }

  async delete(recipient: Buffer, ids: Buffer[]): Promise<void> {
    if (ids.length === 0) return
    const recipientKey = recipient.toString('hex')
    await this.write(async () => {
      const scope = and(eq(envelopes.recipient, recipient), inArray(envelopes.id, ids))
      if ((this.usage.get(recipientKey)?.powCount ?? 0) === 0) {
        const [result] = await this.database.db.delete(envelopes).where(scope)
        this.removed(recipientKey, result.affectedRows)
        return
      }
      // Only a mailbox that holds proof-of-work envelopes needs to know which ones go; the
      // rows are locked, so a concurrent delete of the same ids cannot count them twice.
      const { deleted, pow } = await this.database.transaction(async (tx) => {
        const pow = await tx.select({ bytes: sql<number>`length(${envelopes.body})`.mapWith(Number) }).from(envelopes)
          .where(and(scope, eq(envelopes.viaPow, 1))).for('update')
        const [result] = await tx.delete(envelopes).where(scope)
        return { deleted: result.affectedRows, pow }
      })
      this.removed(recipientKey, deleted, pow.length, pow.reduce((sum, row) => sum + row.bytes, 0))
    })
  }

  /** Deletes up to `limit` envelopes that arrived before `cutoff`; returns how many. */
  async deleteArrivedBefore(cutoff: number, limit: number): Promise<number> {
    return this.write(async () => {
      const expired = await this.database.transaction(async (tx) => {
        const rows = await tx.select({ recipient: envelopes.recipient, id: envelopes.id,
          powBytes: sql<number>`if(${envelopes.viaPow} = 1, length(${envelopes.body}), -1)`.mapWith(Number) }).from(envelopes)
          .where(lt(envelopes.serverTimestamp, cutoff))
          .orderBy(asc(envelopes.serverTimestamp))
          .limit(limit)
          .for('update')
        if (rows.length > 0) {
          await tx.delete(envelopes).where(sql`(${envelopes.recipient}, ${envelopes.id}) IN (${sql.join(rows.map(row => sql`(${row.recipient}, ${row.id})`), sql`, `)})`)
        }
        return rows
      })
      const perRecipient = new Map<string, { count: number, powCount: number, powBytes: number }>()
      for (const { recipient, powBytes } of expired) {
        const key = recipient.toString('hex')
        const entry = perRecipient.get(key) ?? { count: 0, powCount: 0, powBytes: 0 }
        entry.count++
        if (powBytes >= 0) {
          entry.powCount++
          entry.powBytes += powBytes
        }
        perRecipient.set(key, entry)
      }
      for (const [key, { count, powCount, powBytes }] of perRecipient) this.removed(key, count, powCount, powBytes)
      return expired.length
    })
  }

  /** The account was deleted together with its mailbox. */
  forgetUsage(recipientKey: string): void {
    this.usage.delete(recipientKey)
    if (this.counting) this.forgotten.add(recipientKey)
  }

  private async count(): Promise<void> {
    try {
      await this.countAll()
    } finally {
      this.forgotten.clear()
    }
  }

  private async countAll(): Promise<void> {
    while (this.writes > 0) await new Promise<void>(resolve => (this.writesDone = resolve))
    const rows = await this.database.db
      .select({
        recipient: envelopes.recipient,
        count: sql<number>`count(*)`.mapWith(Number),
        bytes: sql<number>`coalesce(sum(length(${envelopes.body})), 0)`.mapWith(Number),
        powCount: sql<number>`coalesce(sum(${envelopes.viaPow} = 1), 0)`.mapWith(Number),
        powBytes: sql<number>`coalesce(sum(if(${envelopes.viaPow} = 1, length(${envelopes.body}), 0)), 0)`.mapWith(Number),
      })
      .from(envelopes)
      .groupBy(envelopes.recipient)
    for (const row of rows) {
      const recipientKey = row.recipient.toString('hex')
      if (!this.forgotten.has(recipientKey)) {
        this.usage.set(recipientKey, { count: row.count, bytes: row.bytes, powCount: row.powCount || 0, powBytes: row.powBytes || 0 })
      }
    }
    this.counted = true
  }

  /** Runs a mailbox write, never at the same time as the count. */
  private async write<T>(run: () => Promise<T>): Promise<T> {
    while (this.counting) await this.counting.catch(() => {})
    this.writes++
    try {
      return await run()
    } finally {
      if (--this.writes === 0 && this.writesDone) {
        this.writesDone()
        this.writesDone = null
      }
    }
  }

  /** Until the count, writes are not applied: it reads the database after they are done. */
  private added(recipientKey: string, bytes: number, viaPow: boolean): void {
    if (!this.counted) return
    const usage = this.usage.get(recipientKey) ?? { count: 0, bytes: 0, powCount: 0, powBytes: 0 }
    usage.count++
    usage.bytes += bytes
    if (viaPow) {
      usage.powCount++
      usage.powBytes += bytes
    }
    this.usage.set(recipientKey, usage)
  }

  /**
   * An emptied mailbox is forgotten, which also makes its bytes exact again.
   * Proof-of-work rows are given exactly; the bytes of the others are
   * estimated from their average.
   */
  private removed(recipientKey: string, count: number, powCount = 0, powBytes = 0): void {
    const usage = this.usage.get(recipientKey)
    if (!this.counted || !usage || count === 0) return
    if (count >= usage.count) {
      this.usage.delete(recipientKey)
      return
    }
    const otherCount = usage.count - usage.powCount
    const otherBytes = usage.bytes - usage.powBytes
    const removedOther = Math.min(otherCount, count - powCount)
    const removedOtherBytes = otherCount > 0 ? Math.round(otherBytes * removedOther / otherCount) : 0
    usage.powCount = Math.max(0, usage.powCount - powCount)
    usage.powBytes = usage.powCount === 0 ? 0 : Math.max(0, usage.powBytes - powBytes)
    usage.count -= count
    usage.bytes = Math.max(usage.powBytes, usage.bytes - removedOtherBytes - powBytes)
  }
}
