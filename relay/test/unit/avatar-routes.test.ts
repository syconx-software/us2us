import { createHash, randomBytes } from 'node:crypto'
import { createServer, request, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { createApp, createRouter, toNodeListener } from 'h3'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Rate } from '../../server/lib/config'
import { formatUuid } from '../../server/lib/encoding'
import { Logger } from '../../server/lib/logger'
import { ConcurrencyLimiter, RateLimiter } from '../../server/lib/rate-limit'
import accountAvatar from '../../server/routes/v1/accounts/[accountId]/avatar'
import ownAvatar from '../../server/routes/v1/accounts/me/avatar'
import { AccountCache } from '../../server/services/account-cache'
import type { AccountStore } from '../../server/services/accounts'
import type { AvatarWrite, StoredAvatar } from '../../server/services/avatars'
import { Api, b64u } from '../support/client'
import { AVATAR_SIZES, avatarRecord } from '../support/avatar'

// The routes reach the relay through useRelay(); each test assembles one
// from the real token check, cache and limiters around in-memory stores.
const fakeRelay = vi.hoisted(() => ({ current: undefined as unknown }))
vi.mock('../../server/relay', () => ({ useRelay: () => fakeRelay.current }))

const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest()
const HOUR = 3_600_000
const DAY = 24 * HOUR
const UNLIMITED: Rate = { limit: Number.POSITIVE_INFINITY, windowMs: HOUR }

/** Accounts by token hash, as the token check needs them. */
class FakeAccounts {
  readonly tokenHashes = new Map<string, Buffer>()

  add(): { id: Buffer, token: string } {
    const token = randomBytes(32)
    const id = randomBytes(16)
    this.tokenHashes.set(id.toString('hex'), sha256(token))
    return { id, token: b64u(token) }
  }

  exists(id: Buffer): boolean {
    return this.tokenHashes.has(id.toString('hex'))
  }

  remove(id: Buffer): void {
    this.tokenHashes.delete(id.toString('hex'))
  }

  async findByAuthTokenHash(hash: Buffer): Promise<Buffer | null> {
    for (const [id, tokenHash] of this.tokenHashes) {
      if (tokenHash.equals(hash)) return Buffer.from(id, 'hex')
    }
    return null
  }

  async touch(): Promise<void> {}
}

/** The avatars table in memory, written by the same rules as the MySQL store. */
class FakeAvatars {
  readonly rows = new Map<string, { highest: bigint, record: Buffer | null }>()
  writes = 0
  finds = 0
  /** Runs when a write reaches the store, before it looks at the account. */
  beforeWrite: (() => void) | undefined
  /** Thrown by every call, as a database error would be. */
  failure: Error | undefined

  constructor(private readonly accounts: FakeAccounts) {}

  async store(accountId: Buffer, version: bigint, record: Buffer): Promise<AvatarWrite> {
    return this.write(accountId, version, record)
  }

  async remove(accountId: Buffer, version: bigint): Promise<AvatarWrite> {
    return this.write(accountId, version, null)
  }

  async find(accountId: Buffer): Promise<StoredAvatar | null> {
    this.finds++
    if (this.failure) throw this.failure
    const record = this.rows.get(accountId.toString('hex'))?.record
    return record ? { version: record.readBigUInt64BE(5), record } : null
  }

  private write(accountId: Buffer, version: bigint, record: Buffer | null): AvatarWrite {
    this.beforeWrite?.()
    if (this.failure) throw this.failure
    if (!this.accounts.exists(accountId)) return { outcome: 'gone' }
    const key = accountId.toString('hex')
    const row = this.rows.get(key)
    if (row && version <= row.highest) return { outcome: 'stale', highest: row.highest }
    this.rows.set(key, { highest: version, record })
    this.writes++
    return { outcome: 'written' }
  }
}

let accounts: FakeAccounts
let avatars: FakeAvatars
let server: Server
let api: Api
let logged: string[]

beforeAll(async () => {
  const router = createRouter()
    .use('/v1/accounts/me/avatar', ownAvatar)
    .use('/v1/accounts/:accountId/avatar', accountAvatar)
  server = createServer(toNodeListener(createApp().use(router)))
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  api = new Api(`http://127.0.0.1:${(server.address() as AddressInfo).port}`)
})

afterAll(async () => {
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
})

