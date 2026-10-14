package com.syconx.us2us.ui.components.marks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.assertSize
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.ConnectionLine
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedHourMeter
import com.syconx.us2us.ui.components.DottedMeter
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.GhostPill
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.LineMarkMetrics
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.VerifiedSeal
import com.syconx.us2us.ui.components.marks.MarkShots.frameOf
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.Bytes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every mark laid out with the design's data measures what the design measures: the iOS
 * ComponentMetricsTests numbers (Chrome's getBoundingClientRect) plus the inventory's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class MarkMetricsTest {
    @get:Rule
    val host = MarkShots.host()

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
    fun avatarSizes() {
        show {
            for (size in AvatarSize.entries) {
                Tagged(size.name) { Avatar("deniz", AvatarPalette.A, size = size, showsOnlineDot = size == AvatarSize.ListRow) }
            }
        }
        for (size in AvatarSize.entries) {
            host.compose.assertSize(size.name, size.diameter, size.diameter)
        }
    }

    @Test
    fun ghostPillAndChip() {
        val now = DesignFixtures.referenceDate
        val chats = DesignFixtures.chats(now, ghostUrgent = false, english = false)
        val urgent = DesignFixtures.chats(now, ghostUrgent = true, english = false)
        show {
            Tagged("pill") { GhostPill(chats[0].ghost) }
            Tagged("pillUrgent") { GhostPill(chats[2].ghost) }
            Tagged("pillDays") { GhostPill(chats[3].ghost) }
            Tagged("chip") { GhostChip(chats[0].ghost, onClick = {}) }
            Tagged("chipUrgent") { GhostChip(urgent[0].ghost, onClick = {}) }
            Tagged("off") { GhostChip(GhostState.Off, onClick = {}) }
        }
        host.compose.assertSize("pill", 68.75.dp, 22.dp)
        host.compose.assertSize("pillUrgent", 68.75.dp, 22.dp)
        host.compose.assertSize("pillDays", null, 22.dp)
        host.compose.assertSize("chip", 111.2.dp, 34.dp)
        host.compose.assertSize("chipUrgent", 111.2.dp, 34.dp)
        host.compose.assertSize("off", 94.41.dp, 34.dp)
    }

    @Test
    fun lineMarksAndTheSafetyLine() {
        show {
            Tagged("lock") { LineMark() }
            Tagged("connection") { LineMark(metrics = LineMarkMetrics.Connection, isFlowing = true) }
            Tagged("cover") { LineMark(metrics = LineMarkMetrics.Cover) }
            Tagged("sticker") { LineMark(metrics = LineMarkMetrics.Sticker) }
            Tagged("line", 402) { ConnectionLine("selin_n", "sen", DesignFixtures.deniz) }
            Tagged("seal") { VerifiedSeal() }
            Tagged("qr") { QrCodeCard(SafetyQRCodec.encode(Bytes.utf8("08223")), "QR") }
        }
        host.compose.assertSize("lock", 52.dp, 14.dp)
        host.compose.assertSize("connection", 60.dp, 9.dp)
        host.compose.assertSize("cover", 86.dp, 23.dp)
        host.compose.assertSize("sticker", 36.dp, 10.dp)
        host.compose.assertSize("line", 402.dp, 73.05.dp)
        host.compose.assertSize("seal", 32.dp, 32.dp)
        host.compose.assertSize("qr", 220.dp, 220.dp)
    }

    @Test
    fun dottedRulesAndMeters() {
        show {
            Tagged("rule", 321) { DottedRule() }
            Tagged("timeline") { DottedRule(Modifier.height(51.17.dp), pattern = DotPattern.Timeline, orientation = androidx.compose.foundation.gestures.Orientation.Vertical) }
            Tagged("progress", 354) { DottedMeter(progress = 0.62f) }
            Tagged("strength", 313.72.toInt()) { DottedMeter(progress = 0.82f, pattern = DotPattern.Strength) }
            Tagged("hours") { DottedHourMeter(totalHours = 24, litHours = 23) }
        }
        host.compose.assertSize("rule", 321.dp, 2.dp)
        host.compose.assertSize("timeline", 2.dp, 51.17.dp)
        host.compose.assertSize("progress", 354.dp, 8.dp)
        host.compose.assertSize("strength", 313.dp, 6.dp)
        host.compose.assertSize("hours", 288.dp, 8.dp)
    }

    @Test
    fun groupedRows() {
        show {
            Tagged("card", 370) {
                GroupedCard {
                    GroupedNavigationRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.CircleHalf)) },
                        title = "Aktif durumu",
                        detail = "Kabul ettiklerim",
                        onClick = {},
                        modifier = Modifier.testTag("nav"),
                    )
                    GroupedSeparator(Modifier.testTag("separator"))
                    GroupedToggleRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Checks)) },
                        title = "Okundu bilgisi",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("toggle"),
                    )
                }
            }
            Tagged("subtitled", 370) {
                GroupedCard {
                    GroupedNavigationRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Scan)) },
                        title = "QR kodla ekle",
                        subtitle = "Yan yanaysanız ekler ve aynı anda doğrular",
                        onClick = {},
                    )
                }
            }
            Tagged("tile") { IconTile(IconTileContent.FaceId) }
        }
        host.compose.assertSize("card", 370.dp, 98.dp)
        host.compose.assertSize("nav", 370.dp, 48.dp)
        host.compose.assertSize("toggle", 370.dp, 48.dp)
        host.compose.assertSize("subtitled", 370.dp, 62.34.dp)
        host.compose.assertSize("tile", 30.dp, 30.dp)
        val separator = host.compose.frameOf("separator")
        val card = host.compose.frameOf("card")
        assertEquals(57f, separator.x - card.x, 0.01f) // the rule starts after the inset
        assertEquals(313f, separator.w, 0.01f)
        assertEquals(2f, separator.h, 0.01f)
    }
}
