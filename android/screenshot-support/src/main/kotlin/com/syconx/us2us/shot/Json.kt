package com.syconx.us2us.shot

/**
 * A small JSON reader for the layout files: objects become [Map]s (key order kept), arrays [List]s,
 * numbers [Double]s. It needs nothing from Android, so layout checks also work outside Robolectric.
 */
internal object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value()
        reader.skipSpace()
        require(reader.atEnd) { "trailing characters at ${reader.position}" }
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipSpace() {
            while (!atEnd && text[position].isWhitespace()) position++
        }

        fun value(): Any? {
            require(!atEnd) { "unexpected end of JSON" }
            return when (val c = text[position]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $position")
            }
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++
            skipSpace()
            if (text[position] == '}') {
                position++
                return result
            }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                skipSpace()
                result[key] = value()
                skipSpace()
                when (text[position++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("expected ',' or '}' at ${position - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            position++
            skipSpace()
            if (text[position] == ']') {
                position++
                return result
            }
            while (true) {
                skipSpace()
                result += value()
                skipSpace()
                when (text[position++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("expected ',' or ']' at ${position - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = text[position++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = text[position++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> error("bad escape '\\$e' at ${position - 1}")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = position
            while (!atEnd && text[position] in "+-0123456789.eE") position++
            return text.substring(start, position).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, position)) { "unexpected token at $position" }
            position += word.length
            return value
        }

        private fun expect(c: Char) {
            require(!atEnd && text[position] == c) { "expected '$c' at $position" }
            position++
        }
    }
}
