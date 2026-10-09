import { randomBytes } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, registerWithAccessKey, send } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay } from '../support/relay'
import { TestSocket } from '../support/ws'

let db: TestDb

beforeAll(async () => {
  db = await TestDb.connect()
})

afterAll(async () => {
  await db.close()
})

describe('graceful shutdown', () => {
  it('stores envelopes held in memory and closes sockets with 1001 on SIGTERM', async () => {
    // A long ack timeout: without the shutdown flush these envelopes would only live in memory.
    const relay = await startRelay({ WS_ACK_TIMEOUT_MS: '60000' })
    const api = new Api(relay.url)
    const recipient = await registerWithAccessKey(api)
    const socket = await TestSocket.synced(relay.wsUrl, recipient.authToken)
    const ids: string[] = []
    for (let i = 0; i < 5; i++) ids.push((await send(api, recipient, randomBytes(64))).body.id)
    await socket.waitForMessages(5)
    expect(await db.count('envelopes WHERE recipient = ?', [uuidBytes(recipient.accountId)])).toBe(0)

    expect(await relay.stop('SIGTERM')).toBe(0)
    expect(relay.errors()).toEqual([])
    expect((await socket.closed).code).toBe(1001)
    const rows = await db.query<{ id: Buffer }>('SELECT id FROM envelopes WHERE recipient = ? ORDER BY id', [uuidBytes(recipient.accountId)])
    expect(rows.map(row => row.id.toString('base64url'))).toEqual(ids)
  })
})
