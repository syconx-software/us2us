import { randomBytes, randomUUID } from 'node:crypto'
import { request } from 'node:http'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, eventually, register, sleep, type TestAccount } from '../support/client'
import { AVATAR_SIZES, VERSION_MAX, avatarRecord } from '../support/avatar'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'

/*
 * Avatar records for search (PROTOCOL.md §4.12): the relay stores one opaque
 * record per account, refuses writes whose version is not above the
 * account's highest, and drops the record with a username change and the
 * row with the account.
 */

const DAY = 86_400_000

let relay: RelayProcess
let api: Api
let db: TestDb
let searcher: TestAccount

beforeAll(async () => {
  relay = await startRelay()
  api = new Api(relay.url)
  db = await TestDb.connect()
  searcher = await register(api)
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

const put = (account: TestAccount, record: Uint8Array, target = api) =>
  target.put('/v1/accounts/me/avatar', { token: account.authToken, body: record })
const remove = (account: TestAccount, version: bigint | number | string, target = api) =>
  target.delete('/v1/accounts/me/avatar', { token: account.authToken, headers: { 'x-avatar-version': String(version) } })
const fetchAvatar = (owner: string, headers: Record<string, string> = {}, token = searcher.authToken, target = api) =>
  target.get(`/v1/accounts/${owner}/avatar`, { token, headers })

/** The stored row, with the version as an exact decimal. */
async function row(account: TestAccount): Promise<{ highest: string, length: number | null } | undefined> {
  const [found] = await db.query<{ highest: string, length: number | null }>(
    'SELECT CAST(highest_version AS CHAR) AS highest, LENGTH(record) AS length FROM avatars WHERE account_id = ?',
    [uuidBytes(account.accountId)],
  )
  return found && { highest: found.highest, length: found.length === null ? null : Number(found.length) }
}

describe('schema', () => {
  it('has the avatars table from the migration', async () => {
    const columns = await db.query<{ name: string, type: string, nullable: string }>(
      `SELECT column_name AS name, column_type AS type, is_nullable AS nullable FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'avatars' ORDER BY ordinal_position`,
    )
    expect(columns).toEqual([
      { name: 'account_id', type: 'binary(16)', nullable: 'NO' },
      { name: 'highest_version', type: 'bigint unsigned', nullable: 'NO' },
      { name: 'record', type: 'blob', nullable: 'YES' },
    ])
  })
})

describe('store and fetch', () => {
  it('stores a record of every size and hands it out byte for byte', async () => {
    const owner = await register(api)
    let version = 1
    for (const size of AVATAR_SIZES) {
      const record = avatarRecord(version, size)
      const stored = await put(owner, record)
      expect(stored.status, String(size)).toBe(204)
      expect(stored.headers.get('etag')).toBe(`"${version}"`)

      const fetched = await fetchAvatar(owner.accountId)
      expect(fetched.status).toBe(200)
      expect(Buffer.compare(fetched.body, record)).toBe(0)
      expect(fetched.headers.get('content-type')).toBe('application/octet-stream')
      expect(fetched.headers.get('content-length')).toBe(String(size))
      expect(fetched.headers.get('etag')).toBe(`"${version}"`)
      expect(fetched.headers.get('cache-control')).toBe('no-store')
      expect(await row(owner)).toEqual({ highest: String(version), length: size })
      version++
    }
    // The owner may fetch its own record, by any case of its id.
    expect((await fetchAvatar(owner.accountId.toUpperCase(), {}, owner.authToken)).status).toBe(200)
  })

  it('answers If-None-Match naming the stored version with an empty 304', async () => {
    const owner = await register(api)
    const record = avatarRecord(2)
    await put(owner, record)
    const notModified = await fetchAvatar(owner.accountId, { 'if-none-match': '"2"' })
    expect(notModified.status).toBe(304)
    expect(notModified.body).toHaveLength(0)
    expect(notModified.headers.get('etag')).toBe('"2"')
    for (const value of ['"1"', '*', 'W/"02"']) {
      expect((await fetchAvatar(owner.accountId, { 'if-none-match': value })).status, value).toBe(200)
    }
    // No Range support: the whole record, always.
    const ranged = await fetchAvatar(owner.accountId, { range: 'bytes=0-10' })
    expect(ranged.status).toBe(200)
    expect(Buffer.compare(ranged.body, record)).toBe(0)
  })

  it('keeps versions beyond 2^53 exact, up to 2^63 - 1', async () => {
    const owner = await register(api)
    // Odd values above 2^53 have no exact double: a lossy read would round them up.
    const big = 2n ** 53n + 1n
    expect((await put(owner, avatarRecord(big))).status).toBe(204)
    expect((await put(owner, avatarRecord(big + 2n))).status).toBe(204)
    expect((await put(owner, avatarRecord(big + 2n))).headers.get('x-avatar-version')).toBe('9007199254740995')
    expect((await fetchAvatar(owner.accountId)).headers.get('etag')).toBe('"9007199254740995"')
    expect((await put(owner, avatarRecord(big + 3n))).status).toBe(204)

    expect((await put(owner, avatarRecord(VERSION_MAX))).status).toBe(204)
    expect(await row(owner)).toEqual({ highest: String(VERSION_MAX), length: 8192 })
    const full = await put(owner, avatarRecord(VERSION_MAX))
    expect(full.status).toBe(409)
    expect(full.headers.get('x-avatar-version')).toBe('9223372036854775807')
    expect((await remove(owner, VERSION_MAX)).status).toBe(409)
  })
})

describe('validation', () => {
  it('refuses lengths other than the four, a body over 49152 bytes with 413', async () => {
    const owner = await register(api)
    expect((await put(owner, avatarRecord(1, 8191))).status).toBe(400)
    expect((await put(owner, avatarRecord(1, 8193))).status).toBe(400)
    expect((await put(owner, new Uint8Array())).status).toBe(400)
    const tooLarge = await put(owner, avatarRecord(1, 49153))
    expect(tooLarge.status).toBe(413)
    expect(tooLarge.body.error.code).toBe('payload_too_large')
    expect((await put(owner, avatarRecord(1, 49152))).status).toBe(204)
  })

  it('answers 413 for a declared length over the limit without waiting for the body', async () => {
    const owner = await register(api)
    const status = await new Promise<number | undefined>((resolve, reject) => {
      const req = request(`${relay.url}/v1/accounts/me/avatar`, {
        method: 'PUT',
        headers: { 'x-auth-token': owner.authToken, 'content-length': String(10 * 1024 * 1024), 'content-type': 'application/octet-stream' },
      }, (res) => {
        res.resume()
        req.destroy()
        resolve(res.statusCode)
      })
      req.on('error', reject)
      req.flushHeaders()
    })
    expect(status).toBe(413)
    expect(await row(owner)).toBeUndefined()
  })

  it('requires a Content-Length', async () => {
    const owner = await register(api)
    const record = avatarRecord(1)
    const chunked = await fetch(`${relay.url}/v1/accounts/me/avatar`, {
      method: 'PUT',
      headers: { 'x-auth-token': owner.authToken },
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(record)
          controller.close()
        },
      }),
      duplex: 'half',
    } as RequestInit)
    expect(chunked.status).toBe(400)
    expect(await row(owner)).toBeUndefined()
  })

  it('refuses another magic or format byte and versions 0 and 2^63', async () => {
    const owner = await register(api)
    for (const record of [
      avatarRecord(1, 8192, { magic: 'BRPX' }),
      avatarRecord(1, 8192, { format: 0x02 }),
      avatarRecord(0),
      avatarRecord(2n ** 63n),
    ]) {
      const response = await put(owner, record)
      expect(response.status).toBe(400)
      expect(response.body.error.code).toBe('bad_request')
    }
    expect(await row(owner)).toBeUndefined()
  })

  it('requires a decimal X-Avatar-Version on delete', async () => {
    const owner = await register(api)
    expect((await api.delete('/v1/accounts/me/avatar', { token: owner.authToken })).status).toBe(400)
    for (const value of ['0', '-3', 'x', '9223372036854775808']) expect((await remove(owner, value)).status, value).toBe(400)
    expect(await row(owner)).toBeUndefined()
  })

  it('answers other methods with 405, a malformed id with 400 and a missing token with 401', async () => {
    const owner = await register(api)
    const own = await api.get('/v1/accounts/me/avatar', { token: owner.authToken })
    expect(own.status).toBe(405)
    expect(own.headers.get('allow')).toBe('PUT, DELETE')
    const other = await api.put(`/v1/accounts/${owner.accountId}/avatar`, { token: owner.authToken, body: avatarRecord(1) })
    expect(other.status).toBe(405)
    expect(other.headers.get('allow')).toBe('GET')
    expect((await fetchAvatar('not-a-uuid')).status).toBe(400)
    expect((await api.get(`/v1/accounts/${owner.accountId}/avatar`)).status).toBe(401)
    expect((await api.put('/v1/accounts/me/avatar', { body: avatarRecord(1) })).status).toBe(401)
    expect((await api.delete('/v1/accounts/me/avatar', { headers: { 'x-avatar-version': '1' } })).status).toBe(401)
  })
})

