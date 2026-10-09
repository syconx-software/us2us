import { bytesParam, defineApiRoute, limitByAccount, requireAccount } from '../../../http/api'
import { encodeB64u, formatUuid } from '../../../lib/encoding'
import { notFound } from '../../../lib/errors'
import { useRelay } from '../../../relay'

/** §4.4 Exact-match directory lookup; there is deliberately no search. */
export default defineApiRoute({
  GET: async (event) => {
    const accountId = await requireAccount(event)
    const relay = useRelay()
    limitByAccount(accountId, 'directory', relay.config.rateLimits.directory)
    const entry = await relay.accounts.lookup(bytesParam(event, 'usernameHash', 32))
    if (!entry) throw notFound('username')
    return { accountId: formatUuid(entry.accountId), identityKey: encodeB64u(entry.identityKey) }
  },
})
