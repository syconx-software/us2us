package com.syconx.us2us.domain.sticker

import com.syconx.us2us.domain.MessageContent
import java.util.Locale

// The built-in sticker set (PROTOCOL.md §8.8) as data: ids, packs, emoji, labels and words. The
// artwork is drawn in code by `ui.brand` from the id. The same set as the core's `StickerCatalog`
// and the iOS app's `BrandStickerCatalog`; :fixtures tests that the three agree.

/**
 * The language a sticker's words are drawn in. A sent sticker carries the language it was shown in
 * (`Sticker.lang`, BCP-47 “tr” / “en”) and the recipient draws exactly that language, so both ends
 * see the same sticker.
 */
enum class StickerLanguage(
    /** The BCP-47 tag sent in `Sticker.lang`. */
    val wireCode: String,
) {
    Turkish("tr"),
    English("en"),
    ;

    /** The other one: the picker's TR/EN switch. */
    val other: StickerLanguage get() = if (this == Turkish) English else Turkish

    companion object {
        /** The app's language: Turkish for Turkish, English otherwise (the same rule as the app language). */
        fun forLocale(locale: Locale): StickerLanguage = if (locale.language == "tr") Turkish else English

        /**
         * A received `Sticker.lang`: the primary subtag of a BCP-47 tag (“tr”, “tr-TR”, “EN_gb” …).
         * `null` for an empty, unknown or over-long (> 35 characters) value.
         */
        fun fromWireCode(wireCode: String): StickerLanguage? {
            if (wireCode.codePointCount(0, wireCode.length) > 35) return null
            return when (wireCode.split('-', '_').firstOrNull { it.isNotEmpty() }?.lowercase(Locale.ROOT)) {
                "tr" -> Turkish
                "en" -> English
                else -> null
            }
        }
    }
}

/** A Turkish + English pair (labels, pack titles). */
data class StickerText(val turkish: String, val english: String) {
    operator fun get(language: StickerLanguage): String = if (language == StickerLanguage.Turkish) turkish else english
}

/**
 * The words of a text sticker, as drawn: one string per line. The two languages are written
 * separately (a warm phrase in each, not a translation) and break where the phrase reads best.
 */
data class StickerLines(val turkish: List<String>, val english: List<String>) {
    operator fun get(language: StickerLanguage): List<String> = if (language == StickerLanguage.Turkish) turkish else english
}

/** A pack of the built-in set (`Sticker.pack_id`). */
data class StickerPack(
    /** The stable wire id (“us2us.cups”). Never renamed. */
    val id: String,
    /** Its title's key in the conversation strings (the picker's pack tab). */
    val titleKey: String,
    /** The sticker drawn on the pack's tab. */
    val coverId: Int,
)

/**
 * One sticker of the built-in set. Stickers ship inside the app: the relay never sees which one was
 * sent, only the sealed message carrying `Sticker { pack_id, sticker_id, emoji, lang }`.
 */
data class CatalogSticker(
    /** The stable wire id (`Sticker.sticker_id`). Unique across every pack and never reused, also after a sticker is retired. */
    val id: Int,
    val packId: String,
    /** A readable name for code and docs (“cups.hello”). Not sent. */
    val key: String,
    /** `Sticker.emoji`: what a client that does not know the sticker shows. */
    val emoji: String,
    /** What the screen reader says, in the app's language. */
    val label: StickerText,
    /** The words drawn on a text sticker; `null` for a wordless one. */
    val lines: StickerLines?,
) {
    val isText: Boolean get() = lines != null

    /** The words on one line each (“Seni duyuyorum”). */
    fun text(language: StickerLanguage): String? = lines?.get(language)?.joinToString(" ")

    /**
     * The screen-reader label in the app's language [ui]. A text sticker shown in the other
     * language is read with its words as drawn, then what it means in the app's language:
     * “Merhaba! (Hi!)”.
     */
    fun accessibilityLabel(shownIn: StickerLanguage, ui: StickerLanguage): String {
        val text = text(shownIn)
        return if (text == null || shownIn == ui) label[ui] else "$text (${label[ui]})"
    }
}

/** A sticker as chosen in the picker: the sticker and the language its words were shown in (recents, sending, the long-press preview). */
data class BrandStickerSelection(val sticker: CatalogSticker, val language: StickerLanguage) {
    /** The recents id: “101.tr”, “1.-” (the language only for a sticker with words). */
    val id: String get() = "${sticker.id}.${if (sticker.isText) language.wireCode else "-"}"

    /** `Sticker.lang` to send: only a sticker with words has a language. */
    val wireLanguage: String get() = if (sticker.isText) language.wireCode else ""

