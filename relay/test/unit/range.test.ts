import { expect, it } from 'vitest'
import { parseRange } from '../../server/lib/range'

it('parses single byte ranges', () => {
  expect(parseRange(undefined, 100)).toBeNull()
  expect(parseRange('bytes=0-9', 100)).toEqual({ start: 0, end: 9 })
  expect(parseRange('bytes=90-', 100)).toEqual({ start: 90, end: 99 })
  expect(parseRange('bytes=90-1000', 100)).toEqual({ start: 90, end: 99 })
  expect(parseRange('bytes=-10', 100)).toEqual({ start: 90, end: 99 })
  expect(parseRange('bytes=-1000', 100)).toEqual({ start: 0, end: 99 })
  expect(parseRange('bytes=100-', 100)).toBe('unsatisfiable')
  expect(parseRange('bytes=-0', 100)).toBe('unsatisfiable')
  for (const ignored of ['bytes=5-1', 'bytes=-', 'bytes=0-1,4-5', 'items=0-1', 'bytes=a-b']) expect(parseRange(ignored, 100)).toBeNull()
})
