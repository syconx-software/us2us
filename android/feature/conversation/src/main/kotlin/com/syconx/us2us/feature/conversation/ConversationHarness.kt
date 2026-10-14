package com.syconx.us2us.feature.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.ui.composer.ComposerVoiceMode
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.domain.sticker.StickerLanguage
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Debug harness for 05 · Sohbet: opens every screen and state directly, in its exact design state,
 * for screenshots and design comparisons (`U2Route conversation:<chat>/<state>`, the iOS ids):
 *
 * | state | shows |
 * |---|---|
 * | `deniz/5.1` | 5.1 chat (`U2GhostUrgent`: last hour + banner) |
 * | `deniz/5.1-failed` | newest message not delivered: “İletilemedi” + “Tekrar gönder” |
 * | `deniz/5.1-reply` | composer with the reply quote (after “Yanıtla”) |
 * | `deniz/5.1-attach` · `5.1-stickers` | the “+” menu · the sticker picker |
 * | `deniz/5.1-blocked` | deniz blocked (proposed): “Engellendi” in the header, the bar with “Engeli kaldır” in the composer's place |
 * | `deniz/5.3` · `5.3-incoming` · `5.3-confirm` | actions on “Kapı kodu 4471” · on deniz's photo · the “Herkesten sil” confirmation |
 * | `deniz/5.4` · `5.4-pending` · `5.4-failed` | info of “Çok güzelmiş! 8 gibi olur mu?” · “Kapı kodu 4471” sent only · not delivered |
 * | `deniz/5.5` | Ghost sheet (on, 24 sa); `mert_k/5.5` for Ghost off |
 * | `deniz/5.1-voice-*` | the recorder held, locked, in review, its hint, the microphone refused; an unplayed voice message |
 * | `mert_k/5.2*`, `deniz/5.1-viewer*`, `deniz/5.6*` | the media states: the covers open over the chat |
 */
object ConversationHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Conversation

    override val states: List<String> = listOf(
        "deniz/5.1", "deniz/5.1-failed", "deniz/5.1-reply", "deniz/5.1-attach", "deniz/5.1-stickers",
        "deniz/5.1-blocked", "deniz/5.1-viewer", "deniz/5.1-viewer-zoomed", "deniz/5.3", "deniz/5.3-incoming",
        "deniz/5.3-confirm", "deniz/5.4", "deniz/5.4-pending", "deniz/5.4-failed", "deniz/5.5", "deniz/5.6",
        "deniz/5.6-clean", "deniz/5.6-captured", "deniz/5.1-save-notice", "deniz/5.2-place", "deniz/5.2-map",
        "deniz/5.2-map-peer", "deniz/5.2-map-ask", "deniz/5.2-map-denied", "deniz/5.1-voice-hold",
        "deniz/5.1-voice-locked", "deniz/5.1-voice-review", "deniz/5.1-voice-hint", "deniz/5.1-voice-permission",
        "deniz/5.1-voice-unplayed", "mert_k/5.2", "mert_k/5.2-ended", "mert_k/5.2-opened", "mert_k/5.2-player",
        "mert_k/5.2-player-loading", "mert_k/5.2-file", "mert_k/5.2-file-actions", "mert_k/5.2-remote",
        "mert_k/5.2-downloading", "mert_k/5.2-unavailable", "mert_k/5.2-preparing", "mert_k/5.2-prepare-failed",
        "mert_k/5.2-file-disappearing", "mert_k/5.2-saved", "mert_k/5.4-saved", "mert_k/5.5",
    )

    /** The view-once photo of the 5.6 reference (deniz, 18:40): no message holds it, the cover draws the design's picture. */
    val viewOnceDesignMessage = MessageId("viewOnce.design")

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        val chat = ChatId(target?.takeIf { it.isNotEmpty() } ?: return null)
        val ui = ConversationHarnessState(state ?: "5.1", chat)
        return HarnessPlan(
            start = StartScreen.Chats,
            stack = listOf(AppRoute.Conversation(chat)),
            uiState = ui,
            prepare = controls?.let { fixtures -> { prepare(ui, fixtures) } },
        )
    }

    /** The service side of a state, before its first frame. */
    suspend fun prepare(ui: ConversationHarnessState, fixtures: FixtureControls) {
        val chat = ui.chat
        val messenger = fixtures as? MessengerService
        when (ui.state) {
            "5.1-blocked" -> fixtures.setBlocked(chat)
            "5.1-failed" -> fixtures.setStatus(MessageStatus.Failed, MessageId("d6"), chat, DesignFixtures.referenceDate)
            "5.4-failed" -> fixtures.setStatus(MessageStatus.Failed, MessageId("d6"), chat, DesignFixtures.referenceDate)
            "5.2-ended" -> messenger?.stopLiveLocation(MessageId("m4"), chat)
            "5.2-opened" -> messenger?.markViewOnceOpened(MessageId("m6"), chat)
            "5.2-map", "5.2-map-peer", "5.2-map-ask", "5.2-map-denied" -> {
                if (ui.state == "5.2-map-ask") fixtures.ownPositionOverride = OwnPositionState.NeedsPermission
                if (ui.state == "5.2-map-denied") fixtures.ownPositionOverride = OwnPositionState.Denied
                ui.peerShare = fixtures.startDemoLiveLocation(chat)
                // Only the peer shares: the user's own share has ended.
                if (ui.state != "5.2-map") messenger?.stopLiveLocation(MessageId("m4"), chat)
            }
            "5.2-player-loading" -> fixtures.holdsMediaPayloads = true
            "5.2-remote", "5.2-downloading", "5.2-unavailable" -> {
                val (video, file) = when (ui.state) {
                    "5.2-remote" -> MediaAvailability.Remote to MediaAvailability.Remote
                    "5.2-downloading" -> MediaAvailability.Downloading(0.4) to MediaAvailability.Downloading(0.62)
                    else -> MediaAvailability.Unavailable to MediaAvailability.Unavailable
                }
                fixtures.setMediaAvailability(video, MessageId("m2"), chat)
                fixtures.addIncomingFile(chat, file)
            }
            "5.2-preparing", "5.2-prepare-failed" -> fixtures.addOutgoingVideo(chat, failed = ui.state == "5.2-prepare-failed")
            "5.2-file-disappearing" -> fixtures.setDisappearing(DisappearingTimer.FiveMinutes, MessageId("m1"), chat)
            "5.2-saved", "5.4-saved" -> fixtures.setSavedByPeer(MessageId("m1"), chat)
            "5.1-voice-unplayed" -> fixtures.addIncomingVoice(chat)
        }
    }

    /** The view-model side of a state, once the conversation is on screen. */
    suspend fun apply(ui: ConversationHarnessState, viewModel: ConversationViewModel) {
        fun message(id: String) = viewModel.message(MessageId(id))
        when (ui.state) {
            "5.1-reply" -> message("d2")?.let { viewModel.replyTo = it.quote(viewModel.ownUsername, viewModel.peerUsername, viewModel.strings) }
            "5.1-attach" -> {
                delay(300)
                if (!viewModel.showsAttachMenu) viewModel.toggleAttachMenu()
            }
            "5.1-stickers" -> {
                delay(300)
                viewModel.openStickerPicker()
            }
            "5.2-place" -> {
                delay(300)
                message("m3")?.let(viewModel::openLocation)
            }
            "5.2-map", "5.2-map-peer", "5.2-map-ask", "5.2-map-denied" -> {
                delay(300)
                val share = if (ui.state == "5.2-map") MessageId("m4") else ui.peerShare
                share?.let(viewModel::message)?.let(viewModel::openLiveMap)
            }
            "5.3" -> viewModel.openActionsWhenReady(MessageId("d6"))
            "5.3-incoming" -> viewModel.openActionsWhenReady(MessageId("d2"))
            "5.2-file-actions" -> viewModel.openActionsWhenReady(MessageId("m1"))
            "5.3-confirm" -> {
                delay(600)
                viewModel.deleteForEveryoneCandidate = MessageId("d6")
            }
            "5.1-viewer", "5.1-viewer-zoomed", "5.1-save-notice" -> {
                if (ui.state == "5.1-save-notice") delay(300)
                message("d2")?.let(viewModel::openMedia)
            }
            "5.2-player" -> viewModel.cover = ConversationViewModel.Cover.Video(MessageId("m2"))
            "5.2-player-loading" -> message("m2")?.let(viewModel::openMedia)
            "5.2-file", "5.2-file-disappearing" -> {
                delay(400)
                message("m1")?.let(viewModel::openMedia)
            }
            "5.4-saved" -> viewModel.info = ConversationViewModel.InfoItem(MessageId("m1"))
            "5.4" -> viewModel.info = ConversationViewModel.InfoItem(MessageId("d3"))
            "5.4-pending", "5.4-failed" -> viewModel.info = ConversationViewModel.InfoItem(MessageId("d6"))
            "5.5" -> viewModel.showsGhostSettings = true
            "5.6", "5.6-clean", "5.6-captured" -> viewModel.cover = ConversationViewModel.Cover.ViewOnce(viewOnceDesignMessage)
            "5.1-voice-hold" -> viewModel.voice.showDemo(ComposerVoiceMode.Holding, 7.seconds, SlideOffset(-34f, -18f))
            "5.1-voice-locked" -> viewModel.voice.showDemo(ComposerVoiceMode.Locked, 12.seconds)
            "5.1-voice-review" -> viewModel.voice.showDemo(ComposerVoiceMode.Review, 12.seconds)
            "5.1-voice-hint" -> {
                delay(300)
                viewModel.voice.showDemoHint()
            }
            "5.1-voice-permission" -> {
                delay(300)
                viewModel.voice.showsPermissionNotice = true
            }
        }
    }
}

/**
 * `U2ConversationDemo <action>`: one interaction performed by itself after the screen appears
 * (`U2ConversationDemoDelay`, 1.5 s by default), so transitions can be recorded and checked with
 * screenshots at intervals (the iOS demos).
 */
