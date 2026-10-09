import { defineApiRoute, requireAccount } from '../../../http/api'
import { unauthorized } from '../../../lib/errors'
import { useRelay } from '../../../relay'

export default defineApiRoute({
  GET: async (event) => {
    const accountId = await requireAccount(event)
    const status = await useRelay().preKeys.status(accountId)
    if (!status) throw unauthorized()
    return status
  },
})
