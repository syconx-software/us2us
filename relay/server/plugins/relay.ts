import type { NodeOptions } from 'crossws/adapters/node'
import { refuseOtherUpgrades } from '../realtime/websocket'
import { useRelay } from '../relay'

/** How long the final shutdown step may take; Nitro's own limit (NITRO_SHUTDOWN_TIMEOUT) is 30 s. */
const STOP_BUDGET_MS = 20_000

export default defineNitroPlugin((nitroApp) => {
  // The build prerenders the website with this server; the relay must not start there.
  if (import.meta.prerender) return

  // Throws on an invalid environment, which aborts startup with the list of problems.
  const relay = useRelay()

  // Read by the node-server preset after all plugins have run.
  const websocket: NodeOptions = {
    hooks: { upgrade: refuseOtherUpgrades },
    // ws refuses larger frames itself (close 1009) instead of buffering them first.
    serverOptions: { maxPayload: relay.config.wsMaxFrameBytes },
  }
  nitroApp.h3App.options.websocket = websocket

  relay.start().catch((error: unknown) => {
    relay.logger.error('relay startup failed', { error: error instanceof Error ? error.message : 'unknown' })
    process.exit(1)
  })

  // Nitro's graceful shutdown destroys WebSocket sockets before its `close`
  // hook runs. This listener is registered earlier (plugins run before the
  // server starts), so sessions are closed cleanly and their unacknowledged
  // envelopes are handed to the persister first.
  for (const signal of ['SIGTERM', 'SIGINT'] as const) {
    process.once(signal, () => relay.beginShutdown())
  }
  nitroApp.hooks.hookOnce('close', () => relay.stop(Date.now() + STOP_BUDGET_MS))
})
