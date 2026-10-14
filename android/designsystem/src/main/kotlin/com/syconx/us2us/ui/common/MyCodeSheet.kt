package com.syconx.us2us.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.QrModules
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2ButtonLabel
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.LocalOwnAvatar
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/** Test tags of the own-code sheet (the iOS accessibility identifiers). */
object MyCodeTags {
    const val SHEET = "myCode.sheet"
    const val SHARE = "myCode.share"
    const val CARD = "myCode.card"
}

/**
 * “Kodumu göster”: the user's own contact code, everywhere it is offered (4.1 QR menu, 4.2 pushed
 * inside the sheet, 6.2 on the camera surface, the 7.1 profile card). The design's “6.1 layout
 * without the peer”: the own end of the line (avatar 48, palette D, the name in `monoMeta`
 * `text·68%`, gap 8) 26 below the bar, and the 6.1 QR card 22 below it. Someone next to you scans
 * it to add *and* verify you. [payload] is a `ContactCode` (`MessengerService.ownContactCode()`);
 * `null` draws the empty card.
 */
@Composable
fun MyCodeView(username: String, payload: String?, modifier: Modifier = Modifier) {
    val ownAvatar = LocalOwnAvatar.current
    val modules = remember(payload) { payload?.let(QrModules::cached) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .padding(top = 26.dp)
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(username, AvatarPalette.D, size = AvatarSize.Verify, picture = ownAvatar)
            U2Text(username, U2TextStyles.monoMeta, color = U2Theme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        QrCodeCard(modules, stringResource(R.string.common_a11y_mycode), Modifier.padding(top = 22.dp).testTag(MyCodeTags.CARD))
    }
}

/**
 * The own code in a full-height sheet (4.1 › QR › “Kodumu göster”, 7.1 › QR): the sheet bar
 * “Kodum” with “Kapat”, the code, and what it does. It is the content of a `U2Sheet`;
 * [onDismiss] closes it.
 *
 * The username and the code come from the service. A host that can load the code before the sheet
 * rises (so nothing is inserted while it moves, as 4.1 does) uses the other overload with the
 * payload from [preparedOwnContactCode].
 */
@Composable
fun MyCodeSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val username by messenger.ownUsername.collectAsState()
    var payload by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messenger) { payload = messenger.preparedOwnContactCode() }
    MyCodeSheet(username = username, payload = payload, onClose = onDismiss, modifier = modifier)
}

/**
 * The own-code sheet for [username] and its contact code [payload] (loaded by the host before
 * presenting). [closeTitle] is “Kapat”, or “Geri” where the page is pushed inside the 4.2 sheet.
 */
@Composable
fun MyCodeSheet(
    username: String,
    payload: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeTitle: String = stringResource(R.string.common_close),
) {
    Column(modifier.fillMaxWidth().testTag(MyCodeTags.SHEET), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetBar(title = stringResource(R.string.common_mycode_title), cancelTitle = closeTitle, onCancel = onClose)
        MyCodeView(username, payload)
        U2Text(
            stringResource(R.string.common_mycode_caption),
            U2TextStyles.callout,
            color = U2Theme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 24.dp)
                .widthIn(max = 290.dp),
        )
        InviteShareButton(username, Modifier.padding(top = 8.dp))
    }
}

/**
 * “Davet bağlantını paylaş”: the invitation (the text with the link, the subject for mail apps)
 * straight to the system share sheet, for someone who is not next to you to scan the code.
 */
@Composable
private fun InviteShareButton(username: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val name = remember(username) { UsernameRules.normalize(username).takeIf(UsernameRules::isValid) }
    val title = stringResource(R.string.invite_entry_share)
    val text = name?.let { stringResource(R.string.invite_message, it, MyCodeShare.link(it)) }
    val subject = stringResource(R.string.invite_subject)
    U2TextButton(
        onClick = { if (text != null) MyCodeShare.share(context, guard, text, subject) },
        modifier = modifier.testTag(MyCodeTags.SHARE),
        tone = U2TextButtonTone.Accent,
        fillsWidth = false,
        enabled = name != null,
    ) {
        U2ButtonLabel(title, glyph = PhosphorGlyph.Export, glyphSize = 20.dp)
    }
}

/** The share sheet behind “Davet bağlantını paylaş”. */
internal object MyCodeShare {
    /** `https://us2us.app/u/<username>` (the feature's `InviteLink`; the name is already normalized and valid). */
    fun link(username: String): String = "https://us2us.app/u/$username"

    fun share(context: Context, guard: ExternalActivityGuard, text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, subject)
        }
        val chooser = Intent.createChooser(send, null)
        try {
            guard.launching { context.startActivity(chooser) }
        } catch (_: ActivityNotFoundException) {
            // No share target on this device: nothing to hand the text to.
        }
    }
}

/**
 * The own contact code with its QR already generated, so a sheet showing it never draws an empty
 * card first. `null` until the account exists.
 */
suspend fun MessengerService.preparedOwnContactCode(): String? {
    val payload = try {
        ownContactCode()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (payload != null) QrModules.cached(payload)
    return payload
}
