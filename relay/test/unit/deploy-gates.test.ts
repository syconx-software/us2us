import { afterEach, describe, expect, it } from 'vitest'
import { spawnSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyRelease } from '../../../scripts/prepare-relay-release.mjs'

const source = fileURLToPath(new URL('../../..', import.meta.url))
const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'us2us-deploy-gates-')); roots.push(root)
  const write = (path: string, data: string, executable = false) => {
    const file = join(root, path); mkdirSync(dirname(file), { recursive: true }); writeFileSync(file, data)
    if (executable) chmodSync(file, 0o755)
  }
  for (const file of ['.output/server/index.mjs', '.output/server/node_modules/drizzle-orm/mysql2/index.js', '.output/server/node_modules/drizzle-orm/mysql2/migrator.js', 'drizzle/meta/_journal.json', 'deploy/ecosystem.config.cjs', 'deploy/runtime-migrate.mjs']) write(`relay/${file}`, '{}')
  write('relay/.output/server/package.json', JSON.stringify({ dependencies: { mysql2: '3', 'drizzle-orm': '0.45' } }))
  mkdirSync(join(root, 'scripts'))
  for (const file of ['deploy-relay.sh', 'prepare-relay-release.mjs']) copyFileSync(join(source, 'scripts', file), join(root, 'scripts', file))
  write('bin/pnpm', '#!/bin/bash\nprintf "pnpm:%s\\n" "$*" >> "$EVENTS"\nif [ "$*" = "run --silent audit" ] && [ "${FAIL_AUDIT:-0}" = 1 ]; then exit 1; fi\n', true)
  // No command in this fixture can contact production. Unexpected rsync/SSH is a test failure.
  write('bin/ssh', '#!/bin/bash\nprintf "ssh\\n" >> "$EVENTS"\nexit 1\n', true)
  write('bin/rsync', '#!/bin/bash\nprintf "rsync\\n" >> "$EVENTS"\nexit 99\n', true)
  const events = join(root, 'events')
  const run = (args: string[] = [], override: Record<string, string> = {}) => spawnSync('/bin/bash', [join(root, 'scripts/deploy-relay.sh'), ...args], {
    cwd: root, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, PATH: `${join(root, 'bin')}:${dirname(process.execPath)}:/usr/bin:/bin`, EVENTS: events,
      US2US_DEPLOY_TARGET: 'test@example.invalid', US2US_APP_ROOT: join(root, 'server'), US2US_SKIP_AUDIT: '0', ...override },
  })
  return { root, run, events: () => existsSync(events) ? readFileSync(events, 'utf8') : '' }
}

describe('deployment fail-closed gates (stubbed network)', () => {
  it('an audit failure stops before build, SSH or upload', () => {
    const setup = fixture()
    const result = setup.run([], { FAIL_AUDIT: '1' })
    expect(result.status).toBe(1)
    expect(setup.events()).toBe('pnpm:run --silent audit\n')
  })

  it('prepare-only creates a verifiable runtime package without reading deploy configuration or contacting SSH', () => {
    const setup = fixture(), destination = join(setup.root, 'prepared')
    writeFileSync(join(setup.root, 'scripts/deploy.local.env'), 'exit 99 # Must not be sourced by prepare-only\n')
    const result = setup.run(['--prepare-only', destination])
    expect(result.status, result.stderr).toBe(0)
    expect(verifyRelease(destination).files).toBe(7)
    expect(setup.events()).toBe('pnpm:run --silent audit\npnpm:build\n')
  })

  it('a missing private migration configuration stops before upload or any live switch', () => {
    const setup = fixture()
    const result = setup.run()
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('nothing uploaded')
    expect(setup.events()).toBe('pnpm:run --silent audit\npnpm:build\nssh\n')
  })

  it.each(['/', '/tmp/..', '//', '/tmp/root;touch-bad'])('refuses an unsafe deployment root before SSH: %s', root => {
    const setup = fixture()
    expect(setup.run([], { US2US_APP_ROOT: root }).status).not.toBe(0)
    expect(setup.events()).not.toContain('ssh')
    expect(setup.events()).not.toContain('rsync')
  })
})
