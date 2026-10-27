package com.syconx.us2us.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.domain.CommunityCard
import com.syconx.us2us.domain.CommunityApplication
import com.syconx.us2us.domain.CommunityChat
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.CommunityPage
import com.syconx.us2us.domain.CommunitySender
import com.syconx.us2us.domain.CommunitySnapshot
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.mapState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.nav.SoftNavigationStack
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 4.1 with communities among the chats: their rows, opening one, and the way to discover and create them from 4.2. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class CommunityChatsTest {
    @get:Rule
    val host = ShotHost()

    /** A hang fails the test instead of blocking the run. */
    @get:Rule
    val deadline = CommunitiesInteractionTest.Deadline(seconds = 120)

    private val fixtures = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), english = true, latency = Duration.ZERO)
    private val messenger = WithCommunity(fixtures)
    private val model = PreviewAppModel(messenger, clock = fixtures.clock)
    private val inboxService = InboxFixture()
    private val screens = object : com.syconx.us2us.ui.env.FeatureScreens by com.syconx.us2us.ui.env.FeatureScreens.None {
        @androidx.compose.runtime.Composable
        override fun CommunityManage(service: CommunitiesService, id: String, title: String, onClose: () -> Unit) {
            com.syconx.us2us.feature.conversation.CommunityManageHost(service, id, title, onClose, onClose)
        }
    }
    private val outerMotion = com.syconx.us2us.ui.nav.U2LayerMotion()
    private val community = CommunityChats.chatId("book-club")

    private fun open(withInbox: Boolean = false) {
        if (withInbox) messenger.communities = inboxService
        host.render(lang = "en") {
            U2PreviewRoot(appModel = remember { model }, dark = true) {
                CompositionLocalProvider(com.syconx.us2us.ui.nav.LocalLayerMotion provides outerMotion) {
                SoftNavigationStack(
                    router = model.router,
                    root = { CompositionLocalProvider(com.syconx.us2us.ui.env.LocalFeatureScreens provides screens) { ChatsScreen() } },
                    destination = { Box(Modifier.fillMaxSize().background(U2Theme.colors.bg).testTag(OTHER_SCREEN_TAG)) },
                )
                }
            }
        }
        host.advance(800)
    }

    private fun exists(text: String) = host.compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = host.compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun aCommunityRowShowsItsNameSenderPreviewAndUnreadAndOpensLikeAChat() {
        open()
        assertTrue(tagged(ChatsTags.chat(community)))
        assertTrue(exists("Book club"))
        assertTrue("who wrote the newest message", exists("@deniz: See you at seven"))
        assertTrue("its unread count", exists("4"))
        // Mixed with the chats by last activity: it is above every chat it is newer than.
        val list = messenger.chats.value
        assertEquals(community, list.first().id)
        host.compose.onNodeWithTag(ChatsTags.chat(community)).performClick()
        host.advance(1_000)
        assertEquals(AppRoute.Conversation(community), model.router.top)
    }

    @Test fun theNewChatSheetLeadsToTheCommunities() {
        open()
        host.compose.onNodeWithTag(ChatsTags.NEW).performClick()
        host.advance(1_200)
        assertTrue(tagged(NewChatTags.COMMUNITIES))
        host.compose.onNodeWithTag(NewChatTags.COMMUNITIES).performClick()
        host.advance(2_000)
        assertTrue("the sheet goes down and the communities rise", tagged(CommunityTags.SCREEN))
        assertTrue(exists("Discover public communities"))
    }

    @Test fun ownerRequestsAreAboveChatsAndOpenRealApproveRejectControls() {
        open(withInbox = true)
        val card = host.compose.onNodeWithTag("chats.communityRequests.owned")
        card.assertExists()
        assertTrue(card.fetchSemanticsNode().boundsInRoot.top < host.compose.onNodeWithTag(ChatsTags.chat(community)).fetchSemanticsNode().boundsInRoot.top)
        assertEquals(listOf("owned"), inboxService.queried.distinct())
        card.performClick(); host.advance(1_000)
        host.compose.onNodeWithTag("communities.request.alice").assertExists()
        host.compose.onAllNodes(hasText("Reject") and androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        host.advance(1_000)
        assertEquals("alice", inboxService.rejected)
        host.compose.onAllNodes(hasText("Approve") and androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        host.advance(1_000)
        assertEquals("bob", inboxService.approved)
        host.compose.onNodeWithContentDescription("Close").performClick(); host.advance(1_000)
        assertTrue(!tagged("chats.communityRequests.owned"))
        assertTrue(tagged(ChatsTags.chat(community)))
    }

    @Test fun lockingClearsInboxAndSheetAndUnlockLoadsNewRequests() {
        open(withInbox = true)
        host.compose.onNodeWithTag("chats.communityRequests.owned").performClick(); host.advance(1_000)
        inboxService.unlocked.value = false; host.advance(1_000)
        assertTrue(!tagged("chats.communityRequests.owned"))
        assertTrue(!tagged("communities.manageSheet"))
        val count = inboxService.queried.size
        host.advance(30_000)
        assertEquals(count, inboxService.queried.size)
        inboxService.unlocked.value = true; host.advance(1_000)
        assertTrue(tagged("chats.communityRequests.owned"))
        assertTrue(!tagged("communities.manageSheet"))
    }

    @Test fun inboxOutageKeepsCountAndZeroRequestsRemovesCard() {
        open(withInbox = true)
        inboxService.failApplications = true; host.advance(10_100)
        assertTrue(tagged("chats.communityRequests.owned"))
        inboxService.failApplications = false; inboxService.pending.clear(); host.advance(10_100)
        assertTrue(!tagged("chats.communityRequests.owned"))
        assertTrue(tagged(ChatsTags.chat(community)))
    }

    @Test fun cancelledFetchCannotRestoreRowsAfterLock() {
        open(withInbox = true)
        inboxService.delayed = kotlinx.coroutines.CompletableDeferred()
        host.advance(10_100)
        val waiting = inboxService.delayed!!
        inboxService.unlocked.value = false; host.advance(1_000)
        inboxService.pending.clear()
        inboxService.delayed = null
        inboxService.unlocked.value = true; host.advance(1_000)
        waiting.complete(listOf(CommunityApplication("stale", "key", 1)))
        host.advance(1_000)
        assertTrue(!tagged("chats.communityRequests.owned"))
    }

    @Test fun backgroundStopsPollingAndForegroundLoadsAgain() {
        open(withInbox = true)
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single() as androidx.activity.ComponentActivity
        val lifecycle = activity.lifecycle as androidx.lifecycle.LifecycleRegistry
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        host.advance(1_000)
        val calls = inboxService.queried.size
        host.advance(30_000)
        assertEquals(calls, inboxService.queried.size)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        host.advance(1_000)
        assertTrue(inboxService.queried.size > calls)
    }

    @Test fun cancelledOperationDoesNotStopAnUnlockedInboxPoll() {
        open(withInbox = true)
        inboxService.cancelNext = true; host.advance(10_100)
        val calls = inboxService.queried.size
        host.advance(10_100)
        assertTrue(inboxService.queried.size > calls)
        assertTrue(tagged("chats.communityRequests.owned"))
    }

    @Test fun coveredChatsStopPollingAndReturningRefreshesTheInbox() {
        open(withInbox = true)
        host.compose.onNodeWithTag(ChatsTags.chat(community)).performClick(); host.advance(1_000)
        val calls = inboxService.queried.size
        host.advance(30_000)
        assertEquals("the covered root does no owner API work", calls, inboxService.queried.size)
        model.router.pop(); repeat(16) { host.advance(32) }
        assertTrue(inboxService.queried.size > calls)
        assertTrue(tagged("chats.communityRequests.owned"))
    }

    @Test fun inboxWaitsUntilTheNavigationHasSettled() {
        open(withInbox = true)
        outerMotion.updateMoving(true); repeat(4) { host.advance(32) }
        val calls = inboxService.queried.size
        host.advance(30_000)
        assertEquals("no refresh competes with a moving stack", calls, inboxService.queried.size)
        outerMotion.updateMoving(false); repeat(4) { host.advance(32) }
        assertTrue(inboxService.queried.size > calls)
        assertTrue(tagged("chats.communityRequests.owned"))
    }

    private class InboxFixture : CommunitiesService {
        override val unlocked = MutableStateFlow(true)
        val queried = mutableListOf<String>()
        val pending = mutableListOf(CommunityApplication("alice", "key", 1, "05ab cdef"), CommunityApplication("bob", "key", 2, "05ba dcfe"))
        var approved: String? = null
        var rejected: String? = null
        var cancelNext = false
        var failApplications = false
        var delayed: kotlinx.coroutines.CompletableDeferred<List<CommunityApplication>>? = null
        override suspend fun refresh() = CommunitySnapshot(listOf(
            CommunityCard("owned", "Owned community", false, 1, "me", "key", 1, active = true, isOwner = true),
            CommunityCard("member", "Joined community", true, 2, "other", "key", 1, active = true),
            CommunityCard("ended", "Ended community", true, 1, "me", "key", 1, isOwner = true)), true)
        override suspend fun applications(id: String): List<CommunityApplication> { queried += id; if (cancelNext) { cancelNext = false; throw kotlinx.coroutines.CancellationException("operation cancelled") }; if (failApplications) error("offline"); delayed?.let { return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { it.await() } }; return pending.toList() }
        override suspend fun approve(id: String, application: CommunityApplication) { approved = application.account; pending.removeAll { it.account == application.account } }
        override suspend fun reject(id: String, account: String) { rejected = account; pending.removeAll { it.account == account } }
        override suspend fun search(name: String, after: String?) = CommunityPage(emptyList(), null)
        override suspend fun create(name: String, public: Boolean) = "new"
        override suspend fun request(group: CommunityCard) = Unit
        override suspend fun redeem(code: String) = Unit
        override suspend fun remove(id: String, account: String) = Unit
        override suspend fun invite(id: String) = ""
        override suspend fun revokeInvites(id: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun leave(id: String) = Unit
        override suspend fun withdraw(id: String) = Unit
        override suspend fun forget(id: String) = Unit
    }

    /** The fixtures with one community at the top of the chat list. */
    private class WithCommunity(private val base: FixturesMessengerService) : MessengerService by base {
        private val row = ChatSummary(
            id = CommunityChats.chatId("book-club"),
            peer = Peer("Book club", AvatarPalette.C, isVerified = false, isAcceptedContact = false, presence = Presence.Hidden),
            lastMessage = MessagePreview(
                MessagePreviewKind.Text("See you at seven"), MessageDirection.Incoming, null,
                DesignClock.referenceDate.plusSeconds(60), sender = CommunitySender("4c3b2a19-8f7e-4d6c-9b5a-0e1f2a3b4c5d", "deniz"),
            ),
            unreadCount = 4,
            ghost = GhostState.Off,
            disappearing = DisappearingTimer.Off,
            community = CommunityChat("book-club", "Book club", isPublic = true, memberCount = 5, isOwner = false, isActive = true),
        )
        override val chats: StateFlow<List<ChatSummary>> = base.chats.mapState { listOf(row) + it }
        override var communities: CommunitiesService = object : CommunitiesService {
            override val unlocked = MutableStateFlow(true)
            override suspend fun refresh() = CommunitySnapshot(emptyList(), true)
            override suspend fun search(name: String, after: String?) = CommunityPage(emptyList(), null)
            override suspend fun create(name: String, public: Boolean) = "new"
            override suspend fun request(group: CommunityCard) = Unit
            override suspend fun redeem(code: String) = Unit
            override suspend fun applications(id: String) = emptyList<CommunityApplication>()
            override suspend fun approve(id: String, application: CommunityApplication) = Unit
            override suspend fun reject(id: String, account: String) = Unit
            override suspend fun remove(id: String, account: String) = Unit
            override suspend fun invite(id: String) = ""
            override suspend fun revokeInvites(id: String) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun leave(id: String) = Unit
            override suspend fun withdraw(id: String) = Unit
            override suspend fun forget(id: String) = Unit
        }
    }
}
