import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { ICON_LINKS, ICON_LINKS_HTML } from '../../app/content/assets'
import { invitePage } from '../../app/content/invite'
import { notFoundPage } from '../../app/content/not-found'
import { paths, site } from '../../app/content/site'
import { why, WHY_PUBLISHED, WHY_SECTION, WHY_SOURCES, whyDate, whyDocument, whyParts, type WhyPart } from '../../app/content/why'
import { SITE_PAGES } from '../../server/lib/website'

const LOCALES = ['tr', 'en'] as const
const publicFile = (path: string) => readFileSync(new URL(`../../public${path}`, import.meta.url))

/** Every parsed run of a page's text, in reading order. */
function allParts(locale: 'tr' | 'en'): WhyPart[] {
  const doc = whyDocument(locale)
  return [
    ...doc.standfirst,
    ...doc.sections.flatMap(section => section.blocks.flatMap(block => block.parts)),
    ...doc.about.flat(),
    ...doc.cta,
  ]
}

describe('"why we built it" text (app/content/why.ts)', () => {
  it('has the same sections, in the same order, in both languages', () => {
    const ids = LOCALES.map(locale => why[locale].sections.map(section => section.id))
    expect(ids[0]).toEqual(ids[1])
    expect(new Set(ids[0]).size).toBe(ids[0]!.length)
    expect(ids[0]).not.toContain(WHY_SECTION.sources)
    expect(ids[0]).not.toContain(WHY_SECTION.about)
    for (const locale of LOCALES) {
      for (const section of why[locale].sections) {
        expect(section.title.trim(), `${locale} ${section.id}`).not.toBe('')
        expect(section.paragraphs.length, `${locale} ${section.id}`).toBeGreaterThan(0)
        if (section.quote) expect(section.quote.after, `${locale} ${section.id} quote`).toBeLessThan(section.paragraphs.length)
      }
    }
  })

  it('cites only listed sources, and every source in both languages', () => {
    const all = WHY_SOURCES.map((_, index) => index + 1)
    for (const locale of LOCALES) {
      const cited = allParts(locale).flatMap(part => (part.kind === 'cite' ? [part.n] : []))
      for (const n of cited) expect(all, `${locale}: [${n}]`).toContain(n)
      expect([...new Set(cited)].sort((a, b) => a - b), locale).toEqual(all)
      // Marks follow the word they belong to.
      for (const [index, part] of allParts(locale).entries()) {
        const before = allParts(locale)[index - 1]
        if (part.kind === 'cite' && before?.kind === 'text') expect(before.text, `${locale}: space before [${part.n}]`).not.toMatch(/\s$/)
      }
    }
  })

  it('numbers the sources in the order the text first cites them, in both languages', () => {
    const all = WHY_SOURCES.map((_, index) => index + 1)
    for (const locale of LOCALES) expect(whyDocument(locale).cited, locale).toEqual(all)
  })

  it('is the final text: no placeholders, short quotations, a description and a link preview', () => {
    /** Every string in a value, however deeply nested. */
    const strings = (value: unknown): string[] => typeof value === 'string' ? [value] : value && typeof value === 'object' ? Object.values(value).flatMap(strings) : []
    for (const text of [...strings(why), ...strings(WHY_SOURCES)]) {
      expect(text, text).not.toMatch(/YER TUTUCU|PLACEHOLDER/i)
      // A quotation from someone else stays under 15 words.
      for (const [, quoted] of text.matchAll(/“([^”]*)”/g)) expect(quoted!.split(/\s+/).length, quoted).toBeLessThan(15)
    }
    for (const locale of LOCALES) {
      // Pull quotes get their quotation marks from the stylesheet.
      for (const section of why[locale].sections) if (section.quote) expect(section.quote.text, `${locale} ${section.id}`).not.toMatch(/^[“"‘']/)
      expect(why[locale].description.length, `${locale} description`).toBeGreaterThan(80)
      expect(why[locale].description.length, `${locale} description`).toBeLessThanOrEqual(200)
      expect(why[locale].social.length, `${locale} social`).toBeGreaterThan(80)
      expect(why[locale].social, `${locale} social`).not.toBe(why[locale].description)
    }
  })

  it('lists each source once, over https, with its date and language', () => {
    expect(WHY_SOURCES.length).toBeGreaterThan(0)
    expect(new Set(WHY_SOURCES.map(source => source.url)).size).toBe(WHY_SOURCES.length)
    for (const source of WHY_SOURCES) {
      const url = new URL(source.url)
      expect(url.protocol, source.url).toBe('https:')
      // Written as the browser sends it: nothing the HTML or the tests' URL scan would cut or change.
      expect(source.url, source.url).toBe(url.href)
      expect(source.url, source.url).not.toMatch(/[\s"'`<>()]/)
      expect(url.search, `${source.url}: no tracking parameters`).not.toMatch(/utm_|fbclid|gclid/)
      expect(source.publisher.trim(), source.url).not.toBe('')
      expect(source.title.trim(), source.url).not.toBe('')
      expect(source.date, source.url).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(Number.isNaN(Date.parse(source.date)), source.url).toBe(false)
      expect(source.date <= WHY_PUBLISHED, `${source.url} is dated after the statement`).toBe(true)
      expect(['tr', 'en']).toContain(source.lang)
    }
  })

  it('links inline only to pages of this site', () => {
    for (const locale of LOCALES) {
      for (const part of allParts(locale)) {
        if (part.kind === 'link') expect(SITE_PAGES, `${locale}: ${part.href}`).toContain(part.href.replace(/#.*$/, ''))
      }
      for (const link of why[locale].cta.links) expect(SITE_PAGES).toContain(paths[locale][link.page])
    }
  })

  it('is dated 11 October 2026, in each language’s own words', () => {
    expect(whyDate(WHY_PUBLISHED, 'tr')).toBe('11 Ekim 2026')
    expect(whyDate(WHY_PUBLISHED, 'en')).toBe('11 October 2026')
  })

  it('is a page of the site in both languages, named in the header and footer', () => {
    expect(paths.tr.why).toBe('/tr/neden-yaptik')
    expect(paths.en.why).toBe('/en/why-we-built-it')
    expect(SITE_PAGES).toContain('/tr/neden-yaptik')
    expect(SITE_PAGES).toContain('/en/why-we-built-it')
    expect(site.tr.nav.why).toBe('Neden yaptık')
    expect(site.en.nav.why).toBe('Why we built it')
    // The invite and 404 pages mirror the footer.
    expect(invitePage('deniz', 'tr', 'v1')).toContain('<a class="footer-link" href="/tr/neden-yaptik">Neden yaptık</a>')
    expect(invitePage('deniz', 'en', 'v1')).toContain('<a class="footer-link" href="/en/why-we-built-it">Why we built it</a>')
    expect(notFoundPage('v1')).toContain('<a class="footer-link" href="/en/why-we-built-it">Why we built it</a>')
  })
})

describe('whyParts', () => {
  it('splits citation marks and links to our own pages out of the text', () => {
    expect(whyParts('Bildirimler boğuyor.[2] Ayrıntılar [gizlilik politikasında](/tr/gizlilik#guvenlik).')).toEqual([
      { kind: 'text', text: 'Bildirimler boğuyor.' },
      { kind: 'cite', n: 2, first: true },
      { kind: 'text', text: ' Ayrıntılar ' },
      { kind: 'link', text: 'gizlilik politikasında', href: '/tr/gizlilik#guvenlik' },
      { kind: 'text', text: '.' },
    ])
  })

  it('marks only the first citation of each source as the place to come back to', () => {
    const seen = new Set<number>()
    expect(whyParts('a[1] b[2]', seen).filter(part => part.kind === 'cite')).toEqual([{ kind: 'cite', n: 1, first: true }, { kind: 'cite', n: 2, first: true }])
    expect(whyParts('c[1][3]', seen).filter(part => part.kind === 'cite')).toEqual([{ kind: 'cite', n: 1, first: false }, { kind: 'cite', n: 3, first: true }])
    // Each first citation is unique on the page, so its id is too.
    for (const locale of LOCALES) {
      const firsts = allParts(locale).flatMap(part => (part.kind === 'cite' && part.first ? [part.n] : []))
      expect(new Set(firsts).size, locale).toBe(firsts.length)
    }
  })

  it('leaves other brackets, and links to other sites, as plain text', () => {
    for (const text of ['[YER TUTUCU] metin', '[a](https://example.com)', '[1a]', 'a [ b ] c', '[x](relative)']) {
      expect(whyParts(text), text).toEqual([{ kind: 'text', text }])
    }
  })
})

describe('site icons', () => {
  it('are named by the invite and 404 pages as by the others', () => {
    expect(ICON_LINKS_HTML).toBe([
      '<link rel="icon" href="/favicon.ico" sizes="32x32">',
      '<link rel="icon" type="image/svg+xml" href="/favicon.svg">',
      '<link rel="apple-touch-icon" href="/apple-touch-icon.png">',
    ].join('\n'))
    expect(invitePage('deniz', 'tr', 'v1')).toContain(ICON_LINKS_HTML)
    expect(notFoundPage('v1')).toContain(ICON_LINKS_HTML)
  })

  it('ship as files: an SVG with nothing to fetch, an ICO of 16, 32 and 48 px, a 180 px touch icon', () => {
    for (const link of ICON_LINKS) expect(publicFile(link.href).length, link.href).toBeGreaterThan(200)
    const svg = publicFile('/favicon.svg').toString('utf8')
    expect(svg).toMatch(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 32 32">/)
    expect([...svg.matchAll(/https?:\/\/[^"]+/g)].map(match => match[0])).toEqual(['http://www.w3.org/2000/svg'])
    expect(svg).not.toMatch(/<script|<image|href=|@import/)
    // ICONDIR: reserved 0, type 1, count; then one 16-byte entry per image (width, height; 0 = 256).
    const ico = publicFile('/favicon.ico')
    expect(ico.readUInt16LE(0)).toBe(0)
    expect(ico.readUInt16LE(2)).toBe(1)
    const sizes = Array.from({ length: ico.readUInt16LE(4) }, (_, index) => [ico[6 + index * 16], ico[7 + index * 16]])
    expect(sizes).toEqual([[16, 16], [32, 32], [48, 48]])
    // PNG: the IHDR chunk holds the width and height.
    const png = publicFile('/apple-touch-icon.png')
    expect(png.subarray(1, 4).toString('latin1')).toBe('PNG')
    expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([180, 180])
  })
})
