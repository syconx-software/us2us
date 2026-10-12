package com.syconx.us2us.feature.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The world of the report shots: the design's fixtures on the frozen clock, in the shot's theme. */
private val reportShots = ShotEnvironment { spec, content ->
    val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
    U2PreviewRoot(PreviewAppModel(service, clock = service.clock), dark = spec.isDark, designInsets = spec.designInsets) { content() }
}

/** The screen under a sheet: its backdrop hides it anyway. */
@Composable
private fun Behind() {
    Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
}

/**
 * The report sheet (the iOS `safety:deniz/report` and `report-address` states), the fallback without
 * e-mail and the block confirmation with “Bildir”, in light (as the iOS captures) and dark; and the
 * sheet's behaviour: a reason enables the send button, the fallback after a send without a mail app,
 * the copy, and the presenter's timing from a confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class ReportShotTest {
    @get:Rule
    val host = ShotHost(environment = reportShots)

    private val deniz = ReportSubject("deniz", isUsernameVerified = true)
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun model(delivery: ReportDelivery = ReportDelivery.HandedOff, offersBlock: Boolean = true, onBlock: () -> Unit = {}) =
        ReportModel(deniz, "deniz", offersBlock, FakeMailer(delivery), detached) { onBlock() }

    @Composable
    private fun SheetOver(model: ReportModel) {
        Behind()
        U2Sheet(isPresented = true, onDismissRequest = { model.sheetDidDisappear() }) { ReportSheetContent(model) }
    }

    @Test
    fun reportSheet() {
        for (theme in ShotTheme.entries) {
            val report = model().apply { reason = ReportReason.Harassment }
            host.shoot("report_sheet", theme, "tr") { SheetOver(report) }
            host.compose.onNodeWithTag(ReportTags.ALSO_BLOCK).assertIsOn()
            host.compose.onNodeWithTag(ReportTags.SEND).assertIsEnabled()
            host.compose.onNodeWithTag(ReportTags.FORM).performTouchInput { swipeUp() }
            host.advance(1500)
            host.capture("report_sheet-scrolled")
        }
        val english = model().apply { reason = ReportReason.Spam }
        host.shoot("report_sheet", ShotTheme.Dark, "en") { SheetOver(english) }
    }

    @Test
    fun someoneBlockedAlreadyIsNotOfferedTheBlock() {
        val report = model(offersBlock = false)
        host.shoot("report_sheet-blocked", ShotTheme.Dark, "tr") { SheetOver(report) }
        assertTrue(host.compose.onAllNodes(hasTestTag(ReportTags.ALSO_BLOCK)).fetchSemanticsNodes().isEmpty())
        host.compose.onNodeWithTag(ReportTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun addressFallback() {
        for (theme in ShotTheme.entries) {
            var blocks = 0
            val report = model(ReportDelivery.AddressShown) { blocks++ }.apply { reason = ReportReason.Harassment }
            runBlocking { report.send(reportStrings("tr-TR"), reportNow) }
            assertEquals(1, blocks)
            host.shoot("report_address", theme, "tr") { SheetOver(report) }
            host.compose.onNodeWithText("support@us2us.app").assertIsDisplayed()
            host.compose.onNodeWithTag(ReportTags.BLOCKED).assertIsDisplayed()
            host.compose.onNodeWithText("Kapat").assertIsDisplayed()
        }
    }

    @Test
    fun blockConfirmation() {
        for (theme in ShotTheme.entries) {
            host.shoot("report_block-confirm", theme, "tr") {
                Behind()
                BlockConfirmationSheet(item = "deniz", personName = { it }, onBlock = {}, onReport = {}, onDismiss = {})
            }
            host.compose.onNodeWithText("deniz engellensin mi?").assertIsDisplayed()
            host.compose.onNodeWithTag("confirm.report").assertIsDisplayed()
        }
    }

    // MARK: Behaviour

    @Test
    fun aReasonEnablesSendingAndTheFallbackFollowsWithoutAMailApp() {
        var blocks = 0
        var closed: Boolean? = null
        val report = model(ReportDelivery.AddressShown) { blocks++ }
        report.onClose = { closed = it }
        host.render(ShotTheme.Dark, "tr") { SheetOver(report) }
        host.compose.onNodeWithTag(ReportTags.SEND).assertIsNotEnabled()
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Spam)).performClick()
        host.advance(400)
        assertEquals(ReportReason.Spam, report.reason)
        host.compose.onNodeWithTag(ReportTags.SEND).assertIsEnabled()
        host.compose.onNodeWithTag(ReportTags.NOTE).performTextInput("Reklam atıyor")
        assertEquals("Reklam atıyor", report.note)
        host.compose.onNodeWithTag(ReportTags.SEND).performClick()
        host.advance(600)
        assertEquals(ReportModel.Phase.Address, report.phase)
        assertEquals(1, blocks)
        host.compose.onNodeWithTag(ReportTags.FALLBACK).assertIsDisplayed()
        // TalkBack hears what VoiceOver says: a live region with a size (none would leave it out of the tree).
        assertAnnounced("Bu telefonda e-posta ayarlı değil deniz engellendi.")
        host.compose.onNodeWithTag(ReportTags.COPY).performClick()
        host.advance(600)
        assertTrue(report.copiedAddress)
        host.compose.onNode(hasText("Kopyalandı")).assertIsDisplayed()
        assertAnnounced("Kopyalandı deniz engellendi.")
        host.capture("report_address-copied")
        assertNull(closed)
        host.compose.onNodeWithTag(ReportTags.DONE).performClick()
        assertEquals(true, closed)
    }

    private fun assertAnnounced(text: String) {
        val node = host.compose.onNodeWithTag(ReportTags.ANNOUNCEMENT).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        assertEquals(listOf(text), node.config[SemanticsProperties.ContentDescription])
        assertTrue("a live region of no size is not in the accessibility tree", node.size.width > 0 && node.size.height > 0)
    }

    @Test
    fun theToggleOffBlocksNobody() {
        var blocks = 0
        val report = model(ReportDelivery.HandedOff) { blocks++ }
        host.render(ShotTheme.Dark, "tr") { SheetOver(report) }
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Other)).performClick()
        host.compose.onNodeWithTag(ReportTags.ALSO_BLOCK).performClick()
        host.advance(400)
        assertFalse(report.alsoBlock)
        host.compose.onNodeWithTag(ReportTags.SEND).performClick()
        host.advance(400)
        assertEquals(0, blocks)
        assertFalse(report.didBlock)
    }

    /** From a block confirmation the sheet rises 450 ms later; [ReportSheet] tells the host once it has gone. */
    @Test
    fun thePresenterOpensAfterTheConfirmationAndReportsTheEnd() {
        var presenter: ReportPresenter? = null
        val dismissed = mutableListOf<ReportModel>()
        var blocks = 0
        host.render(ShotTheme.Dark, "tr") {
            CompositionLocalProvider(LocalReportMailer provides FakeMailer(ReportDelivery.HandedOff)) {
                val current = rememberReportPresenter()
                SideEffect { presenter = current }
                Behind()
                ReportSheet(current) { dismissed += it }
            }
        }
        val opened = checkNotNull(presenter).open(deniz, "deniz", offersBlock = true, afterConfirmation = true) { blocks++ }
        host.advance(200)
        assertNull("not before the confirmation has gone", presenter?.current)
        host.advance(400)
        assertEquals(opened, presenter?.current)
        host.advance(800)
        host.compose.onNodeWithTag(ReportTags.SHEET).assertIsDisplayed()
        host.compose.onNodeWithTag(ReportTags.reason(ReportReason.Spam)).performClick()
        host.compose.onNodeWithTag(ReportTags.SEND).performClick()
        host.advance(1200)
        assertEquals(1, blocks)
        assertNull(presenter?.current)
        assertEquals(listOf(opened), dismissed)
        assertTrue(dismissed.single().didBlock)
        assertTrue(host.compose.onAllNodes(hasTestTag(ReportTags.SHEET)).fetchSemanticsNodes().isEmpty())
    }
}
