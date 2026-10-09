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
