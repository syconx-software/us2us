import { defineApiRoute, limitByAccount, requireAccount, uuidParam } from '../../../http/api'
import { encodeB64u, formatUuid } from '../../../lib/encoding'
import { notFound } from '../../../lib/errors'
import { useRelay } from '../../../relay'

/**
 * §4.5 Fetch a pre-key bundle, consuming one EC and one Kyber one-time key.
 * One-time keys are handed out per target account only at RATE_LIMIT_KEY_TARGET
 * (whoever asks); past it the bundle carries the signed and last-resort keys
 * only, so many accounts together cannot drain a user's one-time keys.
 */
export default defineApiRoute({
  GET: async (event) => {
    const requester = await requireAccount(event)
    const relay = useRelay()
    limitByAccount(requester, 'key-fetch', relay.config.rateLimits.keyFetch)
    const accountId = uuidParam(event, 'accountId')
    const budget = relay.limiter.take('key-target', accountId.toString('hex'), relay.config.rateLimits.keyTarget)
    const bundle = await relay.preKeys.claimBundle(accountId, budget.ok)
    if (!bundle) throw notFound('account')
    const { signedPreKey, kyberPreKey, oneTimePreKey } = bundle
    return {
      accountId: formatUuid(accountId),
      deviceId: 1,
      registrationId: bundle.registrationId,
      identityKey: encodeB64u(bundle.identityKey),
      signedPreKey: {
        keyId: signedPreKey.keyId,
        publicKey: encodeB64u(signedPreKey.publicKey),
        signature: encodeB64u(signedPreKey.signature),
      },
      kyberPreKey: {
        keyId: kyberPreKey.keyId,
        publicKey: encodeB64u(kyberPreKey.publicKey),
        signature: encodeB64u(kyberPreKey.signature),
      },
      oneTimePreKey: oneTimePreKey && { keyId: oneTimePreKey.keyId, publicKey: encodeB64u(oneTimePreKey.publicKey) },
    }
  },
})
