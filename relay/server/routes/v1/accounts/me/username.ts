import { defineApiRoute, limitByAccount, readJson, requireAccount } from '../../../../http/api'
import { SMALL_BODY_LIMIT } from '../../../../http/limits'
import { unauthorized, usernameTaken } from '../../../../lib/errors'
import { usernameHashBody } from '../../../../lib/validation'
import { useRelay } from '../../../../relay'

/**
 * §3.3 / §4.3 Change username. Every change reserves the previous name for
 * 14 days, so changes are rate limited per account to stop one account from
 * squatting many names.
 */
export default defineApiRoute({
  PUT: async (event) => {
    const accountId = await requireAccount(event)
    const relay = useRelay()
    const usernameHash = usernameHashBody(await readJson(event, SMALL_BODY_LIMIT))
    limitByAccount(accountId, 'username-change', relay.config.rateLimits.usernameChanges)
    const outcome = await relay.accounts.changeUsername(accountId, usernameHash, Date.now(), relay.config.usernameHoldMs)
    if (outcome === 'taken') throw usernameTaken()
    // Deleted while this request waited for it.
    if (outcome === 'gone') throw unauthorized()
    return null
  },
})
