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
}

export interface MailboxLimits {
  maxEnvelopes: number
  maxBytes: number
}

interface Usage {
  count: number
  bytes: number
}

/** The largest envelope the protocol allows (§4.6). */
const ENVELOPE_LIMIT = 64 * 1024
/** A page is read in slices that hold at most this much, whatever the envelopes weigh. */
const SLICE_ROWS = Math.floor(4 * 1024 * 1024 / ENVELOPE_LIMIT)

/**
 * Stored envelopes (PROTOCOL.md §4.6): ciphertext addressed to an account id.
 *
 * It also keeps, in memory, how many envelopes and bytes each recipient has
 * stored, so that one mailbox cannot fill the disk (`admits`). The relay is
 * a single process and every write goes through here, except an account
 * deletion, which calls `forgetUsage`. Only rows written since the process
 * started are counted, so after a restart a mailbox can hold up to one more
 * limit's worth until its older rows are acknowledged or expire. Deletes
 * report only a row count; their bytes are estimated from the mailbox's
 * average, and are exact again whenever a mailbox is emptied; the count
 * limit holds regardless.
 */
export class Mailbox {
  private readonly usage = new Map<string, Usage>()

  constructor(
    private readonly database: Database,
    private readonly limits: MailboxLimits,
  ) {}

  /** Whether a recipient may store one more envelope of `bytes`, on top of `pending` ones not written yet. */
  admits(recipientKey: string, bytes: number, pending: Usage = { count: 0, bytes: 0 }): boolean {
    const usage = this.usage.get(recipientKey) ?? { count: 0, bytes: 0 }
    return usage.count + pending.count < this.limits.maxEnvelopes
      && usage.bytes + pending.bytes + bytes <= this.limits.maxBytes
  }

  /**
   * Idempotent insert: a retried write whose first attempt did commit must
   * not fail on its own duplicate.
   */
  async insert(rows: MailboxRow[]): Promise<void> {
    if (rows.length === 0) return
    await this.database.db.insert(envelopes)
      .values(rows.map(row => ({ recipient: row.recipient, id: row.id, serverTimestamp: row.serverTimestamp, body: row.body })))
      .onDuplicateKeyUpdate({ set: { id: sql`${envelopes.id}` } })
    for (const row of rows) this.added(row.recipient.toString('hex'), row.body.length)
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
    const [result] = await this.database.db.delete(envelopes)
      .where(and(eq(envelopes.recipient, recipient), inArray(envelopes.id, ids)))
    this.removed(recipient.toString('hex'), result.affectedRows)
  }

  /** Deletes up to `limit` envelopes that arrived before `cutoff`; returns how many. */
  async deleteArrivedBefore(cutoff: number, limit: number): Promise<number> {
    const expired = await this.database.transaction(async (tx) => {
      const rows = await tx.select({ recipient: envelopes.recipient, id: envelopes.id }).from(envelopes)
        .where(lt(envelopes.serverTimestamp, cutoff))
        .orderBy(asc(envelopes.serverTimestamp))
        .limit(limit)
        .for('update')
      if (rows.length > 0) {
        await tx.delete(envelopes).where(sql`(${envelopes.recipient}, ${envelopes.id}) IN (${sql.join(rows.map(row => sql`(${row.recipient}, ${row.id})`), sql`, `)})`)
      }
      return rows
    })
    const perRecipient = new Map<string, number>()
    for (const { recipient } of expired) {
      const key = recipient.toString('hex')
      perRecipient.set(key, (perRecipient.get(key) ?? 0) + 1)
    }
    for (const [key, count] of perRecipient) this.removed(key, count)
    return expired.length
  }

  /** The account was deleted together with its mailbox. */
  forgetUsage(recipientKey: string): void {
    this.usage.delete(recipientKey)
  }

  private added(recipientKey: string, bytes: number): void {
    const usage = this.usage.get(recipientKey)
    if (usage) {
      usage.count++
      usage.bytes += bytes
    } else {
      this.usage.set(recipientKey, { count: 1, bytes })
    }
  }

  private removed(recipientKey: string, count: number): void {
    const usage = this.usage.get(recipientKey)
    if (!usage || count === 0) return
    if (count >= usage.count) {
      this.usage.delete(recipientKey)
      return
    }
    usage.bytes -= Math.round(usage.bytes * count / usage.count)
    usage.count -= count
  }
}
