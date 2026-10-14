package com.syconx.us2us.feature.conversation

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.feature.conversation.R as ConversationR

/** Test tags of the live-location share sheet. */
object LiveShareSheetTags {
    const val SHEET = "liveShare.sheet"
    const val ALLOW = "liveShare.allow"
    const val ANYWAY = "liveShare.anyway"
}

/**
 * “+” › Canlı konum on Android while notifications are refused or turned off (a
 * floating sheet, as the microphone notice): a share goes on in the background only while its
 * notification can be seen, so this says in one line that sharing stops when the user leaves the
 * app, and offers the system's question. Never forced: “Yine de paylaş” shares while the app is in front.
 */
@Composable
internal fun LiveShareSheet(onAllow: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier.testTag(LiveShareSheetTags.SHEET).padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(R.string.common_location_live, U2TextStyles.displaySheetTitle.balanced)
            U2Text(stringResource(ConversationR.string.conversation_android_liveshare_body), U2TextStyles.sheetBody, color = colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            U2PrimaryButton(onClick = onAllow, modifier = Modifier.testTag(LiveShareSheetTags.ALLOW)) {
                U2ButtonLabel(stringResource(ConversationR.string.conversation_android_liveshare_allow))
            }
            U2TextButton(
                onClick = onShare,
                modifier = Modifier.testTag(LiveShareSheetTags.ANYWAY),
                tone = U2TextButtonTone.Accent,
                height = U2Size.textButtonS,
            ) { U2ButtonLabel(stringResource(ConversationR.string.conversation_android_liveshare_anyway), glyph = PhosphorGlyph.NavigationArrow) }
        }
    }
}

/** The app's notification settings (where a channel turned off, or a refusal for good, is undone), under the app's guard. */
internal fun openNotificationSettings(context: Context, guard: ExternalActivityGuard) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    guard.launching { runCatching { context.startActivity(intent) } }
}
