import { createDatabase, isDuplicateKey, type Database } from './db/client'
import { accounts, usedPowTokens } from './db/schema'
import { loadConfig, type RelayConfig } from './lib/config'
import { DiskSpace } from './lib/disk-space'
import { describeError } from './lib/errors'
import { EnvelopeIdGenerator } from './lib/envelope-id'
import { Logger } from './lib/logger'
import { PowAuthority, type PowPurpose } from './lib/pow'
import { ConcurrencyLimiter, RateLimiter } from './lib/rate-limit'
import type { PowSolution } from './lib/validation'
import { EnvelopePersister } from './realtime/persister'
import { CLOSE_UNAUTHORIZED, SessionManager } from './realtime/sessions'
import { AccountCache } from './services/account-cache'
import { AccountStore } from './services/accounts'
import { Mailbox } from './services/mailbox'
import { MediaStore } from './services/media'
import { PreKeyStore } from './services/prekeys'
import { RetentionSweeper } from './services/retention'

const HOUSEKEEPING_INTERVAL_MS = 60_000
const DISK_CHECK_INTERVAL_MS = 5_000

/**
 * The relay's process-wide state, created once at startup by the relay
 * plugin. Route handlers and WebSocket hooks reach it through `useRelay()`.
 */
export class Relay {
  readonly logger: Logger
  readonly database: Database
  readonly accounts: AccountStore
  readonly accountCache: AccountCache
  readonly preKeys: PreKeyStore
  readonly mailbox: Mailbox
  readonly media: MediaStore
  readonly persister: EnvelopePersister
  readonly sessions: SessionManager
  readonly pow: PowAuthority
  readonly disk: DiskSpace
  readonly limiter = new RateLimiter()
  readonly inFlight = new ConcurrencyLimiter()
  readonly envelopeIds = new EnvelopeIdGenerator()
  private readonly retention: RetentionSweeper
  private readonly timers: NodeJS.Timeout[] = []
  private retentionRun: Promise<void> | null = null

  constructor(readonly config: RelayConfig) {
    this.logger = new Logger(config.logLevel)
    this.database = createDatabase(config.databaseUrl, config.dbPoolSize)
    this.accounts = new AccountStore(this.database)
    this.accountCache = new AccountCache(this.accounts, this.logger)
    this.preKeys = new PreKeyStore(this.database)
    this.mailbox = new Mailbox(this.database, { maxEnvelopes: config.mailboxMaxEnvelopes, maxBytes: config.mailboxMaxBytes })
    this.media = new MediaStore(this.database, config.mediaDir)
    this.disk = new DiskSpace(config.mediaDir, config.diskMinFreeBytes)
    this.pow = new PowAuthority(config.powSecret, config.powTtlMs, {
      register: config.powDifficultyRegister,
      message: config.powDifficultyMessage,
    })
    this.persister = new EnvelopePersister(this.mailbox, this.logger)
    this.sessions = new SessionManager({
      mailbox: this.mailbox,
      persister: this.persister,
      ackTimeoutMs: config.wsAckTimeoutMs,
      idleTimeoutMs: config.wsIdleTimeoutMs,
      logger: this.logger,
    })
    this.retention = new RetentionSweeper({
      database: this.database,
      mailbox: this.mailbox,
      media: this.media,
      envelopeTtlMs: config.envelopeTtlMs,
      inactiveAccountDays: config.inactiveAccountDays,
      deleteAccount: accountId => this.deleteAccount(accountId),
      inactiveAccounts: (date, limit) => this.accounts.inactiveSince(date, limit),
    })
  }

