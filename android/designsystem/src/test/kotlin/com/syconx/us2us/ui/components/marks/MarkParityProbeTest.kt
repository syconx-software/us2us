package com.syconx.us2us.ui.components.marks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LayoutSpec
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.ConnectionLine
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.components.DotGridMask
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedHourMeter
import com.syconx.us2us.ui.components.DottedMeter
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.GhostCountdown
import com.syconx.us2us.ui.components.GhostPill
import com.syconx.us2us.ui.components.GhostUrgentBanner
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedSection
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.marks.MarkShots.assertFrames
import com.syconx.us2us.ui.components.marks.MarkShots.assertNear
import com.syconx.us2us.ui.components.marks.MarkShots.at
import com.syconx.us2us.ui.components.marks.MarkShots.frameOf
import com.syconx.us2us.ui.components.marks.MarkShots.region
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.syconx.us2us.shot.ShotHost

/**
 * The marks placed at their frames on the drawn screens, in dark and light: every measured frame
 * must match the layout JSON within 1 dp (the gate), and the marks' pixels are compared with the
 * same regions of the iOS references (for review; a loose bound catches a broken render). Only the
 * marks are drawn, so the regions leave out what the screens' own packages draw.
 * Side-by-sides: build/shots/android/designsystem/marks-probe-*-compare.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MarkParityProbeTest {
    @get:Rule
    val host = MarkShots.host()

    private fun frame(tag: String): Frame = host.compose.frameOf(tag)

    @Composable
    private fun now(): Instant = LocalU2Clock.current.now

    // MARK: 4.1 — own avatar, “Hat açık” line, row avatars with the online dot, Ghost pills, separators

    @Test fun chatsDark() = chats(ShotTheme.Dark)

    @Test fun chatsLight() = chats(ShotTheme.Light)

    private fun chats(theme: ShotTheme) {
        MarkShots.probe(
            host, "4.1-sohbet-listesi", theme,
            regions = listOf(
                region(10, 52, 52, 52),
                region(16, 162, 66, 20),
                region(10, 282, 64, 222),
                region(312, 312, 76, 30),
                region(312, 468, 76, 30),
                region(81, 348, 321, 8),
            ),
        ) {
            val chats = DesignFixtures.chats(now(), ghostUrgent = false, english = false)
            Avatar(DesignFixtures.OWN_USERNAME, AvatarPalette.D, Modifier.at(16, 58).testTag("own"), size = AvatarSize.ChatsSelf)
            LineMark(Modifier.at(20, 168.02).testTag("line"), metrics = LineMarkMetrics.Connection, isFlowing = true)
            for ((index, y) in listOf(287.98, 365.98, 443.98).withIndex()) {
                val chat = chats[index]
                Avatar(
                    chat.peer.username,
                    chat.peer.palette,
                    Modifier.at(16, y).testTag("avatar$index"),
                    showsOnlineDot = chat.peer.isAcceptedContact && chat.peer.presence == Presence.Online,
                )
            }
            GhostPill(chats[0].ghost, Modifier.at(315.25, 316.61).testTag("pillCalm"))
            GhostPill(chats[2].ghost, Modifier.at(315.25, 472.61).testTag("pillUrgent"))
            for ((index, y) in listOf(351.98, 429.98, 507.98).withIndex()) {
                DottedRule(Modifier.at(81, y).width(321.dp).testTag("rule$index"))
            }
        }
        assertFrames(
            LayoutSpec.load("4.1-sohbet-listesi"),
            mapOf(
                "own" to (frame("own") to "r.0.0"),
                "line" to (frame("line") to "r.1.1.0"),
                "deniz" to (frame("avatar0") to "r.3.0"),
                "mert_k" to (frame("avatar1") to "r.5.0"),
                "ayla_" to (frame("avatar2") to "r.7.0"),
                "pill" to (frame("pillCalm") to "r.3.1.1.3"),
                "urgent pill" to (frame("pillUrgent") to "r.7.1.1.2"),
                "rule 1" to (frame("rule0") to "r.4"),
                "rule 2" to (frame("rule1") to "r.6"),
                "rule 3" to (frame("rule2") to "r.8"),
            ),
        )
    }

    // MARK: 5.1 — header avatar 38, Ghost chip, header rule; urgent: red chip and the banner

    @Test fun chatDark() = chat(ShotTheme.Dark, urgent = false)

    @Test fun chatLight() = chat(ShotTheme.Light, urgent = false)

    @Test fun chatUrgentDark() = chat(ShotTheme.Dark, urgent = true)

    @Test fun chatUrgentLight() = chat(ShotTheme.Light, urgent = true)

    private fun chat(theme: ShotTheme, urgent: Boolean) {
        val screen = if (urgent) "urgent/5.1-sohbet-urgent" else "5.1-sohbet"
        val regions = listOf(region(60, 55, 42, 42), region(274, 57, 116, 38), region(0, 103, 402, 4)) +
            if (urgent) listOf(region(10, 712, 382, 64)) else emptyList()
        // The banner's two text lines sit 2 px (0.67 dp) lower than Chrome drew them (pixel snapping of the
        // fractional banner and the Mono run's taller ascent); the text-heavy region shows it, so it gets more room.
        MarkShots.probe(host, screen, theme, regions, limit = if (urgent) 16.0 else 12.0) {
            val ghost = DesignFixtures.chats(now(), ghostUrgent = urgent, english = false)[0].ghost
            Avatar("deniz", AvatarPalette.A, Modifier.at(62, 57.31).testTag("avatar"), size = AvatarSize.Header)
            GhostChip(ghost, onClick = {}, modifier = Modifier.at(276.8, 59.31).testTag("chip"))
            DottedRule(Modifier.at(0, 104).testTag("rule"), color = U2Theme.colors.dotRuleHeader)
            if (urgent) {
                GhostUrgentBanner(requireNotNull(ghost.deadline), Modifier.at(12, 714.22).width(378.dp).testTag("banner"))
            }
        }
        val elements = mapOf(
            "avatar" to (frame("avatar") to "r.0.1"),
            "chip" to (frame("chip") to "r.0.3"),
            "rule" to (frame("rule") to "r.1"),
        ) + if (urgent) mapOf("banner" to (frame("banner") to "r.3")) else emptyMap()
        assertFrames(LayoutSpec.load(screen), elements)
        if (urgent) {
            // The sentence wraps where the design wraps: two 18.9 lines, starting after the glyph.
            val text = host.compose.frameOf(hasText("Sohbet", substring = true))
            assertNear("banner text", LayoutSpec.load(screen)["r.3.1"].frame, text)
        }
    }

    // MARK: 6.1 — the line and the QR card

    @Test fun safetyNumberDark() = safetyNumber(ShotTheme.Dark)

    @Test fun safetyNumberLight() = safetyNumber(ShotTheme.Light)

    private fun safetyNumber(theme: ShotTheme) {
        MarkShots.probe(
            host, "6.1-guvenlik-numarasi", theme,
            // The card's modules are a real code of the demo payload; the design's are another code.
            regions = listOf(region(20, 120, 362, 84), region(80, 212, 242, 26), region(80, 212, 26, 240)),
        ) {
            ConnectionLine(
                DesignFixtures.OWN_USERNAME,
                stringResource(R.string.verification_safety_you),
                DesignFixtures.deniz,
                Modifier.at(0, 126).testTag("line"),
            )
            Box(Modifier.at(91, 221.05)) {
                QrCodeCard(SafetyQRCodec.encode(DesignFixtures.safetyNumberPayload), "QR", Modifier.testTag("qr"))
            }
        }
        val layout = LayoutSpec.load("6.1-guvenlik-numarasi")
        assertFrames(
            layout,
            mapOf(
                "line" to (frame("line") to "r.1.0"),
                "own label" to (host.compose.frameOf(hasText("sen")) to "r.1.0.0.1"),
                "peer label" to (host.compose.frameOf(hasText("deniz")) to "r.1.0.2.1"),
                "qr" to (frame("qr") to "r.2"),
            ),
        )
    }

    // MARK: 6.3 — dot grid, the line with the seal

    @Test fun verifiedDark() = verified(ShotTheme.Dark)

    @Test fun verifiedLight() = verified(ShotTheme.Light)

    private fun verified(theme: ShotTheme) {
        MarkShots.probe(
            host, "6.3-dogrulandi", theme,
            regions = listOf(region(0, 104, 402, 150), region(20, 258, 362, 84), region(0, 620, 402, 150)),
        ) {
            DotGridBackdrop(mask = DotGridMask.Verified, color = U2Theme.colors.dotGridSoft)
            ConnectionLine(
                DesignFixtures.OWN_USERNAME,
                stringResource(R.string.verification_safety_you),
                DesignFixtures.deniz,
                Modifier.at(0, 264.27).testTag("line"),
                showsSeal = true,
            )
        }
        val layout = LayoutSpec.load("6.3-dogrulandi")
        assertFrames(layout, mapOf("line" to (frame("line") to "r.2.0")))
        val seal = host.compose.frameOf(hasContentDescription("Doğrulandı"))
        assertNear("seal glyph", layout["r.2.0.1.2.0"].frame, seal)
    }

    // MARK: 3.1 — dot grid and the brand line mark

    @Test fun lockDark() = lock(ShotTheme.Dark)

    @Test fun lockLight() = lock(ShotTheme.Light)

    private fun lock(theme: ShotTheme) {
        MarkShots.probe(
            host, "3.1-face-id", theme,
            regions = listOf(region(170, 106, 62, 26), region(0, 260, 402, 90), region(0, 520, 130, 160), region(272, 520, 130, 160)),
        ) {
            DotGridBackdrop(mask = DotGridMask.Lock, color = U2Theme.colors.dotGridSoft)
            LineMark(Modifier.at(175, 112).testTag("mark"))
        }
        assertFrames(LayoutSpec.load("3.1-face-id"), mapOf("mark" to (frame("mark") to "r.1.0")))
    }

    // MARK: 4.4 — profile avatar 64, header avatar 38, date rules

    @Test fun requestPreviewDark() = requestPreview(ShotTheme.Dark)

    @Test fun requestPreviewLight() = requestPreview(ShotTheme.Light)

    private fun requestPreview(theme: ShotTheme) {
        MarkShots.probe(
            host, "4.4-istek-onizleme", theme,
            regions = listOf(region(164, 126, 74, 72), region(16, 312, 128, 6), region(258, 312, 128, 6)),
        ) {
            Avatar("kuzey.dev", AvatarPalette.C, Modifier.at(62, 57.31).testTag("header"), size = AvatarSize.Header)
            Avatar("kuzey.dev", AvatarPalette.C, Modifier.at(169, 130).testTag("profile"), size = AvatarSize.Profile)
            DottedRule(Modifier.at(16, 314.59).width(126.8.dp).testTag("left"), color = U2Theme.colors.dotRuleDate, pattern = DotPattern.DateRule)
            DottedRule(Modifier.at(259.2, 314.59).width(126.8.dp).testTag("right"), color = U2Theme.colors.dotRuleDate, pattern = DotPattern.DateRule)
        }
        assertFrames(
            LayoutSpec.load("4.4-istek-onizleme"),
            mapOf(
                "header" to (frame("header") to "r.0.1"),
                "profile" to (frame("profile") to "r.2.0.0"),
                "left rule" to (frame("left") to "r.2.1.0"),
                "right rule" to (frame("right") to "r.2.1.1"),
            ),
        )
    }

    // MARK: 1.3 / 5.5 / 7.2 / 7.3 / 5.4 — meters, countdowns, the timeline

    @Test fun silenceDark() = silence(ShotTheme.Dark)

    @Test fun silenceLight() = silence(ShotTheme.Light)

    private fun silence(theme: ShotTheme) {
        MarkShots.probe(host, "1.3-sessizlik-siler", theme, regions = listOf(region(40, 190, 322, 72), region(54, 270, 294, 16))) {
            val deadline = now().plusSeconds(23L * 3600 + 41 * 60 + 7)
            Box(Modifier.at(0, 194.05).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                GhostCountdown(deadline, Modifier.testTag("countdown"), style = U2TextStyles.displayCountdownL)
            }
            DottedHourMeter(totalHours = 24, litHours = 23, modifier = Modifier.at(57, 274.05).testTag("hours"))
        }
        val layout = LayoutSpec.load("1.3-sessizlik-siler")
        assertFrames(layout, mapOf("countdown" to (frame("countdown") to "r.1.1"), "hours" to (frame("hours") to "r.1.2.0")))
    }

    @Test fun ghostSheetDark() = ghostSheet(ShotTheme.Dark)

    @Test fun ghostSheetLight() = ghostSheet(ShotTheme.Light)

    private fun ghostSheet(theme: ShotTheme) {
        MarkShots.probe(host, "5.5-ghost-ve-sureli-mesaj", theme, regions = listOf(region(60, 356, 282, 64)), surface = true) {
            val deadline = now().plusSeconds(23L * 3600 + 41 * 60 + 7)
            Box(Modifier.at(0, 359.86).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                GhostCountdown(deadline, Modifier.testTag("countdown"))
            }
        }
        assertFrames(LayoutSpec.load("5.5-ghost-ve-sureli-mesaj"), mapOf("countdown" to (frame("countdown") to "r.1.1.1")))
    }

    @Test fun metersDark() = meters(ShotTheme.Dark)

    @Test fun metersLight() = meters(ShotTheme.Light)

    private fun meters(theme: ShotTheme) {
        MarkShots.probe(host, "7.3-yedekten-geri-yukle", theme, regions = listOf(region(22, 504, 358, 12))) {
            DottedMeter(0.62f, Modifier.at(24, 506.33).width(354.dp).testTag("progress"))
        }
        assertFrames(LayoutSpec.load("7.3-yedekten-geri-yukle"), mapOf("progress" to (frame("progress") to "r.2.1")))
        MarkShots.probe(host, "7.2-yedegi-disa-aktar", theme, regions = listOf(region(20, 573, 318, 9))) {
            DottedMeter(
                0.82f,
                Modifier.at(22, 574.91).width(313.72.dp).testTag("strength"),
                pattern = DotPattern.Strength,
                trackColor = U2Theme.colors.dotTrackStrength,
                litColor = U2Theme.colors.online,
            )
        }
        assertFrames(LayoutSpec.load("7.2-yedegi-disa-aktar"), mapOf("strength" to (frame("strength") to "r.1.2.2.0")))
        MarkShots.probe(host, "5.4-mesaj-bilgisi", theme, regions = listOf(region(46, 276, 6, 51), region(46, 361, 6, 51)), surface = true) {
            for ((index, y) in listOf(276.08, 361.25).withIndex()) {
                Box(Modifier.at(16, y - 34).size(370.dp, 120.dp).background(U2Theme.colors.bg))
                DottedRule(
                    Modifier.at(48, y).height(51.17.dp).testTag("timeline$index"),
                    color = U2Theme.colors.dotRuleTimeline,
                    pattern = DotPattern.Timeline,
                    orientation = Orientation.Vertical,
                )
            }
        }
        assertFrames(
            LayoutSpec.load("5.4-mesaj-bilgisi"),
            mapOf("first" to (frame("timeline0") to "r.1.3.0.0.1"), "second" to (frame("timeline1") to "r.1.3.1.0.1")),
        )
    }

    // MARK: 7.1 / 4.2 — grouped lists

    @Test fun settingsDark() = settings(ShotTheme.Dark)

    @Test fun settingsLight() = settings(ShotTheme.Light)

    private fun settings(theme: ShotTheme) {
        MarkShots.probe(
            host, "7.1-ayarlar", theme,
            regions = listOf(region(16, 168, 74, 74), region(16, 256, 370, 128), region(16, 396, 370, 178), region(16, 585, 370, 128)),
        ) {
            Box(Modifier.at(16, 168.25).size(370.dp, 74.dp).background(U2Theme.colors.surface, RoundedCornerShape(18.dp)))
            Avatar(DesignFixtures.OWN_USERNAME, AvatarPalette.D, Modifier.at(30, 180.25).testTag("avatar"), size = AvatarSize.Settings)
            Column(Modifier.at(16, 260.25).width(370.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GroupedSection("Gizlilik", Modifier.testTag("privacy")) {
                    GroupedNavigationRow(tile(PhosphorGlyph.CircleHalf, "tile0"), "Aktif durumu", onClick = {}, detail = "Kabul ettiklerim", modifier = Modifier.testTag("row0"))
                    GroupedSeparator(Modifier.testTag("sep0"))
                    GroupedToggleRow(tile(PhosphorGlyph.Checks, "tile1"), "Okundu bilgisi", checked = true, onCheckedChange = {}, modifier = Modifier.testTag("row1"))
                }
                GroupedSection("Güvenlik", Modifier.testTag("security")) {
                    GroupedToggleRow({ IconTile(IconTileContent.FaceId, Modifier.testTag("tile2")) }, "Face ID ile aç", checked = true, onCheckedChange = {})
                    GroupedSeparator()
                    GroupedNavigationRow(tile(PhosphorGlyph.LockSimple, "tile3"), "Otomatik kilit", onClick = {}, detail = "Hemen")
                    GroupedSeparator()
                    GroupedNavigationRow(tile(PhosphorGlyph.Password, "tile4"), "Gizli kodu değiştir", onClick = {}, modifier = Modifier.testTag("row4"))
                }
                GroupedSection("Sohbetler", Modifier.testTag("chats")) {
                    GroupedNavigationRow(tile(PhosphorGlyph.Ghost, "tile5"), "Varsayılan Ghost süresi", onClick = {}, detail = "24 saat")
                    GroupedSeparator(Modifier.testTag("sep5"))
                    GroupedNavigationRow(tile(PhosphorGlyph.Timer, "tile6"), "Varsayılan süreli mesaj", onClick = {}, detail = "Kapalı")
                }
            }
        }
        val layout = LayoutSpec.load("7.1-ayarlar")
        assertFrames(
            layout,
            mapOf(
                "avatar" to (frame("avatar") to "r.2.0.0"),
                "privacy" to (frame("privacy") to "r.2.1"),
                "security" to (frame("security") to "r.2.2"),
                "chats" to (frame("chats") to "r.2.3"),
                "row 0" to (frame("row0") to "r.2.1.1.0"),
                "row 1" to (frame("row1") to "r.2.1.1.2"),
                "row 4" to (frame("row4") to "r.2.2.1.4"),
                "separator 0" to (frame("sep0") to "r.2.1.1.1"),
                "separator 5" to (frame("sep5") to "r.2.3.1.1"),
                "tile 0" to (frame("tile0") to "r.2.1.1.0.0"),
                "tile 1" to (frame("tile1") to "r.2.1.1.2.0"),
                "tile 2" to (frame("tile2") to "r.2.2.1.0.0"),
                "tile 3" to (frame("tile3") to "r.2.2.1.2.0"),
                "tile 5" to (frame("tile5") to "r.2.3.1.0.0"),
                "tile 6" to (frame("tile6") to "r.2.3.1.2.0"),
                "detail 0" to (host.compose.frameOf(hasText("Kabul ettiklerim")) to "r.2.1.1.0.2"),
                "detail 3" to (host.compose.frameOf(hasText("Hemen")) to "r.2.2.1.2.2"),
                "detail 5" to (host.compose.frameOf(hasText("24 saat")) to "r.2.3.1.0.2"),
                "detail 6" to (host.compose.frameOf(hasText("Kapalı")) to "r.2.3.1.2.2"),
            ),
        )
        // Titles start after the tile and gap; their column fills the row (the text itself is narrower).
        for ((text, element) in listOf("Aktif durumu" to "r.2.1.1.0.1.0", "Okundu bilgisi" to "r.2.1.1.2.1.0", "Gizli kodu değiştir" to "r.2.2.1.4.1.0")) {
            val actual = host.compose.frameOf(hasText(text))
            assertNear(text, layout[element].frame.copy(w = actual.w), actual)
        }
    }

    @Test fun newChatOptionsDark() = newChatOptions(ShotTheme.Dark)

    @Test fun newChatOptionsLight() = newChatOptions(ShotTheme.Light)

    private fun newChatOptions(theme: ShotTheme) {
        MarkShots.probe(host, "4.2-yeni-sohbet", theme, regions = listOf(region(16, 470, 370, 127)), surface = true) {
            GroupedCard(Modifier.at(16, 470.73).width(370.dp).testTag("card"), fill = U2Theme.colors.bg) {
                GroupedNavigationRow(
                    tile(PhosphorGlyph.Scan, "tile0"),
                    "QR kodla ekle",
                    onClick = {},
                    subtitle = "Yan yanaysanız ekler ve aynı anda doğrular",
                    modifier = Modifier.testTag("row0"),
                )
                GroupedSeparator(Modifier.testTag("sep"))
                GroupedNavigationRow(tile(PhosphorGlyph.QrCode, "tile1"), "Kodumu göster", onClick = {}, subtitle = "@selin.n", modifier = Modifier.testTag("row1"))
            }
        }
        val layout = LayoutSpec.load("4.2-yeni-sohbet")
        assertFrames(
            layout,
            mapOf(
                "card" to (frame("card") to "r.1.5.0.0"),
                "row 0" to (frame("row0") to "r.1.5.0.0.0"),
                "separator" to (frame("sep") to "r.1.5.0.0.1"),
                "row 1" to (frame("row1") to "r.1.5.0.0.2"),
                "tile 0" to (frame("tile0") to "r.1.5.0.0.0.0"),
                "tile 1" to (frame("tile1") to "r.1.5.0.0.2.0"),
            ),
        )
        val subtitle = host.compose.frameOf(hasText("Yan yanaysanız ekler ve aynı anda doğrular"))
        assertNear("subtitle", layout["r.1.5.0.0.0.1.1"].frame.copy(w = subtitle.w), subtitle)
    }

    private fun tile(glyph: PhosphorGlyph, tag: String): @Composable () -> Unit = { IconTile(IconTileContent.Glyph(glyph), Modifier.testTag(tag)) }
}
