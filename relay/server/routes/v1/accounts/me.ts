import { defineApiRoute, requireAccount } from '../../../http/api'
import { encodeB64u, formatUuid } from '../../../lib/encoding'
import { unauthorized } from '../../../lib/errors'
import { useRelay } from '../../../relay'

export default defineApiRoute({
  GET: async (event) => {
    const accountId = await requireAccount(event)
    const usernameHash = await useRelay().accounts.currentUsernameHash(accountId)
    if (!usernameHash) throw unauthorized()
    return { accountId: formatUuid(accountId), usernameHash: encodeB64u(usernameHash) }
  },

  /** §4.3 Delete account: the account, its keys, its mailbox, the media it uploaded and its avatar record. Irreversible. */
  DELETE: async (event) => {
    const accountId = await requireAccount(event)
    await useRelay().deleteAccount(accountId)
    return null
  },
})
