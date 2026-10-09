import type { LogLevel } from './config'

const ORDER: Record<LogLevel, number> = { error: 0, warn: 1, info: 2, debug: 3 }

export type LogFields = Record<string, string | number | boolean | undefined>

/**
 * Minimal line logger. Call sites pass only operational facts (counts,
 * durations, error codes). Request bodies, tokens, keys, account ids and
 * envelope ids are never logged; client IPs only when LOG_IPS is enabled,
 * which callers check themselves.
 */
export class Logger {
  constructor(private readonly level: LogLevel) {}

  error(message: string, fields?: LogFields): void {
    this.write('error', message, fields)
  }

  warn(message: string, fields?: LogFields): void {
    this.write('warn', message, fields)
  }

  info(message: string, fields?: LogFields): void {
    this.write('info', message, fields)
  }

  debug(message: string, fields?: LogFields): void {
    this.write('debug', message, fields)
  }

  private write(level: LogLevel, message: string, fields?: LogFields): void {
    if (ORDER[level] > ORDER[this.level]) return
    let line = `[relay] ${level} ${message}`
    for (const [key, value] of Object.entries(fields ?? {})) {
      if (value !== undefined) line += ` ${key}=${typeof value === 'string' ? JSON.stringify(value) : value}`
    }
    if (level === 'error' || level === 'warn') console.error(line)
    else console.log(line)
  }
}
