import { generateKeyPairSync, sign, type KeyObject } from 'node:crypto'

const P = (1n << 255n) - 19n
const power = (base: bigint, exponent: bigint) => {
  let result = 1n
  for (base %= P; exponent > 0n; exponent >>= 1n) {
    if (exponent & 1n) result = result * base % P
    base = base * base % P
  }
  return result
}

/** Signing halves of the identity keys made here, by serialized public key. */
const signers = new Map<string, { privateKey: KeyObject, signBit: number }>()

/**
 * A serialized Curve25519 identity key (0x05 || u) that can sign like a
 * libsignal identity key: made from an Ed25519 key pair with u = (1 + y) /
 * (1 - y), and its signatures carry the Edwards sign bit in their top bit,
 * as XEdDSA's do. Independent of the relay's verifier, which is checked
 * against a real libsignal signature in the unit tests.
 */
export function identityKey(): Buffer {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519')
  const edwards = Buffer.from(publicKey.export({ format: 'jwk' }).x!, 'base64url')
  let y = 0n
  for (let i = 31; i >= 0; i--) y = (y << 8n) | BigInt(i === 31 ? edwards[i]! & 0x7f : edwards[i]!)
  let u = (1n + y) * power((1n - y + P) % P, P - 2n) % P
  const key = Buffer.alloc(33)
  key[0] = 0x05
  for (let i = 1; i < 33; i++, u >>= 8n) key[i] = Number(u & 0xffn)
  signers.set(key.toString('base64url'), { privateKey, signBit: edwards[31]! & 0x80 })
  return key
}

/** Signs with an identity key from `identityKey()`; null for any other key. */
export function signWithIdentity(key: Uint8Array | string, message: Uint8Array): Buffer | null {
  const signer = signers.get(typeof key === 'string' ? key : Buffer.from(key).toString('base64url'))
  if (!signer) return null
  const signature = sign(null, message, signer.privateKey)
  signature[63]! |= signer.signBit
  return signature
}
