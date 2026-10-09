#!/usr/bin/env node
// Load sanity check for the online fast path (PROTOCOL.md §1.1).
//
// Starts the built relay (`pnpm build` first) against DATABASE_URL, connects
// one recipient over WebSocket that acknowledges every envelope as it
// arrives, and sends N sealed envelopes one after another over HTTP. Then
// sends N ephemeral envelopes in presence-style batches (one envelope for
// each of 200 online contacts), and finally repeats the sealed sends with
// the recipient offline (and waits until the persister has stored them).
// Reports throughput, latency and how many rows InnoDB inserted in each
// phase.
//
//   DATABASE_URL=mysql://us2us:us2us_dev@127.0.0.1:3307/us2us_test node scripts/load-check.mjs [count]
//
// Use a test database: the script creates 201 accounts and leaves the
// offline phase's envelopes for the retention sweep.

import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import mysql from 'mysql2/promise'

const COUNT = Number(process.argv[2] ?? 2000)
const DATABASE_URL = process.env.DATABASE_URL
if (!DATABASE_URL) {
  console.error('DATABASE_URL is required (use a test database)')
  process.exit(1)
}

const b64u = bytes => Buffer.from(bytes).toString('base64url')
const sha256 = bytes => createHash('sha256').update(bytes).digest()

async function freePort() {
  return new Promise((resolve) => {
    const server = createServer().listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close(() => resolve(port))
    })
  })
}

async function startRelay() {
  const port = await freePort()
  const { TEST: _t, VITEST: _v, ...env } = process.env
  const child = spawn(process.execPath, [fileURLToPath(new URL('../.output/server/index.mjs', import.meta.url))], {
    env: {
      ...env,
      NODE_ENV: 'production',
      HOST: '127.0.0.1',
      PORT: String(port),
      POW_SECRET: randomBytes(32).toString('base64'),
      POW_DIFFICULTY_REGISTER: '4',
      MEDIA_DIR: fileURLToPath(new URL('../.data/load-check-media', import.meta.url)),
      LOG_LEVEL: 'warn',
      RATE_LIMIT_SEND: 'off',
      RATE_LIMIT_EPHEMERAL: 'off',
      RATE_LIMIT_POW: 'off',
      RATE_LIMIT_REGISTER: 'off',
      RATE_LIMIT_ACCOUNT: 'off',
      RATE_LIMIT_WS_FRAMES: 'off',
      RATE_LIMIT_WS_CONNECT: 'off',
      // The offline phase stores `count` envelopes for one recipient.
      MAILBOX_MAX_ENVELOPES: '10000000',
    },
    stdio: ['ignore', 'pipe', 'inherit'],
  })
  await new Promise((resolve, reject) => {
    child.stdout.on('data', chunk => String(chunk).includes('Listening on') && resolve())
    child.once('exit', code => reject(new Error(`relay exited with ${code}`)))
  })
  return { url: `http://127.0.0.1:${port}`, wsUrl: `ws://127.0.0.1:${port}/v1/ws`, stop: () => child.kill('SIGTERM') }
}

function solve(token, difficulty) {
  const nonce = Buffer.alloc(8)
  for (let n = 0n; ; n++) {
    nonce.writeBigUInt64BE(n)
    const digest = createHash('sha256').update(token).update(nonce).digest()
    let bits = 0
    for (const byte of digest) {
      if (byte === 0) { bits += 8; continue }
      bits += Math.clz32(byte) - 24
      break
    }
    if (bits >= difficulty) return n.toString()
  }
}

