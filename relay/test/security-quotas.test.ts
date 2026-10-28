import { randomBytes } from 'node:crypto'
import { statfsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, eventually, register, registrationBody, registerWithAccessKey, send, solvedPow, type TestAccount } from './support/client'
import { communityApi } from './support/community'
import { TestDb, uuidBytes } from './support/db'
import { startRelay, type RelayProcess } from './support/relay'

/*
 * Regression tests for the relay findings of the 2026-10-28 security audit
 * (reports/security-audit-2026-10-28/messenger.md): M-1 mailbox flooding,
 * M-2 media against envelopes on one disk, M-3 cheap accounts, L-3 one-time
 * key drain, and the trusted proxy header.
 */

const ENVELOPE = 64 * 1024
const MiB = 1024 * 1024

/** A sealed send authorized by a message proof-of-work, as a first contact sends it. */
async function powSend(api: Api, recipient: string, body: Uint8Array, headers: Record<string, string> = {}) {
  const pow = await solvedPow(api, `purpose=message&to=${recipient}`)
  return api.put(`/v1/messages/${recipient}`, { body, headers: { 'x-pow': `${pow.token}:${pow.nonce}`, ...headers } })
}

async function drain(api: Api, account: TestAccount, count: number) {
  const page = await api.get(`/v1/messages?limit=${count}`, { token: account.authToken })
  expect(page.status).toBe(200)
  const ids = (page.body.messages as Array<{ id: string }>).map(message => message.id)
  expect((await api.post('/v1/messages/ack', { token: account.authToken, json: { ids } })).status).toBe(204)
  return ids.length
}

describe('M-1: strangers with proof-of-work cannot fill a mailbox for the recipient\'s contacts', () => {
  let relay: RelayProcess
  let api: Api
  let db: TestDb

  beforeAll(async () => {
    relay = await startRelay()
    api = new Api(relay.url)
    db = await TestDb.connect()
  })

  afterAll(async () => {
    await db.close()
    if (relay) {
      expect(relay.errors()).toEqual([])
      await relay.stop()
    }
  })

  it('caps proof-of-work envelopes at their share (8 MiB), keeps the rest for access-key senders, and counts it across a restart', async () => {
    const victim = await registerWithAccessKey(api)
    const statuses: number[] = []
    for (let i = 0; i < 130; i++) statuses.push((await powSend(api, victim.accountId, randomBytes(ENVELOPE))).status)
    // 8 MiB / 64 KiB = 128 envelopes.
    expect(statuses.slice(0, 128).every(status => status === 202)).toBe(true)
    expect(statuses.slice(128)).toEqual([429, 429])
    const refused = await powSend(api, victim.accountId, randomBytes(100))
    expect(refused.status).toBe(429)
    expect(refused.body.error).toEqual({ code: 'rate_limited', message: 'the recipient’s mailbox is full' })
    expect(Number(refused.headers.get('retry-after'))).toBeGreaterThan(0)

    // A contact (access key) still gets through, and another recipient's strangers too.
    expect((await send(api, victim, randomBytes(ENVELOPE))).status).toBe(202)
    const other = await registerWithAccessKey(api)
    expect((await powSend(api, other.accountId, randomBytes(100))).status).toBe(202)

    // Only the class is stored, nothing about the sender.
    await eventually(async () => await db.count('envelopes WHERE recipient = ? AND via_pow = 1', [uuidBytes(victim.accountId)]) === 128)
    expect(await db.count('envelopes WHERE recipient = ? AND via_pow = 0', [uuidBytes(victim.accountId)])).toBe(1)

    // A restarted relay counts the stored share again.
    await relay.stop()
    relay = await startRelay()
    api = new Api(relay.url)
    expect((await powSend(api, victim.accountId, randomBytes(100))).status).toBe(429)
    expect((await send(api, victim, randomBytes(100))).status).toBe(202)

    // Fetching and acknowledging frees the share again.
    expect(await drain(api, victim, 10)).toBe(10)
    expect((await powSend(api, victim.accountId, randomBytes(ENVELOPE))).status).toBe(202)
  }, 90_000)
})

