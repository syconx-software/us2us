import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import mysql from 'mysql2/promise'
import { prepareRelease, verifyRelease } from '../../../scripts/prepare-relay-release.mjs'
import { TEST_DATABASE_URL } from '../support/env'

const source = fileURLToPath(new URL('../..', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'us2us-isolated-release-'))
const artifact = join(work, 'release')
const suffix = randomBytes(6).toString('hex')
const database = `us2usrelease${suffix}`
const migrationUser = `um${suffix}`, runtimeUser = `ur${suffix}`
const password = randomBytes(24).toString('hex')
const migrationEnv = join(work, 'migrate.env'), runtimeEnv = join(work, 'relay.env')
let admin: mysql.Connection, relay: ChildProcess | undefined, runtimeUrl: string, migrationUrl: string
let serverOutput = ''
const urlFor = (user: string) => { const url = new URL(TEST_DATABASE_URL); url.username = user; url.password = password; url.pathname = `/${database}`; return url.href }
const run = (mode: string, ...files: string[]) => spawnSync(process.execPath, [join(artifact, 'deploy/runtime-migrate.mjs'), mode, ...files], {
  cwd: artifact, env: { PATH: dirname(process.execPath), NODE_ENV: 'production' }, encoding: 'utf8', timeout: 15_000,
})

beforeAll(async () => {
  prepareRelease(source, artifact)
  admin = await mysql.createConnection({ uri: TEST_DATABASE_URL })
  await admin.query(`CREATE DATABASE \`${database}\``)
  for (const user of [migrationUser, runtimeUser]) await admin.query('CREATE USER ?@\'localhost\' IDENTIFIED BY ?', [user, password])
  await admin.query(`GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`${database}\`.* TO ?@'localhost'`, [migrationUser])
  await admin.query(`GRANT SELECT, INSERT, UPDATE, DELETE ON \`${database}\`.* TO ?@'localhost'`, [runtimeUser])
  migrationUrl = urlFor(migrationUser); runtimeUrl = urlFor(runtimeUser)
  writeFileSync(migrationEnv, `DATABASE_URL=${migrationUrl}\n`, { mode: 0o600 })
  writeFileSync(runtimeEnv, `DATABASE_URL=${runtimeUrl}\nHOST=127.0.0.1\nLOG_IPS=0\nPOW_SECRET=${'x'.repeat(32)}\n`, { mode: 0o600 })
  // Every case starts on the fully migrated schema, even when selected on its own.
  const initial = run('--migrate', migrationEnv)
  if (initial.status !== 0) throw new Error(initial.stderr || 'isolated schema initialization failed')
})

afterAll(async () => {
  if (relay && relay.exitCode === null) {
    relay.kill('SIGTERM')
    await new Promise<void>(resolve => { const timer = setTimeout(() => { relay?.kill('SIGKILL'); resolve() }, 4000); relay!.once('exit', () => { clearTimeout(timer); resolve() }) })
  }
  if (admin) {
    try {
      await admin.query(`DROP DATABASE IF EXISTS \`${database}\``)
      for (const user of [migrationUser, runtimeUser]) await admin.query('DROP USER IF EXISTS ?@\'localhost\'', [user])
    } finally { await admin.end() }
  }
  rmSync(work, { recursive: true, force: true })
})

