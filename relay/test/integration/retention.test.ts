import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { lutimes, mkdir, symlink, utimes, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, eventually, register } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'

const DAY = 86_400_000

let relay: RelayProcess
let api: Api
let db: TestDb

beforeAll(async () => {
  relay = await startRelay({ RETENTION_INTERVAL_SECONDS: '1' })
  api = new Api(relay.url)
  db = await TestDb.connect()
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

describe('retention sweep', () => {
  it('deletes envelopes after 14 days and keeps younger ones', async () => {
    const recipient = randomBytes(16)
    const now = Date.now()
    await db.query('INSERT INTO envelopes (recipient, id, server_ts, body) VALUES (?, ?, ?, ?), (?, ?, ?, ?)', [
      recipient, randomBytes(16), now - 15 * DAY, randomBytes(10),
      recipient, randomBytes(16), now - 13 * DAY, randomBytes(10),
    ])
    await eventually(async () => await db.count('envelopes WHERE recipient = ?', [recipient]) === 1)
    const [kept] = await db.query('SELECT server_ts FROM envelopes WHERE recipient = ?', [recipient])
    expect(Number(kept!.server_ts)).toBe(now - 13 * DAY)
  })

  it('deletes expired media with its files', async () => {
    const expired = randomBytes(16)
    const live = randomBytes(16)
    const path = (id: Buffer) => join(relay.mediaDir, id.toString('hex').slice(0, 2), id.toString('hex'))
    for (const id of [expired, live]) {
      await mkdir(join(relay.mediaDir, id.toString('hex').slice(0, 2)), { recursive: true })
      await writeFile(path(id), randomBytes(100))
    }
    await writeFile(`${path(expired)}.part`, randomBytes(10))
    await db.query('INSERT INTO media (id, uploader, delete_token_hash, size, expires_at) VALUES (?, ?, ?, 100, ?), (?, ?, ?, 100, ?)', [
      expired, randomBytes(16), randomBytes(32), Date.now() - 1000,
      live, randomBytes(16), randomBytes(32), Date.now() + DAY,
    ])
    await eventually(async () => await db.count('media WHERE id = ?', [expired]) === 0)
    expect(existsSync(path(expired))).toBe(false)
    expect(existsSync(`${path(expired)}.part`)).toBe(false)
    expect(existsSync(path(live))).toBe(true)
    expect(await db.count('media WHERE id = ?', [live])).toBe(1)
  })

  it('deletes old media files that have no row, and nothing else', async () => {
    // One subdirectory is checked per run, so every one gets the same files.
    const old = new Date(Date.now() - 2 * 3_600_000)
    const plant = async (path: string, mtime = old) => {
      await writeFile(path, randomBytes(10))
      await utimes(path, mtime, mtime)
      return path
    }
    const idIn = (subdir: number) => Buffer.concat([Buffer.from([subdir]), randomBytes(15)])
    const live = Array.from({ length: 256 }, (_, i) => idIn(i))
    await db.query(`INSERT INTO media (id, uploader, delete_token_hash, size, expires_at) VALUES ${live.map(() => '(?, ?, ?, 10, ?)').join(', ')}`,
      live.flatMap(id => [id, randomBytes(16), randomBytes(32), Date.now() + DAY]))

    const outside = await plant(join(relay.mediaDir, 'outside'))
    const orphans: string[] = []
    const kept: string[] = [outside]
    for (let i = 0; i < 256; i++) {
      const subdir = join(relay.mediaDir, i.toString(16).padStart(2, '0'))
      const orphan = join(subdir, idIn(i).toString('hex'))
      // The .part first: a sweep between the two leaves the finished file, which is then not counted as swept.
      await plant(`${orphan}.part`)
      orphans.push(await plant(orphan))
      kept.push(await plant(join(subdir, live[i]!.toString('hex'))))
      kept.push(await plant(join(subdir, idIn(i).toString('hex')), new Date()))
      kept.push(await plant(join(subdir, 'README')))
      const link = join(subdir, idIn(i).toString('hex'))
      await symlink(outside, link)
      await lutimes(link, old, old)
      kept.push(link)
    }

    const swept = await eventually(() => orphans.find(path => !existsSync(path)))
    await eventually(() => !existsSync(`${swept}.part`))
    for (const path of kept) expect(existsSync(path), path).toBe(true)
  })

  it('forgets used proof-of-work tokens once they expire', async () => {
    const [old, fresh] = [randomBytes(32), randomBytes(32)]
    await db.query('INSERT INTO used_pow_tokens (token_hash, expires_at) VALUES (?, ?), (?, ?)', [old, Date.now() - 1000, fresh, Date.now() + 600_000])
    await eventually(async () => await db.count('used_pow_tokens WHERE token_hash = ?', [old]) === 0)
    expect(await db.count('used_pow_tokens WHERE token_hash = ?', [fresh])).toBe(1)
  })

  it('releases username holds after they run out', async () => {
    const account = await register(api)
    const accountId = uuidBytes(account.accountId)
    const [expiredHold, activeHold] = [randomBytes(32), randomBytes(32)]
    await db.query('INSERT INTO usernames (username_hash, account_id, held_until) VALUES (?, ?, ?), (?, ?, ?)', [
      expiredHold, accountId, Date.now() - 1000,
      activeHold, accountId, Date.now() + DAY,
    ])
    await eventually(async () => await db.count('usernames WHERE username_hash = ?', [expiredHold]) === 0)
    expect(await db.count('usernames WHERE account_id = ?', [accountId])).toBe(2) // current name and the active hold
  })

  it('deletes accounts that have not connected for 180 days, with everything they own', async () => {
    const inactive = await register(api)
    const active = await register(api)
    const id = uuidBytes(inactive.accountId)
    await db.query('UPDATE accounts SET last_seen = ? WHERE id = ?', [new Date(Date.now() - 181 * DAY).toISOString().slice(0, 10), id])

    await eventually(async () => await db.count('accounts WHERE id = ?', [id]) === 0)
    for (const table of ['usernames WHERE account_id = ?', 'one_time_pre_keys WHERE account_id = ?']) {
      expect(await db.count(table, [id]), table).toBe(0)
    }
    expect((await api.get('/v1/accounts/me', { token: inactive.authToken })).status).toBe(401)
    expect((await api.get('/v1/accounts/me', { token: active.authToken })).status).toBe(200)
  })

  it('stores only a coarse last-seen date', async () => {
    const account = await register(api)
    const [row] = await db.query<{ last_seen: unknown }>('SELECT CAST(last_seen AS CHAR) AS last_seen FROM accounts WHERE id = ?', [uuidBytes(account.accountId)])
    expect(row!.last_seen).toBe(new Date().toISOString().slice(0, 10))
  })
})
