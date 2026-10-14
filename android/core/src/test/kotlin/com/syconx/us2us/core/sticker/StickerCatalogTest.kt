package com.syconx.us2us.core.sticker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The built-in sticker set (PROTOCOL.md §8.8): stable wire ids, Turkish and English everywhere, an
 * emoji fallback for every sticker, and how a received `Sticker` is drawn. Mirrors the iOS app's
 * StickerCatalogTests, so both platforms render the same set.
 */
class StickerCatalogTest {
    /** The published wire table. Rows may be appended; a row is never changed or removed. */
    private val wireTable = listOf(
        Triple(1, "us2us.cups", "cups.hello"), Triple(2, "us2us.cups", "cups.together"), Triple(3, "us2us.cups", "cups.laugh"),
        Triple(4, "us2us.cups", "cups.cry"), Triple(5, "us2us.cups", "cups.wow"), Triple(6, "us2us.cups", "cups.love"),
        Triple(7, "us2us.cups", "cups.wink"), Triple(8, "us2us.cups", "cups.think"), Triple(9, "us2us.cups", "cups.hug"),
        Triple(10, "us2us.cups", "cups.tangled"), Triple(11, "us2us.cups", "cups.listen"),
        Triple(101, "us2us.words", "words.hello"), Triple(102, "us2us.words", "words.lineOpen"),
        Triple(103, "us2us.words", "words.hearYou"), Triple(104, "us2us.words", "words.betweenUs"),
        Triple(105, "us2us.words", "words.talkLater"), Triple(106, "us2us.words", "words.missYou"),
        Triple(107, "us2us.words", "words.goodMorning"), Triple(108, "us2us.words", "words.goodNight"),
        Triple(109, "us2us.words", "words.ok"), Triple(110, "us2us.words", "words.thanks"), Triple(111, "us2us.words", "words.haha"),
        Triple(112, "us2us.words", "words.hello.phone"),
        Triple(201, "us2us.privacy", "privacy.lock"), Triple(202, "us2us.privacy", "privacy.ghost"),
        Triple(203, "us2us.privacy", "privacy.key"), Triple(204, "us2us.privacy", "privacy.notLooking"),
        Triple(205, "us2us.privacy", "privacy.noTrace"), Triple(206, "us2us.privacy", "privacy.shh"),
        Triple(207, "us2us.privacy", "privacy.endToEnd"), Triple(208, "us2us.privacy", "privacy.verified"),
        Triple(301, "us2us.mood", "mood.coffee"), Triple(302, "us2us.mood", "mood.onMyWay"), Triple(303, "us2us.mood", "mood.busy"),
        Triple(304, "us2us.mood", "mood.party"), Triple(305, "us2us.mood", "mood.sleepy"), Triple(306, "us2us.mood", "mood.chill"),
        Triple(307, "us2us.mood", "mood.cheers"),
    )

    private val all = StickerCatalog.all

    private fun sticker(id: Int) = checkNotNull(StickerCatalog.sticker(id)) { "sticker $id" }

    // Ids

    @Test
    fun `ids are unique across every pack and match the published wire table`() {
        assertEquals(all.size, all.map { it.id }.toSet().size, "duplicate sticker id")
        assertEquals(all.size, all.map { it.key }.toSet().size, "duplicate sticker key")
        assertEquals(wireTable, all.map { Triple(it.id, it.packId, it.key) })
        assertEquals(38, all.size)
        assertEquals(1, StickerCatalog.VERSION)
    }

    @Test
    fun `retired ids are never handed out again, and 0 is never an id`() {
        for (sticker in all) {
            assertFalse(sticker.id in StickerCatalog.retiredIds, sticker.key)
            assertTrue(sticker.id > 0, "${sticker.key}: 0 is the legacy text sticker's id")
        }
    }