describe('M-1: per recipient and client address', () => {
  let relay: RelayProcess
  let api: Api

  beforeAll(async () => {
    relay = await startRelay({ TRUST_PROXY: '1', RATE_LIMIT_SEND_PAIR_POW: '2/hour', RATE_LIMIT_SEND_PAIR_BYTES: `${3 * ENVELOPE + 300}/hour` })
    api = new Api(relay.url)
  })

  afterAll(async () => {
    if (relay) {
      expect(relay.errors()).toEqual([])
      await relay.stop()
    }
  })

  const from = (ip: string) => ({ 'x-forwarded-for': ip })

  it('limits proof-of-work sends from one address to one recipient, not to others or from elsewhere', async () => {
    const victim = await registerWithAccessKey(api), other = await registerWithAccessKey(api)
    expect((await powSend(api, victim.accountId, randomBytes(100), from('198.51.100.1'))).status).toBe(202)
    expect((await powSend(api, victim.accountId, randomBytes(100), from('198.51.100.1'))).status).toBe(202)
    const third = await powSend(api, victim.accountId, randomBytes(100), from('198.51.100.1'))
    expect([third.status, third.body.error.code]).toEqual([429, 'rate_limited'])
    expect(Number(third.headers.get('retry-after'))).toBeGreaterThan(0)
    // An IPv6 /64 is one address.
    expect((await powSend(api, victim.accountId, randomBytes(100), from('2001:db8:5:6::1'))).status).toBe(202)
    expect((await powSend(api, victim.accountId, randomBytes(100), from('2001:db8:5:6::2'))).status).toBe(202)
    expect((await powSend(api, victim.accountId, randomBytes(100), from('2001:db8:5:6:ffff::3'))).status).toBe(429)
    expect((await powSend(api, victim.accountId, randomBytes(100), from('198.51.100.2'))).status).toBe(202)
    expect((await powSend(api, other.accountId, randomBytes(100), from('198.51.100.1'))).status).toBe(202)
  })

  it('limits the bytes one address stores for one recipient, also with the access key', async () => {
    const victim = await registerWithAccessKey(api)
    const sendFrom = (ip: string) => api.put(`/v1/messages/${victim.accountId}`, { body: randomBytes(ENVELOPE), headers: { 'x-access-key': victim.accessKey, ...from(ip) } })
    for (let i = 0; i < 3; i++) expect((await sendFrom('203.0.113.9')).status).toBe(202)
    expect((await sendFrom('203.0.113.9')).status).toBe(429)
    expect((await sendFrom('203.0.113.10')).status).toBe(202)
    // Ephemeral sends are never stored and not limited this way.
    const ephemeral = await api.put(`/v1/messages/${victim.accountId}?ephemeral=1`, { body: randomBytes(100), headers: { 'x-access-key': victim.accessKey, ...from('203.0.113.9') } })
    expect(ephemeral.status).toBe(202)
  })
})

