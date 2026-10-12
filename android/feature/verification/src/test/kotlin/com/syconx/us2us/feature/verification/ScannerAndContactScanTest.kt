package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.env.U2Haptic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 6.2 scanner's rules, and adding a contact by QR (iOS QRScannerModel, ContactScanModel). */
class ScannerAndContactScanTest {
    private var now = 0L

    private fun scanner(verdict: (String) -> QrScannerModel.Verdict): Pair<QrScannerModel, MutableList<String>> {
        val seen = mutableListOf<String>()
        val model = QrScannerModel(QrScannerModel.Purpose.AddContact) { now }
        model.isActive = true
        model.evaluate = { code ->
            seen += code
            verdict(code)
        }
        return model to seen
    }

    @Test
    fun aRejectedCodeStaysQuietWhileItIsInFrontOfTheCamera() = runBlocking {
        val haptics = RecordedHaptics()
        val (model, seen) = scanner { QrScannerModel.Verdict.Warn(QrScannerModel.Warning.Unrecognized) }
        model.haptics = haptics
        model.handle("us2us:@nobody")
        assertEquals(QrScannerModel.Warning.Unrecognized, model.warning)
        assertEquals(1, model.warningGeneration)
        now += 1_000
        model.dismissWarning()
        model.handle("us2us:@nobody")
        assertEquals("seen again within 2.5 s: still quiet", 1, seen.size)
        // Each sighting restarts the quiet time.
        now += 2_000
        model.handle("us2us:@nobody")
        assertEquals(1, seen.size)
        now += 2_600
        model.handle("us2us:@nobody")
        assertEquals("out of sight for 2.5 s: compared again", 2, seen.size)
        assertEquals(listOf(U2Haptic.Error, U2Haptic.Error), haptics.played)
    }

    @Test
    fun anotherCodeIsComparedAtOnce() = runBlocking {
        val (model, seen) = scanner { QrScannerModel.Verdict.Warn(QrScannerModel.Warning.Unrecognized) }
        model.handle("us2us:@a")
        model.handle("us2us:@b")
        assertEquals(listOf("us2us:@a", "us2us:@b"), seen)
    }

    @Test
    fun nothingIsReadWhileInactiveOrShowingTheOwnCode() = runBlocking {
        val (model, seen) = scanner { QrScannerModel.Verdict.Accept }
        model.isActive = false
        model.handle("x")
        model.isActive = true
        model.setTorch(true)
        model.showOwnCode()
        assertFalse("the torch goes with the camera", model.torchOn)
        assertEquals(QrScannerModel.Stage.MyCode, model.stage)
        model.handle("x")
        assertTrue(seen.isEmpty())
        model.showScanning()
        assertEquals(QrScannerModel.Stage.Scanning, model.stage)
    }

    @Test
    fun anAcceptedCodeHandsOver() = runBlocking {
        val (model, _) = scanner { QrScannerModel.Verdict.Accept }
        var accepted = 0
        model.onAccept = { accepted++ }
        model.setTorch(true)
        model.handle("code")
        assertEquals(1, accepted)
        assertFalse(model.torchOn)
        assertNull(model.warning)
    }

    @Test
    fun resetStartsOver() = runBlocking {
        val (model, _) = scanner { QrScannerModel.Verdict.Warn(QrScannerModel.Warning.Mismatch) }
        model.handle("code")
        model.showOwnCode()
        model.reset()
        assertEquals(QrScannerModel.Stage.Scanning, model.stage)
        assertNull(model.warning)
    }

    // MARK: Add by QR

    private class Opened {
        val chats = mutableListOf<ChatId>()
        var closes = 0
    }

    private fun contactScan(): Triple<ContactScanModel, Opened, com.syconx.us2us.fixtures.FixturesMessengerService> {
        val service = fixtures()
        val opened = Opened()
        val model = ContactScanModel(service, onClose = { opened.closes++ }, onOpenChat = { opened.chats += it })
        return Triple(model, opened, service)
    }

    @Test
    fun theOwnCodeAndAnUnknownNameWarn() = runBlocking {
        val (model, opened, service) = contactScan()
        model.load()
        assertEquals(service.ownContactCode(), model.scanner.ownCode?.payload)
        assertNull("a contact code has no digits", model.scanner.ownCode?.groups)
        model.scanner.handle(service.ownContactCode()!!)
        assertEquals(QrScannerModel.Warning.OwnCode, model.scanner.warning)
        model.scanner.handle(ContactCode.encode("bilinmeyen"))
        assertEquals(QrScannerModel.Warning.Unrecognized, model.scanner.warning)
        model.scanner.handle("WIFI:S:home;;")
        assertTrue(opened.chats.isEmpty())
        assertNull(model.verification)
    }

    @Test
    fun aCodeWithTheKeyAddsAndVerifies() = runBlocking {
        val (model, opened, service) = contactScan()
        model.scanner.handle(model.scanner.simulatedCodes().first!!)
        val verification = model.verification!!
        assertTrue(verification.isVerified)
        assertEquals(VerificationMethod.FaceToFace, verification.info?.method)
        assertEquals(0.5f, verification.verifiedEntranceDelay)
        assertFalse(model.scanner.isActive)
        assertTrue("6.3 first; the chat opens from it", opened.chats.isEmpty())
        model.finish()
        model.finish()
        assertEquals(listOf(ChatId("deniz")), opened.chats)
        assertEquals(1, opened.closes)
        assertTrue(service.peer(ChatId("deniz")).value!!.isVerified)
    }

    @Test
    fun aCodeWithoutTheKeyOpensTheChatUnverified() = runBlocking {
        val (model, opened, _) = contactScan()
        val announced = mutableListOf<Int>()
        model.announce = { announced += it }
        model.scanner.handle(ContactCode.encode("deniz"))
        assertNull(model.verification)
        assertEquals(listOf(ChatId("deniz")), opened.chats)
        assertEquals(1, opened.closes)
        assertEquals(listOf(com.syconx.us2us.ui.R.string.verification_scan_contact_added), announced)
    }

    @Test
    fun anotherKeyVerifiesNothing() = runBlocking {
        val (model, opened, _) = contactScan()
        val other = DesignFixtures.fixtureKeyFingerprint("someone-else")
        model.scanner.handle(ContactCode.encode("deniz", other))
        assertEquals(QrScannerModel.Warning.ContactKeyMismatch, model.scanner.warning)
        assertTrue(opened.chats.isEmpty())
        assertTrue("scanning goes on", model.scanner.isActive)
    }
}
