package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.SafetyCodeComparison
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.env.U2Haptic
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.motion.U2Motion
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val DENIZ = ChatId("deniz")

internal fun fixtures(english: Boolean = false) = FixturesMessengerService(
    hasAccount = true,
    clock = DesignClock.frozen(DesignClock.referenceDate),
    english = english,
    latency = Duration.ZERO,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
)

/** Haptics recorded for assertions. */
internal class RecordedHaptics : U2Haptics {
    val played = mutableListOf<U2Haptic>()

    override fun perform(haptic: U2Haptic) {
        played += haptic
    }
}

/** 6.1 → 6.2 → 6.3 against the fixtures (iOS VerificationModel behaviour). */
class VerificationModelTest {
    @Test
    fun loadsTheNumberItsCodeAndTheOwnCode() = runBlocking {
        val model = VerificationModel(DENIZ, fixtures())
        model.load()
        val info = model.info!!
        assertEquals(DesignFixtures.safetyNumberGroups, info.groups)
        assertEquals(SafetyQRCodec.encode(info.scannablePayload), model.qrPayload)
        assertFalse(model.isVerified)
        assertFalse(model.numberChanged)
        val own = model.scanner.ownCode!!
        assertEquals(DesignFixtures.OWN_USERNAME, own.username)
        assertEquals(model.qrPayload, own.payload)
        assertEquals(info.groups, own.groups)
        assertEquals(QrScannerModel.Purpose.SafetyNumber("deniz"), model.scanner.purpose)
    }

    @Test
    fun markingByHandPushesVerified() = runBlocking {
        val haptics = RecordedHaptics()
        val announced = mutableListOf<Int>()
        val model = VerificationModel(DENIZ, fixtures())
        model.haptics = haptics
        model.announce = { announced += it }
        model.load()
        model.requestMarkVerified()
        assertTrue(model.confirmsMarkVerified)
        model.markVerifiedManually()
        assertTrue(model.isVerified)
        assertEquals(VerificationMethod.Manual, model.info?.method)
        assertTrue(model.showsVerified)
        assertFalse("pushed, not in place", model.verifiedAppearsInPlace)
        assertEquals(0f, model.verifiedEntranceDelay)
        assertEquals(listOf(U2Haptic.Success), haptics.played)
        assertEquals(listOf(com.syconx.us2us.ui.R.string.verification_verified_title), announced)
        assertTrue(model.peer!!.isVerified)
    }

    @Test
    fun aNumberThatChangedMeanwhileIsNeverMarked() = runBlocking {
        val service = fixtures()
        val refusing = object : MessengerService by service {
            override suspend fun markVerified(chat: ChatId, number: Bytes): Boolean = false

            override suspend fun acceptSafetyNumberChange(chat: ChatId, number: Bytes): Boolean = false
        }
        val haptics = RecordedHaptics()
        val model = VerificationModel(DENIZ, refusing)
        model.haptics = haptics
        model.load()
        model.requestMarkVerified()
        model.markVerifiedManually()
        assertFalse(model.isVerified)
        assertFalse(model.showsVerified)
        assertTrue("6.1 says the number changed while it was open", model.changedWhileOpen)
        assertEquals(listOf(U2Haptic.Error), haptics.played)
    }

    @Test
    fun removingTheVerificationTakesTheSealAway() = runBlocking {
        val service = fixtures()
        service.markVerified(DENIZ, VerificationMethod.FaceToFace)
        val model = VerificationModel(DENIZ, service)
        model.load()
        assertTrue(model.isVerified)
        model.requestRemoval()
        assertTrue(model.confirmsRemoval)
        model.removeVerification()
        assertFalse(model.isVerified)
        assertFalse(service.peer(DENIZ).value!!.isVerified)
    }

    @Test
    fun acceptingAChangeGoesBackToTheChatWithoutASeal() = runBlocking {
        val service = fixtures()
        service.setSafetyNumberChanged(DENIZ)
        val model = VerificationModel(DENIZ, service)
        var exits = 0
        model.exitToChat = { exits++ }
        model.load()
        assertTrue(model.numberChanged)
        model.requestAccept()
        assertTrue(model.confirmsAccept)
        model.acceptChangeWithoutVerifying()
        assertFalse(model.numberChanged)
        assertFalse(model.isVerified)
        assertEquals(1, exits)
        assertFalse(service.peer(DENIZ).value!!.safetyNumberChanged)
    }

