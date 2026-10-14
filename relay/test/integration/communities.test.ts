import { randomBytes, randomUUID } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { createDatabase } from '../../server/db/client'
import { COMMUNITY_TTL, parseCommunityState } from '../../server/lib/community'
import { CommunityStore } from '../../server/services/communities'
import { Api, b64u, register, registerWithAccessKey, send, sha256, signature, type ApiResponse, type TestAccount } from '../support/client'
import { communityApi, communityBody, communityState, encodeCommunity, sortedMembers, type TestCommunityState } from '../support/community'
import { TestDb, uuidBytes } from '../support/db'
import { TEST_DATABASE_URL } from '../support/env'
import { startRelay, type RelayProcess } from '../support/relay'

let relay: RelayProcess
let api: Api
let db: TestDb
let people: TestAccount[]
let groups: ReturnType<typeof communityApi>

beforeAll(async () => {
  relay = await startRelay()
  api = new Api(relay.url)
  groups = communityApi(api)
  db = await TestDb.connect()
  people = []
  // Bound setup concurrency just as client fanout must be bounded.
  for (let offset = 0; offset < 101; offset += 10) {
    people.push(...await Promise.all(Array.from({ length: Math.min(10, 101 - offset) }, () => register(api))))
  }
})

afterAll(async () => {
  if (db) await db.close()
  if (relay) { await relay.stop(); expect(relay.errors()).toEqual([]) }
})

function status<T>(response: ApiResponse<T>, expected: number): T {
  expect(response.status, JSON.stringify(response.body)).toBe(expected)
  return response.body
}

async function create(owner: TestAccount, options: Partial<TestCommunityState> = {}) {
  const state = communityState(owner, options)
  const body = communityBody(state)
  expect(status(await api.post('/v1/communities', { token: owner.authToken, json: body }), 201)).toEqual(body)
  return state
}

const request = (person: TestAccount, state: TestCommunityState) =>
  api.post(`/v1/communities/${state.id}/requests`, { token: person.authToken, json: {} })
const update = (owner: TestAccount, state: TestCommunityState) =>
  api.put(`/v1/communities/${state.id}/state`, { token: owner.authToken, json: communityBody(state) })
const inbox = (person: TestAccount) => api.get('/v1/communities/inbox', { token: person.authToken })
const ack = (person: TestAccount, ids: string[]) => api.post('/v1/communities/inbox/ack', { token: person.authToken, json: { ids } })
const deliver = (sender: TestAccount, recipient: TestAccount, state: TestCommunityState, messageId = randomUUID(), body = randomBytes(100)) =>
  api.put(`/v1/communities/${state.id}/messages/${recipient.accountId}/${messageId}`, {
    token: sender.authToken, headers: { 'x-community-revision': String(state.revision) }, body,
  })

async function approve(owner: TestAccount, state: TestCommunityState, additions: TestAccount[]) {
  for (let offset = 0; offset < additions.length; offset += 10) {
    const batch = await Promise.all(additions.slice(offset, offset + 10).map(person => request(person, state)))
    batch.forEach(response => status(response, 202))
  }
  const next = { ...state, revision: Number(state.revision) + 1, members: sortedMembers([...state.members, ...additions]) }
  status(await update(owner, next), 200)
  return next
}