describe('versions', () => {
  it('refuses a write that is not above the highest version with 409 and X-Avatar-Version', async () => {
    const owner = await register(api)
    const stored = avatarRecord(5)
    expect((await put(owner, stored)).status).toBe(204)
    for (const version of [5, 3]) {
      const stale = await put(owner, avatarRecord(version))
      expect(stale.status).toBe(409)
      expect(stale.body).toEqual({ error: { code: 'stale_version', message: expect.any(String) } })
      expect(stale.headers.get('x-avatar-version')).toBe('5')
    }
    expect((await remove(owner, 5)).status).toBe(409)
    expect(Buffer.compare((await fetchAvatar(owner.accountId)).body, stored)).toBe(0)
  })

  it('lets a client whose answer was lost write again with X-Avatar-Version + 1', async () => {
    const owner = await register(api)
    expect((await put(owner, avatarRecord(1))).status).toBe(204)
    const retry = await put(owner, avatarRecord(1))
    expect(retry.status).toBe(409)
    const next = BigInt(retry.headers.get('x-avatar-version')!) + 1n
    expect((await put(owner, avatarRecord(next))).status).toBe(204)
    expect((await fetchAvatar(owner.accountId)).headers.get('etag')).toBe('"2"')
  })

  it('deletes with a higher version, also when nothing is stored, and remembers that version', async () => {
    const owner = await register(api)
    expect((await remove(owner, 2)).status).toBe(204)
    expect(await row(owner)).toEqual({ highest: '2', length: null })

    expect((await put(owner, avatarRecord(3))).status).toBe(204)
    expect((await remove(owner, 4)).status).toBe(204)
    expect(await row(owner)).toEqual({ highest: '4', length: null })
    expect((await fetchAvatar(owner.accountId)).status).toBe(404)
  })

  it('refuses a late store after a newer delete (the setting was turned off meanwhile)', async () => {
    const owner = await register(api)
    expect((await put(owner, avatarRecord(1))).status).toBe(204)
    expect((await remove(owner, 3)).status).toBe(204)
    const late = await put(owner, avatarRecord(2))
    expect(late.status).toBe(409)
    expect(late.headers.get('x-avatar-version')).toBe('3')
    expect((await fetchAvatar(owner.accountId)).status).toBe(404)
  })

  it('lets exactly one of two concurrent writes with the same version through', async () => {
    const owner = await register(api)
    const statuses = await Promise.all([put(owner, avatarRecord(7)), put(owner, avatarRecord(7))].map(p => p.then(r => r.status)))
    expect([...statuses].sort()).toEqual([204, 409])
  })

  it('ends with the highest version when writes race', async () => {
    const owner = await register(api)
    const versions = [3, 9, 1, 7, 5, 8, 2, 6, 4]
    const statuses = await Promise.all(versions.map(version => put(owner, avatarRecord(version)).then(r => r.status)))
    for (const status of statuses) expect([204, 409]).toContain(status)
    expect(statuses[versions.indexOf(9)]).toBe(204)
    expect((await fetchAvatar(owner.accountId)).headers.get('etag')).toBe('"9"')
    expect(await row(owner)).toEqual({ highest: '9', length: 8192 })
  })
})

