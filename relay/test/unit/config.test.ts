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
    expect(config.rateLimits.avatarFetch).toEqual({ limit: 60, windowMs: 3_600_000 })
    expect(config.rateLimits.avatarWrite).toEqual({ limit: 30, windowMs: 86_400_000 })
    expect(config.rateLimits.avatarDelete).toEqual({ limit: 200, windowMs: 86_400_000 })
    expect(config.rateLimits.communityCreate).toEqual({ limit: 5, windowMs: 3_600_000 })
    expect(config.rateLimits.communitySend).toEqual({ limit: 6000, windowMs: 60_000 })
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

  it('applies the 2026-10-28 audit limits by default', () => {
    const config = loadConfig(base)
    expect(config).toMatchObject({
      powDifficultyRegisterMax: 26,
      powRegisterAdaptPerHour: 120,
      mailboxPowMaxEnvelopes: 500,
      mailboxPowMaxBytes: 8 * 1024 * 1024,
      diskEnvelopeReserveBytes: 2048 * 1024 * 1024,
      mediaStoredMaxBytes: 2048 * 1024 * 1024,
      mediaStoredMaxNewBytes: 256 * 1024 * 1024,
      newAccountDays: 7,
      trustProxyFrom: ['127.0.0.1', '::1'],
      trustProxyHeader: 'x-forwarded-for',
    })
    expect(config.rateLimits.registerGlobal).toEqual({ limit: 1000, windowMs: 3_600_000 })
    expect(config.rateLimits.registerPrefix).toEqual({ limit: 20, windowMs: 3_600_000 })
    expect(config.rateLimits.keyTarget).toEqual({ limit: 60, windowMs: 3_600_000 })
    expect(config.rateLimits.sendPairPow).toEqual({ limit: 60, windowMs: 3_600_000 })
    expect(config.rateLimits.sendPairBytes).toEqual({ limit: 32 * 1024 * 1024, windowMs: 3_600_000 })
    expect(config.rateLimits.mediaBytesNew).toEqual({ limit: 512 * 1024 * 1024, windowMs: 86_400_000 })
    expect(config.rateLimits.mediaBytesGlobal).toEqual({ limit: 24 * 1024 ** 3, windowMs: 86_400_000 })
    expect(config.rateLimits.communityPair).toEqual({ limit: 5000, windowMs: 86_400_000 })
    // A small mailbox gets a proportionally small proof-of-work share.
    expect(loadConfig({ ...base, MAILBOX_MAX_ENVELOPES: '100', MAILBOX_MAX_BYTES: '1048576' })).toMatchObject({ mailboxPowMaxEnvelopes: 50, mailboxPowMaxBytes: 524_288 })
  })

  it('keeps at least half of every mailbox for access-key senders and checks the other new settings', () => {
    expect(problems({ MAILBOX_POW_MAX_ENVELOPES: '5001' })).toEqual([expect.stringContaining('MAILBOX_POW_MAX')])
    expect(problems({ MAILBOX_POW_MAX_BYTES: String(26 * 1024 * 1024) })).toEqual([expect.stringContaining('MAILBOX_POW_MAX')])
    expect(problems({ MAILBOX_POW_MAX_ENVELOPES: '5000', MAILBOX_POW_MAX_BYTES: String(25 * 1024 * 1024) })).toEqual([])
    expect(problems({ POW_DIFFICULTY_REGISTER: '24', POW_DIFFICULTY_REGISTER_MAX: '23' })).toEqual([expect.stringContaining('POW_DIFFICULTY_REGISTER_MAX')])
    expect(problems({ POW_DIFFICULTY_REGISTER_MAX: '29' })).toEqual([expect.stringContaining('POW_DIFFICULTY_REGISTER_MAX')])
    expect(problems({ MEDIA_STORED_MAX_NEW_MB: '4096' })).toEqual([expect.stringContaining('MEDIA_STORED_MAX_NEW_MB')])
    expect(problems({ MEDIA_STORED_MAX_NEW_MB: '32' })).toEqual([expect.stringContaining('MEDIA_STORED_MAX_NEW_MB')])
    expect(problems({ TRUST_PROXY_FROM: '127.0.0.1, nginx' })).toEqual([expect.stringContaining('TRUST_PROXY_FROM')])
    expect(problems({ TRUST_PROXY_HEADER: 'forwarded' })).toEqual([expect.stringContaining('TRUST_PROXY_HEADER')])
    expect(loadConfig({ ...base, TRUST_PROXY_FROM: '10.0.0.2, ::1', TRUST_PROXY_HEADER: 'X-Real-IP' }))
      .toMatchObject({ trustProxyFrom: ['10.0.0.2', '::1'], trustProxyHeader: 'x-real-ip' })
  })
})
