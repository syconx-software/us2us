import { statfs } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { describe, expect, it } from 'vitest'
import { DiskSpace } from '../../server/lib/disk-space'

const MiB = 1024 * 1024

describe('DiskSpace (audit M-2)', () => {
  it('keeps a reserve above the floor that media cannot use and envelopes can', async () => {
    const stats = await statfs(tmpdir())
    const free = stats.bavail * stats.bsize
    // Floor 100 MiB under the free space, reserve 200 MiB above the floor.
    const disk = new DiskSpace(tmpdir(), free - 100 * MiB, 200 * MiB)
    await disk.refresh()
    expect(disk.reserve(1, 'media')).toBe(false)
    expect(disk.reserve(50 * MiB, 'envelope')).toBe(true)
    expect(disk.reserve(60 * MiB)).toBe(false)
  })

  it('lets media use the space above floor and reserve, and counts it until the next refresh', async () => {
    const stats = await statfs(tmpdir())
    const free = stats.bavail * stats.bsize
    const disk = new DiskSpace(tmpdir(), free - 300 * MiB, 100 * MiB)
    await disk.refresh()
    expect(disk.reserve(150 * MiB, 'media')).toBe(true)
    expect(disk.reserve(100 * MiB, 'media')).toBe(false)
    expect(disk.reserve(100 * MiB, 'envelope')).toBe(true)
  })

  it('is off with a floor of 0', () => {
    const disk = new DiskSpace(tmpdir(), 0, 100 * MiB)
    expect(disk.reserve(Number.MAX_SAFE_INTEGER, 'media')).toBe(true)
  })
})
