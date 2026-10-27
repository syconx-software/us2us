import { afterEach, describe, expect, it } from 'vitest'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { prepareRelease, verifyRelease } from '../../../scripts/prepare-relay-release.mjs'
import { assertRuntimeGrants, assertSeparateDatabaseUsers, failureCategory, readMigrationEnvironment } from '../../deploy/runtime-migrate.mjs'

const roots: string[] = []
const makeRoot = () => { const root = mkdtempSync(join(tmpdir(), 'us2us-release-policy-')); roots.push(root); return root }
const write = (root: string, path: string, data: string) => { mkdirSync(dirname(join(root, path)), { recursive: true }); writeFileSync(join(root, path), data) }
function fixture() {
  const root = makeRoot()
  for (const file of ['.output/server/index.mjs', '.output/server/node_modules/drizzle-orm/mysql2/index.js', '.output/server/node_modules/drizzle-orm/mysql2/migrator.js', 'drizzle/meta/_journal.json', 'deploy/ecosystem.config.cjs', 'deploy/runtime-migrate.mjs']) write(root, file, '{}')
  write(root, '.output/server/package.json', JSON.stringify({ dependencies: { mysql2: '3.24.5', 'drizzle-orm': '0.45.3' } }))
  return root
}
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

describe('runtime-only release packaging', () => {
  it('copies only runtime inputs and preserves an existing destination', () => {
    const source = fixture(), destination = join(makeRoot(), 'release')
    write(source, '.env.production', 'REAL_SECRET=do-not-copy')
    write(source, 'node_modules/build-tool/index.js', 'build-only')
    write(source, 'server/source.ts', 'source-only')
    const result = prepareRelease(source, destination)
    expect(result.files).toBe(7)
    expect(existsSync(join(destination, '.env.production'))).toBe(false)
    expect(existsSync(join(destination, 'node_modules'))).toBe(false)
    expect(existsSync(join(destination, 'server'))).toBe(false)
    expect(verifyRelease(destination)).toEqual(result)
    expect(() => prepareRelease(source, destination)).toThrow('must not exist')
    expect(verifyRelease(destination)).toEqual(result)
  })

  it('rejects links outside a runtime input instead of dereferencing source credentials', () => {
    const source = fixture(), destination = join(makeRoot(), 'release')
    write(source, '.env', 'SECRET=do-not-copy')
    symlinkSync(join(source, '.env'), join(source, '.output/server/innocent.js'))
    expect(() => prepareRelease(source, destination)).toThrow('external')
    expect(existsSync(destination)).toBe(false)
  })

  it('rejects a deployment helper reached through an external parent-directory link', () => {
    const source = fixture(), outside = makeRoot(), destination = join(makeRoot(), 'release')
    rmSync(join(source, 'deploy'), { recursive: true })
    write(outside, 'ecosystem.config.cjs', 'private-outside-source')
    write(outside, 'runtime-migrate.mjs', 'private-outside-source')
    symlinkSync(outside, join(source, 'deploy'))
    expect(() => prepareRelease(source, destination)).toThrow('external symlink')
    expect(existsSync(destination)).toBe(false)
  })

  it.each(['.output/.env', '.output/private.key'])('rejects a credential file in the runtime output: %s', (name) => {
    const source = fixture(), destination = join(makeRoot(), 'release')
    write(source, name, 'not-a-real-credential')
    expect(() => prepareRelease(source, destination)).toThrow('credential')
    expect(existsSync(destination)).toBe(false)
  })

  it('refuses missing migrator and build-only dependency leakage', () => {
    const source = fixture(), destination = join(makeRoot(), 'release')
    rmSync(join(source, '.output/server/node_modules/drizzle-orm/mysql2/migrator.js'))
    expect(() => prepareRelease(source, destination)).toThrow('required runtime file')
    write(source, '.output/server/node_modules/drizzle-orm/mysql2/migrator.js', '{}')
    write(source, '.output/server/package.json', JSON.stringify({ dependencies: { mysql2: '3', 'drizzle-orm': '0.45', 'node-forge': '1.4.0' } }))
    expect(() => prepareRelease(source, destination)).toThrow('build tools')
  })

  it('detects modified, extra and missing files and a different Node major', () => {
    const source = fixture(), destination = join(makeRoot(), 'release')
    prepareRelease(source, destination)
    const entry = join(destination, '.output/server/index.mjs'), original = readFileSync(entry)
    writeFileSync(entry, 'tampered')
    expect(() => verifyRelease(destination)).toThrow('checksum')
    writeFileSync(entry, original)
    write(destination, '.output/extra.txt', 'extra')
    expect(() => verifyRelease(destination)).toThrow('file list')
    rmSync(join(destination, '.output/extra.txt'))
    const metadata = JSON.parse(readFileSync(join(destination, 'release.json'), 'utf8'))
    metadata.nodeMajor++
    writeFileSync(join(destination, 'release.json'), JSON.stringify(metadata))
    expect(() => verifyRelease(destination)).toThrow('Node major')
  })
})

