package com.syconx.us2us.feature.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.AvatarSearchCopy
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.DeviceProtection
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.PresenceVisibility
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.domain.nav.SettingsPicker
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.feature.settings.avatar.AvatarEditorScreen
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.common.MyCodeSheet
import com.syconx.us2us.ui.common.preparedOwnContactCode
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.DisclosureDetail
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedNavigationRow
import com.syconx.us2us.ui.components.GroupedSection
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.GroupedToggleRow
import com.syconx.us2us.ui.components.IconTile
import com.syconx.us2us.ui.components.IconTileContent
import com.syconx.us2us.ui.components.IconTileTone
import com.syconx.us2us.ui.components.SectionLabel
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.groupedRowPress
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.components.u2CardPress
import com.syconx.us2us.ui.env.DeviceBiometrics
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.env.windowSafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.L10n
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2FadeCover
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.sheet.U2Toast
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2OuterRing
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val presentationIds = AtomicLong()

/** One presentation of the own-code sheet: the code travels with it, so the sheet rises complete. */
@Immutable
internal data class OwnCodePresentation(val payload: String?, val id: Long = presentationIds.incrementAndGet())

/** One opening of “Profil görseli” ([harnessState]: `avatar` / `avatar-crop` from the debug harness). */
@Immutable
internal data class AvatarEditorPresentation(val harnessState: String? = null, val id: Long = presentationIds.incrementAndGet())

/**
 * 7.1 · Ayarlar: profile card and the C13 groups (Gizlilik, Güvenlik, Sohbetler, Veri ve görünüm,
 * exactly as drawn), then Uygulama (Hakkında) and Hesap (Her şeyi sil) below the fold. Scrolls.
 */
