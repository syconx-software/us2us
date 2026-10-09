import { defineApiRoute, limitByAccount, requireAccount, uuidParam } from '../../../http/api'
import { encodeB64u, formatUuid } from '../../../lib/encoding'
import { notFound } from '../../../lib/errors'
import { useRelay } from '../../../relay'

/** §4.5 Fetch a pre-key bundle, consuming one EC and one Kyber one-time key. */
export default defineApiRoute({
  GET: async (event) => {
    const requester = await requireAccount(event)
    const relay = useRelay()
    limitByAccount(requester, 'key-fetch', relay.config.rateLimits.keyFetch)
    const accountId = uuidParam(event, 'accountId')
    const bundle = await relay.preKeys.claimBundle(accountId)
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
