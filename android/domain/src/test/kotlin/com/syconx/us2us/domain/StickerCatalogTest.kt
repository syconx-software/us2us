package com.syconx.us2us.domain

import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerRecents
import com.syconx.us2us.domain.sticker.StickerResolution
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StickerCatalogTest {
    private val hello = StickerCatalog.sticker(101)!!
    private val cupHello = StickerCatalog.sticker(1)!!

    @Test
    fun `ids are unique and every pack has its cover`() {
        assertEquals(StickerCatalog.all.size, StickerCatalog.all.map { it.id }.toSet().size)
        assertEquals(38, StickerCatalog.all.size)
        for (pack in StickerCatalog.packs) assertEquals(pack.id, StickerCatalog.sticker(pack.coverId)?.packId)
        assertTrue(StickerCatalog.all.all { it.packId in StickerCatalog.packs.map { pack -> pack.id } })
        assertTrue(StickerCatalog.retiredIds.none { StickerCatalog.sticker(it) != null })
    }

    @Test
    fun `selections name the language only for stickers with words`() {
        assertEquals("101.tr", com.syconx.us2us.domain.sticker.BrandStickerSelection(hello, StickerLanguage.Turkish).id)
        assertEquals("1.-", com.syconx.us2us.domain.sticker.BrandStickerSelection(cupHello, StickerLanguage.English).id)
        val content = com.syconx.us2us.domain.sticker.BrandStickerSelection(hello, StickerLanguage.English).messageContent
        assertEquals(MessageContent.Sticker("us2us.words", 101, "👋", "en"), content)
        assertEquals("", com.syconx.us2us.domain.sticker.BrandStickerSelection(cupHello, StickerLanguage.English).wireLanguage)
    }

    @Test
    fun `received stickers resolve like PROTOCOL 8·8 says`() {
        val inTurkish = StickerCatalog.resolve("us2us.words", 101, "👋", "tr-TR", StickerLanguage.English)
        assertEquals(StickerLanguage.Turkish, (inTurkish as StickerResolution.Sticker).selection.language)
        val noLanguage = StickerCatalog.resolve("us2us.words", 101, "👋", "", StickerLanguage.English)
        assertEquals(StickerLanguage.English, (noLanguage as StickerResolution.Sticker).selection.language)
        assertEquals(StickerResolution.LegacyText("tamam!"), StickerCatalog.resolve("us2us.text", 0, " tamam! ", "", StickerLanguage.Turkish))
        assertEquals(StickerResolution.Emoji("🦄"), StickerCatalog.resolve("other.pack", 101, "🦄", "", StickerLanguage.Turkish))
        assertEquals(StickerResolution.Emoji("🦄"), StickerCatalog.resolve("us2us.cups", 101, "🦄", "", StickerLanguage.Turkish))
        assertEquals(StickerResolution.Unavailable, StickerCatalog.resolve("x", 9999, "  ", "", StickerLanguage.Turkish))
        assertNull(StickerLanguage.fromWireCode("de"))
        assertNull(StickerLanguage.fromWireCode("tr" + "-x".repeat(20)))
        assertEquals(StickerLanguage.English, StickerLanguage.fromWireCode("EN_gb"))
        assertEquals(StickerLanguage.Turkish, StickerLanguage.forLocale(Locale.forLanguageTag("tr-TR")))
        assertEquals(StickerLanguage.English, StickerLanguage.forLocale(Locale.GERMAN))
    }

    @Test
    fun `quote summaries say the words as drawn`() {
        val words = StickerCatalog.resolution(MessageContent.Sticker("us2us.words", 103, "🫂", "en"), StickerLanguage.Turkish)
        assertEquals("🫂 I hear you", words?.summary(StickerLanguage.Turkish))
        val art = StickerCatalog.resolution(MessageContent.Sticker("us2us.cups", 1, "👋", ""), StickerLanguage.Turkish)
        assertEquals("👋 El sallayan bardak", art?.summary(StickerLanguage.Turkish))
        assertEquals("tamam!", StickerCatalog.resolution(MessageContent.TextSticker("tamam!"), StickerLanguage.Turkish)?.summary(StickerLanguage.English))
        assertNull(StickerCatalog.resolution(MessageContent.Text("x"), StickerLanguage.Turkish))
        assertEquals("Merhaba! (Hi!)", hello.accessibilityLabel(StickerLanguage.Turkish, StickerLanguage.English))
    }

    @Test
    fun `recents keep the newest first, without repeats, at most twelve`() {
        var ids = listOf("6.-", "101.tr")
        ids = StickerRecents.adding(com.syconx.us2us.domain.sticker.BrandStickerSelection(hello, StickerLanguage.Turkish), ids)
        assertEquals(listOf("101.tr", "6.-"), ids)
        ids = StickerRecents.adding(com.syconx.us2us.domain.sticker.BrandStickerSelection(hello, StickerLanguage.English), ids)
        assertEquals(listOf("101.en", "101.tr", "6.-"), ids)
        var many = emptyList<String>()
        for (sticker in StickerCatalog.all) many = StickerRecents.adding(com.syconx.us2us.domain.sticker.BrandStickerSelection(sticker, StickerLanguage.Turkish), many)
        assertEquals(StickerRecents.LIMIT, many.size)
        val selections = StickerRecents.selections(listOf("101.tr", "9999.-", "101.tr", "1.-", "x", "101."), StickerLanguage.English)
        // “101.” has no language: the app's; a repeat and unknown ids are skipped.
        assertEquals(listOf("101.tr", "1.-", "101.en"), selections.map { it.id })
        assertEquals(StickerLanguage.English, StickerRecents.selection("101.", StickerLanguage.English)?.language)
    }
}
