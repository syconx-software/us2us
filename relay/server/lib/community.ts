import { decodeB64u, decodeB64uExact, encodeB64u, formatUuid } from './encoding'
import { ApiError, badRequest } from './errors'
import { verifyIdentitySignature } from './xeddsa'

export interface CommunityMember { accountId: Buffer, identityKey: Buffer }
export interface CommunityState {
  id: Buffer
  revision: number
  visibility: number
  owner: Buffer
  ownerIdentityKey: Buffer
  name: string
  members: CommunityMember[]
  bytes: Buffer
}
export const COMMUNITY_DOMAIN = Buffer.from('BRP/CommunityState/v1\0', 'ascii')
export const COMMUNITY_TTL = 14 * 24 * 3600 * 1000
export const COMMUNITY_MAX_MEMBERS = 100
export const COMMUNITY_NAME_MAX_BYTES = 120

/** 409 for a roster or admission past COMMUNITY_MAX_MEMBERS (COMMUNITIES.md). */
export const communityFull = () => new ApiError(409, 'community_full', 'community is full')

/** White_Space code points that are not controls; the only ones trimmed from a name. */
const NAME_SPACE = '\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000'
const NAME_EDGES = new RegExp(`^[${NAME_SPACE}]+|[${NAME_SPACE}]+$`, 'gu')
/** C0/C1 controls, and bidi marks, embeddings, overrides and isolates. */
const NAME_FORBIDDEN = /[\u0000-\u001f\u007f-\u009f؜‎‏‪-‮⁦-⁩]/u

/**
 * The community name rule (COMMUNITIES.md, spec/test-vectors/communities-names.json):
 * the trimmed name, or null if it is empty, longer than 120 UTF-8 bytes or
 * contains a forbidden code point. A lone surrogate never survives the
 * UTF-8 round trip of a decoded state.
 */
export function canonicalCommunityName(input: string): string | null {
  const name = input.replace(NAME_EDGES, '')
  const bytes = Buffer.byteLength(name, 'utf8')
  return bytes >= 1 && bytes <= COMMUNITY_NAME_MAX_BYTES && !NAME_FORBIDDEN.test(name) ? name : null
}

/** A search prefix: the name rule, except that it may be empty. */
export function communitySearchPrefix(input: string): string | null {
  const prefix = input.replace(NAME_EDGES, '')
  return prefix === '' ? '' : canonicalCommunityName(prefix)
}

/** A canonical encoding parser; `verifyCommunitySignature` checks the owner's signature. */
export function parseCommunityState(bytes: Buffer): CommunityState {
  if (bytes.length > 8192) throw badRequest('community state is too large')
  let offset = 0
  const take = (n: number) => {
    if (offset + n > bytes.length) throw badRequest('truncated community state')
    const value = bytes.subarray(offset, offset + n); offset += n; return value
  }
  if (!take(COMMUNITY_DOMAIN.length).equals(COMMUNITY_DOMAIN)) throw badRequest('invalid community state domain')
  const id = take(16)
  const revision = Number(take(8).readBigUInt64BE())
  if (!Number.isSafeInteger(revision) || revision < 1) throw badRequest('invalid community revision')
  const visibility = take(1)[0]!
  if (visibility !== 0 && visibility !== 1) throw badRequest('invalid community visibility')
  const owner = take(16), ownerIdentityKey = take(33)
  const nameBytes = take(take(2).readUInt16BE())
  const name = nameBytes.toString('utf8')
  if (!Buffer.from(name).equals(nameBytes)
      || (visibility === 0 ? nameBytes.length !== 0 : canonicalCommunityName(name) !== name)) throw badRequest('invalid community name')
  const count = take(2).readUInt16BE()
  if (count < 1) throw badRequest('community must have at least one member')
  const members: CommunityMember[] = []
  for (let i = 0; i < count; i++) {
    const accountId = take(16), identityKey = take(33)
    if (identityKey[0] !== 5 || (i > 0 && Buffer.compare(members[i - 1]!.accountId, accountId) >= 0)) throw badRequest('invalid or duplicate community member')
    members.push({ accountId, identityKey })
  }
  if (offset !== bytes.length || ownerIdentityKey[0] !== 5
      || !members.some(m => m.accountId.equals(owner) && m.identityKey.equals(ownerIdentityKey))) throw badRequest('invalid community owner or trailing data')
  // Well formed, but over the limit: the same answer as a full admission.
  if (count > COMMUNITY_MAX_MEMBERS) throw communityFull()
  return { id, revision, visibility, owner, ownerIdentityKey, name, members, bytes }
}

/** The owner's libsignal identity signature over the exact state bytes. */
export function verifyCommunitySignature(state: CommunityState, signature: Buffer): void {
  if (!verifyIdentitySignature(state.ownerIdentityKey, state.bytes, signature)) throw badRequest('invalid community state signature')
}

export function communityWrite(value: unknown): { state: CommunityState, signature: Buffer } {
  const object = jsonObject(value)
  const bytes = typeof object.state === 'string' ? decodeB64u(object.state) : null
  const signature = decodeB64uExact(object.signature, 64)
  if (!bytes || !signature || Object.keys(object).some(k => k !== 'state' && k !== 'signature')) throw badRequest('expected state and signature')
  return { state: parseCommunityState(bytes), signature }
}

export function jsonObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw badRequest('expected JSON object')
  return value as Record<string, unknown>
}

export function stateResponse(row: { state: Buffer, signature: Buffer }) {
  return { state: encodeB64u(row.state), signature: encodeB64u(row.signature) }
}
export function communitySummary(state: CommunityState) {
  return { id: formatUuid(state.id), name: state.name, visibility: state.visibility === 1 ? 'public' : 'private',
    ownerAccountId: formatUuid(state.owner), ownerIdentityKey: encodeB64u(state.ownerIdentityKey), memberCount: state.members.length, revision: state.revision }
}
