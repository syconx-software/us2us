import type { Hooks, Peer } from 'crossws'
import { authenticateToken } from '../http/auth'
import { resolveClientAddress } from '../lib/client-address'
import { ApiError, describeError, internalError, notFound, rateLimited } from '../lib/errors'
import { ipRateKey } from '../lib/rate-limit'
import { envelopeIds } from '../lib/validation'
import { useRelay } from '../relay'
import type { LiveSession, SessionSocket } from './sessions'

const WS_PATH = '/v1/ws'

const CLOSE_INVALID_PAYLOAD = 1007
const CLOSE_POLICY_VIOLATION = 1008
const OPEN = 1
const PONG_FRAME = JSON.stringify({ type: 'pong' })

type UpgradeRequest = Parameters<Hooks['upgrade']>[0]

/**
 * Builds the HTTP response that refuses an upgrade. crossws writes the
 * status line and headers onto the raw socket after percent-encoding header
 * values, so the body is a byte array (no automatic content-type) and only
 * header values that survive encodeURIComponent are used. crossws closes the
 * socket afterwards, which `connection: close` tells keep-alive clients.
 */
function refuse(error: ApiError): Response {
  const body = new TextEncoder().encode(JSON.stringify(error.toBody()))
  const headers: Record<string, string> = { 'content-length': String(body.byteLength), 'connection': 'close' }
  if (error.retryAfterSeconds !== undefined) headers['retry-after'] = String(error.retryAfterSeconds)
  return new Response(body, { status: error.status, headers })
}

function upgradeAddress(request: UpgradeRequest): string {
  // crossws 0.3.5 (pinned) hands hooks a proxy that keeps the Node request as `_req`.
  const peer = (request as { _req?: { socket?: { remoteAddress?: string } } })._req?.socket?.remoteAddress
  return resolveClientAddress(useRelay().config, peer, name => request.headers.get(name))
}

function socketOf(peer: Peer): SessionSocket {
  return {
    get isOpen() {
      return peer.websocket.readyState === OPEN
    },
    get bufferedAmount() {
      return peer.websocket.bufferedAmount ?? 0
    },
    send: text => peer.send(text),
    close: (code, reason) => peer.close(code, reason),
    terminate: () => peer.terminate(),
  }
}

/**
 * Application-wide upgrade hook. crossws accepts an upgrade on any path whose
 * route has no WebSocket hooks, which would let anyone hold idle sockets
 * open; every path except /v1/ws is refused here.
 */
export function refuseOtherUpgrades(request: UpgradeRequest): Response | undefined {
  try {
    if (new URL(request.url).pathname === WS_PATH) return undefined
  } catch {
    // A malformed Host header makes the URL unparsable; refuse below.
  }
  return refuse(notFound('WebSocket endpoint'))
}

/** WebSocket protocol of PROTOCOL.md §4.7. */
export const relayWebSocketHooks: Partial<Hooks> = {
  // Authentication happens here, before the 101 response: a missing or wrong
  // X-Auth-Token is answered with HTTP 401 and never becomes a socket.
  async upgrade(request) {
    try {
      const relay = useRelay()
      const address = upgradeAddress(request)
      const limit = relay.limiter.take('ws-connect', ipRateKey(address), relay.config.rateLimits.wsConnect)
      if (!limit.ok) {
        relay.noteRateLimitedAddress('ws-connect', address)
        return refuse(rateLimited(limit.retryAfterSeconds))
      }
      // A missing or unknown token is answered 401 (counted against the
      // address, §4.11), an address over that limit 429.
      const account = await authenticateToken(relay, address, request.headers.get('x-auth-token'))
      relay.accountCache.touch(account)
      request.context.account = account
    } catch (error) {
      if (error instanceof ApiError) return refuse(error)
      useRelay().logger.error('websocket upgrade failed', { error: describeError(error) })
      return refuse(internalError())
    }
  },

  // Must stay synchronous: crossws does not await hooks, and a frame or a
  // delivery may arrive right after this returns.
  open(peer) {
    const account = peer.context.account as Buffer
    peer.context.session = useRelay().sessions.open(account, socketOf(peer))
  },

  message(peer, message) {
    const session = peer.context.session as LiveSession | undefined
    if (!session || session.closed) return
    const relay = useRelay()
    session.lastFrameAt = Date.now()
    if (!relay.limiter.take('ws-frames', session.accountKey, relay.config.rateLimits.wsFrames).ok) {
      return peer.close(CLOSE_POLICY_VIOLATION, 'rate limited')
    }

    let frame: { type?: unknown, ids?: unknown }
    try {
      frame = JSON.parse(message.text()) as typeof frame
      if (typeof frame !== 'object' || frame === null) throw new TypeError('not an object')
    } catch {
      return peer.close(CLOSE_INVALID_PAYLOAD, 'invalid frame')
    }

    if (frame.type === 'ping') {
      peer.send(PONG_FRAME)
    } else if (frame.type === 'ack') {
      let ids: Buffer[]
      try {
        ids = envelopeIds(frame.ids)
      } catch {
        return peer.close(CLOSE_INVALID_PAYLOAD, 'invalid ack')
      }
      relay.sessions.acknowledgeFrame(session, ids)
    }
    // Unknown frame types are ignored so that newer clients work with this relay.
  },

  close(peer) {
    const session = peer.context.session as LiveSession | undefined
    if (session) useRelay().sessions.closed(session)
  },

  error(_peer, error) {
    useRelay().logger.debug('websocket error', { error: describeError(error) })
  },
}
