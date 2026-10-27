package com.syconx.us2us.feature.chats

import androidx.compose.ui.test.*
import com.syconx.us2us.domain.*
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.theme.U2Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose actions across the community service boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class CommunitiesInteractionTest {
    @get:Rule val host = ShotHost()
    /** A hang fails the test instead of blocking the run. */
    @get:Rule val deadline = Deadline(seconds = 120)
    private val service = CommunityFixture()
    private val opened = mutableListOf<String>()
    private val screen get() = host.compose.onNodeWithTag(CommunityTags.SCREEN)
    private fun open(lang: String = "en") { host.render(lang = lang) { U2Theme { CommunitiesScreen(service, onClose = {}, onOpen = { opened += it }) } }; host.advance(400) }
    private fun scroll(text: String) {
        screen.performScrollToNode(hasText(text) or hasContentDescription(text))
        host.advance(100)
    }
    private fun top() { screen.performScrollToIndex(0); host.advance(100) }
    private fun click(text: String) {
        scroll(text)
        tap(text)
    }
    /** A button outside the list (a sheet's). */
    private fun tap(text: String) {
        host.compose.onAllNodes(hasText(text) and hasClickAction()).onLast().performClick()
        host.advance(1_000)
    }

    private fun type(description: String, text: String) {
        host.compose.onNodeWithContentDescription(description).performTextInput(text)
        host.advance(400)
    }
    /** Confirms the open destructive step and lets it go. */
    private fun confirm() {
        host.compose.onNodeWithTag("communities.confirm").performClick()
        host.advance(1_000)
    }
    private fun exists(text: String) = host.compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun searchingWaitsForTwoCharactersAndJoiningIsDisclosedFirst() {
        open()
        type("Search public communities by name", "S")
        assertFalse("one character never reaches the relay", "S" in service.queries)
        type("Search public communities by name", "tudy")
        assertEquals("Study", service.queries.last())
        click("Request to join")
        assertNull("nothing is sent before the disclosure is confirmed", service.requested)
        assertTrue(exists("the relay sees who belongs to a community"))
        assertFalse("media is no longer “text only”", exists("Text only"))
        tap("Send request")
        assertEquals(service.publicGroup.id, service.requested)
        top()
        assertTrue(exists("Approval pending"))
    }

    @Test fun aJoinedCommunityOpensAsAChat() {
        service.groups = listOf(service.owned, service.ended)
        open()
        // The conversation and its management are the chat's own screen now, not part of this one.
        listOf("Join requests", "Create one-use invitation", "Delete community", "Leave community").forEach {
            assertFalse("$it is not on the directory", exists(it))
        }
        host.compose.onNodeWithTag(CommunityTags.group("owned")).performClick()
        host.advance(600)
        host.compose.onNodeWithTag(CommunityTags.group("ended")).performClick()
        host.advance(600)
        assertEquals(listOf("owned", "ended"), opened)
    }

    @Test fun aNewCommunityOpensAtOnce() {
        open()
        click("Create a community")
        host.compose.onNodeWithContentDescription("Community name").performTextInput("Kitap kulübü")
        host.advance(400)
        tap("Create")
        assertEquals("Kitap kulübü" to false, service.created)
        assertEquals(listOf("created-id"), opened)
    }

    @Test fun pendingRequestsAreWithdrawnAfterConfirming() {
        service.groups = listOf(service.pending)
        open()
        click("Withdraw request")
        assertNull(service.withdrawn)
        confirm()
        assertEquals("pending", service.withdrawn)
        assertTrue(opened.isEmpty())
    }

    @Test fun aRosterThatDoesNotVerifyIsShown() {
        service.warning = true
        open()
        host.compose.onNodeWithTag(CommunityTags.WARNING).assertExists()
    }

    @Test fun lockingClearsTheScreenAndUnlockingResumesPolling() {
        service.groups = listOf(service.owned)
        open()
        type("Search public communities by name", "Stu")
        service.failNext = true
        host.advance(10_100)
        assertTrue(tagged(CommunityTags.ERROR))

        service.unlocked.value = false
        host.advance(1_000)
        assertFalse(tagged(CommunityTags.ERROR))
        assertFalse("the list is gone", exists("Owned"))
        assertFalse("the query is gone", exists("Stu"))
        val whileLocked = service.refreshes
        host.advance(30_000)
        assertEquals("no polling while locked", whileLocked, service.refreshes)

        service.unlocked.value = true
        host.advance(400)
        assertTrue("polling resumes after the unlock", service.refreshes > whileLocked)
        assertTrue(exists("Owned"))
        assertFalse(tagged(CommunityTags.ERROR))
    }

    @Test fun aCancelledRequestDoesNotEndThePoll() {
        open()
        service.cancelNext = true
        host.advance(10_100)
        val afterCancel = service.refreshes
        host.advance(10_100)
        assertTrue("the poll goes on after a cancelled request", service.refreshes > afterCancel)
        assertFalse(tagged(CommunityTags.ERROR))
    }

    /** The directory, for review next to the iOS captures. */
    @Test fun directoryShots() {
        for (lang in listOf("tr", "en")) {
            service.groups = listOf(service.owned, service.pending)
            open(lang)
            host.capture("communities_directory")
        }
    }

    /** Interrupts the test thread once [seconds] have passed, so a test that never settles fails. */
    class Deadline(private val seconds: Long) : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer(true)
                timer.schedule(object : java.util.TimerTask() { override fun run() = thread.interrupt() }, seconds * 1_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
    }

    private class CommunityFixture : CommunitiesService {
        override val unlocked = MutableStateFlow(true)
        val identity = base64Url(KEY)
        val publicGroup = CommunityCard("public", "Study", true, 2, "owner", "key", 1)
        val owned = CommunityCard("owned", "Owned", false, 3, "me", "mykey", 1, true,
            listOf(CommunityMemberCard("me", isOwner = true, isSelf = true), CommunityMemberCard(DENIZ, "deniz"),
                CommunityMemberCard("0a1b2c3d-4e5f-4061-8293-a4b5c6d7e8f9")), true)
        val pending = CommunityCard("pending", "Waiting", true, 0, "", "key", 0, pending = true)
        val ended = CommunityCard("ended", "Ended", true, 2, "owner", "key", 3, false, listOf(CommunityMemberCard("me", isSelf = true)))
        val joined = CommunityCard("joined", "Joined", true, 2, "owner", "key", 3, true,
            listOf(CommunityMemberCard("owner", isOwner = true), CommunityMemberCard("me", isSelf = true)))
        var groups = emptyList<CommunityCard>()
        var warning = false
        val queries = mutableListOf<String>()
        val applicationList = mutableListOf(CommunityApplication(APPLICANT, identity, 1, CommunityFormat.fingerprint(KEY)))
        var requested: String? = null
        var approved: String? = null
        var removed: String? = null
        var withdrawn: String? = null
        var forgotten: String? = null
        var left: String? = null
        var created: Pair<String, Boolean>? = null
        var refreshes = 0
        var failNext = false
        var cancelNext = false
        override suspend fun refresh(): CommunitySnapshot {
            refreshes += 1
            if (cancelNext) { cancelNext = false; throw CancellationException("operation cancelled by a lock") }
            if (failNext) { failNext = false; error("relay unavailable") }
            return CommunitySnapshot(groups, true, warning)
        }
        override suspend fun search(name: String, after: String?): CommunityPage {
            queries += name
            return CommunityPage(if (name.isEmpty()) emptyList() else listOf(publicGroup), null)
        }
        override suspend fun request(group: CommunityCard) { requested = group.id; groups = groups + group.copy(count = 0, pending = true) }
        override suspend fun applications(id: String) = applicationList.toList()
        override suspend fun approve(id: String, application: CommunityApplication) {
            approved = application.account
            applicationList.removeAll { it.account == application.account }
        }
        override suspend fun create(name: String, public: Boolean): String { created = name to public; return "created-id" }
        override suspend fun redeem(code: String) = Unit
        override suspend fun reject(id: String, account: String) = Unit
        override suspend fun remove(id: String, account: String) { removed = account }
        override suspend fun invite(id: String) = "us2us-community:v1:invite"
        override suspend fun revokeInvites(id: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun leave(id: String) { left = id; groups = groups.filter { it.id != id } }
        override suspend fun withdraw(id: String) { withdrawn = id; groups = groups.filter { it.id != id } }
        override suspend fun forget(id: String) { forgotten = id; groups = groups.filter { it.id != id } }
    }

    private companion object {
        const val APPLICANT = "d5bb5a72-0301-484d-9aae-e17b80094f55"
        const val DENIZ = "4c3b2a19-8f7e-4d6c-9b5a-0e1f2a3b4c5d"
        val KEY: ByteArray = "05a77a12c630a9629a2b3971b478932d93a3f1d9aa4ae127966a9b1bc39d34467e"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun base64Url(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
