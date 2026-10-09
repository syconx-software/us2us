import { defineEventHandler, setResponseHeaders } from 'h3'
import { APP_SITE_ASSOCIATION_HEADERS, APP_SITE_ASSOCIATION_JSON, APP_SITE_ASSOCIATION_PATH } from '../lib/app-links'

/**
 * Serves /.well-known/apple-app-site-association (server/lib/app-links.ts)
 * from memory: JSON, no redirect, GET and HEAD only. Other methods fall
 * through to the static 404 (server/middleware/not-found.ts). Middleware
 * runs in file-name order, so this answers before not-found.ts.
 */
export default defineEventHandler((event) => {
  if (event.method !== 'GET' && event.method !== 'HEAD') return
  if (event.path.replace(/[?#].*$/, '') !== APP_SITE_ASSOCIATION_PATH) return
  setResponseHeaders(event, { ...APP_SITE_ASSOCIATION_HEADERS })
  return APP_SITE_ASSOCIATION_JSON
})
