package com.syconx.us2us.core.username

import com.syconx.us2us.core.text.UnicodeText
import com.syconx.us2us.core.username.UsernameRules.Violation
import com.syconx.us2us.core.vectors.VectorFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.random.Random

/** [UsernameRules] against the case table shared with the iOS app (spec/username-rules.json). */
class UsernameRulesTest {
    private class Case(val input: String, val normalized: String, val violations: List<String>, val valid: Boolean)

    private val cases: List<Case> =
        Json.parseToJsonElement(File(VectorFile.specDir(), "username-rules.json").readText()).jsonObject
            .getValue("cases").jsonArray.map { element ->
                val case = element.jsonObject
                Case(
                    input = case.getValue("input").jsonPrimitive.content,
                    normalized = case.getValue("normalized").jsonPrimitive.content,
                    violations = case.getValue("violations").jsonArray.map { it.jsonPrimitive.content },
                    valid = case.getValue("valid").jsonPrimitive.boolean,
                )
            }

    @TestFactory
    fun `every case of the shared table`(): List<DynamicTest> = cases.mapIndexed { index, case ->
        DynamicTest.dynamicTest("#$index '${case.input.escaped()}'") {
            val normalized = UsernameRules.normalize(case.input)
            assertEquals(case.normalized, normalized, "normalized")
            assertEquals(case.violations, UsernameRules.violations(normalized).map(::name), "violations")
            assertEquals(case.violations.firstOrNull(), UsernameRules.violation(normalized)?.let(::name), "violation")
            assertEquals(case.valid, UsernameRules.isValid(normalized), "isValid")
            assertEquals(if (case.valid) case.normalized else null, UsernameRules.username(case.input)?.value, "username")

            // The protocol's own parser gives the same answer for the input without its '@'.
            val withoutAt = withoutAt(case.input)
            assertEquals(UsernameRules.username(case.input)?.value, Username.parse(withoutAt)?.value, "Username.parse")
            assertEquals(case.valid, Username.check(withoutAt) == null, "Username.check")
        }
    }

    @Test
    fun `the table names every violation and is consistent`() {
        assertTrue(cases.isNotEmpty())
        assertEquals(NAMES.values.toSet(), cases.flatMap { it.violations }.toSet())
        cases.forEach { assertEquals(it.valid, it.violations.isEmpty(), it.input.escaped()) }
    }

    @Test
    fun `typed text is never turned into another name`() {
        mapOf("Kağan" to "kağan", "Ali Veli" to "ali veli", "İsmail" to "i̇smail", "a".repeat(30) to "a".repeat(30))
            .forEach { (typed, normalized) ->
                assertEquals(normalized, UsernameRules.normalize(typed), typed)
                assertNull(UsernameRules.username(typed), typed)
            }
    }

    @Test
    fun `only one leading at sign is dropped`() {
        assertEquals("deniz", UsernameRules.username("@deniz")?.value)
        assertEquals("deniz", UsernameRules.username("　@Deniz ")?.value)
        assertNull(UsernameRules.username("@@deniz"))
        assertNull(UsernameRules.username("deniz@"))
        assertNull(Username.parse("@deniz"), "the protocol's parser itself does not drop it")
    }

    @Test
    fun `length is counted in code points of the normalized name as on iOS`() {
        // An İ lowercases to two code points, i and U+0307, so it counts as two.
        val longest = "İ" + "a".repeat(UsernameRules.MAX_LENGTH - 2)
        assertEquals(listOf(Violation.TURKISH_CHARACTERS), UsernameRules.violations(UsernameRules.normalize(longest)))
        assertEquals(
            listOf(Violation.TURKISH_CHARACTERS, Violation.LENGTH),
            UsernameRules.violations(UsernameRules.normalize(longest + "a")),
        )
        assertEquals(listOf(Violation.TURKISH_CHARACTERS), UsernameRules.violations(UsernameRules.normalize("aİ")))
        assertEquals(listOf(Violation.LENGTH), UsernameRules.violations("a".repeat(UsernameRules.MAX_LENGTH + 1)))
        assertEquals(emptyList<Violation>(), UsernameRules.violations("a".repeat(UsernameRules.MAX_LENGTH)))
        // A combining mark is a code point of its own: it is reported, the letter before it is not.
        assertEquals(listOf(Violation.CHARACTERS), UsernameRules.violations(UsernameRules.normalize("q́bc")))
        assertEquals(
            listOf(Violation.TURKISH_CHARACTERS, Violation.CHARACTERS, Violation.LENGTH),
            UsernameRules.violations(UsernameRules.normalize("ş́")),
        )
    }

    @Test
    fun `violations agree with the protocol's parser for any input`() {
        val alphabet = listOf(
            "a", "k", "z", "A", "Z", "0", "9", "_", ".", "-", "@", " ", "　", "\u0085", "\u001c",
            "ı", "İ", "ş", "Ş", "ğ", "ü", "Ö", "ç", "é", "é", "̇", "ａ", "ﬁ", "K", "👍",
        )
        val random = Random(31)
        repeat(5000) {
            val input = buildString { repeat(random.nextInt(0, 28)) { append(alphabet.random(random)) } }
            val normalized = UsernameRules.normalize(input)
            val violations = UsernameRules.violations(normalized)
            assertEquals(violations.sortedBy { it.ordinal }.distinct(), violations, input.escaped())
            assertEquals(Username.parse(withoutAt(input))?.value, UsernameRules.username(input)?.value, input.escaped())
            assertEquals(violations.isEmpty(), Username.check(withoutAt(input)) == null, input.escaped())
        }
    }

    private companion object {
        val NAMES = mapOf(
            Violation.TURKISH_CHARACTERS to "turkishCharacters",
            Violation.CHARACTERS to "characters",
            Violation.FIRST_LETTER to "firstLetter",
            Violation.LENGTH to "length",
        )

        fun name(violation: Violation): String = NAMES.getValue(violation)

        /** The fixture's first two steps: trim Unicode `White_Space`, drop one leading '@'. */
        fun withoutAt(input: String) = UnicodeText.trimWhitespace(input).removePrefix("@")

        fun String.escaped(): String = buildString {
            for (c in this@escaped) {
                if (Character.isISOControl(c) || Character.isSpaceChar(c)) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
