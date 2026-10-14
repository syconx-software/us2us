import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { canonicalCommunityName, communitySearchPrefix, communitySummary, communityWrite, parseCommunityState, verifyCommunitySignature } from '../../server/lib/community'
import { ApiError } from '../../server/lib/errors'
import { verifyIdentitySignature } from '../../server/lib/xeddsa'
import { b64u, ecKey } from '../support/client'
import { communityBody, communityState, encodeCommunity, sortedMembers } from '../support/community'
import { identityKey, signWithIdentity } from '../support/identity'

/** Shared with the Swift and Kotlin implementations (spec/test-vectors). */
const vectors = (name: string) => JSON.parse(readFileSync(new URL(`../../../spec/test-vectors/${name}`, import.meta.url), 'utf8'))

const owner = { accountId: '00000000-0000-0000-0000-000000000001', identityKey: b64u(Buffer.concat([Buffer.from([5]), Buffer.alloc(32, 7)])) }
const second = { accountId: '00000000-0000-0000-0000-000000000002', identityKey: b64u(ecKey()) }

function rejects(run: () => unknown, code = 'bad_request') {
  expect(run).toThrow(ApiError)
  try { run() } catch (error) { expect((error as ApiError).code).toBe(code) }
}

describe('community canonical state', () => {
  it('decodes the independent big-endian encoding and preserves exact signed bytes', () => {
    const input = communityState(owner, { id: '01020304-0506-0708-090a-0b0c0d0e0f10', revision: 0x01020304, name: 'İstanbul', members: [owner, second] })
    const bytes = encodeCommunity(input)
    const parsed = parseCommunityState(bytes)
    expect(parsed.bytes).toEqual(bytes)
    expect(parsed.revision).toBe(0x01020304)
    expect(parsed.members.map(member => member.accountId.toString('hex'))).toEqual([
      '00000000000000000000000000000001', '00000000000000000000000000000002',
    ])
    expect(communitySummary(parsed)).toEqual({ id: input.id, name: 'İstanbul', visibility: 'public',
      ownerAccountId: owner.accountId, ownerIdentityKey: owner.identityKey, memberCount: 2, revision: 0x01020304 })
  })

  it('accepts exactly 100 members and safe revision boundaries', () => {
    const members = Array.from({ length: 100 }, (_, index) => ({ ...owner,
      accountId: `00000000-0000-0000-0000-${(index + 1).toString(16).padStart(12, '0')}` }))
    const state = communityState(owner, { members: sortedMembers(members), revision: Number.MAX_SAFE_INTEGER })
    expect(parseCommunityState(encodeCommunity(state)).members).toHaveLength(100)
    // A well-formed roster over the limit is the same 409 as a full admission.
    rejects(() => parseCommunityState(encodeCommunity({ ...state, members: [...members, { ...second, accountId: '00000000-0000-0000-0000-000000000fff' }] })), 'community_full')
    rejects(() => parseCommunityState(encodeCommunity({ ...state, revision: 0 })))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, revision: BigInt(Number.MAX_SAFE_INTEGER) + 1n })))
  })

  it('rejects truncated, ambiguous, unsorted and owner-substituted states', () => {
    const state = communityState(owner)
    const valid = encodeCommunity(state)
    for (let length = 0; length < valid.length; length++) rejects(() => parseCommunityState(valid.subarray(0, length)))
    rejects(() => parseCommunityState(Buffer.concat([valid, Buffer.from([0])])))
    rejects(() => parseCommunityState(Buffer.alloc(8193)))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, members: [owner, owner] })))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, members: [second, owner] })))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, members: [second] })))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, members: [{ ...owner, identityKey: b64u(ecKey()) }] })))
    rejects(() => parseCommunityState(encodeCommunity({ ...state, owner: { ...owner, identityKey: b64u(Buffer.alloc(33)) } })))
    const badDomain = Buffer.from(valid); badDomain[0] = 0
    rejects(() => parseCommunityState(badDomain))
  })

  it('keeps private names off the relay and bounds public UTF-8 names', () => {
    expect(parseCommunityState(encodeCommunity(communityState(owner, { visibility: 0, name: '' }))).name).toBe('')
    for (const name of ['', ' leading', 'trailing ', 'line\nbreak', 'zero\0byte', 'x'.repeat(121), 'ş'.repeat(61)]) {
      rejects(() => parseCommunityState(encodeCommunity(communityState(owner, { name }))))
    }
    expect(parseCommunityState(encodeCommunity(communityState(owner, { name: 'ş'.repeat(60) }))).name).toBe('ş'.repeat(60))
    rejects(() => parseCommunityState(encodeCommunity(communityState(owner, { visibility: 0, name: 'secret' }))))
    const invalidUtf8 = encodeCommunity(communityState(owner, { name: 'x' }))
    invalidUtf8[Buffer.from('BRP/CommunityState/v1\0').length + 16 + 8 + 1 + 16 + 33 + 2] = 0xff
    rejects(() => parseCommunityState(invalidUtf8))
  })

  it('rejects noncanonical base64, malformed signatures and extra write fields', () => {
    const body = communityBody(communityState(owner))
    expect(communityWrite(body).signature).toHaveLength(64)
    for (const input of [null, [], {}, { ...body, state: `${body.state}=` }, { ...body, signature: b64u(randomBytes(63)) },
      { ...body, signature: `${body.signature}=` }, { ...body, owner: owner.accountId }]) rejects(() => communityWrite(input))
  })

  it('applies the shared name rule (spec/test-vectors/communities-names.json)', () => {
    const file = vectors('communities-names.json')
    expect(file.vectors.length).toBeGreaterThanOrEqual(30)
    for (const vector of file.vectors as Array<{ description: string, name: string, valid: boolean, canonical: string | null }>) {
      expect(canonicalCommunityName(vector.name), vector.description).toBe(vector.canonical)
      expect(vector.valid, vector.description).toBe(vector.canonical !== null)
      if (!vector.valid) continue
      // A state carries the canonical name, and only that.
      expect(parseCommunityState(encodeCommunity(communityState(owner, { name: vector.canonical! }))).name).toBe(vector.canonical)
      if (vector.name !== vector.canonical) rejects(() => parseCommunityState(encodeCommunity(communityState(owner, { name: vector.name }))))
    }
    for (const vector of file.vectors.filter((v: { valid: boolean, name: string }) => !v.valid && v.name !== '')) {
      rejects(() => parseCommunityState(encodeCommunity(communityState(owner, { name: vector.name }))))
    }
    expect(communitySearchPrefix('  ')).toBe('')
    expect(communitySearchPrefix(' Kitap ')).toBe('Kitap')
    expect(communitySearchPrefix('Kitap\u202e')).toBeNull()
  })

  it('decodes the shared state vectors (spec/test-vectors/communities-invalid.json)', () => {
    const file = vectors('communities-invalid.json')
    const outcomes = (file.vectors as Array<{ reason: string, stateHex: string, valid: boolean }>).map((vector) => {
      try {
        parseCommunityState(Buffer.from(vector.stateHex, 'hex'))
        return { reason: vector.reason, valid: true }
      } catch (error) {
        expect(error, vector.reason).toBeInstanceOf(ApiError)
        expect((error as ApiError).code, vector.reason).toBe(vector.reason.includes('101 members') ? 'community_full' : 'bad_request')
        return { reason: vector.reason, valid: false }
      }
    })
    expect(outcomes).toEqual(file.vectors.map((vector: { reason: string, valid: boolean }) => ({ reason: vector.reason, valid: vector.valid })))
    expect(outcomes.filter(outcome => outcome.valid)).toHaveLength(2)
  })
})

