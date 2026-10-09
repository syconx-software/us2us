import { defineApiRoute, readJson, requireAccount } from '../../../../http/api'
import { SMALL_BODY_LIMIT } from '../../../../http/limits'
import { accessKeyHashBody } from '../../../../lib/validation'
import { useRelay } from '../../../../relay'

/** §4.3 Set access key hash; null disables access-key delivery (everyone needs PoW). */
export default defineApiRoute({
  PUT: async (event) => {
    const accountId = await requireAccount(event)
    const accessKeyHash = accessKeyHashBody(await readJson(event, SMALL_BODY_LIMIT))
    await useRelay().accounts.setAccessKeyHash(accountId, accessKeyHash)
    return null
  },
})
