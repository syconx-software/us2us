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

  it('counts per recipient, so two recipients cannot tell they were sent to in the same burst (audit L-6)', () => {
    const generator = new EnvelopeIdGenerator()
    const now = 1_760_000_000_000
    const a = [generator.next(now, false, 'a'), generator.next(now, false, 'a')]
    const b = [generator.next(now, false, 'b'), generator.next(now, false, 'b')]
    const a3 = generator.next(now, false, 'a')
    expect(a.map(id => id.readUInt16BE(6))).toEqual([0, 1])
    expect(b.map(id => id.readUInt16BE(6))).toEqual([0, 1])
    expect(a3.readUInt16BE(6)).toBe(2)
    expect(Buffer.compare(a[1]!, a3)).toBe(-1)
    // A recipient's ids stay ordered when the clock steps back.
    const later = generator.next(now + 5, false, 'b')
    const stepped = generator.next(now + 1, false, 'b')
    expect(Buffer.compare(later, stepped)).toBe(-1)
  })

  it('forgets recipients from earlier milliseconds once it tracks many, and stays ordered', () => {
    const generator = new EnvelopeIdGenerator()
    let previous = generator.next(1000, false, 'keep')
    for (let i = 0; i < 20_000; i++) generator.next(1000 + Math.floor(i / 100), false, `r${i}`)
    const next = generator.next(500, false, 'keep')
    expect(Buffer.compare(previous, next)).toBe(-1)
    previous = next
    expect(Buffer.compare(previous, generator.next(500, false, 'keep'))).toBe(-1)
    expect((generator as unknown as { counters: Map<string, unknown> }).counters.size).toBeLessThanOrEqual(10_001)
  })
})