    @Test
    fun aConfirmationNeverVerifiesANumberRefreshedBehindTheSheet() = runBlocking {
        val fixture = fixtures()
        var current = fixture.safetyNumber(DENIZ)!!
        var verified = false
        val service = object : MessengerService by fixture {
            override suspend fun safetyNumber(chat: ChatId) = current
            override suspend fun markVerified(chat: ChatId, number: Bytes): Boolean {
                verified = number == current.scannablePayload
                return verified
            }
        }
        val model = VerificationModel(DENIZ, service)
        model.load()
        model.requestMarkVerified()
        current = current.copy(scannablePayload = Bytes.utf8("replacement-identity"))
        model.load() // The normal identity-state observer refreshes while confirmation is open.
        model.markVerifiedManually()
        assertFalse("a number never compared by the user was verified", verified)
        assertTrue(model.changedWhileOpen)
    }

    @Test
    fun aConfirmationNeverAcceptsASecondIdentityChange() = runBlocking {
        val fixture = fixtures()
        var current = fixture.safetyNumber(DENIZ)!!
        var accepted = false
        val service = object : MessengerService by fixture {
            override suspend fun safetyNumber(chat: ChatId) = current
            override suspend fun acceptSafetyNumberChange(chat: ChatId, number: Bytes): Boolean {
                accepted = number == current.scannablePayload
                return accepted
            }
        }
        val model = VerificationModel(DENIZ, service)
        var exited = false
        model.exitToChat = { exited = true }
        model.load()
        model.requestAccept()
        current = current.copy(scannablePayload = Bytes.utf8("second-replacement-identity"))
        model.load()
        model.acceptChangeWithoutVerifying()
        assertFalse("a second change was accepted by the first confirmation", accepted)
        assertFalse(exited)
        assertTrue(model.changedWhileOpen)
    }

    @Test
    fun cancelledOrConsumedConfirmationCannotBeReused() = runBlocking {
        val fixture = fixtures()
        val model = VerificationModel(DENIZ, fixture)
        model.load()
        model.requestMarkVerified()
        model.cancelMarkVerified()
        model.markVerifiedManually()
        assertFalse(fixture.safetyNumber(DENIZ)!!.isVerified)
        model.requestMarkVerified()
        model.markVerifiedManually()
        assertTrue(model.isVerified)
        fixture.clearVerification(DENIZ)
        model.load()
        model.markVerifiedManually()
        assertFalse(fixture.safetyNumber(DENIZ)!!.isVerified)
        fixture.setSafetyNumberChanged(DENIZ)
        model.load()
        model.requestAccept()
        model.cancelAccept()
        model.acceptChangeWithoutVerifying()
        assertTrue(fixture.safetyNumber(DENIZ)!!.hasUnverifiedChange)
    }

    @Test
    fun aWrongCodeWarnsOnceAndScanningGoesOn() = runBlocking {
        val service = fixtures()
        var comparisons = 0
        val counting = object : MessengerService by service {
            override suspend fun verifyScannedSafetyCode(code: String, chat: ChatId): SafetyCodeComparison {
                comparisons++
                return service.verifyScannedSafetyCode(code, chat)
            }
        }
        val model = VerificationModel(DENIZ, counting)
        model.load()
        model.presentScanner()
        assertTrue(model.isScannerPresented)
        assertTrue(model.scanner.isActive)
        val wrong = model.scanner.simulatedCodes().second
        model.scanner.handle(wrong)
        assertEquals(QrScannerModel.Warning.Mismatch, model.scanner.warning)
        model.scanner.handle(wrong)
        assertEquals("the same code still in front of the camera is not compared again", 1, comparisons)
        assertTrue(model.isScannerPresented)
        assertFalse(model.isVerified)
        // Anything that is not a safety-number code is ignored.
        model.scanner.dismissWarning()
        model.scanner.handle("https://us2us.app")
        assertNull(model.scanner.warning)
    }

