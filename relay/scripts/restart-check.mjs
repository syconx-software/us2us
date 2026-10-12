#!/usr/bin/env node
// Restart check: a relay stopped with SIGTERM in the middle of traffic loses
// no envelope it accepted (PROTOCOL.md §1.1, §9; README "Graceful shutdown").
//
//   node scripts/restart-check.mjs --relay http://127.0.0.1:3000 --database mysql://…/db
//
// Whoever runs the relay drives the restart; scripts/interop.sh at the
// repository root does all of it. The relay must run with rate limits off
// and a WS_ACK_TIMEOUT_MS far above the check's duration (e.g. 120000), so
// that unacknowledged live envelopes exist only in memory until shutdown.
//
// 1. Registers R1, online over a WebSocket that never acknowledges (the
//    online fast path), and R2, offline (the persister path), each with an
//    access key, and sends random envelopes to both from concurrent senders.
// 2. Once R1 holds --min-live unacknowledged envelopes, none of them in MySQL,
//    prints the line READY and keeps sending. Now send the relay SIGTERM,
//    then start it again on the same address and database.
// 3. Stops sending when R1's socket closes, waits for the relay to come back,
//    and checks that every envelope answered 202 (and every frame R1 saw) is
//    delivered again: R1's backlog over a new WebSocket, R2's mailbox over
//    HTTP. Acknowledges everything and deletes both accounts.
//
// Prints READY, then a JSON report, to stdout; progress goes to stderr.
// Exit status 0 if nothing was lost, 1 otherwise.

import { createHash, randomBytes, randomInt } from 'node:crypto'
import { parseArgs } from 'node:util'
import mysql from 'mysql2/promise'

const { values: options } = parseArgs({
  options: {
    'relay': { type: 'string' },
    'database': { type: 'string' },
    'min-live': { type: 'string', default: '200' },
    'senders': { type: 'string', default: '8' },
    'max-per-recipient': { type: 'string', default: '5000' },
    'timeout': { type: 'string', default: '120' },
  },
})
if (!options.relay || !options.database) {
  console.error('usage: restart-check.mjs --relay <url> --database <mysql url> [--min-live 200] [--senders 8] [--max-per-recipient 5000] [--timeout 120]')
  process.exit(2)
}
const RELAY = options.relay.replace(/\/$/, '')
const WS_URL = `${RELAY.replace(/^http/, 'ws')}/v1/ws`
const MIN_LIVE = Number(options['min-live'])
const SENDERS = Number(options.senders)
const MAX_PER_RECIPIENT = Number(options['max-per-recipient'])
const TIMEOUT_MS = Number(options.timeout) * 1000

const b64u = bytes => Buffer.from(bytes).toString('base64url')
const sha256 = bytes => createHash('sha256').update(bytes).digest()
const hex = uuid => uuid.replace(/-/g, '')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = line => console.error(`[restart-check] ${line}`)

async function until(what, condition, ms = TIMEOUT_MS) {
  const deadline = Date.now() + ms
  while (!await condition()) {
    if (Date.now() > deadline) throw new Error(`${what}: not within ${ms} ms`)
    await sleep(50)
  }
}

function solve(token, difficulty) {
  const nonce = Buffer.alloc(8)
  for (let n = 0n; ; n++) {
    nonce.writeBigUInt64BE(n)
    const digest = createHash('sha256').update(token).update(nonce).digest()
    let bits = 0
    for (const byte of digest) {
      if (byte === 0) {
        bits += 8
        continue
      }
      bits += Math.clz32(byte) - 24
      break
    }
    if (bits >= difficulty) return n.toString()
  }
}

async function json(response, expected) {
  if (response.status !== expected) throw new Error(`${response.url}: HTTP ${response.status} ${await response.text()}`)
  return response.status === 204 ? null : response.json()
}

/** An account with random keys: the relay cannot tell them from real ones (it checks shapes only). */
async function register() {
  const challenge = await json(await fetch(`${RELAY}/v1/pow?purpose=register`), 200)
  const ec = () => b64u(Buffer.concat([Buffer.from([5]), randomBytes(32)]))
  const account = await json(await fetch(`${RELAY}/v1/accounts`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      usernameHash: b64u(randomBytes(32)),
      identityKey: ec(),
      registrationId: randomInt(1, 16381),
      signedPreKey: { keyId: 1, publicKey: ec(), signature: b64u(randomBytes(64)) },
      kyberLastResortKey: { keyId: 1, publicKey: b64u(randomBytes(1569)), signature: b64u(randomBytes(64)) },
      pow: { token: challenge.token, nonce: solve(challenge.token, challenge.difficulty) },
    }),
  }), 201)
  const accessKey = randomBytes(16)
  await json(await fetch(`${RELAY}/v1/accounts/me/access-key`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', 'x-auth-token': account.authToken },
    body: JSON.stringify({ accessKeyHash: b64u(sha256(accessKey)) }),
  }), 204)
  return { ...account, accessKey: b64u(accessKey) }
}