function assembleRelay(limits: { avatarFetch?: Rate, avatarWrite?: Rate, avatarDelete?: Rate } = {}): void {
  accounts = new FakeAccounts()
  avatars = new FakeAvatars(accounts)
  const logger = new Logger('debug')
  fakeRelay.current = {
    config: {
      trustProxy: false,
      rateLimits: {
        authFail: UNLIMITED,
        account: UNLIMITED,
        avatarFetch: limits.avatarFetch ?? UNLIMITED,
        avatarWrite: limits.avatarWrite ?? UNLIMITED,
        avatarDelete: limits.avatarDelete ?? UNLIMITED,
      },
    },
    limiter: new RateLimiter(),
    inFlight: new ConcurrencyLimiter(),
    accountCache: new AccountCache(accounts as unknown as AccountStore, logger),
    avatars,
    logger,
    noteRateLimitedAddress: () => {},
  }
}

beforeEach(() => {
  assembleRelay()
  logged = []
  const capture = (...args: unknown[]) => {
    logged.push(args.map(String).join(' '))
  }
  vi.spyOn(console, 'log').mockImplementation(capture)
  vi.spyOn(console, 'error').mockImplementation(capture)
  vi.spyOn(console, 'warn').mockImplementation(capture)
})

afterEach(() => {
  vi.restoreAllMocks()
})

const put = (token: string, record: Uint8Array, headers: Record<string, string> = {}) =>
  api.put('/v1/accounts/me/avatar', { token, body: record, headers })
const remove = (token: string, version?: string) =>
  api.delete('/v1/accounts/me/avatar', { token, headers: version === undefined ? {} : { 'x-avatar-version': version } })
const fetchAvatar = (token: string, accountId: Buffer | string, headers: Record<string, string> = {}) =>
  api.get(`/v1/accounts/${typeof accountId === 'string' ? accountId : formatUuid(accountId)}/avatar`, { token, headers })

/** Sends only the headers of a PUT with the given Content-Length and resolves with the answer, the body never sent. */
function putHeadersOnly(token: string, contentLength: string): Promise<{ status: number, body: string }> {
  return new Promise((resolve, reject) => {
    const req = request(`${api.baseUrl}/v1/accounts/me/avatar`, {
      method: 'PUT',
      headers: { 'x-auth-token': token, 'content-length': contentLength, 'content-type': 'application/octet-stream' },
    }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk: string) => {
        body += chunk
      })
      res.on('end', () => {
        req.destroy()
        resolve({ status: res.statusCode!, body })
      })
    })
    req.on('error', reject)
    req.flushHeaders()
  })
}

