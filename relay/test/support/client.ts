import { createHash, randomBytes } from 'node:crypto'

export const b64u = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64url')
export const fromB64u = (value: string) => Buffer.from(value, 'base64url')
export const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest()

export interface ApiResponse<T = any> {
  status: number
  headers: Headers
  body: T
}

export interface RequestOptions {
  json?: unknown
  body?: Uint8Array | string
  headers?: Record<string, string>
  token?: string
}

/** A thin fetch wrapper; parses JSON bodies and never throws on HTTP errors. */
export class Api {
  constructor(readonly baseUrl: string) {}

  async request<T = any>(method: string, path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = { ...options.headers }
    if (options.token) headers['x-auth-token'] = options.token
    let body: BodyInit | undefined
    if (options.json !== undefined) {
      headers['content-type'] = 'application/json'
      body = JSON.stringify(options.json)
    } else if (options.body !== undefined) {
      headers['content-type'] ??= 'application/octet-stream'
      body = options.body as BodyInit
    }
    const response = await fetch(this.baseUrl + path, { method, headers, body, redirect: 'manual' })
    const type = response.headers.get('content-type') ?? ''
    const parsed = type.includes('application/json') ? await response.json() : Buffer.from(await response.arrayBuffer())
    return { status: response.status, headers: response.headers, body: parsed as T }
  }

  get<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('GET', path, options)
  }

  put<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('PUT', path, options)
  }

  post<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('POST', path, options)
  }

  delete<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('DELETE', path, options)
  }
}

/** Brute-forces a PoW nonce (tests run with tiny difficulties). */
export function solvePow(token: string, difficulty: number): string {
  const prefix = Buffer.from(token, 'utf8')
  const nonceBytes = Buffer.alloc(8)
  for (let nonce = 0n; ; nonce++) {
    nonceBytes.writeBigUInt64BE(nonce)
    const digest = createHash('sha256').update(prefix).update(nonceBytes).digest()
    if (leadingZeroBits(digest) >= difficulty) return nonce.toString()
  }
}

function leadingZeroBits(bytes: Uint8Array): number {
  let bits = 0
  for (const byte of bytes) {
    if (byte !== 0) return bits + Math.clz32(byte) - 24
    bits += 8
  }
  return bits
}

export async function solvedPow(api: Api, query: string): Promise<{ token: string, nonce: string }> {
  const { status, body } = await api.get(`/v1/pow?${query}`)
  if (status !== 200) throw new Error(`pow request failed: ${status} ${JSON.stringify(body)}`)
  return { token: body.token, nonce: solvePow(body.token, body.difficulty) }
}

export const ecKey = () => Buffer.concat([Buffer.from([0x05]), randomBytes(32)])
/** Shaped like a serialized ML-KEM-1024 KEMPublicKey; the relay only checks sizes. */
export const kemKey = () => Buffer.concat([Buffer.from([0x08]), randomBytes(1568)])
export const signature = () => randomBytes(64)

export function oneTimeKeys(firstId: number, count: number) {
  return Array.from({ length: count }, (_, i) => ({ keyId: firstId + i, publicKey: b64u(ecKey()) }))
}

export function kyberKeys(firstId: number, count: number) {
  return Array.from({ length: count }, (_, i) => ({ keyId: firstId + i, publicKey: b64u(kemKey()), signature: b64u(signature()) }))
}

export function registrationBody(overrides: Record<string, unknown> = {}) {
  return {
    usernameHash: b64u(randomBytes(32)),
    identityKey: b64u(ecKey()),
    registrationId: 1234,
    signedPreKey: { keyId: 1, publicKey: b64u(ecKey()), signature: b64u(signature()) },
    kyberLastResortKey: { keyId: 1, publicKey: b64u(kemKey()), signature: b64u(signature()) },
    oneTimePreKeys: oneTimeKeys(1, 5),
    kyberOneTimePreKeys: kyberKeys(2, 5),
    ...overrides,
  }
}

export interface TestAccount {
  accountId: string
  authToken: string
  usernameHash: string
  identityKey: string
}

export async function register(api: Api, overrides: Record<string, unknown> = {}): Promise<TestAccount> {
  const body = registrationBody(overrides)
  const pow = await solvedPow(api, 'purpose=register')
  const response = await api.post('/v1/accounts', { json: { ...body, pow } })
  if (response.status !== 201) throw new Error(`registration failed: ${response.status} ${JSON.stringify(response.body)}`)
  return { ...response.body, usernameHash: body.usernameHash, identityKey: body.identityKey }
}

/** Registers an account and gives it an access key, as an accepted contact would use. */
export async function registerWithAccessKey(api: Api): Promise<TestAccount & { accessKey: string }> {
  const account = await register(api)
  const accessKey = randomBytes(16)
  const response = await api.put('/v1/accounts/me/access-key', { token: account.authToken, json: { accessKeyHash: b64u(sha256(accessKey)) } })
  if (response.status !== 204) throw new Error(`setting the access key failed: ${response.status}`)
  return { ...account, accessKey: b64u(accessKey) }
}

/** Sends a sealed envelope with the recipient's access key. */
export function send(api: Api, recipient: TestAccount & { accessKey: string }, envelope: Uint8Array, query = '') {
  return api.put(`/v1/messages/${recipient.accountId}${query}`, {
    body: envelope,
    headers: { 'x-access-key': recipient.accessKey },
  })
}

export interface EphemeralItem {
  to: string
  accessKey: string
  envelope: string
}

/** `POST /v1/messages/ephemeral`, as a presence fan-out would use it. */
export function sendEphemeral(api: Api, messages: EphemeralItem[]) {
  return api.post('/v1/messages/ephemeral', { json: { messages } })
}

export const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

/** Polls until `check` returns a truthy value or the timeout passes. */
export async function eventually<T>(check: () => Promise<T> | T, timeoutMs = 10_000, intervalMs = 25): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = await check()
    if (value) return value
    if (Date.now() > deadline) throw new Error('condition not met in time')
    await sleep(intervalMs)
  }
}
