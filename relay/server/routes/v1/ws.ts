import { defineEventHandler } from 'h3'
import { defineApiHandler } from '../../http/api'
import { ApiError } from '../../lib/errors'
import { relayWebSocketHooks } from '../../realtime/websocket'

/** §4.7 WebSocket endpoint; a plain GET without an upgrade gets 426. */
export default defineEventHandler({
  handler: defineApiHandler(() => {
    throw new ApiError(426, 'bad_request', 'this endpoint requires a WebSocket upgrade')
  }),
  websocket: relayWebSocketHooks,
})
