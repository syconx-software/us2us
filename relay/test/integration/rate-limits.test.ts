import { randomBytes } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, eventually, register, registerWithAccessKey, send, sendEphemeral, solvedPow } from '../support/client'
import { TestDb } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'
import { TestSocket } from '../support/ws'

let relay: RelayProcess
let api: Api

beforeAll(async () => {
  relay = await startRelay({ RATE_LIMIT_POW: '20/min', RATE_LIMIT_REGISTER: '4/hour' })
  api = new Api(relay.url)
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await relay.stop()
})

function expectLimited(response: { status: number, body: any, headers: Headers }) {
  expect(response.status).toBe(429)
  expect(response.body.error.code).toBe('rate_limited')
  expect(Number(response.headers.get('retry-after'))).toBeGreaterThanOrEqual(1)
}

describe('per IP', () => {
  it('limits registrations, including failed attempts', async () => {
    const accounts = [await register(api), await register(api), await register(api)]
    expect(accounts).toHaveLength(3)
    expect((await api.post('/v1/accounts', { json: {} })).status).toBe(400) // the fourth, invalid, still counts
    const pow = await solvedPow(api, 'purpose=register')
    expectLimited(await api.post('/v1/accounts', { json: { pow } }))
  })

  it('limits proof-of-work challenges', async () => {
    let response = await api.get('/v1/pow?purpose=register')
    while (response.status === 200) response = await api.get('/v1/pow?purpose=register')
    expectLimited(response)
  })
})

describe('per account', () => {
  it('limits directory lookups and bundle fetches of each account separately', async () => {
    const limited = await startRelay({ RATE_LIMIT_DIRECTORY: '2/hour', RATE_LIMIT_KEY_FETCH: '2/hour', RATE_LIMIT_USERNAME_CHANGES: '1/day' })
    try {
      const limitedApi = new Api(limited.url)
      const [a, b] = [await register(limitedApi), await register(limitedApi)]
      const lookup = (token: string) => limitedApi.get(`/v1/directory/${b.usernameHash}`, { token })
      expect((await lookup(a.authToken)).status).toBe(200)
      expect((await lookup(a.authToken)).status).toBe(200)
      expectLimited(await lookup(a.authToken))
      expect((await lookup(b.authToken)).status).toBe(200)

      const fetchBundle = (token: string) => limitedApi.get(`/v1/keys/${b.accountId}`, { token })
      expect((await fetchBundle(a.authToken)).status).toBe(200)
      expect((await fetchBundle(a.authToken)).status).toBe(200)
      expectLimited(await fetchBundle(a.authToken))

      const change = () => limitedApi.put('/v1/accounts/me/username', { token: a.authToken, json: { usernameHash: randomBytes(32).toString('base64url') } })
      expect((await change()).status).toBe(204)
      expectLimited(await change())
    } finally {
      await limited.stop()
    }
  })
})

describe('client address', () => {
  it('ignores X-Forwarded-For unless TRUST_PROXY is set', async () => {
    const strict = await startRelay({ RATE_LIMIT_POW: '1/min' })
    const proxied = await startRelay({ RATE_LIMIT_POW: '1/min', TRUST_PROXY: '1' })
    try {
      const pow = (target: RelayProcess, ip: string) => new Api(target.url).get('/v1/pow?purpose=register', { headers: { 'x-forwarded-for': ip } })
      expect((await pow(strict, '198.51.100.1')).status).toBe(200)
      expect((await pow(strict, '198.51.100.2')).status).toBe(429)

      expect((await pow(proxied, '198.51.100.1')).status).toBe(200)
      expect((await pow(proxied, '198.51.100.2')).status).toBe(200)
      expect((await pow(proxied, '198.51.100.1')).status).toBe(429)
      // The right-most address is the one the proxy saw; whatever the client prepends is ignored.
      expect((await pow(proxied, '203.0.113.7, 198.51.100.2')).status).toBe(429)
      // One IPv6 /64 is one client.
      expect((await pow(proxied, '2001:db8:1:2::1')).status).toBe(200)
      expect((await pow(proxied, '2001:db8:1:2:ffff::9')).status).toBe(429)
    } finally {
      await strict.stop()
      await proxied.stop()
    }
  })
})

describe('sends and sockets', () => {
  it('limits sealed sends per IP and WebSocket connects per IP', async () => {
    const limited = await startRelay({ RATE_LIMIT_SEND: '3/min', RATE_LIMIT_WS_CONNECT: '1/min' })
    try {
      const limitedApi = new Api(limited.url)
      const recipient = await registerWithAccessKey(limitedApi)
      for (let i = 0; i < 3; i++) expect((await send(limitedApi, recipient, randomBytes(8))).status).toBe(202)
      expectLimited(await send(limitedApi, recipient, randomBytes(8)))

      const socket = await TestSocket.synced(limited.wsUrl, recipient.authToken)
      await expect(TestSocket.open(limited.wsUrl, recipient.authToken)).rejects.toThrow()
      await socket.close()
    } finally {
      await limited.stop()
    }
  })

  it('charges ephemeral batches per envelope', async () => {
    const limited = await startRelay({ RATE_LIMIT_EPHEMERAL: '200/min' })
    try {
      const limitedApi = new Api(limited.url)
      const recipient = await registerWithAccessKey(limitedApi)
      const batch = (count: number) => sendEphemeral(limitedApi, Array.from({ length: count }, () => (
        { to: recipient.accountId, accessKey: recipient.accessKey, envelope: b64u(randomBytes(20)) }
      )))
      expect((await batch(150)).status).toBe(202)
      expectLimited(await batch(60))
    } finally {
      await limited.stop()
    }
  })
})

describe('requests in flight', () => {
  it('serves at most 64 requests of one account at once', async () => {
    const busy = await startRelay()
    const db = await TestDb.connect()
    try {
      const busyApi = new Api(busy.url)
      const account = await register(busyApi)
      // Cached token, so that the requests below go straight to their handler.
      expect((await busyApi.get('/v1/keys/status', { token: account.authToken })).status).toBe(200)
      // Key status requests read one_time_pre_keys; with the table locked they all wait.
      await db.query('LOCK TABLES `one_time_pre_keys` WRITE')
      const statuses: number[] = []
      const requests = Array.from({ length: 80 }, () =>
        busyApi.get('/v1/keys/status', { token: account.authToken }).then(r => statuses.push(r.status)))
      await eventually(() => statuses.length === 16, 5000)
      expect(statuses).toEqual(Array(16).fill(429))
      await db.query('UNLOCK TABLES')
      await Promise.all(requests)
      expect(statuses.filter(status => status === 200)).toHaveLength(64)
    } finally {
      await db.query('UNLOCK TABLES')
      await db.close()
      await busy.stop()
    }
  })
})