describe('community discovery and membership', () => {
  it('lists public names with literal prefix search and keeps private groups unlisted and inaccessible', async () => {
    const owner = await register(api)
    const label = `Search-${randomUUID()}`
    const publicGroup = await create(owner, { name: `${label}%_ üye` })
    const privateGroup = await create(owner, { visibility: 0, name: '' })
    const viewer = people[0]!
    const found = status(await api.get(`/v1/communities?query=${encodeURIComponent(label.toUpperCase())}`, { token: viewer.authToken }), 200)
    expect(found.communities.map((group: { id: string }) => group.id)).toEqual([publicGroup.id])
    const literal = status(await api.get(`/v1/communities?query=${encodeURIComponent(`${label}%_`)}`, { token: viewer.authToken }), 200)
    expect(literal.communities.map((group: { id: string }) => group.id)).toEqual([publicGroup.id])
    const all = status(await api.get('/v1/communities', { token: viewer.authToken }), 200)
    expect(all.communities.some((group: { id: string }) => group.id === privateGroup.id)).toBe(false)
    status(await api.get(`/v1/communities/${privateGroup.id}`, { token: viewer.authToken }), 404)
    status(await request(viewer, privateGroup), 404)
    status(await deliver(viewer, owner, { ...privateGroup, revision: 999 }), 404)
    status(await api.get(`/v1/communities/${privateGroup.id}/requests`, { token: viewer.authToken }), 404)
    expect(await db.query<{ name: string, search_name: string }>('SELECT name, search_name FROM communities WHERE id = ?', [uuidBytes(privateGroup.id)]))
      .toEqual([{ name: '', search_name: '' }])
    status(await api.get('/v1/communities'), 401)
  })

  it('requires owner approval and rejects actor, identity, roster and revision substitution', async () => {
    const owner = await register(api), applicant = people[1]!, outsider = people[2]!
    const group = await create(owner)
    status(await request(applicant, group), 202)
    status(await request(applicant, group), 202)
    const pending = status(await api.get(`/v1/communities/${group.id}/requests`, { token: owner.authToken }), 200)
    expect(pending.requests).toEqual([{ accountId: applicant.accountId, identityKey: applicant.identityKey, createdAt: expect.any(Number) }])
    status(await api.get(`/v1/communities/${group.id}`, { token: applicant.authToken }), 404)
    const next = { ...group, revision: 2, members: sortedMembers([owner, applicant]) }
    status(await update(applicant, next), 404)
    status(await update(owner, { ...next, members: sortedMembers([owner, outsider]) }), 403)
    status(await update(owner, { ...next, members: sortedMembers([owner, { ...applicant, identityKey: outsider.identityKey }]) }), 400)
    status(await update(owner, { ...next, owner: applicant }), 400)
    status(await update(owner, next), 200)
    status(await update(owner, next), 409)
    status(await update(owner, { ...next, revision: 3, name: 'Changed' }), 400)
    const mine = status(await api.get('/v1/communities/mine', { token: applicant.authToken }), 200)
    expect(mine.communities.some((item: { state: string }) => parseCommunityState(Buffer.from(item.state, 'base64url')).id.equals(uuidBytes(group.id)))).toBe(true)
    expect(status(await api.get(`/v1/communities/${group.id}/requests`, { token: owner.authToken }), 200).requests).toEqual([])
  })

  it('serializes competing approvals at99 and never admits member101', async () => {
    const owner = await register(api)
    let group = await create(owner)
    group = await approve(owner, group, people.slice(0, 98))
    expect(group.members).toHaveLength(99)
    const candidates = people.slice(98, 100)
    for (const person of candidates) status(await request(person, group), 202)
    const attempts = candidates.map(person => ({ ...group, revision: 3, members: sortedMembers([...group.members, person]) }))
    const outcomes = await Promise.all(attempts.map(state => update(owner, state)))
    expect(outcomes.map(response => response.status).sort()).toEqual([200, 409])
    const current = status(await api.get(`/v1/communities/${group.id}`, { token: owner.authToken }), 200)
    expect(parseCommunityState(Buffer.from(current.state, 'base64url')).members).toHaveLength(100)
    expect(await db.count('community_members WHERE community_id = ?', [uuidBytes(group.id)])).toBe(100)
    expect(status(await request(people[100]!, group), 409).error.code).toBe('community_full')
    const winner = attempts[outcomes.findIndex(response => response.status === 200)]!
    const loser = candidates[outcomes.findIndex(response => response.status === 409)]!
    expect(status(await update(owner, { ...winner, revision: 4, members: sortedMembers([...winner.members, loser]) }), 409).error.code).toBe('community_full')
  })
})

describe('private community invitations', () => {
  it('consumes once across racing accounts, permits only same-account retry, and still needs approval', async () => {
    const owner = await register(api), first = people[3]!, second = people[4]!
    const group = await create(owner, { visibility: 0, name: '' })
    const invitation = status(await api.post(`/v1/communities/${group.id}/invites`, { token: owner.authToken, json: { expiresInSeconds: 60 } }), 201)
    const redeem = (person: TestAccount) => api.post('/v1/communities/invites/redeem', { token: person.authToken,
      json: { communityId: group.id, token: invitation.token } })
    const outcomes = await Promise.all([redeem(first), redeem(second)])
    expect(outcomes.map(response => response.status).sort()).toEqual([202, 404])
    const winner = outcomes[0]!.status === 202 ? first : second
    status(await redeem(winner), 202)
    status(await api.get(`/v1/communities/${group.id}`, { token: winner.authToken }), 404)
    expect(await db.count('community_members WHERE community_id = ?', [uuidBytes(group.id)])).toBe(1)
    const rows = await db.query<{ token_hash: Buffer }>('SELECT token_hash FROM community_invites WHERE community_id = ?', [uuidBytes(group.id)])
    expect(rows[0]!.token_hash).toEqual(sha256(Buffer.from(invitation.token, 'base64url')))
    const next = { ...group, revision: 2, members: sortedMembers([owner, winner]) }
    status(await update(owner, next), 200)
    status(await api.get(`/v1/communities/${group.id}`, { token: winner.authToken }), 200)
  })

  it('refuses expired, revoked and rejected invitation replay, including previous redeemer', async () => {
    const owner = await register(api), applicant = people[5]!
    const group = await create(owner, { visibility: 0, name: '' })
    const make = async () => status(await api.post(`/v1/communities/${group.id}/invites`, { token: owner.authToken, json: { expiresInSeconds: 60 } }), 201)
    const redeem = (token: string) => api.post('/v1/communities/invites/redeem', { token: applicant.authToken, json: { communityId: group.id, token } })
    const expired = await make()
    await db.query('UPDATE community_invites SET expires_at = ? WHERE token_hash = ?', [Date.now() - 1, sha256(Buffer.from(expired.token, 'base64url'))])
    status(await redeem(expired.token), 404)
    const revoked = await make()
    status(await redeem(revoked.token), 202)
    status(await api.delete(`/v1/communities/${group.id}/invites`, { token: owner.authToken }), 204)
    status(await redeem(revoked.token), 404)
    const rejected = await make()
    status(await redeem(rejected.token), 202)
    status(await api.delete(`/v1/communities/${group.id}/requests/${applicant.accountId}`, { token: owner.authToken }), 204)
    status(await redeem(rejected.token), 404)
    expect(status(await api.get(`/v1/communities/${group.id}/requests`, { token: owner.authToken }), 200).requests).toEqual([])
  })
})