    @Test
    fun `pack ids are stable, every sticker is in a pack and every pack is used`() {
        assertEquals(listOf("us2us.cups", "us2us.words", "us2us.privacy", "us2us.mood"), StickerCatalog.packs.map { it.id })
        val packIds = StickerCatalog.packs.map { it.id }.toSet()
        assertFalse(StickerCatalog.LEGACY_TEXT_PACK_ID in packIds)
        for (sticker in all) assertTrue(sticker.packId in packIds, sticker.key)
        for (pack in StickerCatalog.packs) {
            assertTrue(StickerCatalog.stickers(pack.id).isNotEmpty(), pack.id)
            assertEquals(pack.id, StickerCatalog.sticker(pack.coverId)?.packId, "${pack.id} cover")
            assertEquals("sticker.pack." + pack.id.removePrefix("us2us."), pack.titleKey)
        }
        assertEquals(listOf(1, 101, 202, 301), StickerCatalog.packs.map { it.coverId })
        assertEquals(listOf(11, 12, 8, 7), StickerCatalog.packs.map { StickerCatalog.stickers(it.id).size })
    }

    @Test
    fun `lookups by id and by pack and id`() {
        assertEquals("words.ok", StickerCatalog.sticker(109)?.key)
        assertEquals("words.ok", StickerCatalog.sticker("us2us.words", 109)?.key)
        assertNull(StickerCatalog.sticker("us2us.cups", 109))
        assertNull(StickerCatalog.sticker(999))
        assertNull(StickerCatalog.sticker(-1))
        assertEquals("sticker.pack.mood", StickerCatalog.pack("us2us.mood")?.titleKey)
        assertNull(StickerCatalog.pack(StickerCatalog.LEGACY_TEXT_PACK_ID))
    }

    // Strings

    @Test
    fun `every sticker has a Turkish and an English label`() {
        for (sticker in all) {
            assertTrue(sticker.label.turkish.isNotBlank(), sticker.key)
            assertTrue(sticker.label.english.isNotBlank(), sticker.key)
            assertEquals(sticker.label.turkish, sticker.label[StickerLanguage.TURKISH])
            assertEquals(sticker.label.english, sticker.label[StickerLanguage.ENGLISH])
        }
    }

    @Test
    fun `text stickers have Turkish and English words, wordless ones have none`() {
        val text = all.filter { it.isText }
        assertEquals(16, text.size)
        assertTrue(StickerCatalog.stickers(StickerCatalog.WORDS_PACK_ID).all { it.isText })
        assertTrue(StickerCatalog.stickers(StickerCatalog.CUPS_PACK_ID).none { it.isText })
        assertEquals(listOf(205, 206), StickerCatalog.stickers(StickerCatalog.PRIVACY_PACK_ID).filter { it.isText }.map { it.id })
        assertEquals(listOf(302, 303), StickerCatalog.stickers(StickerCatalog.MOOD_PACK_ID).filter { it.isText }.map { it.id })
        for (sticker in text) {
            val lines = sticker.lines!!
            for (language in StickerLanguage.entries) {
                // One or two short lines: legible on a small picker cell.
                val shown = lines[language]
                assertTrue(shown.size in 1..2, "${sticker.key} $language")
                assertTrue(shown.all { it.isNotEmpty() && it.codePointCount(0, it.length) <= 12 }, "${sticker.key} $language")
                assertEquals(shown.joinToString(" "), sticker.text(language))
            }
        }
        for (sticker in all.filterNot { it.isText }) {
            assertNull(sticker.text(StickerLanguage.TURKISH), sticker.key)
            assertNull(sticker.text(StickerLanguage.ENGLISH), sticker.key)
        }
        assertEquals("Merhaba!", sticker(101).text(StickerLanguage.TURKISH))
        assertEquals("Hi!", sticker(101).text(StickerLanguage.ENGLISH))
        assertEquals("Seni duyuyorum", sticker(103).text(StickerLanguage.TURKISH))
        assertEquals("I hear you", sticker(103).text(StickerLanguage.ENGLISH))
        assertEquals(listOf("Line's", "open"), sticker(102).lines?.english)
    }

