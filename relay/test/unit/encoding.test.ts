import { describe, expect, it } from 'vitest'
import { decodeB64u, decodeB64uExact, encodeB64u, formatUuid, parseUuid, utcDate } from '../../server/lib/encoding'

describe('base64url', () => {
  it('round-trips without padding', () => {
    for (let length = 0; length < 40; length++) {
      const bytes = Buffer.from(Array.from({ length }, (_, i) => (i * 37 + length) & 0xff))
      const encoded = encodeB64u(bytes)
      expect(encoded).not.toContain('=')
      expect(decodeB64u(encoded)).toEqual(bytes)
    }
  })

  it('encodes views of larger buffers correctly', () => {
    const backing = Buffer.from([1, 2, 3, 4, 5, 6])
    expect(encodeB64u(backing.subarray(2, 4))).toBe(Buffer.from([3, 4]).toString('base64url'))
  })

  it('rejects padding, foreign characters and non-canonical encodings', () => {
    for (const bad of ['AQ==', 'A+B/', 'AQ!', 'A', 'AR', ' AQ', 42, null, undefined]) expect(decodeB64u(bad)).toBeNull()
    expect(decodeB64u('AQ')).toEqual(Buffer.from([1]))
  })

  it('checks exact lengths', () => {
    expect(decodeB64uExact(encodeB64u(Buffer.alloc(16)), 16)).toHaveLength(16)
    expect(decodeB64uExact(encodeB64u(Buffer.alloc(15)), 16)).toBeNull()
  })
})

describe('uuids', () => {
  it('parses any case and formats lowercase', () => {
    const id = 'E621E1F8-C36C-495A-93FC-0C247A3E6E5F'
    const bytes = parseUuid(id)!
    expect(bytes).toHaveLength(16)
    expect(formatUuid(bytes)).toBe(id.toLowerCase())
    for (const bad of ['e621e1f8c36c495a93fc0c247a3e6e5f', 'e621e1f8-c36c-495a-93fc-0c247a3e6e5', 'zzzzzzzz-c36c-495a-93fc-0c247a3e6e5f', 5]) {
      expect(parseUuid(bad)).toBeNull()
    }
  })
})

it('formats UTC dates', () => {
  expect(utcDate(Date.UTC(2026, 9, 7, 23, 59, 59))).toBe('2026-10-07')
})
