package com.syconx.us2us.core.presence

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.presenceMessage
import com.syconx.us2us.core.testing.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PresenceTest {
    private val peer = AccountId(UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef"))
    private val clock = MutableClock()
    private val tracker = PresenceTracker(clock)

    private fun online(validFor: Int = 300) = presenceMessage { state = PresenceMessage.State.ONLINE; validForSeconds = validFor }
    private val offline = presenceMessage { state = PresenceMessage.State.OFFLINE }

    @Test
    fun `display follows the spec's windows`() {
        assertEquals(PresenceStatus.None, tracker.status(peer))
        tracker.record(peer, online())
        assertEquals(PresenceStatus.Active, tracker.status(peer))
        assertEquals(listOf(peer), tracker.onlineContacts())

        tracker.record(peer, offline)
        assertEquals(PresenceStatus.ActiveJustNow, tracker.status(peer))
        assertEquals(emptyList<AccountId>(), tracker.onlineContacts())
        clock.advance(4.minutes)
        assertEquals(PresenceStatus.ActiveJustNow, tracker.status(peer))
        clock.advance(2.minutes)
        assertEquals(PresenceStatus.ActiveMinutesAgo(6), tracker.status(peer))
        clock.advance(55.minutes)
        assertEquals(PresenceStatus.None, tracker.status(peer), "no long-term last seen")
    }

    @Test
    fun `an ONLINE that is not refreshed expires, and peers cannot extend it`() {
        tracker.record(peer, online(validFor = 86_400))
        clock.advance(299.seconds)
        assertEquals(PresenceStatus.Active, tracker.status(peer))
        clock.advance(2.seconds)
        // Crash or lost network: the last proof of activity counts as going offline.
        assertEquals(PresenceStatus.ActiveMinutesAgo(5), tracker.status(peer))
        tracker.forget(peer)
        assertEquals(PresenceStatus.None, tracker.status(peer))
    }

    @Test
    fun `only accepted contacts that share, and whose key we hold, are eligible`() {
        val contact = Contact(peer, "bob", ContactState.ACCEPTED, theirAccessKey = AccessKey(ByteArray(16)), presenceSharing = true)
        assertTrue(PresencePolicy.isEligible(contact, weShare = true))
        assertFalse(PresencePolicy.isEligible(contact, weShare = false), "reciprocal")
        assertFalse(PresencePolicy.isEligible(contact.copy(presenceSharing = false), weShare = true))
        assertFalse(PresencePolicy.isEligible(contact.copy(theirAccessKey = null), weShare = true))
        for (state in listOf(ContactState.OUTGOING_REQUEST, ContactState.INCOMING_REQUEST, ContactState.BLOCKED)) {
            assertFalse(PresencePolicy.isEligible(contact.copy(state = state), weShare = true), state.name)
        }
    }

    @Test
    fun `an ONLINE is answered once and a reply never`() {
        val announcement = PresencePolicy.online().presence
        assertEquals(300, announcement.validForSeconds)
        assertTrue(PresencePolicy.shouldReply(announcement))
        assertFalse(PresencePolicy.shouldReply(PresencePolicy.online(reply = true).presence))
        assertFalse(PresencePolicy.shouldReply(PresencePolicy.offline().presence))
    }
}
