import { randomBytes } from 'node:crypto'
import { connect, type Socket } from 'node:net'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { EnvelopeIdGenerator } from '../server/lib/envelope-id'
import { Logger } from '../server/lib/logger'
import { EnvelopePersister, type PendingEnvelope } from '../server/realtime/persister'
import { SessionManager, type SessionSocket } from '../server/realtime/sessions'
import type { Mailbox, MailboxRow, StoredEnvelope } from '../server/services/mailbox'
import { Api, b64u, eventually, registerWithAccessKey, send, sleep } from './support/client'
import { TestDb, uuidBytes } from './support/db'
import { startRelay, type RelayProcess } from './support/relay'

/*
 * Security review (lens: dos), findings DOS-1 .. DOS-7. Each test states the
 * behaviour a non-abusable relay would have (mostly as described by the
 * finding's own fix) and fails against the current relay, or demonstrates
 * the resource blow-up directly.
 *
 * Constants mirrored from the source so the tests keep their meaning:
 */
const MAX_BUFFERED_BYTES = 1024 * 1024 // server/realtime/sessions.ts
const SYNC_PAGE_SIZE = 200 //            the backlog page size before the fix (now 2 MiB slices)
const ENVELOPE = 64 * 1024 //            default ENVELOPE_MAX_BYTES
const randomToken = () => b64u(randomBytes(32))

// ---------------------------------------------------------------------------
// DOS-1 / DOS-2: the in-memory fast path and the backlog sync (unit level,
// against the real SessionManager with an in-memory mailbox/socket).
// ---------------------------------------------------------------------------

class FakeMailbox {
  readonly rows = new Map<string, MailboxRow>()
  async insert(rows: MailboxRow[]): Promise<void> {
    for (const row of rows) this.rows.set(row.id.toString('hex'), row)
  }

  async fetch(recipient: Buffer, limit: number, after?: Buffer): Promise<StoredEnvelope[]> {
    return [...this.rows.values()]
      .filter(row => row.recipient.equals(recipient) && (!after || Buffer.compare(row.id, after) > 0))
      .sort((a, b) => Buffer.compare(a.id, b.id))
      .slice(0, limit)
  }

  async delete(_recipient: Buffer, ids: Buffer[]): Promise<void> {
    for (const id of ids) this.rows.delete(id.toString('hex'))
  }
}

/** A socket that accumulates everything written to it and never drains (a stalled reader). */
class StalledSocket implements SessionSocket {
  readonly frames: string[] = []
  closedWith: number | null = null
  terminated = false
  buffered = 0
  peakBuffered = 0

  get isOpen(): boolean {
    return this.closedWith === null && !this.terminated
  }

  get bufferedAmount(): number {
    return this.buffered
  }

  send(text: string): void {
    this.frames.push(text)
    this.buffered += Buffer.byteLength(text)
    if (this.buffered > this.peakBuffered) this.peakBuffered = this.buffered
  }

  close(code: number): void {
    this.closedWith ??= code
  }

  terminate(): void {
    this.terminated = true
    this.closedWith ??= 1006
  }

  types(): string[] {
    return this.frames.map(frame => (JSON.parse(frame) as { type: string }).type)
  }
}

const ids = new EnvelopeIdGenerator()

function makeSessions() {
  const mailbox = new FakeMailbox()
  const logger = new Logger('error')
  const persister = new EnvelopePersister(mailbox as unknown as Mailbox, logger)
  // A long ack timeout so the fast-path ack timers never fire during a test.
  const sessions = new SessionManager({ mailbox: mailbox as unknown as Mailbox, persister, ackTimeoutMs: 600_000, idleTimeoutMs: 600_000, logger })
  return { mailbox, sessions }
}

function envelope(recipient: Buffer, bodyBytes = 8): PendingEnvelope {
  const id = ids.next()
  return { recipient, recipientKey: recipient.toString('hex'), id, idKey: id.toString('hex'), serverTimestamp: Date.now(), body: randomBytes(bodyBytes), acked: false }
}

async function until(condition: () => boolean, ms = 10_000): Promise<void> {
  const deadline = Date.now() + ms
  while (!condition()) {
    if (Date.now() > deadline) throw new Error('condition not met in time')
    await new Promise(resolve => setTimeout(resolve, 5))
  }
}

