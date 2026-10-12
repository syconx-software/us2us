import { describe, expect, it } from 'vitest'
import {
  AVATAR_RECORD_MAX_BYTES,
  AVATAR_VERSION_MAX,
  avatarETag,
  avatarRecordVersion,
  ifNoneMatchNames,
  isAvatarRecordSize,
  parseAvatarVersion,
} from '../../server/lib/avatar'
import { AVATAR_SIZES, VERSION_MAX, avatarRecord } from '../support/avatar'

describe('avatar record sizes (§4.12)', () => {
  it('accepts exactly 8, 16, 32 and 48 KiB', () => {
    for (const size of AVATAR_SIZES) expect(isAvatarRecordSize(size), String(size)).toBe(true)
    for (const size of [0, 13, 8191, 8193, 16383, 16385, 32767, 32769, 49151, 49153, 65536]) {
      expect(isAvatarRecordSize(size), String(size)).toBe(false)
    }
    expect(AVATAR_RECORD_MAX_BYTES).toBe(49152)
    expect(AVATAR_VERSION_MAX).toBe(VERSION_MAX)
  })
})

describe('avatarRecordVersion', () => {
  it('reads the version from a well-formed record of every size', () => {
    for (const size of AVATAR_SIZES) expect(avatarRecordVersion(avatarRecord(7, size))).toBe(7n)
    expect(avatarRecordVersion(avatarRecord(1))).toBe(1n)
    expect(avatarRecordVersion(avatarRecord(VERSION_MAX))).toBe(VERSION_MAX)
    expect(avatarRecordVersion(avatarRecord(2n ** 53n + 1n))).toBe(2n ** 53n + 1n)
  })

  it('refuses another length, magic or format byte, and versions outside 1..2^63-1', () => {
    expect(avatarRecordVersion(avatarRecord(1, 8191))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 8193))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 12))).toBeNull()
    expect(avatarRecordVersion(Buffer.alloc(0))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 8192, { magic: 'BRPX' }))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 8192, { magic: 'brpa' }))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 8192, { format: 0x00 }))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(1, 8192, { format: 0x02 }))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(0))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(2n ** 63n))).toBeNull()
    expect(avatarRecordVersion(avatarRecord(2n ** 64n - 1n))).toBeNull()
  })

  it('reads a record that is a view into a larger buffer', () => {
    const backing = Buffer.concat([Buffer.alloc(5, 0xff), avatarRecord(42), Buffer.alloc(3)])
    expect(avatarRecordVersion(new Uint8Array(backing.buffer, backing.byteOffset + 5, 8192))).toBe(42n)
  })
})

describe('parseAvatarVersion (X-Avatar-Version)', () => {
  it('accepts decimals in 1..2^63-1, also with leading zeros', () => {
    expect(parseAvatarVersion('1')).toBe(1n)
    expect(parseAvatarVersion('0042')).toBe(42n)
    expect(parseAvatarVersion('9223372036854775807')).toBe(VERSION_MAX)
    expect(parseAvatarVersion('000000000009223372036854775807')).toBe(VERSION_MAX)
  })

  it('refuses everything else', () => {
    for (const value of [undefined, null, 5, '', '0', '000', '-1', '+1', '1.0', '1e3', ' 1', '1 ', '0x10', 'abc', '1, 2',
      '9223372036854775808', '18446744073709551615', '99999999999999999999', '1'.repeat(1000)]) {
      expect(parseAvatarVersion(value), String(value)).toBeNull()
    }
  })
})

describe('ETag and If-None-Match', () => {
  it('quotes the decimal version', () => {
    expect(avatarETag(1n)).toBe('"1"')
    expect(avatarETag(VERSION_MAX)).toBe('"9223372036854775807"')
  })

  it('matches only a tag that names the stored version', () => {
    expect(ifNoneMatchNames('"5"', 5n)).toBe(true)
    expect(ifNoneMatchNames('W/"5"', 5n)).toBe(true)
    expect(ifNoneMatchNames('"4", "5"', 5n)).toBe(true)
    expect(ifNoneMatchNames('"4",W/"5"', 5n)).toBe(true)

    expect(ifNoneMatchNames(undefined, 5n)).toBe(false)
    expect(ifNoneMatchNames('', 5n)).toBe(false)
    expect(ifNoneMatchNames('"4"', 5n)).toBe(false)
    expect(ifNoneMatchNames('"05"', 5n)).toBe(false)
    expect(ifNoneMatchNames('5', 5n)).toBe(false)
    expect(ifNoneMatchNames('"55"', 5n)).toBe(false)
    // `*` is not a version; it never turns a fetch into a 304.
    expect(ifNoneMatchNames('*', 5n)).toBe(false)
  })
})
