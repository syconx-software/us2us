import { afterAll, describe, expect, test } from 'vitest'
import { sql } from 'drizzle-orm'
import { createDatabase } from '../../server/db/client'
import { TEST_DATABASE_URL } from '../support/env'

const flag = async (database: ReturnType<typeof createDatabase>) => {
  const result = await database.db.execute(sql`SELECT @@session.sql_log_bin AS logged`)
  return (result[0] as unknown as Array<{ logged: number }>)[0]!.logged
}

describe('the relay and the binary log', () => {
  const on = createDatabase(TEST_DATABASE_URL, 1, { outOfBinaryLog: true })
  const off = createDatabase(TEST_DATABASE_URL, 1)
  afterAll(async () => { await on.close(); await off.close() })

  test('the option keeps every connection out of it; the default changes nothing', async () => {
    expect(await flag(off)).toBe(1)
    const result = await on.db.execute(sql`SHOW GRANTS FOR CURRENT_USER`)
    const grants = JSON.stringify(result[0])
    // Without the privilege the relay keeps working and says so; the setting needs it on the server.
    expect(await flag(on)).toBe(/SESSION_VARIABLES_ADMIN|ALL PRIVILEGES ON \*\.\*/.test(grants) ? 0 : 1)
  })

  test('it holds inside a transaction on the same connection', async () => {
    const before = await flag(on)
    await on.transaction(async tx => {
      const result = await tx.execute(sql`SELECT @@session.sql_log_bin AS logged`)
      expect((result[0] as unknown as Array<{ logged: number }>)[0]!.logged).toBe(before)
    })
  })
})
