import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'

export function sha256(...parts: Uint8Array[]): Buffer {
  const hash = createHash('sha256')
  for (const part of parts) hash.update(part)
  return hash.digest()
}

export function random(length: number): Buffer {
  return randomBytes(length)
}

/**
 * Constant-time comparison for secrets and secret hashes. Only the length can
 * leak, and every value compared here has a fixed, public length.
 */
export function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && timingSafeEqual(a, b)
}
