#!/usr/bin/env node
// Suspends accounts that break the terms of use (relay/README.md, "Acting on
// a report"), lifts suspensions and lists them. Run it on the relay's host
// with the relay's own database settings:
//
//   node --env-file=<relay env file> scripts/account-suspend.mjs <command> ...
//
//   suspend   --username <name> | --account <id>  --reason <code> [--yes] [--wait <seconds>]
//   unsuspend --username <name> | --account <id>  [--yes]
//   status    --username <name> | --account <id>
//   list
//
// A username is resolved the way the apps do it (PROTOCOL.md §3.1, §3.2), so
// the name from a report can be pasted as it is; a previous name that is
// still held for its account (§3.3) finds that account too. An account id
// is the UUID the API shows, 32 hex digits or 22 characters of base64url.
// --reason is a short code (a-z, 0-9, '.', '_', '-'; e.g. "abuse" or a
// ticket number), never text about a person.
//
// Without --yes, suspend and unsuspend only show what they would do. The
// script writes nothing but the suspended_accounts row; the running relay
// reads the list every SUSPENSION_CHECK_SECONDS, then refuses the account,
// closes its WebSocket and removes its media, avatar record and communities,
// and marks the row applied. `suspend` waits for that (--wait, default 30 s;
// 0 does not wait). Only DATABASE_URL is read; nothing secret is printed.
//
// Exit status: 0 done (or nothing to do), 1 not found or failed, 2 bad usage.

