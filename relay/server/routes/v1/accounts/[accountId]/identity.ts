import { defineApiRoute, requireAccount, uuidParam } from '../../../../http/api'
import { encodeB64u, formatUuid } from '../../../../lib/encoding'
import { notFound } from '../../../../lib/errors'
import { useRelay } from '../../../../relay'

/** §4.3 Identity lookup, used to verify sealed senders (§6.3). */
export default defineApiRoute({
  GET: async (event) => {
    await requireAccount(event)
    const accountId = uuidParam(event, 'accountId')
    const identityKey = await useRelay().accounts.identityKey(accountId)
    if (!identityKey) throw notFound('account')
    return { accountId: formatUuid(accountId), identityKey: encodeB64u(identityKey) }
  },
})
