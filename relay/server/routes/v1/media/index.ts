import { createWriteStream } from 'node:fs'
import { rename } from 'node:fs/promises'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { getRequestHeader, setResponseStatus } from 'h3'
import { defineApiRoute, isClientAbort, limitByAccount, requireAccount } from '../../../http/api'
import { random, sha256 } from '../../../lib/crypto'
import { encodeB64u } from '../../../lib/encoding'
import { ApiError, badRequest, payloadTooLarge, unauthorized, unavailable } from '../../../lib/errors'
import { useRelay } from '../../../relay'

const UNAVAILABLE_RETRY_SECONDS = 60
/** Mobile uploads stream through nginx unbuffered, so they may pause longer than other requests (nginx allows 60 s). */
const UPLOAD_STALL_TIMEOUT_MS = 60_000

/**
 * §4.9 Upload an encrypted blob. The body is streamed to disk, never held in
 * memory. A Content-Length is required so that size limits, the daily byte
 * quota and the free-space floor are checked before a single byte is
 * accepted.
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
    if (!relay.disk.reserve(size)) throw unavailable(UNAVAILABLE_RETRY_SECONDS)

    const id = random(16)
    const deleteToken = random(32)
    const expiresAt = Date.now() + mediaTtlMs
    // Deleted while this request waited for it.
    if (!await relay.media.create(id, accountId, sha256(deleteToken), size, expiresAt)) throw unauthorized()
    try {
      await receive(event.node.req, relay.media.partPathOf(id), size)
      await rename(relay.media.partPathOf(id), relay.media.pathOf(id))
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
