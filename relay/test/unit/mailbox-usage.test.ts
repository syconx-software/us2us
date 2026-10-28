import { randomBytes } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import type { Database } from '../../server/db/client'
import { loadConfig } from '../../server/lib/config'
import { Relay } from '../../server/relay'
import { Mailbox, type MailboxRow } from '../../server/services/mailbox'

type CallKind = 'count' | 'insert' | 'delete' | 'expire'

interface Call {
  kind: CallKind
  resolve: (value: unknown) => void
  reject: (error: unknown) => void
}

/**
 * Stands in for MySQL: every query the mailbox makes waits until the test
 * answers it, so that writes and the count can be interleaved at will.
 */
class ScriptedDatabase {
  readonly calls: Call[] = []

  readonly db = {
    select: () => ({ from: () => ({ groupBy: () => this.call('count') }) }),
    insert: () => ({ values: () => ({ onDuplicateKeyUpdate: () => this.call('insert') }) }),
    delete: () => ({ where: () => this.call('delete') }),
  }

  transaction(): Promise<unknown> {
    return this.call('expire')
  }

  /** The oldest unanswered query of a kind. */
  take(kind: CallKind): Call {
    const index = this.calls.findIndex(call => call.kind === kind)
    expect(index).toBeGreaterThanOrEqual(0)
    return this.calls.splice(index, 1)[0]!
  }

  waiting(kind: CallKind): number {
    return this.calls.filter(call => call.kind === kind).length
  }

  private call(kind: CallKind): Promise<unknown> {
    return new Promise((resolve, reject) => this.calls.push({ kind, resolve, reject }))
  }
}

const ENVELOPE = 100

function setup() {
  const database = new ScriptedDatabase()
  const mailbox = new Mailbox(database as unknown as Database, { maxEnvelopes: 5, maxBytes: 5 * ENVELOPE })
  const recipient = randomBytes(16)
  return { database, mailbox, recipient, key: recipient.toString('hex') }
}

const row = (recipient: Buffer): MailboxRow => ({ recipient, id: randomBytes(16), serverTimestamp: Date.now(), body: randomBytes(ENVELOPE) })
const counted = (...mailboxes: Array<[Buffer, number]>) => mailboxes.map(([recipient, count]) => ({ recipient, count, bytes: count * ENVELOPE }))
const tick = () => new Promise(resolve => setImmediate(resolve))

/** How many more envelopes the recipient may store. */
function room(mailbox: Mailbox, key: string): number {
  let n = 0
  while (n < 100 && mailbox.admits(key, ENVELOPE, { count: n, bytes: n * ENVELOPE })) n++
  return n
}

async function load(database: ScriptedDatabase, mailbox: Mailbox, ...mailboxes: Array<[Buffer, number]>): Promise<void> {
  const loading = mailbox.load()
  await tick()
  database.take('count').resolve(counted(...mailboxes))
  await loading
}

