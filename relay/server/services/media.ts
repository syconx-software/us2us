import { lstat, mkdir, readdir, rename, rm, unlink } from 'node:fs/promises'
import { join } from 'node:path'
import { and, eq, gt, inArray, lt, sql } from 'drizzle-orm'
import type { Database, Db } from '../db/client'
import { accounts, media } from '../db/schema'
import { lockAccount } from './accounts'

export interface MediaRecord {
  size: number
  deleteTokenHash: Buffer
}

export interface Uploader {
  /** UTC registration date, `YYYY-MM-DD`. */
  createdOn: string
  /** Bytes of the account's uploads that have not expired or been deleted. */
  storedBytes: number
}

/** `full`: the upload would take the uploader past its stored-media cap. */
export type MediaCreation = 'created' | 'gone' | 'full'

/** Unexpired upload bytes of one account (the `media_uploader` index, then the rows it points to). */
async function storedBytes(db: Db, uploader: Buffer, now: number): Promise<number> {
  const [row] = await db.select({ bytes: sql<number>`coalesce(sum(${media.size}), 0)`.mapWith(Number) }).from(media)
    .where(and(eq(media.uploader, uploader), gt(media.expiresAt, now)))
  return row?.bytes ?? 0
}

/** Ids per lookup query. */
const LOOKUP_BATCH = 500
/** A finished blob or an upload in progress; nothing else in MEDIA_DIR is ever deleted. */
const BLOB_NAME = /^[0-9a-f]{32}(?:\.part)?$/

/**
 * Encrypted media blobs (PROTOCOL.md §4.9). Metadata lives in MySQL, the
 * bytes on disk at `<MEDIA_DIR>/<first two hex digits>/<id hex>`, spread
 * over 256 directories. The row is written before the upload starts, so a
 * crash mid-upload leaves only a row plus a `.part` file, both of which the
 * retention sweep removes when the row expires. The row is the source of
 * truth; a download only succeeds once the finished file has been renamed
 * into place.
 *
 * Files never outlive their row: an account deletion removes the rows
 * before the files, and an upload checks its row again after moving its
 * file into place (`publish`). Every other delete removes the files first,
 * so that a failure leaves a row to retry with; none of them can meet an
 * upload in progress (the delete token is handed out when it completes, a
 * row expires after days). What a crash between the two steps leaves
 * behind is found by `removeOrphans`.
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
  async create(id: Buffer, uploader: Buffer, deleteTokenHash: Buffer, size: number, expiresAt: number,
    maxStoredBytes = Number.POSITIVE_INFINITY, now = Date.now()): Promise<MediaCreation> {
    return this.database.transaction(async (tx) => {
      if (!await lockAccount(tx, uploader)) return 'gone'
      // Under the account's lock, so concurrent uploads cannot each pass the cap.
      if (Number.isFinite(maxStoredBytes) && await storedBytes(tx, uploader, now) + size > maxStoredBytes) return 'full'
      await tx.insert(media).values({ id, uploader, deleteTokenHash, size, expiresAt })
      return 'created'
    })
  }

  /**
   * The uploader's registration date and what it has stored, read without a
   * lock before an upload is admitted; `create` checks the cap again. Null
   * if the account is gone.
   */
  async uploader(uploader: Buffer, now = Date.now()): Promise<Uploader | null> {
    const { db } = this.database
    const [account] = await db.select({ createdOn: accounts.createdOn }).from(accounts).where(eq(accounts.id, uploader)).limit(1)
    if (!account) return null
    return { createdOn: account.createdOn, storedBytes: await storedBytes(db, uploader, now) }
  }

  async find(id: Buffer, now: number): Promise<MediaRecord | null> {
    const [row] = await this.database.db.select({ size: media.size, deleteTokenHash: media.deleteTokenHash })
      .from(media).where(and(eq(media.id, id), gt(media.expiresAt, now))).limit(1)
    return row ?? null
  }

  /**
   * Moves a finished upload into place; false if its row is gone because
   * the uploader's account was deleted meanwhile, and then nothing of it is
   * kept. The row is checked after the move: a deletion that comes later
   * finds the file in place and removes it.
   */
  async publish(id: Buffer): Promise<boolean> {
    try {
      await rename(this.partPathOf(id), this.pathOf(id))
    } catch (error) {
      // An account deletion removes the file even while it is being written.
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT' || await this.hasRow(id)) throw error
      return false
    }
    if (await this.hasRow(id)) return true
    await this.removeFiles([id])
    return false
  }

  async remove(id: Buffer): Promise<void> {
    // Files first, as in removeExpired: if deleting them fails, the row stays.
    await this.removeFiles([id])
    await this.database.db.delete(media).where(eq(media.id, id))
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

  /**
   * Deletes the files of one of the 256 subdirectories (0..255) that have no
   * row and were last written before `modifiedBefore`; returns how many. A
   * row always exists before its file and ids are never reused, so a file
   * without a row never gets one again. Only regular files named like a
   * blob of that subdirectory are considered and symbolic links are never
   * followed; on any error the files stay and the error is thrown.
   */
  async removeOrphans(subdir: number, modifiedBefore: number): Promise<number> {
    const prefix = subdir.toString(16).padStart(2, '0')
    const dir = join(this.dir, prefix)
    const dirStats = await lstat(dir).catch(unlessMissing)
    if (!dirStats?.isDirectory()) return 0

    const candidates: string[] = []
    for (const name of await readdir(dir)) {
      if (!BLOB_NAME.test(name) || !name.startsWith(prefix)) continue
      const stats = await lstat(join(dir, name)).catch(unlessMissing)
      if (stats?.isFile() && stats.mtimeMs < modifiedBefore) candidates.push(name)
    }
    if (candidates.length === 0) return 0

    const ids = [...new Set(candidates.map(name => name.slice(0, 32)))]
    const withRow = await this.withRow(ids.map(hex => Buffer.from(hex, 'hex')))
    let removed = 0
    for (const name of candidates) {
      if (withRow.has(name.slice(0, 32))) continue
      if (await unlink(join(dir, name)).then(() => true, unlessMissing)) removed++
    }
    return removed
  }

  private async hasRow(id: Buffer): Promise<boolean> {
    return (await this.withRow([id])).has(id.toString('hex'))
  }

  /** The ids (hex) among `ids` that have a row, expired or not. */
  private async withRow(ids: Buffer[]): Promise<Set<string>> {
    const found = new Set<string>()
    for (let i = 0; i < ids.length; i += LOOKUP_BATCH) {
      const rows = await this.database.db.select({ id: media.id }).from(media)
        .where(inArray(media.id, ids.slice(i, i + LOOKUP_BATCH)))
      for (const row of rows) found.add(row.id.toString('hex'))
    }
    return found
  }
}

/** For `.catch`: a path that is already gone is no error, anything else is. */
function unlessMissing(error: NodeJS.ErrnoException): undefined {
  if (error.code === 'ENOENT') return undefined
  throw error
}
