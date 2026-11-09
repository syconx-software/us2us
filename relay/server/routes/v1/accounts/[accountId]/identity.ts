import { defineApiRoute, requireAccount, uuidParam } from '../../../../http/api'
import { encodeB64u, formatUuid } from '../../../../lib/encoding'
import { notFound } from '../../../../lib/errors'
import { useRelay } from '../../../../relay'

/** §4.3 Identity lookup, used to verify sealed senders (§6.3). A suspended account is not found. */
export default defineApiRoute({
  GET: async (event) => {
    await requireAccount(event)
    const accountId = uuidParam(event, 'accountId')
    const relay = useRelay()
    const identityKey = await relay.accounts.identityKey(accountId)
    if (!identityKey || await relay.suspensions.isSuspended(accountId)) throw notFound('account')
    return { accountId: formatUuid(accountId), identityKey: encodeB64u(identityKey) }
  },
})