@Composable
internal fun SettingsRootView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val router = LocalRouter.current
    val screens = LocalFeatureScreens.current
    val haptics = LocalHaptics.current
    val harness = LocalHarnessState.current as? SettingsUiState
    val flow = rememberSettingsFlow()
    val messenger = flow.messenger
    val erasure = flow.erasure
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val settings by messenger.settings.collectAsStateWithLifecycle()
    val ownAvatar by messenger.ownAvatar.collectAsStateWithLifecycle()
    val account by messenger.account.collectAsStateWithLifecycle()
    val ownUsername by messenger.ownUsername.collectAsStateWithLifecycle()
    val accessShown by flow.restoreAccess.isShown.collectAsStateWithLifecycle()
    val blockedPeople by messenger.blockedPeople.collectAsStateWithLifecycle()
    val deviceProtection by messenger.deviceProtection.collectAsStateWithLifecycle()

    var ownCode by remember { mutableStateOf<OwnCodePresentation?>(null) }
    var showsInvite by remember { mutableStateOf(false) }
    // The Face ID switch while the system prompt decides (optimistic).
    var pendingFaceId by remember { mutableStateOf<Boolean?>(null) }
    // “Aramada göster” while the change is made (optimistic).
    var pendingAvatarSearch by remember { mutableStateOf<Boolean?>(null) }
    // 7.1 › Profil görseli: the full-screen picker; each opening is its own presentation.
    var avatarEditor by remember { mutableStateOf<AvatarEditorPresentation?>(null) }

    SideEffect { erasure.haptics = haptics }
    // The copy for search may have been written since (the footnote's “not on the server yet” line).
    LaunchedEffect(messenger) { messenger.refreshOwnAvatar() }

    val faceIdReason = stringResource(R.string.android_biometric_confirm_reason)
    // The switch is offered when the phone has an enrolled strong biometric (the
    // fixtures' knob wins), and stays while it is on so it can be turned off.
    val context = LocalContext.current
    val deviceBiometry = remember(context, messenger) { (messenger as? FixtureControls)?.biometry ?: DeviceBiometrics.enrolled(context) }
    val offersBiometrics = deviceBiometry != Biometry.None || settings.faceIdEnabled || pendingFaceId != null

    // The code is generated before the sheet rises, so it rises complete.
    fun openOwnCode() {
        scope.launch { ownCode = OwnCodePresentation(messenger.preparedOwnContactCode()) }
    }

    fun openAvatarEditor(harnessState: String? = null) {
        if (avatarEditor != null) return
        avatarEditor = AvatarEditorPresentation(harnessState)
    }

    // “Şimdi kontrol et” found the outcome: the notice has gone; the toast says which way. Still
    // unanswered: the notice says so itself.
    fun restoreAccessChecked(result: RestoreAccessCheck) {
        when (result) {
            RestoreAccessCheck.Closed -> flow.toast = R.string.settings_access_closed_toast
            RestoreAccessCheck.Refused -> flow.toast = R.string.settings_access_refused_toast
            RestoreAccessCheck.Unanswered -> Unit
        }
    }

    fun setReadReceipts(value: Boolean) {
        flow.scope.launch { messenger.updateSettings(messenger.settings.value.copy(readReceipts = value)) }
    }

    // Face ID: turning it on asks for biometrics first; the switch moves at once and glides back if
    // the prompt fails or the device has none.
    fun setFaceId(value: Boolean) {
        if (value && messenger.biometricUnlockNeedsCode) {
            // The biometric key wraps the vault key: the code once, on its own page; the switch
            // follows when it is on.
            flow.open(SettingsPage.EnableFaceId, router)
            return
        }
        pendingFaceId = value
        flow.scope.launch {
            val result = messenger.setBiometricUnlock(value, faceIdReason)
            pendingFaceId = null
            if (value && !result) flow.toast = R.string.android_biometric_unavailable
        }
    }

    // “Aramada göster”: the switch moves at once; the relay follows (off: the search copy is
    // deleted; on: published again).
    fun setAvatarSearch(value: Boolean) {
        pendingAvatarSearch = value
        flow.scope.launch {
            messenger.setAvatarShownInSearch(value)
            pendingAvatarSearch = null
        }
    }

    val endPaddingPx = with(LocalDensity.current) { windowSafeArea().bottom(U2Spacing.ctaBottom).roundToPx() }
    LaunchedEffect(harness) {
        val ui = harness ?: return@LaunchedEffect
        ui.take("7.1")?.let { state ->
            if (state.startsWith("7.1-access")) {
                flow.restoreAccess.applyHarness(
                    checking = state == "7.1-access-checking",
                    unanswered = state == "7.1-access-unanswered",
                )
            }
            if (state == "7.1-end") {
                launch {
                    delay(100)
                    // iOS scrolls the account section's bottom to the screen's bottom edge
                    // (`scrollTo("end", anchor: .bottom)`): the end without its bottom padding.
                    scroll.scrollTo((scroll.maxValue - endPaddingPx).coerceAtLeast(0))
                }
            }
        }
        // Sheets wait for the launch to settle.
        if (ui.take("qr") != null) {
            launch {
                delay(800)
                openOwnCode()
            }
        }
        ui.take("avatar")?.let { state ->
            launch {
                delay(800)
                openAvatarEditor(state)
            }
        }
        ui.take("erase")?.let { state ->
            launch {
                delay(800)
                erasure.present()
                if (state == "erase-failed") erasure.showFailureForHarness()
            }
        }
    }

    U2Screen(modifier.testTag(SettingsTags.ROOT)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .testTag(SettingsTags.SCROLL),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .u2Top(U2Spacing.headerTopButtons)
                    .padding(horizontal = U2Spacing.screenGutterS)
                    .height(U2Size.iconButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                U2GlassIconButton(
                    glyph = PhosphorGlyph.CaretLeft,
                    contentDescription = stringResource(R.string.common_a11y_back),
                    onClick = router::pop,
                    modifier = Modifier.testTag(SettingsTags.BACK),
                )
            }
            // The reference draws the content 0.5 dp lower than the 38 dp line box suggests (r.1 is 50.25 high).
            Box(
                Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.TITLE)
                    .padding(start = U2Spacing.screenGutter, top = 12.dp, end = U2Spacing.screenGutter, bottom = 0.5.dp),
            ) {
                U2DisplayText(R.string.settings_title, U2TextStyles.displayTitle)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                    .u2Bottom(U2Spacing.ctaBottom),
                verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionGap),
            ) {
                RestoreAccessSlot(accessShown) {
                    // A restore's token rotation is unanswered: the old phone may still have access (§5.5 step 3.4).
                    RestoreAccessNotice(flow.restoreAccess, onResult = ::restoreAccessChecked)
                }
                ProfileCard(
                    username = account?.username ?: DesignFixtures.OWN_USERNAME,
                    picture = ownAvatar.picture,
                    onEditAvatar = { openAvatarEditor() },
                    onOwnCode = ::openOwnCode,
                )
                Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                    SectionLabel(stringResource(R.string.settings_section_privacy), Modifier.testTag(SettingsTags.PRIVACY_LABEL))
                    GroupedCard(Modifier.testTag(SettingsTags.PRIVACY_CARD)) {
                        PickerRow(
                            PhosphorGlyph.CircleHalf,
                            stringResource(R.string.settings_presence),
                            presenceText(settings.presenceVisibility),
                            SettingsPicker.Presence,
                        )
                        GroupedSeparator(Modifier.testTag(SettingsTags.PRIVACY_SEPARATOR))
                        GroupedToggleRow(
                            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Checks)) },
                            title = stringResource(R.string.settings_readreceipts),
                            checked = settings.readReceipts,
                            onCheckedChange = ::setReadReceipts,
                            modifier = Modifier.testTag(SettingsTags.READ_RECEIPTS),
                        )
                        GroupedSeparator()
                        // Proposed: everyone blocked, also those whose chat is gone.
                        GroupedNavigationRow(
                            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Prohibit)) },
                            title = stringResource(R.string.settings_blocked),
                            detail = blockedCount(blockedPeople.size),
                            onClick = { flow.open(SettingsPage.Blocked, router) },
                            modifier = Modifier.testTag(SettingsTags.BLOCKED),
                        )
                        GroupedSeparator()
                        // Proposed (PROTOCOL §8.10): who sees the profile picture.
                        GroupedToggleRow(
                            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Eye)) },
                            title = stringResource(R.string.settings_avatarsearch),
                            checked = pendingAvatarSearch ?: ownAvatar.showInSearch,
                            onCheckedChange = ::setAvatarSearch,
                            modifier = Modifier.testTag(SettingsTags.AVATAR_SEARCH),
                        )
                    }
                    AvatarSearchFootnote(ownAvatar.searchCopy, ownAvatar.isShownInSearch)
                }
                GroupedSection(stringResource(R.string.settings_section_security)) {
                    if (offersBiometrics) {
                        GroupedToggleRow(
                            tile = { IconTile(IconTileContent.FaceId) },
                            title = stringResource(R.string.android_biometric_unlock),
                            checked = pendingFaceId ?: settings.faceIdEnabled,
                            onCheckedChange = ::setFaceId,
                            modifier = Modifier.testTag(SettingsTags.FACE_ID),
                        )
                        GroupedSeparator()
                    }
                    PickerRow(
                        PhosphorGlyph.LockSimple,
                        stringResource(R.string.settings_autolock),
                        stringResource(R.string.settings_autolock_immediately),
                        SettingsPicker.AutoLock,
                    )
                    GroupedSeparator()
                    GroupedNavigationRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Password)) },
                        title = stringResource(R.string.settings_changecode),
                        onClick = { flow.open(SettingsPage.ChangeCode, router) },
                        modifier = Modifier.testTag(SettingsTags.CHANGE_CODE),
                    )
                }
                DeviceProtectionFootnote(deviceProtection)
                GroupedSection(stringResource(R.string.settings_section_chats)) {
                    PickerRow(
                        PhosphorGlyph.Ghost,
                        stringResource(R.string.settings_defaultghost),
                        ghostText(settings.defaultGhost),
                        SettingsPicker.DefaultGhost,
                    )
                    GroupedSeparator()
                    PickerRow(
                        PhosphorGlyph.Timer,
                        stringResource(R.string.settings_defaultdisappearing),
                        stringResource(settings.defaultDisappearing.settingsLabel()),
                        SettingsPicker.DefaultDisappearing,
                    )
                }
                GroupedSection(stringResource(R.string.settings_section_data)) {
                    GroupedNavigationRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.FileLock)) },
                        title = stringResource(R.string.settings_backup),
                        detail = backupText(settings.lastBackupAt),
                        onClick = { flow.open(SettingsPage.BackupExport, router) },
                        modifier = Modifier.testTag(SettingsTags.BACKUP),
                    )
                    GroupedSeparator()
                    PickerRow(
                        PhosphorGlyph.Moon,
                        stringResource(R.string.settings_theme),
                        stringResource(app.preferences.theme.settingsLabel()),
                        SettingsPicker.Theme,
                    )
                    GroupedSeparator()
                    PickerRow(
                        PhosphorGlyph.Translate,
                        stringResource(R.string.settings_language),
                        languageName(app.preferences.language),
                        SettingsPicker.Language,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                    GroupedCard {
                        GroupedToggleRow(
                            tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Eye)) },
                            title = stringResource(R.string.settings_keepscreenon),
                            checked = app.preferences.keepScreenOn,
                            onCheckedChange = { app.preferences.keepScreenOn = it },
                            modifier = Modifier.testTag("settings.keepScreenOn"),
                        )
                    }
                    U2Text(
                        stringResource(R.string.settings_keepscreenon_footnote),
                        U2TextStyles.footnote,
                        color = U2Theme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                GroupedSection(stringResource(R.string.settings_section_app)) {
                    InviteSettingsRow { showsInvite = true }
                    GroupedSeparator()
                    GroupedNavigationRow(
                        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.Info)) },
                        title = stringResource(R.string.settings_about),
                        detail = AboutVersion.of(context).name,
                        onClick = { flow.open(SettingsPage.About, router) },
                        modifier = Modifier.testTag(SettingsTags.ABOUT),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
                    SectionLabel(stringResource(R.string.settings_section_account))
                    GroupedCard { EraseRow(onClick = erasure::present) }
                    U2Text(
                        stringResource(R.string.settings_erase_footnote),
                        U2TextStyles.footnote,
                        color = U2Theme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        SettingsStatusBarFade()
        U2Toast(message = flow.toast?.let { stringResource(it) }, onDismiss = { flow.toast = null })
    }

    U2Sheet(item = ownCode, onDismissRequest = { ownCode = null }) { presentation ->
        MyCodeSheet(username = ownUsername, payload = presentation.payload, onClose = { ownCode = null })
    }
    U2Sheet(isPresented = showsInvite, onDismissRequest = { showsInvite = false }) {
        screens.Invite(state = null, onClose = { showsInvite = false }, modifier = Modifier)
    }
    U2FadeCover(item = avatarEditor, onEscape = { avatarEditor = null }) { presentation ->
        AvatarEditorScreen(onClose = { avatarEditor = null }, harnessState = presentation.harnessState)
    }
    U2Sheet(
        isPresented = erasure.isPresented,
        onDismissRequest = { if (!erasure.isWorking) erasure.isPresented = false },
        style = U2SheetStyle.Floating,
        isDismissible = !erasure.isWorking,
        // The erase sheet is completely gone: if the account was deleted on the relay (or the user
        // chose this phone only), wipe now (never under a sheet that is still on screen) and
        // cross-fade to 1.1.
        onDismissed = { flow.scope.launch { erasure.sheetDidDismiss { app.eraseEverything() } } },
    ) {
        EraseAccountSheet(erasure, flow.scope)
    }
}

