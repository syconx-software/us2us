import { EPHEMERAL_BATCH_MAX_ITEMS } from './validation'

/**
 * Relay configuration, read once from the process environment at startup.
 *
 * The variable names are deliberately unprefixed (DATABASE_URL, MEDIA_DIR, ...)
 * because PROTOCOL.md and the deploy docs refer to them by these names. Every
 * value is validated here so that a misconfigured relay refuses to start
 * instead of failing on the first request. See `.env.example` for the
 * documented list.
 */

export type LogLevel = 'error' | 'warn' | 'info' | 'debug'

/** `limit` events per `windowMs`; `limit = Infinity` disables the limit. */
export interface Rate {
  limit: number
  windowMs: number
}

export interface RateLimits {
  pow: Rate
  register: Rate
  directory: Rate
  keyFetch: Rate
  send: Rate
  ephemeral: Rate
  mediaUploads: Rate
  mediaBytes: Rate
  mediaFetch: Rate
  usernameChanges: Rate
  wsConnect: Rate
  wsFrames: Rate
  account: Rate
  /** Failed `X-Auth-Token` checks per client address (§4.11). */
  authFail: Rate
}

export interface RelayConfig {
  databaseUrl: string
  dbPoolSize: number
  powSecret: Buffer
  powDifficultyRegister: number
  powDifficultyMessage: number
  powTtlMs: number
  envelopeMaxBytes: number
  envelopeTtlMs: number
  /** Per-recipient cap on stored envelopes (count and body bytes); sends beyond it get 429. */
  mailboxMaxEnvelopes: number
  mailboxMaxBytes: number
  wsAckTimeoutMs: number
  wsIdleTimeoutMs: number
  wsMaxFrameBytes: number
  mediaDir: string
  mediaMaxBytes: number
  mediaTtlMs: number
  /** Free space below which MEDIA_DIR's file system takes no more uploads or stored envelopes; 0 disables the check. */
  diskMinFreeBytes: number
  usernameHoldMs: number
  inactiveAccountDays: number
  retentionIntervalMs: number
  trustProxy: boolean
  logIps: boolean
  logLevel: LogLevel
  rateLimits: RateLimits
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const MiB = 1024 * 1024

export class ConfigError extends Error {
  constructor(readonly problems: string[]) {
    super(`invalid relay configuration:\n  - ${problems.join('\n  - ')}`)
    this.name = 'ConfigError'
  }
}

type Env = Record<string, string | undefined>

class EnvReader {
  readonly problems: string[] = []

  constructor(private readonly env: Env) {}

  raw(name: string): string | undefined {
    const value = this.env[name]?.trim()
    return value === undefined || value === '' ? undefined : value
  }

  string(name: string, fallback: string): string {
    return this.raw(name) ?? fallback
  }

  required(name: string): string {
    const value = this.raw(name)
    if (value === undefined) this.problems.push(`${name} is required`)
    return value ?? ''
  }

  int(name: string, fallback: number, min: number, max: number): number {
    const value = this.raw(name)
    if (value === undefined) return fallback
    const parsed = /^\d+$/.test(value) ? Number(value) : Number.NaN
    if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
      this.problems.push(`${name} must be an integer in ${min}..${max}`)
      return fallback
    }
    return parsed
  }

  bool(name: string, fallback: boolean): boolean {
    const value = this.raw(name)?.toLowerCase()
    if (value === undefined) return fallback
    if (['1', 'true', 'yes', 'on'].includes(value)) return true
    if (['0', 'false', 'no', 'off'].includes(value)) return false
    this.problems.push(`${name} must be a boolean (1/0, true/false)`)
    return fallback
  }

  /** Parses `<count>/<sec|min|hour|day>` or `off`. */
  rate(name: string, fallback: Rate): Rate {
    const value = this.raw(name)?.toLowerCase()
    if (value === undefined) return fallback
    if (value === 'off') return { limit: Number.POSITIVE_INFINITY, windowMs: fallback.windowMs }
    const match = /^(\d+)\s*\/\s*(sec|min|hour|day)$/.exec(value)
    const count = match ? Number(match[1]) : Number.NaN
    if (!match || !Number.isSafeInteger(count) || count < 1) {
      this.problems.push(`${name} must look like "30/min" (units: sec, min, hour, day) or "off"`)
      return fallback
    }
    const windows = { sec: 1000, min: MINUTE, hour: HOUR, day: DAY } as const
    return { limit: count, windowMs: windows[match[2] as keyof typeof windows] }
  }
}

