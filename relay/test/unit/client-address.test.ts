import { describe, expect, it } from 'vitest'
import { resolveClientAddress, type ProxyTrust } from '../../server/lib/client-address'

const trust = (overrides: Partial<ProxyTrust> = {}): ProxyTrust =>
  ({ trustProxy: true, trustProxyFrom: ['127.0.0.1', '::1'], trustProxyHeader: 'x-forwarded-for', ...overrides })
const headers = (values: Record<string, string>) => (name: string) => values[name]

describe('resolveClientAddress', () => {
  it('uses the socket address unless TRUST_PROXY is set', () => {
    expect(resolveClientAddress(trust({ trustProxy: false }), '127.0.0.1', headers({ 'x-forwarded-for': '198.51.100.1' }))).toBe('127.0.0.1')
    expect(resolveClientAddress(trust(), undefined, headers({ 'x-forwarded-for': '198.51.100.1' }))).toBe('unknown')
  })

  it('believes the header only from a configured proxy peer, IPv4-mapped or not', () => {
    const forwarded = headers({ 'x-forwarded-for': '198.51.100.1' })
    expect(resolveClientAddress(trust(), '127.0.0.1', forwarded)).toBe('198.51.100.1')
    expect(resolveClientAddress(trust(), '::ffff:127.0.0.1', forwarded)).toBe('198.51.100.1')
    expect(resolveClientAddress(trust(), '::1', forwarded)).toBe('198.51.100.1')
    // Another host that reaches the port directly (a firewall mistake, a neighbour on a shared host).
    expect(resolveClientAddress(trust(), '203.0.113.5', forwarded)).toBe('203.0.113.5')
    expect(resolveClientAddress(trust({ trustProxyFrom: ['192.0.2.1'] }), '127.0.0.1', forwarded)).toBe('127.0.0.1')
  })

  it('takes the right-most X-Forwarded-For entry, or X-Real-IP when configured, and only an IP address', () => {
    expect(resolveClientAddress(trust(), '127.0.0.1', headers({ 'x-forwarded-for': '203.0.113.7, 198.51.100.2' }))).toBe('198.51.100.2')
    expect(resolveClientAddress(trust(), '127.0.0.1', headers({ 'x-forwarded-for': 'evil' }))).toBe('127.0.0.1')
    expect(resolveClientAddress(trust(), '127.0.0.1', headers({ 'x-real-ip': '198.51.100.3' }))).toBe('127.0.0.1')
    const real = trust({ trustProxyHeader: 'x-real-ip' })
    expect(resolveClientAddress(real, '127.0.0.1', headers({ 'x-real-ip': ' 2001:db8::5 ', 'x-forwarded-for': '198.51.100.1' }))).toBe('2001:db8::5')
    expect(resolveClientAddress(real, '127.0.0.1', headers({ 'x-real-ip': '198.51.100.3, 198.51.100.4' }))).toBe('127.0.0.1')
    expect(resolveClientAddress(real, '127.0.0.1', headers({}))).toBe('127.0.0.1')
  })
})
