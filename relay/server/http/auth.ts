import type { RateLimits } from '../lib/config'
import { sha256 } from '../lib/crypto'
import { decodeB64uExact } from '../lib/encoding'
import { rateLimited, unauthorized, type ApiError } from '../lib/errors'
import { ipRateKey, type RateLimiter } from '../lib/rate-limit'
import type { AccountCache } from '../services/account-cache'

/** The parts of the relay that token checks use (shared by HTTP requests and WebSocket upgrades). */
export interface TokenAuthority {
  config: { rateLimits: Pick<RateLimits, 'authFail'> }
  limiter: RateLimiter
  accountCache: AccountCache
}

const AUTH_FAIL = 'auth-fail'

interface Lookups {
  running: number
  /** Requests waiting for one of the running lookups to end. */
  waiting: Array<() => void>
}

/** Token lookups running per client address (per limiter, i.e. per relay). */
const lookupsByLimiter = new WeakMap<RateLimiter, Map<string, Lookups>>()

/** 401s that `authenticateToken` has already counted. */
const counted = new WeakSet<ApiError>()

/**
 * Resolves an `X-Auth-Token` header to an account id (§4); throws 401 for a
 * missing, malformed or unknown token, and enforces the §4.11 limit on
 * failed token checks per client address:
 *
 * - An address over the limit gets 429 before its token is looked up.
 * - A database lookup spends one unit of the address's budget while it
 *   runs; a valid token gives it back, an unknown one keeps it spent. So a
 *   burst of concurrent made-up tokens leads to at most as many lookups as
 *   the budget allows, not one per request.
 * - A request that finds the budget used up only by lookups still running
 *   waits for them instead of failing, so that many clients behind one
 *   address reconnecting at once (after a relay restart, with an empty
 *   token cache) are delayed a little, not refused.
 */
export async function authenticateToken(relay: TokenAuthority, address: string, header: string | null | undefined): Promise<Buffer> {
  const { limiter, accountCache } = relay
  const rate = relay.config.rateLimits.authFail
  const key = ipRateKey(address)
  const lookups = lookupsOf(limiter)
  const gate = limiter.peek(AUTH_FAIL, key, rate)
  if (!gate.ok && !lookups.has(key)) throw rateLimited(gate.retryAfterSeconds)

  const token = decodeB64uExact(header, 32)
  if (!token) {
    limiter.take(AUTH_FAIL, key, rate)
    throw failedCheck()
  }
  const tokenHash = sha256(token)
  const cached = accountCache.cachedAccount(tokenHash)
  if (cached) return cached

  for (;;) {
    const reservation = limiter.take(AUTH_FAIL, key, rate)
    if (reservation.ok) break
    if (!lookups.has(key)) throw rateLimited(reservation.retryAfterSeconds)
    await new Promise<void>(resolve => lookups.get(key)!.waiting.push(resolve))
  }
  const entry = lookups.get(key) ?? { running: 0, waiting: [] }
  entry.running++
  lookups.set(key, entry)
  let accountId: Buffer | null = null
  try {
    accountId = await accountCache.authenticate(tokenHash)
  } catch (error) {
    limiter.refund(AUTH_FAIL, key, rate)
    throw error
  } finally {
    if (--entry.running === 0) lookups.delete(key)
    for (const wake of entry.waiting.splice(0)) wake()
  }
  if (!accountId) throw failedCheck()
  limiter.refund(AUTH_FAIL, key, rate)
  return accountId
}

/**
 * Counts a 401 that `authenticateToken` did not produce (an account deleted
 * while its request ran) against the client address: every request answered
 * 401 counts as a failed token check (§4.11).
 */
export function recordUnauthorized(relay: TokenAuthority, address: string, error: ApiError): void {
  if (!counted.has(error)) relay.limiter.take(AUTH_FAIL, ipRateKey(address), relay.config.rateLimits.authFail)
}

function lookupsOf(limiter: RateLimiter): Map<string, Lookups> {
  let lookups = lookupsByLimiter.get(limiter)
  if (!lookups) lookupsByLimiter.set(limiter, lookups = new Map())
  return lookups
}

function failedCheck(): ApiError {
  const error = unauthorized()
  counted.add(error)
  return error
}
