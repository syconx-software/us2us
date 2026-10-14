import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import mysql from 'mysql2/promise'
import { drizzle } from 'drizzle-orm/mysql2'
import { migrate } from 'drizzle-orm/mysql2/migrator'
import { TEST_DATABASE_URL } from './env'

const root = fileURLToPath(new URL('../..', import.meta.url))

/**
 * Starts every run from an empty schema built by the committed migrations,
 * which also proves that the migrations apply cleanly.
 */
export default async function setup(): Promise<void> {
  if (!existsSync(`${root}/.output/server/index.mjs`)) {
    throw new Error('the relay is not built; run `pnpm build` (or `pnpm test`, which builds first)')
  }
  const connection = await mysql.createConnection({ uri: TEST_DATABASE_URL })
  try {
    const [tables] = await connection.query<mysql.RowDataPacket[]>(
      'SELECT table_name AS name FROM information_schema.tables WHERE table_schema = DATABASE()',
    )
    await connection.query('SET FOREIGN_KEY_CHECKS = 0')
    try {
      for (const { name } of tables) await connection.query(`DROP TABLE \`${name as string}\``)
    } finally {
      await connection.query('SET FOREIGN_KEY_CHECKS = 1')
    }
    await migrate(drizzle({ client: connection }), { migrationsFolder: `${root}/drizzle` })
  } finally {
    await connection.end()
  }
}
