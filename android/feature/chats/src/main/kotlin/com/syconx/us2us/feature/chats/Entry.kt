package com.syconx.us2us.feature.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.feature.chats.invite.InviteDebugState
import com.syconx.us2us.feature.chats.invite.InviteView
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalAppModel
import androidx.compose.ui.res.stringResource

/** 4.1 Sohbetler: the main stack's root. */
@Composable
fun ChatsScreen(modifier: Modifier = Modifier) {
    ChatsView(modifier)
}

/** 4.3 Mesaj istekleri. */
@Composable
fun MessageRequestsScreen(modifier: Modifier = Modifier) {
    MessageRequestsView(modifier)
}

/** 4.2 Yeni sohbet (full-height sheet content), searching for [query]. */
@Composable
fun NewChatSheet(
    query: String,
    onOpenChat: (ChatId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(query) { NewChatPresentation(initialQuery = query) }
    NewChatSheetContent(presentation, onOpenChat = onOpenChat, onCancel = onDismiss, modifier = modifier)
}

/** The invite sheet over 4.1. [state] is a harness state id (`edit`, `copied` …), `null` in production. */
@Composable
fun InviteSheet(state: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val debug = remember(state) { if (state == null) InviteDebugState() else InviteHarness.sheetState(state) ?: InviteDebugState() }
    InviteSheetContent(debug, onClose, modifier)
}

/** The invitation in its own full-height sheet (7.1, the harness): “Kapat” closes it. */
@Composable
internal fun InviteSheetContent(debug: InviteDebugState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val own by app.messenger.ownUsername.collectAsState()
    // The harness may open the sheet before an account exists: the design's own name then.
    val username = own.ifEmpty { if (app.configuration.launchRoute != null) DesignFixtures.OWN_USERNAME else own }
    InviteView(username = username, closeTitle = stringResource(R.string.common_close), onClose = onClose, modifier = modifier, debug = debug)
}
