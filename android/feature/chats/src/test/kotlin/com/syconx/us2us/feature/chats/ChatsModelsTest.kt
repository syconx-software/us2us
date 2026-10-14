package com.syconx.us2us.feature.chats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.OwnAvatarState
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.feature.chats.NewChatModel.Phase
import com.syconx.us2us.feature.chats.NewChatModel.ResultAvatar
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 4.2's exact lookup, 4.3's triage, request content and ages (iOS NewChatModelTests, NewChatNormalizationTests, AvatarUITests, RequestContentTests). */
class NewChatModelTest {
    private fun service(scope: CoroutineScope) = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        latency = Duration.ZERO,
        scope = scope,
    )

    private fun TestScope.model(avatarSettle: Duration = 10.milliseconds, service: FixturesMessengerService = service(backgroundScope)) =
        NewChatModel(service, backgroundScope, debounce = 10.milliseconds, avatarSettle = avatarSettle)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.settle(time: Duration = 200.milliseconds) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun exactLookupAfterThePause() = runTest {
        val model = model()
        model.query = "de"
        assertEquals("still too short: nothing is sent", Phase.Idle, model.phase)
        model.query = "mert_k"
        assertEquals(Phase.Pending, model.phase)
        settle()
        val found = model.phase as Phase.Found
        assertEquals("mert_k", found.peer.username)
        assertTrue(found.isExistingChat)
    }

    @Test
    fun ownNameAndUnknownNames() = runTest {
        val model = model()
        model.query = "selin_n"
        assertEquals(Phase.OwnName, model.phase)
        model.query = "deniz_yilmaz"
        settle()
        assertEquals(Phase.NotFound, model.phase)
    }

    @Test
    fun normalizesWithoutRewritingTheField() = runTest {
        val model = model()
        model.query = " @Mert_K"
        assertEquals("the field is never rewritten", " @Mert_K", model.query)
        settle()
        assertEquals("mert_k", (model.phase as Phase.Found).peer.username)
    }

    @Test
    fun invalidNamesNameTheirRule() = runTest {
        val cases = listOf(
            "ayşe" to UsernameRules.Violation.TurkishCharacters,
            "selin.n" to UsernameRules.Violation.Characters,
            "Ali Veli" to UsernameRules.Violation.Characters,
            "1abc" to UsernameRules.Violation.FirstLetter,
            "a".repeat(25) to UsernameRules.Violation.Length,
            "@@deniz" to UsernameRules.Violation.Characters,
        )
        for ((query, violation) in cases) {
            val model = model()
            model.query = query
            assertEquals(query, Phase.Pending, model.phase)
            settle()
            assertEquals(query, Phase.Invalid(violation), model.phase)
            assertEquals(query, model.query)
            assertEquals(UsernameRules.normalize(query), model.resultName)
            assertEquals(ResultAvatar.NoResult, model.resultAvatar)
        }
    }

    @Test
    fun aStrangersPictureIsFetchedAfterTheHit() = runTest {
        val model = model()
        model.query = "lale"
        assertEquals("nothing is asked while typing", ResultAvatar.NoResult, model.resultAvatar)
        settle()
        assertEquals(ResultAvatar.Loaded(AvatarPicture.preset(14)), model.resultAvatar)
        assertEquals(AvatarPicture.preset(14), model.resultAvatar.picture)
    }

    @Test
    fun aPauseFindsTheResultButFetchesNoPictureUntilItSettles() = runTest {
        val model = model(avatarSettle = 30.seconds)
        model.query = "lale"
        settle()
        assertTrue(model.phase is Phase.Found)
        settle(100.milliseconds)
        assertEquals("the initial and the ring, nothing fetched yet", ResultAvatar.Loading, model.resultAvatar)
        model.query = "lale_x"
        assertEquals(ResultAvatar.NoResult, model.resultAvatar)
        // Return does not wait.
        model.query = "lale"
        model.submit()
        settle(50.milliseconds)
        assertEquals(ResultAvatar.Loaded(AvatarPicture.preset(14)), model.resultAvatar)
    }

    @Test
    fun editingTheNameDropsTheResultsPicture() = runTest {
        val model = model()
        model.query = "deniz"
        settle()
        assertEquals(ResultAvatar.Loaded(AvatarPicture.Initials), model.resultAvatar)
        model.query = "deniz_k"
        assertEquals(ResultAvatar.NoResult, model.resultAvatar)
    }

    @Test
    fun knownPicturesAreThereAtOnce() = runTest {
        val service = service(backgroundScope)
        val model = model(service = service)
        model.query = "oya_t"
        settle()
        assertEquals(DesignAvatars.photoAvatar, model.resultAvatar.picture)
        service.setOwnAvatar(OwnAvatarState(picture = AvatarPicture.preset(9)))
        model.query = "selin_n"
        assertEquals(Phase.OwnName, model.phase)
        assertEquals(ResultAvatar.Loaded(AvatarPicture.preset(9)), model.resultAvatar)
        assertEquals(ResultAvatar.NoResult, NewChatModel.resultAvatar(Phase.Searching, AvatarPicture.preset(9)))
        assertEquals(ResultAvatar.NoResult, NewChatModel.resultAvatar(Phase.NotFound, AvatarPicture.preset(9)))
    }

    @Test
    fun searchingCanBeHeldForTheHarness() = runTest {
        val model = model()
        model.debug = NewChatModel.DebugOptions(freezesSearching = true)
        model.query = "deniz"
        settle(5.seconds)
        assertEquals(Phase.Searching, model.phase)
    }

    @Test
    fun startChatOpensTheExistingChatOrANewOne() = runTest {
        val service = service(backgroundScope)
        val model = model(service = service)
        model.query = "mert_k"
        settle()
        assertEquals(ChatId("mert_k"), model.startChat((model.phase as Phase.Found).peer))
        model.query = "deniz"
        settle()
        val deniz = (model.phase as Phase.Found).peer
        // deniz is in the list already (the design's 4.1): the same chat.
        assertEquals(ChatId("deniz"), model.startChat(deniz))
        assertFalse(model.isStarting)
    }
}

