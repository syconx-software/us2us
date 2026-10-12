package com.syconx.us2us.runtime.support

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Static checks over the runtime's sources: the logging rules of [RuntimeLog] and "no network
 * except the relay" hold everywhere.
 */
class PrivacyLintTest {
    private val root = File("src/main/kotlin/com/syconx/us2us/runtime")

    private fun sources(): List<Pair<String, List<String>>> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).path to it.readLines() }.toList()

    /** The code of a line, without its comment (good enough for these checks: no URLs in strings). */
    private fun code(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
        return line.substringBefore(" // ")
    }

    @Test
    fun noPrintingOrForeignLoggers() {
        val sources = sources()
        assertTrue("found the sources", sources.size > 10)
        val forbidden = listOf("println(", "System.out", "System.err", "printStackTrace(", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "Log.wtf(")
        val print = Regex("""(^|[^\w.])print\(""")
        // The log sinks themselves: RuntimeLog, and the vault's own lines (VaultFiles.kt).
        val sinks = setOf("support/RuntimeLog.kt", "vault/VaultFiles.kt")
        for ((name, lines) in sources) {
            lines.forEachIndexed { number, line ->
                val code = code(line)
                for (token in forbidden) {
                    if (token in code && !(token.startsWith("Log.") && name in sinks)) fail("$name:${number + 1} uses $token")
                }
                if (print.containsMatchIn(code)) fail("$name:${number + 1} prints")
            }
        }
    }

    @Test
    fun logLinesCarryFixedEventsOnly() {
        val call = Regex("""RuntimeLog\.\w+\.(debug|info|notice|error)\((.*?)\)(\s*$|\s*})""")
        for ((name, lines) in sources()) {
            for (line in lines) {
                val match = call.find(code(line)) ?: continue
                val arguments = match.groupValues[2]
                // The event is a string literal without templates; the only other argument is a label.
                val event = Regex("""^"([^"$]*)"""").find(arguments.trim())
                if (event == null) fail("$name: log event is not a fixed string: $arguments")
                val rest = arguments.trim().removePrefix(event!!.value).trim().removePrefix(",").trim()
                if (rest.isNotEmpty()) {
                    val ok = rest.contains("logLabel") || rest.matches(Regex(""""[^"$]*"""")) || rest.endsWith(".name") ||
                        rest.contains("errorCodeName") || rest.matches(Regex("""failure\?\.logLabel \?: "closed""""))
                    if (!ok) fail("$name: log label is not a fixed category: $rest")
                }
            }
        }
    }

    @Test
    fun noNetworkBesidesTheRelay() {
        for ((name, lines) in sources()) {
            lines.forEachIndexed { number, line ->
                val code = code(line)
                for (token in listOf("HttpURLConnection", "openConnection(", "java.net.Socket", "DatagramSocket", "URL(")) {
                    if (token in code) fail("$name:${number + 1} opens its own connection ($token)")
                }
                if (("OkHttpClient(" in code || "OkHttpClient.Builder(" in code || "defaultHttpClient(" in code) && name != "net/NetworkPolicy.kt") {
                    fail("$name:${number + 1} builds its own HTTP client")
                }
                for (scheme in listOf("http://", "https://")) {
                    // Only documentation may name a URL; the policy names schemes, not hosts. XMP's
                    // namespace is an identifier inside image files, never fetched.
                    if (scheme in code && "ns.adobe.com" !in code) fail("$name:${number + 1} names a URL")
                }
            }
        }
    }
}