describe('owner signature', () => {
  it('verifies a real libsignal identity signature (spec/test-vectors/communities.json)', () => {
    const vector = vectors('communities.json')
    const state = parseCommunityState(Buffer.from(vector.stateHex, 'hex'))
    const signature = Buffer.from(vector.signature, 'base64url')
    expect(state.ownerIdentityKey).toEqual(Buffer.from(vector.ownerIdentityKey, 'base64url'))
    expect(() => verifyCommunitySignature(state, signature)).not.toThrow()
    for (const index of [0, 31, 32, 62, 63]) {
      const flipped = Buffer.from(signature)
      flipped[index]! ^= index === 63 ? 0x80 : 0x01
      rejects(() => verifyCommunitySignature(state, flipped))
    }
    const tampered = Buffer.from(state.bytes)
    tampered[tampered.length - 1]! ^= 1
    expect(verifyIdentitySignature(state.ownerIdentityKey, tampered, signature)).toBe(false)
    expect(verifyIdentitySignature(Buffer.from(vector.stateHex.slice(0, 66), 'hex'), state.bytes, signature)).toBe(false)
  })

  it('accepts only the owner identity key, and refuses malformed keys and signatures', () => {
    const key = identityKey(), other = identityKey(), message = randomBytes(300)
    const signature = signWithIdentity(key, message)!
    expect(verifyIdentitySignature(key, message, signature)).toBe(true)
    expect(verifyIdentitySignature(other, message, signature)).toBe(false)
    expect(verifyIdentitySignature(key.subarray(1), message, signature)).toBe(false)
    expect(verifyIdentitySignature(Buffer.concat([Buffer.from([6]), key.subarray(1)]), message, signature)).toBe(false)
    expect(verifyIdentitySignature(key, message, signature.subarray(1))).toBe(false)
    const high = Buffer.from(signature); high[63]! |= 0x60
    expect(verifyIdentitySignature(key, message, high)).toBe(false)
    // u = p - 1 has no Edwards counterpart.
    const minusOne = Buffer.alloc(33, 0xff); minusOne[0] = 5; minusOne[1] = 0xec; minusOne[32] = 0x7f
    expect(verifyIdentitySignature(minusOne, message, signature)).toBe(false)
  })
})

