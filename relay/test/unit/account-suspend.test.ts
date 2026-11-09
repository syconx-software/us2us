import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { databaseUrl, formatUuid, normalizeUsername, parseAccountId, parseCommand, UsageError, usernameHash, CliError } from '../../scripts/account-suspend.mjs'

/** Shared with the Swift and Kotlin implementations (spec/). */
const spec = (name: string) => JSON.parse(readFileSync(new URL(`../../../spec/${name}`, import.meta.url), 'utf8'))

describe('account-suspend usernames', () => {
  it('hashes usernames as the apps do (spec/test-vectors/*/username.json)', () => {
    for (const generator of ['swift', 'kotlin']) {
      for (const vector of spec(`test-vectors/${generator}/username.json`).vectors as Array<{ input: string, normalized: string | null, hash: string | null }>) {
        const normalized = normalizeUsername(vector.input)
        expect(normalized, JSON.stringify(vector.input)).toBe(vector.normalized)
        if (normalized) expect(usernameHash(normalized).toString('hex')).toBe(vector.hash)
      }
    }
  })

  it('accepts names as the apps take them in (spec/username-rules.json)', () => {
    for (const rule of spec('username-rules.json').cases as Array<{ input: string, normalized: string, valid: boolean }>) {
      expect(normalizeUsername(rule.input), JSON.stringify(rule.input)).toBe(rule.valid ? rule.normalized : null)
    }
  })
})

describe('account-suspend account ids', () => {
  const hex = '0123456789abcdef0123456789abcdef'

  it('reads the UUID, hex and base64url forms of one id', () => {
    for (const form of [hex, hex.toUpperCase(), '01234567-89ab-cdef-0123-456789abcdef', 'ASNFZ4mrze8BI0VniavN7w']) {
      expect(parseAccountId(form)?.toString('hex'), form).toBe(hex)
    }
    expect(formatUuid(Buffer.from(hex, 'hex'))).toBe('01234567-89ab-cdef-0123-456789abcdef')
  })

  it('refuses anything else', () => {
    for (const form of ['', 'alice', hex.slice(1), `${hex}00`, '0123456789ab-cdef-0123-4567-89abcdef', 'ASNFZ4mrze8BI0VniavN7x', 'ASNFZ4mrze8BI0VniavN7w==']) {
      expect(parseAccountId(form), form).toBeNull()
    }
  })
})

describe('account-suspend command line', () => {
  it('parses each command', () => {
    expect(parseCommand(['suspend', '--username', ' @Alice_01 ', '--reason', 'ticket-42', '--yes'])).toEqual({
      command: 'suspend', target: { kind: 'username', username: 'alice_01' }, reason: 'ticket-42', yes: true, wait: 30,
    })
    expect(parseCommand(['suspend', '--account', 'ASNFZ4mrze8BI0VniavN7w', '--reason', 'abuse', '--wait', '0']).wait).toBe(0)
    expect(parseCommand(['unsuspend', '--username', 'alice', '--yes'])).toMatchObject({ command: 'unsuspend', yes: true })
    expect(parseCommand(['status', '--username', 'alice'])).toMatchObject({ command: 'status', yes: false })
    expect(parseCommand(['list'])).toEqual({ command: 'list' })
  })

  it('refuses what it cannot carry out exactly', () => {
    for (const args of [
      [],
      ['delete', '--username', 'alice'],
      ['status'],
      ['status', '--username', 'alice', 'extra'],
      ['status', '--username', 'al'],
      ['status', '--username', 'alice', '--account', 'ASNFZ4mrze8BI0VniavN7w'],
      ['suspend', '--username', 'alice'],
      ['suspend', '--username', 'alice', '--reason', ''],
      ['suspend', '--username', 'alice', '--reason', 'Mehmet insulted me'],
      ['suspend', '--username', 'alice', '--reason', 'x'.repeat(33)],
      ['suspend', '--username', 'alice', '--reason', 'abuse', '--wait', '601'],
      ['unsuspend', '--username', 'alice', '--wait', '5'],
      ['list', '--username', 'alice'],
      ['status', '--username'],
    ]) {
      expect(() => parseCommand(args), args.join(' ')).toThrow(UsageError)
    }
  })

  it('takes only a mysql URL from DATABASE_URL', () => {
    expect(databaseUrl({ DATABASE_URL: 'mysql://u:p@127.0.0.1:3306/us2us' })).toBe('mysql://u:p@127.0.0.1:3306/us2us')
    for (const value of [undefined, '', 'postgres://u:p@h/db', 'mysql://u:p@h', 'not a url']) {
      expect(() => databaseUrl({ DATABASE_URL: value }), String(value)).toThrow(CliError)
    }
    // The message never repeats the value.
    expect(() => databaseUrl({ DATABASE_URL: 'postgres://user:secret@h/db' })).toThrow(/^(?!.*secret)/)
  })
})
