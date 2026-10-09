/**
 * A small bounded cache with per-entry expiry. Map iteration order is
 * insertion order, so evicting from the front drops the oldest entries. The
 * relay is a single process, so every write path invalidates its entries
 * directly; the TTL only bounds how long memory is held.
 */
export class TtlCache<K, V> {
  private readonly entries = new Map<K, { value: V, expiresAt: number }>()

  constructor(
    private readonly ttlMs: number,
    private readonly maxEntries: number,
  ) {}

  get(key: K, now = Date.now()): V | undefined {
    const entry = this.entries.get(key)
    if (!entry) return undefined
    if (entry.expiresAt <= now) {
      this.entries.delete(key)
      return undefined
    }
    return entry.value
  }

  set(key: K, value: V, now = Date.now()): void {
    this.entries.delete(key)
    if (this.entries.size >= this.maxEntries) {
      const oldest = this.entries.keys().next()
      if (!oldest.done) this.entries.delete(oldest.value)
    }
    this.entries.set(key, { value, expiresAt: now + this.ttlMs })
  }

  delete(key: K): void {
    this.entries.delete(key)
  }

  sweep(now = Date.now()): void {
    for (const [key, entry] of this.entries) {
      if (entry.expiresAt <= now) this.entries.delete(key)
    }
  }
}
