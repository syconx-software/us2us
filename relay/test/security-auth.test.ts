import { randomBytes } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { RateLimiter } from '../server/lib/rate-limit'
import { Logger } from '../server/lib/logger'
import { EnvelopePersister } from '../server/realtime/persister'
import { SessionManager, CLOSE_UNAUTHORIZED, type SessionSocket } from '../server/realtime/sessions'
import { relayWebSocketHooks } from '../server/realtime/websocket'
import { AccountCache } from '../server/services/account-cache'
import type { AccountStore } from '../server/services/accounts'
import type { Mailbox } from '../server/services/mailbox'
import { Api, b64u, ecKey, register, sha256, solvedPow, registrationBody, type TestAccount } from './support/client'
import { communityApi } from './support/community'
import { TestDb, uuidBytes } from './support/db'
import { startRelay, type RelayProcess } from './support/relay'

/*
 * Security review of authentication: regression tests for findings AUTH-1 ..
 * AUTH-5. Every test here states the behaviour PROTOCOL.md requires and
 * failed against the relay before its fix.
 */

// The WebSocket hooks reach the relay through useRelay(); AUTH-5 swaps in a
// relay assembled from the real cache and session manager.
const fakeRelay = vi.hoisted(() => ({ current: undefined as unknown }))
vi.mock('../server/relay', () => ({ useRelay: () => fakeRelay.current }))

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
/** Lets the event loop turn a few times, to stagger two requests by less than a millisecond. */
const yields = async (turns: number) => {
  for (let i = 0; i < turns; i++) await new Promise(resolve => setImmediate(resolve))
}
const randomToken = () => b64u(randomBytes(32))
const newUsername = () => ({ usernameHash: b64u(randomBytes(32)) })

let db: TestDb

beforeAll(async () => {
  db = await TestDb.connect()
})

afterAll(async () => {
  await db.close()
})

describe('AUTH-1: failed X-Auth-Token checks are rate limited per IP (§4.11, 30/min)', () => {
  let relay: RelayProcess
  let api: Api

  beforeAll(async () => {
    // Named explicitly so the test keeps meaning the §4.11 default once the variable exists.
    relay = await startRelay({ RATE_LIMIT_AUTH_FAIL: '30/min' })
    api = new Api(relay.url)
  })

  afterAll(async () => {
    await relay.stop()
  })

  it('answers 429 once an IP has failed 30 token checks, before looking the token up', async () => {
    const statuses: number[] = []
    for (let i = 0; i < 30; i++) statuses.push((await api.get('/v1/keys/status', { token: randomToken() })).status)
    expect(statuses).toEqual(Array(30).fill(401))

    const over = await api.get('/v1/keys/status', { token: randomToken() })
    expect(over.status).toBe(429)
    expect(over.body.error.code).toBe('rate_limited')
    expect(Number(over.headers.get('retry-after'))).toBeGreaterThanOrEqual(1)
  })
})

