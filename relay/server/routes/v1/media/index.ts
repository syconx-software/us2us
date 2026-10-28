import { createWriteStream } from 'node:fs'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { getRequestHeader, setResponseStatus } from 'h3'
import { defineApiRoute, isClientAbort, limitByAccount, requireAccount } from '../../../http/api'
import { random, sha256 } from '../../../lib/crypto'
import { encodeB64u, utcDate } from '../../../lib/encoding'
import { ApiError, badRequest, payloadTooLarge, rateLimited, unauthorized, unavailable } from '../../../lib/errors'
import { useRelay } from '../../../relay'

const UNAVAILABLE_RETRY_SECONDS = 60
/** The relay-wide upload budget is used up; asking again sooner would not help. */
const GLOBAL_BUDGET_MAX_RETRY_SECONDS = 3600
const STORED_FULL_RETRY_SECONDS = 3600
const GLOBAL_KEY = 'relay'
const DAY_MS = 86_400_000
/** Mobile uploads stream through nginx unbuffered, so they may pause longer than other requests (nginx allows 60 s). */
const UPLOAD_STALL_TIMEOUT_MS = 60_000

/**
 * §4.9 Upload an encrypted blob. The body is streamed to disk, never held in
 * memory. A Content-Length is required so that size limits, the quotas and
 * the free-space floor are checked before a single byte is accepted:
 *
 * - per account: uploads per hour and bytes per day (lower while the
 *   account is new), and the unexpired bytes it has stored (429);
 * - for the whole relay: bytes per day (503), so that many accounts cannot
 *   fill the disk faster than the operator can react;
 * - free space: media stop at the floor plus a reserve that only envelopes
 *   may use (503), so uploads can never stop message delivery.
 */
export default defineApiRoute({
  POST: async (event) => {
    event.node.res.setTimeout(UPLOAD_STALL_TIMEOUT_MS)
    const accountId = await requireAccount(event)
    const relay = useRelay()
    const { mediaMaxBytes, mediaTtlMs, rateLimits } = relay.config

    const declared = getRequestHeader(event, 'content-length')
    const size = declared !== undefined && /^\d+$/.test(declared) ? Number(declared) : Number.NaN
    if (!Number.isSafeInteger(size)) throw badRequest('Content-Length is required')
    if (size > mediaMaxBytes) throw payloadTooLarge(mediaMaxBytes)
    if (size === 0) throw badRequest('the blob must not be empty')
    limitByAccount(accountId, 'media-uploads', rateLimits.mediaUploads)
    limitByAccount(accountId, 'media-bytes', rateLimits.mediaBytes, size)
    const now = Date.now()
    const uploader = await relay.media.uploader(accountId, now)
    if (!uploader) throw unauthorized()
    const isNew = relay.config.newAccountDays > 0 && uploader.createdOn > utcDate(now - relay.config.newAccountDays * DAY_MS)
    const maxStored = isNew ? relay.config.mediaStoredMaxNewBytes : relay.config.mediaStoredMaxBytes
    if (uploader.storedBytes + size > maxStored) throw rateLimited(STORED_FULL_RETRY_SECONDS, 'too much media is stored for this account')
    if (isNew) limitByAccount(accountId, 'media-bytes-new', rateLimits.mediaBytesNew, size)
    const global = relay.limiter.take('media-bytes-global', GLOBAL_KEY, rateLimits.mediaBytesGlobal, size)
    if (!global.ok) throw unavailable(Math.min(GLOBAL_BUDGET_MAX_RETRY_SECONDS, global.retryAfterSeconds))
    if (!relay.disk.reserve(size, 'media')) {
      relay.limiter.refund('media-bytes-global', GLOBAL_KEY, rateLimits.mediaBytesGlobal, size)
      throw unavailable(UNAVAILABLE_RETRY_SECONDS)
    }

    const id = random(16)
    const deleteToken = random(32)
    const expiresAt = now + mediaTtlMs
    const created = await relay.media.create(id, accountId, sha256(deleteToken), size, expiresAt, maxStored, now)
    if (created !== 'created') relay.limiter.refund('media-bytes-global', GLOBAL_KEY, rateLimits.mediaBytesGlobal, size)
    // Deleted while this request waited for it.
    if (created === 'gone') throw unauthorized()
    // Another upload of the same account was admitted meanwhile.
    if (created === 'full') throw rateLimited(STORED_FULL_RETRY_SECONDS, 'too much media is stored for this account')
    try {
      await receive(event.node.req, relay.media.partPathOf(id), size)
      // Deleted while the body arrived.
      if (!await relay.media.publish(id)) throw unauthorized()
    } catch (error) {
      // If even this fails, the row expires and the retention sweep removes both files.
      await relay.media.remove(id).catch(() => undefined)
      if (error instanceof ApiError) throw error
      if (isClientAbort(error)) throw badRequest('the upload was interrupted')
      throw error
    }

    setResponseStatus(event, 201)
    return { mediaId: encodeB64u(id), deleteToken: encodeB64u(deleteToken), expiresAt }
  },
})

/** Streams exactly `size` bytes into a new file. */
async function receive(source: NodeJS.ReadableStream, path: string, size: number): Promise<void> {
  let received = 0
  const meter = new Transform({
    transform(chunk: Buffer, _encoding, callback) {
      received += chunk.length
      callback(received > size ? payloadTooLarge(size) : null, chunk)
    },
  })
  await pipeline(source, meter, createWriteStream(path, { flags: 'wx' }))
  if (received !== size) throw badRequest('the body is shorter than Content-Length')
}
