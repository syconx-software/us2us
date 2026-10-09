import { randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { EnvelopeIdGenerator } from '../../server/lib/envelope-id'
import { Logger } from '../../server/lib/logger'
import { EnvelopePersister, type PendingEnvelope } from '../../server/realtime/persister'
import { MAX_BUFFERED_BYTES, SessionManager, type SessionSocket } from '../../server/realtime/sessions'
import type { Mailbox, MailboxRow, StoredEnvelope } from '../../server/services/mailbox'

/** An in-memory mailbox whose reads and writes can be held back. */
class FakeMailbox {
  readonly rows = new Map<string, MailboxRow>()
  readonly deleted: string[] = []
  readGate: Promise<void> | null = null
  writeGate: Promise<void> | null = null

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

  deleteGate: Promise<void> | null = null
  deletesRunning = 0
  maxDeletesRunning = 0
  deleteCalls = 0

  async delete(_recipient: Buffer, ids: Buffer[]): Promise<void> {
    if (ids.length === 0) return
    this.deleteCalls++
    this.maxDeletesRunning = Math.max(this.maxDeletesRunning, ++this.deletesRunning)
    await this.deleteGate
    this.deletesRunning--
    for (const id of ids) {
      this.deleted.push(id.toString('hex'))
      this.rows.delete(id.toString('hex'))
    }
  }
}

class FakeSocket implements SessionSocket {
  readonly frames: Array<{ type: string, id?: string }> = []
  closedWith: number | null = null
  terminated = false
  /** Set by tests to simulate a client that does not read. */
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
    this.terminated = true
    this.close(1006)
  }

  get ids(): string[] {
    return this.frames.map(frame => frame.type === 'message' ? frame.id! : frame.type)
  }
}

const ids = new EnvelopeIdGenerator()

function envelope(recipient: Buffer): PendingEnvelope {
  const id = ids.next()
  return { recipient, recipientKey: recipient.toString('hex'), id, idKey: id.toString('hex'), serverTimestamp: Date.now(), body: randomBytes(8), acked: false }
}

const wire = (entry: StoredEnvelope) => entry.id.toString('base64url')
const byId = (a: StoredEnvelope, b: StoredEnvelope) => Buffer.compare(a.id, b.id)

function gate(): { promise: Promise<void>, open: () => void } {
  let open!: () => void
  const promise = new Promise<void>(resolve => (open = resolve))
  return { promise, open }
}

const tick = () => new Promise(resolve => setImmediate(resolve))

async function until(condition: () => boolean): Promise<void> {
  for (let i = 0; i < 200 && !condition(); i++) await new Promise(resolve => setTimeout(resolve, 5))
  expect(condition()).toBe(true)
}

function setup(ackTimeoutMs = 60_000) {
  const mailbox = new FakeMailbox()
  const logger = new Logger('error')
  const persister = new EnvelopePersister(mailbox as unknown as Mailbox, logger)
  const sessions = new SessionManager({ mailbox: mailbox as unknown as Mailbox, persister, ackTimeoutMs, idleTimeoutMs: 60_000, logger })
  return { mailbox, persister, sessions }
}

async function live(sessions: SessionManager, account: Buffer, socket = new FakeSocket()) {
  const session = sessions.open(account, socket)
  await until(() => socket.ids.includes('queue_empty'))
  return { session, socket }
}

