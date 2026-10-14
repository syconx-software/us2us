/*
 * The relay's one static 404 page (built once and served from memory by
 * server/middleware/not-found.ts for every unknown path). It uses the
 * site's stylesheet and scripts and mirrors the markup of SiteHeader.vue and
 * SiteFooter.vue; keep them in step. Both languages are shown at once, since
 * the page cannot know the visitor's.
 */

import { ICON_LINKS_HTML, SITE_SCRIPT, STYLESHEET, THEME_COLOR, THEME_SCRIPT, withVersion } from './assets'
import { ICONS, type IconName } from './icons'
import { LICENCES_ANCHOR, notFoundText, paths, SECURITY_ANCHOR, site } from './site'

const icon = (name: IconName, className: string) =>
  `<svg class="icon ${className}" viewBox="0 0 256 256" fill="currentColor" aria-hidden="true" focusable="false">${ICONS[name].map(d => `<path d="${d}"/>`).join('')}</svg>`

const mark = (small = false) => `<span class="mark${small ? ' mark-sm' : ''}" aria-hidden="true"><span></span><span></span><span></span></span>`

const en = site.en

/** The page, with our own files referenced by `assetVersion` (nuxt.config.ts). */
export function notFoundPage(assetVersion: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<script src="${withVersion(THEME_SCRIPT, assetVersion)}"></script>
<title>Not found · us2us</title>
<meta name="robots" content="noindex">
<meta name="color-scheme" content="dark light">
<meta name="theme-color" content="${THEME_COLOR}">
${ICON_LINKS_HTML}
<link rel="stylesheet" href="${withVersion(STYLESHEET, assetVersion)}">
<script type="module" src="${withVersion(SITE_SCRIPT, assetVersion)}"></script>
</head>
<body>
<a class="skip-link" href="#main">${en.skipLink}</a>
<div class="bg-glow" aria-hidden="true"></div>
<div class="bg-dots" aria-hidden="true"></div>
<header class="site-header"><div class="site-nav">
<a class="brand" href="${paths.en.home}" aria-label="${en.nav.home}">${mark()}<span class="wordmark">us2us</span></a>
<div class="header-controls">
<nav class="seg lang-switch" aria-label="Dil / Language"><a class="seg-opt" href="${paths.tr.home}" hreflang="tr" lang="tr">TR<span class="visually-hidden"> ${site.tr.languageName}</span></a><a class="seg-opt" href="${paths.en.home}" hreflang="en" lang="en">EN<span class="visually-hidden"> ${en.languageName}</span></a></nav>
<button type="button" class="btn btn-secondary btn-icon theme-toggle" aria-label="${en.nav.themeLabel}" aria-pressed="false" data-theme-toggle>${icon('sun', 'theme-sun')}${icon('moon', 'theme-moon')}</button>
</div>
</div></header>
<main id="main" class="site-main" tabindex="-1">
<div class="container page-pad error-page">
<p class="eyebrow" aria-hidden="true"><span lang="tr">Sayfa bulunamadı</span> · Not found</p>
<h1 class="display display-page">404<span class="visually-hidden"> · <span lang="tr">Sayfa bulunamadı</span> · Not found</span></h1>
<p class="lead" lang="tr">${notFoundText.tr.message}</p>
<p class="lead">${notFoundText.en.message}</p>
<p class="error-links"><a class="btn btn-secondary" href="${paths.tr.home}" lang="tr">${notFoundText.tr.home}</a> <a class="btn btn-secondary" href="${paths.en.home}">${notFoundText.en.home}</a></p>
</div>
</main>
<footer class="site-footer"><div class="container footer-inner"><div class="hr"></div>
<nav class="footer-row" aria-label="${en.footer.label}">
<p class="footer-brand">${mark(true)}<span class="wordmark wordmark-sm">us2us</span> <span class="footer-note">© 2026</span></p>
<a class="footer-link" href="${paths.en.why}">${en.nav.why}</a>
<a class="footer-link" href="${paths.en.privacy}">${en.footer.privacy}</a>
<a class="footer-link" href="${paths.en.privacy}#${SECURITY_ANCHOR.en}">${en.footer.security}</a>
<a class="footer-link" href="${paths.en.support}">${en.footer.press}</a>
<a class="footer-link" href="${paths.en.support}">${en.footer.contact}</a>
<a class="footer-link footer-legal" href="${paths.en.support}#${LICENCES_ANCHOR.en}">${en.footer.licence}</a>
</nav>
<p class="footer-fine">${en.footer.noTracking}</p>
</div></footer>
</body>
</html>
`
}
