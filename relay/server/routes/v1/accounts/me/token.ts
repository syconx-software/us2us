import { getRequestHeader } from 'h3'
import { defineApiRoute, readOptionalJson, requireAccount } from '../../../../http/api'
import { SMALL_BODY_LIMIT } from '../../../../http/limits'
import { random, sha256 } from '../../../../lib/crypto'
import { decodeB64uExact, encodeB64u } from '../../../../lib/encoding'
import { badRequest, unauthorized } from '../../../../lib/errors'
import { AUTH_TOKEN_LENGTH, tokenRotationBody } from '../../../../lib/validation'
import { CLOSE_UNAUTHORIZED } from '../../../../realtime/sessions'
import { useRelay } from '../../../../relay'

/**
 * §4.3 Rotate auth token, used right after a restore on a new phone. The
 * client sends the new token, which it has stored before asking, and gets
 * 204: a lost response leaves it with a token that works, and asking again
 * with the new token succeeds without changing anything. An empty body is
 * the deprecated form for older clients, in which the relay chooses the
 * token and returns it. The old token stops working at once (there is no
 * grace period), and a WebSocket opened with it is closed with 4401; its
 * unacknowledged envelopes are kept for the new token.
 */
export default defineApiRoute({
  POST: async (event) => {
    const accountId = await requireAccount(event)
    // requireAccount has accepted the header, so it decodes.
    const current = sha256(decodeB64uExact(getRequestHeader(event, 'x-auth-token'), AUTH_TOKEN_LENGTH)!)
    const chosen = tokenRotationBody(await readOptionalJson(event, SMALL_BODY_LIMIT))
    const authToken = chosen ?? random(AUTH_TOKEN_LENGTH)
    const relay = useRelay()
    const outcome = await relay.accounts.rotateAuthTokenHash(accountId, current, sha256(authToken))
    if (outcome === 'stale' || outcome === 'gone') throw unauthorized()
    if (outcome === 'taken') throw badRequest('newAuthToken cannot be used; generate a new one')
    if (outcome === 'replaced') {
      relay.accountCache.forget(accountId)
      relay.sessions.disconnect(accountId.toString('hex'), CLOSE_UNAUTHORIZED, 'token rotated', false)
    }
    return chosen ? null : { authToken: encodeB64u(authToken) }
  },
})