    /** The sticker as it is sent: `Sticker { pack_id, sticker_id, emoji, lang }`, `lang` only for a sticker with words. */
    val messageContent: MessageContent.Sticker
        get() = MessageContent.Sticker(packId = sticker.packId, stickerId = sticker.id, emoji = sticker.emoji, lang = wireLanguage)
}

/** What to draw for a received `Sticker` (PROTOCOL.md › Stickers). */
sealed interface StickerResolution {
    /** A sticker of this catalogue, in the language to draw it in. */
    data class Sticker(val selection: BrandStickerSelection) : StickerResolution

    /** The text sticker of builds before the catalogue (`pack_id` “us2us.text”, the words in `emoji`): the Doto card. */
    data class LegacyText(val text: String) : StickerResolution

    /** A pack or id this build does not know: its emoji, drawn large. */
    data class Emoji(val emoji: String) : StickerResolution

    /** Nothing to draw (no known sticker and no emoji). */
    data object Unavailable : StickerResolution

    /**
     * One line for a reply's quote: the words as drawn (a sticker with words), else the emoji and
     * what the sticker shows in the app's language; `null` when there is nothing to say (the caller
     * says “Sticker”).
     */
    fun summary(ui: StickerLanguage): String? = when (this) {
        is Sticker -> "${selection.sticker.emoji} ${selection.sticker.text(selection.language) ?: selection.sticker.label[ui]}"
        is LegacyText -> text
        is Emoji -> emoji
        Unavailable -> null
    }
}

/**
 * The built-in us2us sticker set, version 1: every sticker is drawn in code, so nothing is
 * downloaded and the relay never sees a sticker. Ids are stable wire values: append new stickers
 * with new ids, and move a removed sticker's id to [retiredIds] instead of reusing it.
 */
object StickerCatalog {
    const val VERSION = 1

    /** The pack id of the plain text stickers sent before the catalogue. */
    const val LEGACY_TEXT_PACK_ID = "us2us.text"

    const val CUPS_PACK_ID = "us2us.cups"
    const val WORDS_PACK_ID = "us2us.words"
    const val PRIVACY_PACK_ID = "us2us.privacy"
    const val MOOD_PACK_ID = "us2us.mood"

    val packs: List<StickerPack> = listOf(
        StickerPack(CUPS_PACK_ID, "sticker.pack.cups", coverId = 1),
        StickerPack(WORDS_PACK_ID, "sticker.pack.words", coverId = 101),
        StickerPack(PRIVACY_PACK_ID, "sticker.pack.privacy", coverId = 202),
        StickerPack(MOOD_PACK_ID, "sticker.pack.mood", coverId = 301),
    )

    /** Ids that were used once and must never be handed out again. */
    val retiredIds: Set<Int> = emptySet()

