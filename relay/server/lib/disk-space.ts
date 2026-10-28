import { statfs } from 'node:fs/promises'

/** Media uploads, or envelopes (sealed sends, community deliveries). */
export type DiskUse = 'media' | 'envelope'

/**
 * Free space of the file system that holds MEDIA_DIR (normally MySQL's as
 * well), refreshed in the background so that request paths check it without
 * a system call. Below the floor the relay refuses new media and stored
 * envelopes: a full disk would stop MySQL, and with it the whole relay.
 *
 * Media must also leave `envelopeReserveBytes` above the floor: blobs are
 * large and cheap to upload, envelopes small, so a flood of uploads stops
 * at the reserve and messages keep being delivered until the floor itself.
 */
export class DiskSpace {
  private free = Number.POSITIVE_INFINITY

  constructor(
    private readonly dir: string,
    private readonly minFreeBytes: number,
    private readonly envelopeReserveBytes = 0,
  ) {}

  async refresh(): Promise<void> {
    if (this.minFreeBytes === 0) return
    const stats = await statfs(this.dir)
    this.free = stats.bavail * stats.bsize
  }

  /** Whether `bytes` more fit above the floor for this use; if so they count as used until the next refresh. */
  reserve(bytes: number, use: DiskUse = 'envelope'): boolean {
    if (this.minFreeBytes === 0) return true
    const floor = this.minFreeBytes + (use === 'media' ? this.envelopeReserveBytes : 0)
    if (this.free - bytes < floor) return false
    this.free -= bytes
    return true
  }
}
