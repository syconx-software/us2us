/*
 * The website's own static files (relay/public), shared by the page head
 * (nuxt.config.ts, SiteShell.vue) and the relay's static 404 page. Every
 * font, script and icon is served from us2us.app itself; the site loads
 * nothing from any other origin.
 */

export const STYLESHEET = '/css/site.css'

/** Applies the saved theme and motion choice before the first paint and wires both toggles (classic script in <head>). */
export const THEME_SCRIPT = '/js/theme.js'

/** Progressive enhancement: status steps, countdown, language hash, lazy 3D scene. */
export const SITE_SCRIPT = '/js/site.js'

/** The 3D backdrop, imported by site.js (with site.js's own `?v=`). */
export const SCENE_SCRIPT = '/js/scene.js'

/**
 * Our own files that change between releases. nuxt.config.ts hashes their
 * content into one version, and the pages refer to them as `<file>?v=<version>`
 * so they can be cached for good (`immutable`); a release with any change
 * gets new URLs. Fonts and three.js carry their version in the file name.
 */
export const VERSIONED_FILES = [STYLESHEET, THEME_SCRIPT, SITE_SCRIPT, SCENE_SCRIPT] as const

export const withVersion = (path: string, version: string) => `${path}?v=${version}`

/** three.js r160, copied from node_modules/three at build time (nuxt.config.ts). */
export const THREE_VERSION = '0.160.0'

export const FONTS = {
  interLatin: '/fonts/inter-v20-latin.woff2',
  /** Only ğ Ğ ş Ş İ, cut from the latin-ext file by scripts/fetch-fonts.py: Turkish pages need nothing else from latin-ext. */
  interTurkish: '/fonts/inter-v20-tr.woff2',
  dotoLatin: '/fonts/doto-v3-latin.woff2',
} as const

/** Background colour of the dark theme, for `theme-color` and the 404 page. */
export const THEME_COLOR = '#161826'

/**
 * The site icons, all in public/: the us2us mark on the app icon's ground.
 * favicon.svg is the one browsers use (at 24 px and smaller it draws the line
 * thicker, so it stays crisp at 16 px); favicon.ico holds 16, 32 and 48 px
 * renders of it for browsers without SVG icons; apple-touch-icon.png is the
 * iOS app icon at 180 px, for home screens. The ICO's `sizes` keeps Chrome
 * on the SVG. Every page names them: the head in nuxt.config.ts, the invite
 * page and the 404 page (ICON_LINKS_HTML).
 */
export const ICON_LINKS = [
  { rel: 'icon', href: '/favicon.ico', sizes: '32x32' },
  { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
  { rel: 'apple-touch-icon', href: '/apple-touch-icon.png' },
] as const

/** ICON_LINKS as HTML, for the string-template pages. */
export const ICON_LINKS_HTML = ICON_LINKS
  .map(link => `<link ${Object.entries(link).map(([name, value]) => `${name}="${value}"`).join(' ')}>`)
  .join('\n')
