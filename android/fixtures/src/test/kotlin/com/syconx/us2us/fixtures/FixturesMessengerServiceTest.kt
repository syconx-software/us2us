package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.AccountCreationFailure
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.SecretCodeError
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.UpcomingDelay
import com.syconx.us2us.domain.UsernameAvailability
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** The fixtures service against the iOS `DesignFixturesServiceTests`, plus the Android-only rules. */
class FixturesMessengerServiceTest {
    private val wall = Instant.parse("2031-05-01T10:00:00Z")
    private var bootReading: Duration = 1.hours

    private fun TestScope.service(hasAccount: Boolean = true, scope: CoroutineScope = backgroundScope) = FixturesMessengerService(
        hasAccount = hasAccount,
        clock = DesignClock.frozen(DesignFixtures.referenceDate),
        latency = Duration.ZERO,
        scope = scope,
        bootClock = { bootReading },
        wallClock = Clock.fixed(wall, ZoneOffset.UTC),
    )

    private val deniz = ChatId("deniz")
    private val kaan = ChatId("kaan")

    @Test
    fun `serves the design's chat list`() = runTest {
        val service = service()
        val chats = service.chats.value
        assertEquals(listOf("deniz", "mert_k", "ayla_", "kaan", "ece_y", "bora", "oya_t"), chats.map { it.peer.username })
        assertEquals(listOf(AvatarPalette.A, AvatarPalette.B, AvatarPalette.C, AvatarPalette.D, AvatarPalette.A, AvatarPalette.B, AvatarPalette.C), chats.map { it.peer.palette })
        assertTrue(chats[0].peer.isVerified)
        assertTrue(chats[1].peer.isOnline)
        assertEquals(2, chats[1].unreadCount)
        assertEquals(23.hours + 41.minutes + 7.seconds, chats[0].ghost.remaining(DesignFixtures.referenceDate))
        assertEquals(MessageStatus.Failed, chats[4].lastMessage?.status)
        assertEquals(7, service.messages(deniz).value.size)
        assertEquals("selin_n", service.account.value?.username)
        assertEquals("selin_n", service.ownUsername.value)
        assertEquals(3, service.requests.value.size)
        assertEquals(ConnectionState.Open, service.connection.value)
        assertEquals(chats[0], service.chat(deniz).value)
        assertEquals(chats[0].peer, service.peer(deniz).value)
        assertNull(service.chat(ChatId("nobody")).value)
    }

    @Test
    fun `unlock follows the protocol delays, counted on the boot clock`() = runTest {
        val service = service()
        // The first two wrong codes are free; the copy counts what is left.
        val first = service.unlock("111112") as UnlockAttemptResult.WrongCode
        assertEquals(UpcomingDelay(2, 1.minutes), first.status.upcoming)
        assertNull(first.status.nextAttemptAllowedAt)
        val second = service.unlock("111113") as UnlockAttemptResult.WrongCode
        assertEquals(1, second.status.upcoming?.attemptsLeft)
        // The third costs a minute.
        val third = service.unlock("111114") as UnlockAttemptResult.WrongCode
        assertEquals(wall.plusSeconds(60), third.status.nextAttemptAllowedAt)
        assertEquals(UpcomingDelay(1, 5.minutes), third.status.upcoming)
        assertEquals(3, service.unlockStatus.value.failures)
        assertEquals(UnlockAttemptResult.Throttled(wall.plusSeconds(60)), service.unlock(DesignFixtures.DEMO_SECRET_CODE))
        bootReading += 30.seconds
        assertEquals(UnlockAttemptResult.Throttled(wall.plusSeconds(30)), service.unlock(DesignFixtures.DEMO_SECRET_CODE))
        assertEquals(UnlockAttemptResult.Malformed, service.unlock("12a"))
        bootReading += 30.seconds
        // The fourth wrong code costs five minutes.
        val fourth = service.unlock("111115") as UnlockAttemptResult.WrongCode
        assertEquals(wall.plusSeconds(300), fourth.status.nextAttemptAllowedAt)
        bootReading += 5.minutes
        assertEquals(UnlockAttemptResult.Unlocked, service.unlock(DesignFixtures.DEMO_SECRET_CODE))
        assertEquals(0, service.unlockStatus.value.failures)
    }

