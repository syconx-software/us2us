package com.syconx.us2us.domain

import com.syconx.us2us.domain.sticker.StickerText
import java.security.MessageDigest

// Profile pictures (PROTOCOL.md §8.9, §8.10), UI-shaped: what C12's circle draws instead of the
// initial, the user's own choice with “Aramada göster”, and a change made on 7.1 › Profil görseli.

/**
 * A profile photo's bytes exactly as they arrived (or were made on this phone): JPEG or HEIC, at
 * most 256 × 256 pixels, held in memory only, never written to a file or an image cache on disk.
 *
 * Two values are equal when their bytes are: the comparison uses a SHA-256 digest taken once, so a
 * chat list that redraws compares 32 bytes, not the photo.
 */
class AvatarPhotoData(
    val data: Bytes,
    /** `image/jpeg` or `image/heic` (§8.9). */
    val contentType: String,
) {
    /** SHA-256 of [data]: identity for equality and for the decoded-image cache. */
    val digest: Bytes = Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()))

    /** The digest as a short string (the decoded-image cache's key). */
    val cacheKey: String get() = digest.toHex()

    override fun equals(other: Any?): Boolean =
        other is AvatarPhotoData && other.digest == digest && other.contentType == contentType

    override fun hashCode(): Int = digest.hashCode()

    override fun toString(): String = "AvatarPhotoData($contentType, ${data.size} bytes)"
}

/**
 * What the user's avatar shows: the initial (C12, the default), a built-in preset by its stable
 * id, or a photo. Stored and sent as one short string ([storageValue]: “initials”, “preset:7”,
 * “photo”), so a choice made on a newer build survives on an older one: a preset id this build does
 * not know is kept as it is and drawn as the initial ([drawn]).
 */
sealed interface AvatarChoice {
    data object Initials : AvatarChoice

    data class Preset(val id: Int) : AvatarChoice

    data object Photo : AvatarChoice

    /** The stored and sent form. */
    val storageValue: String
        get() = when (this) {
            Initials -> "initials"
            is Preset -> PRESET_PREFIX + id
            Photo -> "photo"
        }

    /** What this build draws: an unknown preset falls back to the initial. A photo stays a photo; the caller draws the initial while it has no image for it. */
    val drawn: AvatarChoice
        get() = if (this is Preset && !AvatarPresetCatalog.isKnown(id)) Initials else this

    companion object {
        /** A getter: a stored value would read `null` while the sealed interface's objects initialise. */
        val Default: AvatarChoice get() = Initials

        private const val PRESET_PREFIX = "preset:"

        /** A stored or received value; anything empty, unknown or malformed is the initial (never a crash, never a guess). */
        fun fromStorageValue(storageValue: String): AvatarChoice {
            return when (val value = storageValue.trim()) {
                "initials" -> Initials
                "photo" -> Photo
                else -> {
                    if (!value.startsWith(PRESET_PREFIX)) return Initials
                    val id = value.removePrefix(PRESET_PREFIX).toUIntOrNull()
                    if (id == null || id == 0u || id > Int.MAX_VALUE.toUInt()) Initials else Preset(id.toInt())
                }
            }
        }
    }
}

/**
 * What a person's avatar shows (§8.9): the initial (C12, the default), a built-in preset or a
 * photo. A preset id this build does not know, and a photo without its bytes or that does not
 * decode, are drawn as the initial, never as an error.
 */
class AvatarPicture(val choice: AvatarChoice, photo: AvatarPhotoData? = null) {
    /** The bytes of a [AvatarChoice.Photo]; `null` for the other choices. */
    val photo: AvatarPhotoData? = if (choice == AvatarChoice.Photo) photo else null

    /** The initial letter (or something this build draws as it). */
    val isInitials: Boolean get() = choice.drawn == AvatarChoice.Initials || (choice == AvatarChoice.Photo && photo == null)

    val isPhoto: Boolean get() = choice == AvatarChoice.Photo && photo != null

    /** The preset to draw, or `null` (the initial, a photo, or an id this build does not know). */
    val presetId: Int? get() = (choice.drawn as? AvatarChoice.Preset)?.id

    override fun equals(other: Any?): Boolean = other is AvatarPicture && other.choice == choice && other.photo == photo

    override fun hashCode(): Int = 31 * choice.hashCode() + (photo?.hashCode() ?: 0)

    override fun toString(): String = "AvatarPicture(${choice.storageValue})"

    companion object {
        val Initials = AvatarPicture(AvatarChoice.Initials)

        fun preset(id: Int): AvatarPicture = AvatarPicture(AvatarChoice.Preset(id))

        fun photo(photo: AvatarPhotoData): AvatarPicture = AvatarPicture(AvatarChoice.Photo, photo)
    }
}

/** Where the copy for search (§8.10) stands, as 7.1 says it. */
enum class AvatarSearchCopy {
    /** The relay holds what the picture and “Aramada göster” ask for: the encrypted copy, or none. */
    InSync,

    /** A change has not reached the relay yet; it is tried again at the next connection. */
    Pending,