describe('separate community delivery', () => {
  it('isolates1:1 authorization and mailboxes, makes retries idempotent, and scopes acknowledgements', async () => {
    const owner = await register(api), recipient = await registerWithAccessKey(api), other = people[6]!
    const group = await approve(owner, await create(owner), [recipient])
    const messageId = randomUUID(), cipher = randomBytes(80)
    const path = `/v1/communities/${group.id}/messages/${recipient.accountId}/${messageId}`
    status(await api.put(path, { body: cipher, headers: { 'x-access-key': recipient.accessKey, 'x-community-revision': '2' } }), 401)
    const sent = status(await deliver(owner, recipient, group, messageId, cipher), 202)
    expect(status(await deliver(owner, recipient, group, messageId, cipher), 202)).toEqual(sent)
    status(await deliver(owner, recipient, group, messageId, randomBytes(80)), 409)
    status(await deliver(other, recipient, group), 404)
    status(await deliver(owner, other, group), 404)
    const directCipher = randomBytes(90)
    const direct = status(await send(api, recipient, directCipher), 202)
    const directInbox = status(await api.get('/v1/messages', { token: recipient.authToken }), 200)
    expect(directInbox.messages).toEqual([{ id: direct.id, serverTimestamp: direct.serverTimestamp, envelope: b64u(directCipher) }])
    expect(status(await inbox(recipient), 200).envelopes).toEqual([{ ...sent, communityId: group.id, revision: 2, envelope: b64u(cipher) }])
    status(await ack(other, [sent.id]), 204)
    status(await api.post('/v1/messages/ack', { token: recipient.authToken, json: { ids: [sent.id] } }), 204)
    expect(status(await inbox(recipient), 200).envelopes).toHaveLength(1)
    status(await ack(recipient, [sent.id]), 204)
    expect(status(await inbox(recipient), 200).envelopes).toEqual([])
    expect(status(await deliver(owner, recipient, group, messageId, cipher), 202)).toEqual(sent)
    expect(status(await inbox(recipient), 200).envelopes).toEqual([])
    expect(status(await api.get('/v1/messages', { token: recipient.authToken }), 200).messages).toHaveLength(1)
    status(await api.put(`/v1/messages/${recipient.accountId}`, { token: owner.authToken, body: cipher }), 403)
  })

  it('revokes removed sender and recipient, clears obsolete delivery, and rejects stale retries', async () => {
    const owner = await register(api), removed = people[7]!, remaining = people[8]!
    const group = await approve(owner, await create(owner), [removed, remaining])
    const messageId = randomUUID(), cipher = randomBytes(20)
    status(await deliver(owner, removed, group, messageId, cipher), 202)
    status(await deliver(removed, remaining, group), 202)
    status(await deliver(owner, remaining, group), 202)
    const next = { ...group, revision: 3, members: sortedMembers([owner, remaining]) }
    status(await update(owner, next), 200)
    status(await deliver(removed, remaining, next), 404)
    status(await deliver(owner, removed, next), 404)
    status(await deliver(owner, remaining, group), 409)
    status(await api.get(`/v1/communities/${group.id}`, { token: removed.authToken }), 404)
    expect(status(await inbox(removed), 200).envelopes.some((item: { communityId: string }) => item.communityId === group.id)).toBe(false)
    expect(status(await inbox(remaining), 200).envelopes.some((item: { communityId: string }) => item.communityId === group.id)).toBe(false)
    expect(await db.count('community_envelopes WHERE community_id = ? AND (recipient = ? OR sender = ?)',
      [uuidBytes(group.id), uuidBytes(removed.accountId), uuidBytes(removed.accountId)])).toBe(0)
    // Obsolete-revision bodies are dropped; the rows stay as retry tombstones.
    expect(await db.count('community_envelopes WHERE community_id = ? AND (size > 0 OR body IS NOT NULL)', [uuidBytes(group.id)])).toBe(0)
    status(await deliver(owner, remaining, next), 202)
  })

  it('delivers a100-member fanout as99 ciphertexts with bounded retries and no1:1 mail', async () => {
    const owner = await register(api), recipients = people.slice(0, 99)
    const group = await approve(owner, await create(owner), recipients)
    const messageId = randomUUID(), ciphertexts = recipients.map(() => randomBytes(256)), deliveries: string[] = []
    for (let offset = 0; offset < recipients.length; offset += 8) {
      const batch = await Promise.all(recipients.slice(offset, offset + 8).map((person, index) => deliver(owner, person, group, messageId, ciphertexts[offset + index]!)))
      batch.forEach(response => deliveries.push(status(response, 202).id))
    }
    expect(new Set(deliveries).size).toBe(99)
    const repeated = await Promise.all(recipients.slice(0, 8).map((person, index) => deliver(owner, person, group, messageId, ciphertexts[index]!)))
    repeated.forEach((response, index) => expect(status(response, 202).id).toBe(deliveries[index]))
    expect(await db.count('community_envelopes WHERE community_id = ? AND message_id = ?', [uuidBytes(group.id), uuidBytes(messageId)])).toBe(99)
    for (let offset = 0; offset < recipients.length; offset += 8) {
      const batch = await Promise.all(recipients.slice(offset, offset + 8).map(async (person, index) => {
        const result = status(await inbox(person), 200)
        const groupItems = result.envelopes.filter((item: { communityId: string }) => item.communityId === group.id)
        expect(groupItems).toHaveLength(1)
        expect(groupItems[0].envelope).toBe(b64u(ciphertexts[offset + index]!))
        expect(status(await api.get('/v1/messages', { token: person.authToken }), 200).messages).toEqual([])
      }))
      expect(batch).toHaveLength(Math.min(8, recipients.length - offset))
    }
  })

  it('deletes community state on explicit deletion and owner account deletion', async () => {
    const owner = await register(api), member = people[9]!
    const group = await approve(owner, await create(owner), [member])
    status(await deliver(owner, member, group), 202)
    status(await api.delete(`/v1/communities/${group.id}`, { token: member.authToken }), 404)
    status(await api.delete(`/v1/communities/${group.id}`, { token: owner.authToken }), 204)
    expect(await db.count('community_members WHERE community_id = ?', [uuidBytes(group.id)])).toBe(0)
    expect(await db.count('community_envelopes WHERE community_id = ?', [uuidBytes(group.id)])).toBe(0)
    const second = await create(owner)
    status(await api.delete('/v1/accounts/me', { token: owner.authToken }), 204)
    expect(await db.count('communities WHERE id = ?', [uuidBytes(second.id)])).toBe(0)
  })
})

