import { badRequest } from './errors'
import { decodeB64u, decodeB64uExact, parseUuid } from './encoding'

/**
 * Validation of JSON request bodies (PROTOCOL.md §4.3, §4.5, §4.6).
 * The relay cannot verify XEdDSA signatures; it only checks shapes and sizes
 * so that what it stores is well-formed. Unknown fields are ignored for
 * forward compatibility.
 */

export const MAX_ONE_TIME_KEYS_PER_REQUEST = 100
export const MAX_ACK_IDS = 500
/** `POST /v1/messages/ephemeral` (§4.6): 1–200 items of at most 4 KiB each. */
export const EPHEMERAL_BATCH_MAX_ITEMS = 200
export const EPHEMERAL_ENVELOPE_MAX_BYTES = 4096
export const ACCESS_KEY_LENGTH = 16
/** An auth token (§4) is 32 random bytes, chosen by the relay at registration and by the client when rotating. */
export const AUTH_TOKEN_LENGTH = 32
const MIN_TOKEN_BYTE_VALUES = 16
const KEY_ID_MAX = 0xffffff
const REGISTRATION_ID_MAX = 16380
const EC_PUBLIC_KEY_LENGTH = 33
const EC_KEY_TYPE = 0x05
const SIGNATURE_LENGTH = 64
/** A serialized KEMPublicKey is a type byte plus the key (1569 bytes for Kyber/ML-KEM-1024). */
const KEM_PUBLIC_KEY_MAX = 2048

export interface OneTimePreKey {
  keyId: number
  publicKey: Buffer
}

export interface SignedKey {
  keyId: number
  publicKey: Buffer
  signature: Buffer
}

export interface PowSolution {
  token: string
  nonce: string
}

export interface RegistrationRequest {
  usernameHash: Buffer
  identityKey: Buffer
  registrationId: number
  signedPreKey: SignedKey
  kyberLastResortKey: SignedKey
  oneTimePreKeys: OneTimePreKey[]
  kyberOneTimePreKeys: SignedKey[]
  pow: PowSolution
}

export interface KeyUploadRequest {
  signedPreKey?: SignedKey
  kyberLastResortKey?: SignedKey
  oneTimePreKeys: OneTimePreKey[]
  kyberOneTimePreKeys: SignedKey[]
  replaceAll: boolean
}

export interface EphemeralItem {
  recipient: Buffer
  accessKey: Buffer
  envelope: Buffer
}

type Json = Record<string, unknown>

function object(value: unknown, path: string): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw badRequest(`${path} must be an object`)
  }
  return value as Json
}

function integer(value: unknown, path: string, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    throw badRequest(`${path} must be an integer in ${min}..${max}`)
  }
  return value
}

function bytes(value: unknown, path: string, length: number): Buffer {
  const decoded = decodeB64uExact(value, length)
  if (!decoded) throw badRequest(`${path} must be ${length} bytes of unpadded base64url`)
  return decoded
}

function ecPublicKey(value: unknown, path: string): Buffer {
  const key = bytes(value, path, EC_PUBLIC_KEY_LENGTH)
  if (key[0] !== EC_KEY_TYPE) throw badRequest(`${path} must be a serialized Curve25519 public key`)
  return key
}

function kemPublicKey(value: unknown, path: string): Buffer {
  const key = decodeB64u(value)
  if (!key || key.length < 2 || key.length > KEM_PUBLIC_KEY_MAX) {
    throw badRequest(`${path} must be a serialized KEM public key in unpadded base64url`)
  }
  return key
}

function list(value: unknown, path: string, max: number): unknown[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > max) {
    throw badRequest(`${path} must be an array of at most ${max} entries`)
  }
  return value
}

function signedEcKey(value: unknown, path: string): SignedKey {
  const key = object(value, path)
  return {
    keyId: integer(key.keyId, `${path}.keyId`, 1, KEY_ID_MAX),
    publicKey: ecPublicKey(key.publicKey, `${path}.publicKey`),
    signature: bytes(key.signature, `${path}.signature`, SIGNATURE_LENGTH),
  }
}

function signedKemKey(value: unknown, path: string): SignedKey {
  const key = object(value, path)
  return {
    keyId: integer(key.keyId, `${path}.keyId`, 1, KEY_ID_MAX),
    publicKey: kemPublicKey(key.publicKey, `${path}.publicKey`),
    signature: bytes(key.signature, `${path}.signature`, SIGNATURE_LENGTH),
  }
}

function oneTimeEcKeys(value: unknown, path: string): OneTimePreKey[] {
  return list(value, path, MAX_ONE_TIME_KEYS_PER_REQUEST).map((entry, i) => {
    const key = object(entry, `${path}[${i}]`)
    return {
      keyId: integer(key.keyId, `${path}[${i}].keyId`, 1, KEY_ID_MAX),
      publicKey: ecPublicKey(key.publicKey, `${path}[${i}].publicKey`),
    }
  })
}

function oneTimeKemKeys(value: unknown, path: string): SignedKey[] {
  return list(value, path, MAX_ONE_TIME_KEYS_PER_REQUEST).map((entry, i) => signedKemKey(entry, `${path}[${i}]`))
}

