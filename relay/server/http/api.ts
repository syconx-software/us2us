import {
  defineEventHandler,
  getRequestHeader,
  getRouterParam,
  setResponseHeader,
  setResponseHeaders,
  setResponseStatus,
  type EventHandlerRequest,
  type H3Event,
} from 'h3'
import { forwardedFor } from '../lib/client-address'
import type { Rate } from '../lib/config'
import { decodeB64uExact, parseUuid } from '../lib/encoding'
import { ApiError, badRequest, describeError, internalError, notFound, payloadTooLarge, rateLimited } from '../lib/errors'
import { ipRateKey } from '../lib/rate-limit'
import { useRelay } from '../relay'
import { authenticateToken, recordUnauthorized } from './auth'

const API_HEADERS = {
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
}

/**
 * A request whose connection makes no progress is dropped: a client that
 * stops reading a download (or stops sending an upload) would otherwise hold
 * its buffers and file handle forever, since Node's own timeouts do not
 * cover a response being written. Node fires a socket timeout during a
 * blocked write only after the write queue stayed unchanged for a second
 * period, so a stalled request goes after 15 to 30 seconds.
 */
const STALL_TIMEOUT_MS = 15_000

/**
 * Authenticated requests one account may have running at once: far more
 * than an app needs, and far fewer than the database pool's queue, so that
 * one account firing its whole per-minute burst at once cannot make every
 * other request fail.
 */
const MAX_REQUESTS_PER_ACCOUNT = 64

/**
 * Wraps a /v1 handler: sets the API headers, drops stalled connections and
 * turns every failure into the §2.1 error body. Unexpected errors are logged
 * by code only (never with the query parameters or bodies that drizzle and
 * others put in messages) and answered with 500 `internal`. Every 401 counts
 * against the client's failed-token budget (§4.11).
 */
export function defineApiHandler<T>(handler: (event: H3Event<EventHandlerRequest>) => Promise<T> | T) {
  return defineEventHandler(async (event) => {
    setResponseHeaders(event, API_HEADERS)
    const { res } = event.node
    res.setTimeout(STALL_TIMEOUT_MS, () => res.destroy())
    try {
      return await handler(event)
    } catch (error) {
      if (res.headersSent) {
        // A streamed media download failed midway; all that is left is to cut it off.
        res.destroy()
        return undefined
      }
      const apiError = error instanceof ApiError ? error : internalError()
      if (!(error instanceof ApiError)) {
        useRelay().logger.error('request failed', { route: event.context.matchedRoute?.path, error: describeError(error) })
      }
      if (apiError.status === 401) recordUnauthorized(useRelay(), clientAddress(event), apiError)
      setResponseStatus(event, apiError.status)
      if (apiError.retryAfterSeconds !== undefined) setResponseHeader(event, 'retry-after', apiError.retryAfterSeconds)
      if (apiError.headers) setResponseHeaders(event, apiError.headers)
      return apiError.toBody()
    }
  })
}

type Method = 'GET' | 'PUT' | 'POST' | 'DELETE'
type Handler = (event: H3Event<EventHandlerRequest>) => unknown

/**
 * One /v1 path with its methods. Nitro's method-suffixed route files are not
 * used because h3 resolves a wrong method on a known path by falling back
 * to a less specific route (the website, in development); this way every
 * /v1 response, including 405, is JSON in the §2.1 format.
 */
export function defineApiRoute(handlers: Partial<Record<Method, Handler>>) {
  const allow = Object.keys(handlers).join(', ')
  return defineApiHandler((event) => {
    const handler = handlers[event.method as Method]
    if (!handler) {
      setResponseHeader(event, 'allow', allow)
      throw new ApiError(405, 'bad_request', `method not allowed; use ${allow}`)
    }
    return handler(event)
  })
}

/** `/v1` and unknown paths under it answer in the API's error format, not with a web page. */
export const unknownEndpoint = defineApiHandler(() => {
  throw notFound('endpoint')
})

/** The client address, from X-Forwarded-For only when TRUST_PROXY is set. */
export function clientAddress(event: H3Event): string {
  if (useRelay().config.trustProxy) {
    const forwarded = forwardedFor(getRequestHeader(event, 'x-forwarded-for'))
    if (forwarded) return forwarded
  }
  return event.node.req.socket.remoteAddress ?? 'unknown'
}

