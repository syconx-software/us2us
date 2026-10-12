package com.syconx.us2us.core.sticker

import java.util.Locale

/**
 * The language a sticker's words are drawn in. A sent sticker carries the language it was shown in
 * (`Sticker.lang`, PROTOCOL.md §8.8) and the recipient draws exactly that language, so both ends
 * see the same sticker.
 */
enum class StickerLanguage(
    /** The BCP-47 primary language subtag sent in `Sticker.lang`. */
    val wireCode: String,
) {
    TURKISH("tr"),
    ENGLISH("en"),
    ;

    /** The other one: the picker's TR | EN switch. */
    val other: StickerLanguage get() = if (this == TURKISH) ENGLISH else TURKISH

    companion object {
        /** Longer `lang` values are ignored (BCP-47 tags fit in 35 characters). */
        const val MAX_WIRE_CODE_LENGTH = 35

        /**
         * A received `Sticker.lang`: the primary subtag of a BCP-47 tag, in any case and with any
         * region ("tr", "tr-TR", "EN_gb"). `null` for an empty, unknown or over-long value; the
         * reader's own language is drawn then.
         */
        fun fromWireCode(lang: String): StickerLanguage? {
            if (lang.codePointCount(0, lang.length) > MAX_WIRE_CODE_LENGTH) return null
            return when (lang.split('-', '_').firstOrNull { it.isNotEmpty() }?.lowercase()) {
                "tr" -> TURKISH
                "en" -> ENGLISH
                else -> null
            }
        }

        /** The app's language: Turkish for Turkish, English otherwise. */
        fun forLocale(locale: Locale): StickerLanguage = if (locale.language == "tr") TURKISH else ENGLISH
    }
}

/** A Turkish and English pair: a sticker's label. */
data class StickerText(val turkish: String, val english: String) {
    operator fun get(language: StickerLanguage): String = if (language == StickerLanguage.TURKISH) turkish else english
}

/**
 * The words of a text sticker as drawn, one string per line. The two languages are written
 * separately (a natural phrase in each, not a translation) and break where the phrase reads best.
 */
data class StickerLines(val turkish: List<String>, val english: List<String>) {
    operator fun get(language: StickerLanguage): List<String> = if (language == StickerLanguage.TURKISH) turkish else english
}

/** A pack of the built-in set (`Sticker.pack_id`). */
data class StickerPack(
    /** The stable wire id ("us2us.cups"). Never renamed. */
    val id: String,
    /**
     * The copy key of the pack's title (the picker's pack tab), the same key as in the iOS app. The
     * title follows the app's language, so it is an app string, not catalog data.
     */
    val titleKey: String,
    /** The sticker drawn on the pack's tab. */
    val coverId: Int,
)

/**
 * One sticker of the built-in set. Stickers ship inside the app and are drawn on the phone: the
 * relay never sees which one was sent, only the sealed message carrying
 * `Sticker{pack_id, sticker_id, emoji, lang}`.
 */
data class BuiltInSticker(
    /** The stable wire id (`Sticker.sticker_id`): unique across every pack, never reused. */
    val id: Int,
    val packId: String,
    /** A readable name for code and docs ("cups.hello"). Not sent. */
    val key: String,
    /** `Sticker.emoji`: what a client that does not know the sticker shows. */
    val emoji: String,
    /** What the screen reader says, in the app's language. */
    val label: StickerText,
    /** The words drawn on a text sticker; `null` for a wordless one. */
    val lines: StickerLines?,
) {
    val isText: Boolean get() = lines != null

    /** The words on one line ("Seni duyuyorum"); `null` for a wordless sticker. */
    fun text(language: StickerLanguage): String? = lines?.get(language)?.joinToString(" ")

    /**
     * The screen-reader label in the app's language [ui]. A text sticker shown in the other
     * language is read with its words as drawn, then what it means: "Merhaba! (Hi!)".
     */
    fun accessibilityLabel(shownIn: StickerLanguage, ui: StickerLanguage): String {
        val text = text(shownIn)
        return if (text == null || shownIn == ui) label[ui] else "$text (${label[ui]})"
    }
}

/** A sticker as chosen in the picker: the sticker and the language its words were shown in. */
data class StickerSelection(val sticker: BuiltInSticker, val language: StickerLanguage) {
    /** Identifies the selection in recents: the language matters only for a sticker with words. */
    val id: String get() = "${sticker.id}.${if (sticker.isText) language.wireCode else "-"}"

    /** `Sticker.lang` to send: only a sticker with words has a language. */
    val wireLanguage: String get() = if (sticker.isText) language.wireCode else ""
}

/** What to draw for a received `Sticker` (PROTOCOL.md §8.8). */
sealed interface StickerResolution {
    /** A sticker of this catalog, in the language to draw it in. */
    data class BuiltIn(val selection: StickerSelection) : StickerResolution

    /** The text sticker of builds before the catalog (`pack_id` "us2us.text", the words in `emoji`). */
    data class LegacyText(val words: String) : StickerResolution

    /** A pack or id this build does not know: its emoji, drawn large. */
    data class Emoji(val emoji: String) : StickerResolution

