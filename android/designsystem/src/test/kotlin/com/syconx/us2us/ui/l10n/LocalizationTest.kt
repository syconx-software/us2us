package com.syconx.us2us.ui.l10n

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.syconx.us2us.shot.ShotPaths
import com.syconx.us2us.ui.R
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * iOS `LocalizationTests`, and the conversion itself: every key of every catalogue resolves in
 * Turkish and English to exactly the catalogue's text, with the same arguments in the same places.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tr = Locale.forLanguageTag("tr")
    private val en = Locale.forLanguageTag("en")

    @Test
    fun everyCatalogueKeyResolvesToItsTextInBothLanguages() {
        var checked = 0
        var keys = 0
        for (table in StringTable.entries) {
            val catalogue = JSONObject(File(ShotPaths.repoDir, table.catalogue).readText()).getJSONObject("strings")
            assertEquals("${table.catalogue}: every key is indexed (re-run scripts/xcstrings_to_android.py)", catalogue.length(), table.strings.size)
            keys += catalogue.length()
            for (entry in table.strings) {
                val item = catalogue.getJSONObject(entry.key)
                val localizations = item.getJSONObject("localizations")
                for (lang in listOf("tr", "en")) {
                    val strings = U2Strings.of(context, Locale.forLanguageTag(lang))
                    val localization = localizations.getJSONObject(lang)
                    val where = "${table.catalogue} › ${entry.key} [$lang]"
                    when (val ref = entry.ref) {
                        is StringRef.Text -> {
                            val ios = localization.getJSONObject("stringUnit").getString("value")
                            val args = sampleArgs(entry.argumentTypes, count = 3)
                            val android = if (args.isEmpty()) strings[ref.id] else strings.get(ref.id, *args.toTypedArray())
                            assertEquals(where, formatIos(ios, args), android)
                        }
                        is StringRef.Plural -> {
                            val plural = localization.getJSONObject("variations").getJSONObject("plural")
                            for (category in plural.keys()) {
                                val count = when (category) {
                                    "one" -> 1
                                    "other" -> 7
                                    else -> error("$where: unexpected category $category")
                                }
                                val ios = plural.getJSONObject(category).getJSONObject("stringUnit").getString("value")
                                val args = sampleArgs(entry.argumentTypes, count)
                                assertEquals("$where/$category", formatIos(ios, args), strings.plural(ref.id, count, *args.toTypedArray()))
                            }
                        }
                    }
                    checked++
                }
            }
        }
        assertEquals("every key in two languages", keys * 2, checked)
        assertTrue("the app and kit catalogues hold at least 828 keys", keys >= 828)
    }

    @Test
    fun everyStringHasTurkishAndEnglish() {
        for (table in StringTable.entries) {
            val catalog = JSONObject(File(ShotPaths.repoDir, table.catalogue).readText())
            // The app's catalogues are written in Turkish first; Us2usKit and the scene's in English.
            val source = if (table == StringTable.Kit || table == StringTable.Scene3D) "en" else "tr"
            assertEquals(table.catalogue, source, catalog.getString("sourceLanguage"))
            val strings = catalog.getJSONObject("strings")
            assertTrue(strings.length() > 0)
            for (key in strings.keys()) {
                val localizations = strings.getJSONObject(key).getJSONObject("localizations")
                assertTrue("$key has no Turkish", localizations.has("tr"))
                assertTrue("$key has no English", localizations.has("en"))
            }
        }
    }

    @Test
    fun lookupInAnExplicitLanguage() {
        val trStrings = U2Strings.of(context, tr)
        val enStrings = U2Strings.of(context, en)
        assertEquals("Sohbetler", trStrings[R.string.common_chats_title])
        assertEquals("Chats", enStrings[R.string.common_chats_title])
        assertEquals("2 kişi seninle yazışmak istiyor", trStrings.plural(R.plurals.common_chats_requests_subtitle, 2, 2))
        assertEquals("1 person wants to message you", enStrings.plural(R.plurals.common_chats_requests_subtitle, 1, 1))
        assertEquals("2 people want to message you", enStrings.plural(R.plurals.common_chats_requests_subtitle, 2, 2))
        // By iOS key, with the arguments in order, as `String(localized: "chats.requests.subtitle \(2)")`.
        assertEquals("2 people want to message you", enStrings.get(StringTable.Common, "chats.requests.subtitle %lld", 2))
        assertEquals("Sohbetler", trStrings.get(StringTable.Common, "chats.title"))
        assertEquals(
            "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.",
            trStrings.get(StringTable.Lock, "lock.code.wrong.warning %lld %@", 2, "1 dakika"),
        )
        assertEquals(
            "Wrong code. 1 more wrong attempt and you'll have to wait 1 minute.",
            enStrings.plural(R.plurals.lock_code_wrong_warning, 1, 1, "1 minute"),
        )
        // A device language other than Turkish or English reads English (values/).
        assertEquals("Chats", U2Strings.of(context, Locale.GERMANY)[R.string.common_chats_title])
    }

    @Test
    fun resourceNamesFollowTheKeys() {
        assertEquals(StringRef.Text(R.string.chats_newchat_info), StringTable.Chats.find("newChat.info %@ %@"))
        assertEquals(StringRef.Text(R.string.chats_newchat_info), StringTable.Chats.find("newChat.info"))
        assertEquals(StringRef.Plural(R.plurals.lock_code_wrong_warning), StringTable.Lock.find("lock.code.wrong.warning"))
        assertEquals(StringRef.Text(R.string.contact_a11y_video), StringTable.Contact.find("a11y.video %@ %@ %@"))
        assertEquals(StringRef.Text(R.string.conversation_a11y_video), StringTable.Conversation.find("a11y.video %@ %@"))
        assertEquals(StringRef.Text(R.string.kit_new_message), StringTable.Kit.find("NEW_MESSAGE"))
        assertEquals(StringRef.Text(R.string.infoplist_nscamerausagedescription), StringTable.InfoPlist.find("NSCameraUsageDescription"))
        assertEquals(StringRef.Text(R.string.report_title), StringTable.Report.find("report.title"))
        assertEquals(StringRef.Text(R.string.report_a11y_report_note), StringTable.Report.find("a11y.report.note"))
        assertEquals(StringRef.Text(R.string.report_blocked), StringTable.Report.find("report.blocked %@"))
        assertNull(StringTable.Chats.find("chats.title"))
        assertEquals("a11y.typing", L10n.baseKey("a11y.typing %@"))
        assertEquals("a11y.code.entered", L10n.baseKey("a11y.code.entered %lld %lld"))
    }

    /** 9.3: upper-casing always passes the language. */
    @Test
    fun turkishUppercase() {
        val tr = Locale.forLanguageTag("tr")
        assertEquals("MESAJ İSTEKLERİ", L10n.uppercased("mesaj istekleri", tr))
        assertEquals("İLETİLDİ", L10n.uppercased("iletildi", tr))
        assertEquals("GİZLİ KODU GİR", "gizli kodu gir".u2Uppercase(tr))
        assertEquals("İSTEKLER", "istekler".u2Uppercase(tr))
        assertEquals("iletilemedi", L10n.lowercased("İLETİLEMEDİ", tr))
        assertEquals("REQUESTS", "requests".u2Uppercase(Locale.forLanguageTag("en")))
    }

    @Test
    fun languageResolution() {
        val turkey = Locale.forLanguageTag("tr-TR")
        assertEquals("tr_TR", AppLanguage.System.resolvedLocale(listOf("tr-TR"), turkey).toString())
        assertEquals("en_TR", AppLanguage.System.resolvedLocale(listOf("de-DE", "en-GB"), turkey).toString())
        assertEquals("en_TR", AppLanguage.System.resolvedLocale(listOf("de-DE"), turkey).toString())
        assertEquals("tr_TR", AppLanguage.System.resolvedLocale(listOf("de-DE", "tr"), turkey).toString())
        assertEquals("en_TR", AppLanguage.English.resolvedLocale(listOf("tr"), turkey).toString())
        assertEquals("tr", AppLanguage.Turkish.resolvedLocale(listOf("en"), Locale.forLanguageTag("tr")).toString())
        assertEquals(AppLanguage.English, AppLanguage.fromRaw("en"))
        assertEquals(AppLanguage.System, AppLanguage.fromRaw("system"))
        assertNull(AppLanguage.fromRaw("de"))
        assertTrue(AppLanguage.System.localeList.isEmpty)
        assertEquals("tr", AppLanguage.Turkish.localeList.toLanguageTags())
    }

    @Test
    @Config(qualifiers = "tr-rTR")
    fun theUiLocaleIsTheLanguageTheStringsResolvedTo() {
        assertEquals("tr", L10n.uiLanguage(context.resources))
        assertEquals("tr_TR", L10n.uiLocale(context.resources).toString())
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun anotherDeviceLanguageReadsEnglish() {
        assertEquals("en", L10n.uiLanguage(context.resources))
        assertEquals("en_DE", L10n.uiLocale(context.resources).toString())
        assertEquals("Chats", context.getString(R.string.common_chats_title))
    }

    @Test
    fun turkishSuffixes() {
        assertEquals("deniz’e", TurkishSuffix.dative("deniz"))
        assertEquals("ayla_’ya", TurkishSuffix.dative("ayla_"))
        assertEquals("kaan’a", TurkishSuffix.dative("kaan"))
        assertEquals("kaan2’ye", TurkishSuffix.dative("kaan2"))
        assertEquals("mert_k’e", TurkishSuffix.dative("mert_k"))
        assertEquals("deniz’in", TurkishSuffix.genitive("deniz"))
        assertEquals("ayla_’nın", TurkishSuffix.genitive("ayla_"))
        assertEquals("mert_k’in", TurkishSuffix.genitive("mert_k"))
        assertEquals("kaan2’nin", TurkishSuffix.genitive("kaan2"))
        assertEquals("bora’nın", TurkishSuffix.genitive("bora"))
        assertEquals("oya_t’ın", TurkishSuffix.genitive("oya_t"))
        assertEquals("selin_n’in", TurkishSuffix.genitive("selin_n"))
        assertEquals("göktürk’ün", TurkishSuffix.genitive("göktürk"))
        assertEquals("ali6’nın", TurkishSuffix.genitive("ali6"))
        assertEquals("ALİ’nin", TurkishSuffix.genitive("ALİ"))
        assertEquals("deniz’in", L10n.possessive("deniz", tr))
        assertEquals("deniz’s", L10n.possessive("deniz", en))
    }

    @Test
    fun emphasisedArguments() {
        val emphasis = SpanStyle(color = Color.White)
        val strings = U2Strings.of(context, tr)
        val requests = strings[R.string.chats_newchat_info_requests]
        val sentence = Emphasis.format(strings[R.string.chats_newchat_info], listOf("deniz", requests), mapOf(2 to emphasis), tr)
        assertEquals(strings.get(R.string.chats_newchat_info, "deniz", requests), sentence.text)
        val span = sentence.spanStyles.single()
        assertEquals(requests, sentence.text.substring(span.start, span.end))
        // The iOS way: the first occurrence of the emphasised words.
        val highlighted = Emphasis.highlight(sentence.text, requests, emphasis)
        assertEquals(span.start, highlighted.spanStyles.single().start)
        // Literal percent signs and implicit positions.
        val progress = Emphasis.format(strings[R.string.conversation_a11y_media_downloading_progress], listOf(40), mapOf(1 to emphasis), tr)
        assertEquals("İndiriliyor, %40", progress.text)
        assertNotNull(progress.spanStyles.singleOrNull())
    }

    private fun sampleArgs(types: String, count: Int): List<Any> {
        var numbers = 0
        return types.mapIndexed { index, type ->
            if (type == 'd') {
                numbers++
                if (numbers == 1) count else 30 + index
            } else {
                "‹${index + 1}›"
            }
        }
    }

    /** What Foundation makes of an iOS format: `%lld`, `%@`, positional or not, `%%`. */
    private fun formatIos(format: String, args: List<Any>): String {
        var implicit = 0
        return Regex("""%(?:(\d+)\$)?(lld|ld|d|@|%)""").replace(format) { m ->
            if (m.groupValues[2] == "%") return@replace "%"
            val position = m.groupValues[1].toIntOrNull() ?: ++implicit
            args[position - 1].toString()
        }
    }
}