internal object ConversationDemo {
    suspend fun run(demo: String, viewModel: ConversationViewModel) {
        val chat = viewModel.chatId
        val fixtures = viewModel.messenger as? FixtureControls
        fun message(id: String) = viewModel.message(MessageId(id))
        when (demo) {
            "send" -> {
                viewModel.draft = "Görüşürüz 👋"
                viewModel.sendDraft()
            }
            "sendLong" -> {
                viewModel.draft = "Tamam, o zaman sekizde iskelede buluşalım. Ben biraz erken gelip vapurun saatine de bakarım, gecikirse haber veririm."
                viewModel.sendDraft()
            }
            "sticker" -> StickerCatalog.sticker(103)?.let { viewModel.send(BrandStickerSelection(it, StickerLanguage.forLocale(viewModel.strings.locale))) }
            "status" -> {
                val id = ConversationRows.lastOutgoingId(viewModel.messages) ?: return
                fixtures?.setStatus(MessageStatus.Delivered, id, chat)
                delay(2.seconds)
                fixtures?.setStatus(MessageStatus.Read, id, chat)
            }
            "react" -> {
                viewModel.openActionsWhenReady(MessageId("d5"))
                delay(1.2.seconds)
                viewModel.react("❤️", MessageId("d5"))
            }
            "reply" -> {
                viewModel.openActionsWhenReady(MessageId("d1"))
                delay(1.2.seconds)
                viewModel.reply(MessageId("d1"))
            }
            "deleteEveryone" -> viewModel.messenger.deleteForEveryone(MessageId("d5"), chat)
            "deleteMe" -> viewModel.messenger.deleteForMe(MessageId("d1"), chat)
            "ghostSave" -> viewModel.saveGhost(true, GhostPeriod.SixHours, DisappearingTimer.FiveMinutes)
            "stopLive" -> viewModel.stopLiveLocation(MessageId("m4"))
            "actions" -> viewModel.messages.lastOrNull { !it.isSystem }?.let { viewModel.openActionsWhenReady(it.id) }
            "viewOnce" -> message("m6")?.let(viewModel::openViewOnce)
            "attach" -> viewModel.toggleAttachMenu()
            "openPhoto" -> message("d2")?.let(viewModel::openMedia)
            "playVideo" -> message("m2")?.let(viewModel::openMedia)
            "openFile" -> message("m1")?.let(viewModel::openMedia)
            "downloadFile" -> {
                val id = fixtures?.addIncomingFile(chat, MediaAvailability.Remote) ?: return
                delay(1.seconds)
                viewModel.message(id)?.let(viewModel::openMedia)
            }
            "sendVideo" -> fixtures?.simulateVideoSend(chat)
            "sendPhotos" -> {
                viewModel.draft = "Dünkü yürüyüşten"
                viewModel.sendDemoAttachment(ConversationViewModel.AttachmentKind.Photo)
            }
            "playVoice" -> viewModel.toggleVoice(MessageId("d4"))
            "openPlace" -> message("m3")?.let(viewModel::openLocation)
            "openMap" -> message("m4")?.let(viewModel::openLiveMap)
            "typing" -> {
                viewModel.debugPeerTyping = true
                delay(2.5.seconds)
                viewModel.debugPeerTyping = false
                viewModel.appendDebug(incoming(viewModel))
            }
            "incoming" -> viewModel.appendDebug(incoming(viewModel))
        }
    }

    private var incomingCount = 0

    /** A message from the peer, a minute after the newest. */
    private fun incoming(viewModel: ConversationViewModel): Message {
        incomingCount++
        val after = viewModel.messages.lastOrNull()?.sentAt ?: DesignFixtures.referenceDate
        return Message(
            MessageId("demo-in-$incomingCount"),
            MessageDirection.Incoming,
            after.plusSeconds(60),
            MessageContent.Text("Geldim, iskelenin başındayım 🙂"),
        )
    }
}

/**
 * A conversation harness state: the state id and the chat it is for. Applied once, when that chat
 * first shows (re-entering the chat shows it as it is).
 */
class ConversationHarnessState(override val state: String, val chat: ChatId) : HarnessUiState {
    /** The peer's demo live share (`5.2-map-peer` and the like), started in `prepare`. */
    var peerShare: MessageId? = null

    var isApplied: Boolean = false
        internal set
}

/** Runs the harness state on [viewModel] when it is for this chat. */
@Composable
internal fun ConversationHarnessEffect(viewModel: ConversationViewModel) {
    val ui = LocalHarnessState.current as? ConversationHarnessState ?: return
    if (ui.chat != viewModel.chatId) return
    val configuration = LocalAppModel.current.configuration
    LaunchedEffect(ui, viewModel) {
        if (ui.isApplied) return@LaunchedEffect
        ui.isApplied = true
        ConversationHarness.apply(ui, viewModel)
        val demo = configuration.conversationDemo ?: return@LaunchedEffect
        delay((configuration.conversationDemoDelay?.takeIf { it > 0 } ?: 1.5).seconds)
        ConversationDemo.run(demo, viewModel)
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val conversationHarnesses: List<FeatureHarness> = listOf(ConversationHarness)