    @Test
    fun `every sticker has an emoji fallback`() {
        for (sticker in all) {
            val codePoints = sticker.emoji.codePoints().toArray()
            assertTrue(codePoints.isNotEmpty(), sticker.key)
            val isEmoji = codePoints.any { Character.isEmojiPresentation(it) } || 0xFE0F in codePoints
            assertTrue(isEmoji, "${sticker.key}: \"${sticker.emoji}\"")
            assertTrue(codePoints.none { Character.isLetter(it) }, sticker.key)
        }
        assertEquals("😵‍💫", sticker(10).emoji)
        assertEquals("☀️", sticker(107).emoji)
    }

    @Test
    fun `no empty or padded string anywhere`() {
        fun check(string: String, what: String) {
            assertTrue(string.isNotEmpty(), what)
            assertEquals(string.trim(), string, what)
            assertFalse("  " in string, "$what: \"$string\"")
        }
        for (sticker in all) {
            check(sticker.packId, "${sticker.id} pack")
            check(sticker.key, "${sticker.id} key")
            check(sticker.emoji, "${sticker.key} emoji")
            check(sticker.label.turkish, "${sticker.key} label tr")
            check(sticker.label.english, "${sticker.key} label en")
            for (language in StickerLanguage.entries) {
                sticker.lines?.get(language).orEmpty().forEach { check(it, "${sticker.key} line $language") }
            }
        }
        for (pack in StickerCatalog.packs) {
            check(pack.id, "pack")
            check(pack.titleKey, "${pack.id} title key")
        }
    }

    // Language

    @Test
    fun `Sticker lang codes`() {
        val known = mapOf(
            "tr" to StickerLanguage.TURKISH, "tr-TR" to StickerLanguage.TURKISH, "TR" to StickerLanguage.TURKISH,
            "en" to StickerLanguage.ENGLISH, "en-GB" to StickerLanguage.ENGLISH, "en_US" to StickerLanguage.ENGLISH,
            "EN_gb" to StickerLanguage.ENGLISH,
        )
        for ((code, language) in known) assertEquals(language, StickerLanguage.fromWireCode(code), code)
        for (code in listOf("", "de", "t", "-", "turkish", "tr-" + "x".repeat(40))) {
            assertNull(StickerLanguage.fromWireCode(code), "\"$code\"")
        }
        assertEquals(StickerLanguage.TURKISH, StickerLanguage.fromWireCode("tr-" + "x".repeat(32)), "35 characters are accepted")
        assertNull(StickerLanguage.fromWireCode("tr-" + "x".repeat(33)), "36 are not")
    }

    @Test
    fun `lang codes are read the same in a Turkish locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // "I".lowercase() in a Turkish locale would be a dotless ı; the wire code must not care.
            assertEquals(StickerLanguage.ENGLISH, StickerLanguage.fromWireCode("EN"))
            assertEquals(StickerLanguage.TURKISH, StickerLanguage.fromWireCode("TR"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the picker's language follows the app's, Turkish for Turkish and English otherwise`() {
        assertEquals(StickerLanguage.TURKISH, StickerLanguage.forLocale(Locale.forLanguageTag("tr-TR")))
        assertEquals(StickerLanguage.ENGLISH, StickerLanguage.forLocale(Locale.forLanguageTag("en-TR")))
        assertEquals(StickerLanguage.ENGLISH, StickerLanguage.forLocale(Locale.forLanguageTag("de-DE")))
        assertEquals(StickerLanguage.ENGLISH, StickerLanguage.TURKISH.other)
        assertEquals(StickerLanguage.TURKISH, StickerLanguage.ENGLISH.other)
        assertEquals("en", StickerLanguage.ENGLISH.wireCode)
        assertEquals("tr", StickerLanguage.TURKISH.wireCode)
    }

