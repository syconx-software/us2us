import mysql from 'mysql2/promise'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Api, register } from '../support/client'
import { TEST_DATABASE_URL } from '../support/env'
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
const textOf = (page: string) => page.replace(/<(script|style)\b[\s\S]*?<\/\1>/g, ' ').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/\s+/g, ' ')
const headingOf = (page: string) => textOf(page.match(/<h1\b[\s\S]*?<\/h1>/)![0]).trim()

describe('invite page', () => {
  it('greets in the visitor’s language, with a link to the other one', async () => {
    const cases: Array<[Record<string, string>, string, string]> = [
      [{ 'accept-language': 'tr-TR,tr;q=0.9,en;q=0.8' }, 'tr', 'us2us’ta @selin.n seninle konuşmak istiyor'],
      [{ 'accept-language': 'en-GB,en;q=0.9' }, 'en', '@selin.n wants to talk to you on us2us'],
      [{ 'accept-language': 'de-DE,tr;q=0.5' }, 'tr', 'us2us’ta @selin.n seninle konuşmak istiyor'],
      [{}, 'en', '@selin.n wants to talk to you on us2us'],
    ]
    for (const [headers, lang, heading] of cases) {
      const response = await get('/u/selin.n', headers)
      expect(response.status).toBe(200)
      expect(response.headers.get('content-type')).toBe('text/html; charset=utf-8')
      expect(response.headers.get('vary')).toBe('accept-language')
      const page = await response.text()
      expect(page).toContain(`<html lang="${lang}">`)
      expect(headingOf(page)).toBe(heading)
      const other = lang === 'tr' ? 'en' : 'tr'
      expect(page).toContain(`href="/u/selin.n?lang=${other}" hreflang="${other}"`)
    }
    // The language switch: ?lang= wins over the header.
    const switched = await (await get('/u/selin.n?lang=en', { 'accept-language': 'tr' })).text()
    expect(switched).toContain('<html lang="en">')
    expect((await get('/u/deniz/')).status).toBe(200)
    expect((await fetch(`${relay.url}/u/deniz`, { method: 'HEAD' })).status).toBe(200)
  })

  it('sends the site’s strict CSP, noindex and no cookie', async () => {
    const response = await get('/u/deniz')
    const csp = response.headers.get('content-security-policy')!
    expect(csp).toContain('default-src \'none\'')
    expect(csp).toContain('script-src \'self\';')
    expect(csp).toContain('style-src \'self\';')
    expect(csp).toContain('frame-ancestors \'none\'')
    expect(csp).toContain('require-trusted-types-for \'script\'; trusted-types \'none\'')
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|https?:/)
    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(response.headers.get('referrer-policy')).toBe('no-referrer')
    expect(response.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    expect(response.headers.get('cache-control')).toBe('no-cache')
    expect(response.headers.get('set-cookie')).toBeNull()
    const page = await response.text()
    expect(page).toContain('<meta name="robots" content="noindex, nofollow">')
    // The same asset version as the prerendered pages.
    const version = (await (await get('/en')).text()).match(/\/css\/site\.css\?v=([\w-]+)/)![1]
    expect(page).toContain(`<link rel="stylesheet" href="/css/site.css?v=${version}">`)
    expect(page).toContain(`<script src="/js/theme.js?v=${version}"></script>`)
    expect(page).not.toMatch(/\sstyle=|<style|<script>/)
  })

  it('answers malformed names with the normal 404, never echoing them', async () => {
    const notFound = await (await get('/does/not/exist')).text()
    const paths = [
      '/u/', '/u/ab', `/u/${'a'.repeat(25)}`, '/u/Deniz', '/u/%3Cscript%3E', '/u/de%22niz', '/u/deniz%00', '/u/de%20niz',
      '/u/deniz/x', '/u/...', '/u/%C5%9Feyma', '/u/deniz;', '/U/deniz', '/u//deniz',
    ]
    for (const path of paths) {
      const response = await get(path)
      expect(response.status, path).toBe(404)
      expect(await response.text(), path).toBe(notFound)
    }
    // Only GET and HEAD.
    const posted = await fetch(`${relay.url}/u/deniz`, { method: 'POST', body: 'x' })
    expect(posted.status).toBe(404)
  })

  it('looks the same for every well-formed name, whether or not the account exists', async () => {
    // Nothing is looked up, so a registered-looking name and a random one get the same page.
    const a = (await (await get('/u/selin.n', { 'accept-language': 'tr' })).text()).replaceAll('selin.n', 'NAME')
    const b = (await (await get('/u/q9_x7_nobody', { 'accept-language': 'tr' })).text()).replaceAll('q9_x7_nobody', 'NAME')
    expect(a).toBe(b)
  })

  it('sends no query to the database', async () => {
    const connection = await mysql.createConnection({ uri: TEST_DATABASE_URL })
    const counters = async () => {
      const [rows] = await connection.query<mysql.RowDataPacket[]>(
        'SHOW GLOBAL STATUS WHERE Variable_name IN (\'Com_select\', \'Com_insert\', \'Com_update\', \'Com_delete\', \'Com_replace\')',
      )
      return rows.reduce((sum, row) => sum + Number(row.Value), 0)
    }
    try {
      // Wait until the relay's start-up work (a retention sweep) is over.
      let before = await counters()
      for (let i = 0; i < 20; i++) {
        await new Promise(resolve => setTimeout(resolve, 150))
        const now = await counters()
        if (now === before) break
        before = now
      }
      for (let i = 0; i < 40; i++) {
        const response = await get(`/u/someone_${i}`, { 'accept-language': i % 2 ? 'tr' : 'en' })
        expect(response.status).toBe(200)
        await response.arrayBuffer()
      }
      expect(await counters()).toBe(before)
      // The counters do see the relay's queries: a registration writes.
      await register(new Api(relay.url))
      expect(await counters()).toBeGreaterThan(before)
    } finally {
      await connection.end()
    }
  })
})

describe('apple-app-site-association', () => {
  it('is served as JSON from /.well-known, without a redirect', async () => {
    const response = await get('/.well-known/apple-app-site-association')
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('application/json')
    expect(response.headers.get('location')).toBeNull()
    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(await response.json()).toEqual({
      applinks: {
        details: [{
          appIDs: ['C8QW8MBYQX.com.syconx.us2us'],
          components: [{ '/': '/u/*', 'comment': 'Invite links: https://us2us.app/u/<username>' }],
        }],
      },
    })
    expect((await fetch(`${relay.url}/.well-known/apple-app-site-association`, { method: 'HEAD' })).status).toBe(200)
    // Nothing else under that name.
    for (const path of ['/.well-known/apple-app-site-association.json', '/apple-app-site-association', '/.well-known/']) {
      expect((await get(path)).status, path).toBe(404)
    }
  })
})
