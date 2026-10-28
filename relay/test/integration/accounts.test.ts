import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, ecKey, register, registerWithAccessKey, registrationBody, send, sha256, solvedPow, type TestAccount } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'

let relay: RelayProcess
let api: Api
let db: TestDb

beforeAll(async () => {
  relay = await startRelay()
  api = new Api(relay.url)
  db = await TestDb.connect()
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

describe('basics', () => {
  it('reports health and version', async () => {
    const { status, body, headers } = await api.get('/v1/health')
    expect(status).toBe(200)
    expect(body).toEqual({ ok: true, version: '0.1.0' })
    expect(headers.get('cache-control')).toBe('no-store')
  })

  it('answers unknown endpoints and wrong methods with the JSON error format', async () => {
    const unknown = await api.get('/v1/nothing/here')
    expect(unknown.status).toBe(404)
    expect(unknown.body).toEqual({ error: { code: 'not_found', message: expect.any(String) } })

    const wrongMethod = await api.post('/v1/health')
    expect(wrongMethod.status).toBe(405)
    expect(wrongMethod.body.error.code).toBe('bad_request')
    expect(wrongMethod.headers.get('allow')).toBe('GET')
  })

  it('has no push endpoint', async () => {
    const account = await register(api)
    const { status, body } = await api.put('/v1/push', { token: account.authToken, json: { platform: 'fcm', token: 'abc' } })
    expect(status).toBe(404)
    expect(body.error.code).toBe('not_found')
  })

  it('rejects requests without a valid auth token', async () => {
    for (const token of [undefined, 'not-base64!', b64u(randomBytes(31)), b64u(randomBytes(32))]) {
      const { status, body } = await api.get('/v1/accounts/me', { token })
      expect(status).toBe(401)
      expect(body.error.code).toBe('unauthorized')
    }
  })
})

describe('proof-of-work challenges', () => {
  it('issues register and message challenges', async () => {
    const register = await api.get('/v1/pow?purpose=register')
    expect(register.status).toBe(200)
    expect(register.body).toEqual({ token: expect.stringMatching(/^v2\.register\.-\.\d+\.4\.[\w-]+\.[\w-]+$/), difficulty: 4, expiresAt: expect.any(Number) })
    expect(register.body.expiresAt).toBeGreaterThan(Date.now())

    const to = '0f5b1d2c-8c55-4c39-9b7b-3a7f5e0c9d11'
    const message = await api.get(`/v1/pow?purpose=message&to=${to.toUpperCase()}`)
    expect(message.body.token).toContain(`.message.${to}.`)
  })

  it('rejects unknown purposes and a message challenge without recipient', async () => {
    expect((await api.get('/v1/pow')).status).toBe(400)
    expect((await api.get('/v1/pow?purpose=login')).status).toBe(400)
    expect((await api.get('/v1/pow?purpose=message&to=nope')).status).toBe(400)
  })
})

describe('registration', () => {
  it('creates an account and returns its id and token', async () => {
    const body = registrationBody()
    const pow = await solvedPow(api, 'purpose=register')
    const { status, body: created } = await api.post('/v1/accounts', { json: { ...body, pow } })
    expect(status).toBe(201)
    expect(created.accountId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(Buffer.from(created.authToken, 'base64url')).toHaveLength(32)

    const me = await api.get('/v1/accounts/me', { token: created.authToken })
    expect(me.body).toEqual({ accountId: created.accountId, usernameHash: body.usernameHash })

    // Only hashes of secrets are stored.
    const [row] = await db.query('SELECT auth_token_hash, last_seen FROM accounts WHERE id = ?', [uuidBytes(created.accountId)])
    expect(row!.auth_token_hash).toEqual(sha256(Buffer.from(created.authToken, 'base64url')))
    expect(await db.count('usernames WHERE username_hash = ?', [Buffer.from(body.usernameHash, 'base64url')])).toBe(1)
  })

  it('validates the request', async () => {
    const pow = await solvedPow(api, 'purpose=register')
    const invalid: Array<Record<string, unknown>> = [
      { usernameHash: b64u(randomBytes(31)) },
      { identityKey: b64u(Buffer.concat([Buffer.from([0x04]), randomBytes(32)])) },
      { identityKey: `${b64u(ecKey())}=` },
      { registrationId: 0 },
      { registrationId: 16381 },
      { signedPreKey: { keyId: 0, publicKey: b64u(ecKey()), signature: b64u(randomBytes(64)) } },
      { signedPreKey: { keyId: 1, publicKey: b64u(ecKey()), signature: b64u(randomBytes(63)) } },
      { kyberLastResortKey: { keyId: 1, publicKey: b64u(randomBytes(2049)), signature: b64u(randomBytes(64)) } },
      { oneTimePreKeys: Array.from({ length: 101 }, (_, i) => ({ keyId: i + 1, publicKey: b64u(ecKey()) })) },
      { oneTimePreKeys: [{ keyId: 16777216, publicKey: b64u(ecKey()) }] },
      { pow: { token: 'x', nonce: 1 } },
    ]
    for (const override of invalid) {
      const { status, body } = await api.post('/v1/accounts', { json: { ...registrationBody(), pow, ...override } })
      expect(status, JSON.stringify(override).slice(0, 80)).toBe(400)
      expect(body.error.code).toBe('bad_request')
    }
    expect((await api.post('/v1/accounts', { body: 'not json' })).status).toBe(400)
  })

  it('requires a valid, unused register proof-of-work', async () => {
    const challenge = await api.get('/v1/pow?purpose=register')
    const wrong = await api.post('/v1/accounts', { json: { ...registrationBody(), pow: { token: challenge.body.token, nonce: '18446744073709551616' } } })
    expect(wrong.status).toBe(403)
    expect(wrong.body.error.code).toBe('pow_invalid')

    const messagePow = await solvedPow(api, 'purpose=message&to=0f5b1d2c-8c55-4c39-9b7b-3a7f5e0c9d11')
    expect((await api.post('/v1/accounts', { json: { ...registrationBody(), pow: messagePow } })).body.error.code).toBe('pow_invalid')

    const tampered = await solvedPow(api, 'purpose=register')
    const forged = { ...tampered, token: tampered.token.replace(/\.\d+\./, '.9999999999.') }
    expect((await api.post('/v1/accounts', { json: { ...registrationBody(), pow: forged } })).body.error.code).toBe('pow_invalid')

    const pow = await solvedPow(api, 'purpose=register')
    expect((await api.post('/v1/accounts', { json: { ...registrationBody(), pow } })).status).toBe(201)
    const reused = await api.post('/v1/accounts', { json: { ...registrationBody(), pow } })
    expect(reused.status).toBe(403)
    expect(reused.body.error.code).toBe('pow_invalid')
  })

  it('refuses a username hash that is taken', async () => {
    const first = await register(api)
    const pow = await solvedPow(api, 'purpose=register')
    const { status, body } = await api.post('/v1/accounts', { json: { ...registrationBody({ usernameHash: first.usernameHash }), pow } })
    expect(status).toBe(409)
    expect(body.error.code).toBe('username_taken')
  })
})

describe('usernames', () => {
  it('changes the username and reserves the old one for its owner', async () => {
    const alice = await register(api)
    const bob = await register(api)
    const newHash = b64u(randomBytes(32))

    expect((await api.put('/v1/accounts/me/username', { token: alice.authToken, json: { usernameHash: newHash } })).status).toBe(204)
    expect((await api.get('/v1/accounts/me', { token: alice.authToken })).body.usernameHash).toBe(newHash)

    // The directory finds the new name; the held old one is not findable...
    expect((await api.get(`/v1/directory/${newHash}`, { token: bob.authToken })).body.accountId).toBe(alice.accountId)
    expect((await api.get(`/v1/directory/${alice.usernameHash}`, { token: bob.authToken })).status).toBe(404)
    // ...and cannot be taken by anyone else, by registration or by a change.
    const pow = await solvedPow(api, 'purpose=register')
    expect((await api.post('/v1/accounts', { json: { ...registrationBody({ usernameHash: alice.usernameHash }), pow } })).status).toBe(409)
    expect((await api.put('/v1/accounts/me/username', { token: bob.authToken, json: { usernameHash: alice.usernameHash } })).status).toBe(409)

    // Alice can take it back; the name she leaves is held in turn.
    expect((await api.put('/v1/accounts/me/username', { token: alice.authToken, json: { usernameHash: alice.usernameHash } })).status).toBe(204)
    expect((await api.get(`/v1/directory/${alice.usernameHash}`, { token: bob.authToken })).body.accountId).toBe(alice.accountId)
    const [hold] = await db.query('SELECT held_until FROM usernames WHERE username_hash = ?', [Buffer.from(newHash, 'base64url')])
    expect(Number(hold!.held_until)).toBeGreaterThan(Date.now() + 13 * 86_400_000)

    // Changing to the current name is a no-op; a name someone uses is taken.
    expect((await api.put('/v1/accounts/me/username', { token: alice.authToken, json: { usernameHash: alice.usernameHash } })).status).toBe(204)
    expect((await api.put('/v1/accounts/me/username', { token: alice.authToken, json: { usernameHash: bob.usernameHash } })).body.error.code).toBe('username_taken')
    expect((await api.put('/v1/accounts/me/username', { token: alice.authToken, json: { usernameHash: 'short' } })).status).toBe(400)
  })
})

describe('tokens and access keys', () => {
  it('rotates the auth token and invalidates the old one at once', async () => {
    const account = await register(api)
    const { status, body } = await api.post('/v1/accounts/me/token', { token: account.authToken })
    expect(status).toBe(200)
    expect(body.authToken).not.toBe(account.authToken)
    expect((await api.get('/v1/accounts/me', { token: account.authToken })).status).toBe(401)
    expect((await api.get('/v1/accounts/me', { token: body.authToken })).status).toBe(200)
  })

  it('rotates to a token the client chose, idempotently and only from the current token', async () => {
    const account = await register(api)
    const next = b64u(randomBytes(32))
    const rotated = await api.post('/v1/accounts/me/token', { token: account.authToken, json: { newAuthToken: next } })
    expect(rotated.status).toBe(204)
    expect(rotated.body).toHaveLength(0)
    expect((await api.get('/v1/accounts/me', { token: account.authToken })).status).toBe(401)
    expect((await api.get('/v1/accounts/me', { token: next })).status).toBe(200)
    const [row] = await db.query('SELECT auth_token_hash FROM accounts WHERE id = ?', [uuidBytes(account.accountId)])
    expect(Buffer.from(row!.auth_token_hash)).toEqual(sha256(Buffer.from(next, 'base64url')))

    // The response was lost: asking again with the new token changes nothing.
    expect((await api.post('/v1/accounts/me/token', { token: next, json: { newAuthToken: next } })).status).toBe(204)
    expect((await api.get('/v1/accounts/me', { token: next })).status).toBe(200)
    // The old token cannot rotate any more.
    expect((await api.post('/v1/accounts/me/token', { token: account.authToken, json: { newAuthToken: b64u(randomBytes(32)) } })).status).toBe(401)

    // Another account's token cannot be taken over, and a body without a token is not the old form.
    const other = await register(api)
    expect((await api.post('/v1/accounts/me/token', { token: next, json: { newAuthToken: other.authToken } })).status).toBe(400)
    expect((await api.post('/v1/accounts/me/token', { token: next, json: {} })).status).toBe(400)
    expect((await api.get('/v1/accounts/me', { token: next })).status).toBe(200)
    expect((await api.get('/v1/accounts/me', { token: other.authToken })).status).toBe(200)
  })

  it('lets exactly one of two concurrent rotations with the same token win', async () => {
    const account = await register(api)
    const tokens = [b64u(randomBytes(32)), b64u(randomBytes(32))]
    const statuses = await Promise.all(tokens.map(newAuthToken =>
      api.post('/v1/accounts/me/token', { token: account.authToken, json: { newAuthToken } }).then(r => r.status)))
    expect([...statuses].sort()).toEqual([204, 401])
    expect((await api.get('/v1/accounts/me', { token: tokens[statuses.indexOf(204)]! })).status).toBe(200)
    expect((await api.get('/v1/accounts/me', { token: tokens[statuses.indexOf(401)]! })).status).toBe(401)
  })

  it('sets and clears the access key', async () => {
    const recipient = await registerWithAccessKey(api)
    expect((await send(api, recipient, randomBytes(100))).status).toBe(202)

    expect((await api.put('/v1/accounts/me/access-key', { token: recipient.authToken, json: { accessKeyHash: null } })).status).toBe(204)
    const denied = await send(api, recipient, randomBytes(100))
    expect(denied.status).toBe(403)
    expect(denied.body.error.code).toBe('access_denied')

    expect((await api.put('/v1/accounts/me/access-key', { token: recipient.authToken, json: {} })).status).toBe(400)
    expect((await api.put('/v1/accounts/me/access-key', { token: recipient.authToken, json: { accessKeyHash: b64u(randomBytes(16)) } })).status).toBe(400)
  })
})

describe('lookups', () => {
  let alice: TestAccount
  let bob: TestAccount

  beforeAll(async () => {
    alice = await register(api)
    bob = await register(api)
  })

  it('returns identity keys by account id', async () => {
    const { status, body } = await api.get(`/v1/accounts/${alice.accountId}/identity`, { token: bob.authToken })
    expect(status).toBe(200)
    expect(body).toEqual({ accountId: alice.accountId, identityKey: alice.identityKey })
    expect((await api.get(`/v1/accounts/${alice.accountId.toUpperCase()}/identity`, { token: bob.authToken })).body.accountId).toBe(alice.accountId)
    expect((await api.get('/v1/accounts/7e0a3f0e-6b0c-4d38-8a8b-5e7f1c2d3b4a/identity', { token: bob.authToken })).status).toBe(404)
    expect((await api.get('/v1/accounts/not-a-uuid/identity', { token: bob.authToken })).status).toBe(400)
    expect((await api.get(`/v1/accounts/${alice.accountId}/identity`)).status).toBe(401)
  })

  it('looks up usernames by exact hash only', async () => {
    const { status, body } = await api.get(`/v1/directory/${alice.usernameHash}`, { token: bob.authToken })
    expect(status).toBe(200)
    expect(body).toEqual({ accountId: alice.accountId, identityKey: alice.identityKey })
    expect((await api.get(`/v1/directory/${b64u(randomBytes(32))}`, { token: bob.authToken })).status).toBe(404)
    expect((await api.get(`/v1/directory/${alice.usernameHash.slice(0, 20)}`, { token: bob.authToken })).status).toBe(400)
  })
})

describe('account deletion', () => {
  it('deletes the account and everything stored for it', async () => {
    const account = await registerWithAccessKey(api)
    const other = await register(api)
    const id = uuidBytes(account.accountId)
    await send(api, account, randomBytes(64))
    const upload = await api.post('/v1/media', { token: account.authToken, body: randomBytes(1000) })
    expect(upload.status).toBe(201)
    const mediaHex = Buffer.from(upload.body.mediaId, 'base64url').toString('hex')
    const blob = join(relay.mediaDir, mediaHex.slice(0, 2), mediaHex)
    expect(existsSync(blob)).toBe(true)

    expect((await api.delete('/v1/accounts/me', { token: account.authToken })).status).toBe(204)

    expect(existsSync(blob)).toBe(false)
    expect(existsSync(`${blob}.part`)).toBe(false)
    expect((await api.get('/v1/accounts/me', { token: account.authToken })).status).toBe(401)
    expect((await api.get(`/v1/directory/${account.usernameHash}`, { token: other.authToken })).status).toBe(404)
    expect((await api.get(`/v1/keys/${account.accountId}`, { token: other.authToken })).status).toBe(404)
    expect((await send(api, account, randomBytes(64))).status).toBe(404)
    expect((await api.get(`/v1/media/${upload.body.mediaId}`)).status).toBe(404)
    for (const table of ['accounts WHERE id = ?', 'usernames WHERE account_id = ?', 'one_time_pre_keys WHERE account_id = ?', 'envelopes WHERE recipient = ?', 'media WHERE uploader = ?']) {
      expect(await db.count(table, [id]), table).toBe(0)
    }

    // The username is free again.
    const pow = await solvedPow(api, 'purpose=register')
    expect((await api.post('/v1/accounts', { json: { ...registrationBody({ usernameHash: account.usernameHash }), pow } })).status).toBe(201)
  })
})