describe('account row races (§3.3, §4.3)', () => {
  let relay: RelayProcess
  let api: Api

  beforeAll(async () => {
    relay = await startRelay({ RETENTION_INTERVAL_SECONDS: '1' })
    api = new Api(relay.url)
  })

  afterAll(async () => {
    await relay.stop()
  })

  /** Puts the token in the relay's cache, so that requests reach their transactions without a lookup. */
  const warm = async (account: TestAccount) =>
    expect((await api.get('/v1/accounts/me', { token: account.authToken })).status).toBe(200)

  const currentNames = (account: TestAccount) =>
    db.count('usernames WHERE account_id = ? AND held_until IS NULL', [uuidBytes(account.accountId)])

  it('AUTH-3: concurrent username changes leave exactly one current username', async () => {
    const violations: Array<{ trial: number, current: number }> = []
    for (let trial = 0; trial < 30 && violations.length === 0; trial++) {
      const account = await register(api)
      const changes = await Promise.all(Array.from({ length: 4 }, () =>
        api.put('/v1/accounts/me/username', { token: account.authToken, json: newUsername() })))
      expect(changes.map(r => r.status)).toEqual([204, 204, 204, 204])
      const current = await currentNames(account)
      if (current !== 1) violations.push({ trial, current })
    }
    expect(violations).toEqual([])
  })

  it('AUTH-2: deleting an account while it changes its username releases every name', async () => {
    const orphans: Array<{ trial: number, heldUntil: Array<number | null>, hash: Buffer }> = []
    for (let trial = 0; trial < 100 && orphans.length === 0; trial++) {
      const account = await register(api)
      const id = uuidBytes(account.accountId)
      await warm(account)
      // The change authenticates before the delete forgets the token, and its
      // transaction runs after the delete has cleared the usernames table.
      const deleting = api.delete('/v1/accounts/me', { token: account.authToken })
      await yields(trial % 5)
      const change = api.put('/v1/accounts/me/username', { token: account.authToken, json: newUsername() })
      const [deleted] = await Promise.all([deleting, change])
      if (deleted.status !== 204) continue
      expect(await db.count('accounts WHERE id = ?', [id])).toBe(0)
      const rows = await db.query<{ hash: Buffer, heldUntil: number | null }>(
        'SELECT username_hash AS hash, held_until AS heldUntil FROM usernames WHERE account_id = ?', [id])
      if (rows.length > 0) orphans.push({ trial, heldUntil: rows.map(r => r.heldUntil), hash: rows[0]!.hash })
    }

    let leftAfterRetention = 0
    let reRegisterStatus = 201
    if (orphans.length > 0) {
      // The left-over name is not findable, yet "taken" for everyone, and no sweep removes it.
      await sleep(2500) // RETENTION_INTERVAL_SECONDS=1, so sweeps have run
      leftAfterRetention = await db.count('usernames WHERE username_hash = ?', [orphans[0]!.hash])
      const body = registrationBody({ usernameHash: b64u(orphans[0]!.hash) })
      const pow = await solvedPow(api, 'purpose=register')
      reRegisterStatus = (await api.post('/v1/accounts', { json: { ...body, pow } })).status
    }
    expect({ orphans: orphans.map(({ trial, heldUntil }) => ({ trial, heldUntil })), leftAfterRetention, reRegisterStatus })
      .toEqual({ orphans: [], leftAfterRetention: 0, reRegisterStatus: 201 })
  })

  it('AUTH-4: deleting an account while it uploads keys removes all its one-time keys', async () => {
    const orphans: Array<{ trial: number, keyIds: number[] }> = []
    for (let trial = 0; trial < 60 && orphans.length === 0; trial++) {
      const account = await register(api)
      const id = uuidBytes(account.accountId)
      await warm(account)
      const deleting = api.delete('/v1/accounts/me', { token: account.authToken })
      await yields(trial % 5)
      const upload = api.put('/v1/keys', {
        token: account.authToken,
        json: { oneTimePreKeys: [{ keyId: 77, publicKey: b64u(ecKey()) }] },
      })
      const [deleted] = await Promise.all([deleting, upload])
      if (deleted.status !== 204) continue
      expect(await db.count('accounts WHERE id = ?', [id])).toBe(0)
      const rows = await db.query<{ keyId: number }>('SELECT key_id AS keyId FROM one_time_pre_keys WHERE account_id = ?', [id])
      if (rows.length > 0) orphans.push({ trial, keyIds: rows.map(r => r.keyId) })
    }
    expect(orphans).toEqual([])
  })
})

describe('AUTH-5: a token rotated during an upgrade lookup must not open a socket (§4.3)', () => {
  /**
   * One account row. The first lookup reads it at once and answers only when
   * released, like a SELECT that read the row just before a concurrent
   * UPDATE committed; later lookups answer right away.
   */
  class RacingStore {
    readonly accountId = randomBytes(16)
    tokenHash: Buffer
    readonly held: Array<() => void> = []
    private holdNext = true

    constructor(token: Buffer) {
      this.tokenHash = sha256(token)
    }

    async findByAuthTokenHash(hash: Buffer): Promise<Buffer | null> {
      const row = hash.equals(this.tokenHash) ? this.accountId : null
      if (this.holdNext) {
        this.holdNext = false
        await new Promise<void>(resolve => this.held.push(resolve))
      }
      return row
    }

    async touch(): Promise<void> {}
  }

  class Socket implements SessionSocket {
    closedWith: number | null = null
    readonly bufferedAmount = 0
    get isOpen() {
      return this.closedWith === null
    }

    send(_text: string): void {}
    close(code: number): void {
      this.closedWith ??= code
    }

    terminate(): void {
      this.close(1006)
    }
  }

  it('refuses the upgrade (or closes the socket with 4401) when the token was rotated while it was being checked', async () => {
    const oldToken = randomBytes(32)
    const store = new RacingStore(oldToken)
    const logger = new Logger('error')
    const mailbox = { fetch: async () => [], insert: async () => {}, delete: async () => {} } as unknown as Mailbox
    const persister = new EnvelopePersister(mailbox, logger)
    const sessions = new SessionManager({ mailbox, persister, ackTimeoutMs: 10_000, idleTimeoutMs: 90_000, logger })
    const accountCache = new AccountCache(store as unknown as AccountStore, logger)
    fakeRelay.current = {
      config: {
        trustProxy: false,
        rateLimits: {
          wsConnect: { limit: Number.POSITIVE_INFINITY, windowMs: 60_000 },
          authFail: { limit: Number.POSITIVE_INFINITY, windowMs: 60_000 },
        },
      },
      limiter: new RateLimiter(),
      accountCache,
      sessions,
      logger,
    }

    const context: Record<string, unknown> = {}
    const request = {
      url: 'http://relay.test/v1/ws',
      headers: new Headers({ 'x-auth-token': b64u(oldToken) }),
      context,
      _req: { socket: { remoteAddress: '127.0.0.1' } },
    }
    const upgrading = relayWebSocketHooks.upgrade!(request as never)
    await sleep(0)
    expect(store.held).toHaveLength(1) // the lookup has read the row with the old token

    // POST /v1/accounts/me/token, exactly as its route does it.
    store.tokenHash = sha256(randomBytes(32))
    accountCache.forget(store.accountId)
    sessions.disconnect(store.accountId.toString('hex'), CLOSE_UNAUTHORIZED, 'token rotated', false)

    store.held.shift()!()
    const refused = await upgrading as Response | undefined
    const socket = new Socket()
    if (!refused) {
      // crossws completes the upgrade and calls `open` with the same context.
      const peer = {
        context,
        websocket: { readyState: 1, bufferedAmount: 0 },
        send: (text: string) => socket.send(text),
        close: (code: number) => socket.close(code),
        terminate: () => socket.terminate(),
      }
      relayWebSocketHooks.open!(peer as never)
      await sleep(10)
    }

    const outcome = {
      upgradeStatus: refused?.status ?? 101,
      sessionAfterRotation: sessions.isOnline(store.accountId.toString('hex')) && socket.isOpen,
    }
    // Either refusal (401) or an immediate 4401 close keeps §4.3's promise.
    expect(outcome.sessionAfterRotation).toBe(false)
    expect(outcome.upgradeStatus === 401 || socket.closedWith === CLOSE_UNAUTHORIZED).toBe(true)
  })
})