/** A WebSocket that records message frames and never acknowledges them. */
function connect(account) {
  const socket = new WebSocket(WS_URL, { headers: { 'x-auth-token': account.authToken } })
  const client = { messages: [], queueEmpty: false, closed: null, socket }
  client.opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', () => reject(new Error('WebSocket failed to open')), { once: true })
  })
  client.closedPromise = new Promise((resolve) => {
    socket.addEventListener('close', (event) => {
      client.closed = { code: event.code, reason: event.reason }
      resolve(client.closed)
    })
  })
  socket.addEventListener('message', (event) => {
    const frame = JSON.parse(String(event.data))
    if (frame.type === 'message') client.messages.push(frame.id)
    else if (frame.type === 'queue_empty') client.queueEmpty = true
  })
  // Keep-alive (§4.7): the relay drops sockets silent for WS_IDLE_TIMEOUT_MS.
  const ping = setInterval(() => socket.readyState === WebSocket.OPEN && socket.send('{"type":"ping"}'), 20_000)
  socket.addEventListener('close', () => clearInterval(ping))
  // A socket whose handshake failed may never report 'close' (undici); its timer must not keep the check running.
  ping.unref()
  return client
}

/**
 * A socket that has received the whole backlog. Retried until then: while the
 * relay stops, upgrades fail or are closed with 1001 before any backlog, so
 * only the restarted relay can satisfy this.
 */
async function synced(account, ms = TIMEOUT_MS) {
  const deadline = Date.now() + ms
  for (;;) {
    const client = connect(account)
    try {
      await client.opened
      await until('queue_empty', () => client.queueEmpty || client.closed !== null, Math.max(0, deadline - Date.now()))
      if (client.queueEmpty) return client
    } catch (error) {
      if (Date.now() > deadline) throw error
    }
    client.socket.close()
    if (Date.now() > deadline) throw new Error(`no synced WebSocket within ${ms} ms`)
    await sleep(200)
  }
}

async function storedRows(db, account) {
  const [[row]] = await db.query('SELECT COUNT(*) AS n FROM envelopes WHERE recipient = UNHEX(?)', [hex(account.accountId)])
  return Number(row.n)
}

async function healthy() {
  try {
    return (await fetch(`${RELAY}/v1/health`)).status === 200
  } catch {
    return false
  }
}