describe('PUT /v1/accounts/me/avatar', () => {
  it('stores a record of each of the four sizes and answers 204 with its version as ETag', async () => {
    const { id, token } = accounts.add()
    let version = 1
    for (const size of AVATAR_SIZES) {
      const record = avatarRecord(version, size)
      const response = await put(token, record)
      expect(response.status, String(size)).toBe(204)
      expect(response.body).toHaveLength(0)
      expect(response.headers.get('etag')).toBe(`"${version}"`)
      expect(avatars.rows.get(id.toString('hex'))).toEqual({ highest: BigInt(version), record })
      version++
    }
  })

  it('refuses a body over 49152 bytes with 413 before reading it', async () => {
    const { token } = accounts.add()
    for (const length of ['49153', String(1024 * 1024)]) {
      const response = await putHeadersOnly(token, length)
      expect(response.status).toBe(413)
      expect(JSON.parse(response.body).error.code).toBe('payload_too_large')
    }
    expect(avatars.writes).toBe(0)
  })

  it('refuses every other length with 400, also before reading it', async () => {
    const { token } = accounts.add()
    for (const length of ['0', '13', '8191', '8193', '16383', '32769', '49151']) {
      const response = await putHeadersOnly(token, length)
      expect(response.status, length).toBe(400)
      expect(JSON.parse(response.body).error.code).toBe('bad_request')
    }
    expect((await put(token, avatarRecord(1, 8191))).status).toBe(400)
    expect((await put(token, avatarRecord(1, 8193))).status).toBe(400)
    expect(avatars.writes).toBe(0)
  })

  it('requires a Content-Length: a chunked body is 400', async () => {
    const { token } = accounts.add()
    const record = avatarRecord(1)
    const chunked = await fetch(`${api.baseUrl}/v1/accounts/me/avatar`, {
      method: 'PUT',
      headers: { 'x-auth-token': token, 'content-type': 'application/octet-stream' },
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(record)
          controller.close()
        },
      }),
      duplex: 'half',
    } as RequestInit)
    expect(chunked.status).toBe(400)
    expect(((await chunked.json()) as { error: { code: string } }).error.code).toBe('bad_request')
    expect(avatars.writes).toBe(0)
  })

  it('refuses another magic or format byte and a version of 0 or ≥ 2^63', async () => {
    const { token } = accounts.add()
    const malformed = [
      avatarRecord(1, 8192, { magic: 'BRPB' }),
      avatarRecord(1, 8192, { magic: 'brpa' }),
      avatarRecord(1, 8192, { format: 0x02 }),
      avatarRecord(1, 8192, { format: 0x00 }),
      avatarRecord(0),
      avatarRecord(2n ** 63n),
      avatarRecord(2n ** 64n - 1n),
    ]
    for (const record of malformed) {
      const response = await put(token, record)
      expect(response.status).toBe(400)
      expect(response.body.error.code).toBe('bad_request')
    }
    expect(avatars.writes).toBe(0)
    expect((await put(token, avatarRecord(2n ** 63n - 1n))).status).toBe(204)
  })

  it('answers a version that is not above the highest with 409 stale_version and X-Avatar-Version, changing nothing', async () => {
    const { id, token } = accounts.add()
    const stored = avatarRecord(5)
    expect((await put(token, stored)).status).toBe(204)
    for (const version of [5, 4, 1]) {
      const response = await put(token, avatarRecord(version))
      expect(response.status).toBe(409)
      expect(response.body).toEqual({ error: { code: 'stale_version', message: expect.any(String) } })
      expect(response.headers.get('x-avatar-version')).toBe('5')
      expect(response.headers.get('etag')).toBeNull()
    }
    expect(avatars.rows.get(id.toString('hex'))!.record).toEqual(stored)
  })

  it('lets a client whose answer was lost repeat with X-Avatar-Version + 1', async () => {
    const { id, token } = accounts.add()
    expect((await put(token, avatarRecord(9))).status).toBe(204)
    // The answer was lost; the client's vault still says 8 and it writes 9 again.
    const retry = await put(token, avatarRecord(9))
    expect(retry.status).toBe(409)
    const next = BigInt(retry.headers.get('x-avatar-version')!) + 1n
    const sealedAgain = avatarRecord(next)
    const second = await put(token, sealedAgain)
    expect(second.status).toBe(204)
    expect(second.headers.get('etag')).toBe('"10"')
    expect(avatars.rows.get(id.toString('hex'))!.record).toEqual(sealedAgain)
  })

  it('answers 401 and keeps nothing when the account is deleted while the upload runs', async () => {
    const { id, token } = accounts.add()
    avatars.beforeWrite = () => accounts.remove(id)
    const response = await put(token, avatarRecord(1))
    expect(response.status).toBe(401)
    expect(response.body.error.code).toBe('unauthorized')
    expect(avatars.rows.size).toBe(0)
  })

  it('requires authentication', async () => {
    expect((await api.put('/v1/accounts/me/avatar', { body: avatarRecord(1) })).status).toBe(401)
    expect((await put(b64u(randomBytes(32)), avatarRecord(1))).status).toBe(401)
    expect(avatars.writes).toBe(0)
  })
})

describe('DELETE /v1/accounts/me/avatar', () => {
  it('requires a well-formed X-Avatar-Version', async () => {
    const { token } = accounts.add()
    for (const value of [undefined, '', '0', '-1', 'abc', '1.5', '9223372036854775808', '1, 2']) {
      const response = await remove(token, value)
      expect(response.status, String(value)).toBe(400)
      expect(response.body.error.code).toBe('bad_request')
    }
    expect(avatars.writes).toBe(0)
  })

  it('deletes the record and answers 204, also when nothing was stored', async () => {
    const { id, token } = accounts.add()
    expect((await remove(token, '3')).status).toBe(204)
    expect(avatars.rows.get(id.toString('hex'))).toEqual({ highest: 3n, record: null })

    expect((await put(token, avatarRecord(4))).status).toBe(204)
    const deleted = await remove(token, '5')
    expect(deleted.status).toBe(204)
    expect(deleted.body).toHaveLength(0)
    expect(avatars.rows.get(id.toString('hex'))).toEqual({ highest: 5n, record: null })
    expect((await fetchAvatar(token, id)).status).toBe(404)
  })

  it('refuses a version that is not above the highest and keeps the record', async () => {
    const { id, token } = accounts.add()
    const stored = avatarRecord(7)
    expect((await put(token, stored)).status).toBe(204)
    for (const version of ['7', '6', '0007']) {
      const response = await remove(token, version)
      expect(response.status).toBe(409)
      expect(response.body.error.code).toBe('stale_version')
      expect(response.headers.get('x-avatar-version')).toBe('7')
    }
    expect((await fetchAvatar(token, id)).body).toEqual(stored)
  })

  it('makes a late retry of an older store fail after a newer delete ("Show in search" turned off)', async () => {
    const { id, token } = accounts.add()
    const other = accounts.add()
    // The publish of version 3 is still under way when the user turns the setting off.
    expect((await remove(token, '4')).status).toBe(204)
    const late = await put(token, avatarRecord(3))
    expect(late.status).toBe(409)
    expect(late.headers.get('x-avatar-version')).toBe('4')
    expect((await fetchAvatar(other.token, id)).status).toBe(404)
  })

  it('answers 401 when the account is deleted while the request waits', async () => {
    const { id, token } = accounts.add()
    avatars.beforeWrite = () => accounts.remove(id)
    expect((await remove(token, '1')).status).toBe(401)
    expect(avatars.rows.size).toBe(0)
  })
})

