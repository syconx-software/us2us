#!/usr/bin/env node
// Runs only from a prepared release, using its traced runtime dependencies. No drizzle-kit.
import { lstatSync, readFileSync, realpathSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseEnv } from 'node:util'

function readPrivateFile(path) {
  const stat = lstatSync(path)
  if (!stat.isFile() || (stat.mode & 0o077) !== 0 || stat.uid !== process.getuid?.()) throw new Error('environment must be an owned private regular file')
  return readFileSync(path, 'utf8')
}

export function readMigrationEnvironment(path) {
  const lines = readPrivateFile(path).split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#'))
  if (lines.length !== 1 || !/^DATABASE_URL=/.test(lines[0])) throw new Error('migration environment must contain only DATABASE_URL')
  let value = lines[0].slice('DATABASE_URL='.length).trim()
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1)
  let parsed
  try { parsed = new URL(value) } catch { throw new Error('invalid migration database URL') }
  if (parsed.protocol !== 'mysql:' || !parsed.username || parsed.pathname.length < 2) throw new Error('invalid migration database URL')
  return value
}

export function assertRuntimeGrants(grants, role, database) {
  if (role !== 'NONE' || !/^[A-Za-z_][A-Za-z0-9_$]*$/.test(database) || !grants.length) throw new Error('runtime grants require review')
  for (const grant of grants) {
    const match = /^GRANT (.+) ON (.+) TO /.exec(grant)
    if (!match || /WITH GRANT OPTION/.test(grant)) throw new Error('runtime privileges exceed DML')
    const privileges = match[1].split(',').map(value => value.trim())
    if (privileges.every(value => value === 'USAGE') && match[2] === '*.*') continue
    if (match[2] !== `\`${database}\`.*` || privileges.some(value => !['SELECT', 'INSERT', 'UPDATE', 'DELETE'].includes(value))) {
      throw new Error('runtime privileges exceed DML or the configured database')
    }
  }
}

export async function checkDeployment(root, migrationFile, runtimeFile) {
  const migrationUrl = readMigrationEnvironment(migrationFile)
  const runtime = parseEnv(readPrivateFile(runtimeFile))
  assertSeparateDatabaseUsers(migrationUrl, runtime.DATABASE_URL ?? '')
  if (runtime.HOST !== '127.0.0.1' || !['0', 'false'].includes(runtime.LOG_IPS ?? '0')) throw new Error('runtime must bind loopback and keep IP logging disabled')
  const require = createRequire(join(root, '.output/server/package.json'))
  const mysql = require('mysql2/promise')
  const connection = await mysql.createConnection({ uri: runtime.DATABASE_URL })
  try {
    const [grants] = await connection.query('SHOW GRANTS FOR CURRENT_USER')
    const [[state]] = await connection.query('SELECT CURRENT_ROLE() AS role, DATABASE() AS db')
    assertRuntimeGrants(grants.map(row => Object.values(row)[0]), state.role, state.db)
  } finally { await connection.end() }
}

export function assertSeparateDatabaseUsers(migrationUrl, runtimeUrl) {
  let migration, runtime
  try { migration = new URL(migrationUrl); runtime = new URL(runtimeUrl) } catch { throw new Error('invalid database configuration') }
  if (migration.protocol !== 'mysql:' || runtime.protocol !== 'mysql:' || !runtime.username
      || migration.host !== runtime.host || migration.pathname !== runtime.pathname
      || decodeURIComponent(migration.username) === decodeURIComponent(runtime.username)) {
    throw new Error('migration and relay must use different users for the same database')
  }
}

export async function migrateRuntime(root, databaseUrl) {
  const require = createRequire(join(root, '.output/server/package.json'))
  const mysql = require('mysql2/promise')
  const { drizzle } = await import(pathToFileURL(join(root, '.output/server/node_modules/drizzle-orm/mysql2/index.js')).href)
  const { migrate } = await import(pathToFileURL(join(root, '.output/server/node_modules/drizzle-orm/mysql2/migrator.js')).href)
  const connection = await mysql.createConnection({ uri: databaseUrl })
  try { await migrate(drizzle({ client: connection }), { migrationsFolder: join(root, 'drizzle') }) } finally { await connection.end() }
}

export function failureCategory(error) {
  const categories = {
    ER_TABLEACCESS_DENIED_ERROR: 'database-privileges', ER_DBACCESS_DENIED_ERROR: 'database-privileges',
    ER_COLUMNACCESS_DENIED_ERROR: 'database-privileges', ER_ACCESS_DENIED_ERROR: 'database-authentication',
    ER_BAD_DB_ERROR: 'database-missing', ECONNREFUSED: 'database-unreachable', ETIMEDOUT: 'database-timeout',
    ERR_MODULE_NOT_FOUND: 'runtime-package-incomplete', MODULE_NOT_FOUND: 'runtime-package-incomplete',
  }
  for (let depth = 0; error && depth < 5; depth++, error = error.cause) {
    if (Object.hasOwn(categories, error.code)) return categories[error.code]
  }
  return 'configuration-or-migration'
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    const [mode, file, runtimeFile, extra] = process.argv.slice(2)
    if (!['--check', '--migrate'].includes(mode) || !file) throw new Error('expected --check|--migrate <private-migration-env>')
    if (extra || (mode === '--migrate' && runtimeFile) || (mode === '--check' && !runtimeFile)) throw new Error('unexpected environment arguments')
    const root = dirname(dirname(fileURLToPath(import.meta.url)))
    // No environment file is sourced or exported. Only the check reads runtime configuration;
    // the separate migration process gets the DDL URL from its one-field private file.
    if (mode === '--check') {
      await checkDeployment(root, file, runtimeFile)
      console.log('Deployment preflight: separate principals, runtime DML, loopback and no IP logging verified')
    } else {
      await migrateRuntime(root, readMigrationEnvironment(file))
      console.log('Migrations completed')
    }
  } catch (error) {
    // Driver errors can contain credential URLs, usernames, queries and database details.
    console.error(`Migration preflight or execution failed (${failureCategory(error)}); configuration/DB details withheld`)
    process.exitCode = 1
  }
}