const code = (response: ApiResponse, expected: number) => status(response, expected).error.code as string
const bytes = (uuid: string) => uuidBytes(uuid)

/** Rows written straight into the database, to reach a limit without thousands of requests. */
async function seedCommunity(owner: TestAccount, members: TestAccount[] = []) {
  const state = communityState(owner, { visibility: 0, name: '', members: sortedMembers([owner, ...members]) })
  await db.query('INSERT INTO communities (id, owner, visibility, name, search_name, revision, state, signature) VALUES (?, ?, 0, \'\', \'\', 1, ?, ?)',
    [bytes(state.id), bytes(owner.accountId), encodeCommunity(state), signature()])
  await db.query('INSERT INTO community_members (community_id, account_id) VALUES ?', [state.members.map(m => [bytes(state.id), bytes(m.accountId)])])
  return state
}

async function seedEnvelopes(group: TestCommunityState, sender: TestAccount, recipient: TestAccount, count: number,
  options: { size?: number, serverTimestamp?: number, tombstone?: boolean } = {}) {
  const rows = Array.from({ length: count }, () => {
    const tombstone = options.tombstone === true
    return [randomBytes(16), bytes(group.id), bytes(sender.accountId), bytes(recipient.accountId), randomBytes(16), group.revision,
      options.serverTimestamp ?? Date.now(), randomBytes(32), tombstone ? 0 : (options.size ?? 1), tombstone ? null : Buffer.from('x')]
  })
  for (let offset = 0; offset < rows.length; offset += 2000) {
    await db.query('INSERT INTO community_envelopes (id, community_id, sender, recipient, message_id, revision, server_ts, digest, size, body) VALUES ?',
      [rows.slice(offset, offset + 2000)])
  }
}

