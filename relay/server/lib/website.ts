import { paths } from '../../app/content/site'

/** Every page of the website; all are prerendered at build time (nuxt.config.ts). */
export const SITE_PAGES: readonly string[] = Object.values(paths).flatMap(pages => Object.values(pages))

// The website loads only its own files: one stylesheet, three small scripts
// (theme, enhancements, 3D scene) plus three.js, the fonts and the favicon,
// all from this origin. No inline scripts (Nuxt's speculation rules are
// removed by app/plugins/no-speculation-rules.server.ts), no inline styles,
// no eval, and no HTML from strings (Trusted Types with no policy: the
// scripts only set textContent).
export const SITE_HEADERS: Readonly<Record<string, string>> = {
  'content-security-policy': [
    'default-src \'none\'',
    'script-src \'self\'',
    'style-src \'self\'',
    'font-src \'self\'',
    'img-src \'self\'',
    'base-uri \'none\'',
    'form-action \'none\'',
    'frame-ancestors \'none\'',
    'require-trusted-types-for \'script\'',
    'trusted-types \'none\'',
  ].join('; '),
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cross-origin-opener-policy': 'same-origin',
}

/** Headers for the site's static files (fonts, scripts, stylesheet). */
export const SITE_ASSET_HEADERS: Readonly<Record<string, string>> = {
  'x-content-type-options': 'nosniff',
  'cross-origin-resource-policy': 'same-origin',
}
