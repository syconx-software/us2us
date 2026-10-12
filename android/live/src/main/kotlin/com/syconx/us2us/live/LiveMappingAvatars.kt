package com.syconx.us2us.live

import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.AvatarSearchCopy
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.OwnAvatarState

// Profile pictures in the mapping layer (PROTOCOL.md §8.9, §8.10): plain records built from the
// runtime's `ProfilePicture` / `OwnProfilePicture`, and the pure decisions of what each screen draws.

// MARK: - Records

/** A picture as the runtime holds it: nothing decoded, nothing decided. */
sealed interface LiveAvatar {
    data object Initials : LiveAvatar

    data class Preset(val id: Int) : LiveAvatar

    /** The photo's bytes (JPEG or HEIC, already through the core's header checks). */
    data class Photo(val data: Bytes, val contentType: String) : LiveAvatar

    companion object
}

enum class LiveAvatarSearchStatus { InSync, Pending, Unavailable }

/** The user's own picture and “Aramada göster”. */
data class LiveOwnAvatar(
    val avatar: LiveAvatar,
    val showInSearch: Boolean,
    val status: LiveAvatarSearchStatus,
) {
    companion object
}

// MARK: - Mapping

/** The picture to draw. An unknown preset id is kept (newer builds' presets); the avatar draws it as the initial. */
fun LiveMapping.avatar(avatar: LiveAvatar): AvatarPicture = when (avatar) {
    LiveAvatar.Initials -> AvatarPicture.Initials
    is LiveAvatar.Preset -> if (avatar.id == 0) AvatarPicture.Initials else AvatarPicture.preset(avatar.id)
    is LiveAvatar.Photo -> AvatarPicture.photo(AvatarPhotoData(avatar.data, avatar.contentType))
}

/**
 * What a chat's avatar shows (§8.9 › Which picture is shown): an accepted contact's picture; for
 * the user's own request, the search copy found this session; an incoming request and a blocked
 * contact the initial, whatever the record holds: a stranger's picture is fetched and decoded only
 * when the user asks for it on 4.4.
 */
fun LiveMapping.avatar(chat: LiveChatRecord): AvatarPicture = when (chat.requestState) {
    LiveRequestState.Accepted, LiveRequestState.OutgoingRequest -> avatar(chat.avatar)
    LiveRequestState.IncomingRequest, LiveRequestState.Blocked -> AvatarPicture.Initials
}

/** 4.4 may offer “Profil resmini göster”: an incoming request whose sender's username the directory confirmed (the copy is opened under it). */
fun LiveMapping.offersAvatar(chat: LiveChatRecord): Boolean {
    if (chat.requestState != LiveRequestState.IncomingRequest || chat.username.isNullOrEmpty()) return false
    return chat.usernameVerified
}

/** 7.1: the own picture and setting; the initial with the setting on while locked (nothing decrypted is in memory then). */
fun LiveMapping.ownAvatar(own: LiveOwnAvatar?): OwnAvatarState {
    own ?: return OwnAvatarState()
    val copy = when (own.status) {
        LiveAvatarSearchStatus.InSync -> AvatarSearchCopy.InSync
        LiveAvatarSearchStatus.Pending -> AvatarSearchCopy.Pending
        LiveAvatarSearchStatus.Unavailable -> AvatarSearchCopy.Unavailable
    }
    return OwnAvatarState(picture = avatar(own.avatar), showInSearch = own.showInSearch, searchCopy = copy)
}
