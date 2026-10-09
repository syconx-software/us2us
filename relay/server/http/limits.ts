/** Small JSON bodies (username, access key, acks). */
export const SMALL_BODY_LIMIT = 16 * 1024

/**
 * Registration and key uploads: up to 100 Kyber one-time keys of about 1.6 KB
 * each, base64url-encoded, plus 100 EC keys.
 */
export const KEY_UPLOAD_BODY_LIMIT = 512 * 1024

/** `POST /v1/messages/ephemeral` (§4.6): up to 200 envelopes of 4 KiB, base64url-encoded. */
export const EPHEMERAL_BATCH_BODY_LIMIT = 1024 * 1024
