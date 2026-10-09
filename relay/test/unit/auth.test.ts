import { createHash, randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { authenticateToken, recordUnauthorized, type TokenAuthority } from '../../server/http/auth'
import { ApiError, unauthorized } from '../../server/lib/errors'
import { RateLimiter } from '../../server/lib/rate-limit'
import type { AccountCache } from '../../server/services/account-cache'

const b64u = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64url')
const sha256Hex = (token: Buffer) => createHash('sha256').update(token).digest('hex')

/** Knows a set of token hashes; every lookup takes a few milliseconds, and the most concurrent ones are counted. */
class FakeCache {
  readonly accounts = new Map<string, Buffer>()
  readonly cached = new Map<string, Buffer>()
  lookups = 0
  running = 0
  maxRunning = 0

  cachedAccount(hash: Buffer): Buffer | undefined {
    return this.cached.get(hash.toString('hex'))
  }

  async authenticate(hash: Buffer): Promise<Buffer | null> {
    this.lookups++
    this.maxRunning = Math.max(this.maxRunning, ++this.running)
    await new Promise(resolve => setTimeout(resolve, 3))
    this.running--
    return this.accounts.get(hash.toString('hex')) ?? null
  }
}

function setup(limit = 30) {
  const cache = new FakeCache()
  const relay: TokenAuthority = {
    config: { rateLimits: { authFail: { limit, windowMs: 60_000 } } },
    limiter: new RateLimiter(),
    accountCache: cache as unknown as AccountCache,
  }
  return { cache, relay }
}

async function outcome(pending: Promise<Buffer>): Promise<number> {
  try {
    await pending
    return 200
  } catch (error) {
    if (error instanceof ApiError) return error.status
    throw error
  }
}

describe('authenticateToken', () => {
  it('lets a burst of made-up tokens from one address cause no more lookups than its budget', async () => {
    const { cache, relay } = setup()
    const statuses = await Promise.all(Array.from({ length: 100 }, () =>
      outcome(authenticateToken(relay, '203.0.113.1', b64u(randomBytes(32))))))
    expect(statuses.filter(s => s === 401)).toHaveLength(30)
    expect(statuses.filter(s => s === 429)).toHaveLength(70)
    expect(cache.lookups).toBe(30)
    // Over the limit, even a valid token is refused before any lookup.
    const token = randomBytes(32)
    cache.accounts.set(sha256Hex(token), randomBytes(16))
    expect(await outcome(authenticateToken(relay, '203.0.113.1', b64u(token)))).toBe(429)
    expect(cache.lookups).toBe(30)
    // Another address is not affected.
    expect(await outcome(authenticateToken(relay, '203.0.113.2', b64u(token)))).toBe(200)
  })

  it('lets a burst of valid tokens from one address through, a budget at a time', async () => {
    const { cache, relay } = setup()
    const tokens = Array.from({ length: 100 }, () => randomBytes(32))
    for (const token of tokens) cache.accounts.set(sha256Hex(token), randomBytes(16))
    const statuses = await Promise.all(tokens.map(token => outcome(authenticateToken(relay, '198.51.100.1', b64u(token)))))
    expect(statuses).toEqual(Array(100).fill(200))
    expect(cache.maxRunning).toBeLessThanOrEqual(30)
    // Nothing was counted as a failure.
    expect(relay.limiter.peek('auth-fail', '198.51.100.1', relay.config.rateLimits.authFail, 30).ok).toBe(true)
  })

  it('counts missing and malformed tokens without a lookup, and every other 401 once', async () => {
    const { cache, relay } = setup(3)
    expect(await outcome(authenticateToken(relay, '192.0.2.1', undefined))).toBe(401)
    let thrown: ApiError | undefined
    try {
      await authenticateToken(relay, '192.0.2.1', 'not-a-token')
    } catch (error) {
      thrown = error as ApiError
    }
    expect(thrown?.status).toBe(401)
    // Already counted: reporting it again does not count twice.
    recordUnauthorized(relay, '192.0.2.1', thrown!)
    expect(cache.lookups).toBe(0)
    // A 401 from elsewhere (an account deleted while its request ran) counts.
    recordUnauthorized(relay, '192.0.2.1', unauthorized())
    expect(await outcome(authenticateToken(relay, '192.0.2.1', b64u(randomBytes(32))))).toBe(429)
  })

  it('answers from the cache without spending the budget', async () => {
    const { cache, relay } = setup(1)
    const token = randomBytes(32)
    const account = randomBytes(16)
    cache.cached.set(sha256Hex(token), account)
    for (let i = 0; i < 5; i++) expect(await authenticateToken(relay, '192.0.2.9', b64u(token))).toEqual(account)
    expect(cache.lookups).toBe(0)
  })
})