import { pbkdf2Sync } from 'node:crypto'
import { realpathSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const USAGE = `usage: account-suspend.mjs suspend (--username <name> | --account <id>) --reason <code> [--yes] [--wait <seconds>]
       account-suspend.mjs unsuspend (--username <name> | --account <id>) [--yes]
       account-suspend.mjs status (--username <name> | --account <id>)
       account-suspend.mjs list`

const COMMANDS = ['suspend', 'unsuspend', 'status', 'list']
const USERNAME = /^[a-z][a-z0-9_]{2,23}$/
const REASON = /^[a-z0-9][a-z0-9._-]{0,31}$/
const DEFAULT_WAIT_SECONDS = 30
const MAX_WAIT_SECONDS = 600
const POLL_MS = 500

/** A mistake in the command line: printed with the usage, exit status 2. */
export class UsageError extends Error {}
/** A refusal with a message fit for the operator: exit status 1. */
export class CliError extends Error {}

/**
 * PROTOCOL.md §3.1 plus the apps' input rule (spec/username-rules.json):
 * trim Unicode White_Space, drop one leading '@', trim again, NFKC,
 * locale-independent lowercase. Null if the result is not a valid username.
 */
export function normalizeUsername(input) {
  const trim = value => value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
  let value = trim(input)
  if (value.startsWith('@')) value = value.slice(1)
  value = trim(value).normalize('NFKC').toLowerCase()
  return USERNAME.test(value) ? value : null
}

/** PROTOCOL.md §3.2. */
export function usernameHash(normalized) {
  return pbkdf2Sync(Buffer.from(normalized, 'utf8'), Buffer.from('BRP/username/v1', 'utf8'), 100_000, 32, 'sha256')
}

/** A 16-byte account id from a UUID, 32 hex digits or unpadded base64url; null otherwise. */
export function parseAccountId(value) {
  const text = value.trim()
  const hex = text.replace(/-/g, '')
  if (/^[0-9a-fA-F]{32}$/.test(hex) && (hex === text || /^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/.test(text))) {
    return Buffer.from(hex, 'hex')
  }
  if (/^[A-Za-z0-9_-]{22}$/.test(text)) {
    const bytes = Buffer.from(text, 'base64url')
    if (bytes.length === 16 && bytes.toString('base64url') === text) return bytes
  }
  return null
}

export function formatUuid(id) {
  const hex = id.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Parses and checks the command line; throws UsageError. */
export function parseCommand(argv) {
  let parsed
  try {
    parsed = parseArgs({
      args: argv,
      allowPositionals: true,
      strict: true,
      options: {
        username: { type: 'string' },
        account: { type: 'string' },
        reason: { type: 'string' },
        yes: { type: 'boolean', default: false },
        wait: { type: 'string' },
      },
    })
  } catch (error) {
    throw new UsageError(error instanceof Error ? error.message : 'invalid arguments')
  }
  const { values, positionals } = parsed
  const [command, ...rest] = positionals
  if (!COMMANDS.includes(command)) throw new UsageError('expected one of: suspend, unsuspend, status, list')
  if (rest.length > 0) throw new UsageError(`unexpected argument: ${rest[0]}`)

  if (command === 'list') {
    if (values.username !== undefined || values.account !== undefined || values.reason !== undefined || values.yes || values.wait !== undefined) {
      throw new UsageError('list takes no options')
    }
    return { command }
  }

  if ((values.username === undefined) === (values.account === undefined)) throw new UsageError('give exactly one of --username and --account')
  let target
  if (values.username !== undefined) {
    const username = normalizeUsername(values.username)
    if (!username) throw new UsageError('not a valid username (3-24 characters: a-z, 0-9, _; starting with a letter)')
    target = { kind: 'username', username }
  } else {
    const accountId = parseAccountId(values.account)
    if (!accountId) throw new UsageError('not an account id (UUID, 32 hex digits or 22 characters of base64url)')
    target = { kind: 'account', accountId }
  }

  if (command === 'suspend') {
    if (values.reason === undefined) throw new UsageError('suspend needs --reason <code>')
    if (!REASON.test(values.reason)) throw new UsageError('--reason must be a short code: up to 32 of a-z, 0-9, ".", "_", "-"')
  } else if (values.reason !== undefined) {
    throw new UsageError(`${command} takes no --reason`)
  }
  if (command === 'status' && values.yes) throw new UsageError('status takes no --yes')
  let wait = DEFAULT_WAIT_SECONDS
  if (values.wait !== undefined) {
    if (command !== 'suspend') throw new UsageError(`${command} takes no --wait`)
    wait = /^\d+$/.test(values.wait) ? Number(values.wait) : Number.NaN
    if (!Number.isSafeInteger(wait) || wait > MAX_WAIT_SECONDS) throw new UsageError(`--wait must be 0..${MAX_WAIT_SECONDS} seconds`)
  }
  return { command, target, reason: values.reason, yes: values.yes, wait }
}

/** The database URL from the environment, checked without ever printing it. */
export function databaseUrl(env = process.env) {
  const value = env.DATABASE_URL?.trim()
  if (!value) throw new CliError('DATABASE_URL is not set (run with node --env-file=<the relay\'s env file>)')
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new CliError('DATABASE_URL is not a valid URL')
  }
  if (parsed.protocol !== 'mysql:' || parsed.pathname.length < 2) throw new CliError('DATABASE_URL must be a mysql:// URL with a database')
  return value
}

/**
 * mysql2 from the repository's dependencies, or else from the traced ones of
 * a built release: the one this script lies in, or the working directory.
 */
async function loadMysql() {
  try {
    return (await import('mysql2/promise')).default
  } catch {
    for (const root of [dirname(dirname(fileURLToPath(import.meta.url))), process.cwd()]) {
      try {
        return createRequire(join(root, '.output/server/package.json'))('mysql2/promise')
      } catch {
        // Not a release; try the next place.
      }
    }
    throw new CliError('mysql2 not found: run this from the relay directory or a built release')
  }
}

const isoTime = ms => (ms === null || ms === undefined ? '-' : new Date(Number(ms)).toISOString())
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/** The account a target names, with what the operator needs to see; null if there is none. */
async function findAccount(connection, target, now) {
  let accountId = target.accountId
  let usernameNote = null
  if (target.kind === 'username') {
    const [rows] = await connection.query('SELECT account_id, held_until FROM usernames WHERE username_hash = ?', [usernameHash(target.username)])
    const row = rows[0]
    if (!row || (row.held_until !== null && Number(row.held_until) <= now)) return null
    accountId = row.account_id
    usernameNote = row.held_until === null ? 'current' : `previous, held until ${isoTime(row.held_until)}`
  }
  const [rows] = await connection.query(
    `SELECT a.id, a.created_on, a.last_seen, s.suspended_at, s.reason, s.applied_at,
       (SELECT COUNT(*) FROM media m WHERE m.uploader = a.id) AS media,
       (SELECT COUNT(*) FROM communities c WHERE c.owner = a.id) AS owned,
       (SELECT COUNT(*) FROM community_members cm WHERE cm.account_id = a.id) AS memberships
     FROM accounts a LEFT JOIN suspended_accounts s ON s.account_id = a.id
     WHERE a.id = ?`,
    [accountId],
  )
  const row = rows[0]
  if (!row) return null
  return {
    id: row.id,
    usernameNote,
    createdOn: String(row.created_on),
    lastSeen: String(row.last_seen),
    suspension: row.suspended_at === null ? null : { at: Number(row.suspended_at), reason: row.reason, appliedAt: row.applied_at === null ? null : Number(row.applied_at) },
    media: Number(row.media),
    owned: Number(row.owned),
    memberships: Number(row.memberships),
  }
}

function describe(account, print) {
  print(`account:     ${formatUuid(account.id)}`)
  if (account.usernameNote) print(`username:    ${account.usernameNote}`)
  print(`registered:  ${account.createdOn}, last seen ${account.lastSeen}`)
  if (!account.suspension) {
    print('suspended:   no')
  } else {
    const { at, reason, appliedAt } = account.suspension
    print(`suspended:   since ${isoTime(at)}, reason ${reason}`)
    print(`applied:     ${appliedAt === null ? 'not yet (the relay applies it on its next check)' : isoTime(appliedAt)}`)
  }
  print(`stored:      ${account.media} media, owns ${account.owned} communities, member of ${account.memberships}`)
}

async function waitForApplied(connection, accountId, seconds) {
  const deadline = Date.now() + seconds * 1000
  for (;;) {
    const [rows] = await connection.query('SELECT applied_at FROM suspended_accounts WHERE account_id = ?', [accountId])
    if (rows[0]?.applied_at !== null && rows[0]?.applied_at !== undefined) return Number(rows[0].applied_at)
    if (rows.length === 0 || Date.now() >= deadline) return null
    await sleep(POLL_MS)
  }
}

/** Runs a parsed command; returns the exit status. */
export async function run(options, { url, print = console.log, now = Date.now() } = {}) {
  const mysql = await loadMysql()
  const connection = await mysql.createConnection({ uri: url, supportBigNumbers: true, timezone: 'Z', dateStrings: true })
  try {
    if (options.command === 'list') {
      const [rows] = await connection.query('SELECT account_id, suspended_at, reason, applied_at FROM suspended_accounts ORDER BY suspended_at')
      for (const row of rows) {
        print(`${formatUuid(row.account_id)}  since ${isoTime(row.suspended_at)}  reason ${row.reason}  ${row.applied_at === null ? 'not applied yet' : 'applied'}`)
      }
      print(`${rows.length} suspended account${rows.length === 1 ? '' : 's'}`)
      return 0
    }

    const account = await findAccount(connection, options.target, now)
    if (!account) {
      print(options.target.kind === 'username' ? 'No account has this username.' : 'No account has this id.')
      return 1
    }
    describe(account, print)
    if (options.command === 'status') return 0

    if (options.command === 'suspend') {
      if (account.suspension) {
        print('Already suspended; nothing changed.')
        return 0
      }
      print(`Will suspend: every request of the account is refused (403 account_suspended), its WebSocket closed,
nobody can find it, fetch its keys or send to it, and its username stays taken.
The relay then deletes its ${account.media} media, its avatar record, the ${account.owned} communities it owns
(for all their members) and its ${account.memberships} memberships with their deliveries.
Sealed messages it already sent cannot be told apart and expire on their own.`)
      if (!options.yes) {
        print('Nothing changed; run again with --yes to suspend.')
        return 0
      }
      let inserted
      try {
        const [result] = await connection.query(
          'INSERT INTO suspended_accounts (account_id, suspended_at, reason, applied_at) SELECT id, ?, ?, NULL FROM accounts WHERE id = ?',
          [Date.now(), options.reason, account.id],
        )
        inserted = result.affectedRows === 1
      } catch (error) {
        if (error?.code !== 'ER_DUP_ENTRY') throw error
        print('Already suspended; nothing changed.')
        return 0
      }
      if (!inserted) {
        print('The account was deleted meanwhile; nothing changed.')
        return 1
      }
      print(`Suspended ${formatUuid(account.id)}.`)
      if (options.wait === 0) return 0
      const appliedAt = await waitForApplied(connection, account.id, options.wait)
      print(appliedAt === null
        ? `The relay has not applied it within ${options.wait} s; it does on its next check (is it running?). Check with: status --account ${formatUuid(account.id)}`
        : `Applied by the relay at ${isoTime(appliedAt)}.`)
      return 0
    }

    // unsuspend
    if (!account.suspension) {
      print('Not suspended; nothing changed.')
      return 0
    }
    print(`Will lift the suspension: the account can sign in again and be found, within SUSPENSION_CHECK_SECONDS.
What was deleted when it was applied (media, avatar record, communities) does not come back.`)
    if (!options.yes) {
      print('Nothing changed; run again with --yes to lift the suspension.')
      return 0
    }
    const [result] = await connection.query('DELETE FROM suspended_accounts WHERE account_id = ?', [account.id])
    print(result.affectedRows === 1 ? `Lifted the suspension of ${formatUuid(account.id)}.` : 'Not suspended any more; nothing changed.')
    return 0
  } finally {
    await connection.end()
  }
}

async function main() {
  let options
  try {
    options = parseCommand(process.argv.slice(2))
  } catch (error) {
    if (!(error instanceof UsageError)) throw error
    console.error(`account-suspend: ${error.message}\n${USAGE}`)
    return 2
  }
  try {
    return await run(options, { url: databaseUrl() })
  } catch (error) {
    // Driver errors can carry the database user, host and query; only their code is shown.
    const reason = error instanceof CliError ? error.message : (error?.code ?? 'unexpected error')
    console.error(`account-suspend: ${reason}`)
    return 1
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  process.exitCode = await main()
}
