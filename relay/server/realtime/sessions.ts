import { encodeB64u } from '../lib/encoding'
import { isEphemeralId } from '../lib/envelope-id'
import { describeError } from '../lib/errors'
import type { Logger } from '../lib/logger'
import { MAX_ACK_IDS } from '../lib/validation'
import type { Mailbox, StoredEnvelope } from '../services/mailbox'
import type { EnvelopePersister, PendingEnvelope } from './persister'

/** The parts of a WebSocket the session logic needs; adapted from a crossws peer. */
export interface SessionSocket {
  readonly isOpen: boolean
  readonly bufferedAmount: number
  send(text: string): void
  close(code: number, reason: string): void
  terminate(): void
}

export const CLOSE_GOING_AWAY = 1001
export const CLOSE_INTERNAL_ERROR = 1011
export const CLOSE_TRY_AGAIN_LATER = 1013
export const CLOSE_REPLACED = 4001
export const CLOSE_UNAUTHORIZED = 4401

/**
 * Outbound byte budget of one socket. Nothing more is written while a
 * socket holds this much unsent data, so a client that reads slowly (or not
 * at all) cannot make the relay buffer without limit: its live envelopes
 * wait in memory for their ack timeout and then go to the mailbox, and its
 * ephemeral frames are dropped.
 */
export const MAX_BUFFERED_BYTES = 1024 * 1024
/** Only something that ignored the budget could buffer this much; such a socket is dropped at once. */
const HARD_BUFFERED_BYTES = 2 * MAX_BUFFERED_BYTES
/** A socket that stays over its budget this long is dropped (close 1013, then the TCP connection). */
const STALL_TIMEOUT_MS = 30_000
const DRAIN_POLL_MS = 25
/** The largest envelope the protocol allows (§4.6); the backlog is read in slices sized for it. */
const ENVELOPE_LIMIT = 64 * 1024
/** At most this much of the backlog is in memory at once, so a 14-day mailbox costs the same as a small one. */
const SYNC_SLICE_ROWS = Math.floor(2 * 1024 * 1024 / ENVELOPE_LIMIT)

type OutboxItem =
  | { kind: 'envelope', entry: PendingEnvelope }
  | { kind: 'ephemeral', envelope: StoredEnvelope }
  | { kind: 'queue_empty' }

export class LiveSession {
  state: 'syncing' | 'live' | 'closed' = 'syncing'
  /** Sent (or, while syncing or over budget, held back) and not yet acknowledged or handed to the persister. */
  readonly pending = new Map<string, PendingEnvelope>()
  /** Frames waiting to be written, in order. */
  outbox: OutboxItem[] = []
  flushQueued = false
  drainTimer: NodeJS.Timeout | null = null
  /** When a sweep first saw the socket over its budget; null while it is within. */
  overBudgetSince: number | null = null
  /** Acknowledged ids that may be in the mailbox, deleted by one query at a time. */
  ackBacklog: Buffer[] = []
  acking = false
  /** Closed because the account was deleted: nothing it holds may be stored again. */
  discarded = false
  lastFrameAt = Date.now()

  constructor(
    readonly account: Buffer,
    readonly accountKey: string,
    readonly socket: SessionSocket,
  ) {}

  /** A getter, so that checks after an `await` are not narrowed away by the compiler. */
  get closed(): boolean {
    return this.state === 'closed'
  }

  get overBudget(): boolean {
    return this.socket.bufferedAmount > MAX_BUFFERED_BYTES
  }
}

export function messageFrame(envelope: StoredEnvelope): string {
  return JSON.stringify({
    type: 'message',
    id: encodeB64u(envelope.id),
    serverTimestamp: envelope.serverTimestamp,
    envelope: encodeB64u(envelope.body),
  })
}

const QUEUE_EMPTY_FRAME = JSON.stringify({ type: 'queue_empty' })

const byId = (a: StoredEnvelope, b: StoredEnvelope) => Buffer.compare(a.id, b.id)

