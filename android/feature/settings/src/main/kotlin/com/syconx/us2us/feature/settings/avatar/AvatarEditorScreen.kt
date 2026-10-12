package com.syconx.us2us.feature.settings.avatar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.brand.AvatarPickerTags
import com.syconx.us2us.ui.brand.AvatarPresetPicker
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.sheet.U2FadeCover
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of “Profil görseli”. */
object AvatarEditorTags {
    const val SCREEN = "avatar.editor"
    const val CLOSE = AvatarPickerTags.CLOSE
}

/**
 * 7.1 › Profil görseli (proposed): the full-screen picker ([AvatarPresetPicker]) in a fade cover
 * over 7.1, and the framing screen ([AvatarCropView]) that fades in over it, also full screen, for a
 * picked or taken photo. The system photo picker and the camera open over this cover, and the
 * framing screen only once they have gone. The initial or a preset is saved on tap; once a change
 * is saved the page fades out by itself (after the framing screen has), over a 7.1 that already
 * shows the new picture. Presented by 7.1 (its avatar), which also closes it.
 *
 * [harnessState] is the debug harness state that opened it (`avatar`, or `avatar-crop` for the
 * framing screen with the night-pier painting); `null` in production. [sources] replaces the
 * system photo picker and camera (tests).
 */
@Composable
fun AvatarEditorScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    harnessState: String? = null,
    sources: AvatarPhotoSourcing? = null,
) {
    val messenger = LocalMessenger.current
    val system = rememberSystemAvatarPhotoSources()
    val model = remember(messenger) { AvatarEditorModel(messenger, sources ?: system) }
    val own by messenger.ownAvatar.collectAsStateWithLifecycle()
    val username by messenger.ownUsername.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    var closing by remember { mutableStateOf(false) }

    // The moved mark (and the framing screen's fade) are seen first, then the page goes.
    LaunchedEffect(model.savedChanges) {
        if (model.savedChanges == 0 || closing) return@LaunchedEffect
        closing = true
        delay((U2Motion.Duration.state * 1_000).toLong() + 120)
        currentOnClose()
    }
    LaunchedEffect(harnessState) {
        if (harnessState != "avatar-crop") return@LaunchedEffect
        delay(700)
        model.framing = AvatarEditorModel.harnessSource()
    }
    DisposableEffect(model) { onDispose { model.toast = null } }

    AvatarPresetPicker(
        username = username,
        current = own.picture.choice,
        onChoose = { choice -> scope.launch { model.choose(choice) } },
        onUploadPhoto = { scope.launch { model.pickPhoto() } },
        onTakePhoto = { scope.launch { model.takePhoto() } },
        modifier = modifier.testTag(AvatarEditorTags.SCREEN),
        onClose = onClose,
        footnote = if (own.showInSearch) stringResource(R.string.settings_avatar_search_note) else null,
        alert = if (model.framing == null) model.toast?.let { stringResource(it) } else null,
        enabled = !model.isBusy && !closing,
    )
    U2FadeCover(item = model.framing, onEscape = model::cancelFraming) { source ->
        AvatarCropView(model, source, notesSearch = own.showInSearch)
    }
}
