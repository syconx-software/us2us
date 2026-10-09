import { defineEventHandler, getRequestHeader, setResponseHeaders } from 'h3'
import { invitePage, inviteLocale, inviteUsername } from '../../app/content/invite'
import { preferredLocale } from '../lib/locale'
import { SITE_HEADERS } from '../lib/website'

/**
 * The invite page, /u/<username> (app/content/invite.ts, docs/INVITES.md),
 * in the visitor's language (`?lang=` from the page's language switch, else
 * Accept-Language). A string template filled in per request: no Vue, no
 * relay state, no database, so the page cannot tell which usernames exist.
 * A malformed name, or any method but GET and HEAD, falls through to the
 * static 404 (server/middleware/not-found.ts runs after this file).
 */
export default defineEventHandler((event) => {
  if (event.method !== 'GET' && event.method !== 'HEAD') return
  const [path = '', query = ''] = event.path.replace(/#.*$/, '').split('?', 2)
  const name = inviteUsername(path)
  if (name === null) return
  const locale = inviteLocale(query, preferredLocale(getRequestHeader(event, 'accept-language')))
  setResponseHeaders(event, {
    ...SITE_HEADERS,
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-cache',
    'vary': 'accept-language',
    'x-robots-tag': 'noindex, nofollow',
  })
  return invitePage(name, locale, String(useRuntimeConfig(event).assetVersion))
})