/**
 * Live WebSocket sessions and the online fast path (PROTOCOL.md §1.1, §4.7).
 *
 * An envelope for a connected recipient is sent from memory and kept in its
 * session's `pending` map until the client acks it. If no ack arrives in
 * time, the socket closes, or the relay shuts down, the envelope goes to the
 * persister, which stores it in the mailbox. So an envelope is always in
 * exactly one place: a session's pending map, the persister, or the mailbox.
 * Two online users chatting cause no database writes at all.
 *
 * Writes to a socket happen after the request that caused them has been
 * answered (they are queued in the session's outbox), so the time a send
 * takes does not depend on whether its recipient is connected.
 */
export class SessionManager {
  private readonly sessions = new Map<string, LiveSession>()
  private shuttingDown = false

  constructor(private readonly deps: {
    mailbox: Mailbox
    persister: EnvelopePersister
    ackTimeoutMs: number
    idleTimeoutMs: number
    logger: Logger
  }) {}

  isOnline(accountKey: string): boolean {
    return this.sessions.has(accountKey)
  }

  /**
   * Registers a freshly authenticated socket (synchronously, so that frames
   * and deliveries arriving right away find it) and starts sending the
   * backlog. A previous session of the same account is closed with 4001 and
   * its unacknowledged envelopes move over to the new one.
   */
  open(account: Buffer, socket: SessionSocket): LiveSession {
    const accountKey = account.toString('hex')
    const session = new LiveSession(account, accountKey, socket)
    if (this.shuttingDown) {
      // An upgrade that was still authenticating when shutdown began. Its
      // sync would take envelopes back from the persister while it drains.
      session.state = 'closed'
      end(socket, CLOSE_GOING_AWAY, 'server restart')
      return session
    }
    const previous = this.sessions.get(accountKey)
    this.sessions.set(accountKey, session)
    if (previous) {
      this.retire(previous)
      for (const entry of previous.pending.values()) {
        clearTimeout(entry.ackTimer)
        session.pending.set(entry.idKey, entry)
      }
      previous.pending.clear()
      end(previous.socket, CLOSE_REPLACED, 'replaced')
    }
    void this.sync(session)
    return session
  }

  /** The socket closed: everything still unacknowledged is stored. */
  closed(session: LiveSession): void {
    if (session.closed) return
    this.retire(session)
    if (this.sessions.get(session.accountKey) === session) this.sessions.delete(session.accountKey)
    this.persistPending(session)
  }

  /**
   * Tries live delivery. Returns false if the recipient has no session (or
   * the relay is shutting down); the caller then hands the envelope to the
   * persister.
   */
  deliver(entry: PendingEnvelope): boolean {
    const session = this.sessions.get(entry.recipientKey)
    if (!session || this.shuttingDown) return false
    session.pending.set(entry.idKey, entry)
    if (session.state === 'live') this.transmit(session, entry)
    return true
  }

  /**
   * Ephemeral traffic (typing, presence, live-location ticks): sent if the
   * recipient is connected and keeping up, never stored. Callers must answer
   * identically whether or not this sent anything (§4.6), so it reports
   * nothing.
   */
  deliverEphemeral(recipientKey: string, envelope: StoredEnvelope): void {
    const session = this.sessions.get(recipientKey)
    if (!session || this.shuttingDown || session.closed) return
    session.outbox.push({ kind: 'ephemeral', envelope })
    this.scheduleFlush(session)
  }

  /**
   * Acknowledges envelopes from WebSocket or HTTP. Envelopes still in memory
   * are simply forgotten; only those that reached the mailbox cost a delete.
   */
  async acknowledge(account: Buffer, ids: Buffer[]): Promise<void> {
    await this.deps.mailbox.delete(account, this.forgetAcknowledged(account.toString('hex'), ids))
  }

  /**
   * An `ack` frame. The deletes it needs are queued on the session and run
   * one query at a time, so a client sending acks as fast as its frame limit
   * allows cannot occupy more than one database connection.
   */
  acknowledgeFrame(session: LiveSession, ids: Buffer[]): void {
    const stored = this.forgetAcknowledged(session.accountKey, ids)
    if (stored.length === 0) return
    session.ackBacklog.push(...stored)
    if (!session.acking) void this.deleteAcknowledged(session)
  }

