package com.syconx.us2us.core.profile

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.ProfileAvatar
import com.syconx.us2us.core.proto.avatarPhoto
import com.syconx.us2us.core.proto.profileAvatar

/**
 * The built-in preset pictures (PROTOCOL.md §8.9). They ship inside the apps and are drawn on the
 * phone from their id, like stickers; nothing is downloaded.
 *
 * Catalog version 1 (§8.9): ids 1–22, the cup mascot presets, with their keys and labels in the
 * table there. Ids are unique, never reused, only appended; 0 is not an id. Only these ids may be
 * sent; a received id this list does not know is shown as the initial letter.
 */
object AvatarPresets {
    /** Every `preset_id` of the catalog (version 1). */
    val ids: Set<Int> = (1..22).toSet()

    fun isKnown(id: Int): Boolean = id in ids
}

/** The two photo formats of §8.9. */
enum class PhotoFormat(val contentType: String) {
    JPEG("image/jpeg"),
    HEIC("image/heic"),
    ;

    companion object {
        /** Exact match: `content_type` is never parsed or case-folded. */
        fun fromContentType(contentType: String): PhotoFormat? = entries.firstOrNull { it.contentType == contentType }
    }
}

/**
 * A profile picture other than the initial letter (PROTOCOL.md §8.9): a built-in [Preset] or a
 * [Photo]. `null` wherever a picture is expected means the initial letter of the username.
 *
 * Build one to send with [preset] or [photo], which apply the sender's rules; [fromProto] applies a
 * receiver's and turns anything malformed into `null`. A picture lives only in the encrypted local
 * store (§5.3): persist it as [toProto]`().toByteArray()` and read it back with [parse].
 */
sealed class ProfilePicture {
    abstract fun toProto(): ProfileAvatar

    /** A photo, as opposed to a preset (a stranger's is fetched only on request, §8.9). */
    val isPhoto: Boolean get() = this is Photo

    /** Whether this client may send this picture: a preset of [AvatarPresets], or a photo that follows §8.9. */
    abstract val isSendable: Boolean

    /**
     * A built-in preset. A received [id] this build does not know is kept (a later build may draw
     * it) but shown as the initial letter: check [isKnown].
     */
    class Preset internal constructor(
        /** `preset_id`, a uint32 on the wire; never 0. */
        val id: Int,
    ) : ProfilePicture() {
        val isKnown: Boolean get() = AvatarPresets.isKnown(id)
        override val isSendable: Boolean get() = isKnown

        override fun toProto(): ProfileAvatar = profileAvatar { presetId = id }
        override fun equals(other: Any?): Boolean = other is Preset && other.id == id
        override fun hashCode(): Int = id
        override fun toString(): String = "Preset(${id.toUInt()})"
    }

    /**
     * A photo of at most 256 × 256 pixels, as sent: JPEG or HEIC bytes ([data] returns a copy).
     * [width] and [height] come from the file header; decoding is the app's job (ImageIO,
     * `ImageDecoder` on API 28+), which shows it cropped to the circle, centred.
     */
    class Photo internal constructor(
        val format: PhotoFormat,
        private val bytes: ByteArray,
        val width: Int,
        val height: Int,
        internal val clean: Boolean,
    ) : ProfilePicture() {
        val data: ByteArray get() = bytes.copyOf()
        val contentType: String get() = format.contentType
        override val isSendable: Boolean get() = clean && width == height && toProto().serializedSize <= MAX_SERIALIZED_BYTES

        override fun toProto(): ProfileAvatar = profileAvatar {
            photo = avatarPhoto {
                contentType = format.contentType
                data = bytes.toByteString()
            }
        }

        override fun equals(other: Any?): Boolean = other is Photo && other.format == format && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = 31 * format.hashCode() + bytes.contentHashCode()
        override fun toString(): String = "Photo($format, ${width}x$height, ${bytes.size} bytes)"
    }

