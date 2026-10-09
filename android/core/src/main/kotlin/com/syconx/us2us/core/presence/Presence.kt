package com.syconx.us2us.core.presence

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.presenceMessage
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The rules of "active now" presence (PROTOCOL.md §8.6): end-to-end encrypted, always ephemeral,
 * only between accepted contacts, and reciprocal.
 */
object PresencePolicy {
    /** `valid_for_seconds` of an ONLINE announcement. */
    const val VALID_FOR_SECONDS = 300

    /** How often ONLINE is refreshed to the contacts currently seen online. */
    val REFRESH_INTERVAL: Duration = 240.seconds

    /** After OFFLINE: "Active just now" below this… */
    val JUST_NOW_WINDOW: Duration = 5.minutes

    /** …then "Active N min ago" until this, then nothing. No long-term "last seen" exists. */
    val RECENT_WINDOW: Duration = 60.minutes

    /**
     * Presence flows only between accepted contacts when **both** sides share it. Ephemeral sends
     * need the contact's access key (§4.6), so without one there is no way to send presence.
     */
    fun isEligible(contact: Contact, weShare: Boolean): Boolean =
        weShare && contact.state == ContactState.ACCEPTED && contact.presenceSharing && contact.theirAccessKey != null

    fun online(reply: Boolean = false): Content = content {
        presence = presenceMessage {
            state = PresenceMessage.State.ONLINE
            validForSeconds = VALID_FOR_SECONDS
            this.reply = reply
        }
    }

    fun offline(): Content = content { presence = presenceMessage { state = PresenceMessage.State.OFFLINE } }

    /** An eligible contact's ONLINE is answered once; a reply is never answered, so nothing ping-pongs. */
    fun shouldReply(presence: PresenceMessage): Boolean = presence.state == PresenceMessage.State.ONLINE && !presence.reply
}

/** What to show for a contact (§8.6 "Display"). */
sealed interface PresenceStatus {
    /** Green dot, "Aktif" / "Active". */
    data object Active : PresenceStatus

    /** "Az önce aktifti" / "Active just now": less than five minutes since going offline. */
    data object ActiveJustNow : PresenceStatus

    /** "N dk önce aktifti" / "Active N min ago", up to an hour. */
    data class ActiveMinutesAgo(val minutes: Int) : PresenceStatus

    /** Nothing is shown. */
    data object None : PresenceStatus
}

/**
 * In-memory view of the contacts' presence, fed by received `PresenceMessage`s. It deliberately
 * keeps nothing that can no longer be displayed, and nothing is ever persisted. Thread-safe.
 */
class PresenceTracker(private val clock: Clock = Clock.systemUTC()) {
    private sealed interface Entry {
        class Online(val lastSeen: Long, val until: Long) : Entry
        class Offline(val since: Long) : Entry
    }

    private val entries = HashMap<AccountId, Entry>()

    @Synchronized
    fun record(contact: AccountId, presence: PresenceMessage) {
        val now = clock.millis()
        when (presence.state) {
            PresenceMessage.State.ONLINE -> {
                // A peer cannot keep itself "active" for longer than the protocol allows.
                val validFor = presence.validForSeconds.coerceIn(0, PresencePolicy.VALID_FOR_SECONDS)
                entries[contact] = Entry.Online(now, now + validFor * 1000L)
            }
            PresenceMessage.State.OFFLINE -> entries[contact] = Entry.Offline(now)
            else -> Unit
        }
        prune(now)
    }

    /** Forgets a contact: presence sharing turned off, blocked or deleted. */
    @Synchronized
    fun forget(contact: AccountId) {
        entries.remove(contact)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun status(contact: AccountId): PresenceStatus = status(entries[contact], clock.millis())

    /** Contacts currently seen online: the targets of the refresh and of OFFLINE. */
    @Synchronized
    fun onlineContacts(): List<AccountId> {
        val now = clock.millis()
        return entries.filter { (_, entry) -> entry is Entry.Online && now < entry.until }.keys.toList()
    }

    private fun prune(now: Long) {
        entries.entries.removeAll { status(it.value, now) == PresenceStatus.None }
    }

    private fun status(entry: Entry?, now: Long): PresenceStatus {
        val offlineSince = when (entry) {
            null -> return PresenceStatus.None
            is Entry.Online -> if (now < entry.until) return PresenceStatus.Active else entry.lastSeen
            is Entry.Offline -> entry.since
        }
        val elapsed = now - offlineSince
        return when {
            elapsed < PresencePolicy.JUST_NOW_WINDOW.inWholeMilliseconds -> PresenceStatus.ActiveJustNow
            elapsed < PresencePolicy.RECENT_WINDOW.inWholeMilliseconds -> PresenceStatus.ActiveMinutesAgo((elapsed / 60_000).toInt())
            else -> PresenceStatus.None
        }
    }
}
