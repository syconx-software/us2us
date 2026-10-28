import { randomUUID } from 'node:crypto'
import { setResponseStatus } from 'h3'
import { clientAddress, defineApiRoute, limitByIp, readJson } from '../../../http/api'
import { KEY_UPLOAD_BODY_LIMIT } from '../../../http/limits'
import { random, sha256 } from '../../../lib/crypto'
import { encodeB64u, formatUuid, parseUuid, utcDate } from '../../../lib/encoding'
import { powInvalid, rateLimited, usernameTaken } from '../../../lib/errors'
import { ipPrefixRateKey } from '../../../lib/rate-limit'
import { registrationRequest } from '../../../lib/validation'
import { useRelay } from '../../../relay'

const GLOBAL_KEY = 'relay'

/**
 * §4.3 Register: proof-of-work, then account, username hash and keys in one
 * transaction. Limits: per address (an IPv6 /64), per IPv6 /48, and for the
 * whole relay. The relay-wide one is only checked before the proof-of-work
 * and spent after it, so requests without a valid solution cannot use it up.
 */
export default defineApiRoute({
  POST: async (event) => {
    const relay = useRelay()
    const { rateLimits } = relay.config
    limitByIp(event, 'register', rateLimits.register)
    const prefix = relay.limiter.take('register-prefix', ipPrefixRateKey(clientAddress(event)), rateLimits.registerPrefix)
    if (!prefix.ok) throw rateLimited(prefix.retryAfterSeconds)
    const global = relay.limiter.peek('register-global', GLOBAL_KEY, rateLimits.registerGlobal)
    if (!global.ok) throw rateLimited(global.retryAfterSeconds)
    const request = registrationRequest(await readJson(event, KEY_UPLOAD_BODY_LIMIT))
    // The PoW is spent even if the username turns out to be taken, so that
    // registration attempts cannot be used to probe usernames for free.
    if (!await relay.redeemPow(request.pow, 'register', null)) throw powInvalid()
    relay.registrationLoad.record()
    const spent = relay.limiter.take('register-global', GLOBAL_KEY, rateLimits.registerGlobal)
    if (!spent.ok) throw rateLimited(spent.retryAfterSeconds)

    const accountId = parseUuid(randomUUID())!
    const authToken = random(32)
    const now = Date.now()
    if (!await relay.accounts.create(accountId, sha256(authToken), request, utcDate(now), now)) throw usernameTaken()

    setResponseStatus(event, 201)
    return { accountId: formatUuid(accountId), authToken: encodeB64u(authToken) }
  },
})
