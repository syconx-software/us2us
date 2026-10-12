package com.syconx.us2us.fixtures

import com.syconx.us2us.core.profile.AvatarPresets
import com.syconx.us2us.core.sticker.StickerLanguage as CoreStickerLanguage
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import com.syconx.us2us.core.vault.UnlockAttemptPolicy
import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.BackupPasswordRules
import com.syconx.us2us.domain.SecretCodeRules
import com.syconx.us2us.domain.UnlockThrottle
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.syconx.us2us.core.sticker.StickerCatalog as CoreStickerCatalog
import com.syconx.us2us.core.username.UsernameRules as CoreUsernameRules

/** The domain's UI-shaped copies of protocol rules and catalogues agree with the core's. */
class CoreParityTest {
    @Test
    fun `the sticker catalogue is the core's`() {
        assertEquals(CoreStickerCatalog.VERSION, StickerCatalog.VERSION)
        assertEquals(CoreStickerCatalog.packs.map { Triple(it.id, it.titleKey, it.coverId) }, StickerCatalog.packs.map { Triple(it.id, it.titleKey, it.coverId) })
        assertEquals(CoreStickerCatalog.retiredIds, StickerCatalog.retiredIds)
        assertEquals(CoreStickerCatalog.all.size, StickerCatalog.all.size)
        for ((core, domain) in CoreStickerCatalog.all.zip(StickerCatalog.all)) {
            assertEquals(core.id, domain.id)
            assertEquals(core.packId, domain.packId, "${core.id}")
            assertEquals(core.key, domain.key, "${core.id}")
            assertEquals(core.emoji, domain.emoji, "${core.id}")
            assertEquals(core.label.turkish, domain.label.turkish, "${core.id}")
            assertEquals(core.label.english, domain.label.english, "${core.id}")
            assertEquals(core.lines?.turkish, domain.lines?.turkish, "${core.id}")
            assertEquals(core.lines?.english, domain.lines?.english, "${core.id}")
        }
        for (code in listOf("tr", "TR-tr", "en_GB", "", "de", "e")) {
            assertEquals(CoreStickerLanguage.fromWireCode(code)?.wireCode, StickerLanguage.fromWireCode(code)?.wireCode, code)
        }
    }

    @Test
    fun `the avatar presets are the core's`() {
        assertEquals(AvatarPresets.ids, AvatarPresetCatalog.all.map { it.id }.toSet())
    }

    @Test
    fun `the throttle table is the core's`() {
        for (failures in 0..15) {
            assertEquals(UnlockAttemptPolicy.delayAfter(failures), UnlockThrottle.delay(failures), "$failures")
            assertEquals(CoreRules.upcomingDelay(failures), UnlockThrottle.upcomingDelay(failures), "$failures")
        }
        assertEquals(UnlockAttemptPolicy.WIPE_AFTER_FAILURES, UnlockThrottle.WIPE_AFTER_FAILURES)
    }

    @Test
    fun `the code and password shapes are the core's`() {
        for (code in listOf("246813", "24681", "2468133", "24681a", "", "٢٤٦٨١٣")) {
            assertEquals(com.syconx.us2us.core.vault.SecretCodePolicy.isWellFormed(code), SecretCodeRules.isWellFormed(code), code)
        }
        assertEquals(BackupPasswordPolicy.MIN_LENGTH, BackupPasswordRules.MIN_LENGTH)
    }

    @Test
    fun `username rules agree with the core on any input`() {
        val alphabet = "abcxyzABCXYZ0129_.-@ıİşŞğĞüÜöÖçÇ 　̇é＠Ａ😀\t"
        val random = Random(20261008)
        val inputs = listOf("", "selin_n", "  @Selin_N ", "kağan", "İstanbul", "1selin", "selin.n", "ab", "a".repeat(25), "＠selin") +
            List(3_000) { String(CharArray(random.nextInt(0, 30)) { alphabet[random.nextInt(alphabet.length)] }) }
        for (input in inputs) {
            val normalized = UsernameRules.normalize(input)
            assertEquals(CoreUsernameRules.normalize(input), normalized, input)
            assertEquals(CoreUsernameRules.violations(normalized).map { it.name }, UsernameRules.violations(normalized).map { it.name.toScreamingSnake() }, input)
            assertEquals(CoreUsernameRules.violation(input)?.name, CoreRules.usernameViolation(input)?.name?.toScreamingSnake(), input)
        }
    }

    private fun String.toScreamingSnake(): String = replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    @Test
    fun `generated passwords have the shape and pass the rules`() {
        val alphabet = BackupPasswordRules.GENERATED_ALPHABET.toSet()
        repeat(1_000) {
            val password = BackupPasswordGenerator.generate()
            assertEquals(24, password.length, password)
            val groups = password.split('-')
            assertEquals(5, groups.size, password)
            assertTrue(groups.all { group -> group.length == 4 && group.all { it in alphabet } }, password)
            assertTrue(BackupPasswordRules.isGeneratedFormat(password), password)
            assertNull(BackupPasswordPolicy.check(password), password)
        }
        assertEquals(10_000, List(10_000) { BackupPasswordGenerator.generate() }.toSet().size)
    }

    @Test
    fun `every generated symbol is equally likely`() {
        val tally = HashMap<Char, Int>()
        for (byte in 0..255) {
            var draws = 0
            val password = BackupPasswordGenerator.generate { count ->
                draws += 1
                ByteArray(count) { (if (draws == 1) byte else 0).toByte() }
            }
            val symbols = password.filter { it != '-' }.toSet()
            if (byte < 248) {
                val symbol = BackupPasswordRules.GENERATED_ALPHABET[byte % 31]
                assertEquals(1, draws)
                assertEquals(setOf(symbol), symbols)
                tally[symbol] = (tally[symbol] ?: 0) + 1
            } else {
                assertEquals(2, draws)
                assertEquals(setOf('a'), symbols)
            }
        }
        assertEquals(31, tally.size)
        assertTrue(tally.values.all { it == 8 })
        assertFalse(BackupPasswordRules.isGeneratedFormat("k7mq-x2pd-9tfa-wn3h-b8z"))
    }
}
