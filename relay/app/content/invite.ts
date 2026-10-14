/*
 * The invite page, https://us2us.app/u/<username> (docs/INVITES.md): the
 * link the app puts into an invitation sent by SMS, WhatsApp or e-mail.
 *
 * Like the 404 page (./not-found.ts) it is a string template served by a
 * small middleware (server/middleware/invite.ts), because the production
 * server has no Vue renderer; it reuses the site's stylesheet, theme script,
 * fonts, icons and the markup of SiteHeader.vue / SiteFooter.vue /
 * StoreButtons.vue (keep them in step).
 *
 * Privacy: the page never asks anything about the username. There is no
 * relay, directory or database lookup (the relay only knows username hashes
 * anyway, PROTOCOL.md §3.2), so the page looks the same for every
 * well-formed name and cannot be used to find out which accounts exist. A
 * name outside the §3.1 rule gets the normal 404. No client JavaScript is
 * needed (theme.js only applies a saved light/dark choice), no cookies,
 * noindex.
 */

import { FONTS, ICON_LINKS_HTML, STYLESHEET, THEME_COLOR, THEME_SCRIPT, withVersion } from './assets'
import { ICONS, type IconName } from './icons'
import { LICENCES_ANCHOR, paths, SECURITY_ANCHOR, site, type Locale } from './site'

/** The path prefix of invite links; the app's links are `https://us2us.app/u/<username>`. */
export const INVITE_PREFIX = '/u/'

/**
 * A username the page accepts: exactly PROTOCOL.md §3.1, already normalized
 * (3–24 characters, a lower-case letter first, then `a-z`, `0-9` and `_`),
 * the same rule the apps use for every name they register, look up or put
 * into a link. None of these characters means anything in HTML, so nothing a
 * visitor types can reach the markup unescaped; the page escapes the name
 * anyway.
 */
export const INVITE_USERNAME = /^[a-z][a-z0-9_]{2,23}$/

/**
 * The username of an invite path (`/u/<username>`, optionally with a
 * trailing slash), or null: nothing is decoded, case-folded or trimmed, so a
 * name has exactly one URL and anything else is the normal 404.
 */
export function inviteUsername(path: string): string | null {
  if (!path.startsWith(INVITE_PREFIX)) return null
  const name = path.slice(INVITE_PREFIX.length).replace(/\/$/, '')
  return INVITE_USERNAME.test(name) ? name : null
}

/** The page's language: `?lang=tr|en` (the language switch) wins over Accept-Language. */
export function inviteLocale(query: string, preferred: Locale): Locale {
  const lang = new URLSearchParams(query).get('lang')
  return lang === 'tr' || lang === 'en' ? lang : preferred
}

/** Escapes text for HTML element content and double-quoted attributes. */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, char => `&#${char.charCodeAt(0)};`)
}

interface InviteText {
  /** Browser title and link-preview title. */
  title: (name: string) => string
  description: (name: string) => string
  kicker: string
  /** The h1, worded exactly as designed. */
  heading: (name: string) => string
  /** What us2us is, in two lines. */
  intro: string
  howTitle: string
  steps: (name: string) => string[]
  appNote: string
  privacyNote: (name: string) => string
}

export const inviteText: Record<Locale, InviteText> = {
  tr: {
    title: name => `@${name} seninle konuşmak istiyor · us2us`,
    description: name => `us2us’ta @${name} seninle konuşmak istiyor. us2us iki kişi arasında kurulan özel bir hat: telefon numarası istemez, bildirim göndermez.`,
    kicker: 'Bir davetin var.',
    heading: name => `us2us’ta @${name} seninle konuşmak istiyor`,
    intro: 'us2us iki kişi arasında kurulan özel bir hat. Telefon numarası istemez, bildirim göndermez; mesajlar uçtan uca şifrelenir ve yalnızca iki ucun telefonunda açılır.',
    howTitle: 'Nasıl eklersin?',
    steps: name => ['us2us’u aç.', '“Yeni sohbet”e dokun.', `@${name} yaz ve mesaj isteği gönder.`],
    appNote: 'us2us telefonunda yüklüyse bu bağlantı uygulamayı doğrudan açar.',
    privacyNote: name => `Bu sayfa çerez kullanmaz, seni izlemez ve @${name} adında bir hesap olup olmadığına bakmaz.`,
  },
  en: {
    title: name => `@${name} wants to talk to you on us2us`,
    description: name => `@${name} wants to talk to you on us2us, a private line between two people: no phone number, no notifications.`,
    kicker: 'You’re invited.',
    heading: name => `@${name} wants to talk to you on us2us`,
    intro: 'us2us is a private line between two people. It never asks for your phone number and sends no notifications; messages are end-to-end encrypted and open only on the phones at either end.',
    howTitle: 'How to add them',
    steps: name => ['Open us2us.', 'Tap “New chat”.', `Type @${name} and send a message request.`],
    appNote: 'If us2us is on your phone, this link opens the app directly.',
    privacyNote: name => `This page sets no cookies, does not track you, and does not check whether an account named @${name} exists.`,
  },
}

const icon = (name: IconName, className = '') =>
  `<svg class="icon${className ? ` ${className}` : ''}" viewBox="0 0 256 256" fill="currentColor" aria-hidden="true" focusable="false">${ICONS[name].map(d => `<path d="${d}"/>`).join('')}</svg>`

const mark = (small = false) => `<span class="mark${small ? ' mark-sm' : ''}" aria-hidden="true"><span></span><span></span><span></span></span>`

