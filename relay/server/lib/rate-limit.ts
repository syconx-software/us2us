import { createHmac, randomBytes } from 'node:crypto'
import { isIPv4, isIPv6 } from 'node:net'
import type { Rate } from './config'

interface Bucket {
  tokens: number
  updatedAt: number
  /** When the bucket will be full again; after that it carries no information. */
  fullAt: number
  limit: number
  windowMs: number
}

export type TakeResult = { ok: true } | { ok: false, retryAfterSeconds: number }

/**
 * In-memory token buckets (PROTOCOL.md §4.11). A rate of `limit / window`
 * becomes a bucket of capacity `limit` refilled continuously at
 * `limit / window`. One process owns all buckets, which is why the relay runs
 * as a single instance. Memory stays bounded: buckets that have refilled
 * completely carry no information and are swept, and when a flood of
 * distinct clients fills the map anyway, the buckets closest to full are
 * dropped first (those clients merely start over with a full bucket). A
 * bucket that has been drained, such as the failed-token budget of an
 * address that is guessing tokens, goes last: touching many other keys
 * cannot push it out, because each of those is nearly full.
 */
export class RateLimiter {
  private readonly buckets = new Map<string, Bucket>()

  constructor(private readonly maxBuckets = 200_000) {}

  take(name: string, key: string, rate: Rate, cost = 1, now = Date.now()): TakeResult {
    const result = this.peek(name, key, rate, cost, now)
    if (!result.ok || !Number.isFinite(rate.limit)) return result
    const id = `${name}\u0000${key}`
    if (!this.buckets.has(id) && this.buckets.size >= this.maxBuckets) this.makeRoom(now)
    this.store(id, rate, this.tokens(id, rate, now) - cost, now)
    return result
  }

  /** Whether `take` would succeed, without spending anything. */
  peek(name: string, key: string, rate: Rate, cost = 1, now = Date.now()): TakeResult {
    if (!Number.isFinite(rate.limit)) return { ok: true }
    const tokens = this.tokens(`${name}\u0000${key}`, rate, now)
    if (tokens >= cost) return { ok: true }
    // A cost above the capacity can never be paid; report a full window.
    const waitMs = cost > rate.limit ? rate.windowMs : (cost - tokens) * rate.windowMs / rate.limit
    return { ok: false, retryAfterSeconds: Math.max(1, Math.ceil(waitMs / 1000)) }
  }

  /** Gives back what an earlier `take` spent, for a reservation that turned out not to be needed. */
  refund(name: string, key: string, rate: Rate, cost = 1, now = Date.now()): void {
    const id = `${name}\u0000${key}`
    if (!Number.isFinite(rate.limit) || !this.buckets.has(id)) return
    this.store(id, rate, Math.min(rate.limit, this.tokens(id, rate, now) + cost), now)
  }

  private tokens(id: string, rate: Rate, now: number): number {
    const bucket = this.buckets.get(id)
    return bucket ? Math.min(rate.limit, bucket.tokens + (now - bucket.updatedAt) * rate.limit / rate.windowMs) : rate.limit
  }

  private store(id: string, rate: Rate, tokens: number, now: number): void {
    this.buckets.set(id, { tokens, updatedAt: now, fullAt: now + (rate.limit - tokens) * rate.windowMs / rate.limit, limit: rate.limit, windowMs: rate.windowMs })
  }

  /** Drops buckets that have refilled completely. */
  sweep(now = Date.now()): void {
    for (const [id, bucket] of this.buckets) {
      if (bucket.fullAt <= now) this.buckets.delete(id)
    }
  }

  /**
   * Frees at least a tenth of the capacity, so that a full map costs one
   * sweep per many insertions instead of one per request. What goes first
   * is what is closest to full again: the share of its capacity it will
   * have refilled now, so the most drained buckets stay.
   */
  private makeRoom(now: number): void {
    this.sweep(now)
    const target = Math.floor(this.maxBuckets * 0.9)
    if (this.buckets.size <= target) return
    const fill = (bucket: Bucket) => Math.min(1, (bucket.tokens + (now - bucket.updatedAt) * bucket.limit / bucket.windowMs) / bucket.limit)
    const order = [...this.buckets].sort(([, a], [, b]) => fill(b) - fill(a))
    for (const [id] of order) {
      if (this.buckets.size <= target) break
      this.buckets.delete(id)
    }
  }

  get size(): number {
    return this.buckets.size
  }
}

/**
 * The key under which a client address is rate limited. IPv6 clients usually
 * own a whole /64, so limiting single addresses would be pointless.
 */
export function ipRateKey(address: string): string {
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address)
  if (mapped) return mapped[1]!
  if (isIPv4(address) || !isIPv6(address)) return address
  const [head = '', tail = ''] = address.split('::')
  const headGroups = head ? head.split(':') : []
  const tailGroups = tail ? tail.split(':') : []
  const groups = address.includes('::')
    ? [...headGroups, ...Array<string>(8 - headGroups.length - tailGroups.length).fill('0'), ...tailGroups]
    : headGroups
  const prefix = groups.slice(0, 4).map(group => group.toLowerCase().replace(/^0+(?=.)/, ''))
  return `${prefix.join(':')}::/64`
}

/**
 * The key for limits that cover a whole site: an IPv6 /48 (what a site or a
 * small provider is usually given), or the address itself for IPv4, whose
 * addresses are scarce and often shared through NAT.
 */
export function ipPrefixRateKey(address: string): string {
  const key = ipRateKey(address)
  if (!key.endsWith('::/64')) return key
  const groups = key.slice(0, -'::/64'.length).split(':')
  return `${groups.slice(0, 3).join(':')}::/48`
}

/**
 * A limiter key for a pair (such as recipient and client address) that is
 * never written anywhere: a MAC under a key that exists only in this
 * process, so the map of buckets does not hold the pair in clear, and
 * nothing links the pair once the process ends or the bucket is swept.
 */
export class PairKeys {
  private readonly key = randomBytes(32)

  of(...parts: string[]): string {
    return createHmac('sha256', this.key).update(parts.join('\u0000')).digest('base64url').slice(0, 22)
  }
}

/**
 * Requests in flight per key. A token bucket bounds requests per minute, but
 * its whole burst may arrive at once; this bounds how many of them are
 * being served (and holding database connections or response buffers) at
 * the same time.
 */
export class ConcurrencyLimiter {
  private readonly running = new Map<string, number>()

  /** Takes a slot; false if `key` already has `max` requests running. */
  enter(key: string, max: number): boolean {
    const count = this.running.get(key) ?? 0
    if (count >= max) return false
    this.running.set(key, count + 1)
    return true
  }

  leave(key: string): void {
    const count = this.running.get(key) ?? 0
    if (count <= 1) this.running.delete(key)
    else this.running.set(key, count - 1)
  }

  get size(): number {
    return this.running.size
  }
}
