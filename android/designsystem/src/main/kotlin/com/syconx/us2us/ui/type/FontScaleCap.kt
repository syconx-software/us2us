package com.syconx.us2us.ui.type

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The system font size, followed up to [max]: for compact chrome that shares a row with the
 * content it describes (the chat header's Ghost chip next to the name). Past [max] the text keeps
 * that size, so the name keeps its room; TalkBack still reads the full value.
 */
@Composable
fun U2FontScaleCap(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}

/** The cap for compact chrome that shares a row (request decision buttons). */
const val U2_COMPACT_FONT_SCALE_CAP: Float = 1.3f

/** The cap for a chip beside a name in a header (the Ghost countdown): the name keeps the room. */
const val U2_HEADER_CHIP_FONT_SCALE_CAP: Float = 1.15f
