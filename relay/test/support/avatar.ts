import { randomBytes } from 'node:crypto'

/** The four lengths of an avatar record (PROTOCOL.md §4.12). */
export const AVATAR_SIZES = [8192, 16384, 32768, 49152] as const

export const VERSION_MAX = (1n << 63n) - 1n

/**
 * A record shaped as a client makes it: the 13-byte header, then random
 * bytes standing in for the nonce, the ciphertext and the tag, which the
 * relay never looks at. `magic` and `format` make malformed ones.
 */
export function avatarRecord(version: bigint | number, size = 8192, malformed: { magic?: string, format?: number } = {}): Buffer {
  const record = randomBytes(size)
  if (size < 13) return record
  record.write(malformed.magic ?? 'BRPA', 0, 4, 'latin1')
  record[4] = malformed.format ?? 0x01
  record.writeBigUInt64BE(BigInt(version), 5)
  return record
}
