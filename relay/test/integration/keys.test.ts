import { randomBytes } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, ecKey, kemKey, kyberKeys, oneTimeKeys, register, signature, type TestAccount } from '../support/client'
import { startRelay, type RelayProcess } from '../support/relay'

let relay: RelayProcess
let api: Api
let fetcher: TestAccount

beforeAll(async () => {
  relay = await startRelay()
  api = new Api(relay.url)
  fetcher = await register(api)
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await relay.stop()
})

const status = (account: TestAccount) => api.get('/v1/keys/status', { token: account.authToken }).then(response => response.body)
const bundle = (account: TestAccount) => api.get(`/v1/keys/${account.accountId}`, { token: fetcher.authToken })

describe('pre-key upload', () => {
  it('reports the keys stored at registration', async () => {
    const account = await register(api)
    expect(await status(account)).toEqual({ oneTimePreKeys: 5, kyberOneTimePreKeys: 5, signedPreKeyId: 1, kyberLastResortKeyId: 1 })
  })

  it('appends one-time keys, ignores known key ids and keeps at most 200 per kind', async () => {
    const account = await register(api, { oneTimePreKeys: [], kyberOneTimePreKeys: [] })
    const upload = (json: unknown) => api.put('/v1/keys', { token: account.authToken, json })

    expect((await upload({ oneTimePreKeys: oneTimeKeys(1, 100), kyberOneTimePreKeys: kyberKeys(1, 60) })).status).toBe(204)
    // Ids 51..149: 50 known, 49 new; the repeated id 51 at the end is ignored too.
    expect((await upload({ oneTimePreKeys: [...oneTimeKeys(51, 99), ...oneTimeKeys(51, 1)] })).status).toBe(204)
    expect(await status(account)).toMatchObject({ oneTimePreKeys: 149, kyberOneTimePreKeys: 60 })

    expect((await upload({ oneTimePreKeys: oneTimeKeys(1000, 100), kyberOneTimePreKeys: kyberKeys(1000, 100) })).status).toBe(204)
    expect((await upload({ kyberOneTimePreKeys: kyberKeys(2000, 100) })).status).toBe(204)
    expect(await status(account)).toMatchObject({ oneTimePreKeys: 200, kyberOneTimePreKeys: 200 })
  })

  it('replaces signed and last-resort keys, and everything with replaceAll', async () => {
    const account = await register(api)
    const response = await api.put('/v1/keys', {
      token: account.authToken,
      json: {
        replaceAll: true,
        signedPreKey: { keyId: 7, publicKey: b64u(ecKey()), signature: b64u(signature()) },
        kyberLastResortKey: { keyId: 8, publicKey: b64u(kemKey()), signature: b64u(signature()) },
        oneTimePreKeys: oneTimeKeys(500, 3),
      },
    })
    expect(response.status).toBe(204)
    expect(await status(account)).toEqual({ oneTimePreKeys: 3, kyberOneTimePreKeys: 0, signedPreKeyId: 7, kyberLastResortKeyId: 8 })
    expect((await api.put('/v1/keys', { token: account.authToken, json: {} })).status).toBe(204)
  })

  it('validates uploads', async () => {
    const account = await register(api)
    const invalid = [
      { replaceAll: 'yes' },
      { oneTimePreKeys: oneTimeKeys(1, 101) },
      { oneTimePreKeys: [{ keyId: 1, publicKey: b64u(randomBytes(33)).replace(/^./, 'A') }] },
      { kyberOneTimePreKeys: [{ keyId: 1, publicKey: b64u(kemKey()) }] },
      { signedPreKey: { keyId: 1, publicKey: b64u(ecKey()) } },
    ]
    for (const json of invalid) {
      expect((await api.put('/v1/keys', { token: account.authToken, json })).status, JSON.stringify(json).slice(0, 60)).toBe(400)
    }
    expect((await api.put('/v1/keys', { json: {} })).status).toBe(401)
  })
})

