import { randomBytes } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../../server/lib/errors'
import { accessKeyHashBody, envelopeIds, ephemeralBatch, keyUploadRequest, registrationRequest } from '../../server/lib/validation'
import { b64u, registrationBody } from '../support/client'

const pow = { token: 't', nonce: '1' }

function rejects(run: () => unknown): string {
  try {
    run()
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('bad_request')
    return (error as ApiError).message
  }
  throw new Error('expected a validation error')
}

describe('registrationRequest', () => {
  it('decodes a valid request and ignores unknown fields', () => {
    const body = { ...registrationBody(), pow, futureField: true }
    const parsed = registrationRequest(body)
    expect(parsed.identityKey).toHaveLength(33)
    expect(parsed.oneTimePreKeys).toHaveLength(5)
    expect(parsed.kyberOneTimePreKeys[0]!.signature).toHaveLength(64)
    expect(parsed.pow).toEqual(pow)
  })

  it('names the offending field', () => {
    expect(rejects(() => registrationRequest({ ...registrationBody({ registrationId: 1.5 }), pow }))).toContain('registrationId')
    expect(rejects(() => registrationRequest({ ...registrationBody(), pow, signedPreKey: { keyId: 1 } }))).toContain('signedPreKey.publicKey')
    expect(rejects(() => registrationRequest({ ...registrationBody(), pow, oneTimePreKeys: [{ keyId: 1, publicKey: 'AA' }] }))).toContain('oneTimePreKeys[0].publicKey')
    expect(rejects(() => registrationRequest([]))).toContain('body')
    expect(rejects(() => registrationRequest({ ...registrationBody() }))).toContain('pow')
  })
})

describe('other bodies', () => {
  it('validates key uploads', () => {
    expect(keyUploadRequest({})).toEqual({ signedPreKey: undefined, kyberLastResortKey: undefined, oneTimePreKeys: [], kyberOneTimePreKeys: [], replaceAll: false })
    rejects(() => keyUploadRequest({ replaceAll: 1 }))
  })

  it('validates access key hashes', () => {
    expect(accessKeyHashBody({ accessKeyHash: null })).toBeNull()
    expect(accessKeyHashBody({ accessKeyHash: b64u(randomBytes(32)) })).toHaveLength(32)
    rejects(() => accessKeyHashBody({}))
  })

  it('validates ack ids', () => {
    expect(envelopeIds([b64u(randomBytes(16))])).toHaveLength(1)
    rejects(() => envelopeIds([b64u(randomBytes(15))]))
    rejects(() => envelopeIds(Array.from({ length: 501 }, () => b64u(randomBytes(16)))))
  })

  it('validates ephemeral batches', () => {
    const to = '0f5b1d2c-8c55-4c39-9b7b-3a7f5e0c9d11'
    const item = (overrides: Record<string, unknown> = {}) => ({ to, accessKey: b64u(randomBytes(16)), envelope: b64u(randomBytes(100)), ...overrides })
    const [parsed] = ephemeralBatch({ messages: [item()] })
    expect(parsed!.recipient.toString('hex')).toBe(to.replace(/-/g, ''))
    expect(parsed!.accessKey).toHaveLength(16)
    expect(ephemeralBatch({ messages: Array.from({ length: 200 }, () => item()) })).toHaveLength(200)
    expect(ephemeralBatch({ messages: [item({ envelope: b64u(randomBytes(4096)) })] })).toHaveLength(1)

    expect(rejects(() => ephemeralBatch({ messages: [] }))).toContain('messages')
    rejects(() => ephemeralBatch({ messages: Array.from({ length: 201 }, () => item()) }))
    rejects(() => ephemeralBatch({}))
    expect(rejects(() => ephemeralBatch({ messages: [item({ to: 'nope' })] }))).toContain('messages[0].to')
    expect(rejects(() => ephemeralBatch({ messages: [item(), item({ accessKey: b64u(randomBytes(15)) })] }))).toContain('messages[1].accessKey')
    expect(rejects(() => ephemeralBatch({ messages: [item({ envelope: b64u(randomBytes(4097)) })] }))).toContain('messages[0].envelope')
    rejects(() => ephemeralBatch({ messages: [item({ envelope: '' })] }))
    rejects(() => ephemeralBatch({ messages: ['x'] }))
  })
})
