import { createHash } from 'node:crypto'
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { FONTS, SITE_SCRIPT, STYLESHEET, THEME_COLOR, THEME_SCRIPT, THREE_VERSION, VERSIONED_FILES, withVersion } from './app/content/assets'
import { SITE_ASSET_HEADERS, SITE_HEADERS, SITE_PAGES } from './server/lib/website'

const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }

/**
 * One version for our own stylesheet and scripts: a hash of their content.
 * The pages (and the 404 page, through runtimeConfig) refer to them as
 * `<file>?v=<version>`, so they are cached for good and a release that
 * changes any of them changes every URL. site.js passes its own `?v=` on to
 * scene.js.
 */
const ASSET_VERSION = (() => {
  const hash = createHash('sha256')
  for (const file of VERSIONED_FILES) hash.update(readFileSync(new URL(`./public${file}`, import.meta.url)))
  return hash.digest('base64url').slice(0, 12)
})()
const asset = (path: string) => withVersion(path, ASSET_VERSION)
const IMMUTABLE = 'public, max-age=31536000, immutable'
/** Page headers: the CSP and friends, and revalidation on every visit (the HTML names the current asset versions). */
const PAGE_HEADERS = { ...SITE_HEADERS, 'cache-control': 'no-cache' }

/**
 * three.js for the 3D backdrop, served from our own origin as
 * /js/vendor/three-<version>.module.min.js: the file of the pinned `three`
 * dependency, copied into a build-only directory that Nitro publishes like
 * public/ (and serves in development). Nothing minified is committed.
 */
function vendorDirectory(): string {
  const require = createRequire(import.meta.url)
  const pkg = JSON.parse(readFileSync(join(dirname(require.resolve('three')), '..', 'package.json'), 'utf8')) as { version: string }
  if (pkg.version !== THREE_VERSION) throw new Error(`three ${pkg.version} is installed, the site expects ${THREE_VERSION}`)
  const directory = fileURLToPath(new URL('./node_modules/.cache/us2us-site/vendor', import.meta.url))
  mkdirSync(directory, { recursive: true })
  copyFileSync(join(dirname(require.resolve('three')), 'three.module.min.js'), join(directory, `three-${THREE_VERSION}.module.min.js`))
  return directory
}

const fontPreload = (href: string) => ({ rel: 'preload', as: 'font', type: 'font/woff2', href, crossorigin: '' }) as const

export default defineNuxtConfig({
  compatibilityDate: '2026-10-01',
  devtools: { enabled: false },
  telemetry: false,

  // The website is static HTML plus our own small scripts and stylesheet
  // from public/ (see app/content/assets.ts): no Nuxt client bundle, no
  // payload, no import map, no inline styles or scripts, and no Vue renderer
  // in the production server (pages are rendered only at build time).
  features: { inlineStyles: false, noScripts: 'production' },
  // No app manifest either (_nuxt/builds/*.json): nothing on the site reads it.
  experimental: { payloadExtraction: false, entryImportMap: false, noVueServer: true, appManifest: false },
  app: {
    head: {
      charset: 'utf-8',
      viewport: 'width=device-width, initial-scale=1',
      meta: [
        { name: 'color-scheme', content: 'dark light' },
        { name: 'theme-color', content: THEME_COLOR },
      ],
      link: [
        { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
        { rel: 'stylesheet', href: asset(STYLESHEET) },
        fontPreload(FONTS.interLatin),
        fontPreload(FONTS.dotoLatin),
      ],
      script: [
        { src: asset(THEME_SCRIPT), tagPriority: 'critical' },
        { src: asset(SITE_SCRIPT), type: 'module' },
      ],
    },
  },

  runtimeConfig: {
    relayVersion: version,
    assetVersion: ASSET_VERSION,
  },

  // The pages are static, rendered once at build time. Every other path gets
  // a static 404 from server/middleware/not-found.ts, so nothing is rendered
  // per request. The page headers also cover every other spelling of the same
  // files (/tr/index.html, /tr/index.html.br, …), which the static handler
  // serves too.
  routeRules: {
    ...Object.fromEntries(SITE_PAGES.map(path => [path, { prerender: true, headers: { ...PAGE_HEADERS } }])),
    '/tr/**': { headers: { ...PAGE_HEADERS } },
    '/en/**': { headers: { ...PAGE_HEADERS } },
    // Fonts and three.js have versioned names, our stylesheet and scripts a
    // versioned URL (?v=, see ASSET_VERSION): all are cached for good.
    '/fonts/**': { headers: { ...SITE_ASSET_HEADERS, 'cache-control': IMMUTABLE } },
    '/js/**': { headers: { ...SITE_ASSET_HEADERS, 'cache-control': IMMUTABLE } },
    '/css/**': { headers: { ...SITE_ASSET_HEADERS, 'cache-control': IMMUTABLE } },
    '/licenses/**': { headers: { ...SITE_ASSET_HEADERS } },
    '/favicon.svg': { headers: { ...SITE_ASSET_HEADERS } },
    '/robots.txt': { headers: { ...SITE_ASSET_HEADERS } },
  },

  // In development the files change under the same ?v=, so revalidate them.
  $development: {
    routeRules: {
      '/js/**': { headers: { 'cache-control': 'no-cache' } },
      '/css/**': { headers: { 'cache-control': 'no-cache' } },
    },
  },

  hooks: {
    'nitro:config'(nitroConfig) {
      nitroConfig.publicAssets ??= []
      nitroConfig.publicAssets.push({ dir: vendorDirectory(), baseURL: '/js/vendor', maxAge: 31536000 })
    },
  },

  nitro: {
    // One long-lived process: WebSocket sessions, rate limits and the online
    // fast path live in memory, so the relay must not be clustered.
    preset: 'node-server',
    esbuild: { options: { target: 'es2023' } },
    experimental: { websocket: true },
    prerender: { crawlLinks: false, routes: [...SITE_PAGES] },
    // Precompressed .br/.gz copies of the HTML, CSS, JS and SVG files.
    compressPublicAssets: { gzip: true, brotli: true },
  },

  typescript: { strict: true },
})
