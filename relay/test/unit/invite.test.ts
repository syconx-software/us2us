import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { escapeHtml, INVITE_USERNAME, invitePage, inviteLocale, inviteUsername } from '../../app/content/invite'
import { APP_SITE_ASSOCIATION, APP_SITE_ASSOCIATION_JSON, IOS_APP_ID } from '../../server/lib/app-links'

/** The text of a page as a reader sees it: tags removed, whitespace collapsed. */
const textOf = (page: string) => page.replace(/<(script|style)\b[\s\S]*?<\/\1>/g, ' ').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/\s+/g, ' ')

describe('invite paths', () => {
  it('accepts exactly the names a us2us username can be', () => {
    for (const name of ['deniz', 'selin.n', 'ayla_', 'kaan2', 'abc', 'a'.repeat(24), 'mert.k', '_x_', '1ab']) {
      expect(inviteUsername(`/u/${name}`), name).toBe(name)
      expect(inviteUsername(`/u/${name}/`), `${name}/`).toBe(name)
    }
  })

  it('refuses everything else, without decoding, folding or trimming it', () => {
    const refused = [
      '/u/', '/u/ab', `/u/${'a'.repeat(25)}`, '/u/Deniz', '/u/DENIZ', '/u/deniz%20', '/u/%64eniz', '/u/de niz',
      '/u/deniz/x', '/u/../tr', '/u/...', '/u/__.', '/u/deniz<script>', '/u/de"niz', '/u/deni\'z', '/u/şeyma', '/u/ırmak',
      '/u/deniz;', '/u/deniz&x', '/u/d-niz', '/u/@deniz', '/U/deniz', '/u//deniz', '/x/deniz', '/u/deniz//',
    ]
    for (const path of refused) expect(inviteUsername(path), path).toBeNull()
    // The pattern itself never matches an HTML-significant character.
    for (const char of '<>&"\'/ =`') expect(INVITE_USERNAME.test(`abc${char}`), char).toBe(false)
  })

  it('takes the language from ?lang=, then from Accept-Language', () => {
    expect(inviteLocale('lang=tr', 'en')).toBe('tr')
    expect(inviteLocale('lang=en', 'tr')).toBe('en')
    expect(inviteLocale('lang=de', 'tr')).toBe('tr')
    expect(inviteLocale('', 'en')).toBe('en')
    expect(inviteLocale('x=1&lang=en', 'tr')).toBe('en')
  })
})

