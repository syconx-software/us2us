package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Block and unblock in the fixtures, and who a report names (iOS `FixtureBlockingTests`). */
class FixturesBlockingTest {
    private fun TestScope.service(scope: CoroutineScope = backgroundScope) = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignFixtures.referenceDate),
        latency = Duration.ZERO,
        scope = scope,
    )

    private val mert = ChatId("mert_k")

    @Test
    fun blockingAContactMakesTheChatReadOnlyAndUnblockingRestoresIt() = runTest {
        val service = service()
        val before = checkNotNull(service.chat(mert).value).peer
        assertTrue(before.isOnline)
        assertTrue(service.block(mert))
        val blocked = checkNotNull(service.chat(mert).value).peer
        assertTrue(blocked.isBlocked && !blocked.isAcceptedContact && !blocked.isOnline)
        assertEquals(AvatarPicture.Initials, blocked.avatar)
        assertTrue(service.peer(mert).value?.isBlocked == true, "the peer flow follows")
        // Nothing goes to someone blocked.
        val count = service.messages(mert).value.size
        service.sendText("Selam", null, mert)
        assertEquals(count, service.messages(mert).value.size)
        assertTrue(service.unblock(mert))
        assertEquals(before, service.chat(mert).value?.peer)
        service.sendText("Selam", null, mert)
        assertEquals(count + 1, service.messages(mert).value.size)
    }

    @Test
    fun blockingTwiceKeepsThePeerFromBeforeTheFirstBlock() = runTest {
        val service = service()
        val before = checkNotNull(service.chat(mert).value).peer
        assertTrue(service.setBlocked(mert))
        assertTrue(service.block(mert), "already blocked counts as blocked")
        assertTrue(service.unblock(mert))
        assertEquals(before, service.chat(mert).value?.peer)
    }

    @Test
    fun unknownChatsAreNeitherBlockedNorUnblocked() = runTest {
        val service = service()
        assertFalse(service.block(ChatId("nobody")))
        assertFalse(service.setBlocked(ChatId("nobody")))
        assertFalse(service.unblock(ChatId("deniz")), "deniz is not blocked")
    }

    @Test
    fun aBlockedRequestStaysAsABlockedChatAndUnblockingMakesItARequestAgain() = runTest {
        val service = service()
        val request = checkNotNull(service.requests.value.firstOrNull { it.id == RequestId("kuzey_dev") })
        val id = ChatId("kuzey_dev")
        service.block(request)
        assertTrue(service.requests.value.none { it.id == request.id })
        val chat = checkNotNull(service.chat(id).value)
        assertTrue(chat.peer.isBlocked && !chat.peer.isAcceptedContact)
        assertEquals(0, chat.unreadCount)
        assertEquals(request.messages.size, service.messages(id).value.size, "its messages stay, read only")
        assertEquals(listOf("deniz", "mert_k"), service.chats.value.take(2).map { it.id.value }, "the design's order stays")
        // Blocking the same request again changes nothing.
        service.block(request)
        assertEquals(1, service.chats.value.count { it.id == id })
        assertTrue(service.unblock(id))
        assertNull(service.chat(id).value)
        assertTrue(service.messages(id).value.isEmpty())
        assertEquals(RequestId("kuzey_dev"), service.requests.value.firstOrNull()?.id, "back where it was")
        // A request again: blocking it from 4.3 works as the first time.
        service.block(service.requests.value.first())
        assertTrue(service.chat(id).value?.peer?.isBlocked == true)
    }

    @Test
    fun aReportNamesTheUsernameAndASuspiciousSenderIsUnconfirmed() = runTest {
        val service = service()
        assertEquals(ReportSubject("deniz", isUsernameVerified = true), service.reportSubject(ChatId("deniz")))
        assertNull(service.reportSubject(ChatId("nobody")))
        val suspicious = checkNotNull(service.requests.value.firstOrNull { MessageRequest.Flag.Suspicious in it.flags })
        assertEquals(ReportSubject(suspicious.sender, isUsernameVerified = false), service.reportSubject(suspicious))
        val plain = checkNotNull(service.requests.value.firstOrNull { it.id == RequestId("kuzey_dev") })
        assertEquals(true, service.reportSubject(plain).isUsernameVerified)
    }

    @Test
    fun eraseForgetsBlocks() = runTest {
        val service = service()
        val request = service.requests.value.first()
        service.block(request)
        service.eraseEverything()
        assertFalse(service.unblock(ChatId(request.id.value)))
        assertTrue(service.requests.value.isEmpty())
    }
}