describe('GET /v1/accounts/{accountId}/avatar', () => {
  it('returns the record with its ETag, as octet-stream and not cacheable', async () => {
    const owner = accounts.add()
    const searcher = accounts.add()
    const record = avatarRecord(12, 16384)
    expect((await put(owner.token, record)).status).toBe(204)

    for (const token of [searcher.token, owner.token]) {
      const response = await fetchAvatar(token, owner.id)
      expect(response.status).toBe(200)
      expect(response.body).toEqual(record)
      expect(response.headers.get('content-type')).toBe('application/octet-stream')
      expect(response.headers.get('content-length')).toBe('16384')
      expect(response.headers.get('etag')).toBe('"12"')
      expect(response.headers.get('cache-control')).toBe('no-store')
    }
    // Any case of the UUID names the same account.
    expect((await fetchAvatar(searcher.token, formatUuid(owner.id).toUpperCase())).status).toBe(200)
  })

  it('answers If-None-Match naming the stored version with an empty 304, and ignores other values', async () => {
    const owner = accounts.add()
    const searcher = accounts.add()
    const record = avatarRecord(3)
    await put(owner.token, record)

    const notModified = await fetchAvatar(searcher.token, owner.id, { 'if-none-match': '"3"' })
    expect(notModified.status).toBe(304)
    expect(notModified.body).toHaveLength(0)
    expect(notModified.headers.get('etag')).toBe('"3"')

    for (const value of ['"2"', '"03"', '*', 'garbage']) {
      const full = await fetchAvatar(searcher.token, owner.id, { 'if-none-match': value })
      expect(full.status, value).toBe(200)
      expect(full.body).toEqual(record)
    }

    // A newer record makes the cached copy stale.
    await put(owner.token, avatarRecord(4))
    expect((await fetchAvatar(searcher.token, owner.id, { 'if-none-match': '"3"' })).status).toBe(200)
  })

  it('ignores Range and always sends the whole record', async () => {
    const owner = accounts.add()
    const record = avatarRecord(1)
    await put(owner.token, record)
    const response = await fetchAvatar(owner.token, owner.id, { range: 'bytes=0-99' })
    expect(response.status).toBe(200)
    expect(response.body).toEqual(record)
    expect(response.headers.get('content-range')).toBeNull()
    expect(response.headers.get('accept-ranges')).toBeNull()
  })

  it('answers an account without a record exactly like an account that does not exist', async () => {
    const searcher = accounts.add()
    const neverPublished = accounts.add()
    const deletedRecord = accounts.add()
    await put(deletedRecord.token, avatarRecord(1))
    await remove(deletedRecord.token, '2')

    const answers = []
    for (const id of [randomBytes(16), neverPublished.id, deletedRecord.id]) {
      const response = await fetchAvatar(searcher.token, id)
      const headers = [...response.headers.entries()].filter(([name]) => name !== 'date')
      answers.push({ status: response.status, body: response.body, headers })
    }
    expect(answers[0]!.status).toBe(404)
    expect(answers[0]!.body).toEqual({ error: { code: 'not_found', message: 'avatar not found' } })
    expect(answers[1]).toEqual(answers[0])
    expect(answers[2]).toEqual(answers[0])
    // Each took the same single lookup; nothing else was asked.
    expect(avatars.finds).toBe(3)
  })

  it('answers 400 for an account id that is not a UUID and 401 without a token', async () => {
    const searcher = accounts.add()
    const malformed = await fetchAvatar(searcher.token, 'not-a-uuid')
    expect(malformed.status).toBe(400)
    expect(malformed.body.error.code).toBe('bad_request')
    expect((await api.get(`/v1/accounts/${formatUuid(randomBytes(16))}/avatar`)).status).toBe(401)
    expect(avatars.finds).toBe(0)
  })
})

