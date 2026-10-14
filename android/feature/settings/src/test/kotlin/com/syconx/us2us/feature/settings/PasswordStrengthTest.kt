package com.syconx.us2us.feature.settings

import com.syconx.us2us.domain.BackupPasswordRules
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.fixtures.FixturesMessengerService
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `spec/common-backup-passwords.txt` (PROTOCOL.md §5.5), read from the repository. */
internal object CommonBackupPasswords {
    fun load(): List<String> {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val spec = generateSequence(start) { it.parentFile }
            .map { File(it, "spec/common-backup-passwords.txt") }
            .firstOrNull { it.isFile }
            ?: error("spec/common-backup-passwords.txt not found above $start")
        val entries = spec.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        check(entries.isNotEmpty()) { "no entries in $spec" }
        return entries
    }
}

private fun log2(value: Double): Double = ln(value) / ln(2.0)

/** 07 · Password strength (7.2): iOS PasswordStrengthTests. */
class PasswordStrengthTest {
    @Test
    fun emptyIsWeakAndUnlit() {
        val strength = PasswordStrength.estimate("")
        assertEquals(PasswordStrength.Level.Weak, strength.level)
        assertEquals(0.0, strength.meterFraction, 0.0)
    }

    @Test
    fun sixDigitCodesAreWeak() {
        for (code in listOf("246813", "000000", "123456", "908172")) {
            assertEquals(code, PasswordStrength.Level.Weak, PasswordStrength.estimate(code).level)
            assertFalse(code, PasswordStrength.estimate(code).isAcceptable)
        }
    }

    @Test
    fun designPasswordIsStrongAt82Percent() {
        val strength = PasswordStrength.estimate(DesignFixtures.DEMO_EXPORT_PASSWORD)
        assertEquals(PasswordStrength.Level.Strong, strength.level)
        // 14 random lower-case letters and digits count in full: 14 × log2(36).
        assertTrue(abs(strength.bits - 14 * log2(36.0)) < 1e-9)
        // 7.2 lights 257.23 of 313.72 dp = 82 %: 32 dots, not a sliver of the 33rd.
        assertTrue(abs(strength.meterFraction * 313.72 - 257.23) < 0.3)
    }

    @Test
    fun repeatsRunsAndKnownFragmentsCountLess() {
        assertTrue(PasswordStrength.estimate("aaaaaaaaaaaaaa").bits < PasswordStrength.estimate(DesignFixtures.DEMO_EXPORT_PASSWORD).bits)
        assertEquals(PasswordStrength.Level.Weak, PasswordStrength.estimate("abcdefghijklmn").level)
        assertTrue(PasswordStrength.estimate("parola2026parola").bits < PasswordStrength.estimate("kedi2026yolda!x").bits)
    }

    /** Names, places, clubs and words with a year, a date or a symbol are what a guesser tries first: never “Güçlü”, and not enough to export. */
    @Test
    fun namesAndYearsAreWeak() {
        val passwords = listOf(
            "Ahmet1985!", "Kerem.2010", "Ataturk1923!", "Elif.Kaya.1990", "Summer2026!", "kedi20262026", "Zeynep2015",
            "BJK1903bjk", "Mustafa1881", "Kagan1990!", "Galatasaray1905!", "ahmetkaya1990", "İstanbul2024!",
            "Şifrem2024", "kuzey7deniz42k",
        )
        for (password in passwords) {
            val strength = PasswordStrength.estimate(password)
            assertTrue(password, strength.level < PasswordStrength.Level.Strong)
            assertFalse(password, strength.isAcceptable)
        }
    }

    @Test
    fun keyboardWalksLeetSpellingsAndDatesAreWeak() {
        for (password in listOf("1q2w3e4r5t6y7u", "zaq12wsxcde3", "qwerty123456", "P@ssw0rd2026", "s1fr3m2024", "01011990", "19.05.1919")) {
            assertEquals(password, PasswordStrength.Level.Weak, PasswordStrength.estimate(password).level)
        }
    }

    @Test
    fun longMixedPassphrasesAreVeryStrong() {
        assertEquals(PasswordStrength.Level.VeryStrong, PasswordStrength.estimate("Gece-yarısı 3 kedi, 2 hat!").level)
        // Five common words: “Güçlü”, while four are only just enough.
        assertEquals(PasswordStrength.Level.Strong, PasswordStrength.estimate("kedi deniz kitap elma kalem").level)
    }

    @Test
    fun generatedPasswordsCountAtTheirExactStrength() {
        assertEquals(PasswordStrength.Level.VeryStrong, PasswordStrength.Generated.level)
        assertTrue(abs(PasswordStrength.Generated.bits - 20 * log2(31.0)) < 1e-9)
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        repeat(200) {
            val password = service.generateBackupPassword()
            val typed = PasswordStrength.estimate(password)
            // Typed or pasted, it never counts more than the generator gives.
            assertTrue(password, typed.bits <= PasswordStrength.Generated.bits + 1e-9)
            assertEquals(password, PasswordStrength.Level.VeryStrong, typed.level)
            assertTrue(password, BackupPasswordRules.isGeneratedFormat(password))
        }
        // The generator's shape alone is not trusted.
        assertEquals(PasswordStrength.Level.Weak, PasswordStrength.estimate("aaaa-aaaa-aaaa-aaaa-aaaa").level)
    }

    @Test
    fun everyListedCommonPasswordIsWeak() {
        for (password in CommonBackupPasswords.load()) {
            assertEquals(password, PasswordStrength.Level.Weak, PasswordStrength.estimate(password).level)
        }
    }

    @Test
    fun theMeterIsFullAt88Bits() {
        assertEquals(1.0, PasswordStrength(120.0).meterFraction, 0.0)
        assertEquals(0.5, PasswordStrength(44.15).meterFraction, 1e-9)
        assertEquals(PasswordStrength.Level.Fair, PasswordStrength(50.0).level)
        assertEquals(PasswordStrength.Level.Strong, PasswordStrength(60.0).level)
        assertEquals(PasswordStrength.Level.VeryStrong, PasswordStrength(80.0).level)
    }
}
