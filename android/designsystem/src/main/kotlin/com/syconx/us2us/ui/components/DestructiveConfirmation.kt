package com.syconx.us2us.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** The test tags of a confirmation's two buttons. */
data class ConfirmationTags(val confirm: String, val cancel: String)

/**
 * A destructive confirmation inside a floating sheet, used for every irreversible step: 3.3 / 7.1
 * “Her şey silinsin mi?”, 7.3 cancel. The host sheet supplies the padding (18 18 28 18).
 *
 * The text block (pad 6 4 0 4, gap 10): the Doto sheet title (balanced: a heading that wraps never
 * leaves one word alone) and the `sheetBody` paragraph in `text·68%`; 18 below it the danger C1
 * and the C3 cancel (`accent300`, gap 8). While [isWorking] the confirm button spins and both
 * buttons ignore taps. [tags] are the buttons' test tags (`null`: none, e.g. a measuring copy that
 * UI tests must never find).
 */
@Composable
fun DestructiveConfirmation(
    title: String,
    message: String,
    confirmTitle: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmGlyph: PhosphorGlyph? = PhosphorGlyph.Trash,
    cancelTitle: String = stringResource(R.string.common_cancel),
    isWorking: Boolean = false,
    tags: ConfirmationTags? = ConfirmationTags("confirm.destructive", "confirm.cancel"),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(title, U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(message, U2TextStyles.sheetBody, color = U2Theme.colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2DangerButton(
                onClick = onConfirm,
                enabled = !isWorking,
                modifier = if (tags != null) Modifier.testTag(tags.confirm) else Modifier,
            ) {
                U2ButtonLabel(confirmTitle, glyph = confirmGlyph, isWorking = isWorking)
            }
            U2TextButton(
                title = cancelTitle,
                onClick = onCancel,
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
                enabled = !isWorking,
                modifier = if (tags != null) Modifier.testTag(tags.cancel) else Modifier,
            )
        }
    }
}
