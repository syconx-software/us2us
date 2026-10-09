import { defineEventHandler, getRequestHeader, sendRedirect, setResponseHeader } from 'h3'
import { preferredLocale } from '../lib/locale'

/** `/` sends visitors to /tr or /en according to Accept-Language. */
export default defineEventHandler((event) => {
  if (event.path !== '/' && !event.path.startsWith('/?')) return
  setResponseHeader(event, 'vary', 'accept-language')
  return sendRedirect(event, `/${preferredLocale(getRequestHeader(event, 'accept-language'))}`, 302)
})
