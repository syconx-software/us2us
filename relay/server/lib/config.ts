import { isIP } from 'node:net'
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
  /** `POST /v1/accounts` from the whole relay: a ceiling for sybil floods, at the price of slower sign-up during one. */
  registerGlobal: Rate
  /** `POST /v1/accounts` per IPv6 /48 (an IPv4 address keeps only its own bucket): one site cannot rotate through its /64s. */
  registerPrefix: Rate
  directory: Rate
  /** `GET /v1/accounts/{id}/avatar` per account; a `304` counts (§4.11). */
  avatarFetch: Rate
  /** `PUT /v1/accounts/me/avatar` (publishing a record), per account (§4.11). */
  avatarWrite: Rate
  /**
   * `DELETE /v1/accounts/me/avatar` per account, a budget of its own (§4.11): turning
   * "Show in search" off must not wait for a day of picture changes.
   */
  avatarDelete: Rate
  keyFetch: Rate
  /**
   * Bundles handed out per target account with one-time keys; past it a
   * bundle carries the signed and last-resort keys only, so one-time keys
   * cannot be drained by many requesters.
   */
  keyTarget: Rate
  send: Rate
  /**
   * Per recipient and client address (§4.6), keyed by a process-local MAC
   * and never stored: proof-of-work sends, and bytes of every stored send.
   */
  sendPairPow: Rate
  sendPairBytes: Rate
  ephemeral: Rate
  mediaUploads: Rate
  mediaBytes: Rate
  /** Upload bytes per account while it is younger than NEW_ACCOUNT_DAYS. */
  mediaBytesNew: Rate
  /** Upload bytes of the whole relay; past it uploads get 503 and messages keep flowing. */
  mediaBytesGlobal: Rate
  mediaFetch: Rate
  usernameChanges: Rate
  wsConnect: Rate
  wsFrames: Rate
  account: Rate
  /** Failed `X-Auth-Token` checks per client address (§4.11). */
  authFail: Rate
  /** Community routes (COMMUNITIES.md): all of them per client address, */
  communityIp: Rate
  /** and per account: creations, join requests and invitation redemptions, deliveries, everything else. */
  communityCreate: Rate
  communityJoin: Rate
  communitySend: Rate
  /** Community deliveries per sender and recipient over all communities, tombstones included. */
  communityPair: Rate
  communityApi: Rate
}

export type ProxyHeader = 'x-forwarded-for' | 'x-real-ip'

