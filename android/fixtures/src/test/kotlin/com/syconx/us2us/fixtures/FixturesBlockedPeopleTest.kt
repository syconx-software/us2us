package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.BlockedPerson
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 7.1 › Engellenenler on the fixtures (iOS `BlockedPeopleFixtureTests`): the harness's two kinds, and
 * unblocking from the list, which must leave the person exactly as unblocking from the contact
 * screen does.
 */
class FixturesBlockedPeopleTest {
    private fun TestScope.service() = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignFixtures.referenceDate),
        latency = Duration.ZERO,
        scope = backgroundScope,
    )

    private val bora = ChatId("bora")
    private val lale = ChatId("lale")

    private fun FixturesMessengerService.ids() = blockedPeople.value.map { it.id.value }

    @Test
    fun nobodyIsBlockedInTheDesignsData() = runTest {
        assertTrue(service().blockedPeople.value.isEmpty())
    }

    @Test
    fun theHarnessBlocksOneWithAChatAndOneWithout() = runTest {
        val service = service()
        service.loadBlockedPeople()
        assertEquals(listOf("bora", "lale"), service.ids())
        val chat = checkNotNull(service.chat(bora).value)
        assertTrue(chat.peer.isBlocked, "bora's chat stays in 4.1, read only")
        assertEquals(chat.peer.palette, service.blockedPeople.value.first().palette, "the list draws bora as 4.1 does")
        assertNull(service.chat(lale).value, "lale's chat is gone")
        assertTrue(service.blockedPeople.value.all { it.avatar == AvatarPicture.Initials && it.isUsernameKnown })
        service.loadBlockedPeople()
        assertEquals(2, service.blockedPeople.value.size, "loading twice blocks nobody twice")
    }

    @Test
    fun blockingFromTheContactScreenOrARequestListsThePerson() = runTest {
        val service = service()
        assertTrue(service.block(ChatId("mert_k")))
        val request = service.requests.value.first { it.id == RequestId("kuzey_dev") }
        service.block(request)
        assertEquals(listOf("kuzey_dev", "mert_k"), service.ids())
    }

    @Test
    fun unblockingSomeoneWithAChatFromTheList() = runTest {
        val service = service()
        val before = checkNotNull(service.chat(bora).value).peer
        service.loadBlockedPeople()
        val person = service.blockedPeople.value.first { it.id == bora }
        assertTrue(service.unblock(person))
        assertEquals(listOf("lale"), service.ids())
        assertEquals(before, service.chat(bora).value?.peer, "accepted again, as before the block")

        // The contact screen's way gives the same result.
        val other = service()
        other.loadBlockedPeople()
        assertTrue(other.unblock(bora))
        assertEquals(service.chat(bora).value?.peer, other.chat(bora).value?.peer)
        assertEquals(service.blockedPeople.value, other.blockedPeople.value)
    }

    @Test
    fun unblockingSomeoneWithoutAChatFromTheList() = runTest {
        val service = service()
        service.loadBlockedPeople()
        val chats = service.chats.value.map { it.id }
        val requests = service.requests.value.map { it.id }
        val person = service.blockedPeople.value.first { it.id == lale }
        assertTrue(service.unblock(person))
        assertEquals(listOf("bora"), service.ids())
        assertEquals(chats, service.chats.value.map { it.id })
        assertEquals(requests, service.requests.value.map { it.id })
        assertFalse(service.unblock(person), "a second tap changes nothing")
    }

    @Test
    fun unblockingABlockedRequestFromTheListMakesItARequestAgain() = runTest {
        val service = service()
        val request = service.requests.value.first { it.id == RequestId("kuzey_dev") }
        service.block(request)
        val person = service.blockedPeople.value.first { it.id.value == "kuzey_dev" }
        assertTrue(service.unblock(person))
        assertTrue(service.blockedPeople.value.isEmpty())
        assertNull(service.chat(ChatId("kuzey_dev")).value)
        assertEquals(RequestId("kuzey_dev"), service.requests.value.first().id)
    }

    @Test
    fun writingToSomeoneBlockedWithoutAChatBringsTheBlockedChatBack() = runTest {
        val service = service()
        service.loadBlockedPeople()
        assertEquals(lale, service.startChat("lale"))
        assertTrue(checkNotNull(service.chat(lale).value).peer.isBlocked)
        assertEquals(listOf("bora", "lale"), service.ids(), "listed once, now with its chat")
        assertTrue(service.unblock(lale))
        assertEquals(true, service.chat(lale).value?.peer?.isAcceptedContact)
        assertEquals(listOf("bora"), service.ids())
    }

    @Test
    fun erasingEverythingForgetsTheBlocks() = runTest {
        val service = service()
        service.loadBlockedPeople()
        service.eraseEverything()
        assertTrue(service.blockedPeople.value.isEmpty())
    }

    @Test
    fun theListIsByNameWithUnknownNamesLast() {
        fun person(id: String, name: String, known: Boolean = true) =
            BlockedPerson(ChatId(id), name, isUsernameKnown = known, palette = AvatarPalette.forUsername(name))
        val listed = BlockedPerson.listed(
            listOf(person("c", "c-6f1c2a", known = false), person("b", "mert_k"), person("a", "ayla_"), person("d", "Kuzey_dev"), person("e", "user10"), person("f", "user9")),
        )
        assertEquals(listOf("ayla_", "Kuzey_dev", "mert_k", "user9", "user10", "c-6f1c2a"), listed.map { it.name })
    }
}
