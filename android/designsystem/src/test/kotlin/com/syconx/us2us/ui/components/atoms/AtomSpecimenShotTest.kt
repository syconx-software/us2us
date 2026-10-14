package com.syconx.us2us.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.CountBadge
import com.syconx.us2us.ui.components.EraseConfirmation
import com.syconx.us2us.ui.components.EraseScope
import com.syconx.us2us.ui.components.FaceIdGlyph
import com.syconx.us2us.ui.components.FlagTag
import com.syconx.us2us.ui.components.FloatingBanner
import com.syconx.us2us.ui.components.FloatingBannerKind
import com.syconx.us2us.ui.components.FloatingMenu
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuTone
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.InfoPill
import com.syconx.us2us.ui.components.MonoKicker
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.OptionCardTone
import com.syconx.us2us.ui.components.PageDots
import com.syconx.us2us.ui.components.PasswordField
import com.syconx.us2us.ui.components.PasswordFieldTrailing
import com.syconx.us2us.ui.components.PulseRings
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.SheetCloseHeader
import com.syconx.us2us.ui.components.SystemPill
import com.syconx.us2us.ui.components.U2CameraPillButton
import com.syconx.us2us.ui.components.U2Checkbox
import com.syconx.us2us.ui.components.U2DangerButton
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2DecisionSize
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2GlassSurface
import com.syconx.us2us.ui.components.U2InlinePillButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.components.U2Wordmark
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.UsernameFieldStatus
import com.syconx.us2us.ui.components.UsernameSearchField
import com.syconx.us2us.ui.components.text.CSSLineClampText
import com.syconx.us2us.ui.components.text.GreedyParagraph
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every atom on three specimen pages, in dark and light (the iOS previews and component gallery):
 * build/shots/android/designsystem/atoms/<theme>-tr/atoms-<page>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class AtomSpecimenShotTest {
    @get:Rule
    val host = AtomShots.host()

    @Test fun buttonsDark() = page("atoms-buttons", ShotTheme.Dark) { Buttons() }

    @Test fun buttonsLight() = page("atoms-buttons", ShotTheme.Light) { Buttons() }

    @Test fun entryDark() = page("atoms-entry", ShotTheme.Dark) { Entry() }

    @Test fun entryLight() = page("atoms-entry", ShotTheme.Light) { Entry() }

    @Test fun cardsDark() = page("atoms-cards", ShotTheme.Dark) { CardsAndMenus() }

    @Test fun cardsLight() = page("atoms-cards", ShotTheme.Light) { CardsAndMenus() }

    @Test fun headersDark() = page("atoms-headers", ShotTheme.Dark) { HeadersAndText() }

    @Test fun headersLight() = page("atoms-headers", ShotTheme.Light) { HeadersAndText() }

    @Test fun confirmationsDark() = page("atoms-confirmations", ShotTheme.Dark) { Confirmations() }

    @Test fun confirmationsLight() = page("atoms-confirmations", ShotTheme.Light) { Confirmations() }

    private fun page(name: String, theme: ShotTheme, content: @Composable ColumnScope.() -> Unit) {
        val file = host.shoot(name, theme, "tr") {
            Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
                // Children keep their own heights; a page that runs long is cut at the screen's edge.
                Column(
                    Modifier
                        .padding(start = 20.dp, top = 66.dp, end = 20.dp)
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
        val image = javax.imageio.ImageIO.read(file)
        assertEquals(1206, image.width)
        assertEquals(2622, image.height)
    }

    @Composable
    private fun Buttons() {
        U2PrimaryButton("Başla", onClick = {})
        U2PrimaryButton("Kodu tara", onClick = {}, glyph = PhosphorGlyph.Scan)
        U2PrimaryButton("Başla", onClick = {}, enabled = false)
        U2SecondaryButton("Kodla aç", onClick = {}, glyph = PhosphorGlyph.Password)
        U2DangerButton("Her şeyi sil", onClick = {}, glyph = PhosphorGlyph.Trash)
        U2DangerButton("Hesap siliniyor…", onClick = {}, isWorking = true, enabled = false)
        U2TextButton("Yedekten geri yükle", onClick = {})
        U2TextButton("Kodu unuttum", onClick = {}, tone = U2TextButtonTone.Accent)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            U2GlassIconButton(PhosphorGlyph.QrCode, "QR", onClick = {})
            U2GlassIconButton(PhosphorGlyph.NotePencil, "Yeni", onClick = {}, tint = U2Theme.colors.accent300)
            U2GlassIconButton(PhosphorGlyph.CaretLeft, "Geri", onClick = {}, diameter = 40.dp, glyphSize = 20.dp)
            U2GlassIconButton(PhosphorGlyph.X, "Kapat", onClick = {}, diameter = 36.dp, glyphSize = 17.dp)
            Box(Modifier.background(U2Theme.colors.mediaViewerBg, CircleShape).padding(4.dp)) {
                U2GlassIconButton(PhosphorGlyph.X, "Kapat", onClick = {}, surface = U2GlassSurface.Media)
            }
            Box(Modifier.background(U2Theme.colors.cameraBg, CircleShape).padding(4.dp)) {
                U2GlassIconButton(PhosphorGlyph.X, "Kapat", onClick = {}, surface = U2GlassSurface.Camera)
            }
        }
        U2FlexRow {
            U2DecisionButton(U2DecisionKind.Destructive, "Engelle", onClick = {})
            U2DecisionButton(U2DecisionKind.Neutral, "Sil", onClick = {})
            U2DecisionButton(U2DecisionKind.Accept, "Kabul et", onClick = {}, modifier = Modifier.u2Flex(1.3f, border = 2.dp))
        }
        U2FlexRow {
            U2DecisionButton(U2DecisionKind.Destructive, "Engelle", onClick = {}, size = U2DecisionSize.Bar)
            U2DecisionButton(U2DecisionKind.Neutral, "Sil", onClick = {}, size = U2DecisionSize.Bar)
            U2DecisionButton(U2DecisionKind.Accept, "Kabul et", onClick = {}, size = U2DecisionSize.Bar, modifier = Modifier.u2Flex(1.4f, border = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(U2Theme.colors.bubbleOutgoing, CircleShape).padding(8.dp)) { U2InlinePillButton("Durdur", onClick = {}) }
            Box(Modifier.background(U2Theme.colors.cameraBg, CircleShape).padding(8.dp)) {
                U2CameraPillButton("Kendi kodumu göster", onClick = {}, glyph = PhosphorGlyph.QrCode)
            }
        }
    }

    @Composable
    private fun Entry() {
        UsernameField("selin.n", onTextChange = {}, status = UsernameFieldStatus.Positive, showsFocusChrome = true)
        UsernameField("ilgaz", onTextChange = {}, status = UsernameFieldStatus.Negative)
        UsernameField("deniz", onTextChange = {}, status = UsernameFieldStatus.Checking)
        UsernameSearchField("deniz", onTextChange = {}, showsFocusChrome = true)
        UsernameSearchField("", onTextChange = {}, isSearching = true)
        PasswordField("correct horse", onTextChange = {}, isHighlighted = true)
        PasswordField("correct horse", onTextChange = {}, trailing = PasswordFieldTrailing.Accepted, isError = true)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeDots(filled = 4)
            PageDots(count = 3, current = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeDots(filled = 6, appearance = CodeDotsAppearance.Error)
            CodeDots(filled = 2, appearance = CodeDotsAppearance.Dimmed, length = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            U2Toggle(checked = true, onCheckedChange = {})
            U2Toggle(checked = false, onCheckedChange = {})
            U2Toggle(checked = true, onCheckedChange = {}, enabled = false)
            CountBadge(2)
            CountBadge(12)
            FaceIdGlyph(30.dp, 1.6f)
            FaceIdGlyph(22.dp, 1.7f, color = U2Theme.colors.accent300)
            FaceIdGlyph(18.dp, 1.8f, color = U2Theme.colors.accent300)
        }
        U2Checkbox(checked = true, onCheckedChange = {}) { U2Text("Anladım: kodu unutursam verilerim kalıcı olarak silinir.", U2TextStyles.calloutL) }
        U2Checkbox(checked = false, onCheckedChange = {}) { U2Text("Henüz işaretlenmedi.", U2TextStyles.calloutL) }
        SegmentedChips(listOf("1 sa", "6 sa", "24 sa", "3 gün", "7 gün"), selection = "24 sa", onSelect = {}) { it }
    }

    @Composable
    private fun CardsAndMenus() {
        InfoBanner("Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler.")
        OptionCard(PhosphorGlyph.FileLock, "Yedekten geri yükle", "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.", onClick = {})
        OptionCard(PhosphorGlyph.Trash, "Her şeyi sil, baştan başla", "Bu cihazdaki tüm sohbetler ve anahtarlar kalıcı olarak silinir.", onClick = {}, tone = OptionCardTone.Danger)
        NoteCard("İletilemedi ne demek?", "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve ona hiç ulaşmaz.")
        FloatingBanner(FloatingBannerKind.Urgent) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PhosphorIcon(PhosphorGlyph.Ghost, size = 18.dp, weight = PhosphorWeight.Fill, tint = U2Theme.colors.danger)
                U2Text("Sohbet 43 dk içinde iki taraftan silinecek.", U2TextStyles.bullet)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SystemPill("Ghost mod açık · 24 saat")
            InfoPill("18:52 · yüz yüze")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FlagTag(PhosphorGlyph.Image, "Medya kabul edene kadar gizli")
            FlagTag(PhosphorGlyph.Warning, "Bağlantı içeriyor", color = U2Theme.colors.danger)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FloatingMenu(
                listOf(
                    FloatingMenuItem("reply", "Yanıtla", PhosphorGlyph.ArrowBendUpLeft) {},
                    FloatingMenuItem("copy", "Kopyala", PhosphorGlyph.Copy, hint = "Pano 1 dk sonra temizlenir") {},
                    FloatingMenuItem("info", "Bilgi", PhosphorGlyph.Info, isEnabled = false) {},
                    FloatingMenuItem("delete", "Benden sil", PhosphorGlyph.Trash, tone = FloatingMenuTone.Destructive, startsGroup = true) {},
                ),
                width = 220.dp,
            )
            Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoKicker("Tam eşleşme")
                MonoKicker("Ghost mod · 24 saat", glyph = PhosphorGlyph.Ghost)
                SectionLabel("Gizlilik", horizontalPadding = 0.dp)
            }
        }
    }

    @Composable
    private fun HeadersAndText() {
        CenteredTitleHeader("Mesaj istekleri", leading = {
            U2GlassIconButton(PhosphorGlyph.CaretLeft, "Geri", onClick = {})
        }, trailing = {
            U2GlassIconButton(PhosphorGlyph.DotsThree, "Daha fazla", onClick = {})
        })
        SheetBar("Yeni sohbet", "Vazgeç", onCancel = {})
        SheetCloseHeader("Mesaj bilgisi", onClose = {})
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            U2Wordmark()
            U2Wordmark(style = U2TextStyles.brandWordmarkL)
        }
        U2DisplayText("İki uç. Bir hat.", U2TextStyles.displayHero)
        U2DisplayText("Her şey silinsin mi?", U2TextStyles.displaySheetTitle.balanced)
        GreedyParagraph("Yedek açılınca kod sorulur; eski kodunla devam edersin. Silinen sohbetler geri gelmez.", U2TextStyles.body)
        CSSLineClampText(
            "Merhaba, toplantı için hazırladığım slaytları paylaşabilir misin? Akşama kadar bakmam gerekiyor, sonra da yola çıkacağım.",
            U2TextStyles.requestSnippet,
            color = U2Theme.colors.textSecondary,
        )
        // 3.1: the Face ID disc with its pulse rings, held 2 s into the loop.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(116.dp).background(U2Theme.colors.accent900, CircleShape))
            PulseRings()
            FaceIdGlyph(56.dp, 1.3f, color = U2Theme.colors.accent300)
        }
    }

    @Composable
    private fun Confirmations() {
        val sheet = Modifier.fillMaxWidth().background(U2Theme.colors.surface, RoundedCornerShape(42.dp)).padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp)
        Box(sheet) { EraseConfirmation(onConfirm = {}, onCancel = {}) }
        Box(sheet) { EraseConfirmation(onConfirm = {}, onCancel = {}, scope = EraseScope.Account, isWorking = true) }
    }
}
