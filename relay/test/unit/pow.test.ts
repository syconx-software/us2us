import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createHmac } from 'node:crypto'
import { leadingZeroBits, parseNonce, parsePowHeader, powDigest, PowAuthority, RegistrationLoad } from '../../server/lib/pow'
import { solvePow } from '../support/client'

const authority = new PowAuthority(Buffer.from('unit-test-secret-unit-test-secret-1234'), 600_000, { register: 8, message: 6 })
const target = '0f5b1d2c-8c55-4c39-9b7b-3a7f5e0c9d11'
const now = 1_760_000_000_000

describe('PowAuthority', () => {
  it('issues tokens in the reference format with the configured difficulty', () => {
    const challenge = authority.issue('message', target, now)
    expect(challenge.difficulty).toBe(6)
    expect(challenge.expiresAt).toBe(now + 600_000)
    expect(challenge.token).toMatch(new RegExp(`^v2\\.message\\.${target}\\.${(now + 600_000) / 1000}\\.6\\.[\\w-]{22}\\.[\\w-]{43}$`))
  })

  it('carries a raised difficulty in the signed token, so it can be neither lowered nor changed under a client', () => {
    const challenge = authority.issue('register', null, now, 11)
    expect(challenge.difficulty).toBe(11)
    expect(challenge.token.split('.')[4]).toBe('11')
    // A solution for the configured 8 bits is not enough for this token ...
    let weak = 0n
    for (;;) {
      const bits = leadingZeroBits(powDigest(challenge.token, weak))
      if (bits >= 8 && bits < 11) break
      weak++
    }
    expect(authority.verify(challenge.token, weak.toString(), 'register', null, now)).toBeNull()
    expect(authority.verify(challenge.token, solvePow(challenge.token, 11), 'register', null, now)).not.toBeNull()
    // ... and lowering the field breaks the MAC.
    const lowered = challenge.token.replace('.11.', '.8.')
    expect(authority.verify(lowered, solvePow(lowered, 8), 'register', null, now)).toBeNull()
    // Never below the configured difficulty, never above the ceiling a phone can solve.
    expect(authority.issue('register', null, now, 2).difficulty).toBe(8)
    expect(authority.issue('register', null, now, 60).difficulty).toBe(28)
  })

  it('still accepts v1 tokens issued before the update, at the configured difficulty', () => {
    const secret = Buffer.from('unit-test-secret-unit-test-secret-1234')
    const body = `v1.message.${target}.${(now + 600_000) / 1000}.${Buffer.alloc(16, 7).toString('base64url')}`
    const token = `${body}.${createHmac('sha256', secret).update(body).digest('base64url')}`
    expect(authority.verify(token, solvePow(token, 6), 'message', target, now)).not.toBeNull()
    expect(authority.verify(token.replace(/^v1/, 'v2'), solvePow(token, 6), 'message', target, now)).toBeNull()
  })

  it('accepts a correct solution and returns the token hash for single use', () => {
    const { token } = authority.issue('register', null, now)
    const verified = authority.verify(token, solvePow(token, 8), 'register', null, now)
    expect(verified?.expiresAt).toBe(now + 600_000)
    expect(verified?.tokenHash).toHaveLength(32)
  })

  it('rejects wrong purpose, target, expiry, signature and work', () => {
    const { token } = authority.issue('message', target, now)
    const nonce = solvePow(token, 6)
    expect(authority.verify(token, nonce, 'message', target, now)).not.toBeNull()
    expect(authority.verify(token, nonce, 'register', null, now)).toBeNull()
    expect(authority.verify(token, nonce, 'message', '7e0a3f0e-6b0c-4d38-8a8b-5e7f1c2d3b4a', now)).toBeNull()
    expect(authority.verify(token, nonce, 'message', target, now + 600_000)).toBeNull()

    const other = new PowAuthority(Buffer.from('another-secret-another-secret-123456'), 600_000, { register: 8, message: 6 })
    expect(other.verify(token, nonce, 'message', target, now)).toBeNull()
    const forged = token.replace(/\.(\d+)\./, (_, s: string) => `.${Number(s) + 1000}.`)
    expect(authority.verify(forged, solvePow(forged, 6), 'message', target, now)).toBeNull()

    // A nonce that does not reach the difficulty.
    let weak = 0n
    while (leadingZeroBits(powDigest(token, weak)) >= 6) weak++
    expect(authority.verify(token, weak.toString(), 'message', target, now)).toBeNull()
    expect(authority.verify('garbage', '1', 'message', target, now)).toBeNull()
    expect(authority.verify(`${token}x`.repeat(30), '1', 'message', target, now)).toBeNull()
  })
})

