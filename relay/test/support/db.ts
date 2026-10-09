import mysql from 'mysql2/promise'
import { TEST_DATABASE_URL } from './env'

/** Direct access to the test database, to check what the relay did (or did not) write. */
export class TestDb {
  private constructor(private readonly connection: mysql.Connection) {}

  static async connect(): Promise<TestDb> {
    return new TestDb(await mysql.createConnection({ uri: TEST_DATABASE_URL }))
  }

  async query<T = mysql.RowDataPacket>(sql: string, params: unknown[] = []): Promise<T[]> {
    const [rows] = await this.connection.query(sql, params)
    return rows as T[]
  }

  async count(sql: string, params: unknown[] = []): Promise<number> {
    const [row] = await this.query<{ n: number }>(`SELECT COUNT(*) AS n FROM ${sql}`, params)
    return Number(row!.n)
  }

  close(): Promise<void> {
    return this.connection.end()
  }
}

export const uuidBytes = (uuid: string) => Buffer.from(uuid.replace(/-/g, ''), 'hex')
