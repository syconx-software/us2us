import { getRequestHeader, setResponseHeader } from 'h3'
import { defineApiRoute, limitByAccount, readBytes, requireAccount } from '../../../../http/api'
import {
  AVATAR_RECORD_MAX_BYTES,
  avatarETag,
  avatarRecordVersion,
  isAvatarRecordSize,
  parseAvatarVersion,
} from '../../../../lib/avatar'
import { badRequest, payloadTooLarge, staleVersion, unauthorized } from '../../../../lib/errors'
import { useRelay } from '../../../../relay'
import type { AvatarWrite } from '../../../../services/avatars'

/**
 * §4.12 The account's own avatar record for search. The relay checks the
 * length and the header and never looks further; the record is ciphertext
 * under a key derived from the username. Every write carries a version
 * above all earlier ones, so a late retry never undoes a newer write. Store
 * and delete have separate per-account budgets (§4.11): a user who has used
 * up the day's stores on picture changes can still take the picture out of
 * search at once. Nothing about these requests is logged (§4.10).
 */
export default defineApiRoute({
  /** Store or replace the record; its version comes from its header. */
  PUT: async (event) => {
    const accountId = await requireAccount(event)
    const relay = useRelay()
    limitByAccount(accountId, 'avatar-write', relay.config.rateLimits.avatarWrite)

    // Checked before a single byte of the body is read.
    const declared = getRequestHeader(event, 'content-length')
    const size = declared !== undefined && /^\d+$/.test(declared) ? Number(declared) : Number.NaN
    if (!Number.isSafeInteger(size)) throw badRequest('Content-Length is required')
    if (size > AVATAR_RECORD_MAX_BYTES) throw payloadTooLarge(AVATAR_RECORD_MAX_BYTES)
    if (!isAvatarRecordSize(size)) throw badRequest('an avatar record is 8192, 16384, 32768 or 49152 bytes long')

    const record = await readBytes(event, size)
    if (record.length !== size) throw badRequest('the body is shorter than Content-Length')
    const version = avatarRecordVersion(record)
    if (version === null) throw badRequest('not an avatar record: wrong magic or format byte, or a version outside 1..2^63-1')

    settle(await relay.avatars.store(accountId, version, record))
    setResponseHeader(event, 'etag', avatarETag(version))
    return null
  },

  /** Delete the record, if any, with a version above all earlier writes (`X-Avatar-Version`). */
  DELETE: async (event) => {
    const accountId = await requireAccount(event)
    const relay = useRelay()
    // Its own budget: a delete only frees storage, and the versions already order it.
    limitByAccount(accountId, 'avatar-delete', relay.config.rateLimits.avatarDelete)
    const version = parseAvatarVersion(getRequestHeader(event, 'x-avatar-version'))
    if (version === null) throw badRequest('X-Avatar-Version must be a decimal in 1..2^63-1')
    settle(await relay.avatars.remove(accountId, version))
    return null
  },
})

function settle(result: AvatarWrite): void {
  // Deleted while this request waited for it (or while its body arrived); nothing was kept.
  if (result.outcome === 'gone') throw unauthorized()
  if (result.outcome === 'stale') throw staleVersion(result.highest)
}
