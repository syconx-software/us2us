import { randomBytes } from 'node:crypto'
import { and, asc, eq, gt, inArray, like, lt, or, sql, type SQL } from 'drizzle-orm'
import { isDuplicateKey, retryOnDeadlock, type Database, type Db } from '../db/client'
import { accounts, communities, communityMembers as members, communityRequests as requests, communityInvites as invites, communityEnvelopes as envelopes } from '../db/schema'
import { COMMUNITY_MAX_MEMBERS, COMMUNITY_TTL, communityFull, communitySearchPrefix, communitySummary, parseCommunityState,
  stateResponse, verifyCommunitySignature, type CommunityState } from '../lib/community'
import { sha256 } from '../lib/crypto'
import { encodeB64u, formatUuid } from '../lib/encoding'
import { ApiError, accessDenied, badRequest, communityInboxFull, notFound, rateLimited, unavailable } from '../lib/errors'
import type { TakeResult } from '../lib/rate-limit'
import { lockAccount } from './accounts'

const MiB = 1024 * 1024
const HOUR = 3_600_000

/** The limits of COMMUNITIES.md. */
export const COMMUNITY_LIMITS = {
  owned: 20,
  memberships: 50,
  /** Pending requests per community, and per requesting account over all communities. */
  pending: 100,
  pendingPerAccount: 10,
  /** A pending request the owner has not answered expires after this. */
  requestTtlMs: 14 * 24 * HOUR,
  invites: 20,
  /** Live (stored, unexpired) envelopes and their bytes per recipient, over all communities. */
  inboxEnvelopes: 10_000,
  inboxBytes: 50 * MiB,
  /** One community's share of a recipient's live envelopes. */
  communityEnvelopes: 2_500,
  communityBytes: 12.5 * MiB,
  /** One sender's share of a recipient's live envelopes, over all communities. */
  senderAcrossEnvelopes: 1_000,
  senderAcrossBytes: 16 * MiB,
  /** Live envelopes and bytes from one sender to one recipient in one community. */
  senderEnvelopes: 500,
  senderBytes: 8 * MiB,
  /** Acknowledged or obsolete rows stay this long as retry tombstones (no body). */
  tombstoneTtlMs: 72 * HOUR,
  pageEnvelopes: 100,
  pageBytes: MiB,
}

const allowed: TakeResult = { ok: true }
const SWEEP_BATCH = 1000
/** Batches per sweep, so one run stays short even after a long outage; the next run continues. */
const SWEEP_MAX_BATCHES = 50

const conflict = (message: string) => new ApiError(409, 'stale_version', message)
const hex = (id: Buffer) => id.toString('hex')
type GroupRow = typeof communities.$inferSelect

/**
 * Isolated community metadata and mailbox. Every membership mutation and send
 * locks the community row first, so admission, leaving and revocation cannot
 * race delivery. Lock order: community rows, then account rows in byte order
 * (an account deletion locks the communities it owns before its account
 * row). A transaction InnoDB still picks as a deadlock victim is run again.
 */
export class CommunityStore {
  constructor(
    private readonly database: Database,
    private readonly reserveDisk: (bytes: number) => boolean = () => true,
    /** The daily delivery budget of a sender towards one recipient (tombstones included). */
    private readonly takePair: (sender: Buffer, recipient: Buffer) => TakeResult = () => allowed,
    /**
     * Envelope ids. The relay passes ids that sort by arrival per recipient
     * (envelope-id.ts), so that deliveries in the same millisecond keep
     * their order in the inbox; random ids by default.
     */
    private readonly nextId: (recipient: Buffer, now: number) => Buffer = () => randomBytes(16),
  ) {}