describe('community creation', () => {
  it('validates the initial state and makes an identical retry idempotent, even when the IDs race', async () => {
    const owner = await register(api), other = await register(api)
    const state = communityState(owner)
    status(await groups.create(owner, { ...state, revision: 2 }), 400)
    status(await groups.create(owner, { ...state, members: sortedMembers([owner, other]) }), 400)
    status(await groups.create(other, state), 400) // the actor is not the state's owner
    status(await groups.create(owner, { ...state, owner: { ...owner, identityKey: other.identityKey }, members: [{ ...owner, identityKey: other.identityKey }] }), 400)
    status(await groups.create(owner, state, { ...communityBody(state), signature: b64u(signature()) }), 400)
    const body = communityBody(state)
    expect(status(await groups.create(owner, state, body), 201)).toEqual(body)
    // A retry signs again (XEdDSA signatures are randomized) and gets the stored state back.
    expect(status(await groups.create(owner, state), 201)).toEqual(body)
    expect(code(await groups.create(owner, { ...state, name: 'Another name' }), 409)).toBe('stale_version')
    expect(await db.count('communities WHERE id = ?', [bytes(state.id)])).toBe(1)

    for (let trial = 0; trial < 5; trial++) {
      const same = communityState(owner)
      const identical = await Promise.all([groups.create(owner, same), groups.create(owner, same)])
      expect(identical.map(response => response.status)).toEqual([201, 201])
      expect(identical[0]!.body.state).toBe(identical[1]!.body.state)
      const contested = communityState(owner)
      const racing = await Promise.all([groups.create(owner, contested), groups.create(owner, { ...contested, name: 'Rival' })])
      expect(racing.map(response => response.status).sort()).toEqual([201, 409])
      await db.query('DELETE FROM communities WHERE id IN (?, ?)', [bytes(same.id), bytes(contested.id)])
    }
  })

  it('refuses names outside the shared rule', async () => {
    const owner = await register(api)
    for (const name of ['Kitap‮Kulübü', 'Kitap⁦Kulübü', 'Kitap‏', 'Kitap\tKulübü', 'Kitap\u0085', ' Kitap', '', 'a'.repeat(121)]) {
      status(await groups.create(owner, communityState(owner, { name })), 400)
    }
    const family = await groups.created(owner, { name: 'Aile 👨‍👩‍👧' })
    const found = status(await api.get(`/v1/communities?query=${encodeURIComponent('aile 👨')}`, { token: owner.authToken }), 200)
    expect(found.communities.find((group: { id: string }) => group.id === family.id)?.name).toBe('Aile 👨‍👩‍👧')
    status(await api.get(`/v1/communities?query=${encodeURIComponent('Aile‮')}`, { token: owner.authToken }), 400)
    status(await api.get(`/v1/communities?query=${encodeURIComponent('a'.repeat(121))}`, { token: owner.authToken }), 400)
  })
})

describe('community limits', () => {
  it('caps owned communities at 20 and memberships at 50', async () => {
    const owner = await register(api)
    for (let i = 0; i < 20; i++) await seedCommunity(owner)
    expect(code(await groups.create(owner, communityState(owner)), 429)).toBe('rate_limited')

    const joiner = await register(api), host = await register(api)
    const open = await groups.created(host)
    for (let i = 0; i < 50; i++) await seedCommunity(host, [joiner])
    expect(code(await groups.request(joiner, open.id), 429)).toBe('rate_limited')
    expect(code(await groups.create(joiner, communityState(joiner)), 429)).toBe('rate_limited')
    expect(status(await api.get('/v1/communities/mine', { token: joiner.authToken }), 200).communities).toHaveLength(50)
  })

  it('caps pending requests at 100 and live invitations at 20 with 60..604800 s expiry', async () => {
    const owner = await register(api)
    const group = await groups.created(owner)
    await db.query('INSERT INTO community_requests (community_id, account_id, created_at) VALUES ?',
      [people.slice(0, 100).map((person, index) => [bytes(group.id), bytes(person.accountId), Date.now() + index])])
    expect(code(await groups.request(people[100]!, group.id), 429)).toBe('rate_limited')
    expect(status(await groups.pending(owner, group.id), 200).requests).toHaveLength(100)

    for (const expiry of [59, 604801, 60.5, '60', null, -1]) status(await groups.invite(owner, group.id, expiry), 400)
    const longest = status(await groups.invite(owner, group.id, 604800), 201)
    expect(longest.expiresAt).toBeGreaterThan(Date.now() + 604790_000)
    expect(longest.expiresAt).toBeLessThanOrEqual(Date.now() + 604800_000)
    for (let i = 1; i < 20; i++) status(await groups.invite(owner, group.id, 60), 201)
    expect(code(await groups.invite(owner, group.id, 60), 429)).toBe('rate_limited')
    // Expired invitations stop counting.
    await db.query('UPDATE community_invites SET expires_at = ? WHERE community_id = ? AND expires_at < ?', [Date.now() - 1, bytes(group.id), Date.now() + 120_000])
    status(await groups.invite(owner, group.id, 60), 201)
  })
})

