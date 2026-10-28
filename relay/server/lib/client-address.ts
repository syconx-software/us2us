import { isIP } from 'node:net'
import type { ProxyHeader } from './config'

export interface ProxyTrust {
  trustProxy: boolean
  /** Peer addresses whose header is believed. */
  trustProxyFrom: readonly string[]
  trustProxyHeader: ProxyHeader
}

/**
 * The client address taken from an `X-Forwarded-For` header. Only used with
 * TRUST_PROXY=1, behind our own nginx, which sets the header to the peer
 * address. The right-most entry is used because a proxy that appends instead
 * of overwriting leaves a client-chosen value at the front.
 */
export function forwardedFor(header: string | null | undefined): string | undefined {
  const last = header?.split(',').pop()?.trim()
  return last || undefined
}

/** `::ffff:127.0.0.1` and `127.0.0.1` are the same peer. */
function normalizePeer(address: string): string {
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address)
  return (mapped ? mapped[1]! : address).toLowerCase()
}

/**
 * The address a request is rate limited under. The proxy header is believed
 * only with TRUST_PROXY=1 and only when the connection itself comes from one
 * of the TRUST_PROXY_FROM addresses, so a client that reaches the relay's
 * port directly (a firewall mistake, another tenant of a shared host) cannot
 * pick its own bucket. A header that does not hold an IP address is ignored.
 */
export function resolveClientAddress(
  trust: ProxyTrust,
  peer: string | undefined,
  header: (name: ProxyHeader) => string | null | undefined,
): string {
  const socketAddress = peer ?? 'unknown'
  if (!trust.trustProxy || peer === undefined) return socketAddress
  const fromProxy = trust.trustProxyFrom.some(allowed => normalizePeer(allowed) === normalizePeer(peer))
  if (!fromProxy) return socketAddress
  const raw = header(trust.trustProxyHeader)
  const value = trust.trustProxyHeader === 'x-real-ip' ? raw?.trim() : forwardedFor(raw)
  return value && isIP(value) !== 0 ? value : socketAddress
}