describe('SessionManager', () => {
  it('sends an envelope accepted while the backlog was being read before queue_empty', async () => {
    const { mailbox, sessions } = setup()
    const account = randomBytes(16)
    const stored = envelope(account)
    await mailbox.insert([stored])
    const reading = gate()
    mailbox.readGate = reading.promise
    const socket = new FakeSocket()
    sessions.open(account, socket)
    await tick()

    const late = envelope(account)
    expect(sessions.deliver(late)).toBe(true)
    reading.open()
    await until(() => socket.ids.includes('queue_empty'))
    expect(socket.ids).toEqual([wire(stored), wire(late), 'queue_empty'])
  })

  it('takes envelopes queued for storage back when their recipient connects', async () => {
    const { mailbox, persister, sessions } = setup()
    const account = randomBytes(16)
    const offline = envelope(account)
    persister.enqueue([offline])
    const { socket } = await live(sessions, account)
    expect(socket.ids).toEqual([wire(offline), 'queue_empty'])
    await sessions.acknowledge(account, [offline.id])
    await tick()
    expect(mailbox.rows.size).toBe(0)
    expect(mailbox.deleteCalls).toBe(0)
  })

  it('writes a live envelope only after the call that delivered it returned; its ack costs no delete', async () => {
    const { mailbox, sessions } = setup()
    const account = randomBytes(16)
    const { socket } = await live(sessions, account)

    const entry = envelope(account)
    expect(sessions.deliver(entry)).toBe(true)
    expect(socket.ids).toEqual(['queue_empty'])
    await tick()
    expect(socket.ids).toEqual(['queue_empty', wire(entry)])
    await sessions.acknowledge(account, [entry.id])
    expect(mailbox.deleteCalls).toBe(0)
  })

  it('holds live envelopes back while the socket is over its budget, drops ephemeral frames, and resumes once it drains', async () => {
    const { sessions } = setup()
    const account = randomBytes(16)
    const { socket } = await live(sessions, account)

    socket.bufferedAmount = MAX_BUFFERED_BYTES + 1
    const held = [envelope(account), envelope(account)]
    for (const entry of held) sessions.deliver(entry)
    sessions.deliverEphemeral(account.toString('hex'), { id: ids.next(Date.now(), true), serverTimestamp: Date.now(), body: randomBytes(8) })
    await new Promise(resolve => setTimeout(resolve, 60))
    expect(socket.ids).toEqual(['queue_empty'])

    socket.bufferedAmount = 0
    await until(() => socket.ids.length === 3)
    expect(socket.ids).toEqual(['queue_empty', ...held.map(wire)])
  })

  it('stores an envelope held back by the budget when its ack timeout passes', async () => {
    const { mailbox, sessions } = setup(50)
    const account = randomBytes(16)
    const { socket } = await live(sessions, account)
    socket.bufferedAmount = MAX_BUFFERED_BYTES + 1
    const entry = envelope(account)
    sessions.deliver(entry)
    await until(() => mailbox.rows.has(entry.idKey))
    // Once stored it is no longer sent on this connection; the next one gets it from the mailbox.
    socket.bufferedAmount = 0
    await new Promise(resolve => setTimeout(resolve, 60))
    expect(socket.ids).toEqual(['queue_empty'])
  })

  it('drops a socket that stays over its budget, and one far over it at once', async () => {
    const { sessions } = setup()
    const slow = await live(sessions, randomBytes(16))
    const stuck = await live(sessions, randomBytes(16))
    slow.socket.bufferedAmount = MAX_BUFFERED_BYTES + 1
    stuck.socket.bufferedAmount = 3 * MAX_BUFFERED_BYTES
    const now = Date.now()
    sessions.sweepIdle(now)
    expect(stuck.socket.terminated).toBe(true)
    expect(slow.socket.closedWith).toBeNull()
    sessions.sweepIdle(now + 29_000)
    expect(slow.socket.closedWith).toBeNull()
    sessions.sweepIdle(now + 30_000)
    expect(slow.socket.closedWith).toBe(1013)
    expect(slow.socket.terminated).toBe(true)
  })

  it('forgives a socket that drained between two sweeps', async () => {
    const { sessions } = setup()
    const { socket } = await live(sessions, randomBytes(16))
    const now = Date.now()
    socket.bufferedAmount = MAX_BUFFERED_BYTES + 1
    sessions.sweepIdle(now)
    socket.bufferedAmount = 0
    sessions.sweepIdle(now + 15_000)
    socket.bufferedAmount = MAX_BUFFERED_BYTES + 1
    sessions.sweepIdle(now + 31_000)
    expect(socket.closedWith).toBeNull()
  })

  it('reads and writes the backlog within the socket budget', async () => {
    const { mailbox, sessions } = setup()
    const account = randomBytes(16)
    const backlog = Array.from({ length: 70 }, () => ({ ...envelope(account), body: randomBytes(32 * 1024) }))
    await mailbox.insert(backlog)
    const socket = new FakeSocket()
    const sent = socket.send.bind(socket)
    let peak = 0
    socket.send = (text: string) => {
      sent(text)
      socket.bufferedAmount += Buffer.byteLength(text)
      peak = Math.max(peak, socket.bufferedAmount)
    }
    sessions.open(account, socket)
    await new Promise(resolve => setTimeout(resolve, 100))
    // Paused at the budget: one frame past it at most, and no queue_empty yet.
    expect(socket.ids.length).toBeLessThan(backlog.length)
    expect(socket.ids).not.toContain('queue_empty')
    expect(peak).toBeLessThanOrEqual(MAX_BUFFERED_BYTES + 64 * 1024)

    const draining = setInterval(() => (socket.bufferedAmount = 0), 5)
    await until(() => socket.ids.includes('queue_empty'))
    clearInterval(draining)
    expect(socket.ids).toEqual([...backlog.sort(byId).map(wire), 'queue_empty'])
  })

  it('closes a replaced socket that does not read with 4001 and drops it at once', async () => {
    const { sessions } = setup()
    const account = randomBytes(16)
    const first = await live(sessions, account)
    const reading = await live(sessions, randomBytes(16))
    first.socket.bufferedAmount = 2 * MAX_BUFFERED_BYTES
    await live(sessions, account)
    expect(first.socket.closedWith).toBe(4001)
    expect(first.socket.terminated).toBe(true)

    sessions.disconnect(reading.session.accountKey, 4401, 'token rotated', false)
    expect(reading.socket.closedWith).toBe(4401)
    expect(reading.socket.terminated).toBe(false)
  })

  it('deletes acknowledged stored envelopes of one socket one query at a time', async () => {
    const { mailbox, sessions } = setup()
    const account = randomBytes(16)
    const { session } = await live(sessions, account)
    const deleting = gate()
    mailbox.deleteGate = deleting.promise
    const frames = Array.from({ length: 5 }, () => [ids.next(), ids.next()])
    for (const frame of frames) sessions.acknowledgeFrame(session, frame)
    await tick()
    expect(mailbox.deletesRunning).toBe(1)
    deleting.open()
    await until(() => mailbox.deleted.length === 10)
    expect(mailbox.maxDeletesRunning).toBe(1)
    expect(mailbox.deleteCalls).toBe(2)
  })

  it('ignores acknowledgements of ephemeral ids', async () => {
    const { mailbox, sessions } = setup()
    const account = randomBytes(16)
    const ephemeral = ids.next(Date.now(), true)
    const stored = ids.next()
    await sessions.acknowledge(account, [ephemeral, stored])
    expect(mailbox.deleted).toEqual([stored.toString('hex')])
  })

  it('gives envelopes reclaimed by a replaced connection to the connection that replaced it', async () => {
    const { mailbox, persister, sessions } = setup()
    const account = randomBytes(16)
    const writing = gate()
    mailbox.writeGate = writing.promise

    // One envelope is being written when the account connects, a second one is still queued.
    const inWrite = envelope(account)
    persister.enqueue([inWrite])
    await tick()
    const queued = envelope(account)
    persister.enqueue([queued])

    const first = new FakeSocket()
    sessions.open(account, first) // takes `queued` back and waits for the write
    await tick()
    const second = new FakeSocket()
    sessions.open(account, second)
    expect(first.closedWith).toBe(4001)

    writing.open()
    await until(() => second.ids.includes('queue_empty'))
    expect(second.ids).toEqual([wire(inWrite), wire(queued), 'queue_empty'])
    expect(mailbox.rows.has(queued.idKey)).toBe(false)
    expect(persister.size).toBe(0)
  })

  it('refuses connections once shutdown has begun', () => {
    const { sessions } = setup()
    const account = randomBytes(16)
    sessions.shutdown()
    const socket = new FakeSocket()
    const session = sessions.open(account, socket)
    expect(socket.closedWith).toBe(1001)
    expect(session.closed).toBe(true)
    expect(sessions.isOnline(account.toString('hex'))).toBe(false)
  })
})
