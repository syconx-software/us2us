import { defineApiRoute, requireAccount } from '../../../../http/api'
import { random, sha256 } from '../../../../lib/crypto'
import { encodeB64u } from '../../../../lib/encoding'
import { CLOSE_UNAUTHORIZED } from '../../../../realtime/sessions'
import { useRelay } from '../../../../relay'

/**
 * §4.3 Rotate auth token, used right after a restore on a new phone. The old
 * token stops working at once, and a WebSocket opened with it is closed with
 * 4401; its unacknowledged envelopes are kept for the new token.
 */
export default defineApiRoute({
  POST: async (event) => {
    const accountId = await requireAccount(event)
    const relay = useRelay()
    const authToken = random(32)
    await relay.accounts.setAuthTokenHash(accountId, sha256(authToken))
    relay.accountCache.forget(accountId)
    relay.sessions.disconnect(accountId.toString('hex'), CLOSE_UNAUTHORIZED, 'token rotated', false)
    return { authToken: encodeB64u(authToken) }
  },
})