describe('DOS-1: live delivery ignores outbound backpressure', () => {
  it('keeps writing sealed envelopes to a stalled socket instead of applying a byte budget', async () => {
    const { sessions } = makeSessions()
    const account = randomBytes(16)
    const socket = new StalledSocket()
    sessions.open(account, socket)
    await until(() => socket.types().includes('queue_empty'))

    // The recipient's socket is already far over any sane budget (slow phone link,
    // or a self-addressed flood). A non-abusable relay would stop writing here.
    socket.buffered = 4 * MAX_BUFFERED_BYTES
    const before = socket.frames.length
    for (let i = 0; i < 50; i++) sessions.deliver(envelope(account, ENVELOPE))

    const sentWhileOverBudget = socket.frames.length - before
    // Fix: over-budget frames are withheld (left pending for the ack timer / close).
    expect(sentWhileOverBudget).toBeLessThanOrEqual(1)
  })

  it('sweepIdle never drops a socket that keeps pinging while its write buffer grows', async () => {
    const { sessions } = makeSessions()
    const account = randomBytes(16)
    const socket = new StalledSocket()
    const session = sessions.open(account, socket)
    await until(() => socket.types().includes('queue_empty'))

    // A client that trickles a ping every few seconds keeps lastFrameAt fresh,
    // so the idle sweep sees nothing wrong even as the buffer balloons.
    session.lastFrameAt = Date.now()
    socket.buffered = 8 * MAX_BUFFERED_BYTES
    sessions.sweepIdle()

    // Fix: a persistently over-budget socket is terminated regardless of lastFrameAt.
    expect(socket.terminated).toBe(true)
  })
})

