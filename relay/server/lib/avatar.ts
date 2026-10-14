/**
 * Avatar records for search (PROTOCOL.md §4.12, §8.10). The relay reads only
 * a record's length and its 13-byte header; everything after the header is
 * a nonce and AES-GCM ciphertext under a key derived from the username,
 * which the relay never has.
 *
 *   header = "BRPA" ‖ 0x01 ‖ uint64_be(version)
 */

/** The only lengths a record may have: 8, 16, 32 and 48 KiB. */
export const AVATAR_RECORD_SIZES: readonly number[] = [8192, 16384, 32768, 49152]
export const AVATAR_RECORD_MAX_BYTES = 49152
export const AVATAR_HEADER_BYTES = 13
/** Versions are 1 .. 2^63 − 1, chosen by the client. */
export const AVATAR_VERSION_MAX = (1n << 63n) - 1n

const MAGIC = Buffer.from('BRPA', 'ascii')
const FORMAT = 0x01
/** A decimal with any number of leading zeros and at most 19 significant digits. */
const DECIMAL = /^0*([1-9][0-9]{0,18})$/

export function isAvatarRecordSize(length: number): boolean {
  return AVATAR_RECORD_SIZES.includes(length)
}

export function isAvatarVersion(version: bigint): boolean {
  return version >= 1n && version <= AVATAR_VERSION_MAX
}

/**
 * The version in the header of a record the relay accepts; null for any
 * other length, another magic or format byte, or a version of 0 or ≥ 2^63.
 */
export function avatarRecordVersion(record: Uint8Array): bigint | null {
  if (!isAvatarRecordSize(record.length)) return null
  const bytes = Buffer.from(record.buffer, record.byteOffset, record.byteLength)
  if (!bytes.subarray(0, MAGIC.length).equals(MAGIC) || bytes[MAGIC.length] !== FORMAT) return null
  const version = bytes.readBigUInt64BE(MAGIC.length + 1)
  return isAvatarVersion(version) ? version : null
}

/** The `X-Avatar-Version` request header: a decimal in 1 .. 2^63 − 1, else null. */
export function parseAvatarVersion(value: unknown): bigint | null {
  if (typeof value !== 'string') return null
  const match = DECIMAL.exec(value)
  if (!match) return null
  const version = BigInt(match[1]!)
  return isAvatarVersion(version) ? version : null
}

/** The entity tag of a record: its version as a decimal in double quotes. */
export function avatarETag(version: bigint): string {
  return `"${version}"`
}

/**
 * Whether an `If-None-Match` header names the stored version (§4.12). Each
 * member of a list is compared, weakly as RFC 9110 §13.1.2 asks; `*` and
 * every other value are ignored, so they never turn a fetch into a `304`.
 */
export function ifNoneMatchNames(header: string | undefined, version: bigint): boolean {
  if (!header) return false
  const tag = avatarETag(version)
  return header.split(',').some((member) => {
    const value = member.trim()
    return value === tag || value === `W/${tag}`
  })
}
