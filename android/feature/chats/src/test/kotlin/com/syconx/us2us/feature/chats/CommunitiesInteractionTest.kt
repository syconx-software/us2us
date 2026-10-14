package com.syconx.us2us.feature.chats

import androidx.compose.ui.semantics.SemanticsActions
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
    private val screen get() = host.compose.onNodeWithTag(CommunityTags.SCREEN)
    private fun open(lang: String = "en") { host.render(lang = lang) { U2Theme { CommunitiesScreen(service) {} } }; host.advance(400) }
    private fun scroll(text: String) {
        screen.performScrollToNode(hasText(text) or hasContentDescription(text))
        host.advance(100)
    }
    private fun top() { screen.performScrollToIndex(0); host.advance(100) }
    private fun click(text: String) {
        scroll(text)
        tap(text)
    }
    /** A button outside the list (a sheet's): brought into view, then clicked. */
    private fun tap(text: String) = clickVisible(host.compose.onAllNodes(hasText(text) and hasClickAction()).onLast())
    private fun tapTag(tag: String) = clickVisible(host.compose.onNodeWithTag(tag))

    /**
     * Clicks [node] once it is on screen: in the manage sheet it is scrolled into view first, by the
     * scroll action itself (`performScrollTo` waits for an animated scroll that the paused clock
     * never moves, and a swipe down would drag the sheet).
     */
    private fun clickVisible(node: SemanticsNodeInteraction) {
        val height = host.compose.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInRoot.height
        val sheet = host.compose.onAllNodesWithTag(CommunityTags.MANAGE_SHEET).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        // Under the sheet's header (its first 160 px) a click would reach the header instead.
        val delta = when {
            sheet == null -> 0f
            bounds.top < sheet.top + 160f -> bounds.top - (sheet.top + 240f)
            bounds.bottom > height - 8f -> bounds.bottom - (height - 120f)
            else -> 0f
        }
        if (delta != 0f) {
            host.compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(CommunityTags.MANAGE_SHEET)))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            host.advance(1_000)
        }
        node.performClick()
        host.advance(1_000)
    }

    private fun type(description: String, text: String) {
        host.compose.onNodeWithContentDescription(description).performTextInput(text)
        host.advance(400)
    }
    private fun openGroup(id: String) {
        host.compose.onNodeWithTag(CommunityTags.group(id)).performClick()
        host.advance(600)
    }
    private fun manage() { tapTag(CommunityTags.MANAGE) }
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
        tap("Send request")
        assertEquals(service.publicGroup.id, service.requested)
        top()
        assertTrue(exists("Approval pending"))
        assertTrue(service.sent.isEmpty())
    }

    @Test fun theConversationKeepsTheScreenAndSendsWithTheComposersButton() {
        service.groups = listOf(service.owned)
        open()
        openGroup("owned")
        // Members, requests, invitations and delete live in the manage sheet, not under the messages.
        listOf("Join requests", "Members ·", "Create one-use invitation", "Delete community", "Leave community").forEach {
            assertFalse("$it is not in the conversation", exists(it))
        }
        host.compose.onNodeWithTag(CommunityTags.MANAGE).assertIsDisplayed()
        assertTrue("an unknown sender is a short id", exists("Member · 9f8e7d6c"))
        assertFalse("never a whole account id", exists("9f8e7d6c-"))
        // The send control is the composer's icon button, not a text button.
        assertFalse(host.compose.onAllNodes(hasText("Send") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        host.compose.onNodeWithTag("composer.send").assertIsNotEnabled()
        host.compose.onNodeWithTag("composer.input").performTextInput("Hello members")
        host.advance(400)
        host.compose.onNodeWithTag("composer.send").assertIsEnabled().performClick()
        host.advance(400)
        assertEquals(listOf("owned" to "Hello members"), service.sent)
        assertFalse("the field is cleared once sent", exists("Hello members"))
    }

    @Test fun keysAreGroupedHexAndAccountsShortIds() {
        assertEquals(
            "05a7 7a12 c630 a962 9a2b 3971 b478 932d 93a3 f1d9 aa4a e127 966a 9b1b c39d 3446 7e",
            CommunityFormat.fingerprint(KEY),
        )
        assertEquals("05ab cdef 01", CommunityFormat.fingerprint(byteArrayOf(0x05, 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x01)))
        assertEquals("d5bb5a72", CommunityFormat.shortId(APPLICANT))
        assertEquals("hyphens and case are ignored", "0a1b2c3d", CommunityFormat.shortId("0A1B-2C3D-4E5F"))
        service.groups = listOf(service.owned)
        open()
        openGroup("owned")
        manage()
        assertTrue(exists("Identity key to add"))
        assertTrue(exists(CommunityFormat.fingerprint(KEY)))
        assertFalse("the raw key is not shown", exists(service.identity))
        assertTrue(exists("Member · d5bb5a72"))
        assertFalse("never a whole account id", exists("d5bb5a72-"))
        assertTrue("a verified contact is shown by username", exists("@deniz"))
        assertTrue("an unknown member is a short id", exists("Member · 0a1b2c3d"))
        assertTrue(exists("Not in your contacts"))
        assertTrue(exists("Members · 3/100"))
        // Every member row has a subtitle, so all rows are the same height with or without “Remove”.
        val rows = listOf("You", "@deniz", "Member · 0a1b2c3d").map { title ->
            host.compose.onAllNodes(hasText(title), useUnmergedTree = true).onFirst().fetchSemanticsNode().let { node ->
                generateSequence(node) { it.parent }.first { it.boundsInRoot.width > node.boundsInRoot.width * 1.5f }.boundsInRoot.height
            }
        }
        assertTrue("member rows share one height: $rows", rows.all { kotlin.math.abs(it - rows[0]) < 1f })
    }

    @Test fun ownerApprovesAndRemovesOnlyAfterConfirming() {
        service.groups = listOf(service.owned)
        open()
        openGroup("owned")
        manage()
        // Reddet · Onayla: the 4.3 decision row across the card (1 : 1.3), not two words at the left.
        val reject = host.compose.onNode(hasText("Reject") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val approve = host.compose.onNode(hasText("Approve") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val card = host.compose.onNodeWithTag(CommunityTags.request(APPLICANT)).fetchSemanticsNode().boundsInRoot
        assertEquals(reject.top, approve.top, 0.5f)
        assertEquals(reject.height, approve.height, 0.5f)
        assertEquals(1 / 1.3f, reject.width / approve.width, 0.05f)
        assertTrue("the row spans the card", approve.right - reject.left > card.width * 0.8f)
        tap("Approve")
        assertEquals(APPLICANT, service.approved)
        tapTag(CommunityTags.remove(DENIZ))
        assertNull("removing needs a confirmation", service.removed)
        host.compose.onNodeWithTag(CommunityTags.MANAGE_SHEET).assertExists()
        confirm()
        assertEquals(DENIZ, service.removed)
        assertTrue("the sheet stays open after removing", tagged(CommunityTags.MANAGE_SHEET))
    }

    @Test fun anInvitationIsHiddenOnceUsedOrWhenTheSheetCloses() {
        service.groups = listOf(service.owned)
        open()
        openGroup("owned")
        manage()
        tap("Create one-use invitation")
        assertTrue(tagged(CommunityTags.INVITE))
        // Someone redeemed it: their request arrives with the next poll.
        service.applicationList += CommunityApplication("redeemer", service.identity, 2, CommunityFormat.fingerprint(KEY))
        host.advance(10_100)
        assertFalse("a redeemed invitation leaves the screen", tagged(CommunityTags.INVITE))

        tap("Create one-use invitation")
        assertTrue(tagged(CommunityTags.INVITE))
        tapTag("sheet.close")
        manage()
        assertFalse("closing the sheet drops the invitation", tagged(CommunityTags.INVITE))

        tap("Create one-use invitation")
        clickVisible(host.compose.onAllNodes(hasText("Approve") and hasClickAction()).onFirst())
        assertFalse("an approval drops the invitation", tagged(CommunityTags.INVITE))

        tap("Create one-use invitation")
        assertTrue(tagged(CommunityTags.INVITE))
        service.groups = listOf(service.owned.copy(revision = 7))
        host.advance(10_100)
        assertFalse("a roster change drops the invitation", tagged(CommunityTags.INVITE))
    }

    @Test fun pendingRequestsAreWithdrawnAndEndedOnesForgottenAfterConfirming() {
        service.groups = listOf(service.pending, service.ended)
        open()
        click("Withdraw request")
        assertNull(service.withdrawn)
        confirm()
        assertEquals("pending", service.withdrawn)
        openGroup("ended")
        assertTrue(exists("You are no longer a member"))
        manage()
        tap("Remove")
        assertNull(service.forgotten)
        confirm()
        assertEquals("ended", service.forgotten)
        assertTrue("back on the list", tagged(CommunityTags.SCREEN))
    }

    @Test fun aMemberLeavesAfterConfirming() {
        service.groups = listOf(service.joined)
        open()
        openGroup("joined")
        manage()
        tap("Leave community")
        assertNull(service.left)
        confirm()
        assertEquals("joined", service.left)
        assertFalse(tagged(CommunityTags.MANAGE_SHEET))
    }

    @Test fun aRosterThatDoesNotVerifyIsShown() {
        service.warning = true
        open()
        host.compose.onNodeWithTag(CommunityTags.WARNING).assertExists()
    }

    @Test fun lockingClearsTheScreenAndUnlockingResumesPolling() {
        service.groups = listOf(service.owned)
        open()
        openGroup("owned")
        host.compose.onNodeWithTag("composer.input").performTextInput("unsent draft")
        host.advance(400)
        manage()
        tap("Create one-use invitation")
        assertTrue(tagged(CommunityTags.INVITE))
        service.failNext = true
        host.advance(10_100)
        assertTrue(tagged(CommunityTags.ERROR))

        service.unlocked.value = false
        host.advance(1_000)
        assertFalse(tagged(CommunityTags.INVITE))
        assertFalse(tagged(CommunityTags.ERROR))
        assertFalse(tagged(CommunityTags.MANAGE_SHEET))
        assertFalse("the draft is gone", exists("unsent draft"))
        assertFalse("the list is gone", exists("Owned"))
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

    /** The conversation and the manage sheet, for review next to the iOS captures. */
    @Test fun conversationAndManageSheetShots() {
        for (lang in listOf("tr", "en")) {
            service.groups = listOf(service.owned)
            service.approved = null
            open(lang)
            openGroup("owned")
            host.capture("communities_conversation")
            manage()
            host.advance(600)
            host.capture("communities_manage")
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
        var refreshes = 0
        var failNext = false
        var cancelNext = false
        val sent = mutableListOf<Pair<String, String>>()
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
        override suspend fun messages(id: String) = listOf(
            CommunityText("m1", "9f8e7d6c-5b4a-4321-8fed-cba987654321", "Hello from a member", 1_700_000_000_000, "received"),
            CommunityText("m2", "me", "Hello back", 1_700_000_060_000, "sent", outgoing = true),
        )
        override suspend fun applications(id: String) = applicationList.toList()
        override suspend fun approve(id: String, application: CommunityApplication) {
            approved = application.account
            applicationList.removeAll { it.account == application.account }
        }
        override suspend fun send(id: String, text: String) { sent += id to text }
        override suspend fun create(name: String, public: Boolean) = Unit
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
