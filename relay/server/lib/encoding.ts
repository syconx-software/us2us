/**
 * Wire encodings from PROTOCOL.md §2: base64url without padding for binary
 * values and lowercase hyphenated UUIDs for account ids.
 */

const B64U = /^[A-Za-z0-9_-]*$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function encodeB64u(bytes: Uint8Array): string {
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString('base64url')
}

/**
 * Strict base64url decoding: no padding, no foreign characters and only the
 * canonical encoding (unused trailing bits must be zero). Node's decoder is
 * lenient and would silently accept all of those, which would let two
 * different strings name the same id.
 */
export function decodeB64u(value: unknown): Buffer | null {
  if (typeof value !== 'string' || !B64U.test(value) || value.length % 4 === 1) return null
  const bytes = Buffer.from(value, 'base64url')
  return bytes.toString('base64url') === value ? bytes : null
}

/** Decodes base64url and requires an exact byte length. */
export function decodeB64uExact(value: unknown, length: number): Buffer | null {
  const bytes = decodeB64u(value)
  return bytes !== null && bytes.length === length ? bytes : null
}

/** Parses a UUID string (any case) into its 16 raw bytes. */
export function parseUuid(value: unknown): Buffer | null {
  if (typeof value !== 'string' || !UUID.test(value)) return null
  return Buffer.from(value.replace(/-/g, ''), 'hex')
}

/** Formats 16 raw bytes as a lowercase hyphenated UUID. */
export function formatUuid(bytes: Uint8Array): string {
  const hex = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** UTC calendar date `YYYY-MM-DD`, the granularity of the stored last-seen date. */
export function utcDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}
