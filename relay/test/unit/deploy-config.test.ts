import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

interface PnpmSettings {
  overrides: Record<string, string>
  auditConfig: { ignoreGhsas: string[] }
}

const manifest = JSON.parse(read('../../package.json')) as { scripts: Record<string, string>, pnpm: PnpmSettings }
const readme = read('../../README.md')

describe('deploy/ecosystem.config.cjs', () => {
  const { apps } = createRequire(import.meta.url)('../../deploy/ecosystem.config.cjs') as { apps: Record<string, unknown>[] }

  it('runs one built relay process in production mode', () => {
    expect(apps).toHaveLength(1)
    const app = apps[0]
    expect(app).toMatchObject({
      name: 'us2us-relay',
      script: '.output/server/index.mjs',
      exec_mode: 'fork',
      instances: 1,
      env: { NODE_ENV: 'production' },
    })
    expect(app?.node_args).toMatch(/^--env-file=.+us2us\.env$/)
    // Shutdown writes the envelopes held in memory and may take up to Nitro's
    // limit (NITRO_SHUTDOWN_TIMEOUT, 30 s); pm2 must not kill it earlier.
    expect(app?.kill_timeout).toBeGreaterThan(30_000)
  })
})

describe('dependency audit settings', () => {
  const start = readme.indexOf('\n## Dependencies\n')
  const dependencies = readme.slice(start, readme.indexOf('\n## ', start + 1))

  it('fails on advisories of every severity', () => {
    expect(manifest.scripts.audit).toBe('pnpm audit --audit-level=low')
  })

  it('explains every ignored advisory and every override in the README', () => {
    expect(start).toBeGreaterThan(0)
    for (const id of manifest.pnpm.auditConfig.ignoreGhsas) expect(dependencies).toContain(`| ${id} |`)
    for (const selector of Object.keys(manifest.pnpm.overrides)) expect(dependencies).toContain(`| \`${selector}\` →`)
  })
})

describe('deploy/nginx/us2us.conf', () => {
  const conf = read('../../deploy/nginx/us2us.conf')
  const code = conf.replace(/#.*$/gm, '')

  /** The `map $remote_addr $us2us_client` block, applied as nginx does: regexes in order, else the default. */
  function clientKey(address: string): string {
    const block = /map \$remote_addr \$us2us_client \{([\s\S]*?)\n\}/.exec(code)![1]!
    const entries = [...block.matchAll(/^\s*"~(.+?)"\s+(\S+);/gm)].map(([, pattern, value]) => [new RegExp(pattern!), value!] as const)
    expect(entries.length).toBeGreaterThan(0)
    for (const [pattern, value] of entries) {
      const match = pattern.exec(address)
      if (match) return value.replace('$g', match.groups!.g!)
    }
    return `raw:${address}`
  }

  it('limits an IPv6 client by its /64 under every compressed spelling, and IPv4 by address', () => {
    // Addresses of one /64, as nginx prints them (RFC 5952 compression).
    const sameNet = ['2001:db8:1:2::1', '2001:db8:1:2::ffff:9', '2001:db8:1:2:a:b:c:d', '2001:db8:1:2:0:0:0:1']
    expect(new Set(sameNet.map(clientKey))).toEqual(new Set(['2001:db8:1:2']))
    expect(new Set(['2001:db8:0:0:1::1', '2001:db8::1', '2001:db8::1:2:3:4'].map(clientKey)).size).toBeLessThanOrEqual(2)
    expect(clientKey('2001:db8::1')).toBe('2001:db8:0:0')
    expect(clientKey('2001:db8:1::5')).toBe('2001:db8:1:0')
    expect(clientKey('2001:db8:1:3::1')).not.toBe(clientKey('2001:db8:1:2::1'))
    expect(clientKey('198.51.100.7')).toBe('raw:198.51.100.7')
  })

  it('keys connection and request limits by that client, sends both address headers, and covers subdomains in HSTS', () => {
    expect(code).toMatch(/limit_conn_zone \$us2us_client zone=us2us_media/)
    expect(code).toMatch(/limit_req_zone \$us2us_client zone=us2us_api/)
    expect(code).not.toMatch(/limit_(conn|req)_zone \$binary_remote_addr/)
    const forwarded = code.match(/proxy_set_header X-Forwarded-For \$remote_addr;/g)!.length
    expect(code.match(/proxy_set_header X-Real-IP \$remote_addr;/g)!.length).toBe(forwarded)
    for (const hsts of code.match(/Strict-Transport-Security "[^"]*"/g)!) expect(hsts).toContain('includeSubDomains')
  })
})
