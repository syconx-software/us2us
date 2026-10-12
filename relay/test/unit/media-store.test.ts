import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { lutimes, mkdir, mkdtemp, readFile, rm, symlink, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { Database } from '../../server/db/client'
import { MediaStore } from '../../server/services/media'

const HOUR = 3_600_000

/**
 * The media table in memory. A lookup answers with every row it holds, a
 * superset of the rows asked for, which is all MediaStore relies on.
 */
class FakeMediaTable {
  readonly ids = new Set<string>()
  failing = false
  deletes = 0

  readonly database = {
    db: {
      select: () => ({ from: () => ({ where: async () => this.rows() }) }),
      delete: () => ({
        where: async () => {
          this.deletes++
        },
      }),
    },
  } as unknown as Database

  private rows(): { id: Buffer }[] {
    if (this.failing) throw Object.assign(new Error('db down'), { code: 'ECONNREFUSED' })
    return [...this.ids].map(hex => ({ id: Buffer.from(hex, 'hex') }))
  }
}

let dir: string
let table: FakeMediaTable
let store: MediaStore

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), 'us2us-media-unit-'))
  table = new FakeMediaTable()
  store = new MediaStore(table.database, dir)
  await store.init()
})

afterEach(async () => {
  await rm(dir, { recursive: true, force: true })
})

/** A random id in the given subdirectory. */
const idIn = (subdir: number) => Buffer.concat([Buffer.from([subdir]), randomBytes(15)])

describe('MediaStore.publish', () => {
  it('moves the finished upload into place while its row exists', async () => {
    const id = randomBytes(16)
    table.ids.add(id.toString('hex'))
    const blob = randomBytes(100)
    await writeFile(store.partPathOf(id), blob)

    expect(await store.publish(id)).toBe(true)
    expect(await readFile(store.pathOf(id))).toEqual(blob)
    expect(existsSync(store.partPathOf(id))).toBe(false)
  })

  it('keeps nothing when the account was deleted before the upload opened its file', async () => {
    const id = randomBytes(16)
    table.ids.add(id.toString('hex'))
    // What an account deletion does in between: rows first, then the files, of which none exist yet.
    table.ids.delete(id.toString('hex'))
    await store.removeFiles([id])
    await writeFile(store.partPathOf(id), randomBytes(100))

    expect(await store.publish(id)).toBe(false)
    expect(existsSync(store.pathOf(id))).toBe(false)
    expect(existsSync(store.partPathOf(id))).toBe(false)
  })

  it('reports a deletion that removed the file while it was being written', async () => {
    const id = randomBytes(16)
    await writeFile(store.partPathOf(id), randomBytes(100))
    await store.removeFiles([id])

    expect(await store.publish(id)).toBe(false)
    expect(existsSync(store.pathOf(id))).toBe(false)
  })

  it('fails when the file is missing although the row exists', async () => {
    const id = randomBytes(16)
    table.ids.add(id.toString('hex'))
    await expect(store.publish(id)).rejects.toMatchObject({ code: 'ENOENT' })
  })
})

describe('MediaStore.remove', () => {
  it('deletes the files, then the row', async () => {
    const id = randomBytes(16)
    await writeFile(store.pathOf(id), randomBytes(10))
    await store.remove(id)
    expect(existsSync(store.pathOf(id))).toBe(false)
    expect(table.deletes).toBe(1)
  })

  it('keeps the row when the files cannot be deleted', async () => {
    const id = randomBytes(16)
    await mkdir(store.pathOf(id))
    await expect(store.remove(id)).rejects.toThrow()
    expect(table.deletes).toBe(0)
  })
})

describe('MediaStore.removeOrphans', () => {
  const subdir = 0x3a
  const old = new Date(Date.now() - 2 * HOUR)
  const planted = async (path: string, mtime = old) => {
    await writeFile(path, randomBytes(10))
    await utimes(path, mtime, mtime)
    return path
  }

  it('deletes only old files of that subdirectory that have no row', async () => {
    const orphan = idIn(subdir)
    const orphanFile = await planted(store.pathOf(orphan))
    const orphanPart = await planted(store.partPathOf(orphan))

    const live = idIn(subdir)
    table.ids.add(live.toString('hex'))
    const keep = [
      await planted(store.pathOf(live)),
      await planted(store.partPathOf(idIn(subdir)), new Date()),
      await planted(store.pathOf(idIn(subdir + 1))),
      await planted(join(dir, '3a', 'README')),
      await planted(join(dir, '3a', idIn(subdir).toString('hex').toUpperCase())),
      await planted(join(dir, '3a', `${idIn(subdir).toString('hex')}.tmp`)),
      // Named for another subdirectory.
      await planted(join(dir, '3a', idIn(0x3b).toString('hex'))),
    ]
    const directory = store.pathOf(idIn(subdir))
    await mkdir(directory)
    await utimes(directory, old, old)
    const outside = await planted(join(dir, 'outside'))
    const link = store.pathOf(idIn(subdir))
    await symlink(outside, link)
    await lutimes(link, old, old)
    keep.push(directory, outside, link)

    expect(await store.removeOrphans(subdir, Date.now() - HOUR)).toBe(2)
    expect(existsSync(orphanFile)).toBe(false)
    expect(existsSync(orphanPart)).toBe(false)
    for (const path of keep) expect(existsSync(path), path).toBe(true)

    expect(await store.removeOrphans(subdir, Date.now() - HOUR)).toBe(0)
  })

  it('keeps every file when the database cannot be asked', async () => {
    const orphan = await planted(store.pathOf(idIn(subdir)))
    table.failing = true
    await expect(store.removeOrphans(subdir, Date.now() - HOUR)).rejects.toThrow('db down')
    expect(existsSync(orphan)).toBe(true)
  })

  it('does not follow a subdirectory that is a symbolic link', async () => {
    const elsewhere = await mkdtemp(join(tmpdir(), 'us2us-media-elsewhere-'))
    try {
      await rm(join(dir, '3a'), { recursive: true })
      await symlink(elsewhere, join(dir, '3a'))
      const target = await planted(join(elsewhere, idIn(subdir).toString('hex')))
      expect(await store.removeOrphans(subdir, Date.now() - HOUR)).toBe(0)
      expect(existsSync(target)).toBe(true)
    } finally {
      await rm(elsewhere, { recursive: true, force: true })
    }
  })

  it('treats a missing subdirectory as empty', async () => {
    await rm(join(dir, '3a'), { recursive: true })
    expect(await store.removeOrphans(subdir, Date.now())).toBe(0)
  })
})
