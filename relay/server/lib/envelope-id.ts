import { random } from './crypto'

const EPHEMERAL_FLAG = 0x80
const FLAG_BYTE = 8

/**
 * Envelope ids (PROTOCOL.md §4.6: 16 bytes, opaque to clients).
 *
 * Ids of envelopes that may be stored are laid out as
 *
 *   uint48_be(milliseconds) ‖ uint16_be(counter) ‖ 0 (1 bit) ‖ random(63 bits)
 *
 * so that byte order equals arrival order. The mailbox table is keyed by
 * (recipient, id), which turns "ordered by arrival" into a plain primary-key
 * range scan and lets ids be assigned before an envelope is (or is not)
 * written to the database on the online fast path.
 *
 * Ephemeral envelopes are never stored and need no order, so their ids are
 * random except for the ephemeral flag bit. A counter would tell each
 * recipient of a presence batch its position in the sender's fan-out, and
 * the last one roughly how many contacts the sender shares presence with.
 * Clients may acknowledge ephemeral frames like any other; the flag lets the
 * relay ignore those acks instead of running a DELETE that cannot match.
 */
export class EnvelopeIdGenerator {
  private lastMs = 0
  private counter = 0

  next(now = Date.now(), ephemeral = false): Buffer {
    if (ephemeral) {
      const id = random(16)
      id[FLAG_BYTE] = id[FLAG_BYTE]! | EPHEMERAL_FLAG
      return id
    }
    if (now > this.lastMs) {
      this.lastMs = now
      this.counter = 0
    } else if (++this.counter > 0xffff) {
      // More than 65536 ids in one millisecond, or the clock went backwards:
      // borrow from the next millisecond to stay monotonic.
      this.lastMs += 1
      this.counter = 0
    }
    const id = Buffer.alloc(16)
    id.writeUIntBE(this.lastMs, 0, 6)
    id.writeUInt16BE(this.counter, 6)
    random(8).copy(id, FLAG_BYTE)
    id[FLAG_BYTE] = id[FLAG_BYTE]! & ~EPHEMERAL_FLAG
    return id
  }
}

/** True for ids this relay issued to ephemeral envelopes, which are never in the mailbox. */
export function isEphemeralId(id: Uint8Array): boolean {
  return id.length === 16 && (id[FLAG_BYTE]! & EPHEMERAL_FLAG) !== 0
}