describe('M-2: media and envelopes on one disk', () => {
  const upload = (api: Api, account: TestAccount, bytes: number) => api.post('/v1/media', { token: account.authToken, body: randomBytes(bytes) })

  it('stops media at a reserve above the floor that only envelopes may use', async () => {
    // A floor just under the free space now, and a reserve above it larger than that gap.
    const stats = statfsSync(tmpdir())
    const freeMb = Math.floor(stats.bavail * stats.bsize / MiB)
    const relay = await startRelay({ DISK_MIN_FREE_MB: String(freeMb - 256), DISK_ENVELOPE_RESERVE_MB: '1024' })
    try {
      const api = new Api(relay.url)
      const owner = await register(api)
      const refused = await upload(api, owner, 1000)
      expect([refused.status, refused.body.error.code]).toEqual([503, 'internal'])
      expect(Number(refused.headers.get('retry-after'))).toBeGreaterThan(0)
      // Messages still flow: sealed sends and community deliveries.
      const recipient = await registerWithAccessKey(api)
      expect((await send(api, recipient, randomBytes(ENVELOPE))).status).toBe(202)
      const groups = communityApi(api)
      const group = await groups.approve(owner, await groups.created(owner), [recipient])
      expect((await groups.deliver(owner, recipient, group)).status).toBe(202)
      expect(relay.errors()).toEqual([])
    } finally {
      await relay.stop()
    }
  })

  it('caps the media one account keeps stored, lower while it is new, and its daily bytes while new', async () => {
    const relay = await startRelay({
      MEDIA_MAX_BYTES: String(MiB), MEDIA_STORED_MAX_MB: '3', MEDIA_STORED_MAX_NEW_MB: '2',
      RATE_LIMIT_MEDIA_BYTES_NEW: `${3 * MiB}/day`,
    })
    const db = await TestDb.connect()
    try {
      const api = new Api(relay.url)
      const fresh = await register(api)
      const today = new Date().toISOString().slice(0, 10)
      expect(await db.count('accounts WHERE id = ? AND created_on = ?', [uuidBytes(fresh.accountId), today])).toBe(1)

      const first = await upload(api, fresh, MiB)
      expect(first.status).toBe(201)
      expect((await upload(api, fresh, MiB)).status).toBe(201)
      const full = await upload(api, fresh, 1)
      expect([full.status, full.body.error.message]).toEqual([429, 'too much media is stored for this account'])
      // Deleting a blob makes room again ...
      expect((await api.delete(`/v1/media/${first.body.mediaId}`, { headers: { 'x-delete-token': first.body.deleteToken } })).status).toBe(204)
      const third = await upload(api, fresh, MiB)
      expect(third.status).toBe(201)
      // ... but a new account's daily bytes are spent (3 MiB).
      expect((await api.delete(`/v1/media/${third.body.mediaId}`, { headers: { 'x-delete-token': third.body.deleteToken } })).status).toBe(204)
      const daily = await upload(api, fresh, MiB)
      expect([daily.status, daily.body.error.code]).toEqual([429, 'rate_limited'])

      // An established account (or one from before the column) gets the full cap.
      const established = await register(api)
      await db.query('UPDATE accounts SET created_on = ? WHERE id = ?', ['1970-01-01', uuidBytes(established.accountId)])
      for (let i = 0; i < 3; i++) expect((await upload(api, established, MiB)).status).toBe(201)
      expect((await upload(api, established, 1)).status).toBe(429)
      expect(relay.errors()).toEqual([])
    } finally {
      await db.close()
      await relay.stop()
    }
  })

  it('refuses uploads with 503 once the relay-wide daily budget is spent, and still delivers messages', async () => {
    const relay = await startRelay({ MEDIA_MAX_BYTES: String(MiB), RATE_LIMIT_MEDIA_BYTES_GLOBAL: `${2 * MiB}/day` })
    try {
      const api = new Api(relay.url)
      const [a, b, c] = [await register(api), await register(api), await register(api)]
      expect((await upload(api, a, MiB)).status).toBe(201)
      expect((await upload(api, b, MiB)).status).toBe(201)
      const refused = await upload(api, c, 1000)
      expect([refused.status, refused.body.error.code]).toEqual([503, 'internal'])
      const retry = Number(refused.headers.get('retry-after'))
      expect(retry).toBeGreaterThan(0)
      expect(retry).toBeLessThanOrEqual(3600)
      const recipient = await registerWithAccessKey(api)
      expect((await send(api, recipient, randomBytes(100))).status).toBe(202)
      expect(relay.errors()).toEqual([])
    } finally {
      await relay.stop()
    }
  })
})