/** The notice's place at the top of 7.1: it rises in and collapses away. */
@Composable
private fun RestoreAccessSlot(isShown: Boolean, content: @Composable () -> Unit) {
    val state = U2Motion.resolve(U2Motion.state, LocalReduceMotion.current)
    val rise = U2Transitions.rise()
    AnimatedVisibility(
        visible = isShown,
        enter = rise.enter + expandVertically(state.spec()),
        exit = rise.exit + shrinkVertically(state.spec()),
    ) {
        Box(Modifier.then(u2TransitionBlur(rise))) { content() }
    }
}

// MARK: - Pieces

@Composable
private fun ProfileCard(
    username: String,
    picture: AvatarPicture,
    onEditAvatar: () -> Unit,
    onOwnCode: () -> Unit,
) {
    val colors = U2Theme.colors
    val editLabel = stringResource(R.string.settings_avatar_edit)
    val description = avatarDescription(picture)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(U2Radius.card))
            .testTag(SettingsTags.PROFILE)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Proposed: the avatar opens “Profil görseli” (a camera badge says so).
        Box(
            Modifier
                .testTag(SettingsTags.AVATAR)
                .u2CardPress(onEditAvatar)
                .clearAndSetSemantics {
                    contentDescription = editLabel
                    stateDescription = description
                    role = Role.Button
                    onClick { onEditAvatar(); true }
                },
        ) {
            Avatar(username = username, palette = AvatarPalette.D, size = AvatarSize.Settings, picture = picture)
            AvatarEditBadge(Modifier.align(Alignment.BottomEnd))
        }
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            U2Text(
                "@$username",
                U2TextStyles.monoUsernameL,
                color = colors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            U2Text(
                stringResource(R.string.settings_profile_caption),
                U2TextStyles.caption,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        U2GlassIconButton(
            glyph = PhosphorGlyph.QrCode,
            contentDescription = stringResource(R.string.settings_a11y_ownqr),
            onClick = onOwnCode,
            diameter = U2Size.iconButtonChat,
            glyphSize = 20.dp,
            tint = colors.accent300,
            modifier = Modifier.testTag(SettingsTags.QR),
        )
    }
}