    @Test
    fun aMatchShowsVerifiedUnderTheFadingCover() = runBlocking {
        val model = VerificationModel(DENIZ, fixtures())
        model.load()
        model.presentScanner()
        model.scanner.setTorch(true)
        model.scanner.handle(model.scanner.simulatedCodes().first!!)
        assertTrue(model.isVerified)
        assertEquals(VerificationMethod.FaceToFace, model.info?.method)
        assertTrue(model.showsVerified)
        assertTrue("6.3 is put under the cover", model.verifiedAppearsInPlace)
        assertFalse(model.isScannerPresented)
        assertFalse(model.scanner.isActive)
        assertFalse(model.scanner.torchOn)
        assertEquals(U2Motion.Duration.screen, model.scannerFadeOut)
        assertEquals(model.scannerFadeOut, model.verifiedEntranceDelay)
    }

    @Test
    fun closingTheScannerStopsItAndTheTorch() = runBlocking {
        val model = VerificationModel(DENIZ, fixtures())
        model.load()
        model.presentScanner()
        model.scanner.setTorch(true)
        model.scanner.close()
        assertFalse(model.isScannerPresented)
        assertFalse(model.scanner.isActive)
        assertFalse(model.scanner.torchOn)
        assertEquals(U2Motion.Duration.state, model.scannerFadeOut, 0.001f)
    }

    @Test
    fun theIdentityStateFollowsTheKeyTheChangeAndTheSeal() = runBlocking {
        val service = fixtures()
        val before = VerificationModel.IdentityState.of(service.peer(DENIZ).value)
        service.setSafetyNumberChanged(DENIZ)
        val after = VerificationModel.IdentityState.of(service.peer(DENIZ).value)
        assertTrue(after.changed)
        assertTrue(before != after)
    }

    @Test
    fun texts() {
        val groups = DesignFixtures.safetyNumberGroups
        assertEquals("0 8 2 2 3, 7 8 6 2 6", VerificationText.spokenDigits(groups.take(2)))
        val withoutRecord = SafetyNumberInfo(groups = emptyList(), scannablePayload = Bytes.utf8(""), verifiedAt = null, method = null, isVerifiedWithoutRecord = true)
        assertTrue(withoutRecord.isVerified)
        assertEquals("8 Ekim 2026", VerificationText.longDate(Instant.parse("2026-10-08T15:52:00Z"), java.util.Locale.forLanguageTag("tr-TR"), java.time.ZoneId.of("Europe/Istanbul")))
        assertEquals("8 October 2026", VerificationText.longDate(Instant.parse("2026-10-08T15:52:00Z"), java.util.Locale.US, java.time.ZoneId.of("Europe/Istanbul")))
    }

    @Test
    fun theScanLineRestsAt47PercentAndSweepsBetween14And80() {
        assertEquals(0.47f, ScanLinePath.position(0f), 0.001f)
        for (step in 0..240) {
            val position = ScanLinePath.position(step * 0.05f)
            assertTrue(position in 0.139f..0.801f)
        }
        // Half a sweep later it is near the bottom of its travel.
        assertTrue(ScanLinePath.position(2.4f * (0.80f - 0.47f) / (0.80f - 0.14f)) > 0.79f)
    }

    @Test
    fun theDesignMockIsTheDesignsPattern() {
        val mock = DesignMockQrModules
        assertEquals(29, mock.size)
        // Finder patterns in three corners, the quiet ring around them.
        for (corner in listOf(0 to 0, 0 to 22, 22 to 0)) {
            assertTrue(mock[corner.first, corner.second])
            assertFalse(mock[corner.first + 1, corner.second + 1])
            assertTrue(mock[corner.first + 3, corner.second + 3])
        }
        assertFalse(mock[7, 7])
    }

    @Test
    fun theSimulatedContactCodeCarriesTheFixturesKey() {
        val decoded = ContactCode.decode(ContactScanModel.simulatedCode("deniz"))!!
        assertEquals("deniz", decoded.username)
        assertEquals(DesignFixtures.fixtureKeyFingerprint("deniz"), decoded.keyFingerprint)
    }
}
