import { describe, expect, it } from 'vitest'
import { ConcurrencyLimiter, ipPrefixRateKey, ipRateKey, PairKeys, RateLimiter } from '../../server/lib/rate-limit'

const perMinute = (limit: number) => ({ limit, windowMs: 60_000 })

describe('RateLimiter', () => {
  it('allows a burst up to the limit, then refills continuously', () => {
    const limiter = new RateLimiter()
    for (let i = 0; i < 3; i++) expect(limiter.take('pow', 'ip', perMinute(3), 1, 0).ok).toBe(true)
    expect(limiter.take('pow', 'ip', perMinute(3), 1, 0)).toEqual({ ok: false, retryAfterSeconds: 20 })
    expect(limiter.take('pow', 'ip', perMinute(3), 1, 19_000).ok).toBe(false)
    expect(limiter.take('pow', 'ip', perMinute(3), 1, 20_000).ok).toBe(true)
  })

  it('keeps names and keys apart', () => {
    const limiter = new RateLimiter()
    expect(limiter.take('a', 'k', perMinute(1), 1, 0).ok).toBe(true)
    expect(limiter.take('a', 'k', perMinute(1), 1, 0).ok).toBe(false)
    expect(limiter.take('b', 'k', perMinute(1), 1, 0).ok).toBe(true)
    expect(limiter.take('a', 'other', perMinute(1), 1, 0).ok).toBe(true)
  })

  it('charges weighted costs and reports a full window for impossible ones', () => {
    const limiter = new RateLimiter()
    const bytes = { limit: 1000, windowMs: 86_400_000 }
    expect(limiter.take('bytes', 'acct', bytes, 900, 0).ok).toBe(true)
    expect(limiter.take('bytes', 'acct', bytes, 200, 0)).toEqual({ ok: false, retryAfterSeconds: 8640 })
    expect(limiter.take('bytes', 'acct', bytes, 5000, 0)).toEqual({ ok: false, retryAfterSeconds: 86_400 })
  })

  it('peeks without spending and refunds what a reservation took', () => {
    const limiter = new RateLimiter()
    expect(limiter.peek('a', 'k', perMinute(2), 1, 0).ok).toBe(true)
    expect(limiter.take('a', 'k', perMinute(2), 1, 0).ok).toBe(true)
    expect(limiter.take('a', 'k', perMinute(2), 1, 0).ok).toBe(true)
    expect(limiter.peek('a', 'k', perMinute(2), 1, 0)).toEqual({ ok: false, retryAfterSeconds: 30 })
    limiter.refund('a', 'k', perMinute(2), 1, 0)
    expect(limiter.peek('a', 'k', perMinute(2), 1, 0).ok).toBe(true)
    // A refund never fills a bucket beyond its capacity.
    limiter.refund('a', 'k', perMinute(2), 5, 0)
    expect(limiter.take('a', 'k', perMinute(2), 3, 0).ok).toBe(false)
    expect(limiter.take('a', 'k', perMinute(2), 2, 0).ok).toBe(true)
  })

  it('treats an infinite limit as off', () => {
    const limiter = new RateLimiter()
    for (let i = 0; i < 1000; i++) expect(limiter.take('x', 'y', { limit: Number.POSITIVE_INFINITY, windowMs: 1 }).ok).toBe(true)
    expect(limiter.size).toBe(0)
  })

  it('sweeps buckets that have refilled', () => {
    const limiter = new RateLimiter()
    limiter.take('a', '1', perMinute(2), 1, 0)
    limiter.take('a', '2', perMinute(2), 2, 0)
    limiter.sweep(30_000)
    expect(limiter.size).toBe(1)
    limiter.sweep(60_000)
    expect(limiter.size).toBe(0)
  })

  it('sweeps when it reaches its size bound', () => {
    const limiter = new RateLimiter(2)
    limiter.take('a', '1', perMinute(5), 1, 0)
    limiter.take('a', '2', perMinute(5), 1, 0)
    limiter.take('a', '3', perMinute(5), 1, 60_000)
    expect(limiter.size).toBe(1)
  })

  it('stays within its size bound under a flood of distinct clients', () => {
    const limiter = new RateLimiter(100)
    for (let i = 0; i < 1000; i++) expect(limiter.take('a', String(i), perMinute(5), 1, 0).ok).toBe(true)
    expect(limiter.size).toBeLessThanOrEqual(100)
    // The newest clients keep their buckets.
    expect(limiter.take('a', '999', perMinute(5), 4, 0).ok).toBe(true)
    expect(limiter.take('a', '999', perMinute(5), 1, 0).ok).toBe(false)
  })
})

