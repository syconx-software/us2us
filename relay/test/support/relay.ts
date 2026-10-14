import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { TEST_DATABASE_URL } from './env'

const entry = fileURLToPath(new URL('../../.output/server/index.mjs', import.meta.url))

export const TEST_POW_SECRET = 'test-pow-secret-0123456789abcdefghijklmnop'

/**
 * Settings for test relays: easy proof-of-work and no rate limits, so that
 * tests can register many accounts from 127.0.0.1. Tests that exercise a
 * limit switch it back on explicitly.
 */
const TEST_ENV: Record<string, string> = {
  NODE_ENV: 'production',
  HOST: '127.0.0.1',
  DATABASE_URL: TEST_DATABASE_URL,
  POW_SECRET: TEST_POW_SECRET,
  POW_DIFFICULTY_REGISTER: '4',
  POW_DIFFICULTY_MESSAGE: '4',
  LOG_LEVEL: 'warn',
  RATE_LIMIT_POW: 'off',
  RATE_LIMIT_REGISTER: 'off',
  RATE_LIMIT_SEND: 'off',
  RATE_LIMIT_EPHEMERAL: 'off',
  RATE_LIMIT_MEDIA_FETCH: 'off',
  RATE_LIMIT_WS_CONNECT: 'off',
  RATE_LIMIT_DIRECTORY: 'off',
  RATE_LIMIT_AVATAR_FETCH: 'off',
  RATE_LIMIT_AVATAR_WRITE: 'off',
  RATE_LIMIT_AVATAR_DELETE: 'off',
  RATE_LIMIT_KEY_FETCH: 'off',
  RATE_LIMIT_MEDIA_UPLOADS: 'off',
  RATE_LIMIT_MEDIA_BYTES: 'off',
  RATE_LIMIT_USERNAME_CHANGES: 'off',
  RATE_LIMIT_WS_FRAMES: 'off',
  RATE_LIMIT_ACCOUNT: 'off',
  RATE_LIMIT_AUTH_FAIL: 'off',
  RATE_LIMIT_COMMUNITY_IP: 'off',
  RATE_LIMIT_COMMUNITY_CREATE: 'off',
  RATE_LIMIT_COMMUNITY_JOIN: 'off',
  RATE_LIMIT_COMMUNITY_SEND: 'off',
  RATE_LIMIT_COMMUNITY_API: 'off',
}

export interface RelayProcess {
  url: string
  wsUrl: string
  mediaDir: string
  /** Error-level log lines; a relay that worked as intended has none. */
  errors(): string[]
  /** Sends a signal (SIGTERM by default) and resolves with the exit code. */
  stop(signal?: NodeJS.Signals): Promise<number | null>
}

async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => (typeof address === 'object' && address ? resolve(address.port) : reject(new Error('no port'))))
    })
  })
}

/** Spawns the built relay with the test settings plus `env`, and waits until it listens. */
export async function startRelay(env: Record<string, string> = {}): Promise<RelayProcess> {
  const port = await freePort()
  const mediaDir = await mkdtemp(join(tmpdir(), 'us2us-media-'))
  // Vitest marks its own process as a test run; the relay must not inherit that.
  const { TEST: _test, VITEST: _vitest, ...inherited } = process.env
  const child: ChildProcess = spawn(process.execPath, [entry], {
    env: { ...inherited, ...TEST_ENV, PORT: String(port), MEDIA_DIR: mediaDir, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let output = ''
  child.stdout!.on('data', (chunk: Buffer) => {
    output += chunk.toString()
  })
  child.stderr!.on('data', (chunk: Buffer) => {
    output += chunk.toString()
  })
  const exited = new Promise<number | null>(resolve => child.once('exit', code => resolve(code)))

  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`relay did not start:\n${output}`)), 20_000)
    const check = () => {
      if (output.includes('Listening on')) {
        clearTimeout(timer)
        resolve()
      }
    }
    child.stdout!.on('data', check)
    void exited.then((code) => {
      clearTimeout(timer)
      reject(new Error(`relay exited with ${code}:\n${output}`))
    })
  })

  return {
    url: `http://127.0.0.1:${port}`,
    wsUrl: `ws://127.0.0.1:${port}/v1/ws`,
    mediaDir,
    errors: () => output.split('\n').filter(line => line.startsWith('[relay] error') || line.includes('[uncaught') || line.includes('[unhandled')),
    async stop(signal = 'SIGTERM') {
      if (child.exitCode === null && child.signalCode === null) child.kill(signal)
      const code = await exited
      await rm(mediaDir, { recursive: true, force: true })
      return code
    },
  }
}
