package com.syconx.us2us.feature.report

import com.syconx.us2us.domain.ReportReason
import com.syconx.us2us.domain.ReportSubject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A mailer that answers as a mail app (or the phone) would, and keeps what it was given. */
internal class FakeMailer(private val delivery: ReportDelivery) : ReportMailer {
    val mails = mutableListOf<ReportMail>()

    override suspend fun deliver(mail: ReportMail): ReportDelivery {
        mails += mail
        return delivery
    }
}

/**
 * When “Bu kişiyi de engelle” blocks (iOS `ReportBlockRuleTests`): only once the report left the
 * sheet (handed to a mail app, or the address shown), never after a cancel or a failure, never with
 * the toggle off or for someone blocked already; the sheet never waits for the block.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportModelTest {
    private val turkish get() = reportStrings("tr-TR")
    private val english get() = reportStrings("en-TR")

    private class Setup(val model: ReportModel, val mailer: FakeMailer) {
        var blocks = 0
        var closed: Boolean? = null
    }

    private fun TestScope.setup(delivery: ReportDelivery, offersBlock: Boolean = true, alsoBlock: Boolean = true): Setup {
        val mailer = FakeMailer(delivery)
        lateinit var setup: Setup
        val model = ReportModel(ReportSubject("deniz", isUsernameVerified = true), "deniz", offersBlock, mailer, backgroundScope) {
            setup.blocks += 1
        }
        setup = Setup(model, mailer)
        model.alsoBlock = alsoBlock
        model.version = "1.0.8"
        model.build = "11"
        model.onClose = { setup.closed = it }
        return setup
    }

    @Test
    fun blocksOnlyOnceTheReportLeftTheSheet() {
        assertTrue(ReportDelivery.blocks(ReportDelivery.Sent, alsoBlock = true))
        assertTrue(ReportDelivery.blocks(ReportDelivery.HandedOff, alsoBlock = true))
        assertTrue(ReportDelivery.blocks(ReportDelivery.AddressShown, alsoBlock = true))
        assertFalse("cancelled or kept as a draft", ReportDelivery.blocks(ReportDelivery.NotSent, alsoBlock = true))
        assertFalse(ReportDelivery.blocks(ReportDelivery.Failed, alsoBlock = true))
        for (delivery in ReportDelivery.entries) assertFalse(ReportDelivery.blocks(delivery, alsoBlock = false))
    }

    @Test
    fun nothingIsSentWithoutAReason() = runTest {
        val setup = setup(ReportDelivery.Sent)
        assertFalse(setup.model.canSend)
        setup.model.send(turkish, reportNow)
        runCurrent()
        assertTrue(setup.mailer.mails.isEmpty())
        assertEquals(0, setup.blocks)
        assertEquals(ReportModel.Phase.Form, setup.model.phase)
    }

    @Test
    fun handedToAMailAppBlocksAndCloses() = runTest {
        val setup = setup(ReportDelivery.HandedOff)
        val model = setup.model
        assertTrue("on by default", model.alsoBlock)
        model.reason = ReportReason.Spam
        model.note = "Reklam"
        assertTrue(model.canSend)
        model.send(turkish, reportNow)
        assertEquals("us2us bildirimi: @deniz", setup.mailer.mails.single().subject)
        assertTrue(setup.mailer.mails.single().body.contains("Not: Reklam"))
        assertTrue(setup.mailer.mails.single().body.contains("Uygulama: us2us Android 1.0.8 (11)"))
        model.blocking?.join()
        assertEquals(1, setup.blocks)
        assertTrue(model.didBlock)
        assertEquals(true, setup.closed)
    }

    @Test
    fun sentBlocksToo() = runTest {
        val setup = setup(ReportDelivery.Sent)
        setup.model.reason = ReportReason.Other
        setup.model.send(english, reportNow)
        setup.model.blocking?.join()
        assertEquals(1, setup.blocks)
        assertEquals(true, setup.closed)
    }

    /** The block stores at once but may also wait for the relay: the sheet closes without waiting for it. */
    @Test
    fun theSheetDoesNotWaitForTheBlockToFinish() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val model = ReportModel(ReportSubject("deniz", true), "deniz", offersBlock = true, FakeMailer(ReportDelivery.HandedOff), backgroundScope) {
            gate.await()
            finished = true
        }
        var closed: Boolean? = null
        model.onClose = { closed = it }
        model.reason = ReportReason.Spam
        model.send(turkish, reportNow)
        runCurrent()
        assertEquals("closed while the block is still on its way", true, closed)
        assertTrue(model.didBlock)
        assertFalse(finished)
        gate.complete(Unit)
        model.blocking?.join()
        assertTrue(finished)
    }

    @Test
    fun cancelledInTheMailAppBlocksNobodyAndKeepsTheForm() = runTest {
        val setup = setup(ReportDelivery.NotSent)
        val model = setup.model
        model.reason = ReportReason.Harassment
        model.note = "Not"
        model.send(turkish, reportNow)
        runCurrent()
        assertEquals(0, setup.blocks)
        assertFalse(model.didBlock)
        assertEquals(ReportModel.Phase.Form, model.phase)
        assertNull(setup.closed)
        assertEquals("nothing typed is lost", ReportReason.Harassment, model.reason)
        assertEquals("Not", model.note)
    }

    @Test
    fun aFailureSaysSoAndBlocksNobody() = runTest {
        val setup = setup(ReportDelivery.Failed)
        setup.model.reason = ReportReason.Spam
        setup.model.send(turkish, reportNow)
        runCurrent()
        assertEquals(0, setup.blocks)
        assertTrue(setup.model.failed)
        assertTrue("it can be tried again", setup.model.canSend)
        assertEquals("E-posta gönderilemedi. Tekrar dene.", setup.model.announcement)
    }

    @Test
    fun withoutMailTheAddressIsShownAndTheBlockFollows() = runTest {
        val setup = setup(ReportDelivery.AddressShown)
        val model = setup.model
        model.reason = ReportReason.Inappropriate
        model.send(turkish, reportNow)
        assertEquals(ReportModel.Phase.Address, model.phase)
        model.blocking?.join()
        assertEquals(1, setup.blocks)
        assertNull("the address stays until “Tamam”", setup.closed)
        assertEquals("Bu telefonda e-posta ayarlı değil deniz engellendi.", model.announcement)
        var copied: String? = null
        model.copyAddress(turkish) { copied = it }
        assertEquals(ReportMail.ADDRESS, copied)
        assertTrue(model.copiedAddress)
        // Pulled down: closes like “Tamam”, once.
        model.sheetDidDisappear()
        model.close()
        assertEquals(true, setup.closed)
    }

    @Test
    fun theToggleOffOrSomeoneBlockedAlreadyBlocksNobody() = runTest {
        val off = setup(ReportDelivery.HandedOff, alsoBlock = false)
        off.model.reason = ReportReason.Spam
        off.model.send(turkish, reportNow)
        runCurrent()
        assertEquals(0, off.blocks)
        assertFalse(off.model.didBlock)
        assertEquals(false, off.closed)
        val already = setup(ReportDelivery.HandedOff, offersBlock = false)
        already.model.reason = ReportReason.Spam
        assertFalse(already.model.blocksOnSend)
        already.model.send(turkish, reportNow)
        runCurrent()
        assertEquals(0, already.blocks)
        assertFalse(already.model.didBlock)
    }

    @Test
    fun cancellingBlocksNobodyAndClosesOnce() = runTest {
        val setup = setup(ReportDelivery.HandedOff)
        setup.model.reason = ReportReason.Spam
        setup.model.close()
        setup.model.close()
        assertEquals(false, setup.closed)
        assertTrue(setup.mailer.mails.isEmpty())
        assertEquals(0, setup.blocks)
    }
}
