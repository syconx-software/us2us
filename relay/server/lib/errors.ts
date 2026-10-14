/** Error codes from PROTOCOL.md §2.1. Clients localize by `code`. */
export type ErrorCode =
  | 'bad_request'
  | 'unauthorized'
  | 'access_denied'
  | 'pow_invalid'
  | 'not_found'
  | 'username_taken'
  | 'stale_version'
  | 'payload_too_large'
  | 'rate_limited'
  | 'internal'
  | 'community_full'
  | 'community_inbox_full'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ErrorCode,
    message: string,
    readonly retryAfterSeconds?: number,
    /** Further response headers the error carries, such as `X-Avatar-Version` (§4.12). */
    readonly headers?: Readonly<Record<string, string>>,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  toBody(): { error: { code: ErrorCode, message: string } } {
    return { error: { code: this.code, message: this.message } }
  }
}

export const badRequest = (message: string) => new ApiError(400, 'bad_request', message)
export const unauthorized = () => new ApiError(401, 'unauthorized', 'missing or invalid auth token')
export const accessDenied = (message = 'a valid access key or proof-of-work is required') =>
  new ApiError(403, 'access_denied', message)
export const powInvalid = () => new ApiError(403, 'pow_invalid', 'proof-of-work is missing, wrong, expired or reused')
export const notFound = (what = 'resource') => new ApiError(404, 'not_found', `${what} not found`)
export const usernameTaken = () => new ApiError(409, 'username_taken', 'username is already taken')
/** §4.12: an avatar write whose version is not above the account's highest; the client writes again with `highest + 1`. */
export const staleVersion = (highest: bigint) =>
  new ApiError(409, 'stale_version', 'the version is not above the highest one written', undefined, { 'x-avatar-version': highest.toString() })
export const payloadTooLarge = (limit: number) =>
  new ApiError(413, 'payload_too_large', `body exceeds the limit of ${limit} bytes`)
export const rateLimited = (retryAfterSeconds: number, message = 'too many requests') =>
  new ApiError(429, 'rate_limited', message, retryAfterSeconds)
/** The relay is short of disk space or cannot store fast enough; the client retries later. */
export const unavailable = (retryAfterSeconds: number) =>
  new ApiError(503, 'internal', 'the relay cannot store anything right now', retryAfterSeconds)
/** COMMUNITIES.md: the recipient's community inbox, or this sender's share of it, is full; the sender retries later. */
export const communityInboxFull = (retryAfterSeconds: number, message: string) =>
  new ApiError(429, 'community_inbox_full', message, retryAfterSeconds)
export const internalError = () => new ApiError(500, 'internal', 'internal error')

/**
 * A log-safe summary of an unexpected error. Database errors from drizzle
 * embed the failed SQL and its parameters (ids, hashes, envelope bytes) in
 * their message, so for anything carrying an error code only the code is
 * reported.
 */
export function describeError(error: unknown): string {
  const root = error instanceof Error && error.cause instanceof Error ? error.cause : error
  if (root instanceof Error) {
    const code = (root as { code?: unknown }).code
    if (typeof code === 'string') return `${root.name} ${code}`
    if (root.name === 'DrizzleQueryError') return 'DrizzleQueryError'
    return `${root.name}: ${root.message.slice(0, 200)}`
  }
  return 'non-error value thrown'
}
