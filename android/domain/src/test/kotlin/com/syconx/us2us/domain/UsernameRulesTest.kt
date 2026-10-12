package com.syconx.us2us.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** [UsernameRules] against the case table shared with the iOS app and the core (spec/username-rules.json). */
class UsernameRulesTest {
    private data class Case(val input: String, val normalized: String, val violations: List<String>, val valid: Boolean)

    @Suppress("UNCHECKED_CAST")
    private val cases: List<Case> = run {
        val root = MiniJson.parse(SpecFiles.file("username-rules.json").readText()) as Map<String, Any?>
        (root.getValue("cases") as List<Map<String, Any?>>).map { case ->
            Case(
                input = case.getValue("input") as String,
                normalized = case.getValue("normalized") as String,
                violations = case.getValue("violations") as List<String>,
                valid = case.getValue("valid") as Boolean,
            )
        }
    }

    @TestFactory
    fun `every case of the shared table`(): List<DynamicTest> = cases.mapIndexed { index, case ->
        DynamicTest.dynamicTest("#$index") {
            val normalized = UsernameRules.normalize(case.input)
            assertEquals(case.normalized, normalized, "normalized")
            assertEquals(case.violations, UsernameRules.violations(normalized).map { it.raw }, "violations")
            assertEquals(case.violations.firstOrNull(), UsernameRules.violation(normalized)?.raw, "violation")
            assertEquals(case.valid, UsernameRules.isValid(normalized), "isValid")
        }
    }

    @Test
    fun `the table is not empty and names every rule`() {
        assertTrue(cases.size > 20)
        assertEquals(UsernameRules.Violation.entries.map { it.raw }.toSet(), cases.flatMap { it.violations }.toSet())
    }

    @Test
    fun `the 2·1 harness names break the rules the design says`() {
        assertEquals(listOf(UsernameRules.Violation.Length), UsernameRules.violations("se"))
        assertEquals(UsernameRules.Violation.Characters, UsernameRules.violation("selin.n"))
        assertEquals(UsernameRules.Violation.TurkishCharacters, UsernameRules.violation("kağan"))
        assertEquals(UsernameRules.Violation.FirstLetter, UsernameRules.violation("1selin"))
        assertTrue(UsernameRules.isValid("selin_n"))
        assertTrue(UsernameRules.isTooShort("se"))
        assertFalse(UsernameRules.isTooShort("sel"))
    }

    @Test
    fun `the field shows what is typed, lower case, never another name`() {
        assertEquals("selin", UsernameRules.displayForm("Selin"))
        assertEquals("kağan", UsernameRules.displayForm("Kağan"))
        // A dotted capital I lower-cases to i + U+0307: shown, and refused as Turkish.
        assertEquals("i̇stanbul", UsernameRules.displayForm("İstanbul"))
        assertEquals(UsernameRules.Violation.TurkishCharacters, UsernameRules.violation(UsernameRules.normalize("İstanbul")))
        // A fullwidth @ would become an @ and be dropped: shown as typed.
        assertEquals("＠selin", UsernameRules.displayForm("＠selin"))
        // Nothing is trimmed while typing.
        assertEquals(" selin", UsernameRules.displayForm(" Selin"))
        assertEquals(9, UsernameRules.length("i̇stanbul"))
    }

    @Test
    fun `normalize drops white space around and one leading at sign`() {
        assertEquals("selin_n", UsernameRules.normalize("  @Selin_N　"))
        assertEquals("@selin", UsernameRules.normalize("@@selin"))
        assertEquals("", UsernameRules.normalize("   "))
    }
}
