import { randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { EnvelopeIdGenerator } from '../../server/lib/envelope-id'
import { Logger } from '../../server/lib/logger'
import { EnvelopePersister, type HeldUsage, type PendingEnvelope } from '../../server/realtime/persister'
import { SessionManager, type LiveSession, type SessionSocket } from '../../server/realtime/sessions'
import type { Mailbox, MailboxLimits, MailboxRow, StoredEnvelope } from '../../server/services/mailbox'

/*
 * Envelopes accepted for a recipient count against its mailbox limits
 * wherever they are: in its live session, in the persister or stored
 * (PROTOCOL.md §4.6). These tests drive the real SessionManager and
 * EnvelopePersister with an in-memory mailbox and the send route's checks.
 */

/** An in-memory mailbox that knows exactly what it stores and applies the relay's limit rule. */
class CountingMailbox {
  readonly rows = new Map<string, MailboxRow>()
  readGate: Promise<void> | null = null
  writeGate: Promise<void> | null = null

  constructor(private readonly limits: MailboxLimits) {}

  readonly loaded = true

  async load(): Promise<void> {}

  stored(recipientKey: string): HeldUsage {
    let count = 0
    let bytes = 0
    for (const row of this.rows.values()) {
      if (row.recipient.toString('hex') !== recipientKey) continue
      count++
      bytes += row.body.length
    }
    return { count, bytes }
  }

  admits(recipientKey: string, bytes: number, pending: HeldUsage): boolean {
    const stored = this.stored(recipientKey)
    return stored.count + pending.count < this.limits.maxEnvelopes
      && stored.bytes + pending.bytes + bytes <= this.limits.maxBytes
  }

  async insert(rows: MailboxRow[]): Promise<void> {
    await this.writeGate
    for (const row of rows) this.rows.set(row.id.toString('hex'), row)
  }

  async fetch(recipient: Buffer, limit: number, after?: Buffer): Promise<StoredEnvelope[]> {
    await this.readGate
    return [...this.rows.values()]
      .filter(row => row.recipient.equals(recipient) && (!after || Buffer.compare(row.id, after) > 0))
      .sort((a, b) => Buffer.compare(a.id, b.id))
      .slice(0, limit)
  }

  async delete(_recipient: Buffer, ids: Buffer[]): Promise<void> {
    for (const id of ids) this.rows.delete(id.toString('hex'))
  }
}

class FakeSocket implements SessionSocket {
  readonly frames: Array<{ type: string, id?: string }> = []
  closedWith: number | null = null
  bufferedAmount = 0

  get isOpen(): boolean {
    return this.closedWith === null
  }

  send(text: string): void {
    this.frames.push(JSON.parse(text) as { type: string, id?: string })
  }

  close(code: number): void {
    this.closedWith ??= code
  }

  terminate(): void {
    this.close(1006)
  }

  get ids(): string[] {
    return this.frames.map(frame => frame.type === 'message' ? frame.id! : frame.type)
  }
}

const ids = new EnvelopeIdGenerator()
const wire = (entry: { id: Buffer }) => entry.id.toString('base64url')
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
const tick = () => new Promise(resolve => setImmediate(resolve))
const sum = (usages: HeldUsage[]): HeldUsage => usages.reduce((a, b) => ({ count: a.count + b.count, bytes: a.bytes + b.bytes }), { count: 0, bytes: 0 })

function gate(): { promise: Promise<void>, open: () => void } {
  let open!: () => void
  const promise = new Promise<void>(resolve => (open = resolve))
  return { promise, open }
}

async function until(condition: () => boolean, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!condition() && Date.now() < deadline) await sleep(5)
  expect(condition()).toBe(true)
}

