import { defineApiRoute } from '../../http/api'

export default defineApiRoute({
  GET: () => ({ ok: true, version: String(useRuntimeConfig().relayVersion) }),
})
