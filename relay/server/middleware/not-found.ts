import { defineEventHandler, setResponseHeaders, setResponseStatus } from 'h3'
import { notFoundPage } from '../../app/content/not-found'
import { SITE_HEADERS, SITE_PAGES } from '../lib/website'

const PAGES = new Set(SITE_PAGES)
let page: string | undefined

/**
 * Answers every request that is neither for the API nor for a website page
 * with one static, bilingual 404 page and the site's security headers. The
 * pages are prerendered and served as files before this runs, and the
 * production server has no Vue renderer (`noVueServer`); without this, an
 * unknown path (or a case variant such as /EN) would be rendered in the
 * relay's only thread on every request.
 */
export default defineEventHandler((event) => {
  if (import.meta.dev) return
  const path = event.path.replace(/[?#].*$/, '')
  if (path === '/v1' || path.startsWith('/v1/')) return
  if ((event.method === 'GET' || event.method === 'HEAD') && PAGES.has(path.replace(/(.)\/$/, '$1'))) return
  setResponseStatus(event, 404)
  setResponseHeaders(event, { ...SITE_HEADERS, 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-cache' })
  // Built once, with the asset version of this build (nuxt.config.ts).
  page ??= notFoundPage(String(useRuntimeConfig(event).assetVersion))
  return page
})
