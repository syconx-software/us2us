import { describe, expect, it } from 'vitest'
import { EnvelopeIdGenerator, isEphemeralId } from '../../server/lib/envelope-id'

describe('EnvelopeIdGenerator', () => {
  it('produces 16-byte ids whose byte order is arrival order', () => {
    const generator = new EnvelopeIdGenerator()
    const ids = [generator.next(1000), generator.next(1000), generator.next(1001), generator.next(1001), generator.next(5000)]
    for (const id of ids) expect(id).toHaveLength(16)
    for (let i = 1; i < ids.length; i++) expect(Buffer.compare(ids[i - 1]!, ids[i]!)).toBe(-1)
    expect(ids[0]!.readUIntBE(0, 6)).toBe(1000)
    expect(ids[1]!.readUInt16BE(6)).toBe(1)
  })

  it('stays monotonic when the clock goes backwards or a millisecond overflows', () => {
    const generator = new EnvelopeIdGenerator()
    let previous = generator.next(10_000)
    for (const now of [9_000, 9_500, 10_000]) {
      const id = generator.next(now)
      expect(Buffer.compare(previous, id)).toBe(-1)
      previous = id
    }
    for (let i = 0; i < 70_000; i++) {
      const id = generator.next(20_000)
      expect(Buffer.compare(previous, id)).toBe(-1)
      previous = id
    }
    expect(previous.readUIntBE(0, 6)).toBe(20_001)
  })

  it('marks ephemeral ids and keeps the stored ones in order around them', () => {
    const generator = new EnvelopeIdGenerator()
    const ids = Array.from({ length: 200 }, (_, i) => generator.next(1000 + (i >> 2), i % 2 === 0))
    ids.forEach((id, i) => expect(isEphemeralId(id), String(i)).toBe(i % 2 === 0))
    const stored = ids.filter((_, i) => i % 2 === 1)
    for (let i = 1; i < stored.length; i++) expect(Buffer.compare(stored[i - 1]!, stored[i]!)).toBe(-1)
    expect(isEphemeralId(Buffer.alloc(15, 0xff))).toBe(false)
  })

  it('gives ephemeral ids no time or counter, so a batch does not number its recipients', () => {
    const generator = new EnvelopeIdGenerator()
    const now = 1_760_000_000_000
    const batch = Array.from({ length: 50 }, () => generator.next(now, true))
    expect(new Set(batch.map(id => id.toString('hex'))).size).toBe(50)
    expect(batch.filter(id => id.readUIntBE(0, 6) === now).length).toBeLessThan(2)
    const counters = batch.map(id => id.readUInt16BE(6))
    expect(counters.filter((counter, i) => i > 0 && counter === counters[i - 1]! + 1).length).toBeLessThan(5)
    // Stored ids are unaffected: still ordered and counted within the millisecond.
    expect(generator.next(now).readUInt16BE(6)).toBe(0)
    expect(generator.next(now).readUInt16BE(6)).toBe(1)
  })
})