describe('M-3: account creation', () => {
  it('raises the registration difficulty with the recent registration rate', async () => {
    const relay = await startRelay({ POW_REGISTER_ADAPT_PER_HOUR: '2', POW_DIFFICULTY_REGISTER_MAX: '7' })
    try {
      const api = new Api(relay.url)
      const difficulty = async () => (await api.get('/v1/pow?purpose=register')).body.difficulty as number
      expect(await difficulty()).toBe(4)
      await register(api)
      expect(await difficulty()).toBe(4)
      await register(api)
      expect(await difficulty()).toBe(5)
      await register(api)
      await register(api)
      expect(await difficulty()).toBe(6)
      for (let i = 0; i < 12; i++) await register(api)
      expect(await difficulty()).toBe(7)
      // The message difficulty is not affected.
      expect((await api.get(`/v1/pow?purpose=message&to=${(await register(api)).accountId}`)).body.difficulty).toBe(4)
    } finally {
      await relay.stop()
    }
  })

  it('limits registrations per IPv6 /48 and for the whole relay', async () => {
    const relay = await startRelay({ TRUST_PROXY: '1', RATE_LIMIT_REGISTER: '1/hour', RATE_LIMIT_REGISTER_PREFIX: '2/hour', RATE_LIMIT_REGISTER_GLOBAL: '4/hour' })
    try {
      const api = new Api(relay.url)
      const registerFrom = async (ip: string) => {
        const pow = await solvedPow(api, 'purpose=register')
        return api.post('/v1/accounts', { json: { ...registrationBody(), pow }, headers: { 'x-forwarded-for': ip } })
      }
      // Two /64s of one /48: the /64 limit alone would allow both, the /48 limit stops the third.
      expect((await registerFrom('2001:db8:aa:1::1')).status).toBe(201)
      expect((await registerFrom('2001:db8:aa:1::2')).status).toBe(429)
      expect((await registerFrom('2001:db8:aa:2::1')).status).toBe(201)
      expect((await registerFrom('2001:db8:aa:3::1')).status).toBe(429)
      // IPv4 addresses keep their own buckets.
      expect((await registerFrom('198.51.100.30')).status).toBe(201)
      expect((await registerFrom('198.51.100.31')).status).toBe(201)
      // The relay-wide ceiling (4/hour): a fifth registration from anywhere is refused,
      // before any proof-of-work is spent.
      const refused = await registerFrom('198.51.100.32')
      expect([refused.status, refused.body.error.code]).toEqual([429, 'rate_limited'])
      expect(relay.errors()).toEqual([])
    } finally {
      await relay.stop()
    }
  })
})

describe('L-3: one-time pre-keys per target', () => {
  it('hands out one-time keys of one account only at the per-target rate, whoever asks, and keeps the rest', async () => {
    const relay = await startRelay({ RATE_LIMIT_KEY_TARGET: '2/hour' })
    try {
      const api = new Api(relay.url)
      const target = await register(api)
      const requesters = [await register(api), await register(api), await register(api)]
      const bundles = []
      for (const requester of requesters) bundles.push((await api.get(`/v1/keys/${target.accountId}`, { token: requester.authToken })).body)
      expect(bundles[0].oneTimePreKey).not.toBeNull()
      expect(bundles[1].oneTimePreKey).not.toBeNull()
      // Past the budget: a valid bundle with the signed and last-resort keys only.
      expect(bundles[2].oneTimePreKey).toBeNull()
      expect(bundles[2].kyberPreKey.keyId).toBe(1)
      expect(bundles[2].signedPreKey.keyId).toBe(1)
      const status = await api.get('/v1/keys/status', { token: target.authToken })
      expect(status.body).toMatchObject({ oneTimePreKeys: 3, kyberOneTimePreKeys: 3 })
      // Another account's keys are unaffected.
      const other = await register(api)
      expect((await api.get(`/v1/keys/${other.accountId}`, { token: requesters[0]!.authToken })).body.oneTimePreKey).not.toBeNull()
    } finally {
      await relay.stop()
    }
  })
})

