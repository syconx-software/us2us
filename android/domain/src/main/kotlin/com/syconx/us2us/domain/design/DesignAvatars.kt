package com.syconx.us2us.domain.design

import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Bytes

/**
 * Profile pictures of the demo data (proposed: the design draws only initials): four chats show a
 * picture (three presets, one photo), a few usernames have one in search, and two request senders
 * have one behind “Profil resmini göster”. The rows the design draws in its references (deniz,
 * mert_k, ayla_) and the own account keep their initials.
 */
object DesignAvatars {
    /**
     * The demo photo: a small sunset over the sea, 256 × 256, a JPEG with nothing but the pixels
     * (the iOS fixtures paint the same picture and run it through the profile-photo pipeline).
     * In memory only.
     */
    val photoAvatar: AvatarPicture by lazy {
        val stream = DesignAvatars::class.java.getResourceAsStream("/com/syconx/us2us/domain/design/photo-avatar.jpg") // absolute: R8 renames the package
        val bytes = stream?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) AvatarPicture.Initials else AvatarPicture.photo(AvatarPhotoData(Bytes.wrap(bytes), "image/jpeg"))
    }

    /** The pictures of the 4.1 rows below the design's first three. */
    fun chatAvatar(username: String): AvatarPicture = when (username) {
        "kaan" -> AvatarPicture.preset(2)
        "ece_y" -> AvatarPicture.preset(5)
        "bora" -> AvatarPicture.preset(16)
        "oya_t" -> photoAvatar
        else -> AvatarPicture.Initials
    }

    /** What 4.2 finds in search: the contacts' own pictures, and a preset for “lale” (no chat yet). “deniz” as 4.2 draws her: the initial. */
    fun searchAvatar(username: String): AvatarPicture = when (username) {
        "lale" -> AvatarPicture.preset(14)
        "kuzey_dev" -> requestAvatar(username)
        else -> chatAvatar(username)
    }

    /** What “Profil resmini göster” finds for a request's sender (4.4): kuzey_dev has a preset, n_aksoy none (the initial stays and 4.4 says so). */
    fun requestAvatar(sender: String): AvatarPicture = when (sender) {
        "kuzey_dev" -> AvatarPicture.preset(21)
        else -> AvatarPicture.Initials
    }
}
