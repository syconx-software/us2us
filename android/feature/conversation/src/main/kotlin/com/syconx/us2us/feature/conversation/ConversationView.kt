package com.syconx.us2us.feature.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.PickedMediaPlan
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.StickerCatalog
import com.syconx.us2us.feature.conversation.voice.MediaPlayerVoiceEngine
import com.syconx.us2us.feature.conversation.voice.VoicePermissionSheet
import com.syconx.us2us.feature.conversation.voice.rememberMicrophoneVoiceDevice
import com.syconx.us2us.feature.media.FilePreviewCover
import com.syconx.us2us.feature.media.LiveLocationMapCover
import com.syconx.us2us.feature.media.LocationOpenSheet
import com.syconx.us2us.feature.media.MediaViewerCover
import com.syconx.us2us.feature.media.VideoPlayerCover
import com.syconx.us2us.feature.media.ViewOnceCover
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.brand.StickerPickerSheet
import com.syconx.us2us.ui.bubbles.LocalVoicePlayback
import com.syconx.us2us.ui.components.ConfirmationTags
import com.syconx.us2us.ui.components.DestructiveConfirmation
import com.syconx.us2us.ui.components.FloatingMenu
import com.syconx.us2us.ui.components.FloatingMenuItem
import com.syconx.us2us.ui.components.FloatingMenuMetrics
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.GhostUrgentBanner
import com.syconx.us2us.ui.composer.Composer
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.env.rememberU2Now
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Strings
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.media.LocalConversationMediaActivity
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.rememberU2BackdropSource
import com.syconx.us2us.ui.theme.u2BackdropSource
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter

/**
 * 05 · Sohbet: the conversation (5.1, and 5.2 for the content catalogue).
 *
 * C8 header (back · avatar · name + seal · presence · live Ghost chip → 5.5) over the
 * bottom-aligned bubble column (gap 6, pad 0 14 100) and the C21 composer, which follows the
 * keyboard. Only the newest outgoing message says its state as a sentence. Long-press a bubble →
 * 5.3; Bilgi → 5.4; a view-once bubble → 5.6; a photo → the viewer, a video → the player, a file
 * → its preview; the urgent banner floats above the composer in Ghost's last hour, and a changed
 * safety number keeps a banner there until the user decides. The mic records a voice message
 * (press and hold; proposed), a voice bubble plays one; the screen stays on while either goes on.
 */
@Composable
internal fun ConversationContent(chatId: ChatId, modifier: Modifier = Modifier) {
    val messenger = LocalMessenger.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val platform = rememberConversationPlatform()
    val device = rememberMicrophoneVoiceDevice()
    val viewModel = remember(chatId, messenger) {
        ConversationViewModel(chatId, messenger, scope, platform, device, MediaPlayerVoiceEngine())
    }
    viewModel.density = LocalDensity.current.density
    LaunchedEffect(viewModel) { viewModel.run() }
    LaunchedEffect(viewModel) {
        viewModel.loadStickerRecents()
        messenger.conversationAppeared(chatId)
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopVoice()
            messenger.conversationDisappeared(chatId)
        }
    }
    // Ordinary media closes with the app (the lock follows) or when its message goes; a recording
    // and a voice message in memory go with the foreground too.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.closeMedia()
        viewModel.closeLocationScreens()
        viewModel.stopVoice()
    }
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.presentedMediaIsGone }.filter { it }.collect { viewModel.closeMedia() }
    }
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.playingVoiceIsGone }.filter { it }.collect { viewModel.stopVoicePlayback() }
    }
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.pendingFileIsReady }.filter { it }.collect { viewModel.openPendingFile() }
    }
    // Blocked while the chat is open (from the contact screen): the composer goes, and with it the
    // keyboard and whatever it had open.
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.isBlocked }.filter { it }.collect { viewModel.leaveComposer() }
    }
    // The chat ended underneath (Ghost erased it on both ends, or it was removed elsewhere): leave
    // softly instead of showing an empty shell.
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.chat == null && router.top == AppRoute.Conversation(chatId) }.filter { it }.collect { router.pop() }
    }
    KeepsScreenAwake(viewModel.keepsScreenAwake)
    ConversationHarnessEffect(viewModel)

    CompositionLocalProvider(
        LocalVoicePlayback provides viewModel.voicePlayer,
        LocalConversationMediaActivity provides viewModel.mediaActivity,
    ) {
        ConversationLayers(viewModel, modifier)
        ConversationSheets(viewModel)
    }
    FileExport(viewModel, context)
}