    @Test
    fun `a restarted phone does not shorten the wait`() = runTest {
        val service = service()
        repeat(3) { service.unlock("11111${it + 2}") }
        bootReading += 50.seconds
        // The phone restarted: the boot clock starts again from a small value, and only the time
        // since boot counts (the 50 s before the restart were never seen).
        bootReading = 5.seconds
        val result = service.unlock(DesignFixtures.DEMO_SECRET_CODE)
        assertEquals(UnlockAttemptResult.Throttled(wall.plusSeconds(55)), result)
    }

    @Test
    fun `the optional wipe erases everything on the tenth wrong code`() = runTest {
        val service = service()
        service.setWrongCodes(9)
        assertNull(service.unlockStatus.value.nextAttemptAllowedAt)
        service.wipesAfterTenWrongCodes = true
        assertEquals(UnlockAttemptResult.Wiped, service.unlock("111112"))
        assertNull(service.account.value)
        assertTrue(service.chats.value.isEmpty())
        assertEquals(0, service.unlockStatus.value.failures)
    }

    @Test
    fun `forced unlock answers are used once`() = runTest {
        val service = service()
        service.nextUnlockResult = UnlockAttemptResult.VaultUnusable
        assertEquals(UnlockAttemptResult.VaultUnusable, service.unlock("111112"))
        assertEquals(UnlockAttemptResult.Unlocked, service.unlock(DesignFixtures.DEMO_SECRET_CODE))
    }

    @Test
    fun `right code unlocks and resets`() = runTest {
        val service = service()
        service.unlock("111112")
        assertEquals(UnlockAttemptResult.Unlocked, service.unlock(DesignFixtures.DEMO_SECRET_CODE))
        assertEquals(0, service.unlockStatus.value.failures)
        assertEquals(UnlockAttemptResult.Unlocked, service.verifySecretCode(DesignFixtures.DEMO_SECRET_CODE))
    }

    @Test
    fun `secret code rules come from the core`() = runTest {
        val service = service()
        assertEquals(SecretCodeError.Sequence, service.secretCodeProblem("123456"))
        assertEquals(SecretCodeError.RepeatedDigit, service.secretCodeProblem("000000"))
        assertEquals(SecretCodeError.NotSixDigits, service.secretCodeProblem("12345"))
        assertEquals(SecretCodeError.TooCommon, service.secretCodeProblem("121212"))
        assertNull(service.secretCodeProblem(DesignFixtures.DEMO_SECRET_CODE))
    }

    @Test
    fun `onboarding creates the account`() = runTest {
        val service = service(hasAccount = false)
        assertNull(service.account.value)
        assertFalse(service.hasAccount.value)
        assertEquals(UsernameAvailability.Available, service.usernameAvailability("selin_n"))
        assertEquals(UsernameAvailability.Taken, service.usernameAvailability("deniz"))
        assertEquals(UsernameAvailability.Invalid(UsernameRules.Violation.Length), service.usernameAvailability("ay"))
        assertEquals(UsernameAvailability.Invalid(UsernameRules.Violation.Characters), service.usernameAvailability("selin.n"))
        assertEquals(UsernameAvailability.Empty, service.usernameAvailability(""))
        // Meets the rules, then turns out taken at setup (the relay's 409).
        assertEquals(UsernameAvailability.MeetsRules, service.usernameAvailability(DesignFixtures.TAKEN_AT_SETUP_USERNAME))
        try {
            service.createAccount(DesignFixtures.TAKEN_AT_SETUP_USERNAME, "975318", faceIdEnabled = false)
            fail("the name is taken")
        } catch (error: MessengerException.AccountCreation) {
            assertEquals(AccountCreationFailure.UsernameTaken, error.failure)
        }
        assertNull(service.account.value)
        service.createAccount("selin_n", "975318", faceIdEnabled = false)
        assertEquals("selin_n", service.account.value?.username)
        assertFalse(service.settings.value.faceIdEnabled)
        assertEquals(UnlockAttemptResult.Unlocked, service.unlock("975318"))
    }

    @Test
    fun `a planned setup failure is thrown once`() = runTest {
        val service = service(hasAccount = false)
        service.nextSetupFailure = MessengerException.SetupInterrupted(com.syconx.us2us.domain.SetupInterruption.AccountKept)
        try {
            service.createAccount("selin_n", "975318", faceIdEnabled = true)
            fail("planned failure")
        } catch (_: MessengerException.SetupInterrupted) {
        }
        service.createAccount("selin_n", "975318", faceIdEnabled = true)
        assertEquals("selin_n", service.account.value?.username)
    }

