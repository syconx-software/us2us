import { mkdir, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { and, eq, gt, inArray, lt } from 'drizzle-orm'
import type { Database } from '../db/client'
import { media } from '../db/schema'
import { lockAccount } from './accounts'

export interface MediaRecord {
  size: number
  deleteTokenHash: Buffer
}

/**
 * Encrypted media blobs (PROTOCOL.md §4.9). Metadata lives in MySQL, the
 * bytes on disk at `<MEDIA_DIR>/<first two hex digits>/<id hex>`, spread
 * over 256 directories. The row is written before the upload starts, so a
 * crash mid-upload leaves only a row plus a `.part` file, both of which the
 * retention sweep removes when the row expires. The row is the source of
 * truth; a download only succeeds once the finished file has been renamed
 * into place.
 */
export class MediaStore {
  constructor(
    private readonly database: Database,
    private readonly dir: string,
  ) {}

  async init(): Promise<void> {
    await Promise.all(Array.from({ length: 256 }, (_, i) =>
      mkdir(join(this.dir, i.toString(16).padStart(2, '0')), { recursive: true })))
  }

  pathOf(id: Buffer): string {
    const hex = id.toString('hex')
    return join(this.dir, hex.slice(0, 2), hex)
  }

  partPathOf(id: Buffer): string {
    return `${this.pathOf(id)}.part`
  }

  /**
   * Records an upload before its bytes arrive; false if the uploader's
   * account is gone. The account row is locked so that an account deletion
   * running at the same time cannot miss the new row.
   */
  async create(id: Buffer, uploader: Buffer, deleteTokenHash: Buffer, size: number, expiresAt: number): Promise<boolean> {
    return this.database.transaction(async (tx) => {
      if (!await lockAccount(tx, uploader)) return false
      await tx.insert(media).values({ id, uploader, deleteTokenHash, size, expiresAt })
      return true
    })
  }

  async find(id: Buffer, now: number): Promise<MediaRecord | null> {
    const [row] = await this.database.db.select({ size: media.size, deleteTokenHash: media.deleteTokenHash })
      .from(media).where(and(eq(media.id, id), gt(media.expiresAt, now))).limit(1)
    return row ?? null
  }

  async remove(id: Buffer): Promise<void> {
    await this.database.db.delete(media).where(eq(media.id, id))
    await this.removeFiles([id])
  }

  /** Removes up to `limit` expired blobs; returns how many. */
  async removeExpired(now: number, limit: number): Promise<number> {
    const rows = await this.database.db.select({ id: media.id }).from(media)
      .where(lt(media.expiresAt, now)).limit(limit)
    if (rows.length === 0) return 0
    const ids = rows.map(row => row.id)
    // Files first: if deleting them fails, the rows stay and the next sweep retries.
    await this.removeFiles(ids)
    await this.database.db.delete(media).where(inArray(media.id, ids))
    return ids.length
  }

  async removeFiles(ids: Buffer[]): Promise<void> {
    await Promise.all(ids.flatMap(id => [rm(this.pathOf(id), { force: true }), rm(this.partPathOf(id), { force: true })]))
  }
}
