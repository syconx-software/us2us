import { setResponseStatus } from 'h3'
import { defineApiRoute, limitByIp, readJson } from '../../../http/api'
import { EPHEMERAL_BATCH_BODY_LIMIT } from '../../../http/limits'
import { ephemeralBatch } from '../../../lib/validation'
import { useRelay } from '../../../relay'
import { accessKeyMatches } from '../../../services/accounts'

/**
 * §4.6 Ephemeral batch, used for presence fan-out (§8.6): one request
 * carries an envelope for every contact, unauthenticated like a sealed send.
 * Each item is checked the same way whether or not its recipient is online
 * (the access keys of all recipients are looked up in one query), sockets
 * are written only after the answer, and the answer never depends on the
 * items, so the endpoint cannot be used to find out who is online. A
 * recipient gets at most one envelope per batch (presence sends one per
 * contact), so that a batch cannot turn into 200 frames on one socket, whose
 * cost would again show in the timing. Nothing is ever stored.
 */
export default defineApiRoute({
  POST: async (event) => {
    const relay = useRelay()
    const rate = relay.config.rateLimits.ephemeral
    // Every envelope costs one token. An exhausted client is turned away
    // before its body is read; the rest of the cost is charged once the
    // number of items is known.
    limitByIp(event, 'ephemeral', rate)
    const items = ephemeralBatch(await readJson(event, EPHEMERAL_BATCH_BODY_LIMIT))
    if (items.length > 1) limitByIp(event, 'ephemeral', rate, items.length - 1)

    const unique = new Map(items.map(item => [item.recipient.toString('hex'), item.recipient]))
    const recipients = new Map((await relay.accounts.recipients([...unique.values()]))
      .map(row => [row.id.toString('hex'), row]))
    const now = Date.now()
    const delivered = new Set<string>()
    for (const { recipient, accessKey, envelope } of items) {
      const recipientKey = recipient.toString('hex')
      const target = recipients.get(recipientKey)
      if (target && accessKeyMatches(target, accessKey) && !delivered.has(recipientKey)) {
        delivered.add(recipientKey)
        relay.sessions.deliverEphemeral(recipientKey, { id: relay.envelopeIds.next(now, true), serverTimestamp: now, body: envelope })
      }
    }
    setResponseStatus(event, 202)
    return {}
  },
})