    /** Nothing to draw: no known sticker and no emoji. A neutral "Sticker" placeholder. */
    data object Unavailable : StickerResolution
}

/**
 * The built-in us2us sticker set, version 1 (PROTOCOL.md §8.8), the same set as the iOS app's.
 * Every sticker is drawn in code, so nothing is downloaded and the relay never sees a sticker. Ids
 * are stable wire values: append new stickers with new ids, and move a removed sticker's id to
 * [retiredIds] instead of reusing it.
 */
object StickerCatalog {
    const val VERSION = 1

    /** The pack id of the plain text stickers sent before the catalog. Received, never sent. */
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

    val all: List<BuiltInSticker> = listOf(
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

        // Words: Doto on brand shapes; the label is the phrase
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
        BuiltInSticker(
            205, PRIVACY_PACK_ID, "privacy.noTrace", "🫥",
            label = StickerText("İz yok", "No trace"),
            lines = StickerLines(listOf("İz yok"), listOf("No", "trace")),
        ),
        BuiltInSticker(
            206, PRIVACY_PACK_ID, "privacy.shh", "🤫",
            label = StickerText("Parmağı dudağında bardak: Şşş", "Cup with a finger to its lips: Shh"),
            lines = StickerLines(listOf("Şşş"), listOf("Shh")),
        ),
        art(207, PRIVACY_PACK_ID, "privacy.endToEnd", "🔐", tr = "Uçtan uca şifreli hat", en = "End-to-end encrypted line"),
        art(208, PRIVACY_PACK_ID, "privacy.verified", "✅", tr = "Doğrulama mührü", en = "Verified seal"),

        // Mood: what the cup is up to
        art(301, MOOD_PACK_ID, "mood.coffee", "☕", tr = "Kahve molasındaki bardak", en = "Cup on a coffee break"),
        BuiltInSticker(
            302, MOOD_PACK_ID, "mood.onMyWay", "🏃",
            label = StickerText("Koşan bardak: Yoldayım!", "Running cup: On my way!"),
            lines = StickerLines(listOf("Yoldayım!"), listOf("On my way!")),
        ),
        BuiltInSticker(
            303, MOOD_PACK_ID, "mood.busy", "⏳",
            label = StickerText("“Meşgul” tabelalı bardak", "Cup with a “Busy” sign"),
            lines = StickerLines(listOf("Meşgul"), listOf("Busy")),
        ),
        art(304, MOOD_PACK_ID, "mood.party", "🎉", tr = "Parti şapkalı bardak", en = "Cup in a party hat"),
        art(305, MOOD_PACK_ID, "mood.sleepy", "😴", tr = "Uykulu bardak", en = "Sleepy cup"),
        art(306, MOOD_PACK_ID, "mood.chill", "😎", tr = "Güneş gözlüklü havalı bardak", en = "Cool cup in sunglasses"),
        art(307, MOOD_PACK_ID, "mood.cheers", "🥂", tr = "Kadeh tokuşturan iki bardak", en = "Two cups clinking, cheers"),
    )

    private val byId: Map<Int, BuiltInSticker> = all.associateBy { it.id }

    fun sticker(id: Int): BuiltInSticker? = byId[id]

    /** The sticker for a received `pack_id` / `sticker_id` pair. */
    fun sticker(packId: String, stickerId: Int): BuiltInSticker? = byId[stickerId]?.takeIf { it.packId == packId }

    fun pack(id: String): StickerPack? = packs.firstOrNull { it.id == id }

    fun stickers(packId: String): List<BuiltInSticker> = all.filter { it.packId == packId }

    /**
     * What to draw for a received `Sticker` (PROTOCOL.md §8.8):
     *
     * * a known sticker in a known `lang` → that sticker in that language;
     * * a known sticker without `lang` (older senders) or in a language without words here → the
     *   reader's own language [fallback];
     * * `pack_id` "us2us.text" → the legacy text card with `emoji` as its words;
     * * an unknown pack or id → the emoji, drawn large;
     * * nothing known and no emoji → [StickerResolution.Unavailable].
     */
    fun resolve(packId: String, stickerId: Int, emoji: String, lang: String, fallback: StickerLanguage): StickerResolution {
        sticker(packId, stickerId)?.let { sticker ->
            return StickerResolution.BuiltIn(StickerSelection(sticker, StickerLanguage.fromWireCode(lang) ?: fallback))
        }
        val trimmed = emoji.trim()
        return when {
            trimmed.isEmpty() -> StickerResolution.Unavailable
            packId == LEGACY_TEXT_PACK_ID -> StickerResolution.LegacyText(trimmed)
            else -> StickerResolution.Emoji(trimmed)
        }
    }

    private fun art(id: Int, packId: String, key: String, emoji: String, tr: String, en: String) =
        BuiltInSticker(id, packId, key, emoji, StickerText(tr, en), lines = null)

    /** A Words sticker: its label is its phrase. */
    private fun words(id: Int, key: String, emoji: String, tr: List<String>, en: List<String>) =
        BuiltInSticker(
            id, WORDS_PACK_ID, key, emoji,
            label = StickerText(tr.joinToString(" "), en.joinToString(" ")),
            lines = StickerLines(tr, en),
        )
}