  async start(): Promise<void> {
    await this.media.init()
    await this.refreshDiskSpace()
    this.every(DISK_CHECK_INTERVAL_MS, () => void this.refreshDiskSpace())
    this.every(this.config.retentionIntervalMs, () => this.runRetention())
    this.every(HOUSEKEEPING_INTERVAL_MS, () => {
      const now = Date.now()
      this.limiter.sweep(now)
      this.accountCache.sweep(now)
    })
    this.every(Math.min(15_000, Math.ceil(this.config.wsIdleTimeoutMs / 3)), () => this.sessions.sweepIdle())

    this.logger.info('relay started', { trustProxy: this.config.trustProxy })
    try {
      await this.database.db.select({ id: accounts.id }).from(accounts).limit(1)
    } catch (error) {
      this.logger.error('database check failed; is MySQL up and are migrations applied (pnpm db:migrate)?', {
        error: describeError(error),
      })
    }
  }

  /**
   * Redeems a proof-of-work solution: verifies it and records the token as
   * used. Returns false if anything is wrong, including reuse.
   */
  async redeemPow(solution: PowSolution, purpose: PowPurpose, target: string | null): Promise<boolean> {
    const verified = this.pow.verify(solution.token, solution.nonce, purpose, target, Date.now())
    if (!verified) return false
    try {
      await this.database.db.insert(usedPowTokens).values(verified)
      return true
    } catch (error) {
      if (isDuplicateKey(error)) return false
      throw error
    }
  }

  /**
   * The only place a client address can reach the log: with LOG_IPS=1, a
   * request refused by a per-IP rate limit is logged, to investigate abuse.
   */
  noteRateLimitedAddress(bucket: string, address: string): void {
    if (this.config.logIps) this.logger.info('rate limited', { bucket, ip: address })
  }

  /** Deletes an account and everything stored for it, in the database, on disk and in memory. */
  async deleteAccount(accountId: Buffer): Promise<void> {
    const accountKey = accountId.toString('hex')
    const uploads = await this.accounts.delete(accountId)
    this.accountCache.forget(accountId)
    this.sessions.disconnect(accountKey, CLOSE_UNAUTHORIZED, 'account deleted', true)
    this.persister.drop(accountKey)
    this.mailbox.forgetUsage(accountKey)
    await this.media.removeFiles(uploads)
  }

  /**
   * First step of a graceful shutdown, run synchronously on SIGTERM before
   * Nitro tears down the sockets: from now on envelopes go to the mailbox,
   * and open sessions are closed with 1001 after handing their
   * unacknowledged envelopes to the persister.
   */
  beginShutdown(): void {
    for (const timer of this.timers) clearInterval(timer)
    this.timers.length = 0
    this.sessions.shutdown()
  }

  /**
   * Second step, after in-flight requests have finished: store everything
   * the persister still holds, then close the pool.
   */
  async stop(deadline: number): Promise<void> {
    this.beginShutdown()
    const complete = await this.persister.drain(deadline)
    if (!complete) {
      this.logger.error('shutdown deadline reached with undelivered envelopes still in memory', { count: this.persister.size })
    }
    await this.retentionRun
    await this.database.close()
  }

  private async refreshDiskSpace(): Promise<void> {
    await this.disk.refresh().catch((error: unknown) => {
      this.logger.warn('checking free disk space failed', { error: describeError(error) })
    })
  }

  private every(intervalMs: number, task: () => void): void {
    const timer = setInterval(task, intervalMs)
    timer.unref()
    this.timers.push(timer)
  }

  private runRetention(): void {
    if (this.retentionRun) return
    const started = Date.now()
    this.retentionRun = this.retention.run(started)
      .then((result) => {
        const removed = Object.values(result).reduce((sum, n) => sum + n, 0)
        if (removed > 0) this.logger.info('retention sweep', { ...result, ms: Date.now() - started })
      })
      .catch((error: unknown) => this.logger.error('retention sweep failed', { error: describeError(error) }))
      .finally(() => {
        this.retentionRun = null
      })
  }
}

let instance: Relay | undefined

/** Creates the relay on first use; throws ConfigError if the environment is invalid. */
export function useRelay(): Relay {
  instance ??= new Relay(loadConfig())
  return instance
}
