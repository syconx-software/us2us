import { defineConfig } from 'drizzle-kit'

// `pnpm db:migrate` reads the same DATABASE_URL as the relay.
export default defineConfig({
  dialect: 'mysql',
  schema: './server/db/schema.ts',
  out: './drizzle',
  dbCredentials: { url: process.env.DATABASE_URL ?? '' },
  strict: true,
  verbose: true,
})