    val all: List<CatalogSticker> = listOf(
        // Cups: the two-cup mascot
        art(1, CUPS_PACK_ID, "cups.hello", "👋", tr = "El sallayan bardak", en = "Cup waving hello"),
        art(2, CUPS_PACK_ID, "cups.together", "🥰", tr = "İpi kalp olmuş iki bardak", en = "Two cups, one heart-shaped string"),
        art(3, CUPS_PACK_ID, "cups.laugh", "😂", tr = "Kahkaha atan bardak", en = "Cup laughing out loud"),
        art(4, CUPS_PACK_ID, "cups.cry", "😭", tr = "Hüngür hüngür ağlayan bardak", en = "Cup crying its eyes out"),
        art(5, CUPS_PACK_ID, "cups.wow", "😮", tr = "Şaşkın bardak", en = "Astonished cup"),
        art(6, CUPS_PACK_ID, "cups.love", "😍", tr = "Kalp gözlü bardak", en = "Cup with heart eyes"),
        art(7, CUPS_PACK_ID, "cups.wink", "😉", tr = "Göz kırpan bardak", en = "Winking cup"),
        art(8, CUPS_PACK_ID, "cups.think", "🤔", tr = "Düşünceli bardak", en = "Cup deep in thought"),
        art(9, CUPS_PACK_ID, "cups.hug", "🤗", tr = "Sarılan iki bardak", en = "Two cups hugging"),
        art(10, CUPS_PACK_ID, "cups.tangled", "😵‍💫", tr = "İpine dolanmış bardak", en = "Cup tangled in its string"),
        art(11, CUPS_PACK_ID, "cups.listen", "👂", tr = "Kulak kesilmiş bardak", en = "Cup all ears"),

        // Words: Doto on brand shapes
        words(101, "words.hello", "👋", tr = listOf("Merhaba!"), en = listOf("Hi!")),
        words(102, "words.lineOpen", "🟢", tr = listOf("Hat açık"), en = listOf("Line's", "open")),
        words(103, "words.hearYou", "🫂", tr = listOf("Seni", "duyuyorum"), en = listOf("I hear", "you")),
        words(104, "words.betweenUs", "🤐", tr = listOf("Aramızda", "kalsın"), en = listOf("Just", "between us")),
        words(105, "words.talkLater", "🕒", tr = listOf("Sonra", "konuşuruz"), en = listOf("Talk", "later")),
        words(106, "words.missYou", "🥺", tr = listOf("Özledim"), en = listOf("Miss you")),
        words(107, "words.goodMorning", "☀️", tr = listOf("Günaydın"), en = listOf("Good", "morning")),
        words(108, "words.goodNight", "🌙", tr = listOf("İyi", "geceler"), en = listOf("Good", "night")),
        words(109, "words.ok", "👌", tr = listOf("Tamam!"), en = listOf("OK!")),
        words(110, "words.thanks", "🙏", tr = listOf("Teşekkürler"), en = listOf("Thank you")),
        words(111, "words.haha", "😆", tr = listOf("Haha"), en = listOf("Haha")),
        words(112, "words.hello.phone", "📞", tr = listOf("Alo?"), en = listOf("Hello?")),

        // Privacy: locks, ghosts, keys, no trace
        art(201, PRIVACY_PACK_ID, "privacy.lock", "🔒", tr = "Gülümseyen asma kilit", en = "Smiling padlock"),
        art(202, PRIVACY_PACK_ID, "privacy.ghost", "👻", tr = "Hayalet bardak", en = "Ghost cup"),
        art(203, PRIVACY_PACK_ID, "privacy.key", "🔑", tr = "Anahtar sende", en = "You hold the key"),
        art(204, PRIVACY_PACK_ID, "privacy.notLooking", "🙈", tr = "Gözlerini kapatan bardak", en = "Cup covering its eyes"),
        CatalogSticker(
            205, PRIVACY_PACK_ID, "privacy.noTrace", "🫥",
            label = StickerText("İz yok", "No trace"),
            lines = StickerLines(listOf("İz yok"), listOf("No", "trace")),
        ),
        CatalogSticker(
            206, PRIVACY_PACK_ID, "privacy.shh", "🤫",
            label = StickerText("Parmağı dudağında bardak: Şşş", "Cup with a finger to its lips: Shh"),
            lines = StickerLines(listOf("Şşş"), listOf("Shh")),
        ),
        art(207, PRIVACY_PACK_ID, "privacy.endToEnd", "🔐", tr = "Uçtan uca şifreli hat", en = "End-to-end encrypted line"),
        art(208, PRIVACY_PACK_ID, "privacy.verified", "✅", tr = "Doğrulama mührü", en = "Verified seal"),

        // Mood: what the cup is up to
        art(301, MOOD_PACK_ID, "mood.coffee", "☕", tr = "Kahve molasındaki bardak", en = "Cup on a coffee break"),
        CatalogSticker(
            302, MOOD_PACK_ID, "mood.onMyWay", "🏃",
            label = StickerText("Koşan bardak: Yoldayım!", "Running cup: On my way!"),
            lines = StickerLines(listOf("Yoldayım!"), listOf("On my way!")),
        ),
        CatalogSticker(
            303, MOOD_PACK_ID, "mood.busy", "⏳",
            label = StickerText("“Meşgul” tabelalı bardak", "Cup with a “Busy” sign"),
            lines = StickerLines(listOf("Meşgul"), listOf("Busy")),
        ),
        art(304, MOOD_PACK_ID, "mood.party", "🎉", tr = "Parti şapkalı bardak", en = "Cup in a party hat"),
        art(305, MOOD_PACK_ID, "mood.sleepy", "😴", tr = "Uykulu bardak", en = "Sleepy cup"),
        art(306, MOOD_PACK_ID, "mood.chill", "😎", tr = "Güneş gözlüklü havalı bardak", en = "Cool cup in sunglasses"),
        art(307, MOOD_PACK_ID, "mood.cheers", "🥂", tr = "Kadeh tokuşturan iki bardak", en = "Two cups clinking, cheers"),
    )

    private val byId: Map<Int, CatalogSticker> = all.associateBy { it.id }

    fun sticker(id: Int): CatalogSticker? = byId[id]