describe('trusted proxy header', () => {
  const pow = (api: Api, headers: Record<string, string>) => api.get('/v1/pow?purpose=register', { headers })

  it('believes the header only from a TRUST_PROXY_FROM peer', async () => {
    const relay = await startRelay({ RATE_LIMIT_POW: '1/min', TRUST_PROXY: '1', TRUST_PROXY_FROM: '192.0.2.1' })
    try {
      const api = new Api(relay.url)
      // The test connects from 127.0.0.1, which is not the configured proxy: one bucket for all.
      expect((await pow(api, { 'x-forwarded-for': '198.51.100.1' })).status).toBe(200)
      expect((await pow(api, { 'x-forwarded-for': '198.51.100.2' })).status).toBe(429)
    } finally {
      await relay.stop()
    }
  })

  it('reads X-Real-IP when told to, and ignores a value that is not an address', async () => {
    const relay = await startRelay({ RATE_LIMIT_POW: '1/min', TRUST_PROXY: '1', TRUST_PROXY_HEADER: 'x-real-ip' })
    try {
      const api = new Api(relay.url)
      expect((await pow(api, { 'x-real-ip': '198.51.100.1' })).status).toBe(200)
      expect((await pow(api, { 'x-real-ip': '198.51.100.2' })).status).toBe(200)
      expect((await pow(api, { 'x-real-ip': '198.51.100.1' })).status).toBe(429)
      // X-Forwarded-For is not the configured header.
      expect((await pow(api, { 'x-real-ip': '198.51.100.2', 'x-forwarded-for': '198.51.100.3' })).status).toBe(429)
      // Garbage falls back to the peer address (127.0.0.1), not to a bucket of its own.
      expect((await pow(api, { 'x-real-ip': 'not-an-ip' })).status).toBe(200)
      expect((await pow(api, { 'x-real-ip': 'still-not-an-ip' })).status).toBe(429)
    } finally {
      await relay.stop()
    }
  })
})

