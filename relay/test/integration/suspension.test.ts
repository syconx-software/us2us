import { execFile } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { request } from 'node:http'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { formatUuid, usernameHash } from '../../scripts/account-suspend.mjs'
import { avatarRecord } from '../support/avatar'
import { Api, b64u, eventually, register, type TestAccount } from '../support/client'
import { communityApi } from '../support/community'
import { TestDb, uuidBytes } from '../support/db'
import { TEST_DATABASE_URL } from '../support/env'
import { startRelay, type RelayProcess } from '../support/relay'
import { TestSocket } from '../support/ws'

const script = fileURLToPath(new URL('../../scripts/account-suspend.mjs', import.meta.url))

let relay: RelayProcess
let api: Api
let db: TestDb

beforeAll(async () => {
  relay = await startRelay({ SUSPENSION_CHECK_SECONDS: '1' })
  api = new Api(relay.url)
  db = await TestDb.connect()
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

interface CliResult {
  status: number
  stdout: string
  stderr: string
}

/** Runs the operator script as the operator would, with only the database URL in its environment. */
function cli(args: string[], env: Record<string, string> = { DATABASE_URL: TEST_DATABASE_URL }): Promise<CliResult> {
  return new Promise((resolve) => {
    execFile(process.execPath, [script, ...args], { env: { PATH: process.env.PATH ?? '', ...env } }, (error, stdout, stderr) => {
      const status = error ? (typeof error.code === 'number' ? error.code : -1) : 0
      resolve({ status, stdout, stderr })
    })
  })
}

const username = () => `sus_${randomBytes(4).toString('hex')}`

async function registerNamed(name: string): Promise<TestAccount> {
  return register(api, { usernameHash: b64u(usernameHash(name)) })
}

function upgradeStatus(token: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = request(`${relay.url}/v1/ws`, {
      headers: {
        'connection': 'Upgrade',
        'upgrade': 'websocket',
        'sec-websocket-key': randomBytes(16).toString('base64'),
        'sec-websocket-version': '13',
        'x-auth-token': token,
      },
    })
    req.on('upgrade', (_res, socket) => {
      socket.destroy()
      resolve(101)
    })
    req.on('response', (res) => {
      res.resume()
      resolve(res.statusCode ?? 0)
    })
    req.on('error', reject)
    req.end()
  })
}

const suspensionRows = (account?: TestAccount) => account
  ? db.count('suspended_accounts WHERE account_id = ?', [uuidBytes(account.accountId)])
  : db.count('suspended_accounts')