  /**
   * Ends an account's session from the server side: after a token rotation
   * (its envelopes are kept) or an account deletion (`discard`).
   */
  disconnect(accountKey: string, code: number, reason: string, discard: boolean): void {
    const session = this.sessions.get(accountKey)
    if (!session) return
    this.retire(session)
    this.sessions.delete(accountKey)
    if (discard) {
      session.discarded = true
      for (const entry of session.pending.values()) clearTimeout(entry.ackTimer)
      session.pending.clear()
    } else {
      this.persistPending(session)
    }
    end(session.socket, code, reason)
  }

  /**
   * Drops sockets that have been silent for too long (clients ping
   * regularly) and sockets that stopped reading: over their write budget
   * for STALL_TIMEOUT_MS, or far over it.
   */
  sweepIdle(now = Date.now()): void {
    for (const session of this.sessions.values()) {
      const buffered = session.socket.bufferedAmount
      if (now - session.lastFrameAt > this.deps.idleTimeoutMs || buffered > HARD_BUFFERED_BYTES) {
        session.socket.terminate()
      } else if (buffered <= MAX_BUFFERED_BYTES) {
        session.overBudgetSince = null
      } else if (now - (session.overBudgetSince ??= now) >= STALL_TIMEOUT_MS) {
        end(session.socket, CLOSE_TRY_AGAIN_LATER, 'slow consumer')
      }
    }
  }

  /**
   * Graceful shutdown: from now on every envelope goes to the mailbox, and
   * open sessions are closed with 1001 after handing their pending
   * envelopes to the persister; the clients reconnect.
   */
  shutdown(): void {
    this.shuttingDown = true
    for (const session of [...this.sessions.values()]) {
      this.closed(session)
      end(session.socket, CLOSE_GOING_AWAY, 'server restart')
    }
  }

  private forgetAcknowledged(accountKey: string, ids: Buffer[]): Buffer[] {
    const session = this.sessions.get(accountKey)
    const stored: Buffer[] = []
    for (const id of ids) {
      if (isEphemeralId(id)) continue
      const idKey = id.toString('hex')
      const entry = session?.pending.get(idKey)
      if (entry) {
        clearTimeout(entry.ackTimer)
        session!.pending.delete(idKey)
      } else if (!this.deps.persister.acknowledge(accountKey, idKey)) {
        stored.push(id)
      }
    }
    return stored
  }

  private async deleteAcknowledged(session: LiveSession): Promise<void> {
    session.acking = true
    try {
      while (session.ackBacklog.length > 0) {
        const ids = session.ackBacklog.splice(0, MAX_ACK_IDS)
        await this.deps.mailbox.delete(session.account, ids).catch((error: unknown) => {
          this.deps.logger.error('acknowledging envelopes failed', { error: describeError(error) })
        })
      }
    } finally {
      session.acking = false
    }
  }

  /** Marks a session closed and lets go of everything it was about to write. */
  private retire(session: LiveSession): void {
    session.state = 'closed'
    session.outbox = []
    if (session.drainTimer) clearTimeout(session.drainTimer)
    session.drainTimer = null
  }

  private persistPending(session: LiveSession): void {
    if (session.pending.size === 0) return
    const entries = [...session.pending.values()]
    session.pending.clear()
    this.deps.persister.enqueue(entries)
  }

  /** Queues a pending envelope for the socket; its ack timer runs from now, sent yet or not. */
  private transmit(session: LiveSession, entry: PendingEnvelope): void {
    clearTimeout(entry.ackTimer)
    entry.ackTimer = setTimeout(() => this.ackTimedOut(session, entry), this.deps.ackTimeoutMs)
    entry.ackTimer.unref()
    session.outbox.push({ kind: 'envelope', entry })
    this.scheduleFlush(session)
  }

  private ackTimedOut(session: LiveSession, entry: PendingEnvelope): void {
    if (session.pending.get(entry.idKey) !== entry) return
    session.pending.delete(entry.idKey)
    this.deps.persister.enqueue([entry])
  }

