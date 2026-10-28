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
  for (const file of ['deploy-relay.sh', 'deploy-local-env.sh', 'prepare-relay-release.mjs']) copyFileSync(join(source, 'scripts', file), join(root, 'scripts', file))
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

  describe('scripts/deploy.local.env is read as data, from a private file only (audit L-13)', () => {
    const local = (root: string, text: string, mode = 0o600) => {
      const file = join(root, 'scripts/deploy.local.env')
      writeFileSync(file, text)
      chmodSync(file, mode)
      return file
    }
    const unset = { US2US_DEPLOY_TARGET: '', US2US_APP_ROOT: '' }
    const withoutTarget = () => {
      const { US2US_DEPLOY_TARGET: _t, US2US_APP_ROOT: _r, ...rest } = process.env
      return rest
    }

    it('refuses a file that group or others can read, before SSH', () => {
      const setup = fixture()
      local(setup.root, 'US2US_DEPLOY_TARGET=test@example.invalid\n', 0o644)
      const result = setup.run([], unset)
      expect(result.status).toBe(2)
      expect(result.stderr).toContain('chmod 600')
      expect(setup.events()).not.toContain('ssh')
    })

    it('never executes a value', () => {
      const setup = fixture()
      const marker = join(setup.root, 'executed')
      local(setup.root, `US2US_DEPLOY_TARGET=$(touch ${marker})\nUS2US_APP_ROOT=\`touch ${marker}\`\n`)
      const result = spawnSync('/bin/bash', [join(setup.root, 'scripts/deploy-relay.sh')], {
        cwd: setup.root, encoding: 'utf8', timeout: 10_000,
        env: { ...withoutTarget(), PATH: `${join(setup.root, 'bin')}:${dirname(process.execPath)}:/usr/bin:/bin`, EVENTS: join(setup.root, 'events'), US2US_SKIP_AUDIT: '0' },
      })
      expect(result.status).toBe(2)
      expect(result.stderr).toContain('Invalid deployment target')
      expect(existsSync(marker)).toBe(false)
      expect(setup.events()).not.toContain('ssh')
    })

    it('reads both the plain and the shell-default form, and lets the environment win', () => {
      const setup = fixture()
      local(setup.root, `# comment\n: "\${US2US_DEPLOY_TARGET:=file@example.invalid}"\nexport US2US_APP_ROOT='${join(setup.root, 'server')}'\nOTHER=1\n`)
      const run = (env: Record<string, string | undefined>) => spawnSync('/bin/bash', ['-c', `. "${join(setup.root, 'scripts/deploy-local-env.sh')}"; us2us_read_local_env "$1"; printf '%s|%s' "$US2US_DEPLOY_TARGET" "$US2US_APP_ROOT"`, 'x', join(setup.root, 'scripts/deploy.local.env')], {
        encoding: 'utf8', timeout: 10_000, env: { ...withoutTarget(), PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, ...env },
      })
      const fromFile = run({})
      expect(fromFile.status, fromFile.stderr).toBe(0)
      expect(fromFile.stdout).toBe(`file@example.invalid|${join(setup.root, 'server')}`)
      expect(fromFile.stderr).toContain('line 4 is not a US2US_NAME=value setting')
      expect(fromFile.stderr).not.toContain('OTHER=1')
      expect(run({ US2US_DEPLOY_TARGET: 'env@example.invalid' }).stdout).toBe(`env@example.invalid|${join(setup.root, 'server')}`)
    })
  })

  it.each(['/', '/tmp/..', '//', '/tmp/root;touch-bad'])('refuses an unsafe deployment root before SSH: %s', root => {
    const setup = fixture()
    expect(setup.run([], { US2US_APP_ROOT: root }).status).not.toBe(0)
    expect(setup.events()).not.toContain('ssh')
    expect(setup.events()).not.toContain('rsync')
  })
})
