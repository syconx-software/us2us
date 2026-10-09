import { utcDate } from '../lib/encoding'
import { describeError } from '../lib/errors'
import type { Logger } from '../lib/logger'
import { TtlCache } from '../lib/ttl-cache'
import type { AccountStore } from './accounts'

const TTL_MS = 5 * 60_000
const MAX_ENTRIES = 100_000
/**
 * How long a `forget` is remembered for lookups that were already running.
 * A lookup that takes longer than this is treated as stale and repeated.
 */
const FORGET_MEMORY_MS = 60_000
const MAX_LOOKUP_ATTEMPTS = 3

/**
 * Keeps auth token → account out of MySQL for every authenticated request
 * and WebSocket upgrade. The relay is a single process and every code path
 * that changes a token or deletes an account calls `forget`, so the cache is
 * never stale; the TTL only bounds memory.
 *
 * Recipients (access-key hashes) are deliberately not cached: whether a
 * lookup is answered from memory would tell anyone who times a sealed send
 * whether the recipient received something in the last few minutes.
 *
 * It also writes the coarse last-seen date at most once per account per day.
 */
export class AccountCache {
  private readonly byToken = new TtlCache<string, Buffer>(TTL_MS, MAX_ENTRIES)
  private readonly tokenOf = new Map<string, string>()
  private readonly seenOn = new Map<string, string>()
  /** Bumped by every `forget`; each forgotten account remembers the value it got. */
  private generation = 0
  private readonly forgotten = new Map<string, { generation: number, at: number }>()

  constructor(
    private readonly accounts: AccountStore,
    private readonly logger: Logger,
  ) {}

  cachedAccount(authTokenHash: Buffer): Buffer | undefined {
    return this.byToken.get(authTokenHash.toString('hex'))
  }

  /**
   * A lookup can read the row just before a token rotation or an account
   * deletion commits and finish after that change called `forget`. Its
   * result is then stale, and returning it would open a WebSocket that the
   * rotation already disconnected everyone from (§4.3: the old token stops
   * working immediately), so the lookup is repeated instead.
   */
  async authenticate(authTokenHash: Buffer): Promise<Buffer | null> {
    const tokenKey = authTokenHash.toString('hex')
    const cached = this.byToken.get(tokenKey)
    if (cached) return cached
    for (let attempt = 1; ; attempt++) {
      const generation = this.generation
      const started = Date.now()
      const accountId = await this.accounts.findByAuthTokenHash(authTokenHash)
      if (!accountId) return null
      if (!this.forgottenSince(accountId, generation, started)) {
        this.byToken.set(tokenKey, accountId)
        this.tokenOf.set(accountId.toString('hex'), tokenKey)
        return accountId
      }
      if (attempt === MAX_LOOKUP_ATTEMPTS) throw new Error('the account kept changing while its token was looked up')
    }
  }

  /** Invalidates everything cached for an account. */
  forget(accountId: Buffer): void {
    const key = accountId.toString('hex')
    this.forgotten.set(key, { generation: ++this.generation, at: Date.now() })
    const tokenKey = this.tokenOf.get(key)
    if (tokenKey !== undefined) this.byToken.delete(tokenKey)
    this.tokenOf.delete(key)
    this.seenOn.delete(key)
  }

  /** Fire-and-forget: at most one UPDATE per account per UTC day. */
  touch(accountId: Buffer, now = Date.now()): void {
    const key = accountId.toString('hex')
    const today = utcDate(now)
    if (this.seenOn.get(key) === today) return
    this.seenOn.set(key, today)
    this.accounts.touch(accountId, today).catch((error: unknown) => {
      this.seenOn.delete(key)
      this.logger.warn('updating last-seen date failed', { error: describeError(error) })
    })
  }

  sweep(now = Date.now()): void {
    this.byToken.sweep(now)
    for (const [key, tokenKey] of this.tokenOf) {
      if (this.byToken.get(tokenKey, now) === undefined) this.tokenOf.delete(key)
    }
    const today = utcDate(now)
    for (const [key, day] of this.seenOn) {
      if (day !== today) this.seenOn.delete(key)
    }
    for (const [key, { at }] of this.forgotten) {
      if (now - at > FORGET_MEMORY_MS) this.forgotten.delete(key)
    }
  }

  private forgottenSince(accountId: Buffer, generation: number, started: number): boolean {
    const entry = this.forgotten.get(accountId.toString('hex'))
    return (entry !== undefined && entry.generation > generation) || Date.now() - started > FORGET_MEMORY_MS
  }
}