  private scheduleFlush(session: LiveSession): void {
    if (session.flushQueued || session.drainTimer) return
    session.flushQueued = true
    setImmediate(() => {
      session.flushQueued = false
      this.flush(session)
    })
  }

  /**
   * Writes the outbox while the socket is within its budget. Over budget,
   * ephemeral frames are dropped and the rest waits; an envelope that was
   * acknowledged or handed to the persister meanwhile is skipped. If the
   * socket is going away, its pending entries are stored by the close
   * handler.
   */
  private flush(session: LiveSession): void {
    const { socket } = session
    if (session.closed || !socket.isOpen) return
    while (session.outbox.length > 0) {
      if (session.overBudget) {
        session.outbox = session.outbox.filter(item => item.kind !== 'ephemeral')
        if (session.outbox.length === 0) return
        session.drainTimer = setTimeout(() => {
          session.drainTimer = null
          this.flush(session)
        }, DRAIN_POLL_MS)
        session.drainTimer.unref()
        return
      }
      const item = session.outbox.shift()!
      if (item.kind === 'envelope') {
        if (session.pending.get(item.entry.idKey) === item.entry) socket.send(messageFrame(item.entry))
      } else {
        socket.send(item.kind === 'ephemeral' ? messageFrame(item.envelope) : QUEUE_EMPTY_FRAME)
      }
    }
  }

  /**
   * Envelopes a session took back from the persister but can no longer
   * deliver, because a newer connection replaced it in the meantime: they go
   * to that connection, or back to the persister.
   */
  private redistribute(entries: PendingEnvelope[]): void {
    const undelivered = entries.filter(entry => !this.deliver(entry))
    if (undelivered.length > 0) this.deps.persister.enqueue(undelivered)
  }

  /**
   * Backlog (oldest first), then envelopes held in memory, then
   * `queue_empty`. The backlog is read in small slices and written one
   * frame at a time within the socket's budget; a client that does not read
   * it for STALL_TIMEOUT_MS is closed with 1013.
   */
  private async sync(session: LiveSession): Promise<void> {
    const { mailbox, persister, logger } = this.deps
    try {
      const reclaimed = await persister.reclaim(session.accountKey)
      if (session.closed) {
        if (!session.discarded) this.redistribute(reclaimed)
        return
      }
      for (const entry of reclaimed) session.pending.set(entry.idKey, entry)
      let after: Buffer | undefined
      for (;;) {
        const slice = await mailbox.fetch(session.account, SYNC_SLICE_ROWS, after)
        for (const envelope of slice) {
          if (session.closed) return
          if (session.overBudget && !await this.drained(session)) {
            if (!session.closed) end(session.socket, CLOSE_TRY_AGAIN_LATER, 'slow consumer')
            return
          }
          session.socket.send(messageFrame(envelope))
        }
        if (slice.length < SYNC_SLICE_ROWS) break
        after = slice[slice.length - 1]!.id
      }
      if (session.closed) return
      session.state = 'live'
      for (const entry of [...session.pending.values()].sort(byId)) this.transmit(session, entry)
      session.outbox.push({ kind: 'queue_empty' })
      this.scheduleFlush(session)
    } catch (error) {
      logger.error('sending the backlog failed', { error: describeError(error) })
      end(session.socket, CLOSE_INTERNAL_ERROR, 'internal error')
    }
  }

  private async drained(session: LiveSession): Promise<boolean> {
    const deadline = Date.now() + STALL_TIMEOUT_MS
    while (session.overBudget) {
      if (session.closed || Date.now() > deadline) return false
      await new Promise(resolve => setTimeout(resolve, DRAIN_POLL_MS))
    }
    return !session.closed
  }
}

/**
 * Closes a socket with a close code. A socket holding more than its budget
 * is not reading, so it would never see the close frame queued behind that
 * data, and ws would keep the buffer for its 30-second close timeout:
 * such a socket is also dropped at once.
 */
function end(socket: SessionSocket, code: number, reason: string): void {
  socket.close(code, reason)
  if (socket.bufferedAmount > MAX_BUFFERED_BYTES) socket.terminate()
}