describe('M-12 – M-14 and L-22: community requests, tombstones and retries', () => {
  let relay: RelayProcess
  let api: Api
  let db: TestDb
  let groups: ReturnType<typeof communityApi>
  const DAY = 86_400_000

  beforeAll(async () => {
    relay = await startRelay({ RETENTION_INTERVAL_SECONDS: '1', RATE_LIMIT_COMMUNITY_PAIR: '5/day' })
    api = new Api(relay.url)
    groups = communityApi(api)
    db = await TestDb.connect()
  })

  afterAll(async () => {
    await db.close()
    if (relay) {
      expect(relay.errors()).toEqual([])
      await relay.stop()
    }
  })

  const pendingIds = async (owner: TestAccount, id: string) =>
    ((await groups.pending(owner, id)).body.requests as Array<{ accountId: string }>).map(request => request.accountId)

  it('caps the pending requests of one account at 10 over all communities', async () => {
    const owner = await register(api), applicant = await register(api)
    const ids: string[] = []
    for (let i = 0; i < 11; i++) ids.push((await groups.created(owner)).id)
    for (const id of ids.slice(0, 10)) expect((await groups.request(applicant, id)).status).toBe(202)
    const refused = await groups.request(applicant, ids[10]!)
    expect([refused.status, refused.body.error.code]).toEqual([429, 'rate_limited'])
    // Repeating a request that is pending is still fine, and withdrawing one makes room.
    expect((await groups.request(applicant, ids[0]!)).status).toBe(202)
    expect((await groups.withdraw(applicant, ids[0]!)).status).toBe(204)
    expect((await groups.request(applicant, ids[10]!)).status).toBe(202)
  })

  it('lets the owner reject every pending request at once', async () => {
    const owner = await register(api), a = await register(api), b = await register(api), outsider = await register(api)
    const group = await groups.created(owner)
    for (const person of [a, b]) expect((await groups.request(person, group.id)).status).toBe(202)
    expect((await api.delete(`/v1/communities/${group.id}/requests`, { token: outsider.authToken })).status).toBe(404)
    expect(await pendingIds(owner, group.id)).toHaveLength(2)
    expect((await api.delete(`/v1/communities/${group.id}/requests`, { token: owner.authToken })).status).toBe(204)
    expect(await pendingIds(owner, group.id)).toEqual([])
    expect((await api.delete(`/v1/communities/${group.id}/requests`, { token: owner.authToken })).status).toBe(204)
  })

  it('expires requests after 14 days: not listed, not admitted, replaced by a new request, then swept', async () => {
    const owner = await register(api), stale = await register(api), other = await register(api)
    const group = await groups.created(owner)
    for (const person of [stale, other]) expect((await groups.request(person, group.id)).status).toBe(202)
    await db.query('UPDATE community_requests SET created_at = ? WHERE account_id = ?', [Date.now() - 14 * DAY - 60_000, uuidBytes(stale.accountId)])
    expect(await pendingIds(owner, group.id)).toEqual([other.accountId])
    const next = { ...group, revision: 2, members: [...group.members, stale].sort((x, y) => Buffer.compare(uuidBytes(x.accountId), uuidBytes(y.accountId))) }
    expect((await groups.update(owner, next)).status).toBe(403)
    // A new request replaces the expired one.
    expect((await groups.request(stale, group.id)).status).toBe(202)
    expect((await pendingIds(owner, group.id)).sort()).toEqual([stale.accountId, other.accountId].sort())
    // The sweep removes what expired.
    await db.query('UPDATE community_requests SET created_at = ? WHERE account_id = ?', [Date.now() - 14 * DAY - 60_000, uuidBytes(other.accountId)])
    await eventually(async () => await db.count('community_requests WHERE community_id = ? AND account_id = ?', [uuidBytes(group.id), uuidBytes(other.accountId)]) === 0)
    expect(await db.count('community_requests WHERE community_id = ?', [uuidBytes(group.id)])).toBe(1)
  })

  it('sweeps acknowledged tombstones after 72 hours and keeps newer ones for retries', async () => {
    const owner = await register(api), member = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member])
    const messageId = '7c5b1f0e-5a9d-4b8e-9c3a-2f1e0d9c8b7a'
    const body = randomBytes(100)
    const sent = await groups.deliver(owner, member, group, messageId, body)
    expect(sent.status).toBe(202)
    const page = (await groups.inbox(member)).body
    expect((await groups.ack(member, page.envelopes.map((item: { id: string }) => item.id))).status).toBe(204)
    // A fresh tombstone still answers a retry with the first result.
    await new Promise(resolve => setTimeout(resolve, 1500))
    expect((await groups.deliver(owner, member, group, messageId, body)).body).toEqual(sent.body)
    // An old one is swept.
    await db.query('UPDATE community_envelopes SET server_ts = ? WHERE recipient = ?', [Date.now() - 73 * 3600_000, uuidBytes(member.accountId)])
    await eventually(async () => await db.count('community_envelopes WHERE recipient = ?', [uuidBytes(member.accountId)]) === 0)
  })

  it('accepts a message re-encrypted for a new revision under the same message ID', async () => {
    const owner = await register(api), a = await register(api), b = await register(api)
    let group = await groups.approve(owner, await groups.created(owner), [a])
    const messageId = '0d6a2c1b-3e4f-4a5b-8c7d-9e0f1a2b3c4d'
    expect((await groups.deliver(owner, a, group, messageId)).status).toBe(202)
    // The roster changes before A fetched it: the queued body is dropped.
    group = await groups.approve(owner, group, [b])
    const retried = await groups.deliver(owner, a, group, messageId, randomBytes(120))
    expect(retried.status).toBe(202)
    const page = (await groups.inbox(a)).body
    expect(page.envelopes).toHaveLength(1)
    expect(page.envelopes[0].revision).toBe(Number(group.revision))
    // Other ciphertext at the same revision is still a conflict.
    expect((await groups.deliver(owner, a, group, messageId, randomBytes(120))).status).toBe(409)
  })

  it('limits the deliveries of one sender to one recipient per day, tombstones included', async () => {
    const owner = await register(api), member = await register(api), friend = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member, friend])
    for (let i = 0; i < 5; i++) {
      expect((await groups.deliver(owner, member, group)).status).toBe(202)
      const page = (await groups.inbox(member)).body
      await groups.ack(member, page.envelopes.map((item: { id: string }) => item.id))
    }
    const refused = await groups.deliver(owner, member, group)
    expect([refused.status, refused.body.error.code]).toEqual([429, 'rate_limited'])
    expect((await groups.deliver(owner, friend, group)).status).toBe(202)
    expect((await groups.deliver(friend, member, group)).status).toBe(202)
  })
})