describe('isolated production runtime package', () => {
  it('migrates idempotently using only packaged runtime dependencies and a separate DDL identity', async () => {
    expect(verifyRelease(artifact).files).toBeGreaterThan(10)
    const first = run('--migrate', migrationEnv)
    expect(first.status, first.stderr).toBe(0)
    const [[before]] = await admin.query<mysql.RowDataPacket[]>(`SELECT COUNT(*) AS n FROM \`${database}\`.__drizzle_migrations`)
    expect(Number(before!.n)).toBeGreaterThan(0)
    expect(run('--migrate', migrationEnv).status).toBe(0)
    const [[after]] = await admin.query<mysql.RowDataPacket[]>(`SELECT COUNT(*) AS n FROM \`${database}\`.__drizzle_migrations`)
    expect(after!.n).toBe(before!.n)
    expect(run('--check', migrationEnv, runtimeEnv).status).toBe(0)
  })

  it('the runtime user can use tables but cannot change their schema', async () => {
    const connection = await mysql.createConnection({ uri: runtimeUrl })
    try {
      await expect(connection.query('SELECT COUNT(*) FROM accounts')).resolves.toBeDefined()
      await expect(connection.query('CREATE TABLE forbidden_table (id INT)')).rejects.toMatchObject({ code: 'ER_TABLEACCESS_DENIED_ERROR' })
      await expect(connection.query('ALTER TABLE accounts ADD COLUMN forbidden_column INT')).rejects.toMatchObject({ code: 'ER_TABLEACCESS_DENIED_ERROR' })
    } finally { await connection.end() }
  })

  it('fails closed on credential reuse, broad runtime grants, extra secrets and unsafe env permissions', async () => {
    const original = readFileSync(migrationEnv, 'utf8')
    writeFileSync(migrationEnv, `DATABASE_URL=${runtimeUrl}\n`)
    expect(run('--check', migrationEnv, runtimeEnv).status).not.toBe(0)
    writeFileSync(migrationEnv, original)
    const originalRuntime = readFileSync(runtimeEnv, 'utf8')
    writeFileSync(runtimeEnv, originalRuntime.replace(runtimeUrl, migrationUrl))
    const privilegedCheck = run('--check', migrationEnv, runtimeEnv)
    expect(privilegedCheck.status).not.toBe(0)
    expect(privilegedCheck.stderr).not.toContain(password)
    writeFileSync(runtimeEnv, originalRuntime)
    await admin.query(`GRANT CREATE ON \`${database}\`.* TO ?@'localhost'`, [runtimeUser])
    try { expect(run('--check', migrationEnv, runtimeEnv).status).not.toBe(0) }
    finally { await admin.query(`REVOKE CREATE ON \`${database}\`.* FROM ?@'localhost'`, [runtimeUser]) }
    writeFileSync(migrationEnv, original + 'POW_SECRET=not-allowed\n')
    const extra = run('--migrate', migrationEnv)
    expect(extra.status).not.toBe(0)
    expect(extra.stderr).not.toContain(password)
    writeFileSync(migrationEnv, original)
    chmodSync(migrationEnv, 0o644)
    expect(run('--migrate', migrationEnv).status).not.toBe(0)
    chmodSync(migrationEnv, 0o600)
  })

  it('serves health and enforces API authentication with no source/build modules or DDL rights', async () => {
    const reserve = createServer()
    await new Promise<void>(resolve => reserve.listen(0, '127.0.0.1', resolve))
    const address = reserve.address()
    if (!address || typeof address === 'string') throw new Error('no test port')
    const port = address.port
    await new Promise<void>(resolve => reserve.close(() => resolve()))
    relay = spawn(process.execPath, [join(artifact, '.output/server/index.mjs')], { cwd: artifact,
      env: { PATH: dirname(process.execPath), NODE_ENV: 'production', HOST: '127.0.0.1', PORT: String(port), DATABASE_URL: runtimeUrl, POW_SECRET: 'x'.repeat(32), MEDIA_DIR: join(work, 'media'), LOG_LEVEL: 'error' },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    relay.stdout!.on('data', data => { serverOutput += String(data) })
    relay.stderr!.on('data', data => { serverOutput += String(data) })
    const base = `http://127.0.0.1:${port}`
    let ready = false
    for (let attempt = 0; attempt < 100; attempt++) {
      if (relay.exitCode !== null) throw new Error('isolated relay exited before health check')
      try { ready = (await fetch(`${base}/v1/health`)).ok } catch { /* Starting. */ }
      if (ready) break
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    expect(ready, 'isolated relay must start').toBe(true)
    expect((await fetch(`${base}/v1/accounts/me`)).status).toBe(401)
    expect(serverOutput).not.toContain(password)
  })
})
