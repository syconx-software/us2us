package com.syconx.us2us.fixtures

import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.domain.AvatarEdit
import com.syconx.us2us.domain.AvatarEditError
import com.syconx.us2us.domain.AvatarSearchCopy
import com.syconx.us2us.domain.BackupContents
import com.syconx.us2us.domain.BackupFileError
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.BackupPasswordError
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChangeSecretCodeError
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.domain.ContactCodeAddition
import com.syconx.us2us.domain.ContactCodeResolution
import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.RestoreProgress
import com.syconx.us2us.domain.SafetyCodeComparison
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.SystemEvent
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FixturesBackupAndMediaTest {
    private val directory: File = Files.createTempDirectory("fixtures-test").toFile()

    @AfterEach
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun TestScope.service() = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignFixtures.referenceDate),
        latency = Duration.ZERO,
        scope = backgroundScope,
        exportDirectory = File(directory, "export"),
    )

    private val deniz = ChatId("deniz")
    private val mert = ChatId("mert_k")

    @Test
    fun `exported backups are real backup files that open with their password`() = runTest {
        val service = service()
        val progress = mutableListOf<Double>()
        val uri = service.exportBackup(BackupContents(messages = false, media = false), DesignFixtures.DEMO_EXPORT_PASSWORD, "yedek.u2u") { progress += it }
        assertEquals(24, progress.size)
        assertEquals(1.0, progress.last())
        val file = File(uri)
        assertEquals("yedek.u2u", file.name)
        assertEquals(4_096, BackupFile.open(file.readBytes(), DesignFixtures.DEMO_EXPORT_PASSWORD).size)

        val info = service.inspectBackup(uri)
        assertEquals("yedek.u2u", info.fileName)
        assertEquals(DesignFixtures.backupEstimate.totalBytes, info.byteCount)
        assertEquals(DesignFixtures.designBackupDate, info.createdAt)
        // Backups exported in this session open with their own password, the fixtures' with the demo one.
        assertEquals(BackupOpenResult.Opened, service.openBackup(info, DesignFixtures.DEMO_EXPORT_PASSWORD))
        assertEquals(BackupOpenResult.Opened, service.openBackup(info, DesignFixtures.DEMO_BACKUP_PASSWORD))
        assertEquals(BackupOpenResult.WrongPassword, service.openBackup(info, "yanlis-parola-12"))
        service.nextBackupOpenResult = BackupOpenResult.NoLongerValid
        assertEquals(BackupOpenResult.NoLongerValid, service.openBackup(info, DesignFixtures.DEMO_BACKUP_PASSWORD))

        service.finishExport(uri, saved = true)
        assertFalse(file.exists())
        assertEquals(DesignFixtures.referenceDate, service.settings.value.lastBackupAt)
    }

    @Test
    fun `weak backup passwords are refused by the core's rules`() = runTest {
        val service = service()
        assertEquals(BackupPasswordError.TooShort, service.backupPasswordProblem("kısa"))
        assertEquals(BackupPasswordError.RepeatedCharacter, service.backupPasswordProblem("aaaaaAAAAA"))
        assertEquals(BackupPasswordError.SimpleSequence, service.backupPasswordProblem("abcdefghij"))
        assertEquals(BackupPasswordError.SimpleSequence, service.backupPasswordProblem("poiuytrewq"))
        assertNull(service.backupPasswordProblem(DesignFixtures.DEMO_EXPORT_PASSWORD))
        try {
            service.exportBackup(BackupContents(), "abcdefghij", "x.u2u") {}
            fail("refused")
        } catch (error: MessengerException.BackupPassword) {
            assertEquals(BackupPasswordError.SimpleSequence, error.error)
        }
        assertTrue(service.backupPasswordIsSecretCode(DesignFixtures.DEMO_SECRET_CODE))
    }

    @Test
    fun `picked files must be backups`() = runTest {
        val service = service()
        val other = File(directory, "notlar.txt").apply { writeText("x") }
        try {
            service.inspectBackup(other.toURI())
            fail("not a backup")
        } catch (error: MessengerException.BackupFile) {
            assertEquals(BackupFileError.NotABackup, error.error)
        }
        try {
            service.inspectBackup(File(directory, "yok.u2u").toURI())
            fail("unreadable")
        } catch (error: MessengerException.BackupFile) {
            assertEquals(BackupFileError.Unreadable, error.error)
        }
        val standIn = service.inspectBackup(DesignFixtures.standInBackupFile(directory))
        assertEquals(DesignFixtures.backupEstimate.fileName, standIn.fileName)
        val picked = service.inspectBackup(java.net.URI("content://com.android.providers.downloads.documents/document/msf%3A12"))
        assertEquals(DesignFixtures.backupEstimate.fileName, picked.fileName)
    }

    @Test
    fun `the restore stream walks keys, messages, media`() = runTest {
        val service = service()
        val info = service.inspectBackup(DesignFixtures.standInBackupFile(directory))
        val steps = service.restoreBackup(info).toList()
        assertEquals(91, steps.size)
        assertEquals(RestoreProgress.Stage.Keys, steps.first().stage)
        assertEquals(0.0, steps.first().fraction)
        assertEquals(1.0, steps.last().fraction)
        assertEquals(RestoreProgress.Stage.Media, steps.last().stage)
        assertEquals(3_920, steps.last().messagesDone)
        assertEquals(Duration.ZERO, steps.last().estimatedRemaining)
        assertEquals(RestoreProgress.Stage.entries, steps.map { it.stage }.distinct())
        assertTrue(steps.zipWithNext().all { (a, b) -> a.fraction < b.fraction && a.messagesDone <= b.messagesDone })
    }

    @Test
    fun `cancelling ends the restore`() = runTest {
        val service = service()
        val info = service.inspectBackup(DesignFixtures.standInBackupFile(directory))
        val seen = mutableListOf<RestoreProgress>()
        val job = launch {
            service.restoreBackup(info).collect {
                seen += it
                if (seen.size == 5) service.cancelRestore()
            }
        }
        job.join()
        assertEquals(5, seen.size)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `changing the code checks the current one`() = runTest {
        val service = service()
        try {
            service.changeSecretCode("111112", "975318")
            fail("wrong current code")
        } catch (error: MessengerException.ChangeSecretCode) {
            assertEquals(ChangeSecretCodeError.WrongCurrentCode, error.error)
        }
        try {
            service.changeSecretCode(DesignFixtures.DEMO_SECRET_CODE, "123456")
            fail("weak new code")
        } catch (error: MessengerException.ChangeSecretCode) {
            assertEquals(ChangeSecretCodeError.Failed, error.error)
        }
        service.changeSecretCode(DesignFixtures.DEMO_SECRET_CODE, "975318")
        assertEquals(com.syconx.us2us.domain.UnlockAttemptResult.Unlocked, service.unlock("975318"))
    }

    @Test
    fun `verification is bound to the number on screen`() = runTest {
        val service = service()
        val ayla = ChatId("ayla_")
        assertFalse(service.markVerified(ayla, Bytes.utf8("another number")))
        val info = service.safetyNumber(ayla)!!
        assertTrue(service.markVerified(ayla, info.scannablePayload))
        assertTrue(service.chat(ayla).value!!.peer.isVerified)
        assertEquals(MessageContent.System(SystemEvent.SafetyNumberVerified), service.messages(ayla).value.last().content)
        service.clearVerification(ayla)
        assertFalse(service.chat(ayla).value!!.peer.isVerified)

        // 6.2: a scanned code compares raw payloads and verifies face to face.
        assertEquals(SafetyCodeComparison.NotASafetyCode, service.verifyScannedSafetyCode("https://us2us.app", deniz))
        assertEquals(SafetyCodeComparison.Mismatch, service.verifyScannedSafetyCode(SafetyQRCodec.encode(Bytes.utf8("x")), deniz))
        assertEquals(SafetyCodeComparison.Match, service.verifyScannedSafetyCode(SafetyQRCodec.encode(DesignFixtures.safetyNumberPayload), deniz))
        assertEquals(VerificationMethod.FaceToFace, service.safetyNumber(deniz)?.method)

        // A changed number: the banner, then “Doğrulamadan devam et” for the number on screen only.
        service.setSafetyNumberChanged(deniz)
        val changed = service.safetyNumber(deniz)!!
        assertTrue(changed.hasUnverifiedChange)
        assertTrue(service.chat(deniz).value!!.peer.safetyNumberChanged)
        assertFalse(service.acceptSafetyNumberChange(deniz, Bytes.utf8("x")))
        assertTrue(service.acceptSafetyNumberChange(deniz, changed.scannablePayload))
        assertFalse(service.chat(deniz).value!!.peer.safetyNumberChanged)
        assertEquals(MessageContent.System(SystemEvent.SafetyNumberAccepted), service.messages(deniz).value.last().content)
    }

    @Test
    fun `stand-in safety numbers are the iOS digits`() = runTest {
        val service = service()
        // Computed independently (Python) with the iOS algorithm: FNV-1a 64 seed, then the LCG.
        assertEquals(
            listOf("49918", "90095", "69481", "95202", "60267", "03657", "03345", "48675", "07487", "68341", "61155", "52729"),
            service.safetyNumber(mert)?.groups,
        )
        assertEquals(DesignFixtures.safetyNumberGroups, service.safetyNumber(deniz)?.groups)
    }

    @Test
    fun `scanned contact codes`() = runTest {
        val service = service()
        assertEquals(ContactCodeResolution.NotAContactCode, service.resolveContactCode("U2SN:ABC"))
        assertEquals(ContactCodeResolution.OwnCode, service.resolveContactCode(service.ownContactCode()!!))
        assertEquals(ContactCodeResolution.Unknown, service.resolveContactCode(ContactCode.encode("deniz_yilmaz")))
        assertEquals(ContactCodeResolution.KeyMismatch, service.resolveContactCode(ContactCode.encode("lale", "00ff")))
        val laleCode = ContactCode.encode("lale", DesignFixtures.fixtureKeyFingerprint("lale"))
        assertEquals(ContactCodeResolution.Contact("lale"), service.resolveContactCode(laleCode))
        assertEquals(ContactCodeAddition.Verified(ChatId("lale")), service.addContact(laleCode))
        val lale = service.chats.value.first()
        assertTrue(lale.peer.isAcceptedContact)
        assertTrue(lale.peer.isVerified)
        assertEquals(ContactCodeAddition.Added(mert), service.addContact(ContactCode.encode("mert_k")))
        assertEquals(ContactCodeAddition.Failed, service.addContact(ContactCode.encode("deniz_yilmaz")))
        assertEquals("us2us:@selin_n#8a95e3f5cc758424b5e14c50e96acd0b", service.ownContactCode())
    }

    @Test
    fun `media payloads are the PDF, the voice hum, a download first`() = runTest {
        val service = service()
        val pdf = service.mediaPayload(MessageId("m1"), mert)!!
        assertEquals("application/pdf", pdf.contentType)
        assertEquals("kira-sozlesmesi.pdf", pdf.fileName)
        assertTrue(pdf.data.decodeToString().startsWith("%PDF-1.4"))
        val voice = service.mediaPayload(MessageId("d4"), deniz)!!
        assertEquals("audio/wav", voice.contentType)
        assertEquals(44 + 42 * 8_000 * 2, voice.data.size)
        // Without an encoder, the design's photo has no bytes (screens draw the artwork itself).
        assertNull(service.mediaPayload(MessageId("d2"), deniz))

        val file = service.addIncomingFile(mert, MediaAvailability.Remote)
        val payload = service.mediaPayload(file, mert)
        assertNotNull(payload)
        assertEquals(MediaAvailability.Ready, service.messages(mert).value.first { it.id == file }.mediaAvailability)
        service.setMediaAvailability(MediaAvailability.Unavailable, file, mert)
        assertNull(service.mediaPayload(file, mert))
    }

    @Test
    fun `an artwork encoder gives the design's photo and clip their bytes`() = runTest {
        val service = service()
        service.artworkEncoder = object : com.syconx.us2us.domain.harness.DesignArtworkEncoder {
            override suspend fun jpeg(artwork: com.syconx.us2us.domain.DesignArtwork, pixelWidth: Int) = Bytes.utf8("jpeg:${artwork.raw}:$pixelWidth")
            override suspend fun mp4(artwork: com.syconx.us2us.domain.DesignArtwork) = Bytes.utf8("mp4:${artwork.raw}")
        }
        assertEquals(Bytes.utf8("jpeg:nightPier:1536"), service.mediaPayload(MessageId("d2"), deniz)?.data)
        assertEquals(Bytes.utf8("mp4:sunsetVideo"), service.mediaPayload(MessageId("m2"), mert)?.data)
    }

    @Test
    fun `downloads fill in 24 steps`() = runTest {
        val service = service()
        val file = service.addIncomingFile(mert, MediaAvailability.Remote)
        service.downloadMedia(file, mert)
        assertEquals(MediaAvailability.Downloading(0.0), service.messages(mert).value.first { it.id == file }.mediaAvailability)
        // Background work runs as virtual time passes: 24 steps over 2.4 s.
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(MediaAvailability.Downloading(0.5), service.messages(mert).value.first { it.id == file }.mediaAvailability)
        advanceTimeBy(1_300)
        runCurrent()
        assertEquals(MediaAvailability.Ready, service.messages(mert).value.first { it.id == file }.mediaAvailability)
    }

    @Test
    fun `saving marks only exportable media`() = runTest {
        val service = service()
        service.markSaved(MessageId("m1"), mert)
        assertTrue(service.messages(mert).value.first { it.id == MessageId("m1") }.savedByMe)
        service.setDisappearing(com.syconx.us2us.domain.DisappearingTimer.FiveMinutes, MessageId("d2"), deniz)
        service.markSaved(MessageId("d2"), deniz)
        assertFalse(service.messages(deniz).value.first { it.id == MessageId("d2") }.savedByMe)
    }

    @Test
    fun `the contact screen pages the conversation and the archive`() = runTest {
        val service = service()
        val first = service.media(deniz, ChatMediaKind.Visual, before = null, limit = 30)
        assertEquals(30, first.size)
        assertEquals(11, service.media(deniz, ChatMediaKind.Visual, before = first.last().id, limit = 30).size)
        // The archive's nine files and the conversation's lease.
        assertEquals(10, service.media(mert, ChatMediaKind.Documents, before = null, limit = 30).size)
        assertTrue(service.media(ChatId("nobody"), ChatMediaKind.Visual, null, 30).isEmpty())
    }

    @Test
    fun `picked photos show at once, then go one after another`() = runTest {
        val service = service()
        val messages = com.syconx.us2us.domain.PickedMediaPlan.messages(DesignFixtures.pickedPhotos, "Dünkü yürüyüşten")
        service.sendPickedMedia(messages, deniz)
        val sent = service.messages(deniz).value.takeLast(3)
        assertTrue(sent.all { it.mediaAvailability == MediaAvailability.Preparing })
        assertEquals("Dünkü yürüyüşten", (sent.first().content as MessageContent.Photo).caption)
        runCurrent()
        assertTrue(service.messages(deniz).value.takeLast(3).all { it.mediaAvailability == MediaAvailability.Ready && it.status == com.syconx.us2us.domain.MessageStatus.Sent })
    }

    @Test
    fun `the live map's shares and own position`() = runTest {
        val service = service()
        assertEquals(listOf(MessageId("m4")), service.liveLocations(mert).value.shares.map { it.id })
        val demo = service.startDemoLiveLocation(mert)
        assertEquals(listOf(MessageId("m4"), demo), service.liveLocations(mert).value.shares.map { it.id })
        assertEquals(DesignFixtures.peerFixturePosition, service.liveLocations(mert).value.share(demo)?.position)
        assertEquals(OwnPositionState.Position(DesignFixtures.ownFixturePosition), service.ownPositionUpdates().first())
        service.ownPositionOverride = OwnPositionState.NeedsPermission
        assertEquals(OwnPositionState.NeedsPermission, service.ownPositionUpdates().first())
        assertTrue(service.requestOwnPositionAccess())
        service.ownPositionOverride = OwnPositionState.Denied
        assertFalse(service.requestOwnPositionAccess())
    }

    @Test
    fun `profile pictures`() = runTest {
        val service = service()
        service.setAvatar(AvatarEdit.Preset(7))
        assertEquals(7, service.ownAvatar.value.picture.presetId)
        assertEquals(AvatarSearchCopy.InSync, service.ownAvatar.value.searchCopy)
        try {
            service.setAvatar(AvatarEdit.Preset(99))
            fail("unknown preset")
        } catch (error: MessengerException.AvatarEdit) {
            assertEquals(AvatarEditError.Failed, error.error)
        }
        try {
            service.setAvatar(AvatarEdit.Photo(Bytes.utf8("not a photo at all"), CropRect(0.0, 0.0, 1.0, 1.0)))
            fail("unusable photo")
        } catch (error: MessengerException.AvatarEdit) {
            assertEquals(AvatarEditError.UnusablePhoto, error.error)
        }
        val jpeg = com.syconx.us2us.domain.design.DesignAvatars.photoAvatar.photo!!.data
        service.setAvatar(AvatarEdit.Photo(jpeg, CropRect(0.0, 0.0, 256.0, 256.0)))
        assertTrue(service.ownAvatar.value.picture.isPhoto)
        assertFalse(service.setAvatarShownInSearch(false))
        assertFalse(service.ownAvatar.value.showInSearch)
        assertEquals(14, service.searchAvatar("lale").presetId)
        assertEquals(2, service.searchAvatar("kaan").presetId)
        assertEquals(service.ownAvatar.value.picture, service.searchAvatar("selin_n"))
        val requests = service.requests.value
        assertEquals(21, service.revealAvatar(requests[0]).presetId)
        assertTrue(service.revealAvatar(requests[2]).isInitials)
    }

    @Test
    fun `restore access and account deletion knobs`() = runTest {
        val service = service()
        service.setRestoreAccessPending(true)
        service.restoreAccessAnswer = RestoreAccessCheck.Unanswered
        assertEquals(RestoreAccessCheck.Unanswered, service.checkRestoreAccess())
        assertTrue(service.restoreAccessPending.value)
        service.restoreAccessAnswer = RestoreAccessCheck.Closed
        assertEquals(RestoreAccessCheck.Closed, service.checkRestoreAccess())
        assertFalse(service.restoreAccessPending.value)
        service.deleteAccountOnRelay()
        assertEquals(1, service.accountDeletionsOnRelay)
        service.failsAccountDeletion = true
        try {
            service.deleteAccountOnRelay()
            fail("unreachable")
        } catch (_: MessengerException.RelayUnreachable) {
        }
    }

    @Test
    fun `biometrics come from the installed check or the harness`() = runTest {
        val service = service()
        assertFalse(service.unlockWithBiometrics("reason", "Kodla aç"))
        service.biometricCheck = com.syconx.us2us.domain.harness.BiometricCheck { _, cancel -> cancel == "Kodla aç" }
        assertTrue(service.unlockWithBiometrics("reason", "Kodla aç"))
        service.biometricsResult = false
        assertFalse(service.unlockWithBiometrics("reason", "Kodla aç"))
        service.biometricsResult = true
        assertFalse(service.setBiometricUnlock(false, "reason"))
        assertTrue(service.setBiometricUnlock(true, "reason"))
        assertEquals(com.syconx.us2us.domain.UnlockAttemptResult.Unlocked, service.enableBiometricUnlock(DesignFixtures.DEMO_SECRET_CODE))
    }

    @Test
    fun `the voice recording plays back from memory`() = runTest {
        val service = service()
        val audio = Bytes.utf8("m4a")
        val recording = com.syconx.us2us.domain.VoiceRecording(audio, 7.seconds, Bytes.ofUnsigned(List(50) { it * 5 }))
        assertTrue(service.sendVoice(recording, null, deniz))
        val sent = service.messages(deniz).value.last()
        assertEquals(30, (sent.content as MessageContent.Voice).waveform.size)
        assertEquals(audio, service.mediaPayload(sent.id, deniz)?.data)
        assertArrayEquals(audio.toByteArray(), service.mediaPayload(sent.id, deniz)!!.data.toByteArray())
        assertFalse(service.sendVoice(recording, null, ChatId("nobody")))
    }
}
