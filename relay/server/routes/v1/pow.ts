import { getQuery } from 'h3'
import { defineApiRoute, limitByIp } from '../../http/api'
import { formatUuid, parseUuid } from '../../lib/encoding'
import { badRequest } from '../../lib/errors'
import { useRelay } from '../../relay'

/** §4.2: a stateless challenge for registration or for a first message to `to`. */
export default defineApiRoute({
  GET: (event) => {
    const relay = useRelay()
    limitByIp(event, 'pow', relay.config.rateLimits.pow)
    const { purpose, to } = getQuery(event)
    if (purpose === 'register') return relay.pow.issue('register', null, Date.now())
    if (purpose === 'message') {
      const recipient = parseUuid(to)
      if (!recipient) throw badRequest('to must be the recipient account id')
      return relay.pow.issue('message', formatUuid(recipient), Date.now())
    }
    throw badRequest('purpose must be "register" or "message"')
  },
})
