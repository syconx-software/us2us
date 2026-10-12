package com.syconx.us2us.feature.chats

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.feature.chats.invite.InviteLink
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Invite links the app was opened with (`https://us2us.app/u/<username>`, App Links only),
 * waiting until the main stack can show them: the app locks on every launch and return from the
 * background, so a link that arrives is opened only after the unlock (or after onboarding).
 *
 * Memory only (a link is never written anywhere) and only the latest one is kept, for at most ten
 * minutes, so a link tapped long ago never pops up a sheet by surprise. Opening one starts nothing
 * on its own: 4.2 shows the name searched (the same exact lookup as typing it) and nothing is sent
 * until the user taps “Mesaj yaz” (a message request with PoW).
 */
@Stable
class InviteInbox(private val now: () -> Instant = Instant::now) {
    @Immutable
    data class Pending(val username: String, val receivedAt: Instant, val id: String = UUID.randomUUID().toString())

    /** The latest invite link's username, until it is taken. */
    var pending: Pending? by mutableStateOf(null)
        private set

    /** Keeps [url] when it is an invite link; anything else is ignored. */
    fun receive(url: String, at: Instant = now()): Boolean {
        val username = InviteLink.username(url) ?: return false
        pending = Pending(username, at)
        return true
    }

    /** The pending username (once), unless it has expired. */
    fun take(at: Instant = now()): String? {
        val current = pending ?: return null
        pending = null
        val age = at.toEpochMilli() - current.receivedAt.toEpochMilli()
        return if (age < LIFETIME.inWholeMilliseconds) current.username else null
    }

    companion object {
        val LIFETIME = 10.minutes
    }
}

/** Invite links (`https://us2us.app/u/<username>`) handed to the app by the system or `U2OpenURL`. */
object InviteLinkInbox {
    /** The app's inbox: 4.1 opens what it holds once the app is unlocked. */
    val shared = InviteInbox()

    /** Takes [url] if it is an invite link; `false` leaves it to the caller. */
    fun handle(url: String): Boolean = shared.receive(url)
}
