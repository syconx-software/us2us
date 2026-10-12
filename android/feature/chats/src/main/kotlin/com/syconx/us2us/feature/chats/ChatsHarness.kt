package com.syconx.us2us.feature.chats

import androidx.compose.runtime.Immutable
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.feature.chats.invite.InviteChannel
import com.syconx.us2us.feature.chats.invite.InviteDebugState
import com.syconx.us2us.feature.chats.invite.InviteLink

/**
 * What the chats harnesses set up in 4.1 and its sheets (iOS `ChatsHarness.State`); 4.1 applies it
 * once when it first appears. The 4.3 and 4.4 screens read [state] itself (`4.3-block`, `4.4-report`).
 */
@Immutable
data class ChatsUiState(
    override val state: String,
    /** No chats (`4.1-empty`). */
    val hidesChats: Boolean = false,
    /** The 4.2 sheet opens with this query. */
    val newChatQuery: String? = null,
    val newChatDebug: NewChatModel.DebugOptions = NewChatModel.DebugOptions(),
    /** The QR menu is open (`4.1-qr`). */
    val showsQRMenu: Boolean = false,
    /** The own-code sheet is up (`4.1-mycode`). */
    val showsMyCode: Boolean = false,
    /** The invite sheet over 4.1 (`invite:<state>`). */
    val invite: InviteDebugState? = null,
) : HarnessUiState

/**
 * `U2ChatsDemo <action>`: one interaction performed by itself about 1.5 s after the screen appears,
 * so transitions can be recorded and checked without touching the device.
 */
enum class ChatsDemo(val raw: String) {
    /** 4.2: “Mesaj yaz” once the result is in → sheet down, 5.1 pushed. */
    Write("write"),

    /** 4.4: “Kabul et” (the request preview's own demo). */
    Accept("accept"),

    /** 4.4: “Sil” (the request preview's own demo). */
    DeletePreview("delete-preview"),

    /** 4.3: “Sil” on n_aksoy → the card fades and collapses. */
    Delete("delete"),

    /** 4.3: “Kabul et” on kuzey_dev → 5.1 replaces 4.3. */
    AcceptCard("accept-card"),

    /** 4.3: open the first card → 4.4 pushed. */
    OpenCard("open-card"),

    /** 4.1: QR menu → “Kodumu göster”. */
    QrMyCode("qr-mycode"),

    /** 4.1: tap deniz's row → 5.1 pushed. */
    OpenChat("open-chat"),

    /** 4.1: tap compose → the 4.2 sheet rises. */
    Compose("compose"),

    /** 4.1 → 5.1 → 4.1, five times. */
    PushPop("push-pop"),

    /** The 4.2 sheet up and down, five times. */
    SheetCycle("sheet-cycle"),
    ;