export interface RelayConfig {
  databaseUrl: string
  dbPoolSize: number
  powSecret: Buffer
  powDifficultyRegister: number
  /** Ceiling of the adaptive registration difficulty (§4.2). */
  powDifficultyRegisterMax: number
  /** Registrations per hour above which the registration difficulty rises; 0 keeps it fixed. */
  powRegisterAdaptPerHour: number
  powDifficultyMessage: number
  powTtlMs: number
  envelopeMaxBytes: number
  envelopeTtlMs: number
  /** Per-recipient cap on stored envelopes (count and body bytes); sends beyond it get 429. */
  mailboxMaxEnvelopes: number
  mailboxMaxBytes: number
  /**
   * The share of a mailbox that envelopes authorized by proof-of-work (not
   * by the access key) may take, so strangers cannot block a user's contacts.
   */
  mailboxPowMaxEnvelopes: number
  mailboxPowMaxBytes: number
  wsAckTimeoutMs: number
  wsIdleTimeoutMs: number
  wsMaxFrameBytes: number
  mediaDir: string
  mediaMaxBytes: number
  mediaTtlMs: number
  /** Free space below which MEDIA_DIR's file system takes no more uploads or stored envelopes; 0 disables the check. */
  diskMinFreeBytes: number
  /** Free space above the floor that media uploads must leave for envelopes. */
  diskEnvelopeReserveBytes: number
  /** Unexpired media bytes one account may have stored; a lower cap while it is new. */
  mediaStoredMaxBytes: number
  mediaStoredMaxNewBytes: number
  /** Accounts younger than this many days get the new-account media limits; 0 disables them. */
  newAccountDays: number
  usernameHoldMs: number
  inactiveAccountDays: number
  retentionIntervalMs: number
  trustProxy: boolean
  /** Peer addresses whose proxy header is believed (TRUST_PROXY=1 only). */
  trustProxyFrom: string[]
  trustProxyHeader: ProxyHeader
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
    powDifficultyRegisterMax: env.int('POW_DIFFICULTY_REGISTER_MAX', 26, 0, 28),
    powRegisterAdaptPerHour: env.int('POW_REGISTER_ADAPT_PER_HOUR', 120, 0, 1_000_000),
    powDifficultyMessage: env.int('POW_DIFFICULTY_MESSAGE', 18, 0, 64),
    powTtlMs: env.int('POW_TTL_SECONDS', 600, 10, 86_400) * 1000,
    envelopeMaxBytes: env.int('ENVELOPE_MAX_BYTES', 64 * 1024, 1024, 64 * 1024),
    envelopeTtlMs: env.int('ENVELOPE_TTL_DAYS', 14, 1, 30) * DAY,
    mailboxMaxEnvelopes: env.int('MAILBOX_MAX_ENVELOPES', 10_000, 100, 10_000_000),
    // At least one envelope of the largest size must fit.
    mailboxMaxBytes: env.int('MAILBOX_MAX_BYTES', 50 * MiB, 64 * 1024, 64 * 1024 * MiB),
    mailboxPowMaxEnvelopes: 0,
    mailboxPowMaxBytes: 0,
    wsAckTimeoutMs: env.int('WS_ACK_TIMEOUT_MS', 10_000, 50, 120_000),
    wsIdleTimeoutMs: env.int('WS_IDLE_TIMEOUT_MS', 90_000, 1000, 3_600_000),
    wsMaxFrameBytes: env.int('WS_MAX_FRAME_BYTES', 32 * 1024, 1024, 1024 * 1024),
    mediaDir: env.string('MEDIA_DIR', './.data/media'),
    mediaMaxBytes: env.int('MEDIA_MAX_BYTES', 64 * MiB, 1, 1024 * MiB),
    mediaTtlMs: env.int('MEDIA_TTL_DAYS', 14, 1, 30) * DAY,
    diskMinFreeBytes: env.int('DISK_MIN_FREE_MB', 1024, 0, 16 * 1024 * 1024) * MiB,
    diskEnvelopeReserveBytes: env.int('DISK_ENVELOPE_RESERVE_MB', 2048, 0, 16 * 1024 * 1024) * MiB,
    mediaStoredMaxBytes: env.int('MEDIA_STORED_MAX_MB', 2048, 1, 16 * 1024 * 1024) * MiB,
    mediaStoredMaxNewBytes: env.int('MEDIA_STORED_MAX_NEW_MB', 256, 1, 16 * 1024 * 1024) * MiB,
    newAccountDays: env.int('NEW_ACCOUNT_DAYS', 7, 0, 365),
    usernameHoldMs: env.int('USERNAME_HOLD_DAYS', 14, 0, 365) * DAY,
    inactiveAccountDays: env.int('INACTIVE_ACCOUNT_DAYS', 180, 1, 3650),
    retentionIntervalMs: env.int('RETENTION_INTERVAL_SECONDS', 300, 1, 86_400) * 1000,
    trustProxy: env.bool('TRUST_PROXY', false),
    trustProxyFrom: parseProxyPeers(env),
    trustProxyHeader: parseProxyHeader(env),
    logIps: env.bool('LOG_IPS', false),
    logLevel: parseLogLevel(env),
    rateLimits: {
      pow: env.rate('RATE_LIMIT_POW', { limit: 30, windowMs: MINUTE }),
      register: env.rate('RATE_LIMIT_REGISTER', { limit: 5, windowMs: HOUR }),
      registerGlobal: env.rate('RATE_LIMIT_REGISTER_GLOBAL', { limit: 1000, windowMs: HOUR }),
      registerPrefix: env.rate('RATE_LIMIT_REGISTER_PREFIX', { limit: 20, windowMs: HOUR }),
      directory: env.rate('RATE_LIMIT_DIRECTORY', { limit: 60, windowMs: HOUR }),
      avatarFetch: env.rate('RATE_LIMIT_AVATAR_FETCH', { limit: 60, windowMs: HOUR }),
      avatarWrite: env.rate('RATE_LIMIT_AVATAR_WRITE', { limit: 30, windowMs: DAY }),
      avatarDelete: env.rate('RATE_LIMIT_AVATAR_DELETE', { limit: 200, windowMs: DAY }),
      keyFetch: env.rate('RATE_LIMIT_KEY_FETCH', { limit: 120, windowMs: HOUR }),
      keyTarget: env.rate('RATE_LIMIT_KEY_TARGET', { limit: 60, windowMs: HOUR }),
      send: env.rate('RATE_LIMIT_SEND', { limit: 600, windowMs: MINUTE }),
      sendPairPow: env.rate('RATE_LIMIT_SEND_PAIR_POW', { limit: 60, windowMs: HOUR }),
      sendPairBytes: env.rate('RATE_LIMIT_SEND_PAIR_BYTES', { limit: 32 * MiB, windowMs: HOUR }),
      ephemeral: env.rate('RATE_LIMIT_EPHEMERAL', { limit: 3000, windowMs: MINUTE }),
      mediaUploads: env.rate('RATE_LIMIT_MEDIA_UPLOADS', { limit: 60, windowMs: HOUR }),
      mediaBytes: env.rate('RATE_LIMIT_MEDIA_BYTES', { limit: 2048 * MiB, windowMs: DAY }),
      mediaBytesNew: env.rate('RATE_LIMIT_MEDIA_BYTES_NEW', { limit: 512 * MiB, windowMs: DAY }),
      mediaBytesGlobal: env.rate('RATE_LIMIT_MEDIA_BYTES_GLOBAL', { limit: 24 * 1024 * MiB, windowMs: DAY }),
      mediaFetch: env.rate('RATE_LIMIT_MEDIA_FETCH', { limit: 600, windowMs: MINUTE }),
      usernameChanges: env.rate('RATE_LIMIT_USERNAME_CHANGES', { limit: 5, windowMs: DAY }),
      wsConnect: env.rate('RATE_LIMIT_WS_CONNECT', { limit: 60, windowMs: MINUTE }),
      wsFrames: env.rate('RATE_LIMIT_WS_FRAMES', { limit: 1200, windowMs: MINUTE }),
      account: env.rate('RATE_LIMIT_ACCOUNT', { limit: 1200, windowMs: MINUTE }),
      authFail: env.rate('RATE_LIMIT_AUTH_FAIL', { limit: 30, windowMs: MINUTE }),
      communityIp: env.rate('RATE_LIMIT_COMMUNITY_IP', { limit: 12000, windowMs: MINUTE }),
      communityCreate: env.rate('RATE_LIMIT_COMMUNITY_CREATE', { limit: 5, windowMs: HOUR }),
      communityJoin: env.rate('RATE_LIMIT_COMMUNITY_JOIN', { limit: 20, windowMs: MINUTE }),
      communitySend: env.rate('RATE_LIMIT_COMMUNITY_SEND', { limit: 6000, windowMs: MINUTE }),
      communityPair: env.rate('RATE_LIMIT_COMMUNITY_PAIR', { limit: 5000, windowMs: DAY }),
      communityApi: env.rate('RATE_LIMIT_COMMUNITY_API', { limit: 600, windowMs: MINUTE }),
    },
  }

  // Each item of an ephemeral batch costs one token, so a full batch must fit in the bucket.
  if (config.rateLimits.ephemeral.limit < EPHEMERAL_BATCH_MAX_ITEMS) {
    env.problems.push(`RATE_LIMIT_EPHEMERAL must allow at least ${EPHEMERAL_BATCH_MAX_ITEMS} envelopes per window`)
  }
  // Unless set, the proof-of-work share is 500 envelopes and 8 MiB, or half of a smaller mailbox.
  config.mailboxPowMaxEnvelopes = env.int('MAILBOX_POW_MAX_ENVELOPES', Math.min(500, Math.floor(config.mailboxMaxEnvelopes / 2)), 1, 10_000_000)
  config.mailboxPowMaxBytes = env.int('MAILBOX_POW_MAX_BYTES', Math.min(8 * MiB, Math.floor(config.mailboxMaxBytes / 2)), 1, 64 * 1024 * MiB)
  if (config.powDifficultyRegisterMax < config.powDifficultyRegister) {
    env.problems.push('POW_DIFFICULTY_REGISTER_MAX must not be below POW_DIFFICULTY_REGISTER')
  }
  // Contacts (access key) always keep at least half of every mailbox.
  if (config.mailboxPowMaxEnvelopes * 2 > config.mailboxMaxEnvelopes || config.mailboxPowMaxBytes * 2 > config.mailboxMaxBytes) {
    env.problems.push('MAILBOX_POW_MAX_ENVELOPES and MAILBOX_POW_MAX_BYTES must be at most half of MAILBOX_MAX_ENVELOPES and MAILBOX_MAX_BYTES')
  }
  if (config.mediaStoredMaxNewBytes > config.mediaStoredMaxBytes || config.mediaStoredMaxNewBytes < config.mediaMaxBytes) {
    env.problems.push('MEDIA_STORED_MAX_NEW_MB must be between MEDIA_MAX_BYTES and MEDIA_STORED_MAX_MB')
  }
  if (config.databaseUrl && !/^mysql:\/\//.test(config.databaseUrl)) {
    env.problems.push('DATABASE_URL must be a mysql:// URL')
  }
  if (env.problems.length > 0) throw new ConfigError(env.problems)
  return config
}

/** TRUST_PROXY_FROM: comma-separated peer addresses (normally the local nginx). */
function parseProxyPeers(env: EnvReader): string[] {
  const peers = env.string('TRUST_PROXY_FROM', '127.0.0.1,::1').split(',').map(value => value.trim().toLowerCase()).filter(Boolean)
  if (peers.length === 0 || peers.some(peer => isIP(peer) === 0)) {
    env.problems.push('TRUST_PROXY_FROM must list IP addresses, separated by commas')
    return ['127.0.0.1', '::1']
  }
  return peers
}

function parseProxyHeader(env: EnvReader): ProxyHeader {
  const value = env.string('TRUST_PROXY_HEADER', 'x-forwarded-for').toLowerCase()
  if (value === 'x-forwarded-for' || value === 'x-real-ip') return value
  env.problems.push('TRUST_PROXY_HEADER must be x-forwarded-for or x-real-ip')
  return 'x-forwarded-for'
}

function parseLogLevel(env: EnvReader): LogLevel {
  const value = env.string('LOG_LEVEL', 'info').toLowerCase()
  if (value === 'error' || value === 'warn' || value === 'info' || value === 'debug') return value
  env.problems.push('LOG_LEVEL must be one of error, warn, info, debug')
  return 'info'
}
