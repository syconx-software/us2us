package com.syconx.us2us.ui.components.marks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotTheme
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarImageCache
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
import com.syconx.us2us.ui.components.IconTileTone
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.VerifiedSeal
import com.syconx.us2us.domain.ContactCode
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every mark in both themes (the iOS previews of Avatar, LineMark, DottedRules, DotGridBackdrop, Ghost,
 * GroupedList and QRCodeCard), for review: build/shots/android/designsystem/marks/<theme>-tr/marks-specimen-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MarkSpecimenShotTest {
    @get:Rule
    val host = MarkShots.host()

    @Before
    fun decodeTheDemoPhoto() {
        // Decoded before the shot, so the photo is there on the first frame (the app fades it in).
        runBlocking { AvatarImageCache.image(requireNotNull(DesignAvatars.photoAvatar.photo)) }
    }

    @Test fun marksDark() = marks(ShotTheme.Dark)

    @Test fun marksLight() = marks(ShotTheme.Light)

    @Test fun listsDark() = lists(ShotTheme.Dark)

    @Test fun listsLight() = lists(ShotTheme.Light)

    private fun marks(theme: ShotTheme) {
        val shot = host.shoot("marks-specimen-1", theme, "tr") {
            Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
                DotGridBackdrop(mask = DotGridMask.Lock, color = U2Theme.colors.dotGridSoft)
                Column(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 76.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar("deniz", AvatarPalette.A)
                        Avatar("mert_k", AvatarPalette.B, showsOnlineDot = true)
                        Avatar("ayla_", AvatarPalette.C)
                        Avatar("kaan", AvatarPalette.D)
                        Avatar("oya_t", AvatarPalette.C, picture = DesignAvatars.photoAvatar)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar("kuzey.dev", AvatarPalette.C, size = AvatarSize.Profile)
                        Avatar("selin_n", AvatarPalette.D, size = AvatarSize.Settings)
                        Avatar("selin_n", AvatarPalette.D, size = AvatarSize.Verify)
                        Avatar("bora", AvatarPalette.B, size = AvatarSize.Request)
                        Avatar("selin_n", AvatarPalette.D, size = AvatarSize.ChatsSelf)
                        Avatar("mert_k", AvatarPalette.B, size = AvatarSize.Header, showsOnlineDot = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineMark()
                        LineMark(metrics = LineMarkMetrics.Connection, isFlowing = true)
                        LineMark(metrics = LineMarkMetrics.Cover)
                        LineMark(metrics = LineMarkMetrics.Sticker, color = U2Theme.colors.accent700)
                        VerifiedSeal()
                    }
                    GhostSpecimens()
                    DottedRule()
                    DottedRule(color = U2Theme.colors.dotRuleDate, pattern = DotPattern.DateRule)
                    Row(Modifier.height(50.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        DottedRule(color = U2Theme.colors.dotRuleTimeline, pattern = DotPattern.Timeline, orientation = Orientation.Vertical)
                        DottedRule(color = U2Theme.colors.dotRuleTimeline, pattern = DotPattern.Timeline, orientation = Orientation.Vertical)
                    }
                    DottedMeter(progress = 0.62f)
                    DottedMeter(progress = 0.82f, pattern = DotPattern.Strength, trackColor = U2Theme.colors.dotTrackStrength, litColor = U2Theme.colors.online)
                    DottedHourMeter(totalHours = 24, litHours = 23)
                }
            }
        }
        assertTrue(shot.isFile && shot.length() > 0)
    }

    @Composable
    private fun GhostSpecimens() {
        val now = LocalU2Clock.current.now
        val chats = DesignFixtures.chats(now, ghostUrgent = false, english = false)
        val urgent = DesignFixtures.chats(now, ghostUrgent = true, english = false)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostPill(chats[0].ghost)
                GhostPill(chats[2].ghost)
                GhostPill(chats[3].ghost)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostChip(chats[0].ghost, onClick = {})
                GhostChip(urgent[0].ghost, onClick = {})
                GhostChip(GhostState.Off, onClick = {})
            }
            GhostCountdown(requireNotNull(chats[0].ghost.deadline))
            GhostUrgentBanner(requireNotNull(urgent[0].ghost.deadline))
        }
    }

    private fun lists(theme: ShotTheme) {
        val shot = host.shoot("marks-specimen-2", theme, "tr") {
            Box(Modifier.fillMaxSize().background(U2Theme.colors.bg)) {
                DotGridBackdrop(mask = DotGridMask.Verified, color = U2Theme.colors.dotGridSoft)
                Column(Modifier.fillMaxWidth().padding(top = 70.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ConnectionLine(DesignFixtures.OWN_USERNAME, stringResource(R.string.verification_safety_you), DesignFixtures.deniz, showsSeal = true)
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        GroupedSection("Gizlilik") {
                            GroupedNavigationRow({ IconTile(IconTileContent.Glyph(PhosphorGlyph.CircleHalf)) }, "Aktif durumu", onClick = {}, detail = "Kabul ettiklerim")
                            GroupedSeparator()
                            GroupedToggleRow({ IconTile(IconTileContent.Glyph(PhosphorGlyph.Checks)) }, "Okundu bilgisi", checked = true, onCheckedChange = {})
                            GroupedSeparator()
                            GroupedToggleRow({ IconTile(IconTileContent.FaceId) }, "Face ID ile aç", checked = false, onCheckedChange = {})
                        }
                        GroupedCard(fill = U2Theme.colors.surface) {
                            GroupedNavigationRow(
                                { IconTile(IconTileContent.Glyph(PhosphorGlyph.Scan)) },
                                "QR kodla ekle",
                                onClick = {},
                                subtitle = "Yan yanaysanız ekler ve aynı anda doğrular",
                            )
                            GroupedSeparator()
                            GroupedNavigationRow(
                                { IconTile(IconTileContent.Glyph(PhosphorGlyph.Trash), tone = IconTileTone.Danger) },
                                "Hesabı sil",
                                onClick = {},
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodeCard(ContactCode.encode(DesignFixtures.OWN_USERNAME), "QR")
                    }
                }
            }
        }
        assertTrue(shot.isFile && shot.length() > 0)
        assertTrue(File(MarkShots.outputDir, "${theme.raw}-tr/marks-specimen-2.png").isFile)
    }
}