  async create(actor: Buffer, state: CommunityState, signature: Buffer) {
    if (!actor.equals(state.owner) || state.revision !== 1 || state.members.length !== 1) throw badRequest('initial community must contain only its owner at revision 1')
    verifyCommunitySignature(state, signature)
    try {
      return await this.transaction(async (tx) => {
        if (!await lockAccount(tx, actor)) throw accessDenied('account deleted')
        await this.checkIdentity(tx, actor, state.ownerIdentityKey)
        const [existing] = await tx.select({ state: communities.state, signature: communities.signature }).from(communities)
          .where(eq(communities.id, state.id)).limit(1)
        if (existing) return retriedCreate(existing, state)
        const owned = await tx.select({ id: communities.id }).from(communities).where(eq(communities.owner, actor)).limit(COMMUNITY_LIMITS.owned)
        if (owned.length >= COMMUNITY_LIMITS.owned) throw rateLimited(3600, 'community ownership limit reached')
        await this.checkMembershipLimit(tx, actor)
        const row = { id: state.id, owner: actor, visibility: state.visibility, name: state.name,
          searchName: state.name.normalize('NFC').toLowerCase(), revision: 1, state: state.bytes, signature }
        await tx.insert(communities).values(row)
        await tx.insert(members).values({ communityId: state.id, accountId: actor })
        return stateResponse(row)
      })
    } catch (error) {
      if (!isDuplicateKey(error)) throw error
      // Another create of the same ID committed first.
      const [existing] = await this.database.db.select({ state: communities.state, signature: communities.signature }).from(communities)
        .where(eq(communities.id, state.id)).limit(1)
      if (existing) return retriedCreate(existing, state)
      throw conflict('community ID already exists')
    }
  }

  async browse(query: string, after?: Buffer) {
    const prefix = communitySearchPrefix(query)
    if (prefix === null) throw badRequest('invalid search')
    const search = prefix.normalize('NFC').toLowerCase()
    const rows = await this.database.db.select().from(communities).where(and(
      eq(communities.visibility, 1), after ? gt(communities.id, after) : undefined,
      search ? like(communities.searchName, `${search.replace(/[\\%_]/g, '\\$&')}%`) : undefined,
    )).orderBy(asc(communities.id)).limit(21)
    const page = rows.slice(0, 20)
    return { communities: page.map(row => communitySummary(parseCommunityState(row.state))),
      nextCursor: rows.length > 20 ? formatUuid(page[19]!.id) : null }
  }

  async mine(actor: Buffer) {
    const rows = await this.database.db.select({ state: communities.state, signature: communities.signature }).from(communities)
      .innerJoin(members, eq(members.communityId, communities.id)).where(eq(members.accountId, actor)).limit(COMMUNITY_LIMITS.memberships)
    return { communities: rows.map(stateResponse) }
  }

  async get(actor: Buffer, id: Buffer) {
    const [row] = await this.database.db.select({ state: communities.state, signature: communities.signature }).from(communities)
      .innerJoin(members, eq(members.communityId, communities.id)).where(and(eq(communities.id, id), eq(members.accountId, actor))).limit(1)
    if (!row) throw notFound('community')
    return stateResponse(row)
  }

