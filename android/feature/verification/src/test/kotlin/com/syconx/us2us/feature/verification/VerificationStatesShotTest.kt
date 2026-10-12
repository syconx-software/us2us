package com.syconx.us2us.feature.verification

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotOptions
import com.syconx.us2us.shot.ShotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every 06 harness state the design does not draw, rendered under its route name
 * (`safety_deniz_<state>`) in dark and light (and a few in English), for the iOS captures of the
 * same routes; each shot also checks that the state shows what it should.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class VerificationStatesShotTest {
    private var rig = VerificationRig()

    @get:Rule
    val host = ShotHost(environment = verificationShots { rig })

    private fun shoot(state: String, theme: ShotTheme, lang: String = "tr") {
        rig = VerificationRig(english = lang == "en")
        val route = "safety:deniz/$state"
        rig.apply(route)
        host.shoot(route, theme, lang, options = ShotOptions(settleMillis = 3_000)) { VerificationApp(rig) }
    }

    private fun shows(tag: String): Boolean =
        host.compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun expect(state: String, tag: String) {
        assertTrue("$state should show $tag", shows(tag))
    }

    @Test
    fun contactScreen() {
        for (theme in ShotTheme.entries) {
            shoot("photos", theme)
            expect("photos", VerificationTags.tile("d2"))
            shoot("documents", theme)
            expect("documents", VerificationTags.DOCUMENTS)
            shoot("menu", theme)
            expect("menu", "menu.share")
        }
        rig = VerificationRig()
        rig.apply("safety:kaan/photos")
        host.shoot("safety:kaan/photos", ShotTheme.Dark, "tr") { VerificationApp(rig) }
        expect("kaan/photos", VerificationTags.PHOTOS_EMPTY)
        rig = VerificationRig()
        rig.apply("safety:kaan/documents")
        host.shoot("safety:kaan/documents", ShotTheme.Dark, "tr") { VerificationApp(rig) }
        expect("kaan/documents", VerificationTags.DOCUMENTS_EMPTY)
    }

    @Test
    fun safetyNumberStates() {
        for (theme in ShotTheme.entries) {
            shoot("safety-verified", theme)
            expect("safety-verified", VerificationTags.VERIFIED_SINCE)
            shoot("changed", theme)
            expect("changed", VerificationTags.CHANGED)
            expect("changed", VerificationTags.ACCEPT_CHANGE)
            shoot("confirm-accept", theme)
            expect("confirm-accept", VerificationTags.CONFIRM)
            shoot("confirm", theme)
            expect("confirm", VerificationTags.CONFIRM)
            shoot("confirm-remove", theme)
            expect("confirm-remove", "confirm.destructive")
        }
        shoot("safety", ShotTheme.Dark, "en")
        expect("safety en", VerificationTags.SCAN)
        shoot("changed", ShotTheme.Dark, "en")
        expect("changed en", VerificationTags.CHANGED)
    }

    @Test
    fun scannerStates() {
        for (theme in ShotTheme.entries) {
            shoot("mismatch", theme)
            expect("mismatch", VerificationTags.WARNING)
            shoot("mycode", theme)
            expect("mycode", VerificationTags.OWN_CODE)
            shoot("denied", theme)
            expect("denied", VerificationTags.DENIED)
            shoot("torch", theme)
            host.compose.onNodeWithTag(VerificationTags.TORCH, useUnmergedTree = true).assert(isSelected())
        }
        shoot("scan", ShotTheme.Dark, "en")
        expect("scan en", VerificationTags.SIMULATOR_MOCK)
    }

    @Test
    fun verifiedStates() {
        for (theme in ShotTheme.entries) {
            shoot("verified-manual", theme)
            expect("verified-manual", VerificationTags.VERIFIED_PILL)
        }
        shoot("verified", ShotTheme.Dark, "en")
        expect("verified en", VerificationTags.VERIFIED_PILL)
    }

    @Test
    fun addByQrStates() {
        for (theme in ShotTheme.entries) {
            shoot("contact-scan", theme)
            expect("contact-scan", VerificationTags.SIMULATOR_MOCK)
            shoot("contact-unknown", theme)
            expect("contact-unknown", VerificationTags.WARNING)
            shoot("contact-own", theme)
            expect("contact-own", VerificationTags.WARNING)
            shoot("contact-mycode", theme)
            expect("contact-mycode", VerificationTags.OWN_CODE)
            shoot("contact-verified", theme)
            expect("contact-verified", VerificationTags.VERIFIED_PILL)
        }
    }
}