describe('DOS-2: backlog sync has no per-frame backpressure', () => {
  // Adjusted after the fix: the original waited for queue_empty on a socket
  // that never drains, which a relay that pauses at the budget can only
  // answer with close 1013 after its drain timeout, never with queue_empty.
  it('pauses the backlog at the socket budget instead of pushing a whole page into a stalled socket', async () => {
    const { mailbox, sessions } = makeSessions()
    const account = randomBytes(16)
    const count = SYNC_PAGE_SIZE - 1
    for (let i = 0; i < count; i++) {
      const id = ids.next()
      mailbox.rows.set(id.toString('hex'), { recipient: account, id, serverTimestamp: Date.now(), body: randomBytes(ENVELOPE) })
    }

    const socket = new StalledSocket()
    sessions.open(account, socket)
    await sleep(500)
    // Stalled: the sync stopped near the budget and waits for the client to read.
    expect(socket.types()).not.toContain('queue_empty')
    expect(socket.peakBuffered).toBeLessThanOrEqual(MAX_BUFFERED_BYTES + 2 * ENVELOPE)

    // Once the client reads, the rest follows, still within the budget.
    const reading = setInterval(() => (socket.buffered = 0), 5)
    try {
      await until(() => socket.types().includes('queue_empty'), 20_000)
    } finally {
      clearInterval(reading)
    }
    expect(socket.types().filter(type => type === 'message')).toHaveLength(count)
    expect(socket.peakBuffered).toBeLessThanOrEqual(MAX_BUFFERED_BYTES + 2 * ENVELOPE)
  })

  it('replaces a stalled connection with close(), so its buffered backlog lingers for the ws close timeout', async () => {
    const { sessions } = makeSessions()
    const account = randomBytes(16)
    const first = new StalledSocket()
    sessions.open(account, first)
    await until(() => first.types().includes('queue_empty'))

    first.buffered = 10 * MAX_BUFFERED_BYTES // a large, unread backlog is still in flight
    const second = new StalledSocket()
    sessions.open(account, second)

    expect(first.closedWith).toBe(4001) // replaced, as the protocol requires
    // Fix: a replaced socket with a large buffer is terminate()d so its memory is freed
    // at once instead of waiting out the bundled ws 30 s close timeout.
    expect(first.terminated).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// DOS-3 .. DOS-7: against a real relay process.
// ---------------------------------------------------------------------------

describe('relay DoS (integration)', () => {
  let relay: RelayProcess
  let api: Api
  let db: TestDb

  beforeAll(async () => {
    relay = await startRelay()
    api = new Api(relay.url)
    db = await TestDb.connect()
  })

  afterAll(async () => {
    await db.close()
    await relay.stop()
  })

  const comSelect = async (): Promise<number> => {
    const [row] = await db.query<{ Variable_name: string, Value: string }>(
      "SHOW GLOBAL STATUS LIKE 'Com_select'",
    )
    return Number(row!.Value)
  }

  async function storeOffline(account: Awaited<ReturnType<typeof registerWithAccessKey>>, count: number, bodyBytes = ENVELOPE): Promise<number[]> {
    const body = randomBytes(bodyBytes)
    const statuses: number[] = []
    const concurrency = 25
    for (let i = 0; i < count; i += concurrency) {
      const batch = Array.from({ length: Math.min(concurrency, count - i) }, () => send(api, account, body).then(r => r.status))
      statuses.push(...await Promise.all(batch))
    }
    return statuses
  }

  describe('DOS-5b: one mailbox fetch per account at a time', () => {
    it('refuses a second fetch of a mailbox while the first response is still being sent', async () => {
      const account = await registerWithAccessKey(api)
      expect((await storeOffline(account, 120)).every(s => s === 202)).toBe(true)
      const { port } = new URL(relay.url)
      // ~10 MiB of JSON that nobody reads: the response stays in flight.
      const stalled = await openStalledGet(Number(port), '/v1/messages?limit=500', { 'x-auth-token': account.authToken })
      expect(stalled.status).toBe(200)
      const second = await api.get('/v1/messages', { token: account.authToken })
      expect(second.status).toBe(429)
      stalled.socket.destroy()
      await eventually(async () => (await api.get('/v1/messages?limit=1', { token: account.authToken })).status === 200)
    })
  })

  describe('DOS-4: a stalled download is timed out by the relay', () => {
    // Adjusted after the fix. A client that never reads cannot notice that
    // the relay closed the connection (the FIN waits behind the unread
    // data), so the original "closed" flag could never turn true. Each
    // client now reads after the wait: a connection the relay dropped ends
    // before the whole body arrived. The blob is 8 MiB so that it cannot
    // fit into the loopback socket buffers and finish on its own.
    it('drops non-reading media downloads after its stall timeout, freeing their file handles', async () => {
      const account = await registerWithAccessKey(api)
      const blob = randomBytes(8 * 1024 * 1024)
      const upload = await api.post('/v1/media', { token: account.authToken, body: blob, headers: { 'content-length': String(blob.length) } })
      expect(upload.status).toBe(201)
      const mediaId = upload.body.mediaId as string

      const { port } = new URL(relay.url)
      const sockets: Array<{ socket: Socket, closed: boolean, status: number | null }> = []
      const N = 20
      for (let i = 0; i < N; i++) {
        const entry = await openStalledGet(Number(port), `/v1/media/${mediaId}`)
        sockets.push(entry)
      }
      // Every download started (headers were received) and then stalled.
      expect(sockets.every(s => s.status === 200 || s.status === 206)).toBe(true)

      // Node's keep-alive (5 s), headers (60 s) and request (300 s) timeouts do
      // not apply to an in-progress response; the relay's own stall timeout does.
      await sleep(40_000)
      const outcomes = await Promise.all(sockets.map(s => readToEnd(s.socket, 10_000)))
      for (const s of sockets) s.socket.destroy()

      // Fix: a stalled streamed response is destroyed within ~30 s, freeing its FileHandle.
      const keptOpen = outcomes.filter(outcome => !outcome.ended || outcome.bytes >= blob.length)
      expect(keptOpen).toEqual([])
    }, 90_000)
  })

  describe('DOS-5: GET /v1/messages?limit=500 is bounded by bytes as well as by count', () => {
    it('returns at most a few MiB in one response for a full mailbox', async () => {
      const account = await registerWithAccessKey(api)
      const statuses = await storeOffline(account, 500)
      expect(statuses.every(s => s === 202)).toBe(true)

      const response = await fetch(`${relay.url}/v1/messages?limit=500`, { headers: { 'x-auth-token': account.authToken } })
      expect(response.status).toBe(200)
      const text = await response.text()
      const bytes = Buffer.byteLength(text)

      // Fix: a page is capped by bytes (a few MiB) with more=true. (The
      // original also asserted the unfixed ~43 MiB, which cannot hold together
      // with the cap; that assertion was removed.)
      expect(bytes).toBeLessThanOrEqual(8 * 1024 * 1024)
      const page = JSON.parse(text) as { messages: unknown[], more: boolean }
      expect(page.messages.length).toBeGreaterThan(0)
      expect(page.more).toBe(true)
    }, 60_000)
  })

  describe('DOS-6: per-recipient mailbox storage budget', () => {
    // Adjusted after the fix: the original also asserted that all 850 were
    // stored, which contradicts the refusal it asks for.
    it('refuses envelopes for one recipient past its 50 MiB budget, and stores everything it accepted', async () => {
      const account = await registerWithAccessKey(api)
      // 850 x 64 KiB ~= 53 MiB, past the default MAILBOX_MAX_BYTES of 50 MiB.
      const count = 850
      const statuses = await storeOffline(account, count)
      const accepted = statuses.filter(s => s === 202).length
      const refused = statuses.filter(s => s !== 202)

      expect(statuses.length).toBe(count)
      expect(refused.length).toBeGreaterThan(0)
      expect(new Set(refused)).toEqual(new Set([429]))
      expect(accepted * ENVELOPE).toBeLessThanOrEqual(50 * 1024 * 1024)
      await eventually(async () => await db.count('envelopes WHERE recipient = ?', [uuidBytes(account.accountId)]) === accepted)

      // Another recipient has its own budget.
      const other = await registerWithAccessKey(api)
      expect((await send(api, other, randomBytes(ENVELOPE))).status).toBe(202)
    }, 90_000)
  })

  describe('DOS-7: unknown website paths are answered from memory, never rendered per request', () => {
    // Rewritten after the fix: the original measured the per-request Vue
    // render that the production server no longer has.
    it('serves one static 404 with the site headers for every unknown path, method and case variant', async () => {
      const html = { accept: 'text/html' }
      const unknown = [`/tr/${randomBytes(6).toString('hex')}`, '/EN', '/Tr/Gizlilik', '/en/Privacy', '/__nuxt_error', '/__nuxt_island/x', '/does/not/exist']
      const bodies = new Set<string>()
      for (const path of unknown) {
        const response = await fetch(relay.url + path, { headers: html, redirect: 'manual' })
        bodies.add(await response.text())
        expect(response.status, path).toBe(404)
        expect(response.headers.get('content-type'), path).toMatch(/text\/html/)
        expect(response.headers.get('content-security-policy'), path).toContain('default-src \'none\'')
        expect(response.headers.get('x-powered-by'), path).toBeNull()
      }
      const posted = await fetch(`${relay.url}/en`, { method: 'POST', headers: html, body: 'x' })
      bodies.add(await posted.text())
      expect(posted.status).toBe(404)
      // One page for all of them, and not a Vue render (no app root).
      expect(bodies.size).toBe(1)
      const [page] = bodies
      expect(page).toContain('The page you are looking for does not exist.')
      expect(page).not.toContain('__nuxt')

      const timeAvg = async (path: () => string, n: number, headers?: Record<string, string>): Promise<number> => {
        const start = Date.now()
        for (let i = 0; i < n; i++) {
          const response = await fetch(relay.url + path(), headers ? { headers } : undefined)
          await response.text()
        }
        return (Date.now() - start) / n
      }
      const randomPath = () => `/tr/${randomBytes(6).toString('hex')}`
      const notFound = await timeAvg(randomPath, 200, html)
      const prerendered = await timeAvg(() => '/en', 200, html)
      const health = await timeAvg(() => '/v1/health', 200)
      // eslint-disable-next-line no-console
      console.log(`DOS-7 avg ms/req — 404: ${notFound.toFixed(2)}, prerendered /en: ${prerendered.toFixed(2)}, /v1/health: ${health.toFixed(2)}`)
      // As cheap as a static page (the per-request render cost 3-5x as much).
      expect(notFound).toBeLessThan(Math.max(prerendered, health) * 2)
    }, 60_000)
  })
})

describe('DOS-3: failed X-Auth-Token checks are rate limited per IP (PROTOCOL.md §4.11)', () => {
  // Adjusted after the fix: its own relay (an IP over the limit is refused
  // for every authenticated request, which would break the tests that
  // share one), addresses set through X-Forwarded-For, and a check that
  // the refusal needs no database lookup instead of the unfixed "every
  // invalid token reaches MySQL".
  let relay: RelayProcess
  let api: Api
  let db: TestDb

  beforeAll(async () => {
    relay = await startRelay({ RATE_LIMIT_AUTH_FAIL: '30/min', TRUST_PROXY: '1' })
    api = new Api(relay.url)
    db = await TestDb.connect()
  })

  afterAll(async () => {
    await db.close()
    await relay.stop()
  })

  const status = (ip: string, token: string) =>
    api.get('/v1/keys/status', { token, headers: { 'x-forwarded-for': ip } }).then(response => response.status)

  it('answers 429 without looking the token up once an IP has failed 30 checks; other clients are unaffected', async () => {
    const valid = await registerWithAccessKey(api)
    expect(await status('198.51.100.20', valid.authToken)).toBe(200)

    const statuses: number[] = []
    for (let i = 0; i < 40; i++) statuses.push(await status('203.0.113.7', randomToken()))
    expect(statuses.slice(0, 30)).toEqual(Array(30).fill(401))
    expect(statuses.slice(30)).toEqual(Array(10).fill(429))

    // With the accounts table locked, a request that looks a token up stalls.
    await db.query('LOCK TABLES `accounts` WRITE')
    let answered: boolean
    let refused: Promise<number>
    try {
      refused = status('203.0.113.7', randomToken())
      answered = await settlesWithin(refused, 1000)
    } finally {
      await db.query('UNLOCK TABLES')
    }
    expect(answered).toBe(true)
    expect(await refused).toBe(429)
    expect(await status('198.51.100.20', valid.authToken)).toBe(200)
  })

  it('lets at most 30 lookups of one IP through even when they all arrive at once', async () => {
    const statuses = await Promise.all(Array.from({ length: 100 }, () => status('203.0.113.8', randomToken())))
    expect(statuses.filter(s => s === 401).length).toBeLessThanOrEqual(30)
    expect(statuses.filter(s => s !== 401).every(s => s === 429)).toBe(true)
  })
})

/** Resumes a paused socket and reads until the peer has closed it, or until `ms` pass. */
function readToEnd(socket: Socket, ms: number): Promise<{ ended: boolean, bytes: number }> {
  return new Promise((resolve) => {
    let bytes = 0
    const timer = setTimeout(() => resolve({ ended: false, bytes }), ms)
    socket.on('data', (chunk: Buffer) => (bytes += chunk.length))
    socket.once('close', () => {
      clearTimeout(timer)
      resolve({ ended: true, bytes })
    })
    socket.resume()
  })
}

/** True if `pending` settles within `ms`. */
async function settlesWithin(pending: Promise<unknown>, ms: number): Promise<boolean> {
  let timer: NodeJS.Timeout | undefined
  const timeout = new Promise<false>((resolve) => {
    timer = setTimeout(() => resolve(false), ms)
  })
  const settled = await Promise.race([pending.then(() => true, () => true), timeout])
  clearTimeout(timer)
  return settled
}

/** Opens a raw GET, reads only the status line + headers, then stops reading the body. */
function openStalledGet(port: number, path: string, headers: Record<string, string> = {}): Promise<{ socket: Socket, closed: boolean, status: number | null }> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1')
    const entry = { socket, closed: false, status: null as number | null }
    socket.on('close', () => { entry.closed = true })
    socket.on('error', () => { entry.closed = true })
    let buffer = Buffer.alloc(0)
    const onData = (chunk: Buffer) => {
      buffer = Buffer.concat([buffer, chunk])
      const headerEnd = buffer.indexOf('\r\n\r\n')
      if (headerEnd >= 0) {
        const head = buffer.subarray(0, headerEnd).toString()
        const match = /^HTTP\/1\.1 (\d+)/.exec(head)
        entry.status = match ? Number(match[1]) : null
        socket.off('data', onData)
        socket.pause() // never read the body again: the relay's write stalls on TCP backpressure
        resolve(entry)
      }
    }
    socket.on('data', onData)
    socket.on('connect', () => {
      const extra = Object.entries(headers).map(([name, value]) => `${name}: ${value}\r\n`).join('')
      socket.write(`GET ${path} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nConnection: keep-alive\r\n${extra}\r\n`)
    })
    socket.setTimeout(15_000, () => reject(new Error('stalled GET did not receive headers')))
  })
}
