import mysql from 'mysql2/promise'
import { drizzle, type MySql2Database } from 'drizzle-orm/mysql2'
import * as schema from './schema'

export type Db = MySql2Database<typeof schema>

export interface Database {
  db: Db
  /**
   * Runs `work` in a READ COMMITTED transaction on a dedicated connection.
   *
   * drizzle's own `transaction()` acquires a pooled connection before its
   * try/finally, so a failing BEGIN (for example while MySQL restarts) leaks
   * the connection and slowly exhausts the pool. This version always gives
   * the connection back, and destroys it when its state is unknown.
   * READ COMMITTED avoids gap locks; the pre-key claim relies on it together
   * with SKIP LOCKED.
   */
  transaction<T>(work: (tx: Db) => Promise<T>): Promise<T>
  close(): Promise<void>
}

export function createDatabase(url: string, poolSize: number): Database {
  const pool = mysql.createPool({
    uri: url,
    connectionLimit: poolSize,
    maxIdle: poolSize,
    idleTimeout: 60_000,
    queueLimit: 1000,
    enableKeepAlive: true,
    supportBigNumbers: true,
    timezone: 'Z',
  })
  const db = drizzle({ client: pool, schema, mode: 'default' })

  async function transaction<T>(work: (tx: Db) => Promise<T>): Promise<T> {
    const connection = await pool.getConnection()
    let reusable = false
    try {
      await connection.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED')
      await connection.query('START TRANSACTION')
      let result: T
      try {
        result = await work(drizzle({ client: connection, schema, mode: 'default' }))
      } catch (error) {
        // A failed ROLLBACK leaves the connection in an unknown state; it is
        // destroyed below and the original error is the one worth reporting.
        reusable = await connection.query('ROLLBACK').then(() => true, () => false)
        throw error
      }
      await connection.query('COMMIT')
      reusable = true
      return result
    } finally {
      if (reusable) connection.release()
      else connection.destroy()
    }
  }

  return { db, transaction, close: () => pool.end() }
}

/** MySQL ER_DUP_ENTRY, possibly wrapped in drizzle's DrizzleQueryError. */
export function isDuplicateKey(error: unknown): boolean {
  for (let e: unknown = error; e instanceof Error; e = e.cause) {
    if ((e as { code?: unknown }).code === 'ER_DUP_ENTRY') return true
  }
  return false
}
