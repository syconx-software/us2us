package com.syconx.us2us.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.SafetyQRCodec
import com.syconx.us2us.domain.design.DesignAvatars
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.feature.chats.ChatListRow
import com.syconx.us2us.feature.chats.ConnectionStatusLine
import com.syconx.us2us.feature.chats.RequestsCard
import com.syconx.us2us.feature.chats.RestoreAccessCard
import com.syconx.us2us.feature.conversation.ConversationGalleryParts
import com.syconx.us2us.feature.conversation.ConversationRows
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.brand.BrandAvatarGallery
import com.syconx.us2us.ui.brand.BrandStickerGallery
import com.syconx.us2us.ui.bubbles.MessageBubbleView
import com.syconx.us2us.ui.bubbles.MessageStatusLegendRow
import com.syconx.us2us.ui.bubbles.MessageStatusSentence
import com.syconx.us2us.ui.bubbles.chipLabelRes
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.CodeDots
import com.syconx.us2us.ui.components.CodeDotsAppearance
import com.syconx.us2us.ui.components.ConnectionLine
import com.syconx.us2us.ui.components.CountBadge
import com.syconx.us2us.ui.components.GhostChip
import com.syconx.us2us.ui.components.GhostPill
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedSection
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.InfoBanner
import com.syconx.us2us.ui.components.KeypadBottomLeading
import com.syconx.us2us.ui.components.LineMark
import com.syconx.us2us.ui.components.NoteCard
import com.syconx.us2us.ui.components.NumericKeypad
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.PageDots
import com.syconx.us2us.ui.components.PasswordField
import com.syconx.us2us.ui.components.QrCodeCard
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.SegmentedChips
import com.syconx.us2us.ui.components.SystemPill
import com.syconx.us2us.ui.components.U2Checkbox
import com.syconx.us2us.ui.components.U2DecisionButton
import com.syconx.us2us.ui.components.U2DecisionKind
import com.syconx.us2us.ui.components.U2FlexRow
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2SecondaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2Toggle
import com.syconx.us2us.ui.components.UsernameField
import com.syconx.us2us.ui.components.UsernameFieldStatus
import com.syconx.us2us.ui.components.UsernameSearchField
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.components.u2Flex
import com.syconx.us2us.ui.composer.Composer
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.media.MediaGlassCircle
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.media.MediaProgressRing
import com.syconx.us2us.ui.media.MediaStateGlyph
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2Toast
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.time.Duration.Companion.seconds

/** Test tags of the debug galleries' frame. */
object GalleryTags {
    const val COMPONENTS = "gallery.components"
    const val CLOSE = "gallery.close"
}