describe('community transport validation', () => {
  it('bounds envelopes, revision headers and acknowledgements', async () => {
    const owner = await register(api), member = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member])
    status(await groups.deliver(owner, member, group, randomUUID(), Buffer.alloc(0)), 400)
    status(await groups.deliver(owner, member, group, randomUUID(), randomBytes(65536)), 202)
    status(await groups.deliver(owner, member, group, randomUUID(), randomBytes(65537)), 413)
    const path = `/v1/communities/${group.id}/messages/${member.accountId}/${randomUUID()}`
    status(await api.put(path, { token: owner.authToken, body: randomBytes(10) }), 400)
    for (const revision of ['', '0', '02', 'abc', '2.0', '-2', '+2', '0x2', '9007199254740992', '12345678901234567']) {
      status(await api.put(path, { token: owner.authToken, body: randomBytes(10), headers: { 'x-community-revision': revision } }), 400)
    }
    status(await api.put(`/v1/communities/${group.id}/messages/${member.accountId}/not-a-uuid`, { token: owner.authToken, body: randomBytes(10), headers: { 'x-community-revision': '2' } }), 400)
    status(await groups.ack(member, Array.from({ length: 101 }, () => b64u(randomBytes(16)))), 400)
    status(await groups.ack(member, [b64u(randomBytes(15))]), 400)
    status(await api.post('/v1/communities/inbox/ack', { token: member.authToken, json: { ids: 'x' } }), 400)
    status(await groups.ack(member, Array.from({ length: 100 }, () => b64u(randomBytes(16)))), 204)
  })

  it('pages the inbox by count and by bytes, oldest first, with `more`', async () => {
    const owner = await register(api), member = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member])
    const sent: string[] = []
    for (let i = 0; i < 150; i++) sent.push(status(await groups.deliver(owner, member, group, randomUUID(), randomBytes(20)), 202).id)
    const first = status(await groups.inbox(member), 200)
    expect(first.envelopes.map((item: { id: string }) => item.id)).toEqual(sent.slice(0, 100))
    expect(first.more).toBe(true)
    status(await groups.ack(member, sent.slice(0, 100)), 204)
    const second = status(await groups.inbox(member), 200)
    expect(second.envelopes.map((item: { id: string }) => item.id)).toEqual(sent.slice(100))
    expect(second.more).toBe(false)
    status(await groups.ack(member, sent.slice(100)), 204)

    for (let i = 0; i < 20; i++) status(await groups.deliver(owner, member, group, randomUUID(), randomBytes(60_000)), 202)
    const large = status(await groups.inbox(member), 200)
    expect(large.envelopes).toHaveLength(17) // 17 x 60000 <= 1 MiB < 18 x 60000
    expect(large.more).toBe(true)
  })

  it('drops live mail that lost its membership or revision instead of letting it hold up the inbox', async () => {
    const owner = await register(api), member = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member])
    const other = await groups.created(owner) // the member is not in it
    await seedEnvelopes(other, owner, member, 150, { serverTimestamp: Date.now() - 60_000 })
    await seedEnvelopes({ ...group, revision: 1 }, owner, member, 5, { serverTimestamp: Date.now() - 60_000 })
    const sent = status(await groups.deliver(owner, member, group), 202)
    const first = status(await groups.inbox(member), 200)
    expect(first).toEqual({ envelopes: [], more: true })
    const second = status(await groups.inbox(member), 200)
    expect(second.envelopes.map((item: { id: string }) => item.id)).toEqual([sent.id])
    expect(second.more).toBe(false)
    expect(await db.count('community_envelopes WHERE recipient = ? AND size > 0', [bytes(member.accountId)])).toBe(1)
  })

  it('sweeps expired deliveries and invitations in batches until none are left', async () => {
    const owner = await register(api), member = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [member])
    const expired = Date.now() - COMMUNITY_TTL - 1000
    await seedEnvelopes(group, owner, member, 1500, { serverTimestamp: expired })
    await seedEnvelopes(group, member, owner, 1500, { serverTimestamp: expired, tombstone: true })
    await seedEnvelopes(group, owner, member, 3)
    await db.query('INSERT INTO community_invites (token_hash, community_id, expires_at) VALUES ?',
      [Array.from({ length: 1200 }, () => [randomBytes(32), bytes(group.id), Date.now() - 1])])
    // Unswept expired rows are invisible and do not count.
    expect(status(await groups.inbox(member), 200).envelopes).toHaveLength(3)
    const database = createDatabase(TEST_DATABASE_URL, 2)
    try {
      const result = await new CommunityStore(database).sweep(Date.now())
      expect(result.communityEnvelopes).toBeGreaterThanOrEqual(3000)
      expect(result.communityInvites).toBeGreaterThanOrEqual(1200)
    } finally {
      await database.close()
    }
    expect(await db.count('community_envelopes WHERE community_id = ?', [bytes(group.id)])).toBe(3)
    expect(await db.count('community_invites WHERE community_id = ?', [bytes(group.id)])).toBe(0)
  })
})