describe('migration secret and privilege boundaries', () => {
  it('reports a fixed error category without reflecting driver messages or unknown codes', () => {
    expect(failureCategory({ message: 'mysql://private:password@host/db', cause: { code: 'ER_TABLEACCESS_DENIED_ERROR' } })).toBe('database-privileges')
    expect(failureCategory({ code: 'PASSWORD_OR_OTHER_SECRET', message: 'sensitive SQL' })).toBe('configuration-or-migration')
  })
  it('reads a private one-field env as data, never as shell code', () => {
    const file = join(makeRoot(), 'migration.env')
    writeFileSync(file, '# Private migration identity\nDATABASE_URL="mysql://migrate:p@localhost/db"\n', { mode: 0o600 })
    expect(readMigrationEnvironment(file)).toBe('mysql://migrate:p@localhost/db')
    chmodSync(file, 0o644)
    expect(() => readMigrationEnvironment(file)).toThrow('private')
    chmodSync(file, 0o600)
    writeFileSync(file, 'DATABASE_URL=mysql://migrate:p@localhost/db\nPOW_SECRET=do-not-load\n')
    expect(() => readMigrationEnvironment(file)).toThrow('only DATABASE_URL')
  })

  it('rejects a symlinked env and keeps credential URLs out of public errors', () => {
    const root = makeRoot(), file = join(root, 'migration.env')
    writeFileSync(file, 'DATABASE_URL=not-a-valid-secret-url', { mode: 0o600 })
    expect(() => readMigrationEnvironment(file)).toThrow('invalid migration database URL')
    symlinkSync(file, join(root, 'link.env'))
    expect(() => readMigrationEnvironment(join(root, 'link.env'))).toThrow('private regular file')
  })

  it('rejects one principal under another password/encoding, or another target database', () => {
    expect(() => assertSeparateDatabaseUsers('mysql://migrate:a@localhost/db', 'mysql://relay:b@localhost/db')).not.toThrow()
    for (const runtime of ['mysql://migrate:b@localhost/db', 'mysql://%6digrate:c@localhost/db', 'mysql://relay:b@elsewhere/db', 'mysql://relay:b@localhost/other']) {
      expect(() => assertSeparateDatabaseUsers('mysql://migrate:a@localhost/db', runtime)).toThrow('different users')
    }
  })

  it('accepts only direct DML on the configured database, without roles or grant delegation', () => {
    const usage = 'GRANT USAGE ON *.* TO `relay`@`localhost`'
    const dml = 'GRANT SELECT, INSERT, UPDATE, DELETE ON `us2us`.* TO `relay`@`localhost`'
    expect(() => assertRuntimeGrants([usage, dml], 'NONE', 'us2us')).not.toThrow()
    for (const grant of [dml.replace('DELETE', 'CREATE'), dml.replace('`us2us`', '`other`'), dml + ' WITH GRANT OPTION', 'GRANT ALL PRIVILEGES ON *.* TO `relay`@`localhost`', 'GRANT `administrator`@`%` TO `relay`@`localhost`']) {
      expect(() => assertRuntimeGrants([usage, grant], 'NONE', 'us2us')).toThrow()
    }
    expect(() => assertRuntimeGrants([dml], '`admin`@`%`', 'us2us')).toThrow()
  })
})