/**
 * Every shared component on one scrolling page, with the design's data (launch with `U2Gallery
 * YES`): the iOS `ComponentGallery`, for eyeballing against the references in both themes and
 * languages. A developer page: its labels are verbatim, as on iOS.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    val clock = LocalU2Clock.current
    var toggle by remember { mutableStateOf(true) }
    var checkbox by remember { mutableStateOf(true) }
    var chip by remember { mutableStateOf(GhostPeriod.OneDay) }
    var username by remember { mutableStateOf("selin_n") }
    var search by remember { mutableStateOf("deniz") }
    var password by remember { mutableStateOf("correct horse battery") }
    var draft by remember { mutableStateOf("") }
    var code by remember { mutableIntStateOf(4) }
    var showsSheet by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showsStickers by remember { mutableStateOf(false) }
    var showsAvatars by remember { mutableStateOf(false) }
    val chats = remember(clock) { DesignFixtures.chats(clock.now, ghostUrgent = false, english = false) }

    U2Screen(modifier.testTag(GalleryTags.COMPONENTS)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 70.dp, end = 16.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Group("Typography") {
                U2DisplayText("İki uç. Bir hat.", U2TextStyles.displayHero)
                U2DisplayText("Kullanıcı adını seç", U2TextStyles.displayTitleM)
                U2Text("Sohbetler", U2TextStyles.displayTitle)
                U2Text("23:41:07", U2TextStyles.displayCountdownL, color = U2Theme.colors.accent200)
                U2Text("us2us iki kişi arasında şifreli bir hat kurar.", U2TextStyles.bodyL, color = U2Theme.colors.textSecondary)
                U2Text("deniz · mert_k · 1 / 3", U2TextStyles.monoUsername)
                SectionLabel("Gizlilik · istekler", horizontalPadding = 0.dp)
            }
            Group("Buttons") {
                U2PrimaryButton(title = "Başla", onClick = {})
                U2SecondaryButton(title = "Kodla aç", onClick = {}, glyph = PhosphorGlyph.Password)
                U2TextButton(title = "Yedekten geri yükle", onClick = {})
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    U2GlassIconButton(PhosphorGlyph.QrCode, contentDescription = "QR", onClick = {})
                    U2GlassIconButton(PhosphorGlyph.NotePencil, contentDescription = "Yeni", onClick = {}, tint = U2Theme.colors.accent300)
                    CountBadge(count = 2)
                }
                U2FlexRow(spacing = 8.dp) {
                    U2DecisionButton(U2DecisionKind.Destructive, title = "Engelle", onClick = {})
                    U2DecisionButton(U2DecisionKind.Neutral, title = "Sil", onClick = {})
                    U2DecisionButton(U2DecisionKind.Accept, title = "Kabul et", onClick = {}, modifier = Modifier.u2Flex(1.3f, border = 2.dp))
                }
            }
            Group("Code entry") {
                CodeDots(filled = code, modifier = Modifier.align(Alignment.CenterHorizontally))
                CodeDots(filled = 6, appearance = CodeDotsAppearance.Error, modifier = Modifier.align(Alignment.CenterHorizontally))
                PageDots(count = 3, current = code % 3)
                NumericKeypad(
                    onDigit = { code = minOf(6, code + 1) },
                    onDelete = { code = maxOf(0, code - 1) },
                    bottomLeading = KeypadBottomLeading.FaceId {},
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Group("Controls") {
                U2Toggle(checked = toggle, onCheckedChange = { toggle = it }) {
                    U2Text("Ghost mod", U2TextStyles.rowTitleEmphasis)
                }
                U2Checkbox(checked = checkbox, onCheckedChange = { checkbox = it }) {
                    U2Text("Anladım: kodu unutursam verilerim kalıcı olarak silinir.", U2TextStyles.calloutL)
                }
                SegmentedChips(options = GhostPeriod.entries, selection = chip, onSelect = { chip = it }) { stringResource(it.chipLabelRes) }
            }
            Group("Fields") {
                UsernameField(text = username, onTextChange = { username = it }, status = UsernameFieldStatus.Positive)
                UsernameSearchField(text = search, onTextChange = { search = it })
                PasswordField(text = password, onTextChange = { password = it })
            }
            Group("Rows & cards") {
                RequestsCard(count = 2, onClick = {})
                // 4.1 after a restore whose token rotation is unanswered (proposed).
                RestoreAccessCard(onClick = {})
                chats.take(4).forEach { ChatListRow(it) }
                InfoBanner("Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler.")
                OptionCard(
                    glyph = PhosphorGlyph.FileLock,
                    title = "Yedekten geri yükle",
                    description = "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.",
                    onClick = {},
                )
                NoteCard(title = "İletilemedi ne demek?", message = "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir.")
                GroupedSection("Güvenlik") {
                    GroupedToggleRow({ IconTile(IconTileContent.FaceId) }, "Biyometrik ile aç", checked = toggle, onCheckedChange = { toggle = it })
                    GroupedSeparator()
                    GroupedNavigationRow({ IconTile(IconTileContent.Glyph(PhosphorGlyph.LockSimple)) }, "Otomatik kilit", onClick = {}, detail = "Hemen")
                }
            }
            Group("Ghost & status") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostPill(chats[0].ghost)
                    GhostPill(chats[2].ghost)
                    GhostChip(chats[0].ghost, onClick = {})
                }
                listOf(MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read, MessageStatus.Failed).forEach { MessageStatusLegendRow(it) }
                MessageStatusSentence(MessageStatus.Sent)
                SystemPill("Ghost mod açık · 24 saat")
                // 5.1 verification (proposed): the prompt's look and the held-send banner.
                SystemPill(stringResource(R.string.conversation_verify_prompt), Modifier.align(Alignment.CenterHorizontally), glyph = PhosphorGlyph.QrCode)
                ConversationGalleryParts.SafetyBanner()
            }
            Group("Bubbles") {
                val messages = remember { DesignFixtures.denizMessages(english = false) + DesignFixtures.mertMessages(english = false) }
                messages.forEachIndexed { index, message ->
                    Bubble(message, isTail = ConversationRows.isTail(index, messages))
                }
            }
            Group("Stickers (proposed)") {
                // Received: a known sticker in its sender's language, an older sender without one, a newer pack, nothing to draw.
                val stickers = listOf(
                    MessageContent.Sticker("us2us.words", 103, "🫂", "en"),
                    MessageContent.Sticker("us2us.words", 109, "👌", ""),
                    MessageContent.Sticker("us2us.future", 9001, "🫶", ""),
                    MessageContent.Sticker("us2us.future", 9002, "", ""),
                )
                stickers.forEachIndexed { index, content ->
                    Bubble(Message(MessageId("gallery.sticker.$index"), MessageDirection.Incoming, DesignFixtures.referenceDate, content), isTail = true)
                }
                U2SecondaryButton(title = "Sticker gallery", onClick = { showsStickers = true })
            }
            Group("Avatars (proposed)") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar("deniz", AvatarPalette.A)
                    Avatar("kaan", AvatarPalette.D, picture = AvatarPicture.preset(2))
                    Avatar("oya_t", AvatarPalette.C, showsOnlineDot = true, picture = DesignAvatars.photoAvatar)
                    Box {
                        Avatar("lale", AvatarPalette.B, picture = AvatarPicture.preset(14))
                        MediaProgressRing(
                            fraction = null,
                            modifier = Modifier.size(AvatarSize.ListRow.diameter),
                            tint = U2Theme.colors.accent300,
                            track = U2Theme.colors.accent300.copy(alpha = 0.2f),
                        )
                    }
                }
                U2SecondaryButton(title = "Avatar gallery", onClick = { showsAvatars = true })
            }
            Group("Media states (proposed)") {
                Row(
                    Modifier
                        .background(U2Theme.colors.mediaViewerBg, RoundedCornerShape(U2Radius.bubbleMedia))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(MediaAvailability.Ready, MediaAvailability.Remote, MediaAvailability.Downloading(null), MediaAvailability.Downloading(0.4), MediaAvailability.Unavailable)
                        .forEach { state -> MediaGlassCircle { MediaStateGlyph(state) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MediaGlyph.entries.forEach { MediaGlyphIcon(it, size = 21.dp) }
                }
                listOf(MediaAvailability.Remote, MediaAvailability.Downloading(0.62), MediaAvailability.Unavailable).forEach { Bubble(fileMessage(it)) }
                listOf(MediaAvailability.Preparing, MediaAvailability.PreparationFailed).forEach { Bubble(outgoingVideo(it)) }
                listOf(MediaAvailability.Preparing, MediaAvailability.PreparationFailed).forEach { Bubble(outgoingPhoto(it)) }
            }
            Group("Line") {
                LineMark()
                ConnectionStatusLine(ConnectionState.Open)
                ConnectionStatusLine(ConnectionState.Revoked)
                ConnectionLine(ownUsername = "selin_n", ownLabel = "sen", peer = DesignFixtures.deniz, showsSeal = true)
                QrCodeCard(
                    payload = SafetyQRCodec.encode(Bytes.wrap("0822378626740242326312493".encodeToByteArray())),
                    label = "QR",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Group("Composer & sheet") {
                Composer(text = draft, onTextChange = { draft = it }, onSend = { draft = "" }, bottomPadding = 0.dp)
                U2SecondaryButton(title = "Sheet", onClick = { showsSheet = true })
                U2SecondaryButton(title = "Toast", onClick = { toast = "Kaydedildi" })
            }
        }
        U2Toast(message = toast, onDismiss = { toast = null })
    }
    U2Sheet(isPresented = showsSheet, onDismissRequest = { showsSheet = false }) {
        ConversationGalleryParts.GhostSheet(chats[0], onClose = { showsSheet = false })
    }
    if (showsStickers) {
        GalleryOverlay(onClose = { showsStickers = false }) { BrandStickerGallery() }
    } else if (showsAvatars) {
        GalleryOverlay(onClose = { showsAvatars = false }) { BrandAvatarGallery() }
    }
}

/** A brand gallery over the component gallery, with the close button at the top trailing corner. */
@Composable
private fun GalleryOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        U2GlassIconButton(
            PhosphorGlyph.X,
            contentDescription = stringResource(R.string.common_a11y_close),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 54.dp, end = 16.dp)
                .testTag(GalleryTags.CLOSE),
        )
    }
}