    companion object {
        /** Largest width and height of a photo, in pixels. */
        const val MAX_DIMENSION = 256

        /** Largest serialized `ProfileAvatar`, so that the avatar record stays within 48 KiB (§8.10). */
        const val MAX_SERIALIZED_BYTES = AvatarRecord.MAX_PROFILE_AVATAR_BYTES

        /** A preset to send. @throws IllegalArgumentException unless [id] is in [AvatarPresets]. */
        fun preset(id: Int): Preset {
            require(AvatarPresets.isKnown(id)) { "preset $id is not in the catalog (§8.9)" }
            return Preset(id)
        }

        /**
         * A photo to send, prepared as §8.9 says: square, 1..256 pixels a side, one still JPEG or
         * HEIC image encoded from the pixels alone, so without EXIF, GPS, XMP, IPTC, comments,
         * thumbnails or auxiliary images (an ICC profile may stay), and small enough that the
         * serialized `ProfileAvatar` is at most [MAX_SERIALIZED_BYTES]. The app encodes it (quality
         * about 0.8, lowered to about 0.4, then a smaller size) until this accepts it.
         *
         * @throws InvalidProfilePhotoException naming the first rule [data] breaks.
         */
        fun photo(contentType: String, data: ByteArray): Photo {
            val format = PhotoFormat.fromContentType(contentType) ?: throw InvalidProfilePhotoException(PhotoProblem.UNSUPPORTED_TYPE)
            val photo = readPhoto(format, data.copyOf()) ?: throw InvalidProfilePhotoException(PhotoProblem.NOT_AN_IMAGE)
            when {
                photo.width > MAX_DIMENSION || photo.height > MAX_DIMENSION -> throw InvalidProfilePhotoException(PhotoProblem.TOO_LARGE_DIMENSIONS)
                photo.width != photo.height -> throw InvalidProfilePhotoException(PhotoProblem.NOT_SQUARE)
                !photo.clean -> throw InvalidProfilePhotoException(PhotoProblem.METADATA)
                photo.toProto().serializedSize > MAX_SERIALIZED_BYTES -> throw InvalidProfilePhotoException(PhotoProblem.TOO_MANY_BYTES)
            }
            return photo
        }

        /**
         * A received `ProfileAvatar` (from a `ProfileUpdate`, an avatar record or a backup), checked
         * as §8.9 says, or `null` for the initial letter: no kind, `preset_id` 0, another
         * `content_type`, a file whose signature does not match it, no readable size in its header
         * or more than 256 pixels a side, or a serialization over [MAX_SERIALIZED_BYTES]. A
         * malformed picture never makes the message or record around it invalid.
         */
        fun fromProto(avatar: ProfileAvatar): ProfilePicture? {
            if (avatar.serializedSize > MAX_SERIALIZED_BYTES) return null
            return when (avatar.kindCase) {
                ProfileAvatar.KindCase.PRESET_ID -> avatar.presetId.takeIf { it != 0 }?.let(::Preset)
                ProfileAvatar.KindCase.PHOTO -> {
                    val format = PhotoFormat.fromContentType(avatar.photo.contentType) ?: return null
                    readPhoto(format, avatar.photo.data.toByteArray())
                        ?.takeIf { it.width <= MAX_DIMENSION && it.height <= MAX_DIMENSION }
                }
                else -> null
            }
        }

        /** [fromProto] of serialized bytes; `null` if they do not parse. */
        fun parse(bytes: ByteArray): ProfilePicture? = try {
            fromProto(ProfileAvatar.parseFrom(bytes))
        } catch (_: InvalidProtocolBufferException) {
            null
        }

        private fun readPhoto(format: PhotoFormat, data: ByteArray): Photo? {
            val header = when (format) {
                PhotoFormat.JPEG -> ImageHeaders.jpeg(data)
                PhotoFormat.HEIC -> ImageHeaders.heic(data)
            } ?: return null
            if (header.width < 1 || header.height < 1) return null
            return Photo(format, data, header.width, header.height, clean = !header.hasMetadata)
        }
    }
}

/** Why [ProfilePicture.photo] refused a photo. */
enum class PhotoProblem {
    /** `content_type` is neither `image/jpeg` nor `image/heic`. */
    UNSUPPORTED_TYPE,

    /** The bytes are not a file of that type, or its header has no readable size. */
    NOT_AN_IMAGE,

    /** More than 256 pixels on a side. */
    TOO_LARGE_DIMENSIONS,

    /** Not cropped to a square. */
    NOT_SQUARE,

    /** EXIF, GPS, XMP, IPTC, a comment, a thumbnail or another image besides the pixels. */
    METADATA,

    /** The serialized `ProfileAvatar` exceeds 49110 bytes: encode at a lower quality or size. */
    TOO_MANY_BYTES,
}

class InvalidProfilePhotoException(val problem: PhotoProblem) : IllegalArgumentException("profile photo refused: $problem")