/** A small deterministic generator, so that a failing sequence can be replayed. */
function random(seed: number): () => number {
  let state = seed
  return () => {
    state = (state + 0x6D2B79F5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function setup(options: { limits?: MailboxLimits, ackTimeoutMs?: number, maxHoldMs?: number, maxHeldBytes?: number } = {}) {
  const limits = options.limits ?? { maxEnvelopes: 2, maxBytes: 128 }
  const mailbox = new CountingMailbox(limits)
  const logger = new Logger('error')
  const persister = new EnvelopePersister(mailbox as unknown as Mailbox, logger)
  const sessions = new SessionManager({
    mailbox: mailbox as unknown as Mailbox,
    persister,
    ackTimeoutMs: options.ackTimeoutMs ?? 60_000,
    idleTimeoutMs: 600_000,
    logger,
    maxHoldMs: options.maxHoldMs,
    maxHeldBytes: options.maxHeldBytes,
  })
  /** Accepted and not acknowledged, by id. */
  const outstanding = new Map<string, PendingEnvelope>()

  /** The checks and the hand-over of PUT /v1/messages/{recipient}, in the same order. */
  async function send(recipient: Buffer, bytes = 32): Promise<'accepted' | 'full' | 'unavailable'> {
    const recipientKey = recipient.toString('hex')
    if (!mailbox.loaded) await mailbox.load()
    if (sessions.overloaded) return 'unavailable'
    if (!mailbox.admits(recipientKey, bytes, sessions.inMemory(recipientKey))) return 'full'
    const id = ids.next()
    const entry: PendingEnvelope = { recipient, recipientKey, id, idKey: id.toString('hex'), serverTimestamp: Date.now(), body: randomBytes(bytes), acked: false }
    if (!sessions.deliver(entry)) persister.enqueue([entry])
    outstanding.set(entry.idKey, entry)
    return 'accepted'
  }

  /** What the relay holds for a recipient: in a session, in the persister and stored. */
  function everywhere(recipientKey: string): HeldUsage {
    return sum([sessions.held(recipientKey), persister.held(recipientKey), mailbox.stored(recipientKey)])
  }

  function expectWithinLimits(recipientKey: string): void {
    const usage = everywhere(recipientKey)
    expect(usage.count).toBeLessThanOrEqual(limits.maxEnvelopes)
    expect(usage.bytes).toBeLessThanOrEqual(limits.maxBytes)
  }

  function acknowledged(entries: Iterable<{ idKey: string }>): void {
    for (const entry of entries) outstanding.delete(entry.idKey)
  }

  return { mailbox, persister, sessions, outstanding, send, everywhere, expectWithinLimits, acknowledged }
}

async function live(sessions: SessionManager, account: Buffer, socket = new FakeSocket()) {
  const session = sessions.open(account, socket)
  await until(() => socket.ids.includes('queue_empty'))
  return { session, socket }
}

describe('envelopes held for live sessions', () => {
  it('count against the mailbox limits while the recipient does not acknowledge, and after the ack timeout', async () => {
    const { mailbox, sessions, send, expectWithinLimits } = setup({ limits: { maxEnvelopes: 2, maxBytes: 128 }, ackTimeoutMs: 50 })
    const account = randomBytes(16)
    const key = account.toString('hex')
    const { socket } = await live(sessions, account)

    const results: string[] = []
    for (let i = 0; i < 20; i++) results.push(await send(account))
    expect(results.slice(0, 2)).toEqual(['accepted', 'accepted'])
    expect(new Set(results.slice(2))).toEqual(new Set(['full']))
    expect(sessions.held(key)).toEqual({ count: 2, bytes: 64 })
    expectWithinLimits(key)
    await until(() => socket.ids.length === 3)

    // No ack: they are stored, and the mailbox stays full.
    await until(() => mailbox.rows.size === 2)
    expect(sessions.held(key)).toEqual({ count: 0, bytes: 0 })
    expect(await send(account)).toBe('full')
    expectWithinLimits(key)
  })

  it('count their bytes against the mailbox byte limit', async () => {
    const { sessions, send, expectWithinLimits } = setup({ limits: { maxEnvelopes: 100, maxBytes: 128 } })
    const account = randomBytes(16)
    await live(sessions, account)
    expect(await send(account, 50)).toBe('accepted')
    expect(await send(account, 50)).toBe('accepted')
    expect(await send(account, 50)).toBe('full')
    expect(await send(account, 28)).toBe('accepted')
    expect(await send(account, 1)).toBe('full')
    expectWithinLimits(account.toString('hex'))
  })

  it('leave memory by their hold limit however often the recipient reconnects, and stay within the limits', async () => {
    const maxHoldMs = 150
    const { mailbox, persister, sessions, send, expectWithinLimits } = setup({ limits: { maxEnvelopes: 4, maxBytes: 1024 }, ackTimeoutMs: 60_000, maxHoldMs })
    const account = randomBytes(16)
    const key = account.toString('hex')
    let { session } = await live(sessions, account)

    let accepted = 0
    const started = Date.now()
    while (Date.now() - started < 6 * maxHoldMs) {
      if (await send(account) === 'accepted') accepted++
      // A new connection every 30 ms, well within both timeouts, and never an ack.
      const previous = session
      session = sessions.open(account, new FakeSocket())
      sessions.closed(previous)
      await sleep(30)
      expectWithinLimits(key)
      for (const entry of session.pending.values()) {
        expect(entry.holdUntil!).toBeLessThanOrEqual(entry.serverTimestamp + maxHoldMs + 5)
        expect(Date.now()).toBeLessThanOrEqual(entry.holdUntil! + 100)
      }
    }
    expect(accepted).toBe(4)
    await until(() => sessions.held(key).count === 0 && persister.held(key).count === 0)
    expect(mailbox.stored(key).count).toBe(4)
    expect(await send(account)).toBe('full')
  })

  it('count and time envelopes delivered while the backlog is still being sent', async () => {
    const { mailbox, sessions, send, expectWithinLimits } = setup({ limits: { maxEnvelopes: 2, maxBytes: 1024 }, ackTimeoutMs: 60_000, maxHoldMs: 100 })
    const account = randomBytes(16)
    const key = account.toString('hex')
    const reading = gate()
    mailbox.readGate = reading.promise
    const socket = new FakeSocket()
    const session = sessions.open(account, socket)
    await tick()

    expect(await send(account)).toBe('accepted')
    expect(await send(account)).toBe('accepted')
    expect(await send(account)).toBe('full')
    expect(session.state).toBe('syncing')
    expect(sessions.held(key).count).toBe(2)
    for (const entry of session.pending.values()) expect(entry.ackTimer).toBeDefined()
    const delivered = [...session.pending.values()]

    // The backlog never finishes, yet they go to the mailbox at their hold limit.
    await until(() => mailbox.rows.size === 2)
    expect(session.state).toBe('syncing')
    expect(sessions.held(key)).toEqual({ count: 0, bytes: 0 })
    expect(await send(account)).toBe('full')
    expectWithinLimits(key)

    // Once the backlog is read they arrive from the mailbox.
    reading.open()
    await until(() => socket.ids.includes('queue_empty'))
    expect(socket.ids).toEqual([...delivered.map(wire), 'queue_empty'])
  })

  it('count envelopes a connecting recipient takes back from the persister while a write is running', async () => {
    const { mailbox, sessions, send, expectWithinLimits } = setup({ limits: { maxEnvelopes: 2, maxBytes: 1024 } })
    const account = randomBytes(16)
    const key = account.toString('hex')
    const writing = gate()
    mailbox.writeGate = writing.promise
    expect(await send(account)).toBe('accepted')
    await tick()
    expect(await send(account)).toBe('accepted')

    // The second one is taken back while the first is being written.
    const socket = new FakeSocket()
    sessions.open(account, socket)
    await tick()
    expect(await send(account)).toBe('full')
    expectWithinLimits(key)

    writing.open()
    await until(() => socket.ids.includes('queue_empty'))
    expect(sessions.held(key).count).toBe(1)
    expect(mailbox.stored(key).count).toBe(1)
    expect(await send(account)).toBe('full')
  })

  it('do not deliver again an envelope acknowledged while a connecting recipient waits for a write', async () => {
    const { mailbox, sessions, outstanding, send } = setup({ limits: { maxEnvelopes: 10, maxBytes: 1024 } })
    const account = randomBytes(16)
    const key = account.toString('hex')
    const writing = gate()
    mailbox.writeGate = writing.promise
    expect(await send(account)).toBe('accepted')
    await tick()
    expect(await send(account)).toBe('accepted')
    const [, queued] = [...outstanding.values()]

    // The queued one is taken back, then acknowledged over HTTP before the write ends.
    const socket = new FakeSocket()
    sessions.open(account, socket)
    await tick()
    await sessions.acknowledge(account, [queued!.id])

    writing.open()
    await until(() => socket.ids.includes('queue_empty'))
    expect(socket.ids).not.toContain(wire(queued!))
    expect(sessions.held(key).count).toBe(0)
  })

  it('do not hand an envelope acknowledged meanwhile to the connection that replaced the one taking it back', async () => {
    const { mailbox, persister, sessions, outstanding, send } = setup({ limits: { maxEnvelopes: 10, maxBytes: 1024 } })
    const account = randomBytes(16)
    const key = account.toString('hex')
    const writing = gate()
    mailbox.writeGate = writing.promise
    expect(await send(account)).toBe('accepted')
    await tick()
    expect(await send(account)).toBe('accepted')
    const [written, queued] = [...outstanding.values()]

    // The first connection takes the queued one back and waits for the
    // write; it is acknowledged over HTTP, and a second connection replaces
    // the first before the write ends.
    const first = sessions.open(account, new FakeSocket())
    await tick()
    await sessions.acknowledge(account, [queued!.id])
    const socket = new FakeSocket()
    sessions.open(account, socket)
    sessions.closed(first)

    writing.open()
    await until(() => socket.ids.includes('queue_empty'))
    expect(socket.ids).toEqual([wire(written!), 'queue_empty'])
    expect(sessions.held(key).count).toBe(0)
    expect(persister.held(key).count).toBe(0)
    expect(mailbox.stored(key).count).toBe(1)
  })

  it('count against the memory limit together with the persister, for online and offline recipients alike', async () => {
    const { mailbox, persister, sessions, send } = setup({ limits: { maxEnvelopes: 100, maxBytes: 1 << 20 }, maxHeldBytes: 100 })
    const online = randomBytes(16)
    const offline = randomBytes(16)
    const { session } = await live(sessions, online)
    const writing = gate()
    mailbox.writeGate = writing.promise

    expect(await send(online, 64)).toBe('accepted')
    expect(await send(offline, 64)).toBe('accepted')
    await tick()
    expect(sessions.heldBytes).toBe(64)
    expect(persister.heldBytes).toBe(64)
    expect(sessions.overloaded).toBe(true)
    expect(await send(online, 1)).toBe('unavailable')
    expect(await send(offline, 1)).toBe('unavailable')

    sessions.acknowledgeFrame(session, [...session.pending.values()].map(entry => entry.id))
    expect(sessions.heldBytes).toBe(0)
    expect(await send(online, 1)).toBe('accepted')
    writing.open()
    await until(() => persister.heldBytes === 0)
    expect(await send(offline, 1)).toBe('accepted')
  })

  it('are released by acks, closes, account deletion and shutdown, so every count returns to zero', async () => {
    const { mailbox, persister, sessions, outstanding, send } = setup({ limits: { maxEnvelopes: 100, maxBytes: 1 << 20 } })
    const [a, b, c, d] = [randomBytes(16), randomBytes(16), randomBytes(16), randomBytes(16)]
    const key = (account: Buffer) => account.toString('hex')
    const sa = await live(sessions, a)
    await live(sessions, b)
    await live(sessions, d)
    for (const account of [a, b, c, d]) {
      expect(await send(account)).toBe('accepted')
      expect(await send(account)).toBe('accepted')
    }
    expect(sessions.heldBytes).toBe(6 * 32)

    sessions.acknowledgeFrame(sa.session, [...sa.session.pending.values()].map(entry => entry.id))
    expect(sessions.held(key(a))).toEqual({ count: 0, bytes: 0 })
    const [firstForB] = [...outstanding.values()].filter(entry => entry.recipient.equals(b))
    await sessions.acknowledge(b, [firstForB!.id])
    expect(sessions.held(key(b))).toEqual({ count: 1, bytes: 32 })

    sessions.disconnect(key(d), 4401, 'account deleted', true)
    persister.drop(key(d))
    expect(sessions.held(key(d))).toEqual({ count: 0, bytes: 0 })

    expect(await send(a)).toBe('accepted')
    sessions.closed(sa.session)
    expect(sessions.held(key(a))).toEqual({ count: 0, bytes: 0 })

    sessions.shutdown()
    expect(sessions.held(key(b))).toEqual({ count: 0, bytes: 0 })
    expect(sessions.heldBytes).toBe(0)
    expect(await persister.drain(Date.now() + 5000)).toBe(true)
    expect(persister.heldBytes).toBe(0)
    expect(mailbox.stored(key(a)).count).toBe(1)
    expect(mailbox.stored(key(b)).count).toBe(1)
    expect(mailbox.stored(key(c)).count).toBe(2)
    expect(mailbox.stored(key(d)).count).toBe(0)
  })

  it('keep every count exact through any sequence of sends, acks, timeouts, reconnects and closes', async () => {
    const limits = { maxEnvelopes: 6, maxBytes: 256 }
    const { mailbox, persister, sessions, outstanding, send, everywhere, expectWithinLimits, acknowledged } = setup({ limits, ackTimeoutMs: 20, maxHoldMs: 45 })
    const next = random(20261011)
    const pick = <T>(items: T[]): T => items[Math.floor(next() * items.length)]!
    const accounts = Array.from({ length: 3 }, () => randomBytes(16))
    const current = new Map<string, LiveSession>()
    const everySession: LiveSession[] = []
    let writing: { promise: Promise<void>, open: () => void } | null = null
    let reading: { promise: Promise<void>, open: () => void } | null = null

    const outstandingFor = (account: Buffer) => [...outstanding.values()].filter(entry => entry.recipient.equals(account))

    function check(): void {
      let totalBytes = 0
      for (const account of accounts) {
        const key = account.toString('hex')
        const inSessions = sum(everySession.filter(s => s.accountKey === key).map(s => sum([...s.pending.values()].map(e => ({ count: 1, bytes: e.body.length })))))
        expect(sessions.held(key)).toEqual(inSessions)
        totalBytes += inSessions.bytes
        expectWithinLimits(key)
        // Nothing accepted and unacknowledged is missing (the persister may still count acked ones).
        expect(everywhere(key).count).toBeGreaterThanOrEqual(outstandingFor(account).length)
      }
      expect(sessions.heldBytes).toBe(totalBytes)
      for (const session of everySession) {
        if (session.closed) expect(session.pending.size).toBe(0)
      }
    }

    for (let step = 0; step < 400; step++) {
      const account = pick(accounts)
      const key = account.toString('hex')
      const session = current.get(key)
      const roll = next()
      if (roll < 0.35) {
        await send(account, 1 + Math.floor(next() * 64))
      } else if (roll < 0.45 && session) {
        const chosen = [...session.pending.values()].filter(() => next() < 0.5)
        sessions.acknowledgeFrame(session, chosen.map(entry => entry.id))
        acknowledged(chosen)
      } else if (roll < 0.55) {
        const chosen = outstandingFor(account).filter(() => next() < 0.5)
        await sessions.acknowledge(account, chosen.map(entry => entry.id))
        acknowledged(chosen)
      } else if (roll < 0.65) {
        const opened = sessions.open(account, new FakeSocket())
        everySession.push(opened)
        current.set(key, opened)
        if (session) sessions.closed(session)
      } else if (roll < 0.72 && session) {
        sessions.closed(session)
        current.delete(key)
      } else if (roll < 0.78) {
        if (writing) {
          writing.open()
          writing = null
          mailbox.writeGate = null
        } else {
          writing = gate()
          mailbox.writeGate = writing.promise
        }
      } else if (roll < 0.82) {
        if (reading) {
          reading.open()
          reading = null
          mailbox.readGate = null
        } else {
          reading = gate()
          mailbox.readGate = reading.promise
        }
      } else {
        await sleep(Math.floor(next() * 30))
      }
      await tick()
      check()
    }

    // Let everything settle: what is not acknowledged ends up stored, exactly once.
    writing?.open()
    reading?.open()
    mailbox.writeGate = null
    mailbox.readGate = null
    for (const session of current.values()) sessions.closed(session)
    await until(() => persister.size === 0 && sessions.heldBytes === 0)
    for (const account of accounts) {
      const key = account.toString('hex')
      expect(mailbox.stored(key).count).toBe(outstandingFor(account).length)
      await sessions.acknowledge(account, outstandingFor(account).map(entry => entry.id))
      acknowledged(outstandingFor(account))
      expect(everywhere(key)).toEqual({ count: 0, bytes: 0 })
    }
    expect(sessions.heldBytes).toBe(0)
    expect(persister.heldBytes).toBe(0)
  })
})
