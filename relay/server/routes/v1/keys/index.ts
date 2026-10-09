import { defineApiRoute, readJson, requireAccount } from '../../../http/api'
import { KEY_UPLOAD_BODY_LIMIT } from '../../../http/limits'
import { unauthorized } from '../../../lib/errors'
import { keyUploadRequest } from '../../../lib/validation'
import { useRelay } from '../../../relay'

/**
 * §4.5 Upload/replace pre-keys. One-time keys are appended (known key ids are
 * ignored, at most 200 per kind are kept); signed and last-resort keys
 * replace the previous ones; `replaceAll` first deletes all one-time keys.
 */
export default defineApiRoute({
  PUT: async (event) => {
    const accountId = await requireAccount(event)
    const request = keyUploadRequest(await readJson(event, KEY_UPLOAD_BODY_LIMIT))
    if (!await useRelay().preKeys.upload(accountId, request)) throw unauthorized()
    return null
  },
})
