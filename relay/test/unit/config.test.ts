import { describe, expect, it } from 'vitest'
import { ConfigError, loadConfig } from '../../server/lib/config'

const base = { DATABASE_URL: 'mysql://u:p@localhost/db', POW_SECRET: 'x'.repeat(32) }

function problems(env: Record<string, string>): string[] {
  try {
    loadConfig({ ...base, ...env })
    return []
  } catch (error) {
    if (error instanceof ConfigError) return error.problems
    throw error
  }
}

describe('loadConfig', () => {
  it('applies the protocol defaults', () => {
    const config = loadConfig(base)
    expect(config).toMatchObject({
      powDifficultyRegister: 22,
      powDifficultyMessage: 18,
      powTtlMs: 600_000,
      envelopeMaxBytes: 65_536,
      envelopeTtlMs: 14 * 86_400_000,
      wsAckTimeoutMs: 10_000,
      mediaMaxBytes: 64 * 1024 * 1024,
      mediaTtlMs: 14 * 86_400_000,
      usernameHoldMs: 14 * 86_400_000,
      inactiveAccountDays: 180,
      trustProxy: false,
      logIps: false,
    })
    expect(config.rateLimits.pow).toEqual({ limit: 30, windowMs: 60_000 })
    expect(config.rateLimits.register).toEqual({ limit: 5, windowMs: 3_600_000 })
    expect(config.rateLimits.mediaBytes).toEqual({ limit: 2 * 1024 ** 3, windowMs: 86_400_000 })
    expect(config.rateLimits.ephemeral).toEqual({ limit: 3000, windowMs: 60_000 })
    expect(config.rateLimits.authFail).toEqual({ limit: 30, windowMs: 60_000 })
    expect(config).toMatchObject({ mailboxMaxEnvelopes: 10_000, mailboxMaxBytes: 50 * 1024 * 1024, diskMinFreeBytes: 1024 * 1024 * 1024 })
  })

  it('keeps a mailbox able to hold at least one envelope', () => {
    expect(problems({ MAILBOX_MAX_BYTES: '65535' })).toEqual([expect.stringContaining('MAILBOX_MAX_BYTES')])
    expect(problems({ MAILBOX_MAX_BYTES: '65536', DISK_MIN_FREE_MB: '0' })).toEqual([])
  })

  it('reports every problem at once', () => {
    expect(() => loadConfig({})).toThrow(ConfigError)
    expect(problems({ DATABASE_URL: '', POW_SECRET: '' })).toEqual(['POW_SECRET is required', 'DATABASE_URL is required'])
    expect(problems({ POW_SECRET: 'short', DATABASE_URL: 'postgres://x' })).toHaveLength(2)
    expect(problems({ MEDIA_MAX_BYTES: '-1', TRUST_PROXY: 'maybe', RATE_LIMIT_POW: '30 per minute', LOG_LEVEL: 'loud' })).toHaveLength(4)
  })

  it('parses rates and booleans', () => {
    const config = loadConfig({ ...base, RATE_LIMIT_SEND: '10/sec', RATE_LIMIT_POW: 'off', TRUST_PROXY: 'true', LOG_IPS: '1' })
    expect(config.rateLimits.send).toEqual({ limit: 10, windowMs: 1000 })
    expect(config.rateLimits.pow.limit).toBe(Number.POSITIVE_INFINITY)
    expect(config.trustProxy).toBe(true)
    expect(config.logIps).toBe(true)
  })

  it('requires an ephemeral rate that fits a full batch', () => {
    expect(problems({ RATE_LIMIT_EPHEMERAL: '199/min' })).toEqual([expect.stringContaining('RATE_LIMIT_EPHEMERAL')])
    expect(problems({ RATE_LIMIT_EPHEMERAL: '200/min' })).toEqual([])
    expect(problems({ RATE_LIMIT_EPHEMERAL: 'off' })).toEqual([])
  })
})
