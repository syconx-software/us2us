package com.syconx.us2us.ui.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.assertSize
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CountBadge
import com.syconx.us2us.ui.components.FaceIdGlyph
import com.syconx.us2us.ui.components.FloatingMenu
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuMetrics
import com.syconx.us2us.ui.components.HeaderSpacer
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.OnboardingHeader
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.PageDots
import com.syconx.us2us.ui.components.PasswordField
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.SetupHeader
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.components.SystemPill
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2PrimaryButtonSize
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.UsernameFieldStatus
import com.syconx.us2us.ui.components.UsernameSearchField
import com.syconx.us2us.ui.components.atoms.AtomShots.frameOf
import com.syconx.us2us.ui.components.text.SetupTitle
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.tokens.U2TextStyles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every atom laid out with the design's data measures what the design measures: the iOS
 * ComponentMetricsTests numbers (Chrome's getBoundingClientRect) plus the inventory's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AtomMetricsTest {
    @get:Rule
    val host = AtomShots.host()

    private fun show(content: @Composable () -> Unit) {
        host.render {
            Column(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }

    @Composable
    private fun Tagged(tag: String, width: Int? = null, content: @Composable () -> Unit) {
        Box(Modifier.testTag(tag).then(if (width != null) Modifier.width(width.dp) else Modifier)) { content() }
    }

    @Test
    fun buttonsHaveTheirHeights() {
        show {
            Tagged("primary", 354) { U2PrimaryButton("Başla", onClick = {}) }
            Tagged("secondary", 354) { U2SecondaryButton("Kodla aç", onClick = {}, glyph = PhosphorGlyph.Password) }
            Tagged("inCard", 338) { U2PrimaryButton("Mesaj yaz", onClick = {}, size = U2PrimaryButtonSize.InCard) }
            Tagged("bar", 338) { U2PrimaryButton("Kabul et", onClick = {}, size = U2PrimaryButtonSize.Bar) }
            Tagged("text", 354) { U2TextButton("Yedekten geri yükle", onClick = {}) }
            Tagged("glass") { U2GlassIconButton(PhosphorGlyph.QrCode, "QR", onClick = {}) }
            Tagged("glassChat") { U2GlassIconButton(PhosphorGlyph.CaretLeft, "Back", onClick = {}, diameter = 40.dp, glyphSize = 20.dp) }
            Tagged("glassSheet") { U2GlassIconButton(PhosphorGlyph.X, "Close", onClick = {}, diameter = 36.dp, glyphSize = 17.dp) }
        }
        host.compose.assertSize("primary", 354.dp, 54.dp)
        host.compose.assertSize("secondary", 354.dp, 54.dp)
        host.compose.assertSize("inCard", 338.dp, 48.dp)
        host.compose.assertSize("bar", 338.dp, 50.dp)
        host.compose.assertSize("text", 354.dp, 44.dp)
        host.compose.assertSize("glass", 44.dp, 44.dp)
        host.compose.assertSize("glassChat", 40.dp, 40.dp)
        host.compose.assertSize("glassSheet", 36.dp, 36.dp)
    }

    @Test
    fun codeDotsKeypadAndPageDots() {
        show {
            Tagged("dots") { CodeDots(filled = 4) }
            Tagged("keypad") { NumericKeypad(onDigit = {}, onDelete = {}) }
            Tagged("pageDots") { PageDots(count = 3, current = 0) }
            Tagged("faceId") { FaceIdGlyph(size = 30.dp, lineWidth = 1.6f) }
        }
        host.compose.assertSize("dots", 190.dp, 15.dp)
        host.compose.assertSize("keypad", 286.dp, 360.dp)
        host.compose.assertSize("pageDots", 46.dp, 6.dp)
        host.compose.assertSize("faceId", 30.dp, 30.dp)
    }

    @Test
    fun badgeAndPill() {
        show {
            Tagged("badge2") { CountBadge(count = 2) }
            Tagged("badge12") { CountBadge(count = 12) }
            Tagged("pill") { SystemPill("Ghost mod açık · 24 saat") }
        }
        host.compose.assertSize("badge2", 22.dp, 22.dp)
        host.compose.assertSize("badge12", null, 22.dp)
        host.compose.assertSize("pill", 229.81.dp, 27.39.dp)
    }

    @Test
    fun cards() {
        show {
            Tagged("banner", 370) { InfoBanner("Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler.") }
            Tagged("option", 350) {
                OptionCard(PhosphorGlyph.FileLock, "Yedekten geri yükle", "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.", onClick = {})
            }
            Tagged("note", 370) {
                NoteCard(
                    "İletilemedi ne demek?",
                    "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve ona hiç ulaşmaz. Mesajın altında “Tekrar gönder” belirir.",
                )
            }
        }
        host.compose.assertSize("banner", 370.dp, 67.13.dp)
        host.compose.assertSize("option", 350.dp, 92.17.dp)
        host.compose.assertSize("note", 370.dp, 116.55.dp)
    }

    @Test
    fun fieldsAndControls() {
        show {
            Tagged("username", 362) { UsernameField("selin_n", onTextChange = {}, status = UsernameFieldStatus.Positive) }
            Tagged("search", 362) { UsernameSearchField("selin_n", onTextChange = {}) }
            Tagged("password", 362) { PasswordField("correct horse", onTextChange = {}) }
            Tagged("chips", 366) {
                SegmentedChips(listOf("1 sa", "6 sa", "24 sa", "3 gün", "7 gün"), selection = "24 sa", onSelect = {}) { it }
            }
            Tagged("toggle", 51) { U2Toggle(checked = true, onCheckedChange = {}) }
        }
        host.compose.assertSize("username", 362.dp, 60.dp)
        host.compose.assertSize("search", 362.dp, 56.dp)
        host.compose.assertSize("password", 362.dp, 56.dp)
        host.compose.assertSize("chips", 366.dp, 40.dp)
        host.compose.assertSize("toggle", 51.dp, 31.dp)
    }

    @Test
    fun displayTextLineBoxes() {
        show {
            // 1.1: Doto 46/46 with Inter 700 full stops: two 47 dp lines.
            Tagged("hero", 354) { U2DisplayText("İki uç. Bir hat.", U2TextStyles.displayHero) }
            // 2.2: two lines painted 36 apart, the block 2 × 36.72 tall.
            Tagged("setup", 354) { SetupTitle("Gizli kodunu belirle", U2TextStyles.displayTitleM) }
            // 2.3: the “.” line holds the fraction: 71.0, not 71 + 2 × 0.36.
            Tagged("setupStop", 354) { SetupTitle("Geri dönüş yok.", U2TextStyles.displayTitleM) }
            Tagged("single", 354) { U2DisplayText("Gizli kodunu gir", U2TextStyles.displayTitleXS) }
        }
        host.compose.assertSize("hero", 354.dp, 94.dp)
        host.compose.assertSize("setup", 354.dp, 73.44.dp)
        host.compose.assertSize("single", 354.dp, 30.dp)
        val stop = host.compose.frameOf(hasTestTag("setupStop"))
        // One line if it fits, else two: either way the block is the CSS height.
        assertEquals(if (stop.h > 50f) 73.44f else 37f, stop.h, 1f)
    }

    @Test
    fun flexRowSharesLikeCss() {
        show {
            Tagged("cardRow", 340) {
                U2FlexRow {
                    Box(Modifier.testTag("block").fillMaxWidth().height(40.dp))
                    Box(Modifier.testTag("delete").fillMaxWidth().height(40.dp))
                    Box(Modifier.testTag("accept").u2Flex(1.3f, border = 2.dp).fillMaxWidth().height(40.dp))
                }
            }
            Tagged("barRow", 370) {
                U2FlexRow {
                    U2DecisionButton(U2DecisionKind.Destructive, "Engelle", onClick = {}, modifier = Modifier.testTag("barBlock"))
                    U2DecisionButton(U2DecisionKind.Neutral, "Sil", onClick = {}, modifier = Modifier.testTag("barDelete"))
                    U2DecisionButton(U2DecisionKind.Accept, "Kabul et", onClick = {}, modifier = Modifier.u2Flex(1.4f, border = 2.dp).testTag("barAccept"))
                }
            }
        }
        host.compose.assertSize("block", 97.58.dp, 40.dp, tolerance = 0.5.dp)
        host.compose.assertSize("delete", 97.58.dp, 40.dp, tolerance = 0.5.dp)
        host.compose.assertSize("accept", 128.84.dp, 40.dp, tolerance = 0.5.dp)
        host.compose.assertSize("barBlock", 103.53.dp, 40.dp, tolerance = 0.5.dp)
        host.compose.assertSize("barAccept", 146.94.dp, 40.dp, tolerance = 0.5.dp)
        val accept = host.compose.frameOf(hasTestTag("accept"))
        assertEquals("the last child ends at the row's end", 340f, accept.x + accept.w, 0.5f)
    }

    @Test
    fun headersSitAtTheirDesignRows() {
        show {
            Tagged("onboarding") { OnboardingHeader(page = 1) }
        }
        val onboarding = host.compose.frameOf(hasTestTag("onboarding"))
        // Row at design y 66 (the safe area is the design's 62 here), 31 high (the wordmark's line box).
        assertEquals(97f, onboarding.y + onboarding.h, 0.5f)

        host.render {
            Column {
                Box(Modifier.testTag("setupHeader")) { SetupHeader(step = 2, onBack = {}) }
            }
        }
        val setup = host.compose.frameOf(hasTestTag("setupHeader"))
        assertEquals(100f, setup.y + setup.h, 0.5f)

        host.render {
            Column {
                Box(Modifier.testTag("centred")) {
                    CenteredTitleHeader("Mesaj istekleri", leading = { HeaderSpacer() })
                }
                Box(Modifier.testTag("sheetBar")) { SheetBar("Yeni sohbet", "Vazgeç", onCancel = {}) }
                Box(Modifier.testTag("sheetClose")) { SheetCloseHeader("Mesaj bilgisi", onClose = {}) }
            }
        }
        val centred = host.compose.frameOf(hasTestTag("centred"))
        assertEquals(100f, centred.y + centred.h, 0.5f)
        host.compose.assertSize("sheetBar", 402.dp, 48.dp)
        host.compose.assertSize("sheetClose", 402.dp, 40.dp)
    }

    @Test
    fun floatingMenuHeight() {
        val items = listOf(
            FloatingMenuItem("reply", "Yanıtla", PhosphorGlyph.ArrowBendUpLeft) {},
            FloatingMenuItem("copy", "Kopyala", PhosphorGlyph.Copy) {},
            FloatingMenuItem("info", "Bilgi", PhosphorGlyph.Info) {},
            FloatingMenuItem("delete", "Benden sil", PhosphorGlyph.Trash, startsGroup = true) {},
        )
        show { Tagged("menu") { FloatingMenu(items) } }
        val expected = FloatingMenuMetrics.height(items)
        assertEquals(46f * 4 + 2 + 2 + 7, expected.value, 0.001f)
        host.compose.assertSize("menu", 250.dp, expected)
    }
}
