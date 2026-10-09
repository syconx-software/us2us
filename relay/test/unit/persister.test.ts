import { randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { Logger } from '../../server/lib/logger'
import { EnvelopePersister, type PendingEnvelope } from '../../server/realtime/persister'
import type { Mailbox, MailboxRow } from '../../server/services/mailbox'

/** An in-memory mailbox whose writes can be made to fail or to block. */
class FakeMailbox {
  readonly rows = new Map<string, MailboxRow>()
  failures = 0
  inserts = 0
  gate: Promise<void> | null = null

  async insert(rows: MailboxRow[]): Promise<void> {
    this.inserts++
    await this.gate
    if (this.failures > 0) {
      this.failures--
      throw Object.assign(new Error('db down'), { code: 'ECONNREFUSED' })
    }
    for (const row of rows) this.rows.set(row.id.toString('hex'), row)
  }

  async delete(_recipient: Buffer, ids: Buffer[]): Promise<void> {
    for (const id of ids) this.rows.delete(id.toString('hex'))
  }
}

function envelope(recipient = randomBytes(16)): PendingEnvelope {
  const id = randomBytes(16)
  return { recipient, recipientKey: recipient.toString('hex'), id, idKey: id.toString('hex'), serverTimestamp: Date.now(), body: randomBytes(8), acked: false }
}

function setup() {
  const mailbox = new FakeMailbox()
  const persister = new EnvelopePersister(mailbox as unknown as Mailbox, new Logger('error'))
  return { mailbox, persister }
}

const until = async (condition: () => boolean) => {
  for (let i = 0; i < 400 && !condition(); i++) await new Promise(resolve => setTimeout(resolve, 10))
  expect(condition()).toBe(true)
}

describe('EnvelopePersister', () => {
  it('stores everything enqueued in the same tick in one batch', async () => {
    const { mailbox, persister } = setup()
    const recipient = randomBytes(16)
    persister.enqueue([envelope(recipient), envelope(recipient), envelope()])
    await until(() => mailbox.rows.size === 3)
    expect(mailbox.inserts).toBe(1)
    expect(persister.size).toBe(0)
  })

  it('retries failed writes until they succeed', async () => {
    const { mailbox, persister } = setup()
    mailbox.failures = 1
    persister.enqueue([envelope()])
    await until(() => mailbox.rows.size === 1)
  })

  it('skips the write for an envelope acknowledged while queued', async () => {
    const { mailbox, persister } = setup()
    const entry = envelope()
    persister.enqueue([entry])
    expect(persister.acknowledge(entry.recipientKey, entry.idKey)).toBe(true)
    await until(() => persister.size === 0)
    expect(mailbox.rows.size).toBe(0)
  })

  it('undoes the write for an envelope acknowledged while it was being written', async () => {
    const { mailbox, persister } = setup()
    let release!: () => void
    mailbox.gate = new Promise(resolve => (release = resolve))
    const entry = envelope()
    persister.enqueue([entry])
    await new Promise(resolve => setImmediate(resolve))
    expect(persister.acknowledge(entry.recipientKey, entry.idKey)).toBe(true)
    expect(persister.acknowledge('someone-else', entry.idKey)).toBe(false)
    release()
    await until(() => persister.size === 0)
    expect(mailbox.rows.size).toBe(0)
  })

  it('hands queued envelopes back to a recipient who reconnects', async () => {
    const { mailbox, persister } = setup()
    mailbox.failures = 1
    const recipient = randomBytes(16)
    const entries = [envelope(recipient), envelope(recipient)]
    persister.enqueue([...entries, envelope()])
    // The first write fails; everything waits for the retry.
    await until(() => mailbox.failures === 0)
    const reclaimed = await persister.reclaim(recipient.toString('hex'))
    expect(reclaimed.map(e => e.idKey).sort()).toEqual(entries.map(e => e.idKey).sort())
    await until(() => mailbox.rows.size === 1)
  })

  it('reports what it holds per recipient until it is in the mailbox', async () => {
    const { mailbox, persister } = setup()
    let release!: () => void
    mailbox.gate = new Promise(resolve => (release = resolve))
    const recipient = randomBytes(16)
    const key = recipient.toString('hex')
    const [a, b] = [envelope(recipient), envelope(recipient)]
    persister.enqueue([a, b, envelope()])
    expect(persister.held(key)).toEqual({ count: 2, bytes: a.body.length + b.body.length })
    expect(persister.overloaded).toBe(false)

    const flushed = persister.flushed(key, Date.now() + 5000)
    let done = false
    void flushed.then(() => (done = true))
    await new Promise(resolve => setTimeout(resolve, 30))
    expect(done).toBe(false)
    release()
    await flushed
    expect(mailbox.rows.has(a.idKey) && mailbox.rows.has(b.idKey)).toBe(true)
    expect(persister.held(key)).toEqual({ count: 0, bytes: 0 })
  })

  it('stops waiting for a flush at the deadline', async () => {
    const { mailbox, persister } = setup()
    mailbox.gate = new Promise(() => {})
    const entry = envelope()
    persister.enqueue([entry])
    const started = Date.now()
    await persister.flushed(entry.recipientKey, started + 50)
    expect(Date.now() - started).toBeLessThan(1000)
    expect(persister.held(entry.recipientKey).count).toBe(1)
  })

  it('drains everything on shutdown', async () => {
    const { mailbox, persister } = setup()
    persister.enqueue(Array.from({ length: 250 }, () => envelope()))
    expect(await persister.drain(Date.now() + 5000)).toBe(true)
    expect(mailbox.rows.size).toBe(250)
  })
})