describe('community lock order (COMMUNITIES.md)', () => {
  let relay: RelayProcess
  let api: Api
  let groups: ReturnType<typeof communityApi>

  beforeAll(async () => {
    relay = await startRelay()
    api = new Api(relay.url)
    groups = communityApi(api)
  })

  afterAll(async () => {
    await relay.stop()
  })

  const allowed = (statuses: number[], expected: number[]) => statuses.filter(status => !expected.includes(status))

  it('AUTH-6: deleting an owner or member account while community sends run fails no request and leaves no mail', async () => {
    const unexpected: Array<{ trial: number, statuses: number[] }> = []
    for (let trial = 0; trial < 12; trial++) {
      const owner = await register(api), member = await register(api), other = await register(api)
      const group = await groups.approve(owner, await groups.created(owner), [member, other])
      const victim = trial % 2 ? owner : member
      const pairs: Array<[TestAccount, TestAccount]> = [[owner, member], [member, owner], [other, member], [member, other], [owner, other], [other, owner]]
      const sends = pairs.flatMap(([from, to]) => [groups.deliver(from, to, group), groups.deliver(from, to, group)])
      await yields(trial % 4)
      const deletion = api.delete('/v1/accounts/me', { token: victim.authToken })
      const statuses = (await Promise.all(sends)).map(response => response.status)
      expect((await deletion).status).toBe(204)
      const odd = allowed(statuses, [202, 401, 404])
      if (odd.length > 0) unexpected.push({ trial, statuses })
      const id = uuidBytes(victim.accountId)
      expect(await db.count('community_envelopes WHERE sender = ? OR recipient = ?', [id, id])).toBe(0)
      expect(await db.count('community_members WHERE account_id = ?', [id])).toBe(0)
      if (victim === owner) expect(await db.count('communities WHERE id = ?', [uuidBytes(group.id)])).toBe(0)
    }
    expect(unexpected).toEqual([])
    expect(relay.errors()).toEqual([])
  })

  it('AUTH-7: a member leaving while sends to and from it run keeps no mail for it', async () => {
    for (let trial = 0; trial < 8; trial++) {
      const owner = await register(api), leaver = await register(api), other = await register(api)
      const group = await groups.approve(owner, await groups.created(owner), [leaver, other])
      const sends = [groups.deliver(owner, leaver, group), groups.deliver(leaver, other, group), groups.deliver(other, leaver, group), groups.deliver(leaver, owner, group)]
      await yields(trial % 4)
      const leaving = groups.leave(leaver, group.id)
      const statuses = (await Promise.all(sends)).map(response => response.status)
      expect((await leaving).status).toBe(204)
      expect(allowed(statuses, [202, 404])).toEqual([])
      const id = uuidBytes(leaver.accountId)
      expect(await db.count('community_envelopes WHERE community_id = ? AND (sender = ? OR recipient = ?)', [uuidBytes(group.id), id, id])).toBe(0)
    }
    expect(relay.errors()).toEqual([])
  })
})