class RequestTriageModelTest {
    private fun service() = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)

    @Test
    fun removalsAreOptimisticAndAcceptOpensTheChat() = runTest {
        val service = service()
        val triage = RequestTriageModel()
        val all = service.requests.value
        assertEquals(listOf("kuzey_dev", "n_aksoy", "hediye_kazan"), all.map { it.id.value })
        triage.delete(all[1], service)
        assertEquals(listOf("kuzey_dev", "hediye_kazan"), triage.requests(all).map { it.id.value })
        assertFalse(service.requests.value.any { it.id == RequestId("n_aksoy") })
        triage.block(all[2], service)
        assertEquals(listOf("kuzey_dev"), triage.requests(service.requests.value).map { it.id.value })
        val chat = triage.accept(all[0], service)
        assertEquals(ChatId("kuzey_dev"), chat)
        assertTrue(triage.requests(service.requests.value).isEmpty())
        assertEquals(ChatId("kuzey_dev"), service.chats.value.first().id)
    }

    @Test
    fun deleteAllHidesEveryCardAtOnce() = runTest {
        val service = service()
        val triage = RequestTriageModel()
        triage.deleteAll(service.requests.value, service)
        assertTrue(service.requests.value.isEmpty())
        assertEquals(3, triage.decided.size)
    }

    @Test
    fun leavingCardsKeepTheirPlace() {
        val all = service().requests.value
        val kept = listOf(all[0], all[2])
        assertEquals(all.map { it.id }, mergeLeaving(all, kept).map { it.id })
        assertEquals(all.map { it.id }, mergeLeaving(all, emptyList()).map { it.id })
        // A card gone from the top stays at the top until it has collapsed.
        assertEquals(listOf(all[0].id, all[2].id), mergeLeaving(listOf(all[0]), listOf(all[2])).map { it.id })
    }
}

/** Request content and ages (they need the app's resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RequestContentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Europe/Istanbul")

    @Test
    fun linksAreRecognisedLocally() {
        assertEquals(RequestContent.TextKind.Link, RequestContent.classify("slides.kuzey.dev/meetup"))
        assertEquals(RequestContent.TextKind.ContainsLink, RequestContent.classify("Ödülünü almak için tıkla: bit.ly/3xQ"))
        assertEquals(RequestContent.TextKind.Plain, RequestContent.classify("Merhaba! Nasılsın?"))
        assertTrue(RequestContent.isMedia(MessageContent.TextSticker("tamam!")))
        assertFalse(RequestContent.isMedia(MessageContent.Text("selam")))
    }

    @Test
    fun requestAges() {
        val tr = U2Strings.of(context, Locale.forLanguageTag("tr-TR"))
        val en = U2Strings.of(context, Locale.forLanguageTag("en-US"))
        val now = DesignClock.referenceDate
        fun ago(d: Duration) = now.minus(d.toJavaDuration())
        assertEquals("2 sa", ChatsFormat.requestAge(ago(2.hours), now, tr, zone))
        assertEquals("3 gün", ChatsFormat.requestAge(ago(72.hours), now, tr, zone))
        assertEquals("Dün", ChatsFormat.requestAge(ago(26.hours), now, tr, zone))
        assertEquals("5 dk", ChatsFormat.requestAge(ago(5.minutes), now, tr, zone))
        assertEquals("1 dk", ChatsFormat.requestAge(ago(10.seconds), now, tr, zone))
        assertEquals("Yesterday", ChatsFormat.requestAge(ago(26.hours), now, en, zone))
        assertEquals("Bugün 16:12", ChatsFormat.separator(now.minus(java.time.Duration.ofMinutes(160)), now, tr, zone))
    }
}
