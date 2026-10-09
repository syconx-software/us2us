import { randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { Logger } from '../../server/lib/logger'
import { AccountCache } from '../../server/services/account-cache'
import type { AccountStore } from '../../server/services/accounts'

/** Counts lookups; each lookup can be held until the test releases it. */
class FakeStore {
  lookups = 0
  held: Array<() => void> = []
  hold = false
  /** The token hash the account row currently has. */
  tokenHash: Buffer | null = null

  constructor(readonly accountId: Buffer) {}

  /** Reads the row first, then (optionally) takes its time to answer, like a query racing with an update. */
  async findByAuthTokenHash(hash: Buffer): Promise<Buffer | null> {
    this.lookups++
    const row = this.tokenHash === null || hash.equals(this.tokenHash) ? this.accountId : null
    if (this.hold) await new Promise<void>(resolve => this.held.push(resolve))
    return row
  }

  async touch(): Promise<void> {}
}

function setup() {
  const accountId = randomBytes(16)
  const store = new FakeStore(accountId)
  const cache = new AccountCache(store as unknown as AccountStore, new Logger('error'))
  return { accountId, store, cache }
}

const flush = () => new Promise(resolve => setImmediate(resolve))

describe('AccountCache', () => {
  it('caches token lookups', async () => {
    const { accountId, store, cache } = setup()
    const token = randomBytes(32)
    expect(cache.cachedAccount(token)).toBeUndefined()
    expect(await cache.authenticate(token)).toEqual(accountId)
    expect(cache.cachedAccount(token)).toEqual(accountId)
    expect(await cache.authenticate(token)).toEqual(accountId)
    expect(store.lookups).toBe(1)
  })

  it('repeats a lookup that raced with a rotation of the same account, and does not cache it', async () => {
    const { accountId, store, cache } = setup()
    const oldToken = randomBytes(32)
    store.tokenHash = oldToken
    store.hold = true
    const racing = cache.authenticate(oldToken)
    await flush()
    // The token is rotated (and the cache told) while the lookup still runs on the old row.
    store.tokenHash = randomBytes(32)
    cache.forget(accountId)
    store.hold = false
    store.held.shift()!()
    expect(await racing).toBeNull()
    expect(store.lookups).toBe(2)
    expect(cache.cachedAccount(oldToken)).toBeUndefined()
  })

  it('keeps a lookup that only raced with changes of other accounts', async () => {
    const { accountId, store, cache } = setup()
    const token = randomBytes(32)
    store.hold = true
    const racing = cache.authenticate(token)
    await flush()
    cache.forget(randomBytes(16))
    store.held.shift()!()
    expect(await racing).toEqual(accountId)
    expect(store.lookups).toBe(1)
    expect(cache.cachedAccount(token)).toEqual(accountId)
  })

  it('forgets the cached token of a forgotten account', async () => {
    const { accountId, cache } = setup()
    const token = randomBytes(32)
    await cache.authenticate(token)
    cache.forget(accountId)
    expect(cache.cachedAccount(token)).toBeUndefined()
  })
})
