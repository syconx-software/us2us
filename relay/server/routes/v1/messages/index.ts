import { getQuery } from 'h3'
import { defineApiRoute, holdSlot, requireAccount } from '../../../http/api'
import { encodeB64u } from '../../../lib/encoding'
import { badRequest } from '../../../lib/errors'
import { useRelay } from '../../../relay'

const DEFAULT_LIMIT = 100
const MAX_LIMIT = 500
/**
 * A page carries at most this many envelope bytes (and `more: true` for the
 * rest), so that one fetch costs a few MiB of memory, not 500 × 64 KiB.
 */
const PAGE_BYTES = 4 * 1024 * 1024
/** How long a fetch waits for envelopes accepted just before it to reach the mailbox. */
const FLUSH_WAIT_MS = 2000

/**
 * §4.6 Fetch stored envelopes, oldest first. They stay until acknowledged or
 * expired. One fetch per account at a time.
 */
export default defineApiRoute({
  GET: async (event) => {
    const accountId = await requireAccount(event)
    const { limit: rawLimit } = getQuery(event)
    const limit = rawLimit === undefined ? DEFAULT_LIMIT : Number(rawLimit)
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
      throw badRequest(`limit must be an integer in 1..${MAX_LIMIT}`)
    }
    const accountKey = accountId.toString('hex')
    // Its response is built in memory (up to PAGE_BYTES) and held until the client has read it.
    holdSlot(event, `fetch:${accountKey}`, 1, 'another fetch of this mailbox is still running')

    const relay = useRelay()
    await relay.persister.flushed(accountKey, Date.now() + FLUSH_WAIT_MS)
    const { envelopes, more } = await relay.mailbox.page(accountId, limit, PAGE_BYTES)
    return {
      messages: envelopes.map(row => ({
        id: encodeB64u(row.id),
        serverTimestamp: row.serverTimestamp,
        envelope: encodeB64u(row.body),
      })),
      more,
    }
  },
})
