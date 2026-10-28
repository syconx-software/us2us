package com.syconx.us2us.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A polite live region that draws nothing and says [text] when it appears or changes. For a status
 * that is shown with a running value (a lock-out wait counting down each second): the visible text
 * stays out of the live region, so TalkBack does not read every tick, and this says the sentence
 * once, as it read when the status began.
 */
@Composable
fun U2LiveAnnouncement(text: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (text != null) contentDescription = text else hideFromAccessibility()
            },
    )
}
