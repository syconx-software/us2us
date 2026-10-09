import { defineApiRoute, readJson, requireAccount } from '../../../http/api'
import { SMALL_BODY_LIMIT } from '../../../http/limits'
import { badRequest } from '../../../lib/errors'
import { envelopeIds } from '../../../lib/validation'
import { useRelay } from '../../../relay'

/** §4.6 Acknowledge: the relay forgets the envelopes immediately. */
export default defineApiRoute({
  POST: async (event) => {
    const accountId = await requireAccount(event)
    const body = await readJson(event, SMALL_BODY_LIMIT)
    if (typeof body !== 'object' || body === null) throw badRequest('body must be an object')
    await useRelay().sessions.acknowledge(accountId, envelopeIds((body as { ids?: unknown }).ids))
    return null
  },
})