describe('leaving and withdrawing', () => {
  it('lets a member leave at once, purging its queued mail in that community only', async () => {
    const owner = await register(api), leaver = await register(api), stayer = await register(api), outsider = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [leaver, stayer])
    const elsewhere = await groups.approve(owner, await groups.created(owner), [leaver])
    status(await groups.deliver(owner, leaver, group), 202)
    status(await groups.deliver(leaver, stayer, group), 202)
    status(await groups.deliver(stayer, owner, group), 202)
    status(await groups.deliver(owner, leaver, elsewhere), 202)

    expect(code(await groups.leave(owner, group.id), 409)).toBe('bad_request')
    status(await groups.leave(outsider, group.id), 404)
    status(await groups.leave(leaver, randomUUID()), 404)
    status(await api.delete(`/v1/communities/${group.id}/members/me`, { headers: { 'x-access-key': b64u(randomBytes(16)) } }), 401)
    status(await groups.leave(leaver, group.id), 204)
    status(await groups.leave(leaver, group.id), 404)

    status(await groups.get(leaver, group.id), 404)
    expect(await db.count('community_envelopes WHERE community_id = ? AND (sender = ? OR recipient = ?)',
      [bytes(group.id), bytes(leaver.accountId), bytes(leaver.accountId)])).toBe(0)
    expect(await db.count('community_envelopes WHERE community_id = ?', [bytes(group.id)])).toBe(1)
    expect(await db.count('community_envelopes WHERE community_id = ?', [bytes(elsewhere.id)])).toBe(1)
    const leaverInbox = status(await groups.inbox(leaver), 200).envelopes
    expect(leaverInbox.map((item: { communityId: string }) => item.communityId)).toEqual([elsewhere.id])
    // A send to or from someone who left is answered like one to a removed member.
    const toLeaver = await groups.deliver(owner, leaver, group), fromLeaver = await groups.deliver(leaver, owner, group)
    expect([toLeaver.status, fromLeaver.status]).toEqual([404, 404])
    expect(toLeaver.body).toEqual(status(await groups.deliver(owner, outsider, group), 404))
    const mine = status(await api.get('/v1/communities/mine', { token: leaver.authToken }), 200).communities
    expect(mine.map((item: { state: string }) => parseCommunityState(Buffer.from(item.state, 'base64url')).id.toString('hex'))).toEqual([bytes(elsewhere.id).toString('hex')])
  })

  it('accepts the owner’s next state with or without the departed member, and re-admits only on a new request', async () => {
    const owner = await register(api), leaver = await register(api), stayer = await register(api)
    let group = await groups.approve(owner, await groups.created(owner), [leaver, stayer])
    status(await groups.leave(leaver, group.id), 204)
    // Still listed (the owner has not noticed yet): accepted, but no transport.
    group = { ...group, revision: 3 }
    status(await groups.update(owner, group), 200)
    status(await groups.get(leaver, group.id), 404)
    status(await groups.deliver(owner, leaver, group), 404)
    // Dropped: accepted.
    const dropped = { ...group, revision: 4, members: sortedMembers([owner, stayer]) }
    status(await groups.update(owner, dropped), 200)
    // Listed again without a request: refused.
    const readded = { ...dropped, revision: 5, members: sortedMembers([owner, stayer, leaver]) }
    expect(code(await groups.update(owner, readded), 403)).toBe('access_denied')
    status(await groups.request(leaver, group.id), 202)
    status(await groups.update(owner, readded), 200)
    status(await groups.get(leaver, group.id), 200)
    status(await groups.deliver(owner, leaver, readded), 202)

    // A departed member still listed comes back the same way.
    status(await groups.leave(leaver, group.id), 204)
    status(await groups.request(leaver, group.id), 202)
    status(await groups.update(owner, { ...readded, revision: 6 }), 200)
    status(await groups.get(leaver, group.id), 200)
  })

  it('withdraws the caller’s own request idempotently and stops a redeemed invitation from being replayed', async () => {
    const owner = await register(api), applicant = await register(api), bystander = await register(api)
    const group = await groups.created(owner)
    status(await groups.request(applicant, group.id), 202)
    status(await groups.request(bystander, group.id), 202)
    status(await groups.withdraw(applicant, group.id), 204)
    status(await groups.withdraw(applicant, group.id), 204)
    status(await groups.withdraw(applicant, randomUUID()), 204)
    status(await api.delete(`/v1/communities/${group.id}/requests/me`), 401)
    expect(status(await groups.pending(owner, group.id), 200).requests.map((r: { accountId: string }) => r.accountId)).toEqual([bystander.accountId])
    const next = { ...group, revision: 2, members: sortedMembers([owner, applicant]) }
    expect(code(await groups.update(owner, next), 403)).toBe('access_denied')

    const hidden = await groups.created(owner, { visibility: 0, name: '' })
    const invitation = status(await groups.invite(owner, hidden.id), 201)
    status(await groups.redeem(applicant, hidden.id, invitation.token), 202)
    status(await groups.withdraw(applicant, hidden.id), 204)
    status(await groups.redeem(applicant, hidden.id, invitation.token), 404)
    expect(status(await groups.pending(owner, hidden.id), 200).requests).toEqual([])

    // A member who joined by invitation cannot replay it after leaving.
    const second = status(await groups.invite(owner, hidden.id), 201)
    status(await groups.redeem(bystander, hidden.id, second.token), 202)
    const joined = { ...hidden, revision: 2, members: sortedMembers([owner, bystander]) }
    status(await groups.update(owner, joined), 200)
    status(await groups.leave(bystander, hidden.id), 204)
    status(await groups.redeem(bystander, hidden.id, second.token), 404)
  })
})

