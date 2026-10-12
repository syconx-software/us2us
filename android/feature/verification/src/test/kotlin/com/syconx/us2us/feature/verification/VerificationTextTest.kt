package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.SafetyNumberInfo
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.l10n.U2Strings
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The texts 06 builds, in both languages (iOS VerificationText and the live mapping's “no date”). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ShotHost.SDK])
class VerificationTextTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val at = Instant.parse("2026-10-08T15:52:00Z")

    private fun strings(language: String): U2Strings {
        RuntimeEnvironment.setQualifiers(if (language == "tr") "tr-rTR" else "en-rUS")
        val resources = RuntimeEnvironment.getApplication().resources
        return U2Strings.wrap(resources, Locale.forLanguageTag(if (language == "tr") "tr-TR" else "en-US"))
    }

    private fun info(method: VerificationMethod?, verifiedAt: Instant?, withoutRecord: Boolean = false) = SafetyNumberInfo(
        groups = DesignFixtures.safetyNumberGroups,
        scannablePayload = DesignFixtures.safetyNumberPayload,
        verifiedAt = verifiedAt,
        method = method,
        isVerifiedWithoutRecord = withoutRecord,
    )

    @Test
    fun theVerifiedPillSaysWhenAndHow() {
        val tr = strings("tr")
        assertEquals("8 Ekim 2026 · 18:52 · yüz yüze", VerificationText.verifiedLine(info(VerificationMethod.FaceToFace, at), tr, zone))
        assertEquals("8 Ekim 2026 · 18:52 · elle işaretlendi", VerificationText.verifiedLine(info(VerificationMethod.Manual, at), tr, zone))
        assertEquals("Doğrulandı · 8 Ekim 2026 · 18:52", VerificationText.verifiedSince(info(VerificationMethod.FaceToFace, at), tr, zone))
        val en = strings("en")
        assertEquals("8 October 2026 · 18:52 · face to face", VerificationText.verifiedLine(info(VerificationMethod.FaceToFace, at), en, zone))
    }

    @Test
    fun aVerificationWithoutARecordShowsNoDate() {
        val tr = strings("tr")
        val restored = info(method = null, verifiedAt = null, withoutRecord = true)
        assertNull(VerificationText.verifiedLine(restored, tr, zone))
        assertEquals("Doğrulandı", VerificationText.verifiedSince(restored, tr, zone))
    }

    @Test
    fun theSharedNumberIsTheHeaderAndThreeRows() {
        val text = VerificationText.shareText("selin_n", "deniz", DesignFixtures.safetyNumberGroups, strings("tr"))
        assertEquals(
            listOf(
                "us2us güvenlik numarası · selin_n ↔ deniz",
                "",
                "08223 78626 74024 23263",
                "12493 59214 00841 88304",
                "26120 14864 87953 64169",
            ),
            text.lines(),
        )
        assertEquals(Bytes.utf8(DesignFixtures.safetyNumberGroups.joinToString("")), DesignFixtures.safetyNumberPayload)
    }
}
