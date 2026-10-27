#!/usr/bin/env node
// Creates an API-only applicant for the community UI tests. The pre-key
// signatures are placeholders: this peer never exchanges Signal messages.
// Use only with the isolated local UI relay (register PoW difficulty 0).
// Run from relay/: node ../scripts/community-ui-peer.mjs http://127.0.0.1:33318 > /tmp/community-ui-peer.json
import { randomBytes, generateKeyPairSync } from 'node:crypto'

const base = new URL(process.argv[2])
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)) {
  throw new Error('community UI tests require a loopback relay')
}
const b64 = data => data.toString('base64url')
const ec = () => {
  const { publicKey } = generateKeyPairSync('x25519')
  return b64(Buffer.concat([Buffer.from([5]), Buffer.from(publicKey.export({ format: 'jwk' }).x, 'base64url')]))
}
const pow = await fetch(new URL('/v1/pow?purpose=register', base)).then(r => r.json())
if (pow.difficulty !== 0) throw new Error('use the isolated UI relay with POW_DIFFICULTY_REGISTER=0')
const signature = b64(Buffer.alloc(64))
const response = await fetch(new URL('/v1/accounts', base), {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({
    usernameHash: b64(randomBytes(32)), identityKey: ec(), registrationId: 1234,
    signedPreKey: { keyId: 1, publicKey: ec(), signature },
    kyberLastResortKey: { keyId: 1, publicKey: b64(Buffer.concat([Buffer.from([8]), randomBytes(1568)])), signature },
    oneTimePreKeys: [], kyberOneTimePreKeys: [],
    pow: { token: pow.token, nonce: '0' },
  }),
})
if (response.status !== 201) throw new Error(`registration failed: ${response.status}`)
const account = await response.json()
console.log(JSON.stringify({ accountId: account.accountId, authToken: account.authToken }))