describe('Mailbox usage', () => {
  it('counts every mailbox stored before a restart before admitting anything', async () => {
    const { database, mailbox, recipient, key } = setup()
    const other = randomBytes(16)
    expect(mailbox.loaded).toBe(false)
    expect(mailbox.admits(key, 1)).toBe(false)
    await load(database, mailbox, [recipient, 5], [other, 3])
    expect(mailbox.loaded).toBe(true)
    expect(room(mailbox, key)).toBe(0)
    expect(room(mailbox, other.toString('hex'))).toBe(2)
    expect(room(mailbox, randomBytes(16).toString('hex'))).toBe(5)
  })

  it('reads the database once, however many sends wait for it, and never again for a send', async () => {
    const { database, mailbox, recipient, key } = setup()
    const first = mailbox.load()
    const second = mailbox.load()
    await tick()
    expect(database.waiting('count')).toBe(1)
    database.take('count').resolve(counted([recipient, 1]))
    await Promise.all([first, second])
    await mailbox.load()
    for (let i = 0; i < 3; i++) mailbox.admits(randomBytes(16).toString('hex'), ENVELOPE)
    expect(database.calls).toEqual([])
    expect(room(mailbox, key)).toBe(4)
  })

  it('follows inserts and deletes after the count, also for mailboxes that were empty', async () => {
    const { database, mailbox, recipient, key } = setup()
    await load(database, mailbox, [recipient, 1])
    const rows = [row(recipient), row(recipient)]
    const inserting = mailbox.insert(rows)
    database.take('insert').resolve(undefined)
    await inserting
    expect(room(mailbox, key)).toBe(2)

    const deleting = mailbox.delete(recipient, [rows[0]!.id])
    database.take('delete').resolve([{ affectedRows: 1 }])
    await deleting
    expect(room(mailbox, key)).toBe(3)

    const emptying = mailbox.delete(recipient, [randomBytes(16), randomBytes(16), randomBytes(16)])
    database.take('delete').resolve([{ affectedRows: 3 }])
    await emptying
    expect(room(mailbox, key)).toBe(5)

    const fresh = randomBytes(16)
    const filling = mailbox.insert([row(fresh), row(fresh), row(fresh), row(fresh), row(fresh)])
    database.take('insert').resolve(undefined)
    await filling
    expect(room(mailbox, fresh.toString('hex'))).toBe(0)
  })

  it('counts only once the writes already running are done', async () => {
    const { database, mailbox, recipient, key } = setup()
    const deleting = mailbox.delete(recipient, [randomBytes(16), randomBytes(16)])
    const inserting = mailbox.insert([row(recipient)])
    const loading = mailbox.load()
    await tick()
    expect(database.waiting('count')).toBe(0)
    database.take('delete').resolve([{ affectedRows: 2 }])
    await deleting
    await tick()
    expect(database.waiting('count')).toBe(0)
    database.take('insert').resolve(undefined)
    await inserting
    await tick()
    // Both writes are in what the database answers now, and nothing is applied twice.
    database.take('count').resolve(counted([recipient, 4]))
    await loading
    expect(room(mailbox, key)).toBe(1)
  })

  it('leaves writes done before the count to it', async () => {
    const { database, mailbox, recipient, key } = setup()
    const rows = [row(recipient), row(recipient)]
    const inserting = mailbox.insert(rows)
    database.take('insert').resolve(undefined)
    await inserting
    const deleting = mailbox.delete(recipient, [rows[0]!.id])
    database.take('delete').resolve([{ affectedRows: 1 }])
    await deleting
    const emptied = randomBytes(16)
    const filling = mailbox.insert([row(emptied)])
    database.take('insert').resolve(undefined)
    await filling
    const emptying = mailbox.delete(emptied, [randomBytes(16)])
    database.take('delete').resolve([{ affectedRows: 1 }])
    await emptying

    await load(database, mailbox, [recipient, 1])
    expect(room(mailbox, key)).toBe(4)
    expect(room(mailbox, emptied.toString('hex'))).toBe(5)
  })

  it('holds writes back while the count runs, then applies them to it', async () => {
    const { database, mailbox, recipient, key } = setup()
    const loading = mailbox.load()
    await tick()
    const inserting = mailbox.insert([row(recipient)])
    const deleting = mailbox.delete(recipient, [randomBytes(16), randomBytes(16)])
    const expiring = mailbox.deleteArrivedBefore(Date.now(), 5000)
    await tick()
    expect([database.waiting('insert'), database.waiting('delete'), database.waiting('expire')]).toEqual([0, 0, 0])
    database.take('count').resolve(counted([recipient, 4]))
    await loading
    await tick()
    database.take('insert').resolve(undefined)
    await inserting
    expect(room(mailbox, key)).toBe(0)
    database.take('delete').resolve([{ affectedRows: 2 }])
    await deleting
    expect(room(mailbox, key)).toBe(2)
    database.take('expire').resolve([{ recipient, id: randomBytes(16) }])
    expect(await expiring).toBe(1)
    expect(room(mailbox, key)).toBe(3)
  })

  it('keeps the rows that are still stored counted when older ones expire', async () => {
    const { database, mailbox, recipient, key } = setup()
    const other = randomBytes(16)
    await load(database, mailbox, [recipient, 5], [other, 1])
    const expiring = mailbox.deleteArrivedBefore(Date.now(), 5000)
    database.take('expire').resolve([{ recipient, id: randomBytes(16) }, { recipient, id: randomBytes(16) }, { recipient: other, id: randomBytes(16) }])
    expect(await expiring).toBe(3)
    expect(room(mailbox, key)).toBe(2)
    expect(room(mailbox, other.toString('hex'))).toBe(5)
  })

  it('refuses after a failed count, lets waiting writes through, and counts again on the next send', async () => {
    const { database, mailbox, recipient, key } = setup()
    const loading = mailbox.load()
    await tick()
    const inserting = mailbox.insert([row(recipient)])
    database.take('count').reject(new Error('db down'))
    await expect(loading).rejects.toThrow('db down')
    expect(mailbox.loaded).toBe(false)
    expect(mailbox.admits(key, 1)).toBe(false)

    await tick()
    const retrying = mailbox.load()
    await tick()
    // The insert that waited for the failed count runs first; the next count waits for it.
    expect(database.waiting('count')).toBe(0)
    database.take('insert').resolve(undefined)
    await inserting
    await tick()
    database.take('count').resolve(counted([recipient, 1]))
    await retrying
    expect(room(mailbox, key)).toBe(4)
  })

  it('forgets deleted accounts, also one deleted while the count runs', async () => {
    const { database, mailbox, recipient: deleted, key: deletedKey } = setup()
    const deletedDuringCount = randomBytes(16)
    const kept = randomBytes(16)
    const loading = mailbox.load()
    await tick()
    mailbox.forgetUsage(deletedDuringCount.toString('hex'))
    database.take('count').resolve(counted([deleted, 3], [deletedDuringCount, 5], [kept, 2]))
    await loading
    expect(room(mailbox, deletedDuringCount.toString('hex'))).toBe(5)
    expect(room(mailbox, kept.toString('hex'))).toBe(3)

    mailbox.forgetUsage(deletedKey)
    expect(room(mailbox, deletedKey)).toBe(5)
  })
})

