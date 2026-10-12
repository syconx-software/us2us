package com.syconx.us2us.feature.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.ChatSummary

/** The parts of 5.1 the debug component gallery shows (`U2Gallery YES`), as iOS `ComponentGallery` does. */
object ConversationGalleryParts {
    /** 5.1 verification (proposed): the held-send banner after a safety-number change. */
    @Composable
    fun SafetyBanner(modifier: Modifier = Modifier) {
        SafetyNumberChangedBanner(onCompare = {}, modifier = modifier)
    }

    /** 5.5 · Ghost ve süreli mesaj, for a sheet. */
    @Composable
    fun GhostSheet(chat: ChatSummary, onClose: () -> Unit) {
        GhostSettingsSheet(chat = chat, onSave = { _, _, _ -> onClose() }, onClose = onClose)
    }
}
