import { describeError } from '../lib/errors'
import type { Logger } from '../lib/logger'
import type { Mailbox } from '../services/mailbox'

/** An envelope accepted on the online fast path and not yet acknowledged or stored. */
export interface PendingEnvelope {
  recipient: Buffer
  recipientKey: string
  id: Buffer
  idKey: string
  serverTimestamp: number
  body: Buffer
  acked: boolean
  ackTimer?: NodeJS.Timeout
  /**
   * Set when a live session first takes the envelope. Past it, the envelope
   * goes to the mailbox even if it was never sent or a reconnect moved it
   * to a new session in the meantime.
   */
  holdUntil?: number
}

export interface HeldUsage {
  count: number
  bytes: number
}

const BATCH_SIZE = 100
const MAX_RETRY_DELAY_MS = 30_000
const FLUSH_POLL_MS = 10

/**
 * Writes envelopes to the mailbox: those for offline recipients, and
 * fast-path envelopes whose live delivery did not complete (no ack in time,
 * socket closed, or shutdown; PROTOCOL.md §1.1).
 *
 * A sealed send is answered as soon as its envelope is handed over here,
 * exactly like a send to an online recipient, so the answer's timing does
 * not tell whether the recipient is connected.
 *
 * It never drops an entry. Failed writes are retried with backoff, and an
 * acknowledgement that races with a write is honoured either by skipping the
 * write (still queued) or by deleting the row right after it (being
 * written). Clients de-duplicate by id, so the remaining crash window can
 * only cause a duplicate, never a loss of anything already stored.
 */
export class EnvelopePersister {
  private queue: PendingEnvelope[] = []
  /** Every entry this persister owns, queued or being written, by id. */
  private readonly owned = new Map<string, PendingEnvelope>()
  /** What `owned` holds per recipient, and in total. */
  private readonly perRecipient = new Map<string, HeldUsage>()
  private totalBytes = 0
  private writing: Promise<void> | null = null
  private writingRecipients = new Set<string>()
  private scheduled = false
  private retryTimer: NodeJS.Timeout | null = null
  private retryDelayMs = 0

  constructor(
    private readonly mailbox: Mailbox,
    private readonly logger: Logger,
  ) {}

  enqueue(entries: PendingEnvelope[]): void {
    for (const entry of entries) {
      if (entry.acked || this.owned.has(entry.idKey)) continue
      clearTimeout(entry.ackTimer)
      this.own(entry)
      this.queue.push(entry)
    }
    this.schedule()
  }

  /**
   * Records an ack for an envelope this persister owns. Returns false if it
   * does not own it, in which case the caller deletes it from the mailbox.
   */
  acknowledge(recipientKey: string, idKey: string): boolean {
    const entry = this.owned.get(idKey)
    if (!entry || entry.recipientKey !== recipientKey) return false
    entry.acked = true
    return true
  }

  /**
   * Hands back the queued (not yet written) envelopes of a recipient who just
   * connected, so they are delivered live instead of stored. If a write that
   * includes this recipient is running, waits for it, so that afterwards
   * every envelope is either returned here or visible in the mailbox. While
   * it waits, the envelopes it took still count as held here, so the
   * recipient's mailbox limits see them.
   */
  async reclaim(recipientKey: string): Promise<PendingEnvelope[]> {
    const taken: PendingEnvelope[] = []
    try {
      for (;;) {
        const mine = this.queue.filter(entry => entry.recipientKey === recipientKey)
        if (mine.length > 0) {
          this.queue = this.queue.filter(entry => entry.recipientKey !== recipientKey)
          taken.push(...mine)
        }
        if (!this.writing || !this.writingRecipients.has(recipientKey)) return taken
        await this.writing.catch(() => {})
      }
    } finally {
      for (const entry of taken) this.disown(entry)
    }
  }

