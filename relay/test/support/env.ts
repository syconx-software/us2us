/** Test database; override with TEST_DATABASE_URL. Its tables are dropped and re-created by the global setup. */
export const TEST_DATABASE_URL = process.env.TEST_DATABASE_URL ?? 'mysql://us2us:us2us_dev@127.0.0.1:3307/us2us_test'
