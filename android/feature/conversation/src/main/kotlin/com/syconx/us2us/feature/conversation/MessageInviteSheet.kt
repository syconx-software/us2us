package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A tap is local. The original invitation is redeemed only after explicit confirmation. */
@Composable
internal fun MessageInviteSheet(code: String, service: CommunitiesService?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(code) { mutableStateOf(false) }
    var submitted by remember(code) { mutableStateOf(false) }
    var failed by remember(code) { mutableStateOf(false) }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(service) { service?.unlocked?.collect { if (!it) close() } }
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        U2Text(stringResource(if (submitted) R.string.message_invite_sent else R.string.message_invite_title), U2TextStyles.rowTitle)
        if (!submitted) {
            InfoBanner(glyph = PhosphorGlyph.Info, message = stringResource(R.string.community_privacy))
            if (service == null) U2Text(stringResource(R.string.community_unsupported), U2TextStyles.callout)
            if (failed) U2Text(stringResource(R.string.community_error), U2TextStyles.callout, Modifier.testTag("messageInvite.error"))
            U2PrimaryButton(
                enabled = !busy && service != null,
                modifier = Modifier.testTag("messageInvite.confirm"),
                onClick = {
                    if (!busy && !submitted && service != null) {
                        busy = true
                        failed = false
                        scope.launch {
                            try { service.redeem(code); submitted = true }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed = true }
                            finally { busy = false }
                        }
                    }
                },
            ) { U2Text(stringResource(R.string.community_request), U2TextStyles.button) }
        }
        U2SecondaryButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("messageInvite.close")) {
            U2Text(stringResource(if (submitted) R.string.message_invite_done else R.string.common_cancel), U2TextStyles.button)
        }
    }
}
