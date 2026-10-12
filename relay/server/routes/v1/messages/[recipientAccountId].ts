import { getQuery, getRequestHeader, setResponseStatus, type H3Event } from 'h3'
import { defineApiRoute, limitByIp, readBytes, uuidParam } from '../../../http/api'
import { decodeB64uExact, encodeB64u, formatUuid } from '../../../lib/encoding'
import { accessDenied, badRequest, notFound, powInvalid, rateLimited, unavailable } from '../../../lib/errors'
import { parsePowHeader } from '../../../lib/pow'
import { ACCESS_KEY_LENGTH } from '../../../lib/validation'
import type { PendingEnvelope } from '../../../realtime/persister'
import { useRelay } from '../../../relay'
import { accessKeyMatches, type Recipient } from '../../../services/accounts'

const MAILBOX_FULL_RETRY_SECONDS = 600
const UNAVAILABLE_RETRY_SECONDS = 30

/**
 * §4.6 Sealed send. The sender is anonymous: delivery is authorized by the
 * recipient's access key or, for a first contact, by a proof-of-work bound
 * to the recipient. An online recipient gets the envelope straight from
 * memory (§1.1); otherwise the persister writes it to the mailbox. Either
 * way the envelope is only handed over in memory before the answer, and
 * every check runs for both, so the answer does not tell whether the
 * recipient is online.
 *
 * `ephemeral=1` (typing, presence, live-location ticks) is never stored and
 * needs the access key. The answer must not tell whether the recipient is
 * online, so a wrong key gets the same answer as a right one and the
 * envelope is dropped without a word.
 */
export default defineApiRoute({
  PUT: async (event) => {
    const relay = useRelay()
    limitByIp(event, 'send', relay.config.rateLimits.send)
    const recipient = uuidParam(event, 'recipientAccountId')
    const ephemeral = getQuery(event).ephemeral === '1'
    const body = await readBytes(event, relay.config.envelopeMaxBytes)
    if (body.length === 0) throw badRequest('the envelope must not be empty')

    const target = await relay.accounts.recipient(recipient)
    if (!target) throw notFound('recipient')
    const recipientKey = recipient.toString('hex')
    const now = Date.now()

    if (ephemeral) {
      const accessKey = decodeB64uExact(getRequestHeader(event, 'x-access-key'), ACCESS_KEY_LENGTH)
      if (!accessKey) throw accessDenied('ephemeral sends require X-Access-Key')
      const id = relay.envelopeIds.next(now, true)
      if (accessKeyMatches(target, accessKey)) {
        relay.sessions.deliverEphemeral(recipientKey, { id, serverTimestamp: now, body })
      }
      return accepted(event, id, now)
    }

    await authorize(event, recipient, target)
    // Storage limits apply to every send and count the recipient's envelopes
    // wherever they are (live session, persister, mailbox), so a refusal
    // says nothing about the recipient being online.
    // Only right after start-up, and for every recipient alike: the mailboxes are counted once.
    if (!relay.mailbox.loaded) {
      await relay.countMailboxes().catch(() => {
        throw unavailable(UNAVAILABLE_RETRY_SECONDS)
      })
    }
    // From here on nothing waits, so the checks and the hand-over see the same state.
    if (relay.sessions.overloaded) throw unavailable(UNAVAILABLE_RETRY_SECONDS)
    if (!relay.mailbox.admits(recipientKey, body.length, relay.sessions.inMemory(recipientKey))) {
      throw rateLimited(MAILBOX_FULL_RETRY_SECONDS, 'the recipient’s mailbox is full')
    }
    // Last, so that a refused envelope takes nothing from the free space.
    if (!relay.disk.reserve(body.length)) throw unavailable(UNAVAILABLE_RETRY_SECONDS)
    const id = relay.envelopeIds.next(now)
    const envelope: PendingEnvelope = { recipient, recipientKey, id, idKey: id.toString('hex'), serverTimestamp: now, body, acked: false }
    if (!relay.sessions.deliver(envelope)) relay.persister.enqueue([envelope])
    return accepted(event, id, now)
  },
})

function accepted(event: H3Event, id: Buffer, serverTimestamp: number) {
  setResponseStatus(event, 202)
  return { id: encodeB64u(id), serverTimestamp }
}

async function authorize(event: H3Event, recipient: Buffer, target: Recipient): Promise<void> {
  const accessKey = decodeB64uExact(getRequestHeader(event, 'x-access-key'), ACCESS_KEY_LENGTH)
  if (accessKey && accessKeyMatches(target, accessKey)) return

  const powHeader = getRequestHeader(event, 'x-pow')
  if (powHeader === undefined) throw accessDenied()
  const solution = parsePowHeader(powHeader)
  if (!solution || !await useRelay().redeemPow(solution, 'message', formatUuid(recipient))) throw powInvalid()
}
