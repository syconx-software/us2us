import { randomUUID } from 'node:crypto'
import { setResponseStatus } from 'h3'
import { defineApiRoute, limitByIp, readJson } from '../../../http/api'
import { KEY_UPLOAD_BODY_LIMIT } from '../../../http/limits'
import { random, sha256 } from '../../../lib/crypto'
import { encodeB64u, formatUuid, parseUuid, utcDate } from '../../../lib/encoding'
import { powInvalid, usernameTaken } from '../../../lib/errors'
import { registrationRequest } from '../../../lib/validation'
import { useRelay } from '../../../relay'

/** §4.3 Register: proof-of-work, then account, username hash and keys in one transaction. */
export default defineApiRoute({
  POST: async (event) => {
    const relay = useRelay()
    limitByIp(event, 'register', relay.config.rateLimits.register)
    const request = registrationRequest(await readJson(event, KEY_UPLOAD_BODY_LIMIT))
    // The PoW is spent even if the username turns out to be taken, so that
    // registration attempts cannot be used to probe usernames for free.
    if (!await relay.redeemPow(request.pow, 'register', null)) throw powInvalid()

    const accountId = parseUuid(randomUUID())!
    const authToken = random(32)
    const now = Date.now()
    if (!await relay.accounts.create(accountId, sha256(authToken), request, utcDate(now), now)) throw usernameTaken()

    setResponseStatus(event, 201)
    return { accountId: formatUuid(accountId), authToken: encodeB64u(authToken) }
  },
})
