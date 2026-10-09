import { statfs } from 'node:fs/promises'

/**
 * Free space of the file system that holds MEDIA_DIR (normally MySQL's as
 * well), refreshed in the background so that request paths check it without
 * a system call. Below the floor the relay refuses new media and stored
 * envelopes: a full disk would stop MySQL, and with it the whole relay.
 */
export class DiskSpace {
  private free = Number.POSITIVE_INFINITY

  constructor(
    private readonly dir: string,
    private readonly minFreeBytes: number,
  ) {}

  async refresh(): Promise<void> {
    if (this.minFreeBytes === 0) return
    const stats = await statfs(this.dir)
    this.free = stats.bavail * stats.bsize
  }

  /** Whether `bytes` more fit above the floor; if so they count as used until the next refresh. */
  reserve(bytes: number): boolean {
    if (this.minFreeBytes === 0) return true
    if (this.free - bytes < this.minFreeBytes) return false
    this.free -= bytes
    return true
  }
}
