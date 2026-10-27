package com.syconx.us2us.ui.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.domain.nav.RestoreMode
import java.net.URI

/**
 * Screens of one feature that another feature presents. Feature modules only see the design
 * system, so the app fills these in with the owning feature's entry composables (iOS views simply
 * name each other):
 *
 * - 7.3 Yedekten geri yükle (settings) from 1.1 (onboarding) and 3.3 (lock);
 * - 6.2 QR tarama (verification) from 4.1 and 4.2 (chats), to add a contact;
 * - the invite sheet (chats) from 7.1 (settings);
 * - the photo viewer, the video player and the file preview (media) from the contact screen's tabs
 *   (verification).
 *
 * Each is the content to place in the presenter's own `U2Sheet` / `U2FadeCover`, exactly as the
 * owning feature's entry composable of the same name.
 */
@Stable
interface FeatureScreens {
    /** 7.3 (settings' `RestoreBackupScreen`). */
    @Composable
    fun RestoreBackup(mode: RestoreMode, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier)

    /** 7.3 with a backup already chosen ([file]: 3.3 picks it first, as on iOS); `null` lets 7.3 ask for one. */
    @Composable
    fun RestoreBackup(mode: RestoreMode, file: URI?, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier) =
        RestoreBackup(mode, onClose, onFinished, modifier)

    /** 6.2 (verification's `QrScanCover`). */
    @Composable
    fun QrScan(mode: QrScanMode, onDismiss: () -> Unit, modifier: Modifier)

    /**
     * 6.2 from 4.2: “Sohbete dön” hands the chat to [onOpenChat] (4.2 closes its sheet, then 4.1
     * pushes 5.1) instead of pushing it under the open sheet.
     */
    @Composable
    fun QrScan(mode: QrScanMode, onOpenChat: ((ChatId) -> Unit)?, onDismiss: () -> Unit, modifier: Modifier) =
        QrScan(mode, onDismiss, modifier)

    /** The invite sheet's content (chats' `InviteSheet`); [state] is a harness state id, `null` in production. */
    @Composable
    fun Invite(state: String?, onClose: () -> Unit, modifier: Modifier)

    /** Owner approval screen, supplied by the conversation feature. */
    @Composable
    fun CommunityManage(service: com.syconx.us2us.domain.CommunitiesService, id: String, title: String, onClose: () -> Unit) = Unit

    /** The photo viewer (media's `MediaViewerCover`). */
    @Composable
    fun MediaViewer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier)

    /** The video player (media's `VideoPlayerCover`). */
    @Composable
    fun VideoPlayer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier)

    /** The file preview (media's `FilePreviewCover`). */
    @Composable
    fun FilePreview(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier)

    /** Nothing: previews and tests of a single feature. */
    object None : FeatureScreens {
        @Composable
        override fun RestoreBackup(mode: RestoreMode, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier) = Unit

        @Composable
        override fun QrScan(mode: QrScanMode, onDismiss: () -> Unit, modifier: Modifier) = Unit

        @Composable
        override fun Invite(state: String?, onClose: () -> Unit, modifier: Modifier) = Unit

        @Composable
        override fun MediaViewer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit

        @Composable
        override fun VideoPlayer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit

        @Composable
        override fun FilePreview(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit
    }
}

/** The app's [FeatureScreens]. Provided at the root; [FeatureScreens.None] outside the app. */
val LocalFeatureScreens = staticCompositionLocalOf<FeatureScreens> { FeatureScreens.None }
