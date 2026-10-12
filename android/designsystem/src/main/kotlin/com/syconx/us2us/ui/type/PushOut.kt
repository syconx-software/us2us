package com.syconx.us2us.ui.type

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString

/**
 * The widow avoidance of iOS `Text` (TextKit's `lineBreakStrategy` `.pushOut`): a paragraph that
 * wraps onto a second line does not leave one word alone there; one word of the first line moves
 * down with it (7.1: “Eski telefonun erişimi / kapanmamış olabilir”, not “… kapanmamış /
 * olabilir”). The simulator shows it for two-line paragraphs only (5.5's and 6.3's three-line
 * captions keep their lone last word), so only those are changed. The design's own CSS layouts
 * (`GreedyParagraph`, `CSSLineClampText`, `U2DisplayText`) break greedily, as on iOS.
 *
 * Here the last two words of such a paragraph are joined by a no-break space, so the line breaker
 * keeps them together.
 */
internal object PushOut {
    private const val NO_BREAK_SPACE = '\u00A0'

    /**
     * Whether [layout] (of the text as it is) leaves a lone word on the second of exactly two
     * lines while the first line holds more than one word. `false` when the text was cut by
     * `maxLines` or has several paragraphs.
     */
    fun leavesWidow(layout: TextLayoutResult): Boolean {
        if (layout.lineCount != 2 || layout.hasVisualOverflow) return false
        val text = layout.layoutInput.text.text
        if ('\n' in text) return false
        val start = layout.getLineStart(1)
        val lastLine = text.substring(start).trim()
        if (lastLine.isEmpty() || lastLine.any(Char::isWhitespace)) return false
        return text.substring(0, start).trim().any { it == ' ' }
    }

    /** The space before the last word, or -1. */
    private fun lastSpace(text: CharSequence): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        for (index in end - 1 downTo 1) if (text[index] == ' ') return index
        return -1
    }

    /** [text] with its last two words joined by a no-break space, so they wrap together. */
    fun join(text: String): String {
        val index = lastSpace(text)
        if (index < 0) return text
        return text.substring(0, index) + NO_BREAK_SPACE + text.substring(index + 1)
    }

    fun join(text: AnnotatedString): AnnotatedString {
        val index = lastSpace(text)
        if (index < 0) return text
        return buildAnnotatedString {
            append(text.subSequence(0, index))
            append(NO_BREAK_SPACE)
            append(text.subSequence(index + 1, text.length))
        }
    }
}