describe('bundle fetch', () => {
  it('hands out one EC and one Kyber one-time key per fetch', async () => {
    const account = await register(api, { oneTimePreKeys: oneTimeKeys(10, 2), kyberOneTimePreKeys: kyberKeys(20, 1) })
    const first = await bundle(account)
    expect(first.status).toBe(200)
    expect(first.body).toEqual({
      accountId: account.accountId,
      deviceId: 1,
      registrationId: 1234,
      identityKey: account.identityKey,
      signedPreKey: { keyId: 1, publicKey: expect.any(String), signature: expect.any(String) },
      kyberPreKey: { keyId: 20, publicKey: expect.any(String), signature: expect.any(String) },
      oneTimePreKey: { keyId: expect.any(Number), publicKey: expect.any(String) },
    })
    expect(Buffer.from(first.body.kyberPreKey.publicKey, 'base64url')).toHaveLength(1569)

    // Kyber one-time keys are gone: the last-resort key is returned and kept.
    const second = await bundle(account)
    expect(second.body.kyberPreKey.keyId).toBe(1)
    expect([first.body.oneTimePreKey.keyId, second.body.oneTimePreKey.keyId].sort()).toEqual([10, 11])

    const third = await bundle(account)
    expect(third.body.kyberPreKey.keyId).toBe(1)
    expect(third.body.oneTimePreKey).toBeNull()
    expect(await status(account)).toMatchObject({ oneTimePreKeys: 0, kyberOneTimePreKeys: 0 })
  })

  it('never hands out a one-time key twice under concurrent fetches', async () => {
    const account = await register(api, { oneTimePreKeys: oneTimeKeys(1, 30), kyberOneTimePreKeys: kyberKeys(100, 30) })
    const responses = await Promise.all(Array.from({ length: 60 }, () => bundle(account)))
    expect(responses.every(response => response.status === 200)).toBe(true)

    const ecIds = responses.map(r => r.body.oneTimePreKey?.keyId).filter((id): id is number => id !== undefined)
    expect(ecIds).toHaveLength(30)
    expect(new Set(ecIds).size).toBe(30)
    expect(responses.filter(r => r.body.oneTimePreKey === null)).toHaveLength(30)

    const kyberIds = responses.map(r => r.body.kyberPreKey.keyId).filter(id => id !== 1)
    expect(kyberIds).toHaveLength(30)
    expect(new Set(kyberIds).size).toBe(30)
    expect(await status(account)).toMatchObject({ oneTimePreKeys: 0, kyberOneTimePreKeys: 0 })
  })

  it('hands out one-time keys in random order, so that ids do not count the fetches of others', async () => {
    const account = await register(api, { oneTimePreKeys: oneTimeKeys(1, 40), kyberOneTimePreKeys: kyberKeys(1, 40) })
    const ec: number[] = []
    const kyber: number[] = []
    for (let i = 0; i < 40; i++) {
      const { body } = await bundle(account)
      ec.push(body.oneTimePreKey.keyId)
      kyber.push(body.kyberPreKey.keyId)
    }
    const ascending = (list: number[]) => list.every((id, i) => i === 0 || id > list[i - 1]!)
    for (const list of [ec, kyber]) {
      expect(ascending(list)).toBe(false)
      expect([...list].sort((a, b) => a - b)).toEqual(Array.from({ length: 40 }, (_, i) => i + 1))
    }
  })

  it('answers 404 for unknown accounts and 400 for malformed ids', async () => {
    expect((await api.get('/v1/keys/7e0a3f0e-6b0c-4d38-8a8b-5e7f1c2d3b4a', { token: fetcher.authToken })).status).toBe(404)
    expect((await api.get('/v1/keys/12345', { token: fetcher.authToken })).status).toBe(400)
    expect((await api.get(`/v1/keys/${fetcher.accountId}`)).status).toBe(401)
  })
})
