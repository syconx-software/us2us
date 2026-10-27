package com.syconx.us2us.domain

import java.net.URI

/** Local detection only: no previews, network lookups or invitation tokens in external URLs. */
object MessageLinks {
    sealed interface Target {
        data class Web(val url: String) : Target
        class CommunityInvite(val code: String) : Target {
            override fun toString() = "CommunityInvite(redacted)"
        }
    }
    data class Match(val start: Int, val end: Int, val target: Target)
    private val pattern = Regex("""(?<![\p{L}\p{N}_:/-])(?:us2us-community:v1:[A-Za-z0-9_-]+|(?i:https?://[^\s<>]+|www\.[^\s<>]+))""")

    fun detect(text: String): List<Match> {
        if (':' !in text && '.' !in text) return emptyList()
        return pattern.findAll(text).mapNotNull { match ->
        var value = match.value
        if (value.startsWith("us2us-community:v1:")) {
            return@mapNotNull if (value.length < 2048) Match(match.range.first, match.range.last + 1, Target.CommunityInvite(value)) else null
        }
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        val counts = value.filter { it in "()[]{}" }.groupingBy { it }.eachCount().toMutableMap()
        var end = value.length
        while (end > 0) {
            val last = value[end - 1]
            val opening = pairs[last]
            if (last in ".,;:!?\"'”’" || (opening != null && counts.getOrDefault(last, 0) > counts.getOrDefault(opening, 0))) {
                if (opening != null) counts[last] = counts.getOrDefault(last, 0) - 1
                end--
            } else break
        }
        value = value.substring(0, end)
        val address = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
        val uri = try { URI(address) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            catch (_: java.net.URISyntaxException) { return@mapNotNull null }
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return@mapNotNull null
        Match(match.range.first, match.range.first + value.length, Target.Web(address))
    }.toList()
    }
}