describe('Mailbox proof-of-work share (audit M-1)', () => {
  function powSetup() {
    const database = new ScriptedDatabase()
    const mailbox = new Mailbox(database as unknown as Database, { maxEnvelopes: 10, maxBytes: 10 * ENVELOPE, powMaxEnvelopes: 2, powMaxBytes: 2 * ENVELOPE })
    const recipient = randomBytes(16)
    return { database, mailbox, recipient, key: recipient.toString('hex') }
  }
  const powRow = (recipient: Buffer): MailboxRow => ({ ...row(recipient), viaPow: true })

  it('admits proof-of-work envelopes only within their share, and access-key ones up to the whole mailbox', async () => {
    const { database, mailbox, recipient, key } = powSetup()
    const loading = mailbox.load()
    await tick()
    // One stored proof-of-work envelope before the restart, three others.
    database.take('count').resolve([{ recipient, count: 4, bytes: 4 * ENVELOPE, powCount: 1, powBytes: ENVELOPE }])
    await loading
    expect(mailbox.admits(key, ENVELOPE, { count: 0, bytes: 0 }, true)).toBe(true)
    // In memory, not written yet: counts towards the share too.
    expect(mailbox.admits(key, ENVELOPE, { count: 1, bytes: ENVELOPE, powCount: 1, powBytes: ENVELOPE }, true)).toBe(false)
    const inserting = mailbox.insert([powRow(recipient)])
    database.take('insert').resolve(undefined)
    await inserting
    expect(mailbox.usageOf(key)).toEqual({ count: 5, bytes: 5 * ENVELOPE, powCount: 2, powBytes: 2 * ENVELOPE })
    expect(mailbox.admits(key, 1, undefined, true)).toBe(false)
    expect(mailbox.admits(key, ENVELOPE)).toBe(true)
    expect(room(mailbox, key)).toBe(5)
  })

  it('counts exactly which deleted rows were proof-of-work envelopes, in one locked transaction', async () => {
    const { database, mailbox, recipient, key } = powSetup()
    const loading = mailbox.load()
    await tick()
    database.take('count').resolve([{ recipient, count: 4, bytes: 4 * ENVELOPE, powCount: 2, powBytes: 2 * ENVELOPE }])
    await loading
    // Two ids acknowledged, one of them a proof-of-work envelope.
    const deleting = mailbox.delete(recipient, [randomBytes(16), randomBytes(16)])
    await tick()
    expect(database.waiting('delete')).toBe(0)
    database.take('expire').resolve({ deleted: 2, pow: [{ bytes: ENVELOPE }] })
    await deleting
    expect(mailbox.usageOf(key)).toEqual({ count: 2, bytes: 2 * ENVELOPE, powCount: 1, powBytes: ENVELOPE })
    expect(mailbox.admits(key, ENVELOPE, undefined, true)).toBe(true)

    // Expiry reports the share of each expired row (-1: not proof-of-work).
    const expiring = mailbox.deleteArrivedBefore(Date.now(), 100)
    await tick()
    database.take('expire').resolve([{ recipient, id: randomBytes(16), powBytes: ENVELOPE }])
    await expiring
    expect(mailbox.usageOf(key)).toEqual({ count: 1, bytes: ENVELOPE, powCount: 0, powBytes: 0 })

    // Without proof-of-work envelopes, a delete is a single statement again.
    const plain = mailbox.delete(recipient, [randomBytes(16)])
    await tick()
    database.take('delete').resolve([{ affectedRows: 1 }])
    await plain
    expect(mailbox.usageOf(key)).toEqual({ count: 0, bytes: 0, powCount: 0, powBytes: 0 })
  })
})

describe('Relay.countMailboxes', () => {
  it('logs a failed count once for every send that waited, without ids, and counts again on the next send', async () => {
    const relay = new Relay(loadConfig({ DATABASE_URL: 'mysql://u:p@127.0.0.1:9/none', POW_SECRET: 'x'.repeat(32) }))
    const { database, mailbox, recipient, key } = setup()
    Object.defineProperty(relay, 'mailbox', { value: mailbox })
    const warn = vi.spyOn(relay.logger, 'warn').mockImplementation(() => {})
    try {
      const waiting = [relay.countMailboxes(), relay.countMailboxes(), relay.countMailboxes()]
      await tick()
      expect(database.waiting('count')).toBe(1)
      database.take('count').reject(Object.assign(new Error(`failed for ${key}`), { code: 'ECONNREFUSED' }))
      for (const send of waiting) await expect(send).rejects.toThrow()
      expect(warn).toHaveBeenCalledTimes(1)
      expect(warn).toHaveBeenCalledWith('counting the stored envelopes failed', { error: 'Error ECONNREFUSED' })
      expect(mailbox.loaded).toBe(false)

      const retrying = relay.countMailboxes()
      await tick()
      database.take('count').resolve(counted([recipient, 2]))
      await retrying
      expect(room(mailbox, key)).toBe(3)
      await relay.countMailboxes()
      expect(database.calls).toEqual([])
      expect(warn).toHaveBeenCalledTimes(1)
    } finally {
      await relay.database.close()
    }
  })
})