describe('not found', () => {
  it('answers an account without a record exactly like an account that does not exist', async () => {
    const neverPublished = await register(api)
    const deletedRecord = await register(api)
    await put(deletedRecord, avatarRecord(1))
    await remove(deletedRecord, 2)

    const answers = []
    for (const id of [randomUUID(), neverPublished.accountId, deletedRecord.accountId]) {
      const response = await fetchAvatar(id)
      const headers = [...response.headers.entries()].filter(([name]) => name !== 'date')
      answers.push({ status: response.status, body: response.body, headers })
    }
    expect(answers[0]!.status).toBe(404)
    expect(answers[0]!.body).toEqual({ error: { code: 'not_found', message: 'avatar not found' } })
    expect(answers[1]).toEqual(answers[0])
    expect(answers[2]).toEqual(answers[0])
  })
})

describe('username change', () => {
  it('deletes the record and keeps the highest version', async () => {
    const owner = await register(api)
    expect((await put(owner, avatarRecord(3))).status).toBe(204)
    const changed = await api.put('/v1/accounts/me/username', { token: owner.authToken, json: { usernameHash: b64u(randomBytes(32)) } })
    expect(changed.status).toBe(204)

    expect((await fetchAvatar(owner.accountId)).status).toBe(404)
    expect(await row(owner)).toEqual({ highest: '3', length: null })
    // The client publishes again under the new name, with its next version.
    expect((await put(owner, avatarRecord(3))).headers.get('x-avatar-version')).toBe('3')
    expect((await put(owner, avatarRecord(4))).status).toBe(204)
    expect((await fetchAvatar(owner.accountId)).status).toBe(200)
  })

  it('changes nothing when the name is taken or is the current one', async () => {
    const owner = await register(api)
    const other = await register(api)
    const record = avatarRecord(1)
    await put(owner, record)

    const taken = await api.put('/v1/accounts/me/username', { token: owner.authToken, json: { usernameHash: other.usernameHash } })
    expect(taken.status).toBe(409)
    expect((await api.put('/v1/accounts/me/username', { token: owner.authToken, json: { usernameHash: owner.usernameHash } })).status).toBe(204)

    const fetched = await fetchAvatar(owner.accountId)
    expect(fetched.status).toBe(200)
    expect(Buffer.compare(fetched.body, record)).toBe(0)
  })
})

