/** An inclusive byte range of a resource. */
export interface ByteRange {
  start: number
  end: number
}

/**
 * Interprets a `Range` header for a resource of `size` bytes (RFC 9110
 * §14.1.2). Returns null when the whole resource should be sent: no header,
 * a unit other than bytes, a malformed value, or several ranges (which a
 * server may ignore; clients resume downloads with a single range).
 */
export function parseRange(header: string | undefined, size: number): ByteRange | 'unsatisfiable' | null {
  const match = header === undefined ? null : /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!match) return null
  const [, first = '', last = ''] = match
  if (first === '' && last === '') return null

  if (first === '') {
    // Suffix range: the last N bytes.
    const length = Number(last)
    if (length === 0) return 'unsatisfiable'
    return { start: Math.max(0, size - length), end: size - 1 }
  }
  const start = Number(first)
  if (start >= size) return 'unsatisfiable'
  const end = last === '' ? size - 1 : Number(last)
  // A last position before the first one makes the header invalid, hence ignored.
  if (end < start) return null
  return { start, end: Math.min(end, size - 1) }
}
