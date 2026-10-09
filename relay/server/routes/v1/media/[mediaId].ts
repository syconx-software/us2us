import { open } from 'node:fs/promises'
import { pipeline } from 'node:stream/promises'
import { getRequestHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { bytesParam, defineApiRoute, limitByIp } from '../../../http/api'
import { constantTimeEqual, sha256 } from '../../../lib/crypto'
import { decodeB64uExact } from '../../../lib/encoding'
import { accessDenied, badRequest, notFound } from '../../../lib/errors'
import { parseRange } from '../../../lib/range'
import { useRelay } from '../../../relay'

export default defineApiRoute({
  /**
   * §4.9 Download. No authentication: the 128-bit id is the capability and
   * the content is ciphertext. Single byte ranges let clients resume.
   */
  GET: async (event) => {
    const relay = useRelay()
    limitByIp(event, 'media-fetch', relay.config.rateLimits.mediaFetch)
    const id = bytesParam(event, 'mediaId', 16)
    if (!await relay.media.find(id, Date.now())) throw notFound('media')

    // The file appears only once its upload has completed.
    const file = await open(relay.media.pathOf(id), 'r').catch(() => null)
    if (!file) throw notFound('media')
    try {
      const { size } = await file.stat()
      const range = parseRange(getRequestHeader(event, 'range'), size)
      setResponseHeaders(event, { 'content-type': 'application/octet-stream', 'accept-ranges': 'bytes' })
      if (range === 'unsatisfiable') {
        setResponseStatus(event, 416)
        setResponseHeaders(event, { 'content-range': `bytes */${size}`, 'content-length': 0 })
        event.node.res.end()
        return
      }
      const { start, end } = range ?? { start: 0, end: size - 1 }
      setResponseHeaders(event, { 'content-length': end - start + 1 })
      if (range) {
        setResponseStatus(event, 206)
        setResponseHeaders(event, { 'content-range': `bytes ${start}-${end}/${size}` })
      }
      await pipeline(file.createReadStream({ start, end, autoClose: false }), event.node.res)
    } finally {
      await file.close()
    }
  },

  /** §4.9 Delete with the token handed out at upload: by the recipient after download, or by the sender to unsend. */
  DELETE: async (event) => {
    const relay = useRelay()
    limitByIp(event, 'media-fetch', relay.config.rateLimits.mediaFetch)
    const id = bytesParam(event, 'mediaId', 16)
    const deleteToken = decodeB64uExact(getRequestHeader(event, 'x-delete-token'), 32)
    if (!deleteToken) throw badRequest('X-Delete-Token must be 32 bytes of unpadded base64url')
    const record = await relay.media.find(id, Date.now())
    if (!record) throw notFound('media')
    if (!constantTimeEqual(sha256(deleteToken), record.deleteTokenHash)) throw accessDenied('wrong delete token')
    await relay.media.remove(id)
    return null
  },
})