describe('methods', () => {
  it('answers other methods with 405 and the allowed ones', async () => {
    const { id, token } = accounts.add()
    for (const method of ['GET', 'POST', 'PATCH']) {
      const response = await api.request(method, '/v1/accounts/me/avatar', { token })
      expect(response.status, method).toBe(405)
      expect(response.body.error.code).toBe('bad_request')
      expect(response.headers.get('allow')).toBe('PUT, DELETE')
    }
    for (const method of ['PUT', 'DELETE', 'POST']) {
      const response = await api.request(method, `/v1/accounts/${formatUuid(id)}/avatar`, { token, body: avatarRecord(1) })
      expect(response.status, method).toBe(405)
      expect(response.headers.get('allow')).toBe('GET')
    }
    expect(avatars.writes).toBe(0)
  })
})

describe('rate limits (§4.11)', () => {
  it('limits fetches per account, a 304 included', async () => {
    assembleRelay({ avatarFetch: { limit: 2, windowMs: HOUR } })
    const owner = accounts.add()
    const searcher = accounts.add()
    await put(owner.token, avatarRecord(1))

    expect((await fetchAvatar(searcher.token, owner.id)).status).toBe(200)
    expect((await fetchAvatar(searcher.token, owner.id, { 'if-none-match': '"1"' })).status).toBe(304)
    const limited = await fetchAvatar(searcher.token, owner.id)
    expect(limited.status).toBe(429)
    expect(limited.body.error.code).toBe('rate_limited')
    expect(Number(limited.headers.get('retry-after'))).toBeGreaterThanOrEqual(1)
    // Misses count too: the budget is per requester, whatever it looks at.
    expect((await fetchAvatar(searcher.token, randomBytes(16))).status).toBe(429)
    // Another account has its own budget.
    expect((await fetchAvatar(owner.token, owner.id)).status).toBe(200)
  })

  it('limits stores per account, and a used-up store budget never holds up a delete', async () => {
    assembleRelay({ avatarWrite: { limit: 2, windowMs: DAY }, avatarDelete: { limit: 2, windowMs: DAY } })
    const first = accounts.add()
    const second = accounts.add()
    expect((await put(first.token, avatarRecord(1))).status).toBe(204)
    expect((await put(first.token, avatarRecord(1))).status).toBe(409) // refused stores count as well
    const limited = await put(first.token, avatarRecord(2))
    expect(limited.status).toBe(429)
    expect(limited.body.error.code).toBe('rate_limited')
    expect(Number(limited.headers.get('retry-after'))).toBeGreaterThanOrEqual(1)
    expect(avatars.rows.get(first.id.toString('hex'))?.highest).toBe(1n)

    // "Show in search" off right after: the record goes at once.
    expect((await remove(first.token, '2')).status).toBe(204)
    expect(avatars.rows.get(first.id.toString('hex'))).toEqual({ highest: 2n, record: null })
    expect((await remove(first.token, '2')).status).toBe(409) // refused deletes count too
    const deleteLimited = await remove(first.token, '3')
    expect(deleteLimited.status).toBe(429)
    expect(deleteLimited.body.error.code).toBe('rate_limited')
    // Each account has its own budgets.
    expect((await put(second.token, avatarRecord(1))).status).toBe(204)
    expect((await remove(second.token, '2')).status).toBe(204)
  })
})

describe('logging (§4.10)', () => {
  it('logs no account id, version or ETag, and names a failing route by its template only', async () => {
    const owner = accounts.add()
    const searcher = accounts.add()
    const version = 4_815_162_342n
    await put(owner.token, avatarRecord(version))
    await fetchAvatar(searcher.token, owner.id)
    await fetchAvatar(searcher.token, owner.id, { 'if-none-match': `"${version}"` })
    await remove(owner.token, String(version))
    await fetchAvatar(searcher.token, owner.id)

    // A database error embeds the statement and its parameters in its message.
    avatars.failure = Object.assign(new Error(`Lock wait timeout; params: ${owner.id.toString('hex')}, ${version}`), { code: 'ER_LOCK_WAIT_TIMEOUT' })
    expect((await fetchAvatar(searcher.token, owner.id)).status).toBe(500)
    expect((await remove(owner.token, String(version + 1n))).status).toBe(500)

    expect(logged).toEqual([
      '[relay] error request failed route="/v1/accounts/:accountId/avatar" error="Error ER_LOCK_WAIT_TIMEOUT"',
      '[relay] error request failed route="/v1/accounts/me/avatar" error="Error ER_LOCK_WAIT_TIMEOUT"',
    ])
    const secrets = [owner.id.toString('hex'), formatUuid(owner.id), searcher.id.toString('hex'), formatUuid(searcher.id), String(version)]
    for (const line of logged) {
      for (const secret of secrets) expect(line).not.toContain(secret)
    }
  })
})