/** The screen's own pieces the view model asks for. */
private class ScreenPlatform(private val context: Context) : ConversationPlatform {
    override var haptics: U2Haptics = U2Haptics.None
    override var strings: U2Strings = U2Strings.wrap(context.resources)
    var keyboard: (() -> Unit)? = null

    override fun dismissKeyboard() {
        keyboard?.invoke()
    }

    /** This phone only (marked sensitive), and gone after three minutes if it is still ours. */
    override fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (current == text) runCatching { clipboard.clearPrimaryClip() }
        }, 3.minutes.inWholeMilliseconds)
    }
}

@Composable
private fun rememberConversationPlatform(): ConversationPlatform {
    val context = LocalContext.current
    val platform = remember(context) { ScreenPlatform(context.applicationContext) }
    val keyboard = rememberU2Keyboard()
    platform.haptics = LocalHaptics.current
    platform.strings = rememberU2Strings()
    platform.keyboard = keyboard::dismiss
    return platform
}

/** The screen stays on while a voice message records or plays, and may sleep again when that ends or the chat closes. */
@Composable
private fun KeepsScreenAwake(awake: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun ConversationLayers(viewModel: ConversationViewModel, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val visible = viewModel.actionsVisible
    val dim by animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) U2Motion.reducedCrossFade.spec() else if (visible) U2Motion.spring() else U2Motion.soft(0.24f).spec(),
        label = "actionsDim",
    )
    var composerBottom by remember { mutableFloatStateOf(0f) }
    // Back closes the 5.3 menu or the “+” menu first (iOS: their escape action).
    BackHandler(enabled = viewModel.actions != null) { viewModel.dismissActions() }
    BackHandler(enabled = viewModel.showsAttachMenu) { viewModel.dismissAttachMenu() }
    U2Screen(modifier.testTag("conversation")) {
        Box(
            Modifier
                .fillMaxSize()
                .blur(14.dp * dim, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { alpha = 1f - (1f - U2Opacity.contextBackdropContent) * dim }
                .then(if (viewModel.actions != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            ChatLayer(viewModel, onComposerBottom = { composerBottom = it })
        }
        if (viewModel.actions != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dim }
                    .background(U2Theme.colors.actionsOverlay),
            )
        }
        val context = viewModel.actions
        val message = context?.let { viewModel.message(it.messageId) }
        if (context != null && message != null) {
            MessageActionsOverlay(viewModel, context, message)
        }
        AttachMenuLayer(viewModel, composerBottom)
    }
}

@Composable
private fun ChatLayer(viewModel: ConversationViewModel, onComposerBottom: (Float) -> Unit) {
    val router = LocalRouter.current
    val backdrop = rememberU2BackdropSource()
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(U2Size.composer + 50.dp) }
    val chatId = viewModel.chatId
    Box(Modifier.fillMaxSize()) {
        ConversationMessageList(
            viewModel,
            bottomPadding = composerHeight,
            modifier = Modifier.fillMaxSize().u2BackdropSource(backdrop),
            onVerify = { router.push(AppRoute.SafetyNumber(chatId)) },
        )
        ComposerArea(
            viewModel,
            Modifier
                .align(Alignment.BottomCenter)
                .onPlaced { coordinates ->
                    composerHeight = with(density) { coordinates.size.height.toDp() }
                    onComposerBottom(coordinates.positionInParent().y + coordinates.size.height)
                },
        )
        Header(viewModel, backdrop)
    }
}

@Composable
private fun Header(viewModel: ConversationViewModel, backdrop: U2BackdropSource) {
    val router = LocalRouter.current
    val chat = viewModel.chat ?: return
    val peer = chat.peer
    ChatHeader(
        username = peer.username,
        palette = peer.palette,
        onBack = { router.pop() },
        isVerified = peer.isVerified,
        showsOnlineDot = peer.isOnline,
        avatar = peer.avatar,
        onTapPeer = { router.push(AppRoute.SafetyNumber(viewModel.chatId)) },
        peerHint = stringResource(R.string.conversation_a11y_header_peer_hint),
        backdrop = backdrop,
        status = {
            // Blocked: “Engellendi” where the presence line would be.
            Crossfade(peer.isBlocked, animationSpec = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(), label = "headerBlocked") { blocked ->
                if (blocked) {
                    HeaderStatusText(stringResource(R.string.common_block_blocked), Modifier.testTag("conversation.blockedStatus"))
                } else {
                    PresenceLine(if (peer.isAcceptedContact) peer.presence else Presence.Hidden)
                }
            }
        },
        trailing = {
            // Blocked: shown as it is, but nothing can be changed (nothing reaches them).
            val blocked = peer.isBlocked
            val alpha by animateFloatAsState(if (blocked) U2Opacity.disabled else 1f, U2Motion.resolve(U2Motion.state, LocalReduceMotion.current).spec(), label = "ghostChipBlocked")
            GhostChip(
                chat.ghost,
                onClick = { if (!viewModel.isBlocked) viewModel.openGhostSettings() },
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .semantics { if (blocked) disabled() }
                    .testTag("conversation.ghost"),
            )
        },
    )
}