describe('suspending an account', () => {
  it('refuses, hides and cleans up a suspended account; lifting the suspension restores access', async () => {
    const groups = communityApi(api)
    const name = username()
    const alice = await registerNamed(name)
    const bob = await register(api)
    const carol = await register(api)

    const upload = await api.post('/v1/media', { token: alice.authToken, body: randomBytes(1000) })
    expect(upload.status).toBe(201)
    const mediaHex = Buffer.from(upload.body.mediaId, 'base64url').toString('hex')
    const mediaFile = join(relay.mediaDir, mediaHex.slice(0, 2), mediaHex)
    expect(existsSync(mediaFile)).toBe(true)
    expect((await api.put('/v1/accounts/me/avatar', { token: alice.authToken, body: avatarRecord(1) })).status).toBe(204)
    const owned = await groups.created(alice)
    let joined = await groups.created(carol)
    joined = await groups.approve(carol, joined, [alice])
    expect((await groups.deliver(alice, carol, joined)).status).toBe(202)
    const socket = await TestSocket.synced(relay.wsUrl, alice.authToken)

    const lookup = () => api.get(`/v1/directory/${alice.usernameHash}`, { token: bob.authToken })
    const bundle = () => api.get(`/v1/keys/${alice.accountId}`, { token: bob.authToken })
    expect((await lookup()).status).toBe(200)
    expect((await bundle()).status).toBe(200)

    // Without --yes nothing is written.
    const preview = await cli(['suspend', '--username', `@${name.toUpperCase()}`, '--reason', 'abuse'])
    expect(preview.status).toBe(0)
    expect(preview.stdout).toContain(formatUuid(uuidBytes(alice.accountId)))
    expect(preview.stdout).toContain('Nothing changed')
    expect(await suspensionRows(alice)).toBe(0)

    const suspended = await cli(['suspend', '--username', name, '--reason', 'abuse', '--yes'])
    expect(suspended.stderr).toBe('')
    expect(suspended.status).toBe(0)
    expect(suspended.stdout).toContain('Applied by the relay')

    // The open socket is closed, and every authenticated request is refused.
    expect((await socket.closed).code).toBe(4403)
    const me = await api.get('/v1/accounts/me', { token: alice.authToken })
    expect(me.status).toBe(403)
    expect(me.body.error.code).toBe('account_suspended')
    expect((await api.post('/v1/media', { token: alice.authToken, body: randomBytes(10) })).body.error.code).toBe('account_suspended')
    expect((await groups.create(alice, { ...owned, id: randomUUID() })).status).toBe(403)
    expect(await upgradeStatus(alice.authToken)).toBe(403)

    // Nobody finds it, fetches its keys or sends to it.
    expect((await lookup()).status).toBe(404)
    expect((await bundle()).status).toBe(404)
    expect((await api.get(`/v1/accounts/${alice.accountId}/identity`, { token: bob.authToken })).status).toBe(404)
    expect((await api.get(`/v1/accounts/${alice.accountId}/avatar`, { token: bob.authToken })).status).toBe(404)
    expect((await api.put(`/v1/messages/${alice.accountId}`, { body: randomBytes(100) })).status).toBe(404)
    // Its username stays taken.
    const again = await registerNamed(name).catch((error: Error) => error.message)
    expect(again).toContain('409')

    // What it had published is gone; the community it only belonged to stays for the others.
    const id = uuidBytes(alice.accountId)
    expect(await db.count('media WHERE uploader = ?', [id])).toBe(0)
    expect(existsSync(mediaFile)).toBe(false)
    expect(await db.count('avatars WHERE account_id = ? AND record IS NOT NULL', [id])).toBe(0)
    expect(await db.count('communities WHERE id = ?', [uuidBytes(owned.id)])).toBe(0)
    expect(await db.count('communities WHERE id = ?', [uuidBytes(joined.id)])).toBe(1)
    expect(await db.count('community_members WHERE account_id = ?', [id])).toBe(0)
    expect(await db.count('community_envelopes WHERE sender = ? OR recipient = ?', [id, id])).toBe(0)

    const status = await cli(['status', '--account', alice.accountId])
    expect(status.status).toBe(0)
    expect(status.stdout).toMatch(/suspended: +since .*reason abuse/)
    expect(status.stdout).not.toContain('not yet')
    expect((await cli(['list'])).stdout).toContain(formatUuid(id))

    // Lifting it needs --yes as well.
    expect((await cli(['unsuspend', '--account', alice.accountId])).stdout).toContain('Nothing changed')
    expect(await suspensionRows(alice)).toBe(1)
    const lifted = await cli(['unsuspend', '--account', Buffer.from(id).toString('base64url'), '--yes'])
    expect(lifted.status).toBe(0)
    expect(lifted.stdout).toContain('Lifted the suspension')
    await eventually(async () => (await api.get('/v1/accounts/me', { token: alice.authToken })).status === 200, 5000)
    expect((await lookup()).status).toBe(200)
    expect((await bundle()).status).toBe(200)
    expect(await upgradeStatus(alice.authToken)).toBe(101)
  })

  it('reports unknown names, finds previous ones, and changes nothing twice', async () => {
    const unknown = await cli(['status', '--username', username()])
    expect(unknown.status).toBe(1)
    expect(unknown.stdout).toContain('No account has this username.')
    expect((await cli(['suspend', '--account', randomUUID(), '--reason', 'abuse', '--yes'])).status).toBe(1)

    // A name the account gave up is still held for it, and finds it.
    const old = username()
    const dave = await registerNamed(old)
    expect((await api.put('/v1/accounts/me/username', { token: dave.authToken, json: { usernameHash: b64u(usernameHash(username())) } })).status).toBe(204)
    const previous = await cli(['status', '--username', old])
    expect(previous.status).toBe(0)
    expect(previous.stdout).toContain(formatUuid(uuidBytes(dave.accountId)))
    expect(previous.stdout).toContain('previous, held until')

    expect((await cli(['suspend', '--username', old, '--reason', 'ticket-42', '--yes', '--wait', '0'])).status).toBe(0)
    const twice = await cli(['suspend', '--account', dave.accountId, '--reason', 'other', '--yes'])
    expect(twice.status).toBe(0)
    expect(twice.stdout).toContain('Already suspended; nothing changed.')
    expect(await suspensionRows(dave)).toBe(1)

    expect((await cli(['unsuspend', '--account', dave.accountId, '--yes'])).status).toBe(0)
    const notSuspended = await cli(['unsuspend', '--account', dave.accountId, '--yes'])
    expect(notSuspended.status).toBe(0)
    expect(notSuspended.stdout).toContain('Not suspended; nothing changed.')
    expect(await suspensionRows(dave)).toBe(0)
  })

  it('refuses bad input without touching the database, and never prints the database URL', async () => {
    const before = await suspensionRows()
    for (const args of [
      [],
      ['ban', '--username', 'alice'],
      ['suspend', '--username', 'a', '--reason', 'abuse'],
      ['suspend', '--username', 'alice', '--account', randomUUID(), '--reason', 'abuse'],
      ['suspend', '--account', 'not-an-id', '--reason', 'abuse'],
      ['suspend', '--username', 'alice'],
      ['suspend', '--username', 'alice', '--reason', 'Called me names'],
      ['suspend', '--username', 'alice', '--reason', 'abuse', '--wait', 'soon'],
      ['unsuspend', '--username', 'alice', '--reason', 'abuse'],
      ['status', '--username', 'alice', '--yes'],
      ['list', '--yes'],
      ['status', '--username', 'alice', '--unknown'],
    ]) {
      const result = await cli(args)
      expect(result.status, args.join(' ')).toBe(2)
      expect(result.stderr).toContain('usage:')
    }

    const missing = await cli(['status', '--username', 'alice'], {})
    expect(missing.status).toBe(1)
    expect(missing.stderr).toContain('DATABASE_URL is not set')

    const secret = `pw${randomBytes(8).toString('hex')}`
    const url = new URL(TEST_DATABASE_URL)
    url.username = 'no_such_user'
    url.password = secret
    const denied = await cli(['status', '--username', 'alice'], { DATABASE_URL: url.href })
    expect(denied.status).toBe(1)
    expect(denied.stderr).toMatch(/^account-suspend: [A-Z_]+\n$/)
    expect(denied.stderr + denied.stdout).not.toContain(secret)
    expect(denied.stderr + denied.stdout).not.toContain('no_such_user')
    expect(await suspensionRows()).toBe(before)
  })
})
