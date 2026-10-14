import { randomBytes } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, eventually, register, registerWithAccessKey, send, sendEphemeral, solvedPow, type TestAccount } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'
import { TestSocket } from '../support/ws'

let relay: RelayProcess
let api: Api
let db: TestDb
let recipient: TestAccount & { accessKey: string }

beforeAll(async () => {
  relay = await startRelay({ ENVELOPE_MAX_BYTES: '4096' })
  api = new Api(relay.url)
  db = await TestDb.connect()
  recipient = await registerWithAccessKey(api)
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

const fetchAll = (account: TestAccount, query = '') => api.get(`/v1/messages${query}`, { token: account.authToken })
const ack = (account: TestAccount, ids: string[]) => api.post('/v1/messages/ack', { token: account.authToken, json: { ids } })

describe('sealed send', () => {
  it('accepts envelopes with the recipient access key', async () => {
    const envelope = randomBytes(300)
    const { status, body } = await send(api, recipient, envelope)
    expect(status).toBe(202)
    expect(body).toEqual({ id: expect.any(String), serverTimestamp: expect.any(Number) })
    expect(Buffer.from(body.id, 'base64url')).toHaveLength(16)

    const stored = await fetchAll(recipient)
    expect(stored.body.messages).toContainEqual({ id: body.id, serverTimestamp: body.serverTimestamp, envelope: b64u(envelope) })
    await ack(recipient, [body.id])
  })

  it('refuses senders without a valid access key or proof-of-work', async () => {
    const path = `/v1/messages/${recipient.accountId}`
    const cases: Array<Record<string, string>> = [{}, { 'x-access-key': b64u(randomBytes(16)) }, { 'x-access-key': 'garbage' }]
    for (const headers of cases) {
      const { status, body } = await api.put(path, { body: randomBytes(10), headers })
      expect(status).toBe(403)
      expect(body.error.code).toBe('access_denied')
    }
  })

  it('accepts a first message with a proof-of-work bound to the recipient, once', async () => {
    const path = `/v1/messages/${recipient.accountId}`
    const pow = await solvedPow(api, `purpose=message&to=${recipient.accountId}`)
    const header = { 'x-pow': `${pow.token}:${pow.nonce}` }
    expect((await api.put(path, { body: randomBytes(10), headers: header })).status).toBe(202)

    const reused = await api.put(path, { body: randomBytes(10), headers: header })
    expect(reused.status).toBe(403)
    expect(reused.body.error.code).toBe('pow_invalid')

    const other = await register(api)
    const forOther = await solvedPow(api, `purpose=message&to=${other.accountId}`)
    expect((await api.put(path, { body: randomBytes(10), headers: { 'x-pow': `${forOther.token}:${forOther.nonce}` } })).body.error.code).toBe('pow_invalid')
    expect((await api.put(path, { body: randomBytes(10), headers: { 'x-pow': 'no-separator' } })).body.error.code).toBe('pow_invalid')

    // A wrong access key falls back to the proof-of-work.
    const fresh = await solvedPow(api, `purpose=message&to=${recipient.accountId}`)
    const both = await api.put(path, { body: randomBytes(10), headers: { 'x-access-key': b64u(randomBytes(16)), 'x-pow': `${fresh.token}:${fresh.nonce}` } })
    expect(both.status).toBe(202)

    const { body } = await fetchAll(recipient)
    await ack(recipient, body.messages.map((m: { id: string }) => m.id))
  })

  it('validates recipient and body', async () => {
    const headers = { 'x-access-key': recipient.accessKey }
    expect((await api.put('/v1/messages/7e0a3f0e-6b0c-4d38-8a8b-5e7f1c2d3b4a', { body: randomBytes(10), headers })).status).toBe(404)
    expect((await api.put('/v1/messages/not-a-uuid', { body: randomBytes(10), headers })).status).toBe(400)
    expect((await api.put(`/v1/messages/${recipient.accountId}`, { body: new Uint8Array(), headers })).status).toBe(400)

    const tooLarge = await api.put(`/v1/messages/${recipient.accountId}`, { body: randomBytes(4097), headers })
    expect(tooLarge.status).toBe(413)
    expect(tooLarge.body.error.code).toBe('payload_too_large')
    expect((await send(api, recipient, randomBytes(4096))).status).toBe(202)
    await ack(recipient, (await fetchAll(recipient)).body.messages.map((m: { id: string }) => m.id))
  })

  it('drops ephemeral envelopes for an offline recipient without storing them', async () => {
    const { status, body } = await send(api, recipient, randomBytes(50), '?ephemeral=1')
    expect(status).toBe(202)
    expect(body).toEqual({ id: expect.any(String), serverTimestamp: expect.any(Number) })
    expect(await db.count('envelopes WHERE recipient = ?', [uuidBytes(recipient.accountId)])).toBe(0)
  })

  it('requires an access key for ephemeral sends and hides whether it was right', async () => {
    const path = `/v1/messages/${recipient.accountId}?ephemeral=1`
    const pow = await solvedPow(api, `purpose=message&to=${recipient.accountId}`)
    const refused: Array<Record<string, string>> = [{}, { 'x-pow': `${pow.token}:${pow.nonce}` }, { 'x-access-key': 'garbage' }]
    for (const headers of refused) {
      const { status, body } = await api.put(path, { body: randomBytes(10), headers })
      expect(status, JSON.stringify(headers)).toBe(403)
      expect(body.error.code).toBe('access_denied')
    }
    // A wrong key is answered exactly like the right one, and nothing is stored.
    const wrong = await api.put(path, { body: randomBytes(10), headers: { 'x-access-key': b64u(randomBytes(16)) } })
    expect(wrong.status).toBe(202)
    expect(wrong.body).toEqual({ id: expect.any(String), serverTimestamp: expect.any(Number) })
    expect(await db.count('envelopes WHERE recipient = ?', [uuidBytes(recipient.accountId)])).toBe(0)
  })
})

describe('ephemeral batch', () => {
  const item = (to: TestAccount & { accessKey: string }, envelope: Uint8Array = randomBytes(40)) =>
    ({ to: to.accountId, accessKey: to.accessKey, envelope: b64u(envelope) })

  it('always answers 202 {} and stores nothing', async () => {
    const other = await registerWithAccessKey(api)
    const unknown = { accountId: '7e0a3f0e-6b0c-4d38-8a8b-5e7f1c2d3b4a', accessKey: b64u(randomBytes(16)) }
    const wrongKey = { ...other, accessKey: b64u(randomBytes(16)) }
    const response = await sendEphemeral(api, [item(recipient), item(other), item(wrongKey), { to: unknown.accountId, accessKey: unknown.accessKey, envelope: b64u(randomBytes(4)) }])
    expect(response.status).toBe(202)
    expect(response.body).toEqual({})
    expect(await db.count('envelopes WHERE recipient IN (?, ?)', [uuidBytes(recipient.accountId), uuidBytes(other.accountId)])).toBe(0)
  })

  it('validates the batch', async () => {
    const valid = item(recipient)
    const invalid: unknown[] = [
      {},
      { messages: [] },
      { messages: Array.from({ length: 201 }, () => valid) },
      { messages: [{ ...valid, to: 'nope' }] },
      { messages: [{ ...valid, accessKey: b64u(randomBytes(15)) }] },
      { messages: [{ ...valid, envelope: b64u(randomBytes(4097)) }] },
      { messages: [{ ...valid, envelope: '' }] },
    ]
    for (const json of invalid) {
      const { status, body } = await api.post('/v1/messages/ephemeral', { json })
      expect(status, JSON.stringify(json).slice(0, 80)).toBe(400)
      expect(body.error.code).toBe('bad_request')
    }
    expect((await api.post('/v1/messages/ephemeral', { body: 'not json', headers: { 'content-type': 'application/json' } })).status).toBe(400)
    expect((await sendEphemeral(api, Array.from({ length: 200 }, () => item(recipient)))).status).toBe(202)
    // 4 KiB envelopes fit, as long as the whole body stays within 1 MiB.
    expect((await sendEphemeral(api, Array.from({ length: 150 }, () => item(recipient, randomBytes(4096))))).status).toBe(202)

    const tooLarge = await api.post('/v1/messages/ephemeral', { body: Buffer.alloc(1024 * 1024 + 1, 0x20), headers: { 'content-type': 'application/json' } })
    expect(tooLarge.status).toBe(413)
    expect(tooLarge.body.error.code).toBe('payload_too_large')
    expect((await api.put('/v1/messages/ephemeral', { json: { messages: [valid] } })).status).toBe(405)
  })
})

describe('mailbox', () => {
  it('returns envelopes in arrival order with paging, and deletes them on ack', async () => {
    const account = await registerWithAccessKey(api)
    const sent: string[] = []
    for (let i = 0; i < 7; i++) sent.push((await send(api, account, Buffer.from([i]))).body.id)

    const page = await fetchAll(account, '?limit=5')
    expect(page.body.more).toBe(true)
    expect(page.body.messages.map((m: { id: string }) => m.id)).toEqual(sent.slice(0, 5))
    expect(page.body.messages.map((m: { envelope: string }) => Buffer.from(m.envelope, 'base64url')[0])).toEqual([0, 1, 2, 3, 4])
    const timestamps = page.body.messages.map((m: { serverTimestamp: number }) => m.serverTimestamp)
    expect([...timestamps].sort((a, b) => a - b)).toEqual(timestamps)

    expect((await ack(account, sent.slice(0, 5))).status).toBe(204)
    const rest = await fetchAll(account)
    expect(rest.body).toEqual({ messages: expect.any(Array), more: false })
    expect(rest.body.messages.map((m: { id: string }) => m.id)).toEqual(sent.slice(5))

    // Acking unknown or already acked ids is harmless.
    expect((await ack(account, [...sent, b64u(randomBytes(16))])).status).toBe(204)
    expect((await fetchAll(account)).body.messages).toEqual([])
  })

  it('keeps mailboxes apart', async () => {
    const a = await registerWithAccessKey(api)
    const b = await registerWithAccessKey(api)
    const id = (await send(api, a, randomBytes(8))).body.id
    expect((await fetchAll(b)).body.messages).toEqual([])
    // b cannot delete a's envelope by acking its id.
    await ack(b, [id])
    expect((await fetchAll(a)).body.messages).toHaveLength(1)
  })

  it('validates fetch and ack requests', async () => {
    for (const query of ['?limit=0', '?limit=501', '?limit=abc']) {
      expect((await fetchAll(recipient, query)).status).toBe(400)
    }
    expect((await fetchAll(recipient, '?limit=500')).status).toBe(200)
    expect((await ack(recipient, ['short'])).status).toBe(400)
    expect((await ack(recipient, Array.from({ length: 501 }, () => b64u(randomBytes(16))))).status).toBe(400)
    expect((await api.post('/v1/messages/ack', { token: recipient.authToken, json: { ids: 'x' } })).status).toBe(400)
    expect((await api.get('/v1/messages')).status).toBe(401)
  })
})

describe('storage limits', () => {
  it('caps what one mailbox stores, and frees the room again as envelopes are acknowledged', async () => {
    // 64 KiB per mailbox, 4 KiB envelopes: 16 fit.
    const limited = await startRelay({ ENVELOPE_MAX_BYTES: '4096', MAILBOX_MAX_BYTES: '65536' })
    try {
      const limitedApi = new Api(limited.url)
      const account = await registerWithAccessKey(limitedApi)
      const other = await registerWithAccessKey(limitedApi)
      for (let i = 0; i < 16; i++) expect((await send(limitedApi, account, randomBytes(4096))).status).toBe(202)
      const full = await send(limitedApi, account, randomBytes(4096))
      expect(full.status).toBe(429)
      expect(full.body.error.code).toBe('rate_limited')
      expect(Number(full.headers.get('retry-after'))).toBeGreaterThan(0)
      expect((await send(limitedApi, other, randomBytes(4096))).status).toBe(202)

      const stored = await limitedApi.get('/v1/messages', { token: account.authToken })
      expect(stored.body.messages).toHaveLength(16)
      const ids = stored.body.messages.map((m: { id: string }) => m.id)
      expect((await limitedApi.post('/v1/messages/ack', { token: account.authToken, json: { ids: ids.slice(0, 4) } })).status).toBe(204)
      for (let i = 0; i < 4; i++) expect((await send(limitedApi, account, randomBytes(4096))).status).toBe(202)
      expect((await send(limitedApi, account, randomBytes(4096))).status).toBe(429)
    } finally {
      await limited.stop()
    }
  })

  it('counts envelopes held for a connected recipient that does not acknowledge, and refuses it like an offline one', async () => {
    // A long ack timeout: without the count, the connected recipient would take envelopes without limit.
    const limited = await startRelay({ ENVELOPE_MAX_BYTES: '4096', MAILBOX_MAX_BYTES: '65536', WS_ACK_TIMEOUT_MS: '60000' })
    try {
      const limitedApi = new Api(limited.url)
      const online = await registerWithAccessKey(limitedApi)
      const offline = await registerWithAccessKey(limitedApi)
      const socket = await TestSocket.synced(limited.wsUrl, online.authToken)
      for (let i = 0; i < 16; i++) {
        expect((await send(limitedApi, online, randomBytes(4096))).status).toBe(202)
        expect((await send(limitedApi, offline, randomBytes(4096))).status).toBe(202)
      }
      await socket.waitForMessages(16)
      const toOnline = await send(limitedApi, online, randomBytes(4096))
      const toOffline = await send(limitedApi, offline, randomBytes(4096))
      expect(toOnline.status).toBe(429)
      expect(toOffline.status).toBe(429)
      expect(toOnline.body).toEqual(toOffline.body)
      expect(toOnline.headers.get('retry-after')).toBe(toOffline.headers.get('retry-after'))

      // Closing the socket stores them; the mailbox stays full.
      await socket.close()
      await eventually(async () => await db.count('envelopes WHERE recipient = ?', [uuidBytes(online.accountId)]) === 16)
      expect((await send(limitedApi, online, randomBytes(4096))).status).toBe(429)
    } finally {
      await limited.stop()
    }
  })

  it('counts envelopes stored before a restart', async () => {
    const limits = { ENVELOPE_MAX_BYTES: '4096', MAILBOX_MAX_BYTES: '65536' }
    const before = await startRelay(limits)
    const beforeApi = new Api(before.url)
    const account = await registerWithAccessKey(beforeApi)
    try {
      for (let i = 0; i < 16; i++) expect((await send(beforeApi, account, randomBytes(4096))).status).toBe(202)
    } finally {
      await before.stop()
    }

    const after = await startRelay(limits)
    try {
      const afterApi = new Api(after.url)
      expect((await send(afterApi, account, randomBytes(4096))).status).toBe(429)
      const stored = await afterApi.get('/v1/messages', { token: account.authToken })
      expect(stored.body.messages).toHaveLength(16)
      const ids = stored.body.messages.map((m: { id: string }) => m.id)
      expect((await afterApi.post('/v1/messages/ack', { token: account.authToken, json: { ids: ids.slice(0, 4) } })).status).toBe(204)
      for (let i = 0; i < 4; i++) expect((await send(afterApi, account, randomBytes(4096))).status).toBe(202)
      expect((await send(afterApi, account, randomBytes(4096))).status).toBe(429)
    } finally {
      await after.stop()
    }
  })

  it('refuses media and stored envelopes, but not ephemeral ones, while the disk is below its floor', async () => {
    const full = await startRelay({ DISK_MIN_FREE_MB: String(16 * 1024 * 1024) })
    try {
      const fullApi = new Api(full.url)
      const account = await registerWithAccessKey(fullApi)
      const blob = randomBytes(100)
      const upload = await fullApi.post('/v1/media', { token: account.authToken, body: blob })
      expect(upload.status).toBe(503)
      expect(upload.body.error.code).toBe('internal')
      expect(Number(upload.headers.get('retry-after'))).toBeGreaterThan(0)
      expect((await send(fullApi, account, randomBytes(100))).status).toBe(503)
      expect((await send(fullApi, account, randomBytes(100), '?ephemeral=1')).status).toBe(202)
    } finally {
      await full.stop()
    }
  })
})
