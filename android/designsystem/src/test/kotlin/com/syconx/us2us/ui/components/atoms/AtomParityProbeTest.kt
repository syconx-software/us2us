package com.syconx.us2us.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.FlagTag
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.U2Card
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.text.CSSLineClampText
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.icons.PhosphorGlyph
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.KeypadBottomLeading
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.OnboardingHeader
import com.syconx.us2us.ui.components.PageDots
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.UsernameFieldStatus
import com.syconx.us2us.ui.components.atoms.AtomShots.frame
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.Shots
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The atoms placed at their frames on four drawn screens (1.1, 2.1, 2.2, 3.2), in dark and light:
 * every measured frame must match the layout JSON within 1 dp (the gate), and the atoms' pixels are
 * compared with the same regions of the iOS references (for review; a loose bound catches a broken
 * render). Screens are built from atoms only, so the comparison regions leave out what the feature
 * packages draw (the 3D scene, the lock glyph). Side-by-sides: build/shots/android/designsystem/atoms-probe-*-compare.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AtomParityProbeTest {
    @get:Rule
    val host = AtomShots.host()

    // MARK: 1.1 — header, display heading, page dots, CTA, text button

    @Test fun welcomeDark() = welcome(ShotTheme.Dark)

    @Test fun welcomeLight() = welcome(ShotTheme.Light)

    private fun welcome(theme: ShotTheme) {
        probe("1.1-karsilama", theme, regions = listOf(region(0, 56, 402, 44), region(0, 470, 402, 370))) {
            OnboardingHeader(page = 1)
            U2DisplayText(R.string.onboarding_welcome_title, U2TextStyles.displayHero, Modifier.at(24, 481.5f).width(354.dp))
            U2Text(stringResource(R.string.onboarding_welcome_body), U2TextStyles.bodyL, Modifier.at(24, 591.5f).width(354.dp), color = U2Theme.colors.textSecondary)
            PageDots(count = 3, current = 0, modifier = Modifier.at(24, 694))
            U2PrimaryButton(stringResource(R.string.onboarding_welcome_start), onClick = {}, modifier = Modifier.at(24, 726).width(354.dp))
            U2TextButton(stringResource(R.string.onboarding_welcome_restore), onClick = {}, modifier = Modifier.at(24, 790).width(354.dp))
        }
        val layout = LayoutSpec.load("1.1-karsilama")
        AtomShots.assertFrames(
            layout,
            mapOf(
                "wordmark" to (frame(hasContentDescription("us2us")) to "r.2.0"),
                "pager" to (frame(hasContentDescription("Sayfa 1 / 3")) to "r.2.1"),
                "heading" to (frame(hasText("İki uç. Bir hat.")) to "r.4.0"),
                "start" to (frame(hasText("Başla"), merged = true) to "r.5.1"),
                "restore" to (frame(hasText("Yedekten geri yükle"), merged = true) to "r.5.2"),
            ),
        )
        val dots = frame(hasContentDescription("Sayfa 1 / 3", substring = false), merged = true, index = 1)
        assertNear("page dots", Frame(24f, 694f, 46f, 6f), dots)
    }

    // MARK: 2.1 — setup header, title, focused username field, CTA

    @Test fun usernameDark() = username(ShotTheme.Dark)

    @Test fun usernameLight() = username(ShotTheme.Light)

    private fun username(theme: ShotTheme) {
        probe("2.1-kullanici-adi", theme, regions = listOf(region(0, 56, 402, 44), region(0, 112, 402, 84), region(0, 262, 402, 82), region(0, 766, 402, 70))) {
            SetupHeader(step = 1, onBack = {})
            SetupTitle(R.string.onboarding_username_title, U2TextStyles.displayTitleM, Modifier.at(24, 118).width(354.dp))
            U2Text(stringResource(R.string.onboarding_username_body), U2TextStyles.body, Modifier.at(24, 203.44f).width(354.dp), color = U2Theme.colors.textSecondary)
            UsernameField("selin.n", onTextChange = {}, status = UsernameFieldStatus.Positive, showsFocusChrome = true, modifier = Modifier.at(20, 273.44f).width(362.dp))
            U2PrimaryButton(stringResource(R.string.onboarding_continue), onClick = {}, modifier = Modifier.at(20, 776).width(362.dp))
        }
        val layout = LayoutSpec.load("2.1-kullanici-adi")
        AtomShots.assertFrames(
            layout,
            mapOf(
                "back" to (frame(hasContentDescription("Geri"), merged = true) to "r.0.0"),
                "pager" to (frame(hasContentDescription("Adım 1 / 3")) to "r.0.1"),
                "title" to (frame(hasText("Kullanıcı adını seç")) to "r.1.0"),
                "continue" to (frame(hasText("Devam"), merged = true) to "r.6.0"),
            ),
        )
        // The name starts after “@” and the 2 dp gap; like “@” it is drawn 1 dp above its centred line
        // box, where the design's renderer painted the glyphs (the input fills the rest of the row).
        val name = frame(hasSetTextAction())
        val box = layout["r.2.0.1"].frame
        assertNear("name", box.copy(y = box.y - 1f, w = name.w), name, tolerance = 0.5f)
    }

    // MARK: 2.2 — setup header, title, code dots, keypad

    @Test fun codeDark() = code(ShotTheme.Dark)

    @Test fun codeLight() = code(ShotTheme.Light)

    private fun code(theme: ShotTheme) {
        probe("2.2-gizli-kod", theme, regions = listOf(region(0, 56, 402, 44), region(0, 112, 402, 84), region(90, 280, 222, 46), region(50, 450, 302, 376))) {
            SetupHeader(step = 2, onBack = {})
            SetupTitle(R.string.onboarding_code_title, U2TextStyles.displayTitleM, Modifier.at(24, 118).width(354.dp))
            U2Text(stringResource(R.string.onboarding_code_body), U2TextStyles.body, Modifier.at(24, 203.44f).width(354.dp), color = U2Theme.colors.textSecondary)
            Centered(295.44f) { CodeDots(filled = 4) }
            NumericKeypad(onDigit = {}, onDelete = {}, modifier = Modifier.at(58, 458))
        }
        val layout = LayoutSpec.load("2.2-gizli-kod")
        val keys = (1..9).associate { "key $it" to (frame(hasContentDescription("$it"), merged = true) to "r.4.${it - 1}") } +
            mapOf(
                "key 0" to (frame(hasContentDescription("0"), merged = true) to "r.4.10"),
                "delete" to (frame(hasContentDescription("Sil"), merged = true) to "r.4.11"),
            )
        AtomShots.assertFrames(
            layout,
            keys + mapOf(
                "back" to (frame(hasContentDescription("Geri"), merged = true) to "r.0.0"),
                "pager" to (frame(hasContentDescription("Adım 2 / 3")) to "r.0.1"),
                "title" to (frame(hasText("Gizli kodunu belirle")) to "r.1.0"),
            ),
        )
        val dots = frame(hasContentDescription("4 / 6 hane girildi"))
        assertNear("code dots", Frame(106f, 295.44f, 190f, 15f), dots)
    }

    // MARK: 3.2 — error dots, keypad with Face ID, “Kodu unuttum”

    @Test fun lockDark() = lock(ShotTheme.Dark)

    @Test fun lockLight() = lock(ShotTheme.Light)

    private fun lock(theme: ShotTheme) {
        probe("3.2-kod-yanlis", theme, regions = listOf(region(40, 166, 322, 46), region(90, 228, 222, 40), region(50, 406, 302, 376), region(0, 786, 402, 50))) {
            U2DisplayText(R.string.lock_code_title, U2TextStyles.displayTitleXS, Modifier.at(0, 174).fillMaxWidth(), textAlign = TextAlign.Center)
            Centered(240f) { CodeDots(filled = 6, appearance = CodeDotsAppearance.Error) }
            NumericKeypad(onDigit = {}, onDelete = {}, bottomLeading = KeypadBottomLeading.FaceId {}, modifier = Modifier.at(58, 414))
            U2TextButton(
                stringResource(R.string.lock_code_forgot),
                onClick = {},
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                modifier = Modifier.at(24, 788).width(354.dp),
            )
        }
        val layout = LayoutSpec.load("3.2-kod-yanlis")
        AtomShots.assertFrames(
            layout,
            mapOf(
                "faceId" to (frame(hasContentDescription("Biyometrik ile aç"), merged = true) to "r.3.0.9"),
                "key 0" to (frame(hasContentDescription("0"), merged = true) to "r.3.0.10"),
                "delete" to (frame(hasContentDescription("Sil"), merged = true) to "r.3.0.11"),
                "key 5" to (frame(hasContentDescription("5"), merged = true) to "r.3.0.4"),
                "forgot" to (frame(hasText("Kodu unuttum"), merged = true) to "r.4.0"),
            ),
        )
    }

    // MARK: 4.3 — centred-title header, privacy banner, clamped snippet, flag tags, decision buttons

    @Test fun requestsDark() = requests(ShotTheme.Dark)

    @Test fun requestsLight() = requests(ShotTheme.Light)

    private fun requests(theme: ShotTheme) {
        probe("4.3-mesaj-istekleri", theme, regions = listOf(region(0, 50, 402, 56), region(10, 110, 382, 80), region(80, 232, 300, 50), region(26, 286, 352, 52), region(80, 596, 300, 26))) {
            CenteredTitleHeader(
                stringResource(R.string.common_chats_requests_title),
                leading = { U2GlassIconButton(PhosphorGlyph.CaretLeft, "Geri", onClick = {}) },
                trailing = { U2GlassIconButton(PhosphorGlyph.DotsThree, "Daha fazla", onClick = {}) },
            )
            InfoBanner(stringResource(R.string.chats_requests_banner), Modifier.at(16, 116).width(370.dp))
            for (card in listOf(197.13f to 148.84f, 355.97f to 148.81f, 514.78f to 169.11f)) {
                U2Card(Modifier.at(16, card.first).width(370.dp).height(card.second.dp)) {}
            }
            CSSLineClampText(
                "Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin?",
                U2TextStyles.requestSnippet,
                Modifier.at(88, 238.38f).width(284.dp),
                color = U2Theme.colors.text.copy(alpha = 0.84f),
            )
            CSSLineClampText(
                "Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/…",
                U2TextStyles.requestSnippet,
                Modifier.at(88, 556.03f).width(284.dp),
                color = U2Theme.colors.text.copy(alpha = 0.84f),
            )
            FlagTag(PhosphorGlyph.EyeSlash, "Medya kabul edene kadar gizli", Modifier.at(88, 421.52f))
            FlagTag(PhosphorGlyph.Warning, "Bağlantı içeriyor · açılmaz", Modifier.at(88, 600.63f), color = U2Theme.colors.danger)
            for ((index, y) in listOf(291.97f, 450.78f, 629.89f).withIndex()) {
                U2FlexRow(Modifier.at(32, y).width(340.dp)) {
                    U2DecisionButton(U2DecisionKind.Destructive, "Engelle", onClick = {}, modifier = Modifier.testTag("block$index"))
                    U2DecisionButton(U2DecisionKind.Neutral, "Sil", onClick = {}, modifier = Modifier.testTag("delete$index"))
                    U2DecisionButton(U2DecisionKind.Accept, "Kabul et", onClick = {}, modifier = Modifier.u2Flex(1.3f, border = 2.dp).testTag("accept$index"))
                }
            }
        }
        val layout = LayoutSpec.load("4.3-mesaj-istekleri")
        val buttons = (0..2).flatMap { card ->
            listOf("block" to 0, "delete" to 1, "accept" to 2).map { (kind, slot) ->
                "$kind$card" to (frame(hasTestTag("$kind$card")) to "r.2.$card.1.$slot")
            }
        }.toMap()
        AtomShots.assertFrames(
            layout,
            buttons + mapOf(
                "back" to (frame(hasContentDescription("Geri"), merged = true) to "r.0.0"),
                "more" to (frame(hasContentDescription("Daha fazla"), merged = true) to "r.0.2"),
                "banner" to (frame(hasText(bannerText(), substring = true), merged = true) to "r.1"),
                "snippet" to (frame(hasContentDescription("Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin?"), merged = true) to "r.2.0.0.1.1"),
            ),
        )
        val flag = frame(hasText("Medya kabul edene kadar gizli"), merged = true)
        assertNear("flag tag", Frame(88f, 421.52f, flag.w, 16.27f), flag)
    }

    private fun bannerText(): String = "Kabul edene kadar sohbet açılmaz."

    // MARK: Helpers

    private fun Modifier.at(x: Number, y: Number): Modifier = offset(x.toFloat().dp, y.toFloat().dp)

    @Composable
    private fun BoxScope.Centered(y: Float, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().offset(y = y.dp), contentAlignment = Alignment.TopCenter) { content() }
    }

    private fun region(x: Int, y: Int, w: Int, h: Int) = intArrayOf(x * 3, y * 3, w * 3, h * 3)

    private fun frame(matcher: SemanticsMatcher, merged: Boolean = false, index: Int = 0): Frame {
        val nodes = host.compose.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes()
        check(nodes.size > index) { "${nodes.size} nodes match ${matcher.description}" }
        return nodes[index].frame()
    }

    private fun assertNear(label: String, expected: Frame, actual: Frame, tolerance: Float = 1f) {
        val ok = listOf(actual.x - expected.x, actual.y - expected.y, actual.w - expected.w, actual.h - expected.h).all { kotlin.math.abs(it) <= tolerance }
        assertTrue("$label: expected [$expected], got [$actual]", ok)
    }

    private fun probe(screen: String, theme: ShotTheme, regions: List<IntArray>, content: @Composable BoxScope.() -> Unit) {
        val name = "probe-$screen"
        val shot = host.shoot(name, theme, "tr") {
            Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) { content() }
        }
        val reference = AtomShots.reference(screen, theme)
        val report = StringBuilder()
        var worst = 0.0
        regions.forEachIndexed { index, region ->
            val diff = Shots.compare(shot, reference, region, "atoms-$name-${theme.raw}-$index")
            report.append("$name ${theme.raw} region $index ${region.joinToString(",")}: $diff\n")
            worst = maxOf(worst, diff.meanAbsolute)
        }
        print(report)
        File(AtomShots.outputDir, "$name-${theme.raw}.txt").apply { parentFile?.mkdirs() }.writeText(report.toString())
        assertTrue("$name ${theme.raw}: a region is far from the reference\n$report", worst < 12.0)
    }
}