    /** This relay keeps no pictures for search yet; contacts still get the picture end to end. */
    Unavailable,
}

/** The user's own picture and “Aramada göster” (7.1). */
data class OwnAvatarState(
    val picture: AvatarPicture = AvatarPicture.Initials,
    /** “Aramada göster” / “Show in search”: on by default (§8.10). Kept while the picture is the initial, for the next choice. */
    val showInSearch: Boolean = true,
    val searchCopy: AvatarSearchCopy = AvatarSearchCopy.InSync,
) {
    /** Someone who types the exact username sees the picture: a preset or a photo, with the setting on. */
    val isShownInSearch: Boolean get() = showInSearch && !picture.isInitials
}

/** The square the user framed in a photo, in pixels of the photo as displayed (x, y from the top left). */
data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A new own picture, chosen on 7.1 › Profil görseli. */
sealed interface AvatarEdit {
    data object Initials : AvatarEdit

    /** A preset of [AvatarPresetCatalog] (only ids it lists are ever sent, §8.9). */
    data class Preset(val id: Int) : AvatarEdit

    /**
     * A photo as picked or taken (any format the platform decoder reads; in memory only) and the
     * square the user framed in it. It is scaled to at most 256 × 256, stripped of all metadata and
     * re-encoded on the phone before it goes anywhere; the original is not kept.
     */
    data class Photo(val data: Bytes, val crop: CropRect) : AvatarEdit
}

/** Why a new picture was not set; the previous one stays. */
enum class AvatarEditError {
    /** The photo could not be read, or not made small enough. */
    UnusablePhoto,

    /** The app locked meanwhile, the preset is not one this build may send, or saving failed. */
    Failed,
}

/**
 * The built-in preset pictures (catalogue version 1, §8.9): ids 1–22, the cup mascot presets, in
 * the picker's order. They are drawn on the phone from their id (`ui.brand`); only these ids may be
 * sent, and an id this list does not know is drawn as the initial.
 */
object AvatarPresetCatalog {
    data class Preset(
        /** The stable id (`preset_id`). Never reused. */
        val id: Int,
        /** A readable name for code and docs (“cup.headphones”). Not stored. */
        val key: String,
        /** What the screen reader says in the picker, in the app's language. */
        val label: StickerText,
    )

    /** Ids that were used once and must never be handed out again. */
    val retiredIds: Set<Int> = emptySet()

    /** In the picker's order. */
    val all: List<Preset> = listOf(
        Preset(1, "cup.classic", StickerText("Klasik bardak", "The classic cup")),
        Preset(2, "cup.headphones", StickerText("Kulaklıklı bardak", "Cup with headphones")),
        Preset(3, "cup.beanie", StickerText("Bereli bardak", "Cup in a beanie")),
        Preset(4, "cup.glasses", StickerText("Gözlüklü bardak", "Cup with round glasses")),
        Preset(5, "cup.flower", StickerText("Çiçekli bardak", "Cup with a flower")),
        Preset(6, "cup.cap", StickerText("Şapkalı bardak", "Cup in a cap")),
        Preset(7, "cup.scarf", StickerText("Atkılı bardak", "Cup in a scarf")),
        Preset(8, "cup.ghost", StickerText("Hayalet bardak", "Ghost cup")),
        Preset(9, "cup.cool", StickerText("Güneş gözlüklü bardak", "Cup in sunglasses")),
        Preset(10, "cup.party", StickerText("Parti şapkalı bardak", "Cup in a party hat")),
        Preset(11, "cup.stringHeart", StickerText("İpi kalp olmuş bardak", "Cup with a string heart")),
        Preset(12, "cup.sleepy", StickerText("Uykucu bardak", "Sleepy cup in a nightcap")),
        Preset(13, "cup.wink", StickerText("Göz kırpan bardak", "Winking cup")),
        Preset(14, "cup.crown", StickerText("Taçlı bardak", "Cup with a crown")),
        Preset(15, "cup.bow", StickerText("Fiyonklu bardak", "Cup with a bow")),
        Preset(16, "cup.coffee", StickerText("Kahve bardağı", "Coffee cup")),
        Preset(17, "cup.curious", StickerText("Meraklı bardak", "Curious cup")),
        Preset(18, "cup.twin", StickerText("Açık renk ikiz bardak", "The light twin cup")),
        Preset(19, "cup.catEars", StickerText("Kedi kulaklı bardak", "Cup with cat ears")),
        Preset(20, "cup.chef", StickerText("Aşçı bardak", "Chef cup")),
        Preset(21, "cup.antennae", StickerText("Uzaylı bardak", "Alien cup with antennae")),
        Preset(22, "cup.laugh", StickerText("Kahkaha atan bardak", "Laughing cup")),
    )

    private val byId: Map<Int, Preset> = all.associateBy { it.id }

    /** The preset for a stored id; `null` for one this build does not know (a newer client's). */
    fun preset(id: Int): Preset? = byId[id]

    fun isKnown(id: Int): Boolean = id in byId
}