/**
 * The composer and what floats above it: the changed-safety-number banner (in the column's inset,
 * so it never covers the newest messages) and the urgent banner in Ghost's last hour (6 dp above
 * the composer, 12 dp gutters; it rises in over 0.8 s when the hour starts).
 */
@Composable
private fun ComposerArea(viewModel: ConversationViewModel, modifier: Modifier) {
    val router = LocalRouter.current
    val safeArea = currentSafeArea
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    // The design's 36 above the bottom at rest, 8 above the keyboard.
    val bottomPadding = maxOf(8.dp, safeArea.bottom(36.dp) - keyboard)
    val now = rememberU2Now(1.seconds)
    val chat = viewModel.chat
    val urgent = chat != null && chat.ghost.isUrgent(now)
    val deadline = chat?.ghost?.deadline
    val fade = U2Transitions.fade()
    BannerAbove(
        modifier = modifier.fillMaxWidth(),
        content = {
            AnimatedContent(
                targetState = viewModel.isBlocked,
                transitionSpec = { fade.enter togetherWith fade.exit },
                contentAlignment = Alignment.BottomCenter,
                label = "composerBlocked",
            ) { blocked ->
                if (blocked) {
                    // Blocked (proposed): the bar takes the composer's place, as 4.4's decision bar
                    // does; nothing is sent from here.
                    BlockedChatBar(viewModel.peerUsername, onUnblock = viewModel::unblock, modifier = Modifier.then(u2TransitionBlur(fade)))
                } else {
                    Column(Modifier.fillMaxWidth().then(u2TransitionBlur(fade)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val rise = U2Transitions.rise()
                        AnimatedVisibility(viewModel.showsSafetyNumberChanged, enter = rise.enter, exit = rise.exit) {
                            SafetyNumberChangedBanner(
                                onCompare = { router.push(AppRoute.SafetyNumber(viewModel.chatId)) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        Composer(
                            text = viewModel.draft,
                            onTextChange = { viewModel.draft = it },
                            onSend = viewModel::sendDraft,
                            modifier = Modifier.fillMaxWidth().testTag("composer"),
                            quote = viewModel.replyTo,
                            onCancelQuote = viewModel::cancelReply,
                            onAttach = viewModel::toggleAttachMenu,
                            onTimer = viewModel::openGhostSettings,
                            onSticker = viewModel::openStickerPicker,
                            bottomPadding = bottomPadding,
                            voice = viewModel.voice,
                        )
                    }
                }
            }
        },
        banner = {
            val rise = U2Transitions.rise(U2Motion.ambient)
            AnimatedVisibility(urgent && deadline != null, enter = rise.enter, exit = rise.exit) {
                if (deadline != null) GhostUrgentBanner(deadline, Modifier.padding(horizontal = 12.dp).testTag("conversation.urgent"))
            }
        },
    )
}

/** [content], with [banner] hanging 6 dp above its top (outside its size, as an overlay). */
@Composable
private fun BannerAbove(modifier: Modifier, content: @Composable () -> Unit, banner: @Composable () -> Unit) {
    Layout(contents = listOf(content, banner), modifier = modifier) { (contentMeasurables, bannerMeasurables), constraints ->
        val main = contentMeasurables.first().measure(constraints)
        val banners = bannerMeasurables.map { it.measure(Constraints(maxWidth = main.width)) }
        val gap = 6.dp.roundToPx()
        layout(main.width, main.height) {
            banners.forEach { it.place(0, -(it.height + gap)) }
            main.place(0, 0)
        }
    }
}

/** “+” menu (proposed): Fotoğraf ve video · Dosya · Konum · Canlı konum, floating 8 dp above “+” in the 5.3 menu style; a tap outside closes it. */
@Composable
private fun AttachMenuLayer(viewModel: ConversationViewModel, composerBottom: Float) {
    val density = LocalDensity.current
    val safeArea = currentSafeArea
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val items = attachItems(viewModel)
    val height = FloatingMenuMetrics.height(items)
    val width = 220.dp
    val bottomPadding = if (keyboard > 0.dp) 8.dp else safeArea.bottom(36.dp)
    val plusTop = with(density) { composerBottom.toDp() } - bottomPadding - U2Size.composer
    val shown = viewModel.showsAttachMenu
    if (shown) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = viewModel::dismissAttachMenu)
                .clearAndSetSemantics {},
        )
    }
    val transition = U2Transitions.soft(DpOffset(0.dp, 8.dp), blur = 2.dp, scale = 0.96f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        AnimatedVisibility(
            shown,
            modifier = Modifier.offset(x = U2Spacing.chatComposerGutter, y = plusTop - 8.dp - height),
            enter = transition.enter,
            exit = transition.exit,
        ) {
            FloatingMenu(items, Modifier.testTag("attach.menu"), width = width)
        }
    }
}

@Composable
private fun attachItems(viewModel: ConversationViewModel): List<FloatingMenuItem> {
    val media = stringResource(R.string.conversation_attach_media)
    val file = stringResource(R.string.conversation_attach_file)
    val location = stringResource(R.string.common_media_location)
    val live = stringResource(R.string.common_location_live)
    val picker = rememberMediaPicker(viewModel)
    return listOf(
        FloatingMenuItem("photo", media, PhosphorGlyph.Image) { viewModel.sendDemoAttachment(ConversationViewModel.AttachmentKind.Photo, picker) },
        FloatingMenuItem("file", file, PhosphorGlyph.FilePdf) { viewModel.sendDemoAttachment(ConversationViewModel.AttachmentKind.File, picker) },
        FloatingMenuItem("location", location, PhosphorGlyph.MapPin, glyphWeight = PhosphorWeight.Fill) {
            viewModel.sendDemoAttachment(ConversationViewModel.AttachmentKind.Location, picker)
        },
        FloatingMenuItem("liveLocation", live, PhosphorGlyph.NavigationArrow) {
            viewModel.sendDemoAttachment(ConversationViewModel.AttachmentKind.LiveLocation, picker)
        },
    )
}

/**
 * The live service's pickers: the system photo picker (up to ten photos and videos, no library
 * permission) and the document picker. Each pick is copied by the service.
 */
@Composable
private fun rememberMediaPicker(viewModel: ConversationViewModel): (ConversationViewModel.AttachmentKind) -> Unit {
    val context = LocalContext.current
    val photos = rememberGuardedLauncher(ActivityResultContracts.PickMultipleVisualMedia(PickedMediaPlan.SELECTION_LIMIT)) { uris ->
        val items = uris.map { uri ->
            val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
            PickedMediaItem(
                if (isVideo) PickedMediaItem.Kind.Video else PickedMediaItem.Kind.Photo,
                PickedMediaItem.Source.Copy(CompletableDeferred(URI(uri.toString()))),
            )
        }
        if (items.isNotEmpty()) viewModel.sendPicked(items)
    }
    val document = rememberGuardedLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.send(ConversationAttachment.Document(URI(uri.toString())))
    }
    return { kind ->
        when (kind) {
            ConversationViewModel.AttachmentKind.Photo ->
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            ConversationViewModel.AttachmentKind.File -> document.launch(arrayOf("*/*"))
            else -> Unit
        }
    }
}