describe('membership changes', () => {
  it('removing a member deletes only the invitations it redeemed', async () => {
    const owner = await register(api), removed = await register(api), later = await register(api)
    const hidden = await groups.created(owner, { visibility: 0, name: '' })
    const used = status(await groups.invite(owner, hidden.id), 201), spare = status(await groups.invite(owner, hidden.id), 201)
    status(await groups.redeem(removed, hidden.id, used.token), 202)
    const joined = { ...hidden, revision: 2, members: sortedMembers([owner, removed]) }
    status(await groups.update(owner, joined), 200)
    status(await groups.update(owner, { ...joined, revision: 3, members: [owner] }), 200)
    status(await groups.redeem(removed, hidden.id, used.token), 404)
    status(await groups.redeem(later, hidden.id, spare.token), 202)
  })

  it('cuts off a deleted member account while the owner may keep or drop it from the roster', async () => {
    const owner = await register(api), gone = await register(api), stayer = await register(api)
    const group = await groups.approve(owner, await groups.created(owner), [gone, stayer])
    status(await groups.deliver(gone, stayer, group), 202)
    status(await groups.deliver(stayer, gone, group), 202)
    status(await api.delete('/v1/accounts/me', { token: gone.authToken }), 204)
    expect(await db.count('community_members WHERE account_id = ?', [bytes(gone.accountId)])).toBe(0)
    expect(await db.count('community_envelopes WHERE sender = ? OR recipient = ?', [bytes(gone.accountId), bytes(gone.accountId)])).toBe(0)
    status(await groups.deliver(stayer, gone, group), 404)
    const kept = { ...group, revision: 3 }
    status(await groups.update(owner, kept), 200)
    status(await groups.update(owner, { ...kept, revision: 4, members: sortedMembers([owner, stayer]) }), 200)
    status(await groups.get(stayer, group.id), 200)
  })

  it('never leaves mail for a member removed while sends to it are in flight', async () => {
    for (let trial = 0; trial < 5; trial++) {
      const owner = await register(api), target = await register(api), other = await register(api)
      const group = await groups.approve(owner, await groups.created(owner), [target, other])
      const sends = Array.from({ length: 12 }, (_, i) => i % 2 ? groups.deliver(owner, target, group) : groups.deliver(target, other, group))
      const removal = groups.update(owner, { ...group, revision: 3, members: sortedMembers([owner, other]) })
      const outcomes = await Promise.all(sends)
      status(await removal, 200)
      for (const response of outcomes) expect([202, 404, 409], JSON.stringify(response.body)).toContain(response.status)
      expect(await db.count('community_envelopes WHERE community_id = ? AND (sender = ? OR recipient = ?)',
        [bytes(group.id), bytes(target.accountId), bytes(target.accountId)])).toBe(0)
    }
  })
})