/** The invite URL of `name` in `locale` (the language switch's targets). */
const pageHref = (name: string, locale: Locale) => `${INVITE_PREFIX}${name}?lang=${locale}`

/**
 * The page for `name` (already validated by `inviteUsername`) in `locale`,
 * with our own files referenced by `assetVersion` (nuxt.config.ts).
 */
export function invitePage(name: string, locale: Locale, assetVersion: string): string {
  const text = inviteText[locale]
  const common = site[locale]
  const at = escapeHtml(name)
  const asset = (path: string) => withVersion(path, assetVersion)
  const preload = (href: string) => `<link rel="preload" as="font" type="font/woff2" href="${href}" crossorigin>`
  const languages = (['tr', 'en'] as const).map(code =>
    `<a class="seg-opt" href="${pageHref(at, code)}" hreflang="${code}" lang="${code}"${code === locale ? ' aria-current="true"' : ''}>${code.toUpperCase()}<span class="visually-hidden"> ${site[code].languageName}</span></a>`,
  ).join('')
  return `<!DOCTYPE html>
<html lang="${locale}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(text.title(name))}</title>
<script src="${asset(THEME_SCRIPT)}"></script>
<link rel="stylesheet" href="${asset(STYLESHEET)}">
${preload(FONTS.interLatin)}
${preload(FONTS.dotoLatin)}
${locale === 'tr' ? `${preload(FONTS.interTurkish)}\n` : ''}<meta name="robots" content="noindex, nofollow">
<meta name="description" content="${escapeHtml(text.description(name))}">
<meta name="color-scheme" content="dark light">
<meta name="theme-color" content="${THEME_COLOR}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="us2us">
<meta property="og:title" content="${escapeHtml(text.title(name))}">
<meta property="og:description" content="${escapeHtml(text.description(name))}">
<meta property="og:locale" content="${locale === 'tr' ? 'tr_TR' : 'en_US'}">
${ICON_LINKS_HTML}
</head>
<body>
<a class="skip-link" href="#main">${common.skipLink}</a>
<div class="bg-glow" aria-hidden="true"></div>
<div class="bg-dots" aria-hidden="true"></div>
<header class="site-header"><div class="site-nav">
<a class="brand" href="${paths[locale].home}" aria-label="${common.nav.home}">${mark()}<span class="wordmark">us2us</span></a>
<div class="header-controls">
<nav class="seg lang-switch" aria-label="${common.nav.languageLabel} / ${locale === 'tr' ? 'Language' : 'Dil'}">${languages}</nav>
<button type="button" class="btn btn-secondary btn-icon theme-toggle" aria-label="${common.nav.themeLabel}" aria-pressed="false" data-theme-toggle>${icon('sun', 'theme-sun')}${icon('moon', 'theme-moon')}</button>
</div>
</div></header>
<main id="main" class="site-main page-invite" tabindex="-1">
<div class="container page-pad">
<div class="hero-copy">
<p class="tag tag-outline hero-badge">${icon('lock-simple')}${common.home.hero.badge}</p>
<div class="hero-heading">
<p class="kicker">${text.kicker}</p>
<h1 class="display display-page">${escapeHtml(text.heading(name))}</h1>
</div>
<p class="hero-lead">${text.intro}</p>
<ul class="stores" aria-label="${common.home.stores.label}">
<li><a class="btn btn-primary store" role="link" aria-disabled="true">${icon('apple-logo-fill', 'store-icon')}App Store<span class="visually-hidden">, </span><span class="tag tag-accent store-soon">${common.home.stores.comingSoon}</span></a></li>
<li><a class="btn btn-secondary store" role="link" aria-disabled="true">${icon('google-play-logo-fill', 'store-icon store-icon-play')}Google Play<span class="visually-hidden">, </span><span class="tag tag-neutral store-soon">${common.home.stores.comingSoon}</span></a></li>
</ul>
<p class="stores-note"><span class="beta-dot" aria-hidden="true"></span>${common.home.stores.beta}</p>
<ul class="facts">
${common.home.hero.facts.map(fact => `<li>${icon(fact.icon)}${fact.text}</li>`).join('\n')}
</ul>
<section class="section-copy" aria-labelledby="add-title">
<h2 id="add-title" class="h2 h2-prose">${text.howTitle}</h2>
<ol class="dot-list">
${text.steps(name).map(step => `<li>${escapeHtml(step)}</li>`).join('\n')}
</ol>
<p class="lead">${text.appNote}</p>
<p class="lead">${escapeHtml(text.privacyNote(name))}</p>
</section>
</div>
</div>
</main>
<footer class="site-footer"><div class="container footer-inner"><div class="hr"></div>
<nav class="footer-row" aria-label="${common.footer.label}">
<p class="footer-brand">${mark(true)}<span class="wordmark wordmark-sm">us2us</span> <span class="footer-note">© 2026</span></p>
<a class="footer-link" href="${paths[locale].why}">${common.nav.why}</a>
<a class="footer-link" href="${paths[locale].privacy}">${common.footer.privacy}</a>
<a class="footer-link" href="${paths[locale].privacy}#${SECURITY_ANCHOR[locale]}">${common.footer.security}</a>
<a class="footer-link" href="${paths[locale].support}">${common.footer.press}</a>
<a class="footer-link" href="${paths[locale].support}">${common.footer.contact}</a>
<a class="footer-link footer-legal" href="${paths[locale].support}#${LICENCES_ANCHOR[locale]}">${common.footer.licence}</a>
</nav>
<p class="footer-fine">${common.footer.noTracking}</p>
</div></footer>
</body>
</html>
`
}
