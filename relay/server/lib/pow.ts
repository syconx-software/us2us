import { createHmac } from 'node:crypto'
import { constantTimeEqual, random, sha256 } from './crypto'
import { encodeB64u } from './encoding'

/**
 * Stateless proof-of-work challenges (PROTOCOL.md §4.2).
 *
 * Token: `v2.<purpose>.<target|->.<expiresAtSeconds>.<difficulty>.<random16 b64u>.<hmac b64u>`
 * where the HMAC-SHA256 (keyed with POW_SECRET) covers everything before the
 * last dot. The difficulty a challenge was issued with travels in it, so a
 * difficulty that changes between issue and redemption (adaptive
 * registration, a new setting) never fails a solution that was correct for
 * its challenge, and the MAC keeps a client from lowering it. `v1` tokens
 * (no difficulty field, issued before 2026-10-28) are still accepted at the
 * configured difficulty until they expire. Issuing costs one HMAC and no
 * storage; verifying costs one HMAC and one SHA-256. Single use is enforced
 * by the caller, which records SHA-256(token) until the token expires.
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
/** Ceiling for a raised difficulty (never below the configured one): beyond it a phone takes minutes. */
const MAX_DIFFICULTY = 28
const TOKEN_MAX_LENGTH = 256

export class PowAuthority {
  constructor(
    private readonly secret: Buffer,
    private readonly ttlMs: number,
    private readonly difficulty: Record<PowPurpose, number>,
  ) {}

  /** `difficulty` defaults to the configured one; it may only be higher. */
  issue(purpose: PowPurpose, target: string | null, now: number, difficulty = this.difficulty[purpose]): PowChallenge {
    const bits = Math.max(this.difficulty[purpose], Math.min(MAX_DIFFICULTY, Math.floor(difficulty)))
    const expiresAtSeconds = Math.ceil((now + this.ttlMs) / 1000)
    const body = `v2.${purpose}.${target ?? '-'}.${expiresAtSeconds}.${bits}.${encodeB64u(random(16))}`
    return {
      token: `${body}.${this.mac(body)}`,
      difficulty: bits,
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
    if (lastDot < 0) return null
    const version = parts[0]
    if (!((version === 'v1' && parts.length === 5) || (version === 'v2' && parts.length === 6))) return null
    const [, tokenPurpose, tokenTarget, expiresAtSeconds] = parts as [string, string, string, string]
    if (tokenPurpose !== purpose || tokenTarget !== (target ?? '-')) return null

    const expected = Buffer.from(this.mac(body))
    if (!constantTimeEqual(Buffer.from(token.slice(lastDot + 1)), expected)) return null

    const expiresAt = Number(expiresAtSeconds) * 1000
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= now) return null
    // Signed by us, so only the configured minimum is enforced on top of it.
    const issued = version === 'v2' ? Number(parts[4]) : this.difficulty[purpose]
    if (!Number.isSafeInteger(issued)) return null
    const required = Math.max(issued, this.difficulty[purpose])
    if (leadingZeroBits(powDigest(token, parsedNonce)) < required) return null

    return { tokenHash: sha256(Buffer.from(token, 'utf8')), expiresAt }
  }

  private mac(body: string): string {
    return createHmac('sha256', this.secret).update(body, 'utf8').digest('base64url')
  }
}

const SLOT_MS = 60_000
const SLOTS = 60

/**
 * How many registrations the relay saw in the last hour (in one-minute
 * slots, memory only), and the registration difficulty that follows from
 * it: the configured one while registrations stay under `perHour`, then
 * one bit more each time the rate doubles, up to `max`. Normal sign-up
 * keeps the base cost; a sybil flood pays exponentially more per account,
 * and so does everyone else until it ends.
 */
export class RegistrationLoad {
  private readonly slots = new Array<number>(SLOTS).fill(0)
  private readonly slotStart = new Array<number>(SLOTS).fill(0)

  constructor(
    private readonly base: number,
    private readonly max: number,
    private readonly perHour: number,
  ) {}

  record(now = Date.now()): void {
    const start = now - (now % SLOT_MS)
    const slot = Math.floor(now / SLOT_MS) % SLOTS
    if (this.slotStart[slot] !== start) {
      this.slotStart[slot] = start
      this.slots[slot] = 0
    }
    this.slots[slot]!++
  }

  recent(now = Date.now()): number {
    let total = 0
    for (let slot = 0; slot < SLOTS; slot++) {
      if (now - this.slotStart[slot]! < SLOTS * SLOT_MS) total += this.slots[slot]!
    }
    return total
  }

  difficulty(now = Date.now()): number {
    if (this.perHour === 0) return this.base
    const recent = this.recent(now)
    if (recent < this.perHour) return this.base
    return Math.min(this.max, this.base + 1 + Math.floor(Math.log2(recent / this.perHour)))
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