  /**
   * The owner's next signed state. Admission and removal follow the relay's
   * own membership, not only the previous roster: an account in the roster
   * that has no transport membership (it left, or was deleted) may stay
   * listed or be dropped, but is never admitted by a state that merely keeps
   * it listed, even with a pending request (an unrelated roster change must
   * not re-admit a leaver). Only an account that is new to the roster is
   * admitted, and only with an unexpired pending request; to take a leaver
   * back, the owner signs a state without it and then one with it.
   */
  async update(actor: Buffer, id: Buffer, state: CommunityState, signature: Buffer) {
    if (!id.equals(state.id)) throw badRequest('community ID mismatch')
    verifyCommunitySignature(state, signature)
    return this.transaction(async (tx) => {
      const row = await this.ownerGroup(tx, id, actor)
      const previous = parseCommunityState(row.state)
      if (state.revision !== previous.revision + 1) throw conflict('refresh current community revision')
      if (!state.owner.equals(previous.owner) || !state.ownerIdentityKey.equals(previous.ownerIdentityKey)
          || state.visibility !== previous.visibility || state.name !== previous.name) throw badRequest('owner, identity, visibility and name are immutable')
      const signed = new Map(previous.members.map(m => [hex(m.accountId), m]))
      for (const member of state.members) {
        const old = signed.get(hex(member.accountId))
        if (old && !old.identityKey.equals(member.identityKey)) throw badRequest('member identity cannot change silently')
      }
      const current = new Set((await tx.select({ accountId: members.accountId }).from(members).where(eq(members.communityId, id))).map(m => hex(m.accountId)))
      const outside = state.members.filter(m => !current.has(hex(m.accountId)))
      const pending = new Set(outside.length === 0 ? [] : (await tx.select({ accountId: requests.accountId }).from(requests)
        .where(and(eq(requests.communityId, id), inArray(requests.accountId, outside.map(m => m.accountId)), liveRequest(Date.now())))).map(r => hex(r.accountId)))
      const admitted = outside.filter(m => pending.has(hex(m.accountId)) && !signed.has(hex(m.accountId)))
      if (outside.some(m => !pending.has(hex(m.accountId)) && !signed.has(hex(m.accountId)))) throw accessDenied('new member needs a pending request')
      // Members are sorted, so account rows are locked in byte order.
      for (const member of admitted) {
        if (!await lockAccount(tx, member.accountId)) throw notFound('requester')
        await this.checkIdentity(tx, member.accountId, member.identityKey)
        await this.checkMembershipLimit(tx, member.accountId)
      }
      const next = new Set(state.members.map(m => hex(m.accountId)))
      const removed = [...current].filter(key => !next.has(key)).map(key => Buffer.from(key, 'hex'))
      if (removed.length) {
        await tx.delete(members).where(and(eq(members.communityId, id), inArray(members.accountId, removed)))
        await this.purge(tx, id, removed)
      }
      if (admitted.length) {
        await tx.insert(members).values(admitted.map(m => ({ communityId: id, accountId: m.accountId })))
        await tx.delete(requests).where(and(eq(requests.communityId, id), inArray(requests.accountId, admitted.map(m => m.accountId))))
      }
      // The first extension fails closed on obsolete revisions: drop their
      // bodies now and keep the rows as bounded retry tombstones.
      await tx.update(envelopes).set({ body: null, size: 0 }).where(and(eq(envelopes.communityId, id), gt(envelopes.size, 0)))
      await tx.update(communities).set({ revision: state.revision, state: state.bytes, signature }).where(eq(communities.id, id))
      return stateResponse({ state: state.bytes, signature })
    })
  }