export function limitByIp(event: H3Event, name: string, rate: Rate, cost = 1): void {
  const relay = useRelay()
  const address = clientAddress(event)
  const result = relay.limiter.take(name, ipRateKey(address), rate, cost)
  if (!result.ok) {
    relay.noteRateLimitedAddress(name, address)
    throw rateLimited(result.retryAfterSeconds)
  }
}

export function limitByAccount(accountId: Buffer, name: string, rate: Rate, cost = 1): void {
  const result = useRelay().limiter.take(name, accountId.toString('hex'), rate, cost)
  if (!result.ok) throw rateLimited(result.retryAfterSeconds)
}

/**
 * Resolves `X-Auth-Token` to an account id (§4), after the per-address limit
 * on failed token checks (§4.11). Also applies the general per-account rate
 * limit and records the coarse last-seen date.
 */
export async function requireAccount(event: H3Event): Promise<Buffer> {
  const relay = useRelay()
  const accountId = await authenticateToken(relay, clientAddress(event), getRequestHeader(event, 'x-auth-token'))
  limitByAccount(accountId, 'account', relay.config.rateLimits.account)
  holdSlot(event, accountId.toString('hex'), MAX_REQUESTS_PER_ACCOUNT)
  relay.accountCache.touch(accountId)
  return accountId
}

/**
 * Counts the request as running under `key` until its response is done
 * (or its connection gone); 429 if `max` are running already.
 */
export function holdSlot(event: H3Event, key: string, max: number, message?: string): void {
  const { inFlight } = useRelay()
  if (!inFlight.enter(key, max)) throw rateLimited(1, message)
  event.node.res.once('close', () => inFlight.leave(key))
}

export function uuidParam(event: H3Event, name: string): Buffer {
  const id = parseUuid(getRouterParam(event, name, { decode: true }))
  if (!id) throw badRequest(`${name} must be a UUID`)
  return id
}

export function bytesParam(event: H3Event, name: string, length: number): Buffer {
  const value = decodeB64uExact(getRouterParam(event, name, { decode: true }), length)
  if (!value) throw badRequest(`${name} must be ${length} bytes of unpadded base64url`)
  return value
}

/**
 * Reads the request body into memory with a hard cap. A declared
 * Content-Length above the cap is rejected before reading anything; a
 * chunked body is cut off as soon as it crosses the cap.
 */
export async function readBytes(event: H3Event, maxBytes: number): Promise<Buffer> {
  const declared = getRequestHeader(event, 'content-length')
  if (declared !== undefined) {
    const length = /^\d+$/.test(declared) ? Number(declared) : Number.NaN
    if (!Number.isSafeInteger(length)) throw badRequest('invalid Content-Length')
    if (length > maxBytes) throw payloadTooLarge(maxBytes)
  }
  const chunks: Buffer[] = []
  let total = 0
  try {
    for await (const chunk of event.node.req as AsyncIterable<Buffer>) {
      total += chunk.length
      if (total > maxBytes) throw payloadTooLarge(maxBytes)
      chunks.push(chunk)
    }
  } catch (error) {
    if (isClientAbort(error)) throw badRequest('the request body was interrupted')
    throw error
  }
  return Buffer.concat(chunks, total)
}

/** The client went away mid-request, which is routine on mobile networks and not worth an error log. */
export function isClientAbort(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code
  return code === 'ECONNRESET' || code === 'ECONNABORTED' || code === 'ERR_STREAM_PREMATURE_CLOSE'
}

export async function readJson(event: H3Event, maxBytes: number): Promise<unknown> {
  return parseJson(await readBytes(event, maxBytes))
}

/** `readJson` for a request whose body may be left out: an empty body is `undefined`. */
export async function readOptionalJson(event: H3Event, maxBytes: number): Promise<unknown> {
  const body = await readBytes(event, maxBytes)
  return body.length === 0 ? undefined : parseJson(body)
}

function parseJson(body: Buffer): unknown {
  try {
    return JSON.parse(body.toString('utf8')) as unknown
  } catch {
    throw badRequest('body must be JSON')
  }
}