/** A 20 dp `accent` disc with `ph:camera`, ringed in `surface`, at the avatar's lower right. */
@Composable
private fun AvatarEditBadge(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Box(
        modifier
            .offset(x = 3.dp, y = 3.dp)
            .size(20.dp)
            .u2OuterRing(colors.surface, 2.dp, CircleShape)
            .background(colors.accent, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(PhosphorGlyph.Camera, size = 11.dp, tint = colors.accent900)
    }
}

/** Under Gizlilik: what “Aramada göster” means (PROTOCOL §8.10), and a change that has not reached the relay yet. */
@Composable
private fun AvatarSearchFootnote(copy: AvatarSearchCopy, isShownInSearch: Boolean) {
    val colors = U2Theme.colors
    val fade = U2Transitions.fade()
    val note = when {
        copy == AvatarSearchCopy.Pending -> R.string.settings_avatarsearch_pending
        copy == AvatarSearchCopy.Unavailable && isShownInSearch -> R.string.settings_avatarsearch_unavailable
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(SettingsTags.AVATAR_SEARCH_FOOTNOTE),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        U2Text(stringResource(R.string.settings_avatarsearch_footnote), U2TextStyles.footnote, color = colors.textSecondary)
        var shown by remember { mutableStateOf(note) }
        if (note != null) shown = note
        AnimatedVisibility(note != null, enter = fade.enter, exit = fade.exit) {
            shown?.let { U2Text(stringResource(it), U2TextStyles.footnote, color = colors.textSecondary, modifier = Modifier.then(u2TransitionBlur(fade))) }
        }
    }
}

@Composable
private fun EraseRow(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .groupedRowPress(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(SettingsTags.ERASE)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(IconTileContent.Glyph(PhosphorGlyph.Trash), tone = IconTileTone.Danger)
        U2Text(
            stringResource(R.string.settings_erase),
            U2TextStyles.rowTitle,
            color = U2Theme.colors.danger,
            modifier = Modifier.weight(1f),
        )
        DisclosureDetail()
    }
}

@Composable
private fun PickerRow(glyph: PhosphorGlyph, title: String, detail: String, picker: SettingsPicker) {
    val router = LocalRouter.current
    GroupedNavigationRow(
        tile = { IconTile(IconTileContent.Glyph(glyph)) },
        title = title,
        detail = detail,
        onClick = { router.push(AppRoute.Picker(picker)) },
        modifier = Modifier.testTag(SettingsTags.picker(picker.raw)),
    )
}

/** The 7.1 row that opens the invitation (Uygulama › “Arkadaşını davet et”). */
@Composable
private fun InviteSettingsRow(onClick: () -> Unit) {
    // iOS reads a hint here (“Prepares an invitation”); Compose has no hints, so the click action carries it.
    val hint = stringResource(R.string.invite_a11y_invite_entry)
    GroupedNavigationRow(
        tile = { IconTile(IconTileContent.Glyph(PhosphorGlyph.PaperPlaneTilt)) },
        title = stringResource(R.string.invite_entry_settings),
        onClick = onClick,
        modifier = Modifier
            .semantics { onClick(label = hint) { onClick(); true } }
            .testTag(SettingsTags.INVITE),
    )
}

// MARK: - Texts

/** What the profile card's avatar shows, for accessibility: the preset's name, “Fotoğraf” or “Baş harfin”. */
@Composable
private fun avatarDescription(picture: AvatarPicture): String {
    if (picture.isPhoto) return stringResource(R.string.settings_avatar_current_photo)
    val preset = picture.presetId?.let(AvatarPresetCatalog::preset)
    if (preset != null) {
        val language = if (L10n.isTurkish(currentAppLocale())) StickerLanguage.Turkish else StickerLanguage.English
        return preset.label[language]
    }
    return stringResource(R.string.settings_avatar_current_initials)
}

@Composable
private fun presenceText(value: PresenceVisibility): String =
    stringResource(if (value == PresenceVisibility.AcceptedContacts) R.string.settings_presence_accepted else R.string.settings_presence_nobody)

@Composable
private fun ghostText(value: GhostPeriod?): String = stringResource(value?.longLabel() ?: R.string.common_disappearing_off)

/** How many are blocked; nothing when nobody is. */
@Composable
private fun blockedCount(count: Int): String? = if (count > 0) U2Format.integer(count, currentAppLocale()) else null

@Composable
private fun backupText(date: java.time.Instant?): String {
    date ?: return stringResource(R.string.settings_backup_never)
    return stringResource(R.string.settings_backup_last, U2Format.dayMonth(date, currentAppLocale()))
}

/** The app's version (“1.0.0”) and build number, from the installed package. */
internal data class AboutVersion(val name: String, val build: String) {
    companion object {
        private const val UNKNOWN = "—"

        fun of(context: Context): AboutVersion {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            return AboutVersion(name = info?.versionName ?: UNKNOWN, build = info?.longVersionCode?.toString() ?: UNKNOWN)
        }
    }
}

/**
 * Under 7.1 › Güvenlik: what the phone's secure hardware adds to the secret code (PROTOCOL §5.2,
 * audit 2026-10-28 M-6), said plainly when it is less than full; nothing when it is full.
 */
@Composable
private fun DeviceProtectionFootnote(protection: DeviceProtection) {
    val text = when (protection) {
        DeviceProtection.Full -> return
        DeviceProtection.Limited -> R.string.common_security_deviceprotection_limited
        DeviceProtection.Basic -> R.string.common_security_deviceprotection_basic
    }
    U2Text(
        stringResource(text),
        U2TextStyles.footnote,
        color = if (protection == DeviceProtection.Basic) U2Theme.colors.danger else U2Theme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(SettingsTags.DEVICE_PROTECTION),
    )
}
