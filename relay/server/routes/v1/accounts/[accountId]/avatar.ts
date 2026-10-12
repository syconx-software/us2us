import { getRequestHeader, setResponseHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { defineApiRoute, limitByAccount, requireAccount, uuidParam } from '../../../../http/api'
import { avatarETag, ifNoneMatchNames } from '../../../../lib/avatar'
import { notFound } from '../../../../lib/errors'
import { useRelay } from '../../../../relay'

/**
 * §4.12 Fetch an account's avatar record, as a search shows it after an
 * exact directory hit (§8.10). Any authenticated account may fetch any
 * record; it opens only with the username. Rate limited per account like
 * the directory, a `304` included. An account without a record and one
 * that does not exist get the same `404`, after the same single lookup. No
 * `Range` support. Nothing about these requests is logged (§4.10).
 */
export default defineApiRoute({
  GET: async (event) => {
    const requester = await requireAccount(event)
    const relay = useRelay()
    limitByAccount(requester, 'avatar-fetch', relay.config.rateLimits.avatarFetch)
    const avatar = await relay.avatars.find(uuidParam(event, 'accountId'))
    if (!avatar) throw notFound('avatar')

    setResponseHeader(event, 'etag', avatarETag(avatar.version))
    if (ifNoneMatchNames(getRequestHeader(event, 'if-none-match'), avatar.version)) {
      setResponseStatus(event, 304)
      return null
    }
    setResponseHeaders(event, { 'content-type': 'application/octet-stream', 'content-length': avatar.record.length })
    return avatar.record
  },
})
