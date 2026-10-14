package com.syconx.us2us.feature.verification

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.feature.report.ReportTags
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotOptions
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The contact screen's block and report (iOS `ContactActionsModel`, `ContactBlockedNote`): the
 * harness states `menu`, `block`, `blocked`, `report`, `report-address` as shots, and the taps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ContactBlockingTest {
    private var rig = VerificationRig()

    @get:Rule
    val host = ShotHost(environment = verificationShots { rig })

    private val deniz = ChatId("deniz")

    private fun shoot(state: String, theme: ShotTheme, lang: String = "tr") {
        rig = VerificationRig(english = lang == "en")
        val route = "safety:deniz/$state"
        rig.apply(route)
        host.shoot(route, theme, lang, options = ShotOptions(settleMillis = 3_000)) { VerificationApp(rig) }
    }

    private fun open(route: String) {
        rig = VerificationRig()
        rig.apply(route)
        host.render(ShotTheme.Dark, "tr") { VerificationApp(rig) }
        host.advance(800)
    }

    private fun shows(tag: String): Boolean =
        host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun expect(state: String, vararg tags: String) {
        for (tag in tags) assertTrue("$state should show $tag", shows(tag))
    }

    private fun tap(tag: String, settle: Long = 1_200) {
        host.compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        host.advance(settle)
    }

    @Test
    fun theStatesAreShot() {
        for (theme in ShotTheme.entries) {
            shoot("menu", theme)
            expect("menu", "menu.share", "menu.report", "menu.block")
            assertFalse(shows("menu.unblock"))
            shoot("block", theme)
            expect("block", VerificationTags.BLOCK_CONFIRM, VerificationTags.BLOCK_REPORT, VerificationTags.BLOCK_CANCEL)
            shoot("blocked", theme)
            expect("blocked", VerificationTags.BLOCKED_MARK, VerificationTags.BLOCKED_NOTE, VerificationTags.UNBLOCK)
            shoot("report", theme)
            expect("report", ReportTags.SHEET, ReportTags.ALSO_BLOCK)
            shoot("report-address", theme)
            expect("report-address", ReportTags.FALLBACK, ReportTags.BLOCKED)
            assertTrue("the report blocked deniz", rig.fixtures.peer(deniz).value!!.isBlocked)
        }
        shoot("blocked", ShotTheme.Dark, "en")
        expect("blocked en", VerificationTags.BLOCKED_NOTE)
    }

    @Test
    fun blockingFromTheMenuMarksTheScreenAndUnblockingTakesItBack() {
        open("safety:deniz/safety")
        val before = rig.fixtures.peer(deniz).value!!
        tap(VerificationTags.MORE)
        tap("menu.block")
        assertTrue(shows(VerificationTags.BLOCK_CONFIRM))
        tap(VerificationTags.BLOCK_CONFIRM)
        assertTrue(rig.fixtures.peer(deniz).value!!.isBlocked)
        expect("blocked", VerificationTags.BLOCKED_MARK, VerificationTags.BLOCKED_NOTE)

        // While blocked, the menu offers “Engeli kaldır”, and a report offers no block.
        tap(VerificationTags.MORE)
        expect("menu while blocked", "menu.unblock", "menu.report")
        assertFalse(shows("menu.block"))
        tap("menu.report")
        expect("report", ReportTags.SHEET)
        assertFalse("no “Bu kişiyi de engelle” for someone blocked", shows(ReportTags.ALSO_BLOCK))
        closeReport()
        assertFalse(shows(ReportTags.SHEET))

        tap(VerificationTags.UNBLOCK)
        assertEquals(before, rig.fixtures.peer(deniz).value)
        assertFalse(shows(VerificationTags.BLOCKED_NOTE))
        assertFalse(shows(VerificationTags.BLOCKED_MARK))
    }

    @Test
    fun theConfirmationsBildirOpensTheReportOnceItHasGone() {
        open("safety:deniz/safety")
        tap(VerificationTags.MORE)
        tap("menu.block")
        tap(VerificationTags.BLOCK_REPORT, settle = 300)
        assertFalse("not before 450 ms", shows(ReportTags.SHEET))
        host.advance(900)
        expect("report", ReportTags.SHEET, ReportTags.ALSO_BLOCK)
        assertFalse("nobody is blocked by opening the report", rig.fixtures.peer(deniz).value!!.isBlocked)
    }

    /** Closes the report sheet through its bar's “Vazgeç”. */
    private fun closeReport() {
        host.compose.onNode(hasText("Vazgeç") and hasAnyAncestor(hasTestTag(ReportTags.BAR)), useUnmergedTree = true).performClick()
        host.advance(1_200)
    }
}
