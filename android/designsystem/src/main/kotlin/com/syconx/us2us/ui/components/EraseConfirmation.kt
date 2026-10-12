package com.syconx.us2us.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.syconx.us2us.ui.R

/** What an erase removes. */
enum class EraseScope {
    /**
     * 3.3: the code is forgotten, so the vault stays shut and nothing can sign in to the relay.
     * Only this phone is erased; the copy says the account stays on the relay until it is
     * removed after 180 days without a connection (PROTOCOL §4.10).
     */
    ThisPhone,

    /**
     * 7.1: the account and username are deleted on the relay first; the title and the button say
     * so (“Hesabın silinsin mi?” · “Hesabı ve her şeyi sil”), and the button spins
     * (“Hesap siliniyor…”) until the relay answers.
     */
    Account,
}

/**
 * “Her şey silinsin mi?”, the second confirmation before erasing everything (3.3 › “Her şeyi sil,
 * baştan başla”, 7.1 › Hesap › “Her şeyi sil”): one text, one danger button, “Vazgeç”.
 */
@Composable
fun EraseConfirmation(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    scope: EraseScope = EraseScope.ThisPhone,
    isWorking: Boolean = false,
    tags: ConfirmationTags? = ConfirmationTags("erase.confirm", "erase.cancel"),
) {
    val title: String
    val message: String
    val confirmTitle: String
    when (scope) {
        EraseScope.ThisPhone -> {
            title = stringResource(R.string.common_erase_confirm_title)
            message = stringResource(R.string.common_erase_confirm_body)
            confirmTitle = stringResource(R.string.common_erase_confirm_action)
        }
        EraseScope.Account -> {
            title = stringResource(R.string.settings_erase_confirm_title)
            message = stringResource(R.string.settings_erase_confirm_body)
            confirmTitle = stringResource(if (isWorking) R.string.settings_erase_confirm_working else R.string.settings_erase_confirm_action)
        }
    }
    DestructiveConfirmation(
        title = title,
        message = message,
        confirmTitle = confirmTitle,
        onConfirm = onConfirm,
        onCancel = onCancel,
        modifier = modifier,
        cancelTitle = stringResource(R.string.common_cancel),
        isWorking = isWorking,
        tags = tags,
    )
}