@Composable
private fun ConversationSheets(viewModel: ConversationViewModel) {
    val chatId = viewModel.chatId
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    U2Sheet(
        isPresented = viewModel.showsGhostSettings,
        onDismissRequest = { viewModel.showsGhostSettings = false },
        style = U2SheetStyle.Content,
        onDismissed = viewModel::commitGhostChange,
    ) {
        viewModel.chat?.let { chat ->
            GhostSettingsSheet(chat, onSave = viewModel::saveGhost, onClose = { viewModel.showsGhostSettings = false })
        }
    }
    // The composer's sticker key (proposed): the built-in stickers in a sheet that hugs its content; a sticker sent closes it.
    U2Sheet(isPresented = viewModel.showsStickerPicker, onDismissRequest = viewModel::closeStickerPicker, style = U2SheetStyle.Content) {
        StickerPickerSheet(
            onPick = { pick -> StickerCatalog.selection(pick.stickerId, pick.lang)?.let(viewModel::send) },
            onDismiss = viewModel::closeStickerPicker,
            recents = viewModel.stickerRecents,
        )
    }
    U2Sheet(item = viewModel.info, onDismissRequest = { viewModel.info = null }, style = U2SheetStyle.FullHeight) { item ->
        MessageInfoSheet(viewModel, item.id)
    }
    U2FadeCover(item = viewModel.cover, onEscape = viewModel::closeCover) { cover ->
        when (cover) {
            is ConversationViewModel.Cover.Photo -> MediaViewerCover(chatId, cover.messageId, onDismiss = viewModel::closeCover)
            is ConversationViewModel.Cover.Video -> VideoPlayerCover(chatId, cover.messageId, onDismiss = viewModel::closeCover)
            is ConversationViewModel.Cover.File -> FilePreviewCover(chatId, cover.messageId, onDismiss = viewModel::closeCover)
            is ConversationViewModel.Cover.ViewOnce -> ViewOnceCover(chatId, cover.messageId, onDismiss = viewModel::closeCover)
            is ConversationViewModel.Cover.LiveMap -> LiveLocationMapCover(chatId, cover.messageId, onDismiss = viewModel::closeCover)
        }
    }
    // 5.2 › konum (proposed): a place opens in a map app from the floating chooser.
    U2Sheet(item = viewModel.locationChoice, onDismissRequest = viewModel::closeLocationChoice, style = U2SheetStyle.Floating) { choice ->
        LocationOpenSheet(chatId, choice.id, onDismiss = viewModel::closeLocationChoice)
    }
    // “Herkesten sil” is irreversible: the app's soft floating confirmation, not a system alert.
    U2Sheet(
        isPresented = viewModel.deleteForEveryoneCandidate != null,
        onDismissRequest = { viewModel.deleteForEveryoneCandidate = null },
        style = U2SheetStyle.Floating,
    ) {
        DestructiveConfirmation(
            title = stringResource(R.string.conversation_confirm_deleteeveryone_title),
            message = stringResource(R.string.conversation_confirm_deleteeveryone_message),
            confirmTitle = stringResource(R.string.conversation_actions_deleteeveryone),
            onConfirm = viewModel::confirmDeleteForEveryone,
            onCancel = { viewModel.deleteForEveryoneCandidate = null },
            modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            confirmGlyph = PhosphorGlyph.Trash,
            tags = ConfirmationTags("conversation.deleteEveryone.confirm", "conversation.deleteEveryone.cancel"),
        )
    }
    // Android, “+” › Canlı konum without notifications: the share would stop as the app leaves.
    U2Sheet(
        isPresented = viewModel.showsLiveShareSheet,
        onDismissRequest = viewModel::closeLiveShareSheet,
        style = U2SheetStyle.Floating,
    ) {
        LiveShareSheet(
            onAllow = { viewModel.allowNoticeAndShareLiveLocation(openSettings = { openNotificationSettings(context, guard) }) },
            onShare = viewModel::shareLiveLocationInFront,
        )
    }
    // The microphone was refused: why, and the way to the settings.
    U2Sheet(
        isPresented = viewModel.voice.showsPermissionNotice,
        onDismissRequest = { viewModel.voice.showsPermissionNotice = false },
        style = U2SheetStyle.Floating,
    ) {
        VoicePermissionSheet(
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                guard.launching { runCatching { context.startActivity(intent) } }
            },
            onClose = { viewModel.voice.showsPermissionNotice = false },
        )
    }
}

/** “Dosyalara kaydet”: the system's save dialog, then the file's bytes into the place chosen. */
@Composable
private fun FileExport(viewModel: ConversationViewModel, context: Context) {
    var exporting by remember { mutableStateOf<MessageId?>(null) }
    val launcher = rememberGuardedLauncher(TypedCreateDocument()) { uri ->
        val id = exporting
        exporting = null
        if (uri == null || id == null) {
            viewModel.pendingExport = null
            return@rememberGuardedLauncher
        }
        viewModel.export(id) { payload ->
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.data.toByteArray()) } != null
            }.getOrDefault(false)
        }
    }
    val pending = viewModel.pendingExport
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        val name = (viewModel.message(id)?.content as? MessageContent.File)?.name ?: return@LaunchedEffect
        exporting = id
        viewModel.pendingExport = null
        launcher.launch(name)
    }
}

/** The save dialog with the file's own type (from its name). */
private class TypedCreateDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent {
        val extension = input.substringAfterLast('.', "").lowercase()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return super.createIntent(context, input).setType(type)
    }
}

