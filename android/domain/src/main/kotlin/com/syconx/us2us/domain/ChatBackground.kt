package com.syconx.us2us.domain

/**
 * What one chat shows behind its messages on this phone. It is never sent: the other person keeps
 * their own, and no backup carries it.
 */
sealed interface ChatBackground {
    /** The app's own background, following the theme. */
    data object Standard : ChatBackground

    data class Preset(val preset: ChatBackgroundPreset) : ChatBackground

    /** A JPEG from the background sheet's photo step. */
    class Photo(val jpeg: ByteArray) : ChatBackground {
        override fun equals(other: Any?): Boolean = other is Photo && jpeg.contentEquals(other.jpeg)
        override fun hashCode(): Int = jpeg.contentHashCode()
    }
}

/**
 * The built-in backgrounds. [id] is what the database keeps and is the same on iOS; a colour may
 * change, an id may not.
 */
enum class ChatBackgroundPreset(val id: String) {
    Mist("mist"),
    Sage("sage"),
    Sand("sand"),
    Rose("rose"),
    Dusk("dusk"),
    Ocean("ocean"),
    Moss("moss"),
    Peach("peach"),
    ;

    companion object {
        fun of(id: String): ChatBackgroundPreset? = entries.firstOrNull { it.id == id }
    }
}