  /**
   * Waits until everything held for a recipient is in the mailbox (or the
   * deadline passes), so that a mailbox fetch sees every envelope that was
   * accepted before it.
   */
  async flushed(recipientKey: string, deadline: number): Promise<void> {
    while (this.perRecipient.has(recipientKey)) {
      const left = deadline - Date.now()
      if (left <= 0) return
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, Math.min(left, FLUSH_POLL_MS))
        const wake = () => {
          clearTimeout(timer)
          resolve()
        }
        this.writing?.then(wake, wake)
      })
    }
  }

  /** Forgets everything for a deleted account. */
  drop(recipientKey: string): void {
    for (const entry of this.owned.values()) {
      if (entry.recipientKey === recipientKey) entry.acked = true
    }
  }

  get size(): number {
    return this.owned.size
  }

  /** What is held for a recipient and not written yet. */
  held(recipientKey: string): HeldUsage {
    return this.perRecipient.get(recipientKey) ?? { count: 0, bytes: 0 }
  }

  /** Bytes held for all recipients and not written yet. */
  get heldBytes(): number {
    return this.totalBytes
  }

  /** Writes everything that is queued; used on shutdown. Resolves true when nothing is left. */
  async drain(deadline: number): Promise<boolean> {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    while (this.owned.size > 0 && Date.now() < deadline) {
      if (this.writing) await this.writing.catch(() => {})
      else await this.writeBatch().catch(() => new Promise(resolve => setTimeout(resolve, 200)))
    }
    return this.owned.size === 0
  }

  private own(entry: PendingEnvelope): void {
    this.owned.set(entry.idKey, entry)
    this.totalBytes += entry.body.length
    const usage = this.perRecipient.get(entry.recipientKey)
    if (usage) {
      usage.count++
      usage.bytes += entry.body.length
    } else {
      this.perRecipient.set(entry.recipientKey, { count: 1, bytes: entry.body.length })
    }
  }

  private disown(entry: PendingEnvelope): void {
    if (!this.owned.delete(entry.idKey)) return
    this.totalBytes -= entry.body.length
    const usage = this.perRecipient.get(entry.recipientKey)!
    if (--usage.count === 0) this.perRecipient.delete(entry.recipientKey)
    else usage.bytes -= entry.body.length
  }

  private schedule(): void {
    if (this.scheduled || this.writing || this.retryTimer) return
    this.scheduled = true
    // Batch everything enqueued in the same tick (e.g. a whole closed session).
    setImmediate(() => {
      this.scheduled = false
      void this.flush()
    })
  }

  private async flush(): Promise<void> {
    while (this.queue.length > 0 && !this.writing) {
      try {
        await this.writeBatch()
        this.retryDelayMs = 0
      } catch (error) {
        this.retryDelayMs = Math.min(MAX_RETRY_DELAY_MS, Math.max(1000, this.retryDelayMs * 2))
        this.logger.error('storing undelivered envelopes failed, will retry', {
          pending: this.owned.size,
          retryInMs: this.retryDelayMs,
          error: describeError(error),
        })
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null
          void this.flush()
        }, this.retryDelayMs)
        this.retryTimer.unref()
        return
      }
    }
  }

  private writeBatch(): Promise<void> {
    const batch = this.queue.splice(0, BATCH_SIZE)
    const toWrite = batch.filter((entry) => {
      if (entry.acked) this.disown(entry)
      return !entry.acked
    })
    this.writingRecipients = new Set(toWrite.map(entry => entry.recipientKey))
    const work = (async () => {
      try {
        await this.mailbox.insert(toWrite.map(({ recipient, id, serverTimestamp, body }) => ({ recipient, id, serverTimestamp, body })))
      } catch (error) {
        this.queue.unshift(...toWrite)
        throw error
      }
      const undo = new Map<string, { recipient: Buffer, ids: Buffer[] }>()
      for (const entry of toWrite) {
        this.disown(entry)
        // Acknowledged while it was being written: the row must go again.
        if (entry.acked) {
          const group = undo.get(entry.recipientKey) ?? { recipient: entry.recipient, ids: [] }
          group.ids.push(entry.id)
          undo.set(entry.recipientKey, group)
        }
      }
      for (const { recipient, ids } of undo.values()) {
        await this.mailbox.delete(recipient, ids).catch((error: unknown) => {
          this.logger.warn('deleting acknowledged envelopes failed', { error: describeError(error) })
        })
      }
    })()
    this.writing = work.finally(() => {
      this.writing = null
    })
    return this.writing
  }
}
