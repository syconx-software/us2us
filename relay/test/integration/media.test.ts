import { randomBytes } from 'node:crypto'
import { existsSync, readdirSync } from 'node:fs'
import { request } from 'node:http'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, b64u, eventually, register, sleep, type TestAccount } from '../support/client'
import { TestDb, uuidBytes } from '../support/db'
import { startRelay, type RelayProcess } from '../support/relay'

const MAX_BYTES = 256 * 1024

let relay: RelayProcess
let api: Api
let db: TestDb
let uploader: TestAccount

beforeAll(async () => {
  relay = await startRelay({ MEDIA_MAX_BYTES: String(MAX_BYTES) })
  api = new Api(relay.url)
  db = await TestDb.connect()
  uploader = await register(api)
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await db.close()
  await relay.stop()
})

const upload = (blob: Uint8Array, account = uploader) => api.post('/v1/media', { token: account.authToken, body: blob })
const mediaFile = (mediaId: string) => {
  const hex = Buffer.from(mediaId, 'base64url').toString('hex')
  return join(relay.mediaDir, hex.slice(0, 2), hex)
}

describe('upload and download', () => {
  it('stores a blob on disk and serves it back', async () => {
    const blob = randomBytes(100_000)
    const { status, body } = await upload(blob)
    expect(status).toBe(201)
    expect(body).toEqual({ mediaId: expect.any(String), deleteToken: expect.any(String), expiresAt: expect.any(Number) })
    expect(Buffer.from(body.mediaId, 'base64url')).toHaveLength(16)
    expect(Buffer.from(body.deleteToken, 'base64url')).toHaveLength(32)
    expect(body.expiresAt).toBeGreaterThan(Date.now() + 13.9 * 86_400_000)
    expect(body.expiresAt).toBeLessThanOrEqual(Date.now() + 14 * 86_400_000)
    expect(existsSync(mediaFile(body.mediaId))).toBe(true)

    // No authentication: the id is the capability.
    const download = await api.get(`/v1/media/${body.mediaId}`)
    expect(download.status).toBe(200)
    expect(download.headers.get('content-type')).toBe('application/octet-stream')
    expect(download.headers.get('accept-ranges')).toBe('bytes')
    expect(download.headers.get('content-length')).toBe('100000')
    expect(Buffer.compare(download.body, blob)).toBe(0)
  })

  it('serves byte ranges', async () => {
    const blob = randomBytes(1000)
    const { body } = await upload(blob)
    const range = (value: string) => api.get(`/v1/media/${body.mediaId}`, { headers: { range: value } })

    const middle = await range('bytes=100-199')
    expect(middle.status).toBe(206)
    expect(middle.headers.get('content-range')).toBe('bytes 100-199/1000')
    expect(Buffer.compare(middle.body, blob.subarray(100, 200))).toBe(0)

    const tail = await range('bytes=900-')
    expect(tail.headers.get('content-range')).toBe('bytes 900-999/1000')
    expect(Buffer.compare(tail.body, blob.subarray(900))).toBe(0)

    const suffix = await range('bytes=-10')
    expect(Buffer.compare(suffix.body, blob.subarray(990))).toBe(0)

    const clamped = await range('bytes=990-5000')
    expect(clamped.headers.get('content-range')).toBe('bytes 990-999/1000')

    const unsatisfiable = await range('bytes=1000-')
    expect(unsatisfiable.status).toBe(416)
    expect(unsatisfiable.headers.get('content-range')).toBe('bytes */1000')

    // Multiple ranges are not supported; the whole blob is sent.
    const multiple = await range('bytes=0-1,5-6')
    expect(multiple.status).toBe(200)
    expect(multiple.body).toHaveLength(1000)
  })

  it('answers 404 for unknown ids and 400 for malformed ones', async () => {
    expect((await api.get(`/v1/media/${b64u(randomBytes(16))}`)).status).toBe(404)
    expect((await api.get('/v1/media/abc')).status).toBe(400)
  })
})