  async request(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      const group = await this.group(tx, id)
      if (group.visibility !== 1) throw notFound('community')
      await this.addRequest(tx, group, actor)
    })
  }

  async pending(actor: Buffer, id: Buffer) {
    return this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      const rows = await tx.select({ accountId: requests.accountId, identityKey: accounts.identityKey, createdAt: requests.createdAt }).from(requests)
        .innerJoin(accounts, eq(accounts.id, requests.accountId)).where(and(eq(requests.communityId, id), liveRequest(Date.now())))
        .orderBy(asc(requests.createdAt)).limit(COMMUNITY_LIMITS.pending)
      return { requests: rows.map(row => ({ accountId: formatUuid(row.accountId), identityKey: encodeB64u(row.identityKey), createdAt: row.createdAt })) }
    })
  }

  async reject(actor: Buffer, id: Buffer, requester: Buffer) {
    await this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      await tx.delete(requests).where(and(eq(requests.communityId, id), eq(requests.accountId, requester)))
      // Rejecting an invite request also prevents its same-account replay.
      await tx.delete(invites).where(and(eq(invites.communityId, id), eq(invites.redeemedBy, requester)))
    })
  }

  /** The owner rejects every pending request at once (and the invitations they redeemed). */
  async rejectAll(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      const pending = (await tx.select({ accountId: requests.accountId }).from(requests).where(eq(requests.communityId, id))).map(row => row.accountId)
      if (pending.length === 0) return
      await tx.delete(requests).where(eq(requests.communityId, id))
      await tx.delete(invites).where(and(eq(invites.communityId, id), inArray(invites.redeemedBy, pending)))
    })
  }

  /** The caller withdraws its own pending request; nothing to withdraw is success too. */
  async withdraw(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      const [group] = await tx.select({ id: communities.id }).from(communities).where(eq(communities.id, id)).for('update')
      if (!group) return
      await tx.delete(requests).where(and(eq(requests.communityId, id), eq(requests.accountId, actor)))
      // As with a rejection, the redeemed invitation cannot be replayed.
      await tx.delete(invites).where(and(eq(invites.communityId, id), eq(invites.redeemedBy, actor)))
    })
  }

  /**
   * A member other than the owner leaves: its transport membership and
   * every queued envelope to or from it in this community go at once. The
   * signed roster still lists it until the owner publishes the next state.
   */
  async leave(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      const group = await this.group(tx, id)
      const [member] = await tx.select({ accountId: members.accountId }).from(members)
        .where(and(eq(members.communityId, id), eq(members.accountId, actor))).limit(1)
      if (!member) throw notFound('community')
      if (group.owner.equals(actor)) throw new ApiError(409, 'bad_request', 'the owner cannot leave; delete the community instead')
      await tx.delete(members).where(and(eq(members.communityId, id), eq(members.accountId, actor)))
      await this.purge(tx, id, [actor])
    })
  }

  async invite(actor: Buffer, id: Buffer, expiresInSeconds: number) {
    if (!Number.isInteger(expiresInSeconds) || expiresInSeconds < 60 || expiresInSeconds > 604800) throw badRequest('invite expiry must be 60..604800 seconds')
    return this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      const now = Date.now()
      await tx.delete(invites).where(and(eq(invites.communityId, id), lt(invites.expiresAt, now)))
      const live = await tx.select({ tokenHash: invites.tokenHash }).from(invites).where(eq(invites.communityId, id)).limit(COMMUNITY_LIMITS.invites)
      if (live.length >= COMMUNITY_LIMITS.invites) throw rateLimited(3600, 'too many active invitations')
      const token = randomBytes(32), expiresAt = now + expiresInSeconds * 1000
      await tx.insert(invites).values({ tokenHash: sha256(token), communityId: id, expiresAt })
      return { token: encodeB64u(token), expiresAt }
    })
  }

  async revokeInvites(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      await tx.delete(invites).where(eq(invites.communityId, id))
    })
  }

  async redeem(actor: Buffer, id: Buffer, token: Buffer) {
    return this.transaction(async (tx) => {
      const group = await this.group(tx, id)
      const tokenHash = sha256(token)
      const [invite] = await tx.select().from(invites).where(and(eq(invites.tokenHash, tokenHash), eq(invites.communityId, id))).for('update')
      if (!invite || invite.expiresAt <= Date.now() || (invite.redeemedBy && !invite.redeemedBy.equals(actor))) throw notFound('community')
      await this.addRequest(tx, group, actor)
      await tx.update(invites).set({ redeemedBy: actor }).where(eq(invites.tokenHash, tokenHash))
      return communitySummary(parseCommunityState(group.state))
    })
  }

  async remove(actor: Buffer, id: Buffer) {
    await this.transaction(async (tx) => {
      await this.ownerGroup(tx, id, actor)
      await tx.delete(communities).where(eq(communities.id, id))
    })
  }

  async send(actor: Buffer, id: Buffer, recipient: Buffer, messageId: Buffer, revision: number, body: Buffer) {
    const digest = sha256(body)
    return this.transaction(async (tx) => {
      const group = await this.group(tx, id)
      const membership = await tx.select({ id: members.accountId }).from(members).where(and(eq(members.communityId, id), inArray(members.accountId, [actor, recipient])))
      if (actor.equals(recipient) || membership.length !== 2) throw notFound('community')
      if (group.revision !== revision) throw conflict('refresh current community revision and recipients')
      // Sends into one recipient's inbox serialize on its account row; the
      // sender's row is locked too, so its deletion cannot interleave.
      for (const account of [actor, recipient].sort(Buffer.compare)) {
        if (!await lockAccount(tx, account)) throw notFound('community')
      }
      const [existing] = await tx.select().from(envelopes).where(and(eq(envelopes.communityId, id), eq(envelopes.sender, actor), eq(envelopes.recipient, recipient), eq(envelopes.messageId, messageId))).limit(1)
      if (existing) {
        if (existing.revision === revision && existing.digest.equals(digest)) return { id: encodeB64u(existing.id), serverTimestamp: existing.serverTimestamp }
        // A message re-encrypted for the current revision after a roster change keeps its ID; the
        // row of the obsolete revision has no body left and gives way to it. Anything else conflicts.
        if (existing.revision >= revision) throw conflict('message ID already used with different ciphertext')
        await tx.delete(envelopes).where(eq(envelopes.id, existing.id))
      }
      const now = Date.now()
      const usage = await inboxUsage(tx, recipient, id, actor, now)
      const size = body.length
      if (usage.count >= COMMUNITY_LIMITS.inboxEnvelopes || usage.bytes + size > COMMUNITY_LIMITS.inboxBytes) {
        throw communityInboxFull(600, 'the recipient’s community inbox is full')
      }
      if (usage.communityCount >= COMMUNITY_LIMITS.communityEnvelopes || usage.communityBytes + size > COMMUNITY_LIMITS.communityBytes) {
        throw communityInboxFull(600, 'this community’s share of the recipient’s inbox is full')
      }
      if (usage.senderCount >= COMMUNITY_LIMITS.senderAcrossEnvelopes || usage.senderBytes + size > COMMUNITY_LIMITS.senderAcrossBytes
        || usage.shareCount >= COMMUNITY_LIMITS.senderEnvelopes || usage.shareBytes + size > COMMUNITY_LIMITS.senderBytes) {
        throw communityInboxFull(600, 'too many undelivered messages from this sender to this recipient')
      }
      const pair = this.takePair(actor, recipient)
      if (!pair.ok) throw rateLimited(pair.retryAfterSeconds, 'too many messages from this sender to this recipient today')
      // Invalid/stale sends and idempotent retries never spend shared disk allowance.
      if (!this.reserveDisk(size)) throw unavailable(30)
      const envelopeId = this.nextId(recipient, now)
      await tx.insert(envelopes).values({ id: envelopeId, communityId: id, sender: actor, recipient, messageId, revision, serverTimestamp: now, digest, size: body.length, body })
      return { id: encodeB64u(envelopeId), serverTimestamp: now }
    })
  }

  /**
   * Oldest first: at most 100 envelopes and 1 MiB of ciphertext (at least
   * one envelope). The live rows of one recipient are bounded by the inbox
   * quota and read from the community_inbox index (tombstones skipped), so
   * sorting them is cheap. The page is chosen from the stored sizes, and only
   * its bodies are read. Live mail always belongs to a current membership
   * and revision (leaving and removal purge, a new revision drops bodies);
   * a row that does not is dropped here too, so it can never hold up the
   * inbox.
   */
  async inbox(actor: Buffer) {
    const { db } = this.database
    const { pageEnvelopes, pageBytes } = COMMUNITY_LIMITS
    const rows = await db.select({ id: envelopes.id, communityId: envelopes.communityId, revision: envelopes.revision,
      serverTimestamp: envelopes.serverTimestamp, size: envelopes.size }).from(envelopes, { forceIndex: 'community_inbox' })
      .where(and(eq(envelopes.recipient, actor), gt(envelopes.size, 0), gt(envelopes.serverTimestamp, Date.now() - COMMUNITY_TTL)))
      .orderBy(asc(envelopes.serverTimestamp), asc(envelopes.id))
      .limit(pageEnvelopes + 1)
    if (rows.length === 0) return { envelopes: [], more: false }
    const current = new Map((await db.select({ id: communities.id, revision: communities.revision }).from(members)
      .innerJoin(communities, eq(communities.id, members.communityId)).where(eq(members.accountId, actor))).map(row => [hex(row.id), row.revision]))
    const obsolete = rows.filter(row => current.get(hex(row.communityId)) !== row.revision).map(row => row.id)
    if (obsolete.length) await this.ack(actor, obsolete)
    const page: typeof rows = []
    let bytes = 0
    for (const row of rows) {
      if (current.get(hex(row.communityId)) !== row.revision) continue
      if (page.length === pageEnvelopes || (page.length > 0 && bytes + row.size > pageBytes)) break
      bytes += row.size
      page.push(row)
    }
    const bodies = new Map(page.length === 0 ? [] : (await db.select({ id: envelopes.id, body: envelopes.body }).from(envelopes)
      .where(and(eq(envelopes.recipient, actor), inArray(envelopes.id, page.map(row => row.id))))).map(row => [hex(row.id), row.body]))
    const result = []
    for (const row of page) {
      const body = bodies.get(hex(row.id))
      if (!body) continue // acknowledged or made obsolete meanwhile
      result.push({ id: encodeB64u(row.id), communityId: formatUuid(row.communityId), revision: row.revision,
        serverTimestamp: row.serverTimestamp, envelope: encodeB64u(body) })
    }
    return { envelopes: result, more: rows.length > pageEnvelopes || page.length + obsolete.length < rows.length }
  }

  async ack(actor: Buffer, ids: Buffer[]) {
    if (ids.length) await this.database.db.update(envelopes).set({ body: null, size: 0 }).where(and(eq(envelopes.recipient, actor), inArray(envelopes.id, ids), gt(envelopes.size, 0)))
  }

  /**
   * Deletes expired deliveries, tombstones older than a few days, expired
   * invitations and unanswered requests, in bounded batches.
   */
  async sweep(now: number): Promise<{ communityEnvelopes: number, communityTombstones: number, communityInvites: number, communityRequests: number }> {
    const { db } = this.database
    const communityEnvelopes = await batches(async () => {
      const [header] = await db.delete(envelopes).where(lt(envelopes.serverTimestamp, now - COMMUNITY_TTL)).limit(SWEEP_BATCH)
      return header.affectedRows
    })
    const communityTombstones = await batches(async () => {
      const [header] = await db.delete(envelopes)
        .where(and(eq(envelopes.size, 0), lt(envelopes.serverTimestamp, now - COMMUNITY_LIMITS.tombstoneTtlMs))).limit(SWEEP_BATCH)
      return header.affectedRows
    })
    const communityInvites = await batches(async () => {
      const [header] = await db.delete(invites).where(lt(invites.expiresAt, now)).limit(SWEEP_BATCH)
      return header.affectedRows
    })
    const communityRequests = await batches(async () => {
      const [header] = await db.delete(requests).where(lt(requests.createdAt, now - COMMUNITY_LIMITS.requestTtlMs)).limit(SWEEP_BATCH)
      return header.affectedRows
    })
    return { communityEnvelopes, communityTombstones, communityInvites, communityRequests }
  }

  private transaction<T>(work: (tx: Db) => Promise<T>): Promise<T> {
    return retryOnDeadlock(() => this.database.transaction(work))
  }

  /** Drops what `accounts` sent or were sent in this community, and the invitations they redeemed. */
  private async purge(tx: Db, id: Buffer, removed: Buffer[]) {
    await tx.delete(envelopes).where(and(eq(envelopes.communityId, id), or(inArray(envelopes.sender, removed), inArray(envelopes.recipient, removed))))
    await tx.delete(invites).where(and(eq(invites.communityId, id), inArray(invites.redeemedBy, removed)))
  }

  private async group(tx: Db, id: Buffer): Promise<GroupRow> {
    const [row] = await tx.select().from(communities).where(eq(communities.id, id)).for('update')
    if (!row) throw notFound('community')
    return row
  }
  private async ownerGroup(tx: Db, id: Buffer, actor: Buffer) {
    const row = await this.group(tx, id)
    if (!row.owner.equals(actor)) throw notFound('community')
    return row
  }
  private async checkIdentity(tx: Db, id: Buffer, key: Buffer) {
    const [row] = await tx.select({ key: accounts.identityKey }).from(accounts).where(eq(accounts.id, id)).limit(1)
    if (!row?.key.equals(key)) throw badRequest('identity does not match registered account')
  }
  private async checkMembershipLimit(tx: Db, actor: Buffer) {
    const rows = await tx.select({ id: members.communityId }).from(members).where(eq(members.accountId, actor)).limit(COMMUNITY_LIMITS.memberships)
    if (rows.length >= COMMUNITY_LIMITS.memberships) throw rateLimited(3600, 'community membership limit reached')
  }
  private async addRequest(tx: Db, group: GroupRow, actor: Buffer) {
    if (!await lockAccount(tx, actor)) throw accessDenied('account deleted')
    const [member] = await tx.select().from(members).where(and(eq(members.communityId, group.id), eq(members.accountId, actor)))
    if (member) return
    const now = Date.now()
    const [pending] = await tx.select().from(requests).where(and(eq(requests.communityId, group.id), eq(requests.accountId, actor)))
    if (pending && pending.createdAt > now - COMMUNITY_LIMITS.requestTtlMs) return
    // An expired request the sweep has not reached yet is replaced by a fresh one.
    if (pending) await tx.delete(requests).where(and(eq(requests.communityId, group.id), eq(requests.accountId, actor)))
    const current = await tx.select({ id: members.accountId }).from(members).where(eq(members.communityId, group.id)).limit(COMMUNITY_MAX_MEMBERS)
    if (current.length >= COMMUNITY_MAX_MEMBERS) throw communityFull()
    const count = await tx.select({ id: requests.accountId }).from(requests)
      .where(and(eq(requests.communityId, group.id), liveRequest(now))).limit(COMMUNITY_LIMITS.pending)
    if (count.length >= COMMUNITY_LIMITS.pending) throw rateLimited(600, 'too many pending requests')
    // The actor's account row is locked, so its own requests cannot race this count.
    const mine = await tx.select({ id: requests.communityId }).from(requests)
      .where(and(eq(requests.accountId, actor), liveRequest(now))).limit(COMMUNITY_LIMITS.pendingPerAccount)
    if (mine.length >= COMMUNITY_LIMITS.pendingPerAccount) throw rateLimited(3600, 'too many pending requests from this account')
    await this.checkMembershipLimit(tx, actor)
    await tx.insert(requests).values({ communityId: group.id, accountId: actor, createdAt: now })
  }
}

