import { createHmac } from 'node:crypto'
import { constantTimeEqual, random, sha256 } from './crypto'
import { encodeB64u } from './encoding'

/**
 * Stateless proof-of-work challenges (PROTOCOL.md §4.2).
 *
 * Token: `v1.<purpose>.<target|->.<expiresAtSeconds>.<random16 b64u>.<hmac b64u>`
 * where the HMAC-SHA256 (keyed with POW_SECRET) covers everything before the
 * last dot. Issuing costs one HMAC and no storage; verifying costs one HMAC
 * and one SHA-256. Single use is enforced by the caller, which records
 * SHA-256(token) until the token expires.
 */

export type PowPurpose = 'register' | 'message'

export interface PowChallenge {
  token: string
  difficulty: number
  expiresAt: number
}

export interface VerifiedPow {
  /** SHA-256 of the token, remembered until `expiresAt` to enforce single use. */
  tokenHash: Buffer
  expiresAt: number
}

const MAX_NONCE = (1n << 64n) - 1n
const TOKEN_MAX_LENGTH = 256

export class PowAuthority {
  constructor(
    private readonly secret: Buffer,
    private readonly ttlMs: number,
    private readonly difficulty: Record<PowPurpose, number>,
  ) {}

  issue(purpose: PowPurpose, target: string | null, now: number): PowChallenge {
    const expiresAtSeconds = Math.ceil((now + this.ttlMs) / 1000)
    const body = `v1.${purpose}.${target ?? '-'}.${expiresAtSeconds}.${encodeB64u(random(16))}`
    return {
      token: `${body}.${this.mac(body)}`,
      difficulty: this.difficulty[purpose],
      expiresAt: expiresAtSeconds * 1000,
    }
  }

  /**
   * Checks signature, purpose, target, expiry and work. Returns null for any
   * failure; callers map that to `pow_invalid` without saying which check
   * failed.
   */
  verify(token: string, nonce: string, purpose: PowPurpose, target: string | null, now: number): VerifiedPow | null {
    if (token.length > TOKEN_MAX_LENGTH) return null
    const parsedNonce = parseNonce(nonce)
    if (parsedNonce === null) return null

    const lastDot = token.lastIndexOf('.')
    const body = token.slice(0, lastDot)
    const parts = body.split('.')
    if (lastDot < 0 || parts.length !== 5) return null
    const [version, tokenPurpose, tokenTarget, expiresAtSeconds] = parts as [string, string, string, string, string]
    if (version !== 'v1' || tokenPurpose !== purpose || tokenTarget !== (target ?? '-')) return null

    const expected = Buffer.from(this.mac(body))
    if (!constantTimeEqual(Buffer.from(token.slice(lastDot + 1)), expected)) return null

    const expiresAt = Number(expiresAtSeconds) * 1000
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= now) return null
    if (leadingZeroBits(powDigest(token, parsedNonce)) < this.difficulty[purpose]) return null

    return { tokenHash: sha256(Buffer.from(token, 'utf8')), expiresAt }
  }

  private mac(body: string): string {
    return createHmac('sha256', this.secret).update(body, 'utf8').digest('base64url')
  }
}

/**
 * Parses an unsigned 64-bit nonce given as a decimal string (JSON big
 * integer, §2); leading zeros are accepted (§4.2).
 */
export function parseNonce(value: string): bigint | null {
  const digits = /^0*(\d{1,20})$/.exec(value)?.[1]
  if (digits === undefined) return null
  const nonce = BigInt(digits)
  return nonce <= MAX_NONCE ? nonce : null
}

/** SHA-256( UTF-8(token) ‖ uint64_big_endian(nonce) ). */
export function powDigest(token: string, nonce: bigint): Buffer {
  const nonceBytes = Buffer.alloc(8)
  nonceBytes.writeBigUInt64BE(nonce)
  return sha256(Buffer.from(token, 'utf8'), nonceBytes)
}

export function leadingZeroBits(bytes: Uint8Array): number {
  let bits = 0
  for (const byte of bytes) {
    if (byte === 0) {
      bits += 8
      continue
    }
    return bits + Math.clz32(byte) - 24
  }
  return bits
}

/** Splits an `X-PoW: <token>:<nonce>` header value. */
export function parsePowHeader(value: string): { token: string, nonce: string } | null {
  const separator = value.lastIndexOf(':')
  if (separator <= 0 || separator === value.length - 1) return null
  return { token: value.slice(0, separator), nonce: value.slice(separator + 1) }
}
