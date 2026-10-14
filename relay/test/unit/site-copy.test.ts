import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

/**
 * Absolute privacy claims the protocol cannot keep (PROTOCOL.md §9): sealed
 * sender hides the sender from the envelope, not from IP addresses and timing,
 * and the post-quantum protection covers message content only. A profile
 * picture shown in search is stored by the relay, encrypted under the
 * username only (§8.10). Public copy says so; these phrases must not come
 * back (also across a line break).
 */
const OVERSTATED: RegExp[] = [
  'kimden geldiğini bilmez',
  'mühürlü bir mesajı kimin gönderdiği',
  'mühürlü mesajların göndericisini bilmez',
  'kimin kime yazdığı gizli',
  'ne de bize bildirilir',
  'kuantum sonrası korumalı)',
  'tam kuantum',
  'not who sent it',
  'who sent a sealed message',
  'never knows who',
  'does not learn who sends',
  'who talks to whom stays private',
  'not us, gets a report',
  'post-quantum protected)',
  'fully quantum',
  'görünen adınız ve profil fotoğrafınız',
  'kişi listelerini, profilleri',
  'display name and profile photo',
  'contact lists, profiles',
].map(phrase => new RegExp(phrase.replace(/[()]/g, '\\$&').replace(/ /g, '\\s+'), 'i'))
  .concat([/\banonim\b/i, /\banonymous(ly)?\b/i])

describe('public copy', () => {
  for (const [name, path] of [
    ['website (app/content/site.ts)', '../../app/content/site.ts'],
    ['"why we built it" page (app/content/why.ts)', '../../app/content/why.ts'],
    ['relay README', '../../README.md'],
  ] as const) {
    it(`${name} makes no absolute sender or post-quantum claims`, () => {
      const text = read(path)
      for (const phrase of OVERSTATED) expect(text, `${name}: ${phrase}`).not.toMatch(phrase)
    })
  }
})