describe('RegistrationLoad', () => {
  it('keeps the base difficulty under the threshold, then adds a bit per doubling up to the ceiling', () => {
    const load = new RegistrationLoad(22, 26, 100)
    const start = 1_760_000_000_000
    for (let i = 0; i < 99; i++) load.record(start + i * 1000)
    expect(load.difficulty(start + 100_000)).toBe(22)
    load.record(start + 100_000)
    expect(load.difficulty(start + 100_000)).toBe(23)
    for (let i = 0; i < 100; i++) load.record(start + 100_000)
    expect(load.difficulty(start + 100_000)).toBe(24)
    for (let i = 0; i < 10_000; i++) load.record(start + 200_000)
    expect(load.difficulty(start + 200_000)).toBe(26)
    // An hour later the flood is forgotten.
    expect(load.recent(start + 200_000 + 3_600_000)).toBe(0)
    expect(load.difficulty(start + 200_000 + 3_600_000)).toBe(22)
  })

  it('stays fixed when adaptation is off', () => {
    const load = new RegistrationLoad(22, 26, 0)
    for (let i = 0; i < 10_000; i++) load.record(1000)
    expect(load.difficulty(1000)).toBe(22)
  })
})

describe('nonces and digests', () => {
  it('parses unsigned 64-bit decimal nonces', () => {
    expect(parseNonce('0')).toBe(0n)
    expect(parseNonce('18446744073709551615')).toBe(18446744073709551615n)
    expect(parseNonce('18446744073709551616')).toBeNull()
    // Leading zeros are accepted (§4.2), however many there are.
    expect(parseNonce('007')).toBe(7n)
    expect(parseNonce('000')).toBe(0n)
    expect(parseNonce(`${'0'.repeat(30)}18446744073709551615`)).toBe(18446744073709551615n)
    expect(parseNonce(`${'0'.repeat(30)}18446744073709551616`)).toBeNull()
    for (const bad of ['', '-1', '+1', '1.5', '0x10', ' 1', '123456789012345678901']) expect(parseNonce(bad)).toBeNull()
  })

  it('hashes the token followed by the big-endian nonce', () => {
    const digest = powDigest('t', 258n)
    const expected = new Uint8Array([0x74, 0, 0, 0, 0, 0, 0, 1, 2])
    expect(digest).toEqual(createHash('sha256').update(expected).digest())
  })

  it('counts leading zero bits', () => {
    expect(leadingZeroBits(new Uint8Array([0x80]))).toBe(0)
    expect(leadingZeroBits(new Uint8Array([0x01]))).toBe(7)
    expect(leadingZeroBits(new Uint8Array([0x00, 0x00, 0x10]))).toBe(19)
    expect(leadingZeroBits(new Uint8Array(32))).toBe(256)
  })

  it('splits the X-PoW header at the last colon', () => {
    expect(parsePowHeader('v1.message.x.1.a.b:42')).toEqual({ token: 'v1.message.x.1.a.b', nonce: '42' })
    expect(parsePowHeader('no-colon')).toBeNull()
    expect(parsePowHeader(':1')).toBeNull()
    expect(parsePowHeader('token:')).toBeNull()
  })
})

/**
 * The client libraries generate PoW vectors (spec/test-vectors/README.md);
 * the relay's check must agree with every one of them.
 */
describe('cross-implementation vectors', () => {
  const root = fileURLToPath(new URL('../../../spec/test-vectors', import.meta.url))
  const files = ['kotlin', 'swift'].map(generator => `${root}/${generator}/pow.json`).filter(existsSync)

  it.skipIf(files.length === 0)('agree with spec/test-vectors/*/pow.json', () => {
    for (const file of files) {
      const { vectors } = JSON.parse(readFileSync(file, 'utf8')) as { vectors: Array<{ token: string, nonce: string, difficulty: number, valid: boolean }> }
      expect(vectors.length).toBeGreaterThan(0)
      for (const vector of vectors) {
        const valid = leadingZeroBits(powDigest(vector.token, parseNonce(vector.nonce)!)) >= vector.difficulty
        expect(valid, `${file}: ${JSON.stringify(vector)}`).toBe(vector.valid)
      }
    }
  })
})
