import { readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { startRelay, type RelayProcess } from '../support/relay'

let relay: RelayProcess

beforeAll(async () => {
  relay = await startRelay()
})

afterAll(async () => {
  expect(relay.errors()).toEqual([])
  await relay.stop()
})

const get = (path: string, headers: Record<string, string> = {}) => fetch(relay.url + path, { headers, redirect: 'manual' })
const html = async (path: string) => await (await get(path)).text()
/** The text of a page as a reader sees it: tags removed, whitespace collapsed. */
const textOf = (page: string) => page.replace(/<(script|style)\b[\s\S]*?<\/\1>/g, ' ').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/\s+/g, ' ')

const PAGES = ['/tr', '/en', '/tr/gizlilik', '/en/privacy', '/tr/destek', '/en/support']
const PUBLIC_DIR = fileURLToPath(new URL('../../.output/public', import.meta.url))
const SOURCE_PUBLIC_DIR = fileURLToPath(new URL('../../public', import.meta.url))

function filesUnder(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    return statSync(path).isDirectory() ? filesUnder(path) : [path]
  })
}

describe('website', () => {
  it('redirects / by Accept-Language', async () => {
    const cases: Array<[string | undefined, string]> = [
      ['tr-TR,tr;q=0.9,en-US;q=0.8', '/tr'],
      ['en-GB,en;q=0.9,tr;q=0.5', '/en'],
      ['de-DE,tr;q=0.7', '/tr'],
      ['de-DE,fr;q=0.8', '/en'],
      [undefined, '/en'],
    ]
    for (const [language, target] of cases) {
      const response = await get('/', language ? { 'accept-language': language } : {})
      expect(response.status, language).toBe(302)
      expect(response.headers.get('location'), language).toBe(target)
      expect(response.headers.get('vary')).toBe('accept-language')
    }
  })

  it('serves every page in both languages with its key headings', async () => {
    const pages: Array<[string, string, string[]]> = [
      ['/tr', 'tr', ['Araya kimse giremez.', 'Mesaj senin ucunda kilitlenir, yalnızca onun ucunda açılır.', 'Bildirim yok. Bilerek.', 'Sessiz kalan sohbet kendini siler.', 'Az iz bırakır. Kontrol sende kalır.', 'Hattın ucunda gerçekten o mu var?', 'Hattın iki ucu da sizde.']],
      ['/en', 'en', ['No one gets in between.', 'Sealed at your end. Opened only at theirs.', 'No notifications. On purpose.', 'A quiet conversation deletes itself.', 'Fewer traces. You stay in control.', 'Is it really them on the other end?', 'Both ends of the line are yours.']],
      ['/tr/gizlilik', 'tr', ['Gizlilik politikası', '180 gün', 'Güvenlik']],
      ['/en/privacy', 'en', ['Privacy policy', 'at most 14 days', 'Security']],
      ['/tr/destek', 'tr', ['Destek', 'support@us2us.app', 'Sık sorulan sorular']],
      ['/en/support', 'en', ['Support', 'support@us2us.app', 'Frequently asked questions']],
    ]
    for (const [path, lang, texts] of pages) {
      const response = await get(path)
      expect(response.status, path).toBe(200)
      const page = await response.text()
      expect(page, path).toMatch(new RegExp(`<html\\s+lang="${lang}"`))
      for (const text of texts) expect(textOf(page), `${path}: ${text}`).toContain(text)
      // One h1, a skip link to <main>, and the landmarks.
      expect(page.match(/<h1\b/g), path).toHaveLength(1)
      expect(page, path).toMatch(/<a class="skip-link" href="#main">/)
      expect(page, path).toMatch(/<main id="main"/)
      for (const landmark of ['<header', '<nav', '<footer']) expect(page, `${path} ${landmark}`).toContain(landmark)
    }
  })

  it('carries the approved copy corrections in both languages', async () => {
    const tr = textOf(await html('/tr'))
    const en = textOf(await html('/en'))
    // Ghost mode is optional.
    expect(tr).toContain('Telefon numarası istemez, bildirim göndermez; istersen sessiz kalan sohbetleri iz bırakmadan siler.')
    expect(en).toContain('It never asks for your phone number, sends no notifications, and, if you want, erases quiet conversations without a trace.')
    // The account backup holds no messages.
    expect(tr).toContain('Şifreli hesap yedeği')
    expect(tr).toContain('Mesajlar yedeğe girmez.')
    expect(en).toContain('Encrypted account backup')
    expect(en).toContain('Messages are never included.')
    expect(tr).not.toMatch(/Sohbetlerini parolalı/)
    expect(en).not.toMatch(/Export your chats/)
    // A green dot means "active" (PROTOCOL.md §8.6), not "active just now".
    expect(tr).toMatch(/deniz\s*Aktif/)
    expect(en).toMatch(/deniz\s*Active/)
    // The 14-day status and the ghost windows stay as designed.
    expect(tr).toContain('Alıcı 14 gün içinde uygulamayı açmadı; mesaj ona hiç ulaşmadı.')
    expect(en).toContain('They didn’t open the app within 14 days, so it never reached them.')
    for (const window of ['1 saat', '24 saat', '7 gün']) expect(tr).toContain(window)
    // The apps are not out yet.
    expect(tr).toContain('Yakında iOS ve Android’de.')
    expect(en).toContain('Coming soon to iOS and Android.')
  })

  it('states what the relay can see and the known limits as PROTOCOL.md §9 does', async () => {
    const tr = textOf(await html('/tr/gizlilik'))
    const en = textOf(await html('/en/privacy'))
    // Lookups are authenticated: the relay learns who looked up whom, once per new contact.
    expect(tr).toContain('her yeni kişi için bir kez, hangi hesabın hangi hesabı sorguladığını öğrenir')
    expect(en).toContain('the relay learns, once per new contact, which account looked up which')
    expect(tr).not.toContain('kimin kime yazdığını')
    expect(en).not.toContain('who writes to whom')
    // Cameras and modified apps, iOS timing, a hard relay crash.
    for (const text of ['Dürüst olmak gerekirse', 'ekranın fotoğrafını çekebilir', 'iPhone’da silme bir sonraki açılışa kalabilir', '“Gönderildi”yi görür ama “İletildi”yi görmez']) expect(tr).toContain(text)
    for (const text of ['To be honest', 'photograph the screen', 'the deletion may wait until the app is next opened', 'sees “Sent” without “Delivered”']) expect(en).toContain(text)
    // Deletion and the 7-day disaster-recovery backups agree.
    expect(tr).toContain('şifreli felaket kurtarma yedeklerinde en fazla 7 gün daha kalabilir')
    expect(en).toContain('can remain in encrypted disaster-recovery backups for up to 7 more days')
    // The theme choice stays in the browser.
    expect(tr).toContain('tema seçiminiz yalnızca tarayıcınızda (localStorage) saklanır')
    expect(en).toContain('your theme choice is kept only in your browser (localStorage)')
  })

  it('shows the store buttons as "coming soon", not as links', async () => {
    for (const [path, soon] of [['/tr', 'Yakında'], ['/en', 'Coming soon']] as const) {
      const page = await html(path)
      const stores = [...page.matchAll(/<a class="btn btn-(?:primary|secondary) store"([^>]*)>([\s\S]*?)<\/a>/g)]
      expect(stores, path).toHaveLength(4) // hero and call to action
      for (const [, attributes, content] of stores) {
        expect(attributes).toContain('aria-disabled="true"')
        expect(attributes).not.toContain('href')
        expect(textOf(content!)).toContain(soon)
      }
      expect(page).not.toMatch(/apps\.apple\.com|play\.google\.com/)
    }
  })

  it('works without JavaScript: all statuses, a static countdown, no canvas, an example label', async () => {
    for (const path of ['/tr', '/en']) {
      const page = await html(path)
      expect(page.match(/<li class="tone-\w+ status">/g), path).toHaveLength(4)
      expect(page).not.toMatch(/is-active|is-stepping/)
      expect(page).toContain('<span data-countdown>23:41:07</span>')
      expect(page).not.toContain('<canvas')
      // The QR code and the safety number are an illustration.
      expect(page).toContain('<div class="verify-body" aria-hidden="true">')
      expect(textOf(page)).toMatch(path === '/tr' ? /Örnek görünüm; QR kodu ve numara gerçek değil\./ : /Example only; not a real QR code or safety number\./)
    }
  })

  it('explains, in both languages, that there are no push notifications', async () => {
    const pages: Array<[string, string]> = [
      ['/tr', 'Bildirim yok. Bilerek.'],
      ['/en', 'No notifications. On purpose.'],
      ['/tr/destek', 'us2us neden bildirim göndermiyor?'],
      ['/en/support', 'Why doesn’t us2us send notifications?'],
      ['/tr/gizlilik', 'Apple ve Google hiç devrede değildir'],
      ['/en/privacy', 'Apple and Google are not involved at all'],
    ]
    for (const [path, text] of pages) {
      const page = await html(path)
      expect(textOf(page), path).toContain(text)
      expect(page, path).not.toMatch(/APNs|FCM|push token|bildirim belirteci/i)
    }
  })

  it('links the footer to the privacy, security and support pages, and the language switch to the other version', async () => {
    for (const [path, privacy, security, support, other] of [
      ['/tr', '/tr/gizlilik', '/tr/gizlilik#guvenlik', '/tr/destek', '/en'],
      ['/en', '/en/privacy', '/en/privacy#security', '/en/support', '/tr'],
      ['/tr/gizlilik', '/tr/gizlilik', '/tr/gizlilik#guvenlik', '/tr/destek', '/en/privacy'],
    ] as const) {
      const page = await html(path)
      const footer = page.slice(page.indexOf('<footer'))
      for (const href of [privacy, security, support]) expect(footer, `${path} → ${href}`).toContain(`href="${href}"`)
      expect(page).toMatch(new RegExp(`<a class="seg-opt" href="${other}" hreflang="\\w\\w" lang="\\w\\w" data-lang-link>`))
    }
    // The security section answers to both anchors in both languages.
    for (const path of ['/tr/gizlilik', '/en/privacy']) {
      const page = await html(path)
      expect(page, path).toMatch(/id="guvenlik"/)
      expect(page, path).toMatch(/id="security"/)
    }
  })

  it('names the licence in the footer and links it to the licences, served by the site itself', async () => {
    for (const [path, note, target] of [['/tr', 'AGPL-3.0 · Kaynak kodu yakında', '/tr/destek#lisanslar'], ['/en/privacy', 'AGPL-3.0 · Source code coming soon', '/en/support#licences']] as const) {
      const footer = (await html(path)).split('<footer')[1]!
      expect(footer, path).toContain(`href="${target}"`)
      expect(textOf(footer), path).toContain(note)
      expect(textOf(footer), path).not.toMatch(/Açık kaynak|Open source/)
    }
    for (const path of ['/tr/destek', '/en/support']) {
      const page = await html(path)
      expect(page, path).toMatch(/id="lisanslar"/)
      expect(page, path).toMatch(/id="licences"/)
      for (const licence of ['/fonts/OFL-Inter.txt', '/fonts/OFL-Doto.txt', '/fonts/OFL-MartianMono.txt', '/licenses/phosphor-icons.txt', '/licenses/three.txt']) {
        expect(page, `${path} ${licence}`).toContain(`href="${licence}"`)
      }
    }
  })

  it('has per-language titles, descriptions, canonical and hreflang links', async () => {
    for (const [path, title] of [['/tr', 'us2us · Araya kimse giremez'], ['/en', 'us2us · No one gets in between'], ['/en/privacy', 'Privacy policy · us2us']] as const) {
      const page = await html(path)
      expect(page).toContain(`<title>${title}</title>`)
      expect(page).toMatch(/<meta name="description" content="[^"]{40,}">/)
      expect(page).toContain(`<link rel="canonical" href="https://us2us.app${path}">`)
      expect(page).toContain(`<meta property="og:title" content="${title}">`)
      expect(page).toContain(`<meta property="og:url" content="https://us2us.app${path}">`)
      for (const hreflang of ['tr', 'en', 'x-default']) expect(page).toMatch(new RegExp(`<link rel="alternate" hreflang="${hreflang}" href="https://us2us.app/`))
      expect(page).toContain('<link rel="icon" type="image/svg+xml" href="/favicon.svg">')
    }
  })

  it('sends a strict CSP and loads only its own files: no inline styles, no inline scripts, no other origins', async () => {
    for (const path of PAGES) {
      const response = await get(path)
      const csp = response.headers.get('content-security-policy')!
      expect(csp).toContain('default-src \'none\'')
      expect(csp).toContain('script-src \'self\';')
      expect(csp).toContain('style-src \'self\';')
      expect(csp).toContain('font-src \'self\'')
      expect(csp).toContain('frame-ancestors \'none\'')
      expect(csp).toContain('require-trusted-types-for \'script\'; trusted-types \'none\'')
      expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|inline-speculation-rules|https?:/)
      expect(response.headers.get('x-content-type-options')).toBe('nosniff')
      expect(response.headers.get('referrer-policy')).toBe('no-referrer')
      // The HTML names the current asset versions, so it is revalidated on every visit.
      expect(response.headers.get('cache-control'), path).toBe('no-cache')
      const page = await response.text()
      expect(page, path).not.toMatch(/\sstyle=|<style|speculationrules/)
      // Scripts: our own two files, nothing inline.
      const scripts = [...page.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
      expect(scripts, path).toHaveLength(2)
      for (const [, attributes, body] of scripts) {
        expect(attributes, path).toMatch(/ src="\/js\/(theme|site)\.js\?v=[\w-]{12}"/)
        expect(body, path).toBe('')
      }
      expect(page.indexOf('/js/theme.js'), 'theme script before the stylesheet').toBeLessThan(page.indexOf('/css/site.css'))
      const urls = [...page.matchAll(/(?:src|href)="([^"]+)"/g)].map(match => match[1]!)
      for (const url of urls) {
        if (url.startsWith('mailto:') || url.startsWith('#')) continue
        if (/^https?:/.test(url)) expect(url, path).toMatch(/^https:\/\/us2us\.app\//)
        else expect(url, path).toMatch(/^\//)
      }
    }
  })

  it('serves every file the pages and the stylesheet refer to, with the right content type', async () => {
    const local = new Set<string>()
    for (const path of PAGES) {
      for (const [, url] of (await html(path)).matchAll(/(?:src|href)="(\/[^"#]*)/g)) local.add(url!)
    }
    const stylesheet = [...local].find(url => url.startsWith('/css/site.css?v='))!
    expect(stylesheet).toBeDefined()
    const css = await (await get(stylesheet)).text()
    for (const [, url] of css.matchAll(/url\('?(\/[^')]+)'?\)/g)) local.add(url!)
    const version = stylesheet.split('?v=')[1]!
    local.add(`/js/scene.js?v=${version}`)
    local.add('/js/vendor/three-0.160.0.module.min.js')
    const types: Array<[RegExp, RegExp]> = [
      [/\.css$/, /^text\/css/],
      [/\.js$/, /^(text|application)\/javascript/],
      [/\.woff2$/, /^font\/woff2$/],
      [/\.svg$/, /^image\/svg\+xml/],
      [/\.txt$/, /^text\/plain/],
      [/^\/(tr|en)(\/|$)/, /^text\/html/],
    ]
    for (const url of local) {
      const response = await get(url)
      await response.arrayBuffer()
      expect(response.status, url).toBe(200)
      const path = url.split('?')[0]!
      const type = types.find(([pattern]) => pattern.test(path))
      if (type) expect(response.headers.get('content-type'), url).toMatch(type[1])
      if (/^\/(fonts|js|css|licenses)\//.test(url)) expect(response.headers.get('x-content-type-options'), url).toBe('nosniff')
      // Every file a page names is versioned (by name or by ?v=), so it is cached for good.
      if (/^\/(fonts|js|css)\//.test(url)) expect(response.headers.get('cache-control'), url).toBe('public, max-age=31536000, immutable')
    }
    expect([...local].filter(url => url.endsWith('.woff2')).length).toBeGreaterThanOrEqual(9)
    // All four of our own files share the version, and the pages use no other.
    for (const url of local) if (/^\/(js|css)\/(?!vendor\/)/.test(url)) expect(url, url).toMatch(new RegExp(`\\?v=${version}$`))
    expect(version).toMatch(/^[\w-]{12}$/)
  })

  it('sends the page headers on every spelling of a page file, and no Nuxt app manifest', async () => {
    for (const path of ['/tr/index.html', '/en/privacy/index.html', '/tr/index.html.br', '/en/index.html.gz', '/tr/gizlilik/']) {
      const response = await get(path, { 'accept-encoding': 'br, gzip' })
      await response.arrayBuffer()
      if (response.status !== 200) continue
      expect(response.headers.get('content-security-policy'), path).toContain('frame-ancestors \'none\'')
      expect(response.headers.get('x-content-type-options'), path).toBe('nosniff')
      expect(response.headers.get('referrer-policy'), path).toBe('no-referrer')
      expect(response.headers.get('cache-control'), path).toBe('no-cache')
    }
    const robots = await get('/robots.txt')
    expect(robots.status).toBe(200)
    expect(robots.headers.get('x-content-type-options')).toBe('nosniff')
    expect((await get('/_nuxt/builds/latest.json')).status).toBe(404)
    expect((await get('/logo.svg')).status).toBe(404)
  })

  it('preloads only the small Turkish cut of Inter on Turkish pages', async () => {
    const tr = await html('/tr')
    const en = await html('/en')
    expect(tr).toContain('<link rel="preload" as="font" type="font/woff2" href="/fonts/inter-v20-tr.woff2" crossorigin>')
    expect(tr).not.toContain('latin-ext')
    expect(en).not.toContain('-tr.woff2')
    for (const name of ['inter-v20', 'doto-v3', 'martian-mono-v6']) {
      expect(statSync(join(SOURCE_PUBLIC_DIR, 'fonts', `${name}-tr.woff2`)).size, name).toBeLessThan(6_000)
    }
    // The Turkish faces come after the latin-ext ones (the last matching rule is tried first).
    const css = readFileSync(join(SOURCE_PUBLIC_DIR, 'css/site.css'), 'utf8')
    expect(css.indexOf('inter-v20-tr.woff2')).toBeGreaterThan(css.indexOf('inter-v20-latin-ext.woff2'))
  })

  it('builds no third-party URL into any HTML, CSS, JS or SVG file', () => {
    const allowed = /^(https:\/\/us2us\.app(\/|$)|http:\/\/www\.w3\.org\/(2000\/svg|1999\/xhtml))/
    const files = filesUnder(PUBLIC_DIR).filter(file => /\.(html|css|m?js|svg)$/.test(file))
    expect(files.length).toBeGreaterThan(10)
    for (const file of files) {
      const content = readFileSync(file, 'utf8')
      expect(content, file).not.toMatch(/fonts\.googleapis|fonts\.gstatic|unpkg\.com|jsdelivr|cdnjs|googletagmanager|google-analytics/)
      if (file.includes(`${join('js', 'vendor')}`)) continue
      const external = [...content.matchAll(/https?:\/\/[^\s"'`)<>]+/g)].map(match => match[0]).filter(url => !allowed.test(url))
      expect(external, relative(PUBLIC_DIR, file)).toEqual([])
    }
    // three.js is the unmodified file of the pinned dependency; its only URLs are an XML
    // namespace and a link inside a console warning, neither of which is ever requested.
    const require = createRequire(import.meta.url)
    const three = readFileSync(join(dirname(require.resolve('three')), 'three.module.min.js'))
    expect(readFileSync(join(PUBLIC_DIR, 'js/vendor/three-0.160.0.module.min.js')).equals(three)).toBe(true)
    const threeUrls = new Set([...three.toString('utf8').matchAll(/https?:\/\/[^\s"'`)<>]+/g)].map(match => match[0]))
    expect([...threeUrls].sort()).toEqual(['http://www.w3.org/1999/xhtml', 'https://discourse.threejs.org/t/updates-to-lighting-in-three-js-r155/53733.'])
    // scene.js imports three.js from this origin only.
    const scene = readFileSync(join(PUBLIC_DIR, 'js/scene.js'), 'utf8')
    expect([...scene.matchAll(/import\b[^'"]*['"]([^'"]+)['"]/g)].map(match => match[1])).toEqual(['./vendor/three-0.160.0.module.min.js'])
  })

  it('keeps its own JavaScript within 36 KB and ships the font and icon licences', () => {
    const own = readdirSync(join(SOURCE_PUBLIC_DIR, 'js')).filter(name => name.endsWith('.js'))
    expect(own.sort()).toEqual(['scene.js', 'site.js', 'theme.js'])
    const bytes = own.reduce((sum, name) => sum + statSync(join(SOURCE_PUBLIC_DIR, 'js', name)).size, 0)
    expect(bytes).toBeLessThanOrEqual(36_000)
    for (const licence of ['fonts/OFL-Inter.txt', 'fonts/OFL-Doto.txt', 'fonts/OFL-MartianMono.txt', 'licenses/phosphor-icons.txt', 'licenses/three.txt']) {
      expect(statSync(join(PUBLIC_DIR, licence)).size, licence).toBeGreaterThan(500)
    }
  })

  it('answers unknown pages with a bilingual 404 in the site design', async () => {
    const response = await get('/does/not/exist', { accept: 'text/html' })
    expect(response.status).toBe(404)
    const csp = response.headers.get('content-security-policy')!
    expect(csp).toContain('default-src \'none\'')
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval/)
    const page = await response.text()
    expect(page).toContain('<html lang="en">')
    expect(page).toContain('Aradığınız sayfa bulunamadı.')
    expect(page).toContain('The page you are looking for does not exist.')
    // The same asset version as the pages.
    const version = (await html('/en')).match(/\/css\/site\.css\?v=([\w-]+)/)![1]
    expect(page).toContain(`<link rel="stylesheet" href="/css/site.css?v=${version}">`)
    expect(page).toContain(`<script type="module" src="/js/site.js?v=${version}"></script>`)
    // A heading that says what happened, not just the number.
    expect(textOf(page.match(/<h1\b[\s\S]*?<\/h1>/)![0])).toBe('404 · Sayfa bulunamadı · Not found')
    expect(page).toContain('href="/tr" hreflang="tr"')
    expect(page).toContain('data-theme-toggle')
    expect(page).not.toMatch(/\sstyle=|<style|<script>|<script(?![^>]*\ssrc="\/js\/)/)
  })

  it('serves the pages only by their exact path and only to GET and HEAD', async () => {
    expect((await get('/tr/')).status).toBe(200)
    expect((await fetch(`${relay.url}/en`, { method: 'HEAD' })).status).toBe(200)
    for (const path of ['/EN', '/en/Privacy', '/tr/destek/x']) expect((await get(path)).status, path).toBe(404)
    const posted = await fetch(`${relay.url}/en/support`, { method: 'POST', body: 'x' })
    expect(posted.status).toBe(404)
    expect(posted.headers.get('x-powered-by')).toBeNull()
  })
})