describe('invite page', () => {
  it('says who wants to talk, in Turkish and in English', () => {
    const tr = invitePage('selin.n', 'tr', 'v123')
    const en = invitePage('selin.n', 'en', 'v123')
    expect(tr).toContain('<html lang="tr">')
    expect(en).toContain('<html lang="en">')
    expect(textOf(tr.match(/<h1\b[\s\S]*?<\/h1>/)![0])).toBe('us2us’ta @selin.n seninle konuşmak istiyor')
    expect(textOf(en.match(/<h1\b[\s\S]*?<\/h1>/)![0])).toBe('@selin.n wants to talk to you on us2us')
    // How to add them: open us2us → New chat → @username.
    expect(textOf(tr)).toContain('us2us’u aç. “Yeni sohbet”e dokun. @selin.n yaz ve mesaj isteği gönder.')
    expect(textOf(en)).toContain('Open us2us. Tap “New chat”. Type @selin.n and send a message request.')
    // The stores are "coming soon", as on the landing page: no links.
    for (const [page, soon] of [[tr, 'Yakında'], [en, 'Coming soon']] as const) {
      const stores = [...page.matchAll(/<a class="btn btn-(?:primary|secondary) store"([^>]*)>([\s\S]*?)<\/a>/g)]
      expect(stores).toHaveLength(2)
      for (const [, attributes, content] of stores) {
        expect(attributes).toContain('aria-disabled="true"')
        expect(attributes).not.toContain('href')
        expect(textOf(content!)).toContain(soon)
      }
    }
    // It says that it checks nothing.
    expect(textOf(tr)).toContain('@selin.n adında bir hesap olup olmadığına bakmaz')
    expect(textOf(en)).toContain('does not check whether an account named @selin.n exists')
  })

  it('links to the same invite in the other language, and to the site in its own', () => {
    const tr = invitePage('deniz', 'tr', 'v123')
    expect(tr).toContain('<a class="seg-opt" href="/u/deniz?lang=tr" hreflang="tr" lang="tr" aria-current="true">')
    expect(tr).toContain('<a class="seg-opt" href="/u/deniz?lang=en" hreflang="en" lang="en">')
    expect(tr).toContain('<a class="brand" href="/tr"')
    expect(tr).toContain('href="/tr/gizlilik"')
    const en = invitePage('deniz', 'en', 'v123')
    expect(en).toContain('<a class="seg-opt" href="/u/deniz?lang=en" hreflang="en" lang="en" aria-current="true">')
    expect(en).toContain('href="/en/privacy"')
  })

  it('is noindex, has one h1, landmarks, our own files only and nothing inline', () => {
    for (const locale of ['tr', 'en'] as const) {
      const page = invitePage('deniz', locale, 'v123')
      expect(page).toContain('<meta name="robots" content="noindex, nofollow">')
      expect(page.match(/<h1\b/g)).toHaveLength(1)
      expect(page).toMatch(/<a class="skip-link" href="#main">/)
      for (const landmark of ['<header', '<nav', '<main id="main"', '<footer']) expect(page).toContain(landmark)
      expect(page).not.toMatch(/\sstyle=|<style|<script>|<form|<iframe|<img/)
      const scripts = [...page.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
      expect(scripts).toHaveLength(1)
      expect(scripts[0]![1]).toBe(' src="/js/theme.js?v=v123"')
      expect(page).toContain('<link rel="stylesheet" href="/css/site.css?v=v123">')
      for (const [, url] of page.matchAll(/(?:src|href)="([^"]+)"/g)) expect(url, url).toMatch(/^(\/|#)/)
      expect(page).not.toMatch(/https?:\/\//)
    }
    expect(invitePage('deniz', 'tr', 'v')).toContain('href="/fonts/inter-v20-tr.woff2"')
    expect(invitePage('deniz', 'en', 'v')).not.toContain('-tr.woff2')
  })

  it('escapes the name wherever it appears', () => {
    expect(escapeHtml('<b a="1" c=\'2\'>&</b>')).toBe('&#60;b a=&#34;1&#34; c=&#39;2&#39;&#62;&#38;&#60;/b&#62;')
    // The middleware never passes such a name (inviteUsername refuses it); the template escapes it anyway.
    const hostile = '"><script>alert(1)</script><x y=\''
    const page = invitePage(hostile, 'en', 'v')
    expect(page).not.toContain('<script>alert')
    expect(page).not.toContain(hostile)
    expect(page).not.toContain('<x y=')
    expect(page).toContain('&#34;&#62;&#60;script&#62;alert(1)&#60;/script&#62;')
  })

  it('looks the same for every well-formed name: nothing about the account is looked up', () => {
    const page = (name: string) => invitePage(name, 'en', 'v').replaceAll(name, 'NAME')
    expect(page('selin.n')).toBe(page('zz_nobody_42'))
  })

  it('reaches no database, relay state or network from the page or its middleware', () => {
    // Walks the relative imports of the middleware: none may lead to the
    // database, the relay's services or state, or a network client.
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
    const seen = new Set<string>()
    const forbidden = /mysql2|drizzle|server\/(db|services|realtime|http)\/|server\/relay\.ts|node:(net|http|https|dns|child_process)|useRelay|fetch\(/
    const visit = (file: string) => {
      if (seen.has(file)) return
      seen.add(file)
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(forbidden)
      for (const [, specifier] of source.matchAll(/^import\b[^'"]*['"]([^'"]+)['"]/gm)) {
        if (!specifier!.startsWith('.')) {
          expect(['h3'], `${file} imports ${specifier}`).toContain(specifier)
          continue
        }
        visit(resolve(dirname(file), `${specifier}.ts`))
      }
    }
    for (const middleware of ['server/middleware/invite.ts', 'server/middleware/app-site-association.ts']) visit(resolve(root, middleware))
    expect([...seen].map(file => file.slice(root.length + 1)).sort()).toEqual([
      'app/content/assets.ts',
      'app/content/icons.ts',
      'app/content/invite.ts',
      'app/content/site.ts',
      'server/lib/app-links.ts',
      'server/lib/locale.ts',
      'server/lib/website.ts',
      'server/middleware/app-site-association.ts',
      'server/middleware/invite.ts',
    ])
  })
})

describe('apple-app-site-association', () => {
  it('claims only the invite paths, for the iOS app', () => {
    expect(IOS_APP_ID).toBe('C8QW8MBYQX.com.syconx.us2us')
    expect(JSON.parse(APP_SITE_ASSOCIATION_JSON)).toEqual(APP_SITE_ASSOCIATION)
    expect(APP_SITE_ASSOCIATION.applinks.details).toHaveLength(1)
    const [detail] = APP_SITE_ASSOCIATION.applinks.details
    expect(detail.appIDs).toEqual([IOS_APP_ID])
    expect(detail.components.map(component => component['/'])).toEqual(['/u/*'])
    // Apple's limit is 128 KB uncompressed.
    expect(Buffer.byteLength(APP_SITE_ASSOCIATION_JSON)).toBeLessThan(1_000)
  })
})
