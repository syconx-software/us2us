package com.syconx.us2us.feature.chats

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.MessageRequest
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.UserLookup
import com.syconx.us2us.domain.UsernameRules
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// View models of 04 · Sohbetler. They hold only presentation state and talk to the messenger
// exclusively through `MessengerService`, so the live implementation drops in without touching the
// screens.

// MARK: - 4.2 · Yeni sohbet

/**
 * The exact-username lookup of 4.2: no suggestions, no prefix search. While typing nothing is
 * sent; once typing pauses for 0.4 s, the typed name is normalized ([UsernameRules]: white space,
 * one leading “@”, case) and, when valid, one exact lookup runs. A name no account can have (a
 * Turkish letter, a dot, too long …) is answered with the rule it breaks, without asking anyone,
 * and the field keeps it as typed. Results fade in; editing the name fades the previous result
 * out at once, so a stale match is never shown.
 *
 * A found result's profile picture (PROTOCOL §8.10) is asked for only after that exact hit: the
 * result shows at once with the initial and a travelling ring, and the picture takes its place
 * when it arrives (the initial stays on any failure). An accepted contact's is already here.
 * Editing the name drops it like the result; typing never waits for it.
 *
 * Its work runs on [scope] (the sheet's composition scope).
 */
@Stable
class NewChatModel(
    private val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val debounce: Duration = 400.milliseconds,
    /**
     * How long a found result stays unchanged before its picture is fetched: a pause in typing is
     * not a choice (PROTOCOL §8.10, never while the user is still typing). Return fetches at once.
     */
    private val avatarSettle: Duration = 1.seconds,
) {
    sealed interface Phase {
        /** Empty or still too short: the hint only. */
        data object Idle : Phase

        /** Waiting for typing to pause (a broken rule is said only then). */
        data object Pending : Phase

        /** The exact lookup is running (spinner in the field). */
        data object Searching : Phase

        /** [existingChat]: the chat of the account the lookup resolved to (by account, never by name). */
        data class Found(val peer: Peer, val existingChat: ChatId? = null) : Phase {
            val isExistingChat: Boolean get() = existingChat != null
        }

        /** Nobody has the name. */
        data object NotFound : Phase

        /** Nobody can have the name: it breaks a rule (the first one is shown), so nothing is asked. */
        data class Invalid(val violation: UsernameRules.Violation) : Phase

        /** The user typed their own username. */
        data object OwnName : Phase
    }

    /** The result's picture. */
    sealed interface ResultAvatar {
        /** No result to show one for. */
        data object NoResult : ResultAvatar

        /** Asked for (the initial and the ring meanwhile). */
        data object Loading : ResultAvatar

        /** Here: the initial when there is none or it failed. */
        data class Loaded(override val picture: AvatarPicture) : ResultAvatar

        /** What the result's avatar draws now. */
        val picture: AvatarPicture get() = AvatarPicture.Initials
    }

    /** Screenshot harness controls. */
    @Immutable
    data class DebugOptions(
        /** Keep the lookup running forever (the “Aranıyor…” state). */
        val freezesSearching: Boolean = false,
        /** Draw the focused field without raising the keyboard. */
        val forcesFocusChrome: Boolean = false,
        /** Open on the “Kodumu göster” page. */
        val startsOnMyCode: Boolean = false,
    )

    var debug: DebugOptions = DebugOptions()

    private var typed by mutableStateOf("")

    /** What is typed in the field (never rewritten). */
    var query: String
        get() = typed
        set(value) {
            if (value == typed) return
            typed = value
            scheduleLookup()
        }

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    /** The username the current result is about. */
    var resultName: String by mutableStateOf("")
        private set

    var resultAvatar: ResultAvatar by mutableStateOf(ResultAvatar.NoResult)
        private set

    var isStarting: Boolean by mutableStateOf(false)
        private set

    private var lookupJob: Job? = null

    val ownUsername: String get() = messenger.account.value?.username.orEmpty()

    /** Return key: look up right away (no wait for the pause). */
    fun submit() {
        scheduleLookup(immediately = true)
    }

    private fun scheduleLookup(immediately: Boolean = false) {
        lookupJob?.cancel()
        val name = UsernameRules.normalize(query)
        val violations = UsernameRules.violations(name)
        if (name.isEmpty() || (violations == listOf(UsernameRules.Violation.Length) && UsernameRules.isTooShort(name))) {
            set(Phase.Idle, name)
            return
        }
        if (name == ownUsername) {
            set(Phase.OwnName, name)
            return
        }
        set(Phase.Pending, name)
        val wait = if (immediately) Duration.ZERO else debounce
        val settle = if (immediately) Duration.ZERO else avatarSettle
        lookupJob = scope.launch {
            if (wait > Duration.ZERO) delay(wait)
            // Breaks a rule: no account can have it, so nothing is asked.
            violations.firstOrNull()?.let {
                set(Phase.Invalid(it), name)
                return@launch
            }
            set(Phase.Searching, name)
            if (debug.freezesSearching) return@launch
            val result = try {
                messenger.lookup(name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UserLookup.Idle
            }
            if (!isActive || resultName != name) return@launch
            when (result) {
                is UserLookup.Found -> {
                    set(Phase.Found(result.peer, result.existingChat), name)
                    if (settle > Duration.ZERO && resultAvatar == ResultAvatar.Loading) {
                        delay(settle)
                        if (resultName != name) return@launch
                    }
                    loadResultAvatar(name)
                }
                UserLookup.NotFound -> set(Phase.NotFound, name)
                UserLookup.Idle, UserLookup.Searching -> set(Phase.Idle, name)
            }
        }
    }

    private fun set(phase: Phase, name: String) {
        if (phase == this.phase && name == resultName) return
        resultName = name
        this.phase = phase
        resultAvatar = resultAvatar(phase, messenger.ownAvatar.value.picture)
    }

    /** The found result's search copy, unless the name changed meanwhile. */
    private suspend fun loadResultAvatar(name: String) {
        if (resultAvatar != ResultAvatar.Loading) return
        val picture = try {
            messenger.searchAvatar(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AvatarPicture.Initials
        }
        if (resultName != name || phase !is Phase.Found || resultAvatar != ResultAvatar.Loading) return
        resultAvatar = ResultAvatar.Loaded(picture)
    }

    /**
     * “Mesaj yaz” / “Sohbete git”: the chat to open after the sheet is down, the existing one or a
     * new pending-request conversation.
     *
     * The existing chat is the one of the account the lookup resolved to ([Phase.Found.existingChat]),
     * never a chat that merely shows the same name: after an account was deleted and someone else
     * registered its username, the old chat still shows it (PROTOCOL §3.3). Without one (or once it
     * is gone) the messenger looks the name up again and opens the chat of the account it finds.
     */
    suspend fun startChat(
        peer: Peer,
        existingChat: ChatId? = (phase as? Phase.Found)?.takeIf { it.peer == peer }?.existingChat,
    ): ChatId? {
        if (isStarting) return null
        isStarting = true
        try {
            existingChat?.let { chat -> if (messenger.chat(chat).value != null) return chat }
            return messenger.startChat(peer.username)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            isStarting = false
        }
    }

    fun cancel() {
        lookupJob?.cancel()
    }

    companion object {
        /** What a result's avatar starts as: an accepted contact's picture and the own one are here; anyone else's is fetched. */
        fun resultAvatar(phase: Phase, own: AvatarPicture): ResultAvatar = when (phase) {
            is Phase.Found -> if (phase.peer.isAcceptedContact) ResultAvatar.Loaded(phase.peer.avatar) else ResultAvatar.Loading
            Phase.OwnName -> ResultAvatar.Loaded(own)
            Phase.Idle, Phase.Pending, Phase.Searching, Phase.NotFound, is Phase.Invalid -> ResultAvatar.NoResult
        }
    }
}

// MARK: - 4.3 / 4.4 · Request triage

/**
 * The three decisions on a stranger's request. Removals are optimistic: the card collapses at once
 * (0.36 s) while the messenger catches up, so a slow relay never makes the list jump. Nothing here
 * tells the sender anything: “Sil” is silent, “Engelle” is not announced, no read receipt is sent
 * before acceptance (that is the messenger's contract).
 */
@Stable
class RequestTriageModel {
    /** Requests already decided on this screen (hidden before the messenger confirms). */
    var decided: Set<RequestId> by mutableStateOf(emptySet())
        private set

    fun requests(all: List<MessageRequest>): List<MessageRequest> = all.filter { it.id !in decided }

    suspend fun block(request: MessageRequest, messenger: MessengerService) {
        decide(request.id) { messenger.block(request) }
    }

    suspend fun delete(request: MessageRequest, messenger: MessengerService) {
        decide(request.id) { messenger.delete(request) }
    }

    suspend fun deleteAll(all: List<MessageRequest>, messenger: MessengerService) {
        val shown = requests(all)
        decided = decided + shown.map { it.id }
        for (request in shown) decide(request.id) { messenger.delete(request) }
    }

    /** Accepts: the chat appears in 4.1 and the caller opens 5.1. */
    suspend fun accept(request: MessageRequest, messenger: MessengerService): ChatId? {
        val chat = try {
            messenger.accept(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (chat != null) decided = decided + request.id
        return chat
    }

    /** Hides the card at once; a decision the messenger refused brings it back. */
    private suspend fun decide(id: RequestId, action: suspend () -> Unit) {
        decided = decided + id
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            decided = decided - id
        }
    }
}