    /** The sticker for a received `pack_id` / `sticker_id` pair. */
    fun sticker(packId: String, stickerId: Int): CatalogSticker? = byId[stickerId]?.takeIf { it.packId == packId }

    fun pack(id: String): StickerPack? = packs.firstOrNull { it.id == id }

    fun stickers(packId: String): List<CatalogSticker> = all.filter { it.packId == packId }

    /** A selection for the picker's `(id, lang)` (`ui.brand.StickerSelection`); `null` for an id this build does not know. */
    fun selection(stickerId: Int, lang: String, fallback: StickerLanguage = StickerLanguage.Turkish): BrandStickerSelection? {
        val sticker = sticker(stickerId) ?: return null
        return BrandStickerSelection(sticker, StickerLanguage.fromWireCode(lang) ?: fallback)
    }

    /**
     * What to draw for a received `Sticker`:
     *
     * * a known sticker in a known `lang` → that sticker in that language;
     * * a known sticker without `lang` (older senders) or in a language this build has no words for
     *   → the reader's own language ([fallback]);
     * * `pack_id` “us2us.text” → the legacy Doto card with `emoji` as its words;
     * * an unknown pack or id → the emoji, drawn large;
     * * nothing known and no emoji → [StickerResolution.Unavailable].
     */
    fun resolve(packId: String, stickerId: Int, emoji: String, lang: String, fallback: StickerLanguage): StickerResolution {
        sticker(packId, stickerId)?.let { sticker ->
            return StickerResolution.Sticker(BrandStickerSelection(sticker, StickerLanguage.fromWireCode(lang) ?: fallback))
        }
        val trimmed = emoji.trim()
        return when {
            trimmed.isEmpty() -> StickerResolution.Unavailable
            packId == LEGACY_TEXT_PACK_ID -> StickerResolution.LegacyText(trimmed)
            else -> StickerResolution.Emoji(trimmed)
        }
    }

    /** What a sticker message draws for a reader whose app language is [ui]; `null` for anything that is not a sticker. */
    fun resolution(content: MessageContent, ui: StickerLanguage): StickerResolution? = when (content) {
        is MessageContent.Sticker -> resolve(content.packId, content.stickerId, content.emoji, content.lang, ui)
        is MessageContent.TextSticker -> StickerResolution.LegacyText(content.text)
        else -> null
    }

    private fun art(id: Int, packId: String, key: String, emoji: String, tr: String, en: String) =
        CatalogSticker(id, packId, key, emoji, StickerText(tr, en), lines = null)

    /** A Words sticker: its label is its phrase. */
    private fun words(id: Int, key: String, emoji: String, tr: List<String>, en: List<String>) =
        CatalogSticker(
            id, WORDS_PACK_ID, key, emoji,
            label = StickerText(tr.joinToString(" "), en.joinToString(" ")),
            lines = StickerLines(tr, en),
        )
}

/**
 * The picker's “Son kullanılanlar”: the built-in stickers last sent, newest first, without
 * repeats, at most [LIMIT]. Kept as ids only ([BrandStickerSelection.id]: “101.tr”, “1.-”): which
 * sticker, in which language, never who it went to or when. The live service keeps the list in the
 * vault's encrypted runtime state (never in preferences); the fixtures keep it in memory.
 */
object StickerRecents {
    const val LIMIT = 12

    /** [selection] first, an earlier copy of it removed, cut to [LIMIT]. */
    fun adding(selection: BrandStickerSelection, ids: List<String>): List<String> =
        (listOf(selection.id) + ids.filter { it != selection.id }).take(LIMIT)

    /**
     * The stickers behind stored ids, in order; ids this build does not know (a retired sticker, a
     * damaged list) and repeats are skipped. A wordless sticker takes [ui], which changes nothing it
     * shows.
     */
    fun selections(ids: List<String>, ui: StickerLanguage): List<BrandStickerSelection> {
        val seen = HashSet<String>()
        val selections = ArrayList<BrandStickerSelection>()
        for (id in ids) {
            val selection = selection(id, ui) ?: continue
            if (!seen.add(selection.id)) continue
            selections.add(selection)
            if (selections.size == LIMIT) break
        }
        return selections
    }

    /** “101.tr” → words.hello in Turkish; “1.-” → cups.hello. */
    fun selection(id: String, ui: StickerLanguage): BrandStickerSelection? {
        val parts = id.split('.')
        if (parts.size != 2) return null
        val number = parts[0].toUIntOrNull()?.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: return null
        val sticker = StickerCatalog.sticker(number) ?: return null
        return BrandStickerSelection(sticker, StickerLanguage.fromWireCode(parts[1]) ?: ui)
    }
}
