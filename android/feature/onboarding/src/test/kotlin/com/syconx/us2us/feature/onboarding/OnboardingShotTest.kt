package com.syconx.us2us.feature.onboarding

import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn screens of 01–02 against `ios/design/reference` and the layout JSON: 1.1–1.3 and
 * 2.1–2.3, dark and light, plus the English versions (no drawing; named by route). The frames are
 * the gate (±1 dp); `scripts/compare_shots.py` diffs the pictures for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class OnboardingShotTest {
    @get:Rule
    val host = ShotHost()

    private fun assertWelcomeChrome(ctaY: Float) {
        host.compose.assertAt(OnboardingTags.HEADER, 24f, 66f, 354f, 31f)
        host.compose.assertAt(OnboardingTags.PAGE_DOTS, 24f, ctaY - 32f, 354f, 6f)
    }

    @Test
    fun welcome() {
        for (theme in ShotTheme.entries) {
            host.shoot("1.1-karsilama", theme, "tr") { OnboardingApp("1.1") }
            assertWelcomeChrome(ctaY = 726f)
            host.compose.assertAt(OnboardingTags.WELCOME_TITLE, 24f, 481.5f, 354f, 94f)
            host.compose.assertAt(OnboardingTags.WELCOME_BODY, 24f, 591.5f, 354f, 76.5f)
            host.compose.assertAt(OnboardingTags.START, 24f, 726f, 354f, 54f)
            host.compose.assertAt(OnboardingTags.RESTORE, 24f, 790f, 354f, 44f)
        }
    }

    @Test
    fun noNotifications() {
        for (theme in ShotTheme.entries) {
            host.shoot("1.2-bildirim-yok", theme, "tr") { OnboardingApp("1.2") }
            assertWelcomeChrome(ctaY = 780f)
            host.compose.assertAt(OnboardingTags.CLOCK, 0f, 131f, 402f, 105.25f)
            host.compose.assertAt(OnboardingTags.GHOST_CARD, 20f, 262.25f, 362f, 68f)
            host.compose.assertAt(OnboardingTags.NOTIFICATIONS_TITLE, 24f, 413.84f, 354f, 81.59f)
            host.compose.assertAt(OnboardingTags.LEGEND, 24f, 619.44f, 354f, 102.56f)
            host.compose.assertAt(OnboardingTags.PAGE2_CTA, 24f, 780f, 354f, 54f)
        }
    }

    @Test
    fun silenceErases() {
        for (theme in ShotTheme.entries) {
            host.shoot("1.3-sessizlik-siler", theme, "tr") { OnboardingApp("1.3") }
            assertWelcomeChrome(ctaY = 780f)
            host.compose.assertAt(OnboardingTags.GHOST_KICKER, 107.48f, 161f, 187.03f, 17.05f)
            host.compose.assertAt(OnboardingTags.GHOST_METER, 57f, 274.05f, 288f, 8f)
            host.compose.assertAt(OnboardingTags.GHOST_TITLE, 24f, 459.73f, 354f, 81.59f)
            host.compose.assertAt(OnboardingTags.FEATURES, 24f, 641.33f, 354f, 80.67f)
            host.compose.assertAt(OnboardingTags.PAGE3_CTA, 24f, 780f, 354f, 54f)
        }
    }

    private fun assertSetupHeader(step: Int) {
        host.assertFrame(OnboardingTags.header(step), element = "r.0")
    }

    @Test
    fun username() {
        for (theme in ShotTheme.entries) {
            host.shoot("2.1-kullanici-adi", theme, "tr") { OnboardingApp("2.1") }
            assertSetupHeader(1)
            host.compose.assertAt(OnboardingTags.title(1), 24f, 118f, 354f, 73.44f)
            host.compose.assertAt(OnboardingTags.USERNAME_FIELD, 20f, 273.44f, 362f, 60f)
            // The counter's box is its own line (17.67); the design's span sits in the 20.14 caption line,
            // with the glyphs at the same place.
            host.compose.assertAt(OnboardingTags.USERNAME_COUNTER, 329.69f, 341.44f, 48.31f, 17.67f)
            host.compose.assertAt(OnboardingTags.rule(0), 24f, 375.58f, 354f, 18.89f)
            host.compose.assertAt(OnboardingTags.rule(1), 24f, 402.47f, 354f, 18.89f)
            host.compose.assertAt(OnboardingTags.rule(2), 24f, 429.36f, 354f, 18.89f)
            // The app lists four rules (the design three): the card sits one rule (26.89) lower than drawn.
            host.compose.assertAt(OnboardingTags.EXAMPLE_CARD, 20f, 470.25f + 26.89f, 362f, 145.27f)
            host.compose.assertAt(OnboardingTags.USERNAME_CONTINUE, 20f, 776f, 362f, 54f)
        }
    }

    @Test
    fun secretCode() {
        for (theme in ShotTheme.entries) {
            host.shoot("2.2-gizli-kod", theme, "tr") { OnboardingApp("2.2") }
            assertSetupHeader(2)
            host.compose.assertAt(OnboardingTags.title(2), 24f, 118f, 354f, 133.44f)
            host.compose.assertAt(OnboardingTags.CODE_DOTS, 106f, 295.44f, 190f, 15f)
            host.compose.assertAt(OnboardingTags.KEYPAD, 58f, 458f, 286f, 360f)
        }
    }

    @Test
    fun noWayBack() {
        for (theme in ShotTheme.entries) {
            host.shoot("2.3-geri-donus-yok", theme, "tr") { OnboardingApp("2.3") }
            assertSetupHeader(3)
            host.compose.assertAt(OnboardingTags.title(3), 24f, 118f, 354f, 71.72f)
            host.compose.assertAt(OnboardingTags.FACTS, 16f, 317.72f, 370f, 168f)
            host.compose.assertAt(OnboardingTags.FACE_ID, 16f, 497.72f, 370f, 64f)
            host.compose.assertAt(OnboardingTags.ACKNOWLEDGE, 24f, 715.97f, 354f, 42.03f)
            host.compose.assertAt(OnboardingTags.FINISH, 20f, 776f, 362f, 54f)
        }
    }

    @Test
    fun english() {
        for (state in listOf("1.1", "1.2", "1.3", "2.1", "2.2", "2.3")) {
            host.shoot("onboarding:$state", ShotTheme.Dark, "en") { OnboardingApp(state) }
        }
    }
}
