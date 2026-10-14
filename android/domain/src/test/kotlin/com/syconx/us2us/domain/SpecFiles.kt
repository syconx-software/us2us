package com.syconx.us2us.domain

import java.io.File

/** The repository's `spec/` folder, found from the test's working directory (the module folder). */
object SpecFiles {
    fun file(name: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "spec/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("spec/$name not found above ${File("").absolutePath}")
    }
}

/** A small JSON reader for the spec tables (objects, arrays, strings, numbers, booleans, null). */
class MiniJson private constructor(private val text: String) {
    private var index = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val value = readValue()
            skipWhitespace()
            check(index == text.length) { "trailing characters at $index" }
            value
        }
    }

    private fun readValue(): Any? {
        skipWhitespace()
        return when (val char = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (char == '-' || char.isDigit()) readNumber() else error("unexpected '$char' at $index")
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        check(text.startsWith(word, index)) { "expected $word at $index" }
        index += word.length
        return value
    }

    private fun readNumber(): Double {
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
        return text.substring(start, index).toDouble()
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        index++
        skipWhitespace()
        if (text[index] == '}') return result.also { index++ }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            check(text[index] == ':') { "expected ':' at $index" }
            index++
            result[key] = readValue()
            skipWhitespace()
            when (text[index++]) {
                ',' -> continue
                '}' -> return result
                else -> error("expected ',' or '}' at ${index - 1}")
            }
        }
    }

    private fun readArray(): List<Any?> {
        val result = ArrayList<Any?>()
        index++
        skipWhitespace()
        if (text[index] == ']') return result.also { index++ }
        while (true) {
            result += readValue()
            skipWhitespace()
            when (text[index++]) {
                ',' -> continue
                ']' -> return result
                else -> error("expected ',' or ']' at ${index - 1}")
            }
        }
    }

    private fun readString(): String {
        check(text[index] == '"') { "expected a string at $index" }
        index++
        val out = StringBuilder()
        while (true) {
            when (val char = text[index++]) {
                '"' -> return out.toString()
                '\\' -> when (val escaped = text[index++]) {
                    '"', '\\', '/' -> out.append(escaped)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        out.append(text.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> error("bad escape '$escaped'")
                }
                else -> out.append(char)
            }
        }
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }
}
