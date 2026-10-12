import { createHash, randomBytes } from 'node:crypto'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { createApp, toNodeListener } from 'h3'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Database, Db } from '../../server/db/client'
import { Logger } from '../../server/lib/logger'
import { ConcurrencyLimiter, RateLimiter } from '../../server/lib/rate-limit'
import { tokenRotationBody } from '../../server/lib/validation'
import { CLOSE_UNAUTHORIZED } from '../../server/realtime/sessions'
import rotateToken from '../../server/routes/v1/accounts/me/token'
import { AccountCache } from '../../server/services/account-cache'
import { AccountStore, type TokenRotation } from '../../server/services/accounts'

// The route reaches the relay through useRelay(); each test assembles one
// from the real token check, cache and limiters around an in-memory store.
const fakeRelay = vi.hoisted(() => ({ current: undefined as unknown }))
vi.mock('../../server/relay', () => ({ useRelay: () => fakeRelay.current }))

const b64u = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64url')
const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest()
const newToken = () => randomBytes(32)

/** Accounts and their token hashes, rotated by the same rules as the MySQL store. */
class FakeAccounts {
  readonly tokenHashes = new Map<string, Buffer>()
  replaced = 0
  /** Runs when a rotation reaches the store, before it reads the row. */
  beforeRotation: (() => void) | undefined

  add(token: Buffer): Buffer {
    const id = randomBytes(16)
    this.tokenHashes.set(id.toString('hex'), sha256(token))
    return id
  }

  tokenHash(id: Buffer): Buffer | undefined {
    return this.tokenHashes.get(id.toString('hex'))
  }

  async findByAuthTokenHash(hash: Buffer): Promise<Buffer | null> {
    for (const [id, tokenHash] of this.tokenHashes) {
      if (tokenHash.equals(hash)) return Buffer.from(id, 'hex')
    }
    return null
  }

  async touch(): Promise<void> {}

  async rotateAuthTokenHash(accountId: Buffer, current: Buffer, next: Buffer): Promise<TokenRotation> {
    this.beforeRotation?.()
    const key = accountId.toString('hex')
    const row = this.tokenHashes.get(key)
    if (!row) return 'gone'
    if (row.equals(next)) return 'unchanged'
    if (!row.equals(current)) return 'stale'
    for (const [other, hash] of this.tokenHashes) {
      if (other !== key && hash.equals(next)) return 'taken'
    }
    this.tokenHashes.set(key, next)
    this.replaced++
    return 'replaced'
  }
}

interface Disconnect {
  accountKey: string
  code: number
  discard: boolean
}

let accounts: FakeAccounts
let disconnects: Disconnect[]
let server: Server
let url: string

