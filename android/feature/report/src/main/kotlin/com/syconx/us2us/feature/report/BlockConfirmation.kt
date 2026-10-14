package com.syconx.us2us.feature.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2DangerButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.ui.R as DesignR

/** The test tags of a block confirmation's three buttons. */
@Immutable
data class BlockConfirmationTags(
    val block: String = "confirm.destructive",
    val report: String = "confirm.report",
    val cancel: String = "confirm.cancel",
)

/**
 * The request block's confirmation (4.3, 4.4, the contact screen; iOS: an alert with Engelle ·
 * Bildir · Vazgeç), in the app's floating-sheet confirmation: “{kişi} engellensin mi?” (the Doto
 * sheet title) over “Sana bir daha yazamaz. Engellendiğini bilmez.” (`sheetBody`, `text·68%`);
 * 18 below, the danger C1 “Engelle” (`ph:prohibit`), the C2 “Bildir” (`ph:warning`, opens the
 * report sheet once this has gone) and the C3 “Vazgeç” (`accent300`), gap 8. The host sheet supplies
 * the padding (18 18 28 18).
 */
@Composable
fun BlockConfirmation(
    personName: String,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    tags: BlockConfirmationTags = BlockConfirmationTags(),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(stringResource(DesignR.string.chats_requests_block_title, personName), U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(stringResource(DesignR.string.chats_requests_block_message), U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2DangerButton(onClick = onBlock, modifier = Modifier.testTag(tags.block)) {
                U2ButtonLabel(stringResource(DesignR.string.common_request_block), glyph = PhosphorGlyph.Prohibit)
            }
            U2SecondaryButton(onClick = onReport, modifier = Modifier.testTag(tags.report)) {
                U2ButtonLabel(stringResource(DesignR.string.common_report_action), glyph = PhosphorGlyph.Warning)
            }
            U2TextButton(
                title = stringResource(DesignR.string.common_cancel),
                onClick = onCancel,
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                modifier = Modifier.testTag(tags.cancel),
            )
        }
    }
}

/**
 * [BlockConfirmation] in a floating sheet while [item] is set (it keeps showing the item it was
 * opened with while it goes down). Each choice closes it first ([onDismiss]); “Bildir” then opens
 * the report (with [ReportPresenter.open]'s `afterConfirmation`, so the sheet rises once this has gone).
 */
@Composable
fun <T : Any> BlockConfirmationSheet(
    item: T?,
    personName: (T) -> String,
    onBlock: (T) -> Unit,
    onReport: (T) -> Unit,
    onDismiss: () -> Unit,
    tags: BlockConfirmationTags = BlockConfirmationTags(),
) {
    U2Sheet(item = item, onDismissRequest = onDismiss, style = U2SheetStyle.Floating) { shown ->
        BlockConfirmation(
            personName = personName(shown),
            onBlock = {
                onDismiss()
                onBlock(shown)
            },
            onReport = {
                onDismiss()
                onReport(shown)
            },
            onCancel = onDismiss,
            modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            tags = tags,
        )
    }
}
