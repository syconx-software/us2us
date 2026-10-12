import { readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { ICON_LINKS } from '../../app/content/assets'
import { site } from '../../app/content/site'
import { why, WHY_SECTION, WHY_SOURCES } from '../../app/content/why'
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

const WHY_PAGES = ['/tr/neden-yaptik', '/en/why-we-built-it']
const PAGES = ['/tr', '/en', '/tr/gizlilik', '/en/privacy', '/tr/destek', '/en/support', ...WHY_PAGES]
/** The sources of the "why we built it" page: the only other sites the website links to (never loads from). */
const SOURCE_URLS = new Set(WHY_SOURCES.map(source => source.url))
/** An attribute value as written in the HTML, unescaped. */
const unescape = (value: string) => value.replace(/&amp;/g, '&')
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
    // Trust on first use: the first key comes from the relay; only comparing safety numbers reveals a swap.
    expect(tr).toContain('onun açık anahtarını sunucumuzdan alırsınız')
    expect(en).toContain('their public key comes from our server')
    // Sealed sender hides the sender from the envelope, not from IP and timing; post-quantum covers content only.
    expect(tr).toContain('Mühürlü gönderici tam bir anonimlik sağlamaz')
    expect(tr).toContain('Kuantum sonrası koruma mesaj içerikleri içindir')
    expect(en).toContain('Sealed sender is not full anonymity')
    expect(en).toContain('The post-quantum protection covers message content')
    expect(tr).toContain('henüz kabul etmediği birinden (mesaj isteği) mi geldiği; hangi kişiden geldiği değil')
    expect(en).toContain('from someone not yet accepted (a message request); not which contact')
    for (const text of ['kimden geldiğini bilmez', 'Mühürlü bir mesajı kimin gönderdiği', 'kuantum sonrası korumalı)']) expect(tr).not.toContain(text)
    for (const text of ['but not who sent it', 'Who sent a sealed message', 'post-quantum protected)']) expect(en).not.toContain(text)
    // Six digits: guessing needs this phone (and on an iPhone its Secure Enclave), but a broken OS skips the app's delays.
    expect(tr).toContain('iPhone’da her deneme ayrıca Secure Enclave’den hiç çıkmayan bir anahtar ister')
    expect(en).toContain('On an iPhone every guess also needs a key that never leaves the Secure Enclave')
    // A stolen backup file can be guessed offline.
    expect(tr).toContain('Yedek dosyasını ele geçiren biri parolayı çevrimdışı ve sınırsızca deneyebilir')
    expect(en).toContain('Anyone who obtains the backup file can try passwords offline, without limit')
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
    // Under both sets of buttons: where the app is today, a closed beta on iOS.
    for (const [path, beta] of [
      ['/tr', 'Şu an iOS’ta kapalı betada (TestFlight, davetle). App Store ve Android sürümü yakında.'],
      ['/en', 'Now in closed beta on iOS (TestFlight, by invitation). App Store and Android versions coming soon.'],
    ] as const) {
      const page = await html(path)
      const notes = [...page.matchAll(/<p class="stores-note">([\s\S]*?)<\/p>/g)]
      expect(notes, path).toHaveLength(2)
      for (const [, content] of notes) expect(textOf(content!).trim(), path).toBe(beta)
      // A closed beta has no public link.
      expect(page, path).not.toMatch(/testflight\.apple\.com/)
    }
  })

  it('tells beta testers what keeps their data safe, and where bugs and security problems go', async () => {
    for (const [path, texts] of [
      ['/tr/destek', ['Beta testçileri için', '1.0.5’ten sonra eski bir derlemeye dönmeyin', 'Daha eski derlemeler bu biçimi açamaz', 'Yedeğinizi ve parolasını saklayın', 'Hata bildirmek', 'Bir güvenlik açığı bulduysanız lütfen önce herkese açık bir yerde']],
      ['/en/support', ['For beta testers', 'Don’t go back to an older build after 1.0.5', 'Older builds cannot open that format', 'Keep your backup and its password safe', 'Reporting bugs', 'Please don’t post it anywhere public first']],
    ] as const) {
      const page = await html(path)
      for (const text of texts) expect(textOf(page), `${path}: ${text}`).toContain(text)
      expect(page, path).toContain('id="beta"')
      expect(page, path).toContain('href="mailto:support@us2us.app"')
      expect(page, path).toContain('href="mailto:security@us2us.app"')
      // The notes come before the questions.
      expect(page.indexOf('id="beta"'), path).toBeLessThan(page.indexOf('<details'))
    }
  })

  it('answers the questions about what the app does today', async () => {
    const tr = textOf(await html('/tr/destek'))
    const en = textOf(await html('/en/support'))
    for (const question of [
      'Yedekten geri yükleyince neler geri gelir?', 'Yedek parolamı nasıl seçmeliyim?', 'Sesli mesaj nasıl gönderilir?', 'Konum ve canlı konum nasıl çalışır?',
      'Fotoğraf, video ve dosyalar nasıl açılır?', 'Güvenlik numarası nedir, “Doğrulanmadı” ne demek?', '“Güvenlik numarası değişti” uyarısı ne demek?',
    ]) expect(tr).toContain(question)
    for (const question of [
      'What comes back when I restore from a backup?', 'How should I choose my backup password?', 'How do I send a voice message?', 'How do location and live location work?',
      'How do photos, videos and files open?', 'What is a safety number, and what does “Not verified” mean?', 'What does the “Safety number changed” warning mean?',
    ]) expect(en).toContain(question)
    // A restore brings back the account and contacts, never the messages.
    expect(tr).toContain('Mesajlar, fotoğraflar ve videolar yedekte yoktur, geri gelmez')
    expect(en).toContain('Messages, photos and videos are not in the backup and do not come back')
    // “Erase everything” in Settings deletes the account on the relay; the forgotten-code path erases only the phone.
    expect(tr).toContain('“Kodu unuttum” › “Her şeyi sil, baştan başla” da yalnızca telefonu siler')
    expect(en).toContain('“I forgot my code” › “Erase everything, start over” on the lock screen also erases only the phone')
    // Accepting a changed key without verifying leaves the chat unverified.
    expect(tr).toContain('“Doğrulamadan devam et”i seçerseniz mesajlarınız yeni numarayla gönderilir ama sohbet doğrulanmamış kalır')
    expect(en).toContain('If you choose “Continue without verifying”, your messages are sent under the new number but the chat stays unverified')
    // The app cannot change a username (Settings: “Kullanıcı adın · değiştirilemez”).
    expect(tr).toContain('Şimdilik hayır: uygulamada kullanıcı adı değiştirilemiyor.')
    expect(en).toContain('Not for now: the app cannot change a username yet.')
    expect(tr).not.toContain('günde en fazla 5 kez')
    expect(en).not.toContain('up to 5 times a day')
    // Security problems go to their own address, privately.
    expect(tr).toContain('Lütfen security@us2us.app adresine yazın.')
    expect(en).toContain('Please write to security@us2us.app')
  })

  it('dates the privacy policy 11 October 2026 and covers TestFlight and the security address', async () => {
    const tr = textOf(await html('/tr/gizlilik'))
    const en = textOf(await html('/en/privacy'))
    expect(tr).toContain('Son güncelleme: 11 Ekim 2026')
    expect(en).toContain('Last updated: 11 October 2026')
    expect(tr).toContain('us2us’ı TestFlight ile deniyorsanız')
    expect(en).toContain('if you test us2us with TestFlight')
    expect(tr).toContain('Bir güvenlik açığı bulduysanız security@us2us.app adresine yazın.')
    expect(en).toContain('Found a security problem? Please write to security@us2us.app')
    expect(tr).not.toContain('konu satırına “security”')
    expect(en).not.toContain('“security” in the subject line')
    // Vault format v2 comes with 1.0.5 and the first code entry.
    expect(tr).toContain('(1.0.5 sürümünden itibaren; mevcut veriler bu korumaya kodunuzu ilk girdiğinizde geçer)')
    expect(en).toContain('(from version 1.0.5 on; existing data moves to this protection the first time you enter your code)')
  })

  it('says in every footer that the site uses no cookies or trackers, which it does not', async () => {
    for (const path of PAGES) {
      const response = await get(path)
      expect(response.headers.get('set-cookie'), path).toBeNull()
      const footer = textOf((await response.text()).split('<footer')[1]!)
      expect(footer, path).toContain(path.startsWith('/tr') ? 'Bu site çerez ve izleyici kullanmaz, başka bir siteden hiçbir şey yüklemez.' : 'This site uses no cookies or trackers and loads nothing from other sites.')
    }
    const notFound = await (await get('/does/not/exist', { accept: 'text/html' })).text()
    expect(textOf(notFound.split('<footer')[1]!)).toContain('This site uses no cookies or trackers')
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
      ['/tr/gizlilik', 'Mesajlarınızın tesliminde Apple ve Google hiç devrede değildir'],
      ['/en/privacy', 'Apple and Google play no part in delivering your messages'],
    ]
    for (const [path, text] of pages) {
      const page = await html(path)
      expect(textOf(page), path).toContain(text)
      expect(page, path).not.toMatch(/APNs|FCM|push token|bildirim belirteci/i)
    }
    // The relay does see deliveries while it makes them; the FAQ says so instead of "nobody, not even us".
    const tr = textOf(await html('/tr/destek'))
    const en = textOf(await html('/en/support'))
    expect(tr).toContain('Böylece Apple’a ve Google’a hiçbir şey bildirilmez.')
    expect(en).toContain('Apple and Google are told nothing.')
    expect(tr).not.toContain('ne de bize bildirilir')
    expect(en).not.toContain('not us, gets a report')
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
    for (const [path, title] of [
      ['/tr', 'us2us · Araya kimse giremez'], ['/en', 'us2us · No one gets in between'], ['/en/privacy', 'Privacy policy · us2us'],
      ['/tr/neden-yaptik', 'Neden yaptık · us2us'], ['/en/why-we-built-it', 'Why we built it · us2us'],
    ] as const) {
      const page = await html(path)
      expect(page).toContain(`<title>${title}</title>`)
      expect(page).toMatch(/<meta name="description" content="[^"]{40,}">/)
      expect(page).toContain(`<link rel="canonical" href="https://us2us.app${path}">`)
      expect(page).toContain(`<meta property="og:title" content="${title}">`)
      expect(page).toContain(`<meta property="og:url" content="https://us2us.app${path}">`)
      for (const hreflang of ['tr', 'en', 'x-default']) expect(page).toMatch(new RegExp(`<link rel="alternate" hreflang="${hreflang}" href="https://us2us.app/`))
      // The site icons: the SVG, the ICO fallback (its `sizes` keeps Chrome on the SVG) and the touch icon.
      expect(page).toContain('<link rel="icon" type="image/svg+xml" href="/favicon.svg">')
      expect(page).toContain('<link rel="icon" href="/favicon.ico" sizes="32x32">')
      expect(page).toContain('<link rel="apple-touch-icon" href="/apple-touch-icon.png">')
    }
  })

  it('serves the "why we built it" page in both languages: a press release whose marks link to its sources', async () => {
    /** Text as a reader sees it, with the entities Vue writes decoded. */
    const plain = (markup: string) => textOf(markup).replace(/&#39;/g, '\'').replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    for (const [path, locale, other] of [['/tr/neden-yaptik', 'tr', '/en/why-we-built-it'], ['/en/why-we-built-it', 'en', '/tr/neden-yaptik']] as const) {
      const text = why[locale]
      const response = await get(path)
      expect(response.status, path).toBe(200)
      const page = await response.text()
      expect(page, path).toMatch(new RegExp(`<html\\s+lang="${locale}"`))
      // Eyebrow, headline, standfirst and dateline; an Open Graph article.
      expect(page.match(/<h1\b/g), path).toHaveLength(1)
      expect(plain(page.match(/<h1\b[\s\S]*?<\/h1>/)![0]).trim(), path).toBe(text.headline)
      expect(plain(page), path).toContain(text.eyebrow)
      expect(plain(page), path).toContain(locale === 'tr' ? 'İstanbul, 11 Ekim 2026 — SyconX' : 'Istanbul, 11 October 2026 — SyconX')
      expect(page, path).toContain('<time datetime="2026-10-11">')
      expect(page, path).toContain('<meta property="og:type" content="article">')
      expect(page, path).toContain('<meta property="article:published_time" content="2026-10-11">')
      // Every section under its id, the same in both languages (the language switch keeps the place).
      for (const section of text.sections) {
        expect(page, `${path} #${section.id}`).toContain(`<section id="${section.id}" class="prose-section"`)
        expect(plain(page), `${path} #${section.id}`).toContain(section.title)
      }
      expect(page).toMatch(new RegExp(`<a class="seg-opt" href="${other}" hreflang="\\w\\w" lang="\\w\\w" data-lang-link>`))
      for (const [hreflang, href] of [['tr', '/tr/neden-yaptik'], ['en', '/en/why-we-built-it'], ['x-default', '/en/why-we-built-it']]) {
        expect(page, `${path} ${hreflang}`).toContain(`<link rel="alternate" hreflang="${hreflang}" href="https://us2us.app${href}">`)
      }
      // The sources, numbered: each links out without referrer or opener, and back to its first mark in the text.
      expect(page, path).toContain(`<section id="${WHY_SECTION.sources}" class="prose-section"`)
      expect(plain(page), path).toContain(text.sources.title)
      const entries = [...page.matchAll(/<li id="kaynak-(\d+)" class="source">([\s\S]*?)<\/li>/g)]
      expect(entries.map(([, n]) => Number(n)), path).toEqual(WHY_SOURCES.map((_, index) => index + 1))
      for (const [index, source] of WHY_SOURCES.entries()) {
        const n = index + 1
        const entry = entries[index]![2]!
        expect(entry, `${path} [${n}]`).toContain(`<a class="source-title" href="${source.url.replace(/&/g, '&amp;')}" hreflang="${source.lang}" lang="${source.lang}" rel="noopener noreferrer">`)
        expect(entry, `${path} [${n}]`).toContain(`href="#atif-${n}"`)
        expect(page.match(new RegExp(`<a id="atif-${n}" href="#kaynak-${n}">`, 'g')), `${path} [${n}]`).toHaveLength(1)
      }
      // A word joiner opens every mark, so no line starts with one.
      expect(page.match(/<sup class="cite">/g)!.length, path).toBe(page.match(/<sup class="cite">⁠<a /g)?.length)
      // Every link to another site is one of the sources, and none opens a new window.
      const outbound = [...page.matchAll(/<a\b[^>]*\shref="(https?:[^"]+)"[^>]*>/g)].filter(([, url]) => !unescape(url!).startsWith('https://us2us.app/'))
      expect(outbound.length, path).toBe(WHY_SOURCES.filter(source => !source.url.startsWith('https://us2us.app/')).length)
      for (const [tag, url] of outbound) {
        expect(SOURCE_URLS.has(unescape(url!)), `${path}: ${url}`).toBe(true)
        expect(tag, path).toContain('rel="noopener noreferrer"')
      }
      expect(page, path).not.toMatch(/\starget="|testflight\.apple\.com/)
      // About SyconX, with the press contact; the close links only to our own pages.
      expect(page, path).toContain(`<section id="${WHY_SECTION.about}" class="card elev-sm about-card"`)
      expect(plain(page), path).toContain(text.about.title)
      expect(page, path).toContain('href="mailto:support@us2us.app"')
      expect(page, path).toContain(`href="${locale === 'tr' ? '/tr/destek#beta' : '/en/support#beta'}"`)
      // No third-party scripts: our own two files only.
      const scripts = [...page.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
      expect(scripts.map(([, attributes]) => attributes!.match(/\ssrc="([^"?]+)/)?.[1]).sort(), path).toEqual(['/js/site.js', '/js/theme.js'])
    }
  })

  it('puts "Neden yaptık / Why we built it" right after "Doğrulama / Verification" in every header, and first in every footer', async () => {
    for (const path of PAGES) {
      const locale = path.startsWith('/tr') ? 'tr' : 'en'
      const target = locale === 'tr' ? '/tr/neden-yaptik' : '/en/why-we-built-it'
      const page = await html(path)
      const nav = page.match(/<nav class="section-links"[^>]*>([\s\S]*?)<\/nav>/)![1]!
      const links = [...nav.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)]
      // The label a screen reader reads: the short phone label is hidden from it.
      const label = (inner: string) => inner.replace(/<span class="nav-page-short" aria-hidden="true">[^<]*<\/span>/, '').replace(/<[^>]+>/g, '')
      expect(links.map(([, , inner]) => label(inner!)), path).toEqual([...site[locale].nav.sections.map(section => section.label), site[locale].nav.why])
      expect(links.at(-2)![2], path).toBe(locale === 'tr' ? 'Doğrulama' : 'Verification')
      const [, attributes = '', inner] = links.at(-1)!
      expect(attributes, path).toContain(` class="nav-page" href="${target}"`)
      expect(inner, path).toBe(`<span class="nav-page-full">${site[locale].nav.why}</span><span class="nav-page-short" aria-hidden="true">${site[locale].nav.whyShort}</span>`)
      expect(attributes.includes('aria-current="page"'), path).toBe(WHY_PAGES.includes(path))
      const footer = page.slice(page.indexOf('<footer'))
      expect(footer.match(/<a class="footer-link"[^>]*>[^<]*<\/a>/)![0], path).toBe(`<a class="footer-link" href="${target}">${site[locale].nav.why}</a>`)
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
      const urls = [...page.matchAll(/(?:src|href)="([^"]+)"/g)].map(match => unescape(match[1]!))
      for (const url of urls) {
        if (url.startsWith('mailto:') || url.startsWith('#')) continue
        // The "why" page links to its sources (links only, checked in its own test).
        if (WHY_PAGES.includes(path) && SOURCE_URLS.has(url)) continue
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
      [/\.png$/, /^image\/png$/],
      [/\.ico$/, /^image\/(x-icon|vnd\.microsoft\.icon)$/],
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
      if (ICON_LINKS.some(link => link.href === url)) expect(response.headers.get('x-content-type-options'), url).toBe('nosniff')
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
      // The "why" page's sources are links to other sites (only there, and only as links: see its test).
      const sources = WHY_PAGES.some(path => file === join(PUBLIC_DIR, path, 'index.html')) ? SOURCE_URLS : new Set<string>()
      const external = [...content.matchAll(/https?:\/\/[^\s"'`)<>]+/g)].map(match => unescape(match[0])).filter(url => !allowed.test(url) && !sources.has(url))
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