describe('limits', () => {
  it('refuses blobs above MEDIA_MAX_BYTES before reading them', async () => {
    expect((await upload(randomBytes(MAX_BYTES))).status).toBe(201)
    const { status, body } = await upload(randomBytes(MAX_BYTES + 1))
    expect(status).toBe(413)
    expect(body.error.code).toBe('payload_too_large')
  })

  it('requires a Content-Length and a non-empty body', async () => {
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(randomBytes(10))
        controller.close()
      },
    })
    const chunked = await fetch(`${relay.url}/v1/media`, {
      method: 'POST',
      headers: { 'x-auth-token': uploader.authToken },
      body: stream,
      duplex: 'half',
    } as RequestInit)
    expect(chunked.status).toBe(400)
    expect((await upload(new Uint8Array())).status).toBe(400)
    expect((await api.post('/v1/media', { body: randomBytes(10) })).status).toBe(401)
  })

  it('cleans up after an interrupted upload', async () => {
    const account = await register(api)
    await new Promise<void>((resolve) => {
      const req = request(`${relay.url}/v1/media`, {
        method: 'POST',
        headers: { 'x-auth-token': account.authToken, 'content-length': '50000', 'content-type': 'application/octet-stream' },
      })
      req.on('error', () => resolve())
      req.write(randomBytes(20_000))
      setTimeout(() => {
        req.destroy()
        resolve()
      }, 200)
    })
    await eventually(async () => await db.count('media WHERE uploader = ?', [uuidBytes(account.accountId)]) === 0)
    const leftovers = readdirSync(relay.mediaDir, { recursive: true }).filter(name => String(name).endsWith('.part'))
    expect(leftovers).toEqual([])
  })
})

describe('deletion', () => {
  it('deletes with the delete token only', async () => {
    const { body } = await upload(randomBytes(500))
    const remove = (token?: string) => api.delete(`/v1/media/${body.mediaId}`, { headers: token === undefined ? {} : { 'x-delete-token': token } })

    expect((await remove()).status).toBe(400)
    expect((await remove('short')).status).toBe(400)
    const wrong = await remove(b64u(randomBytes(32)))
    expect(wrong.status).toBe(403)
    expect(wrong.body.error.code).toBe('access_denied')

    expect((await remove(body.deleteToken)).status).toBe(204)
    expect(existsSync(mediaFile(body.mediaId))).toBe(false)
    expect((await api.get(`/v1/media/${body.mediaId}`)).status).toBe(404)
    expect((await remove(body.deleteToken)).status).toBe(404)
  })

  it('keeps nothing of an upload whose account is deleted meanwhile', async () => {
    const account = await register(api)
    const accountId = uuidBytes(account.accountId)
    let finish!: () => void
    const status = new Promise<number | undefined>((resolve, reject) => {
      const req = request(`${relay.url}/v1/media`, {
        method: 'POST',
        headers: { 'x-auth-token': account.authToken, 'content-length': '50000', 'content-type': 'application/octet-stream' },
      }, (res) => {
        res.resume()
        resolve(res.statusCode)
      })
      req.on('error', reject)
      req.write(randomBytes(20_000))
      finish = () => req.end(randomBytes(30_000))
    })
    const row = await eventually(async () => (await db.query<{ id: Buffer }>('SELECT id FROM media WHERE uploader = ?', [accountId]))[0])
    const file = mediaFile(b64u(row!.id))
    await eventually(() => existsSync(`${file}.part`))

    expect((await api.delete('/v1/accounts/me', { token: account.authToken })).status).toBe(204)
    finish()
    expect(await status).toBe(401)
    expect(existsSync(file)).toBe(false)
    expect(existsSync(`${file}.part`)).toBe(false)
    expect(await db.count('media WHERE uploader = ?', [accountId])).toBe(0)
  })
})

describe('quota', () => {
  it('limits uploads per account by count and by bytes per day', async () => {
    const limited = await startRelay({ RATE_LIMIT_MEDIA_BYTES: '3000/day', RATE_LIMIT_MEDIA_UPLOADS: '3/hour' })
    try {
      const limitedApi = new Api(limited.url)
      const account = await register(limitedApi)
      const post = (size: number) => limitedApi.post('/v1/media', { token: account.authToken, body: randomBytes(size) })

      expect((await post(2000)).status).toBe(201)
      const overBytes = await post(1500)
      expect(overBytes.status).toBe(429)
      expect(overBytes.body.error.code).toBe('rate_limited')
      expect(Number(overBytes.headers.get('retry-after'))).toBeGreaterThan(0)

      expect((await post(500)).status).toBe(201)
      expect((await post(100)).status).toBe(429) // the fourth upload in the hour

      // Another account has its own quota.
      const other = await register(limitedApi)
      expect((await limitedApi.post('/v1/media', { token: other.authToken, body: randomBytes(2500) })).status).toBe(201)
    } finally {
      await limited.stop()
    }
    await sleep(10)
  })
})
