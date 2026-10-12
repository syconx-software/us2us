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