describe('account deletion', () => {
  it('deletes the record and its version with the account', async () => {
    const owner = await register(api)
    await put(owner, avatarRecord(4))
    expect((await api.delete('/v1/accounts/me', { token: owner.authToken })).status).toBe(204)
    expect(await db.count('avatars WHERE account_id = ?', [uuidBytes(owner.accountId)])).toBe(0)
    const gone = await fetchAvatar(owner.accountId)
    expect(gone.status).toBe(404)
    expect(gone.body).toEqual({ error: { code: 'not_found', message: 'avatar not found' } })
  })

  it('keeps nothing of a store whose account is deleted while its body arrives', async () => {
    const owner = await register(api)
    const record = avatarRecord(1)
    let finish!: () => void
    const status = new Promise<number | undefined>((resolve, reject) => {
      const req = request(`${relay.url}/v1/accounts/me/avatar`, {
        method: 'PUT',
        headers: { 'x-auth-token': owner.authToken, 'content-length': String(record.length), 'content-type': 'application/octet-stream' },
      }, (res) => {
        res.resume()
        resolve(res.statusCode)
      })
      req.on('error', reject)
      req.write(record.subarray(0, 4096))
      finish = () => req.end(record.subarray(4096))
    })
    // Long enough for the relay to have accepted the token.
    await sleep(300)
    expect((await api.delete('/v1/accounts/me', { token: owner.authToken })).status).toBe(204)
    finish()
    expect(await status).toBe(401)
    expect(await db.count('avatars WHERE account_id = ?', [uuidBytes(owner.accountId)])).toBe(0)
  })

  it('also happens in the sweep of accounts inactive for 180 days', async () => {
    const sweeping = await startRelay({ RETENTION_INTERVAL_SECONDS: '1' })
    try {
      const sweepingApi = new Api(sweeping.url)
      const inactive = await register(sweepingApi)
      const id = uuidBytes(inactive.accountId)
      expect((await put(inactive, avatarRecord(1), sweepingApi)).status).toBe(204)
      expect(await db.count('avatars WHERE account_id = ?', [id])).toBe(1)
      await db.query('UPDATE accounts SET last_seen = ? WHERE id = ?', [new Date(Date.now() - 181 * DAY).toISOString().slice(0, 10), id])

      await eventually(async () => await db.count('accounts WHERE id = ?', [id]) === 0)
      expect(await db.count('avatars WHERE account_id = ?', [id])).toBe(0)
      expect(sweeping.errors()).toEqual([])
    } finally {
      await sweeping.stop()
    }
  })
})

describe('rate limits (§4.11)', () => {
  it('limits fetches (a 304 included), stores and deletes per account, each on its own', async () => {
    const limited = await startRelay({ RATE_LIMIT_AVATAR_FETCH: '2/hour', RATE_LIMIT_AVATAR_WRITE: '2/day', RATE_LIMIT_AVATAR_DELETE: '2/day' })
    try {
      const limitedApi = new Api(limited.url)
      const [owner, looker] = [await register(limitedApi), await register(limitedApi)]
      const expectLimited = (response: { status: number, body: any, headers: Headers }) => {
        expect(response.status).toBe(429)
        expect(response.body.error.code).toBe('rate_limited')
        expect(Number(response.headers.get('retry-after'))).toBeGreaterThanOrEqual(1)
      }

      expect((await put(owner, avatarRecord(1), limitedApi)).status).toBe(204)
      expect((await put(owner, avatarRecord(2), limitedApi)).status).toBe(204)
      expectLimited(await put(owner, avatarRecord(3), limitedApi))
      // The used-up store budget does not hold up turning "Show in search" off.
      expect((await remove(owner, 3, limitedApi)).status).toBe(204)
      expect((await remove(owner, 4, limitedApi)).status).toBe(204)
      expectLimited(await remove(owner, 5, limitedApi))
      expect((await put(looker, avatarRecord(1), limitedApi)).status).toBe(204)

      expect((await fetchAvatar(looker.accountId, {}, looker.authToken, limitedApi)).status).toBe(200)
      expect((await fetchAvatar(looker.accountId, { 'if-none-match': '"1"' }, looker.authToken, limitedApi)).status).toBe(304)
      expectLimited(await fetchAvatar(looker.accountId, {}, looker.authToken, limitedApi))
      expect((await fetchAvatar(looker.accountId, {}, owner.authToken, limitedApi)).status).toBe(200)
      expect(limited.errors()).toEqual([])
    } finally {
      await limited.stop()
    }
  })
})