beforeAll(async () => {
  server = createServer(toNodeListener(createApp().use('/v1/accounts/me/token', rotateToken)))
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1/accounts/me/token`
})

afterAll(async () => {
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
})

beforeEach(() => {
  accounts = new FakeAccounts()
  disconnects = []
  const logger = new Logger('error')
  fakeRelay.current = {
    config: {
      trustProxy: false,
      rateLimits: { authFail: { limit: 30, windowMs: 60_000 }, account: { limit: 1200, windowMs: 60_000 } },
    },
    limiter: new RateLimiter(),
    inFlight: new ConcurrencyLimiter(),
    accounts,
    accountCache: new AccountCache(accounts as unknown as AccountStore, logger),
    sessions: {
      disconnect: (accountKey: string, code: number, _reason: string, discard: boolean) => {
        disconnects.push({ accountKey, code, discard })
      },
    },
    logger,
    noteRateLimitedAddress: () => {},
  }
})

/** `POST /v1/accounts/me/token` authenticated with `token`; `body` is sent as is (a string) or as JSON. */
async function rotate(token: Buffer, body?: unknown): Promise<{ status: number, text: string, json: any }> {
  const headers: Record<string, string> = { 'x-auth-token': b64u(token) }
  let payload: string | undefined
  if (body !== undefined) {
    headers['content-type'] = 'application/json'
    payload = typeof body === 'string' ? body : JSON.stringify(body)
  }
  const response = await fetch(url, { method: 'POST', headers, body: payload })
  const text = await response.text()
  return { status: response.status, text, json: text === '' ? null : JSON.parse(text) }
}

const chosen = (token: Buffer) => ({ newAuthToken: b64u(token) })

describe('POST /v1/accounts/me/token with a client-chosen token', () => {
  it('stores the new token, answers 204 without returning a token and ends the old one at once', async () => {
    const old = newToken()
    const next = newToken()
    const id = accounts.add(old)
    // The old token is cached by a first request, so the rotation has to forget it.
    expect((await rotate(old, chosen(old))).status).toBe(204)

    const response = await rotate(old, chosen(next))
    expect(response.status).toBe(204)
    expect(response.text).toBe('')
    expect(accounts.tokenHash(id)).toEqual(sha256(next))
    expect(disconnects).toEqual([{ accountKey: id.toString('hex'), code: CLOSE_UNAUTHORIZED, discard: false }])
    // No grace period: the old token is refused right away.
    expect((await rotate(old, chosen(next))).status).toBe(401)
  })

  it('answers a repeated rotation with the new token 204 and changes nothing', async () => {
    const old = newToken()
    const next = newToken()
    const id = accounts.add(old)
    expect((await rotate(old, chosen(next))).status).toBe(204)

    // The response was lost; the client asks again with its pending token.
    for (let i = 0; i < 3; i++) {
      const again = await rotate(next, chosen(next))
      expect(again.status).toBe(204)
      expect(again.text).toBe('')
    }
    expect(accounts.replaced).toBe(1)
    expect(disconnects).toHaveLength(1)
    expect(accounts.tokenHash(id)).toEqual(sha256(next))
  })

  it('answers 204 to a request with the old token whose rotation another request finished meanwhile', async () => {
    const old = newToken()
    const next = newToken()
    const id = accounts.add(old)
    // The first attempt is still running on the relay when the client repeats it.
    accounts.beforeRotation = () => accounts.tokenHashes.set(id.toString('hex'), sha256(next))
    expect((await rotate(old, chosen(next))).status).toBe(204)
    expect(accounts.replaced).toBe(0)
    expect(disconnects).toEqual([])
  })

  it('replaces only the token that authenticated the request: of two restores of one backup, one wins', async () => {
    const backup = newToken()
    const first = newToken()
    const second = newToken()
    const id = accounts.add(backup)
    // The other phone's rotation commits while this one waits for the row lock.
    accounts.beforeRotation = () => {
      accounts.beforeRotation = undefined
      accounts.tokenHashes.set(id.toString('hex'), sha256(first))
    }
    expect((await rotate(backup, chosen(second))).status).toBe(401)
    expect(accounts.tokenHash(id)).toEqual(sha256(first))

    // The losing phone's way out (PROTOCOL.md §5.5): its pending token and
    // the backup's are both refused, so the backup is no longer valid.
    expect((await rotate(second, chosen(second))).status).toBe(401)
    expect((await rotate(backup, chosen(second))).status).toBe(401)
    expect((await rotate(first, chosen(first))).status).toBe(204)
  })

  it('lets exactly one of two concurrent rotations with the same token through', async () => {
    const backup = newToken()
    const tokens = [newToken(), newToken()]
    const id = accounts.add(backup)
    const statuses = await Promise.all(tokens.map(token => rotate(backup, chosen(token)).then(r => r.status)))
    expect([...statuses].sort()).toEqual([204, 401])
    const winner = tokens[statuses.indexOf(204)]!
    expect(accounts.tokenHash(id)).toEqual(sha256(winner))
    expect(accounts.replaced).toBe(1)
  })

  it('refuses bodies it cannot use and changes nothing', async () => {
    const old = newToken()
    const id = accounts.add(old)
    const other = newToken()
    accounts.add(other)
    const refused: unknown[] = [
      {},
      { authToken: b64u(newToken()) },
      { newAuthToken: null },
      { newAuthToken: b64u(randomBytes(31)) },
      { newAuthToken: b64u(randomBytes(33)) },
      { newAuthToken: newToken().toString('base64') },
      { newAuthToken: `${b64u(newToken())}=` },
      { newAuthToken: b64u(other) },
      // Plainly not random: what an unchecked failure of the random generator leaves.
      { newAuthToken: b64u(Buffer.alloc(32)) },
      { newAuthToken: b64u(Buffer.alloc(32, randomBytes(4))) },
      'null',
      '[]',
      '"token"',
      'not json',
      ' ',
    ]
    for (const body of refused) {
      const response = await rotate(old, body)
      expect(response.status, JSON.stringify(body)).toBe(400)
      expect(response.json.error.code).toBe('bad_request')
    }
    expect((await rotate(old, { newAuthToken: 'x'.repeat(20_000) })).status).toBe(413)
    expect(accounts.tokenHash(id)).toEqual(sha256(old))
    expect(disconnects).toEqual([])
    // The old token still works.
    expect((await rotate(old, chosen(newToken()))).status).toBe(204)
  })

  it('never puts a token in a refusal', async () => {
    const old = newToken()
    accounts.add(old)
    const other = newToken()
    accounts.add(other)
    const response = await rotate(old, chosen(other))
    expect(response.status).toBe(400)
    expect(response.text).not.toContain(b64u(other))
    expect(response.text).not.toContain(b64u(old))
  })

  it('answers 401 without an auth token or for an account deleted while the request ran', async () => {
    const response = await fetch(url, { method: 'POST', body: JSON.stringify(chosen(newToken())) })
    expect(response.status).toBe(401)

    const old = newToken()
    const id = accounts.add(old)
    accounts.beforeRotation = () => accounts.tokenHashes.delete(id.toString('hex'))
    expect((await rotate(old, chosen(newToken()))).status).toBe(401)
    expect(disconnects).toEqual([])
  })
})

describe('POST /v1/accounts/me/token without a body (deprecated)', () => {
  it('chooses the token and returns it, as clients before client-chosen tokens expect', async () => {
    for (const body of [undefined, '']) {
      const old = newToken()
      const id = accounts.add(old)
      const response = await rotate(old, body)
      expect(response.status).toBe(200)
      const token = Buffer.from(response.json.authToken as string, 'base64url')
      expect(token).toHaveLength(32)
      expect(b64u(token)).toBe(response.json.authToken)
      expect(accounts.tokenHash(id)).toEqual(sha256(token))
      expect(disconnects.at(-1)).toEqual({ accountKey: id.toString('hex'), code: CLOSE_UNAUTHORIZED, discard: false })
      expect((await rotate(old, body)).status).toBe(401)
      // The returned token works, also for the new form.
      expect((await rotate(token, chosen(newToken()))).status).toBe(204)
    }
  })

  it('replaces only the token that authenticated the request', async () => {
    const backup = newToken()
    const other = newToken()
    const id = accounts.add(backup)
    accounts.beforeRotation = () => {
      accounts.beforeRotation = undefined
      accounts.tokenHashes.set(id.toString('hex'), sha256(other))
    }
    expect((await rotate(backup)).status).toBe(401)
    expect(accounts.tokenHash(id)).toEqual(sha256(other))
  })
})

describe('tokenRotationBody', () => {
  it('takes no body as the old form and a body only with a 32-byte newAuthToken', () => {
    expect(tokenRotationBody(undefined)).toBeNull()
    const token = newToken()
    expect(tokenRotationBody({ newAuthToken: b64u(token), extra: 1 })).toEqual(token)
    for (const body of [null, {}, [], 'x', { newAuthToken: '' }, { newAuthToken: b64u(randomBytes(16)) }]) {
      expect(() => tokenRotationBody(body), JSON.stringify(body)).toThrow()
    }
  })

  it('refuses a token with fewer than 16 different byte values and takes random ones', () => {
    const pattern = (values: number) => Buffer.from(Array.from({ length: 32 }, (_, i) => i % values))
    expect(() => tokenRotationBody({ newAuthToken: b64u(Buffer.alloc(32)) })).toThrow('32 random bytes')
    expect(() => tokenRotationBody({ newAuthToken: b64u(pattern(15)) })).toThrow('32 random bytes')
    expect(tokenRotationBody({ newAuthToken: b64u(pattern(16)) })).toEqual(pattern(16))
    for (let i = 0; i < 1000; i++) {
      const token = newToken()
      expect(tokenRotationBody({ newAuthToken: b64u(token) })).toEqual(token)
    }
  })
})

/**
 * A database with one account row, as the rotation's transaction sees it:
 * the row read under the lock and the update written back.
 */
class OneRowDatabase {
  row: { authTokenHash: Buffer } | undefined
  locks: string[] = []
  /** Makes the update fail like a write that hits the unique token index. */
  duplicate = false

  constructor(tokenHash: Buffer | undefined) {
    this.row = tokenHash && { authTokenHash: tokenHash }
  }

  readonly tx = {
    select: () => ({
      from: () => ({
        where: () => ({
          for: async (mode: string) => {
            this.locks.push(mode)
            return this.row ? [{ ...this.row }] : []
          },
        }),
      }),
    }),
    update: () => ({
      set: (values: { authTokenHash: Buffer }) => ({
        where: async () => {
          if (this.duplicate) throw Object.assign(new Error('Duplicate entry'), { code: 'ER_DUP_ENTRY' })
          this.row = { authTokenHash: values.authTokenHash }
        },
      }),
    }),
  }

  asDatabase(): Database {
    return { transaction: <T>(work: (tx: Db) => Promise<T>) => work(this.tx as unknown as Db) } as Database
  }
}

describe('AccountStore.rotateAuthTokenHash', () => {
  const id = randomBytes(16)
  const current = sha256(newToken())
  const next = sha256(newToken())

  async function rotation(database: OneRowDatabase): Promise<TokenRotation> {
    return new AccountStore(database.asDatabase()).rotateAuthTokenHash(id, current, next)
  }

  it('replaces the current token under the row lock', async () => {
    const database = new OneRowDatabase(current)
    expect(await rotation(database)).toBe('replaced')
    expect(database.row!.authTokenHash).toEqual(next)
    expect(database.locks).toEqual(['update'])
  })

  it('reports a rotation that already happened as unchanged', async () => {
    const database = new OneRowDatabase(next)
    expect(await rotation(database)).toBe('unchanged')
    expect(database.row!.authTokenHash).toEqual(next)
  })

  it('leaves a token that another rotation replaced alone', async () => {
    const elsewhere = sha256(newToken())
    const database = new OneRowDatabase(elsewhere)
    expect(await rotation(database)).toBe('stale')
    expect(database.row!.authTokenHash).toEqual(elsewhere)
  })

  it('reports a deleted account and a token that belongs to another account', async () => {
    expect(await rotation(new OneRowDatabase(undefined))).toBe('gone')
    const database = new OneRowDatabase(current)
    database.duplicate = true
    expect(await rotation(database)).toBe('taken')
    expect(database.row!.authTokenHash).toEqual(current)
  })
})
