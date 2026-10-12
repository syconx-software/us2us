package com.syconx.us2us.ui.l10n

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.util.Locale

/**
 * Sentences with emphasised parts, for strings whose argument is set apart (4.2 `newChat.info`:
 * “… onun **Mesaj istekleri** kutusunda bekler.”, the second argument in `text` over
 * `textSecondary`).
 */
object Emphasis {
    private val specifier = Regex("""%(?:(\d+)\$)?([sd%])""")

    /**
     * [sentence] with the first occurrence of [emphasis] in [style] (iOS
     * `AttributedString.range(of:)`). Unchanged when [emphasis] does not occur.
     */
    fun highlight(sentence: String, emphasis: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
        append(sentence)
        val start = if (emphasis.isEmpty()) -1 else sentence.indexOf(emphasis)
        if (start >= 0) addStyle(style, start, start + emphasis.length)
    }

    /**
     * Formats [template] (a string resource as stored, read without arguments: `%1$s`, `%d`,
     * `%%`) with [args], and sets the arguments whose 1-based positions are keys of [styles] in
     * those styles. Unlike [highlight] it never styles a matching word elsewhere in the sentence
     * (a username that happens to contain it).
     */
    fun format(template: String, args: List<Any>, styles: Map<Int, SpanStyle>, locale: Locale): AnnotatedString =
        buildAnnotatedString {
            var implicit = 0
            var cursor = 0
            for (match in specifier.findAll(template)) {
                append(template, cursor, match.range.first)
                cursor = match.range.last + 1
                val kind = match.groupValues[2]
                if (kind == "%") {
                    append('%')
                    continue
                }
                val position = match.groupValues[1].toIntOrNull() ?: ++implicit
                val arg = args.getOrNull(position - 1)
                    ?: throw IllegalArgumentException("'$template' needs argument $position, got ${args.size}")
                val text = String.format(locale, "%$kind", arg)
                val style = styles[position]
                if (style != null) {
                    val start = length
                    append(text)
                    addStyle(style, start, length)
                } else {
                    append(text)
                }
            }
            append(template, cursor, template.length)
        }
}
