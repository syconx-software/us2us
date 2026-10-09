import { randomBytes } from 'node:crypto'
import { request } from 'node:http'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, eventually, registerWithAccessKey, send, sendEphemeral, sleep } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'
import { TestSocket } from '../support/ws'

const ACK_TIMEOUT_MS = 400

let relay: RelayProcess
let api: Api
let db: TestDb

beforeAll(async () => {
  relay = await startRelay({ WS_ACK_TIMEOUT_MS: String(ACK_TIMEOUT_MS), WS_IDLE_TIMEOUT_MS: '1500', WS_MAX_FRAME_BYTES: '1024' })
  api = new Api(relay.url)
  db = await TestDb.connect()
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

const stored = (accountId: string) => db.count('envelopes WHERE recipient = ?', [uuidBytes(accountId)])

/** Attempts a raw upgrade and reports the HTTP status (101 on success). */
function upgradeStatus(path: string, headers: Record<string, string>): Promise<{ status: number, body: string }> {
  return new Promise((resolve, reject) => {
    const req = request(`${relay.url}${path}`, {
      headers: {
        'connection': 'Upgrade',
        'upgrade': 'websocket',
        'sec-websocket-key': randomBytes(16).toString('base64'),
        'sec-websocket-version': '13',
        ...headers,
      },
    })
    req.on('upgrade', (_res, socket) => {
      socket.destroy()
      resolve({ status: 101, body: '' })
    })
    req.on('response', (res) => {
      let body = ''
      res.on('data', (chunk: Buffer) => (body += chunk.toString()))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body }))
    })
    req.on('error', reject)
    req.end()
  })
}

describe('upgrade', () => {
  it('refuses missing and invalid tokens with HTTP 401 before upgrading', async () => {
    const missing = await upgradeStatus('/v1/ws', {})
    expect(missing.status).toBe(401)
    expect(JSON.parse(missing.body)).toEqual({ error: { code: 'unauthorized', message: expect.any(String) } })
    expect((await upgradeStatus('/v1/ws', { 'x-auth-token': b64u(randomBytes(32)) })).status).toBe(401)

    const account = await registerWithAccessKey(api)
    expect((await upgradeStatus('/v1/ws', { 'x-auth-token': account.authToken })).status).toBe(101)
  })

  it('refuses upgrades on every other path', async () => {
    const account = await registerWithAccessKey(api)
    for (const path of ['/v1/health', '/v1/unknown', '/en']) {
      expect((await upgradeStatus(path, { 'x-auth-token': account.authToken })).status, path).toBe(404)
    }
  })
})

describe('delivery', () => {
  it('sends the stored backlog oldest first, then queue_empty', async () => {
    const account = await registerWithAccessKey(api)
    const ids: string[] = []
    for (let i = 0; i < 3; i++) ids.push((await send(api, account, Buffer.from([i]))).body.id)

    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    expect(socket.frames.map(frame => frame.type)).toEqual(['message', 'message', 'message', 'queue_empty'])
    expect(socket.messages.map(frame => frame.id)).toEqual(ids)
    expect(socket.messages[0]).toEqual({ type: 'message', id: ids[0], serverTimestamp: expect.any(Number), envelope: b64u(Buffer.from([0])) })

    socket.ack(ids)
    await eventually(async () => await stored(account.accountId) === 0)
    await socket.close()
  })

  it('delivers to a connected recipient from memory; an acked envelope never touches the database', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)

    const envelope = randomBytes(200)
    const { body } = await send(api, account, envelope)
    const frame = await socket.waitFor(f => f.id === body.id)
    expect(frame.envelope).toBe(b64u(envelope))
    expect(await stored(account.accountId)).toBe(0)

    socket.ack([body.id])
    await sleep(ACK_TIMEOUT_MS * 2)
    expect(await stored(account.accountId)).toBe(0)
    expect((await api.get('/v1/messages', { token: account.authToken })).body.messages).toEqual([])
    await socket.close()
  })

  it('stores an envelope that is not acknowledged in time and re-sends it on the next connection', async () => {
    const account = await registerWithAccessKey(api)
    const first = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { body } = await send(api, account, randomBytes(32))
    await first.waitFor(f => f.id === body.id)
    expect(await stored(account.accountId)).toBe(0)

    await eventually(async () => await stored(account.accountId) === 1)
    await first.close()

    const second = await TestSocket.synced(relay.wsUrl, account.authToken)
    expect(second.messages.map(f => f.id)).toEqual([body.id])
    second.ack([body.id])
    await eventually(async () => await stored(account.accountId) === 0)
    await second.close()
  })

  it('stores unacknowledged envelopes as soon as the socket closes', async () => {
    // With an ack timeout of a minute, only the close can have stored it.
    const patient = await startRelay({ WS_ACK_TIMEOUT_MS: '60000' })
    try {
      const patientApi = new Api(patient.url)
      const account = await registerWithAccessKey(patientApi)
      const socket = await TestSocket.synced(patient.wsUrl, account.authToken)
      const { body } = await send(patientApi, account, randomBytes(32))
      await socket.waitFor(f => f.id === body.id)
      await socket.close()
      await eventually(async () => await stored(account.accountId) === 1)
    } finally {
      await patient.stop()
    }
  })

  it('accepts acks over HTTP for envelopes delivered over the socket', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { body } = await send(api, account, randomBytes(32))
    await socket.waitFor(f => f.id === body.id)
    expect((await api.post('/v1/messages/ack', { token: account.authToken, json: { ids: [body.id] } })).status).toBe(204)
    await sleep(ACK_TIMEOUT_MS * 2)
    expect(await stored(account.accountId)).toBe(0)
    await socket.close()
  })

  it('delivers ephemeral envelopes to connected recipients, never stores them, and drops those with a wrong key', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    const envelope = randomBytes(16)
    const { body } = await send(api, account, envelope, '?ephemeral=1')
    expect(body).toEqual({ id: expect.any(String), serverTimestamp: expect.any(Number) })
    expect((await socket.waitFor(f => f.id === body.id)).envelope).toBe(b64u(envelope))

    const wrong = await send(api, { ...account, accessKey: b64u(randomBytes(16)) }, randomBytes(16), '?ephemeral=1')
    expect(wrong.status).toBe(202)
    expect(Object.keys(wrong.body)).toEqual(Object.keys(body))

    // Acks for ephemeral ids are harmless; nothing was or will be stored.
    socket.ack([body.id, wrong.body.id])
    await sleep(ACK_TIMEOUT_MS * 2)
    expect(socket.messages.map(f => f.id)).toEqual([body.id])
    expect(await stored(account.accountId)).toBe(0)
    await socket.close()
  })

  it('fans an ephemeral batch out to the connected recipients whose access key matches', async () => {
    const [online, offline, guarded] = [await registerWithAccessKey(api), await registerWithAccessKey(api), await registerWithAccessKey(api)]
    const onlineSocket = await TestSocket.synced(relay.wsUrl, online.authToken)
    const guardedSocket = await TestSocket.synced(relay.wsUrl, guarded.authToken)
    const [forOnline, forOffline, forGuarded] = [randomBytes(30), randomBytes(31), randomBytes(32)]

    const response = await sendEphemeral(api, [
      { to: online.accountId, accessKey: online.accessKey, envelope: b64u(forOnline) },
      { to: offline.accountId, accessKey: offline.accessKey, envelope: b64u(forOffline) },
      { to: guarded.accountId, accessKey: b64u(randomBytes(16)), envelope: b64u(forGuarded) },
    ])
    expect(response.status).toBe(202)
    expect(response.body).toEqual({})

    const [frame] = await onlineSocket.waitForMessages(1)
    expect(frame).toEqual({ type: 'message', id: expect.any(String), serverTimestamp: expect.any(Number), envelope: b64u(forOnline) })
    await sleep(200)
    expect(guardedSocket.messages).toEqual([])
    for (const account of [online, offline, guarded]) expect(await stored(account.accountId)).toBe(0)
    await onlineSocket.close()
    await guardedSocket.close()
  })
})

