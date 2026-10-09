#!/usr/bin/env node
// Scratch MySQL databases for scripts/interop.sh (and anything else that
// needs a throw-away relay database), without a mysql client binary:
//
//   node scripts/scratch-db.mjs create <admin-url> <name>   prints the database URL
//   node scripts/scratch-db.mjs drop <admin-url> <name>
//   node scripts/scratch-db.mjs envelopes <database-url>    prints the number of stored envelopes
//
// <admin-url> is a server URL with a user that may create databases, e.g.
// mysql://root@127.0.0.1:3307. Names are restricted to [a-z0-9_]; `create`
// refuses an existing database, so nothing is ever reused or overwritten;
// the character set matches deploy/mysql/setup.sql.
// Apply the schema afterwards with `pnpm db:migrate`.

import mysql from 'mysql2/promise'

const [command, url, name] = process.argv.slice(2)

function databaseName(value) {
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(value ?? '')) throw new Error('database names are [a-z][a-z0-9_]{0,63}')
  return value
}

function serverUrl(value) {
  const parsed = new URL(value)
  if (parsed.protocol !== 'mysql:') throw new Error('expected a mysql:// URL')
  parsed.pathname = ''
  return parsed
}

async function withConnection(uri, work) {
  const connection = await mysql.createConnection({ uri })
  try {
    return await work(connection)
  } finally {
    await connection.end()
  }
}

async function main() {
  switch (command) {
    case 'create': {
      const server = serverUrl(url)
      const database = databaseName(name)
      await withConnection(server.href, connection =>
        connection.query(`CREATE DATABASE \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`))
      server.pathname = `/${database}`
      console.log(server.href)
      break
    }
    case 'drop': {
      const database = databaseName(name)
      await withConnection(serverUrl(url).href, connection => connection.query(`DROP DATABASE IF EXISTS \`${database}\``))
      break
    }
    case 'envelopes': {
      const [[row]] = await withConnection(url, connection => connection.query('SELECT COUNT(*) AS n FROM envelopes'))
      console.log(Number(row.n))
      break
    }
    default:
      console.error('usage: scratch-db.mjs create|drop <admin-url> <name> | envelopes <database-url>')
      process.exit(2)
  }
}

main().catch((error) => {
  console.error(`scratch-db: ${error.code ?? error.message}`)
  process.exit(1)
})