describe('RateLimiter eviction (audit M-3)', () => {
  it('keeps a drained bucket while a flood of other keys fills the map, so a penalty cannot be pushed out', () => {
    const limiter = new RateLimiter(100)
    const authFail = perMinute(30)
    // An address that used up its failed-token budget first ...
    for (let i = 0; i < 30; i++) limiter.take('auth-fail', 'attacker', authFail, 1, 0)
    expect(limiter.peek('auth-fail', 'attacker', authFail, 1, 0).ok).toBe(false)
    // ... then touches far more keys than the map holds.
    for (let i = 0; i < 10_000; i++) limiter.take('auth-fail', `other-${i}`, authFail, 1, 1000)
    expect(limiter.size).toBeLessThanOrEqual(100)
    expect(limiter.peek('auth-fail', 'attacker', authFail, 1, 1000).ok).toBe(false)
  })
})

describe('ipPrefixRateKey', () => {
  it('keys IPv6 by /48 and IPv4 by address', () => {
    expect(ipPrefixRateKey('2001:db8:aa:1::1')).toBe('2001:db8:aa::/48')
    expect(ipPrefixRateKey('2001:db8:aa:ffff:1:2:3:4')).toBe('2001:db8:aa::/48')
    expect(ipPrefixRateKey('2001:db8::1')).toBe('2001:db8:0::/48')
    expect(ipPrefixRateKey('203.0.113.9')).toBe('203.0.113.9')
    expect(ipPrefixRateKey('::ffff:203.0.113.9')).toBe('203.0.113.9')
  })
})

describe('PairKeys', () => {
  it('derives a stable key per pair within a process, without the pair in clear, and another one in another process', () => {
    const keys = new PairKeys()
    const key = keys.of('recipient-hex', '198.51.100.1')
    expect(keys.of('recipient-hex', '198.51.100.1')).toBe(key)
    expect(keys.of('recipient-hex', '198.51.100.2')).not.toBe(key)
    expect(keys.of('recipient-he', 'x198.51.100.1')).not.toBe(key)
    expect(key).not.toContain('198.51')
    expect(key).toMatch(/^[\w-]{22}$/)
    expect(new PairKeys().of('recipient-hex', '198.51.100.1')).not.toBe(key)
  })
})

describe('ipRateKey', () => {
  it('keys IPv4 by address and IPv6 by /64', () => {
    expect(ipRateKey('203.0.113.9')).toBe('203.0.113.9')
    expect(ipRateKey('::ffff:203.0.113.9')).toBe('203.0.113.9')
    expect(ipRateKey('2001:db8:0:1::1')).toBe('2001:db8:0:1::/64')
    expect(ipRateKey('2001:0DB8:0000:0001:aaaa:bbbb:cccc:dddd')).toBe('2001:db8:0:1::/64')
    expect(ipRateKey('2001:db8::1')).toBe('2001:db8:0:0::/64')
    expect(ipRateKey('::1')).toBe('0:0:0:0::/64')
    expect(ipRateKey('unknown')).toBe('unknown')
  })
})

describe('ConcurrencyLimiter', () => {
  it('bounds what runs at once per key and frees slots as requests end', () => {
    const limiter = new ConcurrencyLimiter()
    expect(limiter.enter('a', 2)).toBe(true)
    expect(limiter.enter('a', 2)).toBe(true)
    expect(limiter.enter('a', 2)).toBe(false)
    expect(limiter.enter('b', 2)).toBe(true)
    limiter.leave('a')
    expect(limiter.enter('a', 2)).toBe(true)
    for (const key of ['a', 'a', 'b']) limiter.leave(key)
    expect(limiter.size).toBe(0)
  })
})