    companion object {
        /** The demo delay. */
        const val DELAY_MILLIS = 1_500L

        fun fromRaw(raw: String?): ChatsDemo? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Debug harness for 04 · Sohbetler: 4.1–4.4 (`chats:<state>`), every screen and state in its exact
 * design state, for screenshots and design comparisons.
 *
 * | state | shows |
 * |---|---|
 * | `4.1` | chat list (`U2GhostUrgent` for the last-hour variant) |
 * | `4.1-connecting` · `4.1-closed` | line connecting / closed (proposed copy, packets hidden) |
 * | `4.1-empty` | no chats, no requests (proposed copy) |
 * | `4.1-qr` · `4.1-mycode` | the QR menu · the own-code sheet (proposed) |
 * | `4.2` | new chat, “deniz” found (focused look, no keyboard) |
 * | `4.2-keyboard` | the same with the real focus (keyboard up) |
 * | `4.2-empty` · `4.2-searching` · `4.2-notfound` · `4.2-existing` · `4.2-self` | the other lookup states |
 * | `4.2-mycode` | “Kodumu göster” pushed inside the sheet |
 * | `4.3` · `4.3-empty` · `4.3-block` | requests · none left · the block confirmation |
 * | `4.4` · `4.4-media` · `4.4-link` | preview of kuzey_dev · n_aksoy (photo) · hediye_kazan (link) |
 * | `4.4-report` | the report sheet over hediye_kazan's preview (proposed) |
 * | `4.1-blocked` | the list with bora blocked: “Engellendi” in the preview's place (proposed) |
 */
object ChatsHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Chats

    override val states: List<String> = listOf(
        "4.1", "4.1-connecting", "4.1-closed", "4.1-empty", "4.1-qr", "4.1-mycode", "4.2", "4.2-keyboard",
        "4.2-empty", "4.2-searching", "4.2-notfound", "4.2-existing", "4.2-self", "4.2-mycode", "4.3", "4.3-empty",
        "4.3-block", "4.4", "4.4-media", "4.4-link", "4.4-report", "4.1-blocked",
    )

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        state ?: return null
        val base = ChatsUiState(state)
        fun chats(ui: ChatsUiState = base, prepare: (suspend () -> Unit)? = null) =
            HarnessPlan(StartScreen.Chats, uiState = ui, prepare = prepare)
        fun stack(vararg routes: AppRoute, prepare: (suspend () -> Unit)? = null) =
            HarnessPlan(StartScreen.Chats, stack = routes.toList(), uiState = base, prepare = prepare)
        fun newChat(query: String, focusLook: Boolean = true, searching: Boolean = false, myCode: Boolean = false) = chats(
            base.copy(
                newChatQuery = query,
                newChatDebug = NewChatModel.DebugOptions(freezesSearching = searching, forcesFocusChrome = focusLook, startsOnMyCode = myCode),
            ),
        )
        return when (state) {
            "4.1" -> chats()
            "4.1-connecting" -> chats { controls?.setConnection(ConnectionState.Connecting) }
            "4.1-closed" -> chats { controls?.setConnection(ConnectionState.Closed) }
            "4.1-empty" -> chats(base.copy(hidesChats = true)) { controls?.clearRequests() }
            "4.1-qr" -> chats(base.copy(showsQRMenu = true))
            "4.1-mycode" -> chats(base.copy(showsMyCode = true))
            "4.1-blocked" -> chats { controls?.setBlocked(ChatId("bora")) }
            "4.2" -> newChat("deniz")
            "4.2-keyboard" -> newChat("deniz", focusLook = false)
            "4.2-empty" -> newChat("")
            "4.2-searching" -> newChat("deniz", searching = true)
            "4.2-notfound" -> newChat("deniz_yilmaz")
            "4.2-existing" -> newChat("mert_k")
            "4.2-self" -> newChat(ownUsername(controls))
            "4.2-mycode" -> newChat("", focusLook = false, myCode = true)
            "4.3", "4.3-block" -> stack(AppRoute.Requests)
            "4.3-empty" -> stack(AppRoute.Requests) { controls?.clearRequests() }
            "4.4" -> stack(AppRoute.Requests, AppRoute.RequestPreview(RequestId("kuzey_dev")))
            "4.4-media" -> stack(AppRoute.Requests, AppRoute.RequestPreview(RequestId("n_aksoy")))
            "4.4-link", "4.4-report" -> stack(AppRoute.Requests, AppRoute.RequestPreview(RequestId("hediye_kazan")))
            else -> null
        }
    }

    private fun ownUsername(controls: FixtureControls?): String =
        (controls as? MessengerService)?.account?.value?.username ?: DesignFixtures.OWN_USERNAME
}

/** `newChat` · `newChat:<username>`: the 4.2 sheet over 4.1 with that query (sheets are not stack routes). */
object NewChatHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.NewChat

    override val states: List<String> = emptyList()

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan =
        HarnessPlan(StartScreen.Chats, uiState = ChatsUiState(state ?: "", newChatQuery = target.orEmpty()))
}

/**
 * Debug harness of the invitation (`invite[:<state>]`):
 *
 * | state | shows |
 * |---|---|
 * | (none) | the invite sheet over 4.1 |
 * | `edit` | the message being edited (keyboard up) |
 * | `edited` | an edited message (the bubble shows the user's text) |
 * | `copied` · `sent` | the row status after “Bağlantıyı kopyala” / after a channel sent it |
 * | `link` · `link-unknown` | an invite link for `deniz` / `deniz_yilmaz` arrives: 4.2 searched |
 *
 * `U2OpenURL <url>` hands any URL to the inbox as if the system had opened the app with it (invalid
 * ones are ignored, as in production).
 */
object InviteHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Invite

    override val states: List<String> = listOf(
        "edit", "edited", "copied", "sent", "link", "link-unknown",
    )

    /** The edited sample text of `invite:edited`. */
    const val EDITED_SAMPLE = "Selam! Artık us2us’tayım, buradan yazışalım mı? @selin_n\nhttps://us2us.app/u/selin_n"

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        val id = state.orEmpty()
        sheetState(id)?.let { return HarnessPlan(StartScreen.Chats, uiState = ChatsUiState(id, invite = it)) }
        val username = when (id) {
            "link" -> "deniz"
            "link-unknown" -> "deniz_yilmaz"
            else -> return null
        }
        return HarnessPlan(StartScreen.Chats, uiState = ChatsUiState(id)) {
            InviteLink.url(username)?.let(InviteLinkInbox::handle)
        }
    }

    /** The sheet a state id opens with, `null` for the link states and unknown ids. */
    fun sheetState(id: String?): InviteDebugState? = when (id.orEmpty()) {
        "" -> InviteDebugState()
        "edit" -> InviteDebugState(startsEditing = true)
        "edited" -> InviteDebugState(editedText = EDITED_SAMPLE)
        "copied" -> InviteDebugState(status = InviteChannel.CopyLink)
        "sent" -> InviteDebugState(status = InviteChannel.Sms)
        else -> null
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val chatsHarnesses: List<FeatureHarness> = listOf(ChatsHarness, NewChatHarness, InviteHarness)
