import { expect, it } from 'vitest'
import { preferredLocale } from '../../server/lib/locale'

it('picks Turkish or English by preference and q-value', () => {
  expect(preferredLocale('tr')).toBe('tr')
  expect(preferredLocale('tr-TR,en;q=0.5')).toBe('tr')
  expect(preferredLocale('en-US,tr;q=0.9')).toBe('en')
  expect(preferredLocale('en;q=0.4, tr;q=0.6')).toBe('tr')
  expect(preferredLocale('de, tr;q=0.3')).toBe('tr')
  expect(preferredLocale('de, fr;q=0.3')).toBe('en')
  expect(preferredLocale('tr;q=0')).toBe('en')
  expect(preferredLocale(undefined)).toBe('en')
})