async function register(url) {
  const challenge = await (await fetch(`${url}/v1/pow?purpose=register`)).json()
  const ec = () => b64u(Buffer.concat([Buffer.from([5]), randomBytes(32)]))
  const response = await fetch(`${url}/v1/accounts`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      usernameHash: b64u(randomBytes(32)),
      identityKey: ec(),
      registrationId: 1,
      signedPreKey: { keyId: 1, publicKey: ec(), signature: b64u(randomBytes(64)) },
      kyberLastResortKey: { keyId: 1, publicKey: b64u(randomBytes(1569)), signature: b64u(randomBytes(64)) },
      pow: { token: challenge.token, nonce: solve(challenge.token, challenge.difficulty) },
    }),
  })
  const account = await response.json()
  const accessKey = randomBytes(16)
  await fetch(`${url}/v1/accounts/me/access-key`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', 'x-auth-token': account.authToken },
    body: JSON.stringify({ accessKeyHash: b64u(sha256(accessKey)) }),
  })
  return { ...account, accessKey: b64u(accessKey) }
}

/** Envelopes for offline recipients are written by the persister just after the send was answered. */
async function waitUntilStored(db, recipient, count) {
  const started = performance.now()
  for (;;) {
    const [[{ n: stored }]] = await db.query('SELECT COUNT(*) AS n FROM envelopes WHERE recipient = UNHEX(?)', [recipient.accountId.replace(/-/g, '')])
    if (Number(stored) >= count || performance.now() - started > 30_000) return { stored: Number(stored), ms: performance.now() - started }
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}

async function rowsInserted(db) {
  const [[row]] = await db.query('SHOW GLOBAL STATUS LIKE \'Innodb_rows_inserted\'')
  return Number(row.Value)
}

/** Fails loudly instead of hanging when the relay does not deliver what a phase expects. */
function within(promise, what, ms = 60_000) {
  let timer
  const timeout = new Promise((_, reject) => (timer = setTimeout(() => reject(new Error(`${what} did not finish within ${ms} ms`)), ms)))
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

function percentile(sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]
}

async function sendAll(relay, recipient, onSent) {
  const envelope = randomBytes(600) // a typical padded text message after sealing
  const started = performance.now()
  for (let i = 0; i < COUNT; i++) {
    const sentAt = performance.now()
    const response = await fetch(`${relay.url}/v1/messages/${recipient.accountId}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/octet-stream', 'x-access-key': recipient.accessKey },
      body: envelope,
    })
    const { id } = await response.json()
    if (response.status !== 202) throw new Error(`send failed with ${response.status}`)
    onSent?.(id, sentAt)
  }
  return performance.now() - started
}

async function main() {
  const relay = await startRelay()
  const db = await mysql.createConnection({ uri: DATABASE_URL })
  try {
    const recipient = await register(relay.url)

    // Phase 1: recipient online, acknowledging every envelope immediately.
    // A frame often arrives before the HTTP response that carries its id, so
    // both sides are timestamped and matched up at the end.
    const sentAt = new Map()
    const receivedAt = new Map()
    let received = 0
    let resolvePhase
    const phaseDone = new Promise(resolve => (resolvePhase = resolve))
    const socket = new WebSocket(relay.wsUrl, { headers: { 'x-auth-token': recipient.authToken } })
    socket.addEventListener('message', (event) => {
      const frame = JSON.parse(event.data)
      if (frame.type !== 'message') return
      receivedAt.set(frame.id, performance.now())
      socket.send(JSON.stringify({ type: 'ack', ids: [frame.id] }))
      if (++received === COUNT) resolvePhase()
    })
    await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))

    const before = await rowsInserted(db)
    const onlineMs = await sendAll(relay, recipient, (id, start) => sentAt.set(id, start))
    await within(phaseDone, 'the online phase')
    // Give any (unexpected) ack-timeout write a chance to show up.
    await new Promise(resolve => setTimeout(resolve, 500))
    const onlineInserted = (await rowsInserted(db)) - before
    const [[{ remaining }]] = await db.query('SELECT COUNT(*) AS remaining FROM envelopes WHERE recipient = UNHEX(?)', [recipient.accountId.replace(/-/g, '')])

    // Phase 2: presence-style ephemeral batches: one envelope per contact
    // (the relay forwards at most one per recipient and batch, §4.6) to 200
    // online contacts.
    const batchSize = 200
    const batches = Math.ceil(COUNT / batchSize)
    let ephemeralReceived = 0
    let resolveEphemeral
    const ephemeralDone = new Promise(resolve => (resolveEphemeral = resolve))
    const contacts = []
    const contactSockets = []
    // A few at a time, so that the connects do not overflow the listen backlog.
    for (let i = 0; i < batchSize; i += 20) {
      const group = await Promise.all(Array.from({ length: 20 }, () => register(relay.url)))
      contacts.push(...group)
      contactSockets.push(...await Promise.all(group.map(async (contact) => {
        const contactSocket = new WebSocket(relay.wsUrl, { headers: { 'x-auth-token': contact.authToken } })
        contactSocket.addEventListener('message', (event) => {
          if (JSON.parse(event.data).type === 'message' && ++ephemeralReceived === batches * batchSize) resolveEphemeral()
        })
        await within(new Promise((resolve, reject) => {
          contactSocket.addEventListener('open', resolve, { once: true })
          contactSocket.addEventListener('error', () => reject(new Error('a contact socket failed to open')), { once: true })
        }), 'opening a contact socket', 10_000)
        return contactSocket
      })))
    }
    const presence = b64u(randomBytes(200))
    const batch = JSON.stringify({ messages: contacts.map(contact => ({ to: contact.accountId, accessKey: contact.accessKey, envelope: presence })) })
    const ephemeralBefore = await rowsInserted(db)
    const ephemeralStarted = performance.now()
    for (let i = 0; i < batches; i++) {
      const response = await fetch(`${relay.url}/v1/messages/ephemeral`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: batch,
      })
      if (response.status !== 202) throw new Error(`ephemeral batch failed with ${response.status}`)
    }
    await within(ephemeralDone, 'the ephemeral phase')
    const ephemeralMs = performance.now() - ephemeralStarted
    await new Promise(resolve => setTimeout(resolve, 500))
    const ephemeralInserted = (await rowsInserted(db)) - ephemeralBefore
    const expected = batches * batchSize
    for (const contactSocket of contactSockets) contactSocket.close()
    socket.close()

    // Phase 3: the same recipient offline, so every envelope is stored.
    await new Promise(resolve => setTimeout(resolve, 200))
    const offlineBefore = await rowsInserted(db)
    const offlineMs = await sendAll(relay, recipient)
    const written = await waitUntilStored(db, recipient, Number(remaining) + COUNT)
    const offlineInserted = (await rowsInserted(db)) - offlineBefore

    const latencies = [...sentAt].map(([id, start]) => receivedAt.get(id) - start).sort((a, b) => a - b)
    const report = {
      envelopes: COUNT,
      online: {
        seconds: +(onlineMs / 1000).toFixed(2),
        sendsPerSecond: Math.round(COUNT / (onlineMs / 1000)),
        sendToWsLatencyMs: { p50: +percentile(latencies, 0.5).toFixed(2), p99: +percentile(latencies, 0.99).toFixed(2) },
        innodbRowsInserted: onlineInserted,
        envelopesLeftInMailbox: Number(remaining),
      },
      ephemeral: {
        batches,
        envelopes: expected,
        secondsUntilAllReceived: +(ephemeralMs / 1000).toFixed(2),
        envelopesPerSecond: Math.round(expected / (ephemeralMs / 1000)),
        innodbRowsInserted: ephemeralInserted,
      },
      offline: {
        seconds: +(offlineMs / 1000).toFixed(2),
        sendsPerSecond: Math.round(COUNT / (offlineMs / 1000)),
        envelopesInMailbox: written.stored,
        msFromLastAnswerUntilStored: Math.round(written.ms),
        innodbRowsInserted: offlineInserted,
      },
    }
    console.log(JSON.stringify(report, null, 2))
  } finally {
    await db.end()
    relay.stop()
  }
}

await main()
