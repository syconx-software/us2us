package com.syconx.us2us.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.domain.nav.RestoreMode
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.feature.chats.InviteSheet
import com.syconx.us2us.feature.chats.MessageRequestsScreen
import com.syconx.us2us.feature.conversation.ConversationScreen
import com.syconx.us2us.feature.conversation.RequestPreviewScreen
import com.syconx.us2us.feature.media.FilePreviewCover
import com.syconx.us2us.feature.media.MediaViewerCover
import com.syconx.us2us.feature.media.VideoPlayerCover
import com.syconx.us2us.feature.settings.BackupExportScreen
import com.syconx.us2us.feature.settings.RestoreBackupScreen
import com.syconx.us2us.feature.settings.SettingsPageScreen
import com.syconx.us2us.feature.settings.SettingsPickerScreen
import com.syconx.us2us.feature.settings.SettingsRootScreen
import com.syconx.us2us.feature.verification.QrScanCover
import com.syconx.us2us.feature.verification.SafetyNumberScreen
import com.syconx.us2us.ui.env.FeatureScreens
import java.net.URI

/** One main-stack layer: the feature screen for [route]. [onBack] pops this layer. */
@Composable
fun RouteHost(route: AppRoute, onBack: () -> Unit, modifier: Modifier = Modifier) {
    when (route) {
        is AppRoute.Conversation -> ConversationScreen(route.chat, modifier)
        AppRoute.Requests -> MessageRequestsScreen(modifier)
        is AppRoute.RequestPreview -> RequestPreviewScreen(route.request, modifier)
        is AppRoute.SafetyNumber -> SafetyNumberScreen(route.chat, route.tab, modifier)
        AppRoute.Settings -> SettingsRootScreen(modifier)
        is AppRoute.Picker -> SettingsPickerScreen(route.kind, modifier)
        AppRoute.BackupExport -> BackupExportScreen(modifier)
        is AppRoute.Page -> when (route.kind) {
            SettingsPage.BackupExport -> BackupExportScreen(modifier)
            SettingsPage.Restore -> RestoreBackupScreen(RestoreMode.Settings, onClose = onBack, onFinished = onBack, modifier = modifier)
            SettingsPage.ChangeCode, SettingsPage.EnableFaceId, SettingsPage.About, SettingsPage.Blocked ->
                SettingsPageScreen(route.kind, modifier)
        }
    }
}

/**
 * The screens features present from each other (they only see the design system): each is the
 * owning feature's entry composable. Provided to every screen as `LocalFeatureScreens`.
 */
object AppFeatureScreens : FeatureScreens {
    @Composable
    override fun RestoreBackup(mode: RestoreMode, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier) =
        RestoreBackupScreen(mode, onClose = onClose, onFinished = onFinished, modifier = modifier)

    @Composable
    override fun RestoreBackup(mode: RestoreMode, file: URI?, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier) =
        RestoreBackupScreen(mode, onClose = onClose, onFinished = onFinished, modifier = modifier, file = file)

    @Composable
    override fun QrScan(mode: QrScanMode, onDismiss: () -> Unit, modifier: Modifier) = QrScanCover(mode, onDismiss, modifier)

    @Composable
    override fun QrScan(mode: QrScanMode, onOpenChat: ((ChatId) -> Unit)?, onDismiss: () -> Unit, modifier: Modifier) =
        QrScanCover(mode, onDismiss, modifier, onOpenChat = onOpenChat)

    @Composable
    override fun Invite(state: String?, onClose: () -> Unit, modifier: Modifier) = InviteSheet(state, onClose, modifier)

    @Composable
    override fun MediaViewer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) =
        MediaViewerCover(chat, message, onDismiss, modifier)

    @Composable
    override fun VideoPlayer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) =
        VideoPlayerCover(chat, message, onDismiss, modifier)

    @Composable
    override fun FilePreview(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) =
        FilePreviewCover(chat, message, onDismiss, modifier)
}