async function main() {
  const db = await mysql.createConnection({ uri: options.database })
  try {
    if (!await healthy()) throw new Error(`no relay at ${RELAY}`)
    const online = await register()
    const offline = await register()
    const live = await synced(online, 10_000)

    // Senders: alternate between both recipients until R1's socket closes.
    const accepted = new Map([[online.accountId, []], [offline.accountId, []]])
    const refused = {}
    const failures = []
    let stop = false
    let ready = false
    let next = 0
    let firstSendAt = 0
    const sender = async () => {
      while (!stop) {
        const recipient = next++ % 2 === 0 ? online : offline
        if (accepted.get(recipient.accountId).length >= MAX_PER_RECIPIENT) {
          await sleep(10)
          continue
        }
        firstSendAt ||= Date.now()
        try {
          const response = await fetch(`${RELAY}/v1/messages/${recipient.accountId}`, {
            method: 'PUT',
            headers: { 'content-type': 'application/octet-stream', 'x-access-key': recipient.accessKey },
            body: randomBytes(randomInt(200, 1500)),
          })
          if (response.status === 202) accepted.get(recipient.accountId).push((await response.json()).id)
          else {
            refused[response.status] = (refused[response.status] ?? 0) + 1
            await response.body?.cancel()
          }
        } catch (error) {
          // Expected only once the relay may be going away (after READY); such a send was not accepted.
          failures.push({ afterReady: ready, error: String(error.cause?.code ?? error.message) })
          if (live.closed === null) await sleep(50)
          else return
        }
      }
    }
    const senders = Array.from({ length: SENDERS }, sender)

    await until(`${MIN_LIVE} live envelopes`, () => live.messages.length >= MIN_LIVE && accepted.get(offline.accountId).length >= MIN_LIVE)
    const before = { liveFrames: live.messages.length, storedForOnline: await storedRows(db, online), storedForOffline: await storedRows(db, offline) }
    log(`ready: ${before.liveFrames} unacknowledged live envelopes, ${before.storedForOnline} of them in MySQL`)
    ready = true
    console.log('READY')

    await until('the relay closing the WebSocket', () => live.closed !== null)
    const closedAt = Date.now()
    stop = true
    await Promise.all(senders)
    const liveBeforeClose = [...live.messages]
    log(`socket closed with ${live.closed.code}; waiting for the relay to come back`)
    // R1: everything comes back as backlog, oldest first, then queue_empty.
    const again = await synced(online)
    const backlog = again.messages
    again.socket.close()
    // R2: the mailbox over HTTP.
    const mailbox = []
    for (;;) {
      const page = await json(await fetch(`${RELAY}/v1/messages?limit=500`, { headers: { 'x-auth-token': offline.authToken } }), 200)
      const ids = page.messages.map(message => message.id)
      mailbox.push(...ids)
      if (ids.length > 0) {
        await json(await fetch(`${RELAY}/v1/messages/ack`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-auth-token': offline.authToken },
          body: JSON.stringify({ ids }),
        }), 204)
      }
      if (!page.more) break
    }
    for (let i = 0; i < backlog.length; i += 500) {
      await json(await fetch(`${RELAY}/v1/messages/ack`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-auth-token': online.authToken },
        body: JSON.stringify({ ids: backlog.slice(i, i + 500) }),
      }), 204)
    }
    const leftOver = (await storedRows(db, online)) + (await storedRows(db, offline))
    for (const account of [online, offline]) {
      await json(await fetch(`${RELAY}/v1/accounts/me`, { method: 'DELETE', headers: { 'x-auth-token': account.authToken } }), 204)
    }

    const missing = (expected, actual) => {
      const present = new Set(actual)
      return expected.filter(id => !present.has(id))
    }
    const sortedIds = ids => [...ids].sort((a, b) => Buffer.compare(Buffer.from(a, 'base64url'), Buffer.from(b, 'base64url')))
    const report = {
      sends: {
        acceptedForOnline: accepted.get(online.accountId).length,
        acceptedForOffline: accepted.get(offline.accountId).length,
        refused,
        failedBeforeReady: failures.filter(failure => !failure.afterReady).length,
        // Refused connections and the like while the relay stops: not accepted, so nothing to lose.
        failedWhileStopping: Object.groupBy(failures.filter(failure => failure.afterReady), failure => failure.error),
      },
      atReady: before,
      shutdown: { closeCode: live.closed.code, liveFramesBeforeClose: liveBeforeClose.length, msFromFirstSendToClose: closedAt - firstSendAt },
      afterRestart: { backlogForOnline: backlog.length, mailboxOfOffline: mailbox.length, rowsLeftAfterAck: leftOver },
      lost: {
        acceptedForOnline: missing(accepted.get(online.accountId), backlog),
        liveFramesForOnline: missing(liveBeforeClose, backlog),
        acceptedForOffline: missing(accepted.get(offline.accountId), mailbox),
      },
      // Stored although never answered 202 (e.g. the answer was cut off): a harmless duplicate for a retrying client.
      unconfirmedButStored: {
        online: missing(backlog, [...accepted.get(online.accountId), ...liveBeforeClose]).length,
        offline: missing(mailbox, accepted.get(offline.accountId)).length,
      },
      backlogInIdOrder: backlog.join() === sortedIds(backlog).join(),
      duplicatesInBacklog: backlog.length - new Set(backlog).size,
    }
    const problems = [
      ...Object.entries(report.lost).filter(([, ids]) => ids.length > 0).map(([what, ids]) => `${ids.length} lost: ${what}`),
      ...before.storedForOnline > 0 ? ['live envelopes were in MySQL before the shutdown: is WS_ACK_TIMEOUT_MS long enough?'] : [],
      ...report.sends.failedBeforeReady > 0 ? [`${report.sends.failedBeforeReady} sends failed before the shutdown`] : [],
      ...report.backlogInIdOrder ? [] : ['backlog not in arrival order'],
      ...report.duplicatesInBacklog > 0 ? ['duplicates in the backlog'] : [],
      ...leftOver > 0 ? [`${leftOver} envelopes left after acknowledging everything`] : [],
    ]
    report.ok = problems.length === 0
    report.problems = problems
    console.log(JSON.stringify(report, (key, value) => (Array.isArray(value) && key !== 'problems' ? value.length : value), 2))
    process.exitCode = report.ok ? 0 : 1
  } finally {
    await db.end()
  }
}

main().catch((error) => {
  log(`failed: ${error.message}`)
  process.exit(1)
})
