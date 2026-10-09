import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { request } from 'node:http'
import mysql from 'mysql2/promise'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { EnvelopeIdGenerator } from '../server/lib/envelope-id'
import {
  Api,
  b64u,
  fromB64u,
  kyberKeys,
  oneTimeKeys,
  register,
  registerWithAccessKey,
  send,
  sendEphemeral,
  solvedPow,
  type TestAccount,
} from './support/client'
import { TEST_DATABASE_URL } from './support/env'
import { startRelay, type RelayProcess } from './support/relay'
import { TestSocket, type Frame } from './support/ws'

/*
 * Security review (lens: privacy), the findings that were confirmed: PRIV-1,
 * -2, -3, -4, -5, -7, -8 and -9 (PRIV-6 was refuted). Every test here states
 * the behaviour PROTOCOL.md, the privacy page or the deploy notes require and
 * fails against the current relay.
 */

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

const nginxConf = () => readFileSync(new URL('../deploy/nginx/us2us.conf', import.meta.url), 'utf8')
  .split('\n').map(line => line.replace(/#.*/, '')).join('\n')

/** The top-level `server { ... }` blocks of the nginx site, comments removed. */
function serverBlocks(conf: string): string[] {
  const blocks: string[] = []
  const opener = /(^|\n)\s*server\s*\{/g
  for (let match = opener.exec(conf); match; match = opener.exec(conf)) {
    let depth = 1
    let i = opener.lastIndex
    for (; i < conf.length && depth > 0; i++) {
      if (conf[i] === '{') depth++
      else if (conf[i] === '}') depth--
    }
    blocks.push(conf.slice(match.index, i))
    opener.lastIndex = i
  }
  return blocks
}

/** The counter bytes (6..7) of an envelope id (§4.6 layout in server/lib/envelope-id.ts). */
const idCounter = (id: Buffer) => id.readUInt16BE(6)

describe('relay processes', () => {
  let relay: RelayProcess
  let api: Api
  let locker: mysql.Connection

  beforeAll(async () => {
    relay = await startRelay()
    api = new Api(relay.url)
    locker = await mysql.createConnection({ uri: TEST_DATABASE_URL })
  })

  afterAll(async () => {
    await locker.end()
    await relay.stop()
  })

  /**
   * Runs the requests `start()` returns while `table` is write-locked from
   * another connection, and reports which of them were answered anyway, i.e.
   * which ones did not touch that table before answering.
   */
  async function answeredWhileLocked<K extends string>(table: string, start: () => Record<K, Promise<{ status: number }>>) {
    await locker.query(`LOCK TABLES \`${table}\` WRITE`)
    let requests: Record<K, Promise<{ status: number }>> | undefined
    const answered = {} as Record<K, boolean>
    try {
      requests = start()
      for (const key of Object.keys(requests) as K[]) answered[key] = await settlesWithin(requests[key], 1000)
    } finally {
      await locker.query('UNLOCK TABLES')
    }
    const statuses = {} as Record<K, number>
    for (const key of Object.keys(requests) as K[]) statuses[key] = (await requests[key]).status
    return { answered, statuses }
  }

  describe('PRIV-1: a sealed send is answered the same way whether or not the recipient is online (§4.6, §8.6)', () => {
    /** A message-request send (PoW, no access key), which anyone can make; the junk body is dropped by the victim's app. */
    async function stranger(victim: TestAccount) {
      const pow = await solvedPow(api, `purpose=message&to=${victim.accountId}`)
      return () => api.put(`/v1/messages/${victim.accountId}`, { body: randomBytes(300), headers: { 'x-pow': `${pow.token}:${pow.nonce}` } })
    }

    it('does not wait for a mailbox write before answering a send to an offline recipient', async () => {
      const online = await register(api)
      const offline = await register(api)
      const socket = await TestSocket.synced(relay.wsUrl, online.authToken)
      const toOnline = await stranger(online)
      const toOffline = await stranger(offline)

      // With the mailbox table locked, any request that writes it before
      // answering stalls; one answered from memory does not.
      const { answered, statuses } = await answeredWhileLocked('envelopes', () => ({ online: toOnline(), offline: toOffline() }))
      expect(statuses).toEqual({ online: 202, offline: 202 })
      const [frame] = await socket.waitForMessages(1)
      socket.ack([frame!.id!])
      await socket.close()

      // The 202 must not tell a stranger whether the recipient has an open WebSocket.
      expect(answered.online).toBe(true)
      expect(answered).toEqual({ online: answered.online, offline: answered.online })
    })
  })

  describe('PRIV-3: the time to answer a send does not reveal recent traffic to the recipient', () => {
    it('takes the same path for a recipient sent to a moment ago as for one not sent to', async () => {
      const recent = await registerWithAccessKey(api)
      const quiet = await registerWithAccessKey(api)
      // A wrong but well-formed key: no PoW, no contact relationship, always 202.
      const probe = (victim: TestAccount) => api.put(`/v1/messages/${victim.accountId}?ephemeral=1`, {
        body: randomBytes(64),
        headers: { 'x-access-key': b64u(randomBytes(16)) },
      })
      // Someone sends to `recent` (any send does; here an ephemeral one).
      expect((await sendEphemeral(api, [{ to: recent.accountId, accessKey: recent.accessKey, envelope: b64u(randomBytes(64)) }])).status).toBe(202)

      const { answered, statuses } = await answeredWhileLocked('accounts', () => ({ recent: probe(recent), quiet: probe(quiet) }))
      expect(statuses).toEqual({ recent: 202, quiet: 202 })
      // Either both lookups are answered from memory or both go to MySQL;
      // today only the recently used recipient skips the database.
      expect(answered).toEqual({ recent: answered.quiet, quiet: answered.quiet })
    })
  })

  describe('PRIV-4: an ephemeral batch cannot be used to measure whether a contact is online (§8.6)', () => {
    const isEphemeral = (frame: Frame) => (fromB64u(frame.id!)[8]! & 0x80) !== 0

    it('does not deliver the same recipient more than once per batch', async () => {
      const victim = await registerWithAccessKey(api)
      const socket = await TestSocket.synced(relay.wsUrl, victim.authToken)
      const item = { to: victim.accountId, accessKey: victim.accessKey, envelope: b64u(randomBytes(64)) }
      const batch = await sendEphemeral(api, Array.from({ length: 200 }, () => item))
      // A sealed send afterwards: once it arrives, every frame of the batch has arrived.
      expect((await send(api, victim, randomBytes(32))).status).toBe(202)
      const marker = await socket.waitFor(frame => frame.type === 'message' && !isEphemeral(frame))
      socket.ack([marker.id!])
      const ephemeralFrames = socket.messages.filter(isEphemeral).length
      await socket.close()
      // Rejecting duplicates (400) or collapsing them (one frame) both remove the amplifier.
      expect(batch.status === 400 || ephemeralFrames <= 1, `batch answered ${batch.status}, victim got ${ephemeralFrames} frames`)
        .toBe(true)
    })
  })

  describe('PRIV-5: pre-key bundles do not tell other accounts how many sessions a user started (§4.5, §9)', () => {
    it('does not hand out one-time pre-keys in key id order', async () => {
      const victim = await register(api, { oneTimePreKeys: oneTimeKeys(101, 12), kyberOneTimePreKeys: kyberKeys(201, 12) })
      const fetcher = await register(api)
      const claimed = { ec: [] as number[], kyber: [] as number[] }
      for (let i = 0; i < 12; i++) {
        const { status, body } = await api.get(`/v1/keys/${victim.accountId}`, { token: fetcher.authToken })
        expect(status).toBe(200)
        claimed.ec.push(body.oneTimePreKey.keyId)
        claimed.kyber.push(body.kyberPreKey.keyId)
      }
      // Lowest id first lets anyone compute "keys taken since my last fetch".
      const ascending = (ids: number[]) => ids.every((id, i) => i === 0 || id > ids[i - 1]!)
      expect(ascending(claimed.ec), `EC key ids handed out: ${claimed.ec.join(' ')}`).toBe(false)
      expect(ascending(claimed.kyber), `Kyber key ids handed out: ${claimed.kyber.join(' ')}`).toBe(false)
    })
  })

  describe('PRIV-7: ephemeral envelope ids do not number the recipients of a batch', () => {
    it('gives the last recipient of a presence batch an id that does not count the ones before it', async () => {
      const contacts = await Promise.all(Array.from({ length: 6 }, () => registerWithAccessKey(api)))
      const last = contacts[5]!
      const counters: number[] = []
      for (let round = 0; round < 3; round++) {
        const socket = await TestSocket.synced(relay.wsUrl, last.authToken)
        const batch = contacts.map(contact => ({ to: contact.accountId, accessKey: contact.accessKey, envelope: b64u(randomBytes(32)) }))
        expect((await sendEphemeral(api, batch)).status).toBe(202)
        const [frame] = await socket.waitForMessages(1)
        counters.push(idCounter(fromB64u(frame!.id!)))
        await socket.close()
      }
      // Today: counter = position in the batch (5) every time.
      expect(counters).not.toEqual([5, 5, 5])
    })
  })

  describe('PRIV-9: every website response carries the site headers', () => {
    it('does not render case variants of the site paths without the site security headers', async () => {
      const results: Record<string, { status: number, csp: boolean, poweredBy: string | null }> = {}
      for (const path of ['/EN', '/Tr/Gizlilik', '/en/Privacy']) {
        const response = await fetch(relay.url + path, { redirect: 'manual' })
        await response.arrayBuffer()
        results[path] = { status: response.status, csp: response.headers.has('content-security-policy'), poweredBy: response.headers.get('x-powered-by') }
      }
      const reference = await fetch(`${relay.url}/en`)
      await reference.arrayBuffer()
      expect(reference.headers.has('content-security-policy')).toBe(true)
      for (const [path, result] of Object.entries(results)) {
        expect(result.status === 404 || (result.csp && result.poweredBy === null), `${path}: ${JSON.stringify(result)}`).toBe(true)
      }
    })
  })
})

describe('PRIV-8: failed X-Auth-Token checks are rate limited per IP (§4.11, 30/min)', () => {
  // Each test gets its own relay (and so a fresh budget): once the limit
  // works, an IP over it is refused for every authenticated request.
  let relay: RelayProcess
  let api: Api

  beforeEach(async () => {
    // Named explicitly: the test relays switch every limit off unless a test exercises it.
    relay = await startRelay({ RATE_LIMIT_AUTH_FAIL: '30/min' })
    api = new Api(relay.url)
  })

  afterEach(async () => {
    await relay.stop()
  })

  const randomToken = () => b64u(randomBytes(32))

  /** A WebSocket upgrade with `token`; resolves with the HTTP status of the answer (101 if accepted). */
  const upgrade = (token: string) => new Promise<number>((resolve, reject) => {
    const req = request(`${relay.url}/v1/ws`, {
      headers: {
        'connection': 'Upgrade',
        'upgrade': 'websocket',
        'sec-websocket-version': '13',
        'sec-websocket-key': randomBytes(16).toString('base64'),
        'x-auth-token': token,
      },
    })
    req.on('response', (res) => {
      res.resume()
      resolve(res.statusCode!)
    })
    req.on('upgrade', (res, socket) => {
      socket.destroy()
      resolve(res.statusCode!)
    })
    req.on('error', reject)
    req.end()
  })

  it('answers 429 over HTTP once an IP has failed 30 token checks', async () => {
    const statuses: number[] = []
    for (let i = 0; i < 31; i++) statuses.push((await api.get('/v1/accounts/me', { token: randomToken() })).status)
    expect(statuses.slice(0, 30)).toEqual(Array(30).fill(401))
    expect(statuses[30]).toBe(429)
  })

  it('refuses WebSocket upgrades with 429 once an IP has failed 30 token checks', async () => {
    const statuses: number[] = []
    for (let i = 0; i < 31; i++) statuses.push(await upgrade(randomToken()))
    expect(statuses.slice(0, 30)).toEqual(Array(30).fill(401))
    expect(statuses[30]).toBe(429)
  })
})

describe('PRIV-7 (unit): ephemeral ids carry no batch position', () => {
  it('does not give consecutive counters to the ephemeral ids of one batch', () => {
    const generator = new EnvelopeIdGenerator()
    const now = Date.now() + 10_000
    const counters = Array.from({ length: 6 }, () => idCounter(generator.next(now, true)))
    expect(counters).not.toEqual([0, 1, 2, 3, 4, 5])
  })
})

describe('deploy/nginx/us2us.conf', () => {
  it('PRIV-2: keeps upstream and client-body errors (which name client IP and request URI) out of the error log', () => {
    const conf = nginxConf()
    const quiet = /\berror_log\s+\S+\s+(crit|alert|emerg)\s*;/
    const outside = serverBlocks(conf).reduce((rest, block) => rest.replace(block, ''), conf)
    const blocks = serverBlocks(conf).map(block => ({
      server: /server_name\s+([^;]+);/.exec(block)?.[1] ?? '?',
      quiet: quiet.test(block) || quiet.test(outside),
    }))
    expect(blocks.length).toBeGreaterThan(0)
    expect(blocks.filter(block => !block.quiet).map(block => block.server)).toEqual([])
  })
})
