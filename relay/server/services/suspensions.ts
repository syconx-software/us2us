import { and, asc, eq, inArray, isNull, or } from 'drizzle-orm'
import { retryOnDeadlock, type Database } from '../db/client'
import {
  avatars,
  communities,
  communityEnvelopes,
  communityInvites,
  communityMembers,
  communityRequests,
  media,
  suspendedAccounts,
} from '../db/schema'
import { lockAccount } from './accounts'

export interface SuspensionChanges {
  /** Accounts suspended since the previous read (every one, on the first). */
  added: Buffer[]
  /** Suspended accounts whose stored content has not been removed yet. */
  unapplied: Buffer[]
}

/**
 * Accounts the operator suspended (scripts/account-suspend.mjs writes the
 * rows from another process). The relay keeps the whole list in memory, as
 * it is short, and reads it again every SUSPENSION_CHECK_SECONDS, so that
 * the checks on every request cost no query. Nothing is answered for an
 * account before the list has been read once.
 */
export class Suspensions {
  private ids = new Set<string>()
  private loaded = false
  private reading: Promise<SuspensionChanges> | null = null

  constructor(private readonly database: Database) {}

  /** Whether the account is suspended; reads the list first if it has never been read. */
  async isSuspended(accountId: Buffer): Promise<boolean> {
    if (!this.loaded) await this.refresh()
    return this.ids.has(accountId.toString('hex'))
  }

  /** The same for many accounts at once; returns the suspended ones as hex. */
  async suspendedOf(accountIds: Buffer[]): Promise<Set<string>> {
    if (!this.loaded) await this.refresh()
    return new Set(accountIds.map(id => id.toString('hex')).filter(key => this.ids.has(key)))
  }

  /** Reads the list again; concurrent callers share one read. */
  refresh(): Promise<SuspensionChanges> {
    this.reading ??= this.read().finally(() => {
      this.reading = null
    })
    return this.reading
  }

  /**
   * Removes what a suspended account still has on the relay, in one
   * transaction, and marks the suspension applied. Returns the ids of the
   * media it uploaded, whose files the caller removes after the rows; null
   * if there is nothing to do (the account or the suspension is gone, or it
   * was applied already).
   *
   * Removed: its media, its avatar record (the highest version stays, as
   * after a username change), the communities it owns (for every member:
   * nobody else can sign their next state), its memberships, join requests
   * and redeemed invitations, and every community delivery from or to it.
   * Sealed envelopes it sent cannot be told apart from any other (the relay
   * never learns the sender), so they stay with their recipients until
   * delivered or expired. Envelopes waiting for the account itself and its
   * keys stay too, so that lifting a mistaken suspension loses nothing.
   *
   * Locks as the community store does: community rows first (by id), then
   * the account row; a deadlock victim is run again.
   */
  async apply(accountId: Buffer, now: number): Promise<Buffer[] | null> {
    return retryOnDeadlock(() => this.database.transaction(async (tx) => {
      const owned = await tx.select({ id: communities.id }).from(communities).where(eq(communities.owner, accountId))
      const joined = await tx.select({ id: communityMembers.communityId }).from(communityMembers)
        .where(eq(communityMembers.accountId, accountId))
      const groups = [...new Map([...owned, ...joined].map(row => [row.id.toString('hex'), row.id])).values()]
      if (groups.length > 0) {
        await tx.select({ id: communities.id }).from(communities)
          .where(inArray(communities.id, groups)).orderBy(asc(communities.id)).for('update')
      }
      if (!await lockAccount(tx, accountId)) return null
      const [suspension] = await tx.select({ appliedAt: suspendedAccounts.appliedAt }).from(suspendedAccounts)
        .where(eq(suspendedAccounts.accountId, accountId)).for('update')
      if (!suspension || suspension.appliedAt !== null) return null

      const uploads = await tx.select({ id: media.id }).from(media).where(eq(media.uploader, accountId))
      await tx.delete(media).where(eq(media.uploader, accountId))
      await tx.update(avatars).set({ record: null }).where(eq(avatars.accountId, accountId))
      // Cascades to the members, requests, invitations and deliveries of these communities.
      await tx.delete(communities).where(eq(communities.owner, accountId))
      await tx.delete(communityEnvelopes)
        .where(or(eq(communityEnvelopes.sender, accountId), eq(communityEnvelopes.recipient, accountId)))
      await tx.delete(communityInvites).where(eq(communityInvites.redeemedBy, accountId))
      await tx.delete(communityRequests).where(eq(communityRequests.accountId, accountId))
      await tx.delete(communityMembers).where(eq(communityMembers.accountId, accountId))
      await tx.update(suspendedAccounts).set({ appliedAt: now })
        .where(and(eq(suspendedAccounts.accountId, accountId), isNull(suspendedAccounts.appliedAt)))
      return uploads.map(row => row.id)
    }))
  }

  private async read(): Promise<SuspensionChanges> {
    const rows = await this.database.db
      .select({ accountId: suspendedAccounts.accountId, appliedAt: suspendedAccounts.appliedAt })
      .from(suspendedAccounts)
    const ids = new Set(rows.map(row => row.accountId.toString('hex')))
    const added = rows.filter(row => !this.ids.has(row.accountId.toString('hex'))).map(row => row.accountId)
    this.ids = ids
    this.loaded = true
    return { added, unapplied: rows.filter(row => row.appliedAt === null).map(row => row.accountId) }
  }
}
