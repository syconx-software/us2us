package com.syconx.us2us.feature.verification

import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn screens of 06 against `ios/design/reference` and the layout JSON: 6.1, 6.2 and 6.3, dark
 * and light. The frames are the gate (±1 dp); `scripts/compare_shots.py` diffs the pictures.
 *
 * 6.1 is the contact screen's first tab, as on iOS: its header keeps the design's buttons (“…” in
 * the share button's place), and the tabs under it move the drawn content down by 50 dp (16 + 40 +
 * 20 − 26); the bottom buttons keep the design's frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class VerificationShotTest {
    private var rig = VerificationRig()

    @get:Rule
    val host = ShotHost(environment = verificationShots { rig })

    private fun shoot(name: String, route: String, theme: ShotTheme, lang: String = "tr") {
        rig = VerificationRig(english = lang == "en")
        rig.apply(route)
        host.shoot(name, theme, lang) { VerificationApp(rig) }
    }

    @Test
    fun safetyNumber() {
        for (theme in ShotTheme.entries) {
            shoot("6.1-guvenlik-numarasi", "safety:deniz/safety", theme)
            val frames = Frames(host.compose, LayoutSpec.load("6.1-guvenlik-numarasi"))
            frames.check(VerificationTags.BACK, "r.0.0")
            frames.check(VerificationTags.MORE, "r.0.2")
            frames.check(VerificationTags.LINE, "r.1.0", dy = TABS_SHIFT)
            frames.check(VerificationTags.QR, "r.2", dy = TABS_SHIFT)
            for (index in 0 until 12) frames.check(VerificationTags.digits(index), "r.3.$index", dy = TABS_SHIFT)
            frames.check(VerificationTags.SCAN, "r.5.0")
            frames.check(VerificationTags.MARK_VERIFIED, "r.5.1")
            frames.assertAll()
        }
    }

    @Test
    fun scanner() {
        for (theme in ShotTheme.entries) {
            shoot("6.2-qr-tarama", "safety:deniz/scan", theme)
            val frames = Frames(host.compose, LayoutSpec.load("6.2-qr-tarama"))
            frames.check(VerificationTags.SCAN_CLOSE, "r.2.0")
            frames.check(VerificationTags.TORCH, "r.2.2")
            frames.check(VerificationTags.VIEWFINDER, "r.3.0")
            for (index in 1..4) frames.check(VerificationTags.corner(index), "r.3.0.$index")
            frames.check(VerificationTags.SCAN_LINE, "r.3.0.5")
            frames.check(VerificationTags.HINT, "r.4.0")
            frames.check(VerificationTags.SHOW_MY_CODE, "r.4.1")
            frames.assertAll()
        }
    }

    @Test
    fun verified() {
        for (theme in ShotTheme.entries) {
            shoot("6.3-dogrulandi", "safety:deniz/verified", theme)
            val frames = Frames(host.compose, LayoutSpec.load("6.3-dogrulandi"))
            frames.check(VerificationTags.VERIFIED_CLOSE, "r.1.0.0")
            frames.check(VerificationTags.VERIFIED_LINE, "r.2.0")
            frames.check(VerificationTags.VERIFIED_TEXT, "r.2.1")
            frames.check(VerificationTags.VERIFIED_TITLE, "r.2.1.0")
            frames.check(VerificationTags.VERIFIED_BODY, "r.2.1.1")
            frames.check(VerificationTags.VERIFIED_PILL, "r.2.2")
            frames.check(VerificationTags.VERIFIED_BACK, "r.3.0")
            frames.assertAll()
        }
    }

    private companion object {
        /** The contact screen's tabs above 6.1: 16 + 40 + 20 below the header, where the design has 26. */
        const val TABS_SHIFT = 50f
    }
}
