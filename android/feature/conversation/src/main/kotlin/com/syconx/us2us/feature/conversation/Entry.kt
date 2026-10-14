package com.syconx.us2us.feature.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.feature.conversation.request.RequestPreviewContent

/** 5.1 Sohbet with [chat]. */
@Composable
fun ConversationScreen(chat: ChatId, modifier: Modifier = Modifier) {
    ConversationContent(chat, modifier)
}

/** 4.4 İstek önizleme of [request]. */
@Composable
fun RequestPreviewScreen(request: RequestId, modifier: Modifier = Modifier) {
    RequestPreviewContent(request, modifier)
}
