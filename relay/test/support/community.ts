import { randomBytes, randomUUID } from 'node:crypto'
import { b64u, signature, type Api, type TestAccount } from './client'
import { uuidBytes } from './db'
import { signWithIdentity } from './identity'

export interface TestCommunityState {
  id: string
  revision: number | bigint
  owner: Pick<TestAccount, 'accountId' | 'identityKey'>
  members: Array<Pick<TestAccount, 'accountId' | 'identityKey'>>
  name: string
  visibility: 0 | 1
}

/** Independent implementation of the documented wire format; deliberately
 * does not call the production parser or silently sort malformed test cases. */
export function encodeCommunity(state: TestCommunityState): Buffer {
  const revision = Buffer.alloc(8)
  revision.writeBigUInt64BE(BigInt(state.revision))
  const name = Buffer.from(state.name, 'utf8')
  const nameLength = Buffer.alloc(2)
  nameLength.writeUInt16BE(name.length)
  const count = Buffer.alloc(2)
  count.writeUInt16BE(state.members.length)
  return Buffer.concat([
    Buffer.from('BRP/CommunityState/v1\0', 'ascii'), uuidBytes(state.id), revision,
    Buffer.from([state.visibility]), uuidBytes(state.owner.accountId),
    Buffer.from(state.owner.identityKey, 'base64url'), nameLength, name, count,
    ...state.members.flatMap(member => [uuidBytes(member.accountId), Buffer.from(member.identityKey, 'base64url')]),
  ])
}

export function communityState(owner: TestCommunityState['owner'], overrides: Partial<TestCommunityState> = {}): TestCommunityState {
  return { id: randomUUID(), revision: 1, owner, members: [owner], name: 'Test community', visibility: 1, ...overrides }
}

export function sortedMembers(members: TestCommunityState['members']): TestCommunityState['members'] {
  return [...members].sort((a, b) => Buffer.compare(uuidBytes(a.accountId), uuidBytes(b.accountId)))
}

/** Signed by the owner's identity key when the tests made it (every
 * registered account's), otherwise a random signature the relay refuses. */
export function communityBody(state: TestCommunityState) {
  const bytes = encodeCommunity(state)
  return { state: b64u(bytes), signature: b64u(signWithIdentity(state.owner.identityKey, bytes) ?? signature()) }
}

/** The community routes as the tests use them; every call answers with the raw response. */
export function communityApi(api: Api) {
  const self = {
    create: (owner: TestAccount, state: TestCommunityState, body = communityBody(state)) =>
      api.post('/v1/communities', { token: owner.authToken, json: body }),
    update: (owner: TestAccount, state: TestCommunityState) =>
      api.put(`/v1/communities/${state.id}/state`, { token: owner.authToken, json: communityBody(state) }),
    get: (person: TestAccount, id: string) => api.get(`/v1/communities/${id}`, { token: person.authToken }),
    request: (person: TestAccount, id: string) => api.post(`/v1/communities/${id}/requests`, { token: person.authToken, json: {} }),
    pending: (owner: TestAccount, id: string) => api.get(`/v1/communities/${id}/requests`, { token: owner.authToken }),
    withdraw: (person: TestAccount, id: string) => api.delete(`/v1/communities/${id}/requests/me`, { token: person.authToken }),
    leave: (person: TestAccount, id: string) => api.delete(`/v1/communities/${id}/members/me`, { token: person.authToken }),
    invite: (owner: TestAccount, id: string, expiresInSeconds: unknown = 60) =>
      api.post(`/v1/communities/${id}/invites`, { token: owner.authToken, json: { expiresInSeconds } }),
    redeem: (person: TestAccount, id: string, token: string) =>
      api.post('/v1/communities/invites/redeem', { token: person.authToken, json: { communityId: id, token } }),
    inbox: (person: TestAccount) => api.get('/v1/communities/inbox', { token: person.authToken }),
    ack: (person: TestAccount, ids: string[]) => api.post('/v1/communities/inbox/ack', { token: person.authToken, json: { ids } }),
    deliver: (sender: TestAccount, recipient: TestAccount, state: Pick<TestCommunityState, 'id' | 'revision'>, messageId = randomUUID(), body: Uint8Array = randomBytes(100)) =>
      api.put(`/v1/communities/${state.id}/messages/${recipient.accountId}/${messageId}`, {
        token: sender.authToken, headers: { 'x-community-revision': String(state.revision) }, body,
      }),
    /** Creates a public community (or as `overrides` say) and checks the 201. */
    async created(owner: TestAccount, overrides: Partial<TestCommunityState> = {}): Promise<TestCommunityState> {
      const state = communityState(owner, overrides)
      const response = await self.create(owner, state)
      if (response.status !== 201) throw new Error(`create failed: ${response.status} ${JSON.stringify(response.body)}`)
      return state
    },
    /** Requests (public) for every addition, then publishes the next revision with them. */
    async approve(owner: TestAccount, state: TestCommunityState, additions: TestAccount[]): Promise<TestCommunityState> {
      for (let offset = 0; offset < additions.length; offset += 10) {
        const batch = await Promise.all(additions.slice(offset, offset + 10).map(person => self.request(person, state.id)))
        for (const response of batch) if (response.status !== 202) throw new Error(`request failed: ${response.status} ${JSON.stringify(response.body)}`)
      }
      const next = { ...state, revision: Number(state.revision) + 1, members: sortedMembers([...state.members, ...additions]) }
      const response = await self.update(owner, next)
      if (response.status !== 200) throw new Error(`update failed: ${response.status} ${JSON.stringify(response.body)}`)
      return next
    },
  }
  return self
}
