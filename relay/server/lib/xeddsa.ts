import { createPublicKey, verify } from 'node:crypto'

const P = (1n << 255n) - 19n

function power(base: bigint, exponent: bigint): bigint {
  let result = 1n
  for (base %= P; exponent > 0n; exponent >>= 1n) {
    if (exponent & 1n) result = result * base % P
    base = base * base % P
  }
  return result
}

function littleEndian(bytes: Uint8Array): bigint {
  let value = 0n
  for (let i = bytes.length - 1; i >= 0; i--) value = (value << 8n) | BigInt(bytes[i]!)
  return value
}

/**
 * Verifies a libsignal identity-key signature (XEdDSA over Curve25519), as
 * `IdentityKey.verifySignature` does: the Montgomery u-coordinate becomes the
 * Edwards key y = (u - 1) / (u + 1) with the sign bit carried in the top bit
 * of the signature, which is then an ordinary Ed25519 signature. Node's
 * Ed25519 additionally refuses a non-reduced s, which no honest signer
 * produces. `key` is the serialized 33-byte key (0x05 || u).
 */
export function verifyIdentitySignature(key: Uint8Array, message: Uint8Array, signature: Uint8Array): boolean {
  if (key.length !== 33 || key[0] !== 0x05 || signature.length !== 64) return false
  const u = littleEndian(key.subarray(1)) & ((1n << 255n) - 1n)
  if ((u + 1n) % P === 0n) return false
  let y = (u - 1n + P) % P * power(u + 1n, P - 2n) % P
  const edwards = Buffer.alloc(32)
  for (let i = 0; i < 32; i++, y >>= 8n) edwards[i] = Number(y & 0xffn)
  edwards[31]! |= signature[63]! & 0x80
  const plain = Buffer.from(signature)
  plain[63]! &= 0x7f
  if (plain[63]! & 0xe0) return false
  try {
    const publicKey = createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: edwards.toString('base64url') }, format: 'jwk' })
    return verify(null, message, publicKey, plain)
  } catch {
    return false
  }
}