    @Test
    fun `a sent sticker carries its language only when it has words`() {
        val words = sticker(101)
        val cup = sticker(1)
        assertEquals("en", StickerSelection(words, StickerLanguage.ENGLISH).wireLanguage)
        assertEquals("tr", StickerSelection(words, StickerLanguage.TURKISH).wireLanguage)
        assertEquals("", StickerSelection(cup, StickerLanguage.ENGLISH).wireLanguage)
        assertEquals(StickerSelection(cup, StickerLanguage.ENGLISH).id, StickerSelection(cup, StickerLanguage.TURKISH).id)
        assertNotEquals(StickerSelection(words, StickerLanguage.ENGLISH).id, StickerSelection(words, StickerLanguage.TURKISH).id)
    }

    @Test
    fun `the screen reader reads the app's language, and the words as drawn when they are in the other one`() {
        val hello = sticker(101)
        assertEquals("Merhaba!", hello.accessibilityLabel(shownIn = StickerLanguage.TURKISH, ui = StickerLanguage.TURKISH))
        assertEquals("Merhaba! (Hi!)", hello.accessibilityLabel(shownIn = StickerLanguage.TURKISH, ui = StickerLanguage.ENGLISH))
        assertEquals("El sallayan bardak", sticker(1).accessibilityLabel(shownIn = StickerLanguage.ENGLISH, ui = StickerLanguage.TURKISH))
    }

    // Receiving

    @Test
    fun `a received sticker is drawn in the language it was sent in`() {
        assertEquals(
            StickerResolution.BuiltIn(StickerSelection(sticker(106), StickerLanguage.ENGLISH)),
            StickerCatalog.resolve("us2us.words", 106, "🥺", "en", fallback = StickerLanguage.TURKISH),
        )
        assertEquals(
            StickerResolution.BuiltIn(StickerSelection(sticker(205), StickerLanguage.TURKISH)),
            StickerCatalog.resolve("us2us.privacy", 205, "🫥", "tr-TR", fallback = StickerLanguage.ENGLISH),
        )
    }

    @Test
    fun `no lang from older senders, or an unknown one, is drawn in the reader's language`() {
        val ok = sticker(109)
        assertEquals(
            StickerResolution.BuiltIn(StickerSelection(ok, StickerLanguage.TURKISH)),
            StickerCatalog.resolve("us2us.words", 109, "👌", "", fallback = StickerLanguage.TURKISH),
        )
        assertEquals(
            StickerResolution.BuiltIn(StickerSelection(ok, StickerLanguage.ENGLISH)),
            StickerCatalog.resolve("us2us.words", 109, "👌", "de", fallback = StickerLanguage.ENGLISH),
        )
    }

    @Test
    fun `an unknown pack or id falls back to its emoji, and nothing at all is unavailable`() {
        assertEquals(StickerResolution.Emoji("🫶"), StickerCatalog.resolve("us2us.cups", 999, "🫶", "tr", StickerLanguage.TURKISH))
        assertEquals(StickerResolution.Emoji("🫶"), StickerCatalog.resolve("us2us.future", 1, "🫶", "", StickerLanguage.TURKISH))
        assertEquals(StickerResolution.Emoji("👋"), StickerCatalog.resolve("us2us.words", 1, "👋", "", StickerLanguage.TURKISH))
        assertEquals(StickerResolution.Emoji("😂"), StickerCatalog.resolve("cb.basic", 3, " 😂 ", "", StickerLanguage.TURKISH))
        assertEquals(StickerResolution.Unavailable, StickerCatalog.resolve("cb.basic", 3, " ", "", StickerLanguage.TURKISH))
    }

    @Test
    fun `the text stickers of earlier builds keep their card`() {
        assertEquals(StickerResolution.LegacyText("tamam!"), StickerCatalog.resolve("us2us.text", 0, "tamam!", "", StickerLanguage.ENGLISH))
        assertEquals(StickerResolution.Unavailable, StickerCatalog.resolve("us2us.text", 0, "", "", StickerLanguage.ENGLISH))
    }
}