    @Test
    fun `sending restarts Ghost and moves the chat up`() = runTest {
        val service = service()
        service.sendText("  Selam  ", replyTo = null, chat = kaan)
        val top = service.chats.value.first()
        assertEquals(kaan, top.id)
        assertEquals(GhostPeriod.SevenDays.duration, top.ghost.remaining(DesignFixtures.referenceDate))
        assertEquals(MessagePreviewKind.Text("Selam"), top.lastMessage?.kind)
        val sent = service.messages(kaan).value.last()
        assertEquals(MessageStatus.Sent, sent.status)
        assertEquals(MessageContent.Text("Selam"), sent.content)
        assertTrue(sent.id.value.startsWith("out-1-"))
        service.sendText("   ", replyTo = null, chat = kaan)
        assertEquals(1, service.messages(kaan).value.size)
    }

    @Test
    fun `disappearing chats preview as disappearing`() = runTest {
        val service = service()
        service.updateGhost(GhostState.Off, DisappearingTimer.FiveMinutes, kaan)
        service.sendText("Kapı kodu", null, kaan)
        assertEquals(MessagePreviewKind.Disappearing, service.chats.value.first().lastMessage?.kind)
        assertEquals(DisappearingTimer.FiveMinutes, service.messages(kaan).value.last().disappearing)
    }

    @Test
    fun `ghost changes post system lines`() = runTest {
        val service = service()
        val before = service.messages(deniz).value.size
        service.updateGhost(GhostState(isEnabled = true, period = GhostPeriod.OneHour, deadline = null), DisappearingTimer.FiveMinutes, deniz)
        val added = service.messages(deniz).value.drop(before).map { it.content }
        assertEquals(
            listOf(MessageContent.System(SystemEvent.GhostEnabled(GhostPeriod.OneHour)), MessageContent.System(SystemEvent.DisappearingChanged(DisappearingTimer.FiveMinutes))),
            added,
        )
        assertEquals(1.hours, service.chat(deniz).value?.ghost?.remaining(DesignFixtures.referenceDate))
    }

    @Test
    fun `requests and verification`() = runTest {
        val service = service()
        val request = service.requests.value[0]
        assertEquals(ChatId("kuzey_dev"), service.accept(request))
        assertEquals(2, service.requests.value.size)
        assertEquals(ChatId("kuzey_dev"), service.chats.value.first().id)
        assertTrue(service.chats.value.first().peer.isAcceptedContact)
        assertEquals(3, service.messages(ChatId("kuzey_dev")).value.size)
        service.markVerified(deniz, VerificationMethod.FaceToFace)
        val info = service.safetyNumber(deniz)
        assertEquals(DesignFixtures.safetyNumberGroups, info?.groups)
        assertEquals(VerificationMethod.FaceToFace, info?.method)
        service.delete(service.requests.value.first { it.id == RequestId("n_aksoy") })
        service.block(service.requests.value.first())
        assertTrue(service.requests.value.isEmpty())
    }

    @Test
    fun `erase everything`() = runTest {
        val service = service()
        service.eraseEverything()
        assertNull(service.account.value)
        assertTrue(service.chats.value.isEmpty())
        assertTrue(service.requests.value.isEmpty())
        assertTrue(service.messages(deniz).value.isEmpty())
        assertTrue(service.recentStickers().isEmpty())
    }

    @Test
    fun `restoring brings the design back with the new code`() = runTest {
        val service = service()
        service.eraseEverything()
        service.completeRestore("975318", faceIdEnabled = false)
        assertEquals("selin_n", service.account.value?.username)
        assertEquals(7, service.chats.value.size)
        assertEquals(7, service.messages(deniz).value.size)
        assertEquals(UnlockAttemptResult.Unlocked, service.unlock("975318"))
    }

    @Test
    fun `lookup finds exact names only`() = runTest {
        val service = service()
        val deniz = service.lookup("@Deniz") as com.syconx.us2us.domain.UserLookup.Found
        assertFalse(deniz.isExistingChat)
        assertFalse(deniz.peer.isAcceptedContact)
        assertTrue((service.lookup("mert_k") as com.syconx.us2us.domain.UserLookup.Found).isExistingChat)
        val lale = service.lookup("lale") as com.syconx.us2us.domain.UserLookup.Found
        assertEquals(AvatarPalette.forUsername("lale"), lale.peer.palette)
        assertEquals(com.syconx.us2us.domain.UserLookup.NotFound, service.lookup("deniz_yilmaz"))
        assertEquals(com.syconx.us2us.domain.UserLookup.NotFound, service.lookup("selin.n"))
    }

