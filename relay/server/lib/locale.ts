export type Locale = 'tr' | 'en'

/**
 * Picks Turkish or English from an Accept-Language header, honouring
 * q-values; anything that is not Turkish falls back to English.
 */
export function preferredLocale(acceptLanguage: string | undefined): Locale {
  let best: { locale: Locale, q: number } = { locale: 'en', q: 0 }
  for (const part of (acceptLanguage ?? '').split(',')) {
    const [tag = '', ...params] = part.trim().toLowerCase().split(';')
    const qParam = params.map(p => p.trim()).find(p => p.startsWith('q='))
    const q = qParam === undefined ? 1 : Number(qParam.slice(2))
    if (!Number.isFinite(q) || q <= best.q) continue
    if (tag === 'tr' || tag.startsWith('tr-')) best = { locale: 'tr', q }
    else if (tag === 'en' || tag.startsWith('en-') || tag === '*') best = { locale: 'en', q }
  }
  return best.locale
}