@Composable
private fun ColumnScope.Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        U2Text(title, U2TextStyles.monoKicker, color = U2Theme.colors.accent300)
        content()
    }
}

@Composable
private fun Bubble(message: Message, isTail: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        MessageBubbleView(message, isTail = isTail)
    }
}

/** The 5.2 file bubble in another state. */
private fun fileMessage(state: MediaAvailability): Message =
    DesignFixtures.mertMessages(english = false).first { it.id == MessageId("m1") }.copy(mediaAvailability = state)

/** A photo of a pick from “+” while it is prepared, or after preparing it failed. */
private fun outgoingPhoto(state: MediaAvailability): Message = Message(
    id = MessageId("gallery.photo.$state"),
    direction = MessageDirection.Outgoing,
    sentAt = DesignFixtures.referenceDate,
    content = MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption = null),
    status = if (state == MediaAvailability.PreparationFailed) MessageStatus.Failed else MessageStatus.Sending,
    mediaAvailability = state,
)

/** A video sent from “+” while it is prepared, or after preparing it failed. */
private fun outgoingVideo(state: MediaAvailability): Message = Message(
    id = MessageId("gallery.video.$state"),
    direction = MessageDirection.Outgoing,
    sentAt = DesignFixtures.referenceDate,
    content = MessageContent.Video(MediaSource.Artwork(DesignArtwork.SunsetVideo), duration = 18.seconds, byteCount = 0),
    status = if (state == MediaAvailability.PreparationFailed) MessageStatus.Failed else MessageStatus.Sending,
    mediaAvailability = state,
)