describe('connection management', () => {
  it('closes the previous connection with 4001 and moves its unacknowledged envelopes to the new one', async () => {
    const account = await registerWithAccessKey(api)
    const first = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { body } = await send(api, account, randomBytes(32))
    await first.waitFor(f => f.id === body.id)

    const second = await TestSocket.synced(relay.wsUrl, account.authToken)
    expect(await first.closed).toEqual({ code: 4001, reason: 'replaced' })
    expect(second.messages.map(f => f.id)).toEqual([body.id])
    expect(await stored(account.accountId)).toBe(0)

    second.ack([body.id])
    await sleep(ACK_TIMEOUT_MS * 2)
    expect(await stored(account.accountId)).toBe(0)
    await second.close()
  })

  it('answers ping with pong', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    socket.send({ type: 'ping' })
    await socket.waitFor(f => f.type === 'pong')
    socket.send({ type: 'something-new' })
    socket.send({ type: 'ping' })
    await socket.waitFor(() => socket.frames.filter(f => f.type === 'pong').length === 2)
    await socket.close()
  })

  it('closes the socket on malformed and oversized frames', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    socket.sendRaw('{not json')
    expect((await socket.closed).code).toBe(1007)

    const other = await TestSocket.synced(relay.wsUrl, account.authToken)
    other.send({ type: 'ack', ids: ['short'] })
    expect((await other.closed).code).toBe(1007)

    const third = await TestSocket.synced(relay.wsUrl, account.authToken)
    third.sendRaw(JSON.stringify({ type: 'ping', padding: 'x'.repeat(2048) }))
    expect((await third.closed).code).toBe(1009)
  })

  it('drops sockets that stay silent past the idle timeout', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { code } = await socket.closed
    expect(code).toBe(1006)
  })

  it('closes the socket with 4401 when the token is rotated, keeping its envelopes', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { body } = await send(api, account, randomBytes(32))
    await socket.waitFor(f => f.id === body.id)

    const rotated = await api.post('/v1/accounts/me/token', { token: account.authToken })
    expect((await socket.closed).code).toBe(4401)
    await eventually(async () => await stored(account.accountId) === 1)

    const fresh = await TestSocket.synced(relay.wsUrl, rotated.body.authToken)
    expect(fresh.messages.map(f => f.id)).toEqual([body.id])
    await fresh.close()
  })

  it('closes the socket with 4401 and drops pending envelopes when the account is deleted', async () => {
    const account = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, account.authToken)
    const { body } = await send(api, account, randomBytes(32))
    await socket.waitFor(f => f.id === body.id)

    expect((await api.delete('/v1/accounts/me', { token: account.authToken })).status).toBe(204)
    expect((await socket.closed).code).toBe(4401)
    await sleep(ACK_TIMEOUT_MS * 2)
    expect(await stored(account.accountId)).toBe(0)
  })
})