export function loadConfig(source: Env = process.env): RelayConfig {
  const env = new EnvReader(source)

  const powSecret = env.required('POW_SECRET')
  if (powSecret && Buffer.byteLength(powSecret) < 32) {
    env.problems.push('POW_SECRET must be at least 32 characters (e.g. `openssl rand -base64 48`)')
  }

  const config: RelayConfig = {
    databaseUrl: env.required('DATABASE_URL'),
    dbPoolSize: env.int('DB_POOL_SIZE', 10, 1, 200),
    powSecret: Buffer.from(powSecret, 'utf8'),
    powDifficultyRegister: env.int('POW_DIFFICULTY_REGISTER', 22, 0, 64),
    powDifficultyMessage: env.int('POW_DIFFICULTY_MESSAGE', 18, 0, 64),
    powTtlMs: env.int('POW_TTL_SECONDS', 600, 10, 86_400) * 1000,
    envelopeMaxBytes: env.int('ENVELOPE_MAX_BYTES', 64 * 1024, 1024, 64 * 1024),
    envelopeTtlMs: env.int('ENVELOPE_TTL_DAYS', 14, 1, 30) * DAY,
    mailboxMaxEnvelopes: env.int('MAILBOX_MAX_ENVELOPES', 10_000, 100, 10_000_000),
    // At least one envelope of the largest size must fit.
    mailboxMaxBytes: env.int('MAILBOX_MAX_BYTES', 50 * MiB, 64 * 1024, 64 * 1024 * MiB),
    wsAckTimeoutMs: env.int('WS_ACK_TIMEOUT_MS', 10_000, 50, 120_000),
    wsIdleTimeoutMs: env.int('WS_IDLE_TIMEOUT_MS', 90_000, 1000, 3_600_000),
    wsMaxFrameBytes: env.int('WS_MAX_FRAME_BYTES', 32 * 1024, 1024, 1024 * 1024),
    mediaDir: env.string('MEDIA_DIR', './.data/media'),
    mediaMaxBytes: env.int('MEDIA_MAX_BYTES', 64 * MiB, 1, 1024 * MiB),
    mediaTtlMs: env.int('MEDIA_TTL_DAYS', 14, 1, 30) * DAY,
    diskMinFreeBytes: env.int('DISK_MIN_FREE_MB', 1024, 0, 16 * 1024 * 1024) * MiB,
    usernameHoldMs: env.int('USERNAME_HOLD_DAYS', 14, 0, 365) * DAY,
    inactiveAccountDays: env.int('INACTIVE_ACCOUNT_DAYS', 180, 1, 3650),
    retentionIntervalMs: env.int('RETENTION_INTERVAL_SECONDS', 300, 1, 86_400) * 1000,
    trustProxy: env.bool('TRUST_PROXY', false),
    logIps: env.bool('LOG_IPS', false),
    logLevel: parseLogLevel(env),
    rateLimits: {
      pow: env.rate('RATE_LIMIT_POW', { limit: 30, windowMs: MINUTE }),
      register: env.rate('RATE_LIMIT_REGISTER', { limit: 5, windowMs: HOUR }),
      directory: env.rate('RATE_LIMIT_DIRECTORY', { limit: 60, windowMs: HOUR }),
      keyFetch: env.rate('RATE_LIMIT_KEY_FETCH', { limit: 120, windowMs: HOUR }),
      send: env.rate('RATE_LIMIT_SEND', { limit: 600, windowMs: MINUTE }),
      ephemeral: env.rate('RATE_LIMIT_EPHEMERAL', { limit: 3000, windowMs: MINUTE }),
      mediaUploads: env.rate('RATE_LIMIT_MEDIA_UPLOADS', { limit: 60, windowMs: HOUR }),
      mediaBytes: env.rate('RATE_LIMIT_MEDIA_BYTES', { limit: 2048 * MiB, windowMs: DAY }),
      mediaFetch: env.rate('RATE_LIMIT_MEDIA_FETCH', { limit: 600, windowMs: MINUTE }),
      usernameChanges: env.rate('RATE_LIMIT_USERNAME_CHANGES', { limit: 5, windowMs: DAY }),
      wsConnect: env.rate('RATE_LIMIT_WS_CONNECT', { limit: 60, windowMs: MINUTE }),
      wsFrames: env.rate('RATE_LIMIT_WS_FRAMES', { limit: 1200, windowMs: MINUTE }),
      account: env.rate('RATE_LIMIT_ACCOUNT', { limit: 1200, windowMs: MINUTE }),
      authFail: env.rate('RATE_LIMIT_AUTH_FAIL', { limit: 30, windowMs: MINUTE }),
    },
  }

  // Each item of an ephemeral batch costs one token, so a full batch must fit in the bucket.
  if (config.rateLimits.ephemeral.limit < EPHEMERAL_BATCH_MAX_ITEMS) {
    env.problems.push(`RATE_LIMIT_EPHEMERAL must allow at least ${EPHEMERAL_BATCH_MAX_ITEMS} envelopes per window`)
  }
  if (config.databaseUrl && !/^mysql:\/\//.test(config.databaseUrl)) {
    env.problems.push('DATABASE_URL must be a mysql:// URL')
  }
  if (env.problems.length > 0) throw new ConfigError(env.problems)
  return config
}

function parseLogLevel(env: EnvReader): LogLevel {
  const value = env.string('LOG_LEVEL', 'info').toLowerCase()
  if (value === 'error' || value === 'warn' || value === 'info' || value === 'debug') return value
  env.problems.push('LOG_LEVEL must be one of error, warn, info, debug')
  return 'info'
}