    @Test
    fun `starting a chat opens an empty pending conversation`() = runTest {
        val service = service()
        assertEquals(ChatId("mert_k"), service.startChat("mert_k"))
        assertNull(service.startChat("selin_n"))
        assertNull(service.startChat("a"))
        assertEquals(ChatId("lale"), service.startChat("Lale"))
        val lale = service.chats.value.first()
        assertEquals(ChatId("lale"), lale.id)
        assertFalse(lale.peer.isAcceptedContact)
        assertEquals(14, lale.peer.avatar.presetId)
        assertTrue(service.messages(ChatId("lale")).value.isEmpty())
    }

    @Test
    fun `stickers are sent and go first in the recents`() = runTest {
        val service = service()
        assertEquals(listOf("6.-", "101.tr"), service.recentStickers())
        val hearYou = BrandStickerSelection(StickerCatalog.sticker(103)!!, StickerLanguage.English)
        service.sendSticker(hearYou, replyTo = null, chat = deniz)
        assertEquals(MessageContent.Sticker("us2us.words", 103, "🫂", "en"), service.messages(deniz).value.last().content)
        assertEquals(listOf("103.en", "6.-", "101.tr"), service.recentStickers())
        assertEquals(MessagePreviewKind.Sticker, service.chats.value.first().lastMessage?.kind)
    }

    @Test
    fun `reactions, deletes and the live location stop`() = runTest {
        val service = service()
        val d2 = MessageId("d2")
        service.setReaction("❤️", d2, deniz)
        assertEquals(2, service.messages(deniz).value.first { it.id == d2 }.reactions.single().count)
        service.setReaction("👍", d2, deniz)
        assertEquals(
            listOf(com.syconx.us2us.domain.Reaction("❤️", 1, false), com.syconx.us2us.domain.Reaction("👍", 1, true)),
            service.messages(deniz).value.first { it.id == d2 }.reactions,
        )
        service.setReaction(null, d2, deniz)
        assertEquals(listOf(com.syconx.us2us.domain.Reaction("❤️")), service.messages(deniz).value.first { it.id == d2 }.reactions)

        service.deleteForEveryone(d2, deniz)
        assertEquals(MessageContent.Deleted, service.messages(deniz).value.first { it.id == d2 }.content)
        service.deleteForMe(MessageId("d1"), deniz)
        assertTrue(service.messages(deniz).value.none { it.id == MessageId("d1") })

        val mert = ChatId("mert_k")
        service.stopLiveLocation(MessageId("m4"), mert)
        val live = service.messages(mert).value.first { it.id == MessageId("m4") }.content as MessageContent.LiveLocation
        assertFalse(live.isActive)
        assertEquals(DesignFixtures.referenceDate, live.expiresAt)
    }

    @Test
    fun `the harness's status and media knobs`() = runTest {
        val service = service()
        service.setStatus(MessageStatus.Failed, MessageId("d6"), deniz, DesignFixtures.referenceDate)
        val d6 = service.messages(deniz).value.first { it.id == MessageId("d6") }
        assertEquals(MessageStatus.Failed, d6.status)
        assertEquals(DesignFixtures.referenceDate, d6.failedAt)
        assertEquals(MessageStatus.Failed, service.chat(deniz).value?.lastMessage?.status)

        val mert = ChatId("mert_k")
        val file = service.addIncomingFile(mert, com.syconx.us2us.domain.MediaAvailability.Remote)
        assertEquals(MessageId("mert_k-file-in"), file)
        assertEquals(file, service.addIncomingFile(mert, com.syconx.us2us.domain.MediaAvailability.Unavailable))
        assertEquals(com.syconx.us2us.domain.MediaAvailability.Unavailable, service.messages(mert).value.last().mediaAvailability)
        val video = service.addOutgoingVideo(mert, failed = true)
        assertEquals(MessageStatus.Failed, service.messages(mert).value.first { it.id == video }.status)
        val voice = service.addIncomingVoice(deniz)
        assertEquals(9.seconds, (service.messages(deniz).value.first { it.id == voice }.content as MessageContent.Voice).duration)
        service.setSavedByPeer(MessageId("m1"), mert)
        assertTrue(service.messages(mert).value.first { it.id == MessageId("m1") }.savedByPeer)
        service.setConnection(ConnectionState.Closed)
        assertEquals(ConnectionState.Closed, service.connection.value)
        service.clearRequests()
        assertTrue(service.requests.value.isEmpty())
        service.setGhostUrgent(true)
        assertTrue(service.chats.value.first().ghost.isUrgent(DesignFixtures.referenceDate))
    }
}