export function powSolution(value: unknown, path = 'pow'): PowSolution {
  const pow = object(value, path)
  if (typeof pow.token !== 'string' || typeof pow.nonce !== 'string') {
    throw badRequest(`${path} must contain string "token" and decimal string "nonce"`)
  }
  return { token: pow.token, nonce: pow.nonce }
}

export function registrationRequest(body: unknown): RegistrationRequest {
  const json = object(body, 'body')
  return {
    usernameHash: bytes(json.usernameHash, 'usernameHash', 32),
    identityKey: ecPublicKey(json.identityKey, 'identityKey'),
    registrationId: integer(json.registrationId, 'registrationId', 1, REGISTRATION_ID_MAX),
    signedPreKey: signedEcKey(json.signedPreKey, 'signedPreKey'),
    kyberLastResortKey: signedKemKey(json.kyberLastResortKey, 'kyberLastResortKey'),
    oneTimePreKeys: oneTimeEcKeys(json.oneTimePreKeys, 'oneTimePreKeys'),
    kyberOneTimePreKeys: oneTimeKemKeys(json.kyberOneTimePreKeys, 'kyberOneTimePreKeys'),
    pow: powSolution(json.pow),
  }
}

export function keyUploadRequest(body: unknown): KeyUploadRequest {
  const json = object(body, 'body')
  if (json.replaceAll !== undefined && typeof json.replaceAll !== 'boolean') {
    throw badRequest('replaceAll must be a boolean')
  }
  return {
    signedPreKey: json.signedPreKey === undefined ? undefined : signedEcKey(json.signedPreKey, 'signedPreKey'),
    kyberLastResortKey: json.kyberLastResortKey === undefined
      ? undefined
      : signedKemKey(json.kyberLastResortKey, 'kyberLastResortKey'),
    oneTimePreKeys: oneTimeEcKeys(json.oneTimePreKeys, 'oneTimePreKeys'),
    kyberOneTimePreKeys: oneTimeKemKeys(json.kyberOneTimePreKeys, 'kyberOneTimePreKeys'),
    replaceAll: json.replaceAll === true,
  }
}

export function usernameHashBody(body: unknown): Buffer {
  return bytes(object(body, 'body').usernameHash, 'usernameHash', 32)
}

export function accessKeyHashBody(body: unknown): Buffer | null {
  const json = object(body, 'body')
  if (!('accessKeyHash' in json)) throw badRequest('accessKeyHash is required (use null to disable)')
  return json.accessKeyHash === null ? null : bytes(json.accessKeyHash, 'accessKeyHash', 32)
}

/**
 * The body of a token rotation (§4.3): the client's new token, or null for
 * an empty body (`undefined`), the deprecated form in which the relay
 * chooses the token. A body without `newAuthToken` is refused rather than
 * taken for the old form: a client that got the field wrong would otherwise
 * be handed a token it never stored. So is a token that is plainly not
 * random, such as the zero-filled buffer of a random generator whose failure
 * went unchecked: 32 random bytes take fewer than 16 different values with a
 * probability below 10^-16.
 */
export function tokenRotationBody(body: unknown): Buffer | null {
  if (body === undefined) return null
  const token = bytes(object(body, 'body').newAuthToken, 'newAuthToken', AUTH_TOKEN_LENGTH)
  if (new Set(token).size < MIN_TOKEN_BYTE_VALUES) throw badRequest('newAuthToken must be 32 random bytes')
  return token
}

export function envelopeIds(value: unknown, path = 'ids'): Buffer[] {
  if (!Array.isArray(value) || value.length > MAX_ACK_IDS) {
    throw badRequest(`${path} must be an array of at most ${MAX_ACK_IDS} ids`)
  }
  return value.map((id, i) => bytes(id, `${path}[${i}]`, 16))
}

/**
 * An ephemeral batch. Only the shape is checked here; whether an item's
 * access key is right is decided per item and never reported (§4.6).
 */
export function ephemeralBatch(body: unknown): EphemeralItem[] {
  const { messages } = object(body, 'body')
  if (!Array.isArray(messages) || messages.length === 0 || messages.length > EPHEMERAL_BATCH_MAX_ITEMS) {
    throw badRequest(`messages must be an array of 1..${EPHEMERAL_BATCH_MAX_ITEMS} items`)
  }
  return messages.map((entry, i) => {
    const item = object(entry, `messages[${i}]`)
    const recipient = parseUuid(item.to)
    if (!recipient) throw badRequest(`messages[${i}].to must be an account id`)
    const envelope = decodeB64u(item.envelope)
    if (!envelope || envelope.length === 0 || envelope.length > EPHEMERAL_ENVELOPE_MAX_BYTES) {
      throw badRequest(`messages[${i}].envelope must be 1..${EPHEMERAL_ENVELOPE_MAX_BYTES} bytes of unpadded base64url`)
    }
    return { recipient, accessKey: bytes(item.accessKey, `messages[${i}].accessKey`, ACCESS_KEY_LENGTH), envelope }
  })
}