/** A create whose ID exists: the same initial state again is an idempotent retry. */
function retriedCreate(existing: { state: Buffer, signature: Buffer }, state: CommunityState) {
  if (existing.state.equals(state.bytes)) return stateResponse(existing)
  throw conflict('community ID already exists')
}

/** A request the owner may still answer. */
function liveRequest(now: number) {
  return gt(requests.createdAt, now - COMMUNITY_LIMITS.requestTtlMs)
}

interface InboxUsage {
  count: number
  bytes: number
  communityCount: number
  communityBytes: number
  senderCount: number
  senderBytes: number
  shareCount: number
  shareBytes: number
}

/**
 * A recipient's live community mail in one read of the covering
 * `community_inbox` index: in total, of one community, of one sender over
 * all communities, and of that sender in that community. Only stored,
 * unexpired bodies count; the index puts `size` first after the recipient,
 * so tombstones (size 0) are not even read, and the scan is bounded by the
 * inbox quota.
 */
async function inboxUsage(tx: Db, recipient: Buffer, communityId: Buffer, sender: Buffer, now: number): Promise<InboxUsage> {
  const inCommunity = sql`${envelopes.communityId} = ${communityId}`
  const fromSender = sql`${envelopes.sender} = ${sender}`
  const sum = (condition: SQL, value: SQL | number) => sql<number>`coalesce(sum(if(${condition}, ${value}, 0)), 0)`.mapWith(Number)
  const [row] = await tx.select({
    count: sql<number>`count(*)`.mapWith(Number),
    bytes: sql<number>`coalesce(sum(${envelopes.size}), 0)`.mapWith(Number),
    communityCount: sum(inCommunity, 1),
    communityBytes: sum(inCommunity, sql`${envelopes.size}`),
    senderCount: sum(fromSender, 1),
    senderBytes: sum(fromSender, sql`${envelopes.size}`),
    shareCount: sum(sql`${inCommunity} and ${fromSender}`, 1),
    shareBytes: sum(sql`${inCommunity} and ${fromSender}`, sql`${envelopes.size}`),
  }).from(envelopes, { forceIndex: 'community_inbox' })
    .where(and(eq(envelopes.recipient, recipient), gt(envelopes.size, 0), gt(envelopes.serverTimestamp, now - COMMUNITY_TTL)))
  return row!
}

async function batches(step: () => Promise<number>): Promise<number> {
  let total = 0
  for (let batch = 0; batch < SWEEP_MAX_BATCHES; batch++) {
    const deleted = await step()
    total += deleted
    if (deleted < SWEEP_BATCH) break
  }
  return total
}
