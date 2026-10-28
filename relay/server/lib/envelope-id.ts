import { random } from './crypto'

const EPHEMERAL_FLAG = 0x80
const FLAG_BYTE = 8
/** Recipients remembered before the ones from earlier milliseconds are dropped. */
const MAX_TRACKED = 10_000

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
 * Order only matters within one mailbox, so the counter counts per
 * recipient: a relay-wide counter would let two recipients who compare ids
 * minted in the same millisecond see that they were adjacent (one sender's
 * burst). The millisecond never goes backwards for anyone, so ids stay
 * ordered when the clock steps back, and a recipient's counter that runs
 * over borrows the next millisecond.
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
  /** Per recipient: the millisecond of its last id and the counter within it. */
  private readonly counters = new Map<string, { ms: number, counter: number }>()

  next(now = Date.now(), ephemeral = false, recipientKey = ''): Buffer {
    if (ephemeral) {
      const id = random(16)
      id[FLAG_BYTE] = id[FLAG_BYTE]! | EPHEMERAL_FLAG
      return id
    }
    if (now > this.lastMs) this.lastMs = now
    let state = this.counters.get(recipientKey)
    if (state && state.ms === this.lastMs) {
      if (++state.counter > 0xffff) {
        // More than 65536 ids for one recipient in one millisecond: borrow from the next one.
        this.lastMs += 1
        state.ms = this.lastMs
        state.counter = 0
      }
    } else {
      if (this.counters.size >= MAX_TRACKED) this.prune()
      state = { ms: this.lastMs, counter: 0 }
      this.counters.set(recipientKey, state)
    }
    const id = Buffer.alloc(16)
    id.writeUIntBE(state.ms, 0, 6)
    id.writeUInt16BE(state.counter, 6)
    random(8).copy(id, FLAG_BYTE)
    id[FLAG_BYTE] = id[FLAG_BYTE]! & ~EPHEMERAL_FLAG
    return id
  }

  /** Recipients whose last id is from an earlier millisecond start at 0 again anyway. */
  private prune(): void {
    for (const [key, state] of this.counters) if (state.ms < this.lastMs) this.counters.delete(key)
  }
}

/** True for ids this relay issued to ephemeral envelopes, which are never in the mailbox. */
export function isEphemeralId(id: Uint8Array): boolean {
  return id.length === 16 && (id[FLAG_BYTE]! & EPHEMERAL_FLAG) !== 0
}
