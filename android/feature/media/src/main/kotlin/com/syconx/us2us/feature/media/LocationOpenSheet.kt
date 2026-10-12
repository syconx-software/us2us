package com.syconx.us2us.feature.media

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.OptionCard
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import com.syconx.us2us.feature.media.R as MediaR

/** Test tags of the chooser. */
object LocationOpenTags {
    const val DEVICE = "location.open.apple"
    const val GOOGLE = "location.open.google"
    const val CANCEL = "location.open.cancel"
    const val HANDED_OVER = "location.open.handedOver"
}

/**
 * 5.2 › “Haritada aç” (proposed: the design notes “location → map sheet” without drawing it): a tap
 * on a place bubble — or “Haritada aç” on the live-location map — opens this floating sheet in
 * 3.3's language.
 *
 * Text block (pad 6 4 0 4, gap 10): the Doto sheet title, the privacy hint (`sheetBody`,
 * `text·68%`: the location is handed to the chosen app; for a disappearing message also that the app
 * may keep it in its own history) and, in `monoFileMeta`, exactly what would be handed over. Two
 * option cards (gap 10): a maps app on this device (`ph:map-pin` fill; OpenStreetMap in the browser
 * without one) and Google Maps (`ph:navigation-arrow`; the app when it is installed, otherwise
 * google.com/maps in the browser). C3 “Vazgeç”. The host sheet floats; the padding is 18 18 28 18.
 */
@Composable
fun LocationOpenContent(choice: MapChoice, onChoose: (MapApp) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            U2DisplayText(stringResource(R.string.conversation_location_open_title), U2TextStyles.displaySheetTitle.balanced, isHeader = true)
            U2Text(stringResource(R.string.conversation_location_open_body), U2TextStyles.sheetBody, color = colors.textSecondary)
            if (choice.isDisappearing) {
                U2Text(stringResource(R.string.conversation_location_open_disappearing), U2TextStyles.sheetBody, color = colors.textSecondary)
            }
            val title = choice.place.name.trim().ifEmpty { stringResource(R.string.common_media_location) }
            U2Text(
                title + " · " + MapLinks.displayCoordinates(choice.place),
                U2TextStyles.monoFileMeta,
                color = colors.textSecondary,
                maxLines = 2,
                modifier = Modifier.testTag(LocationOpenTags.HANDED_OVER),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (choice.hasMapsApp) {
                OptionCard(
                    glyph = PhosphorGlyph.MapPin,
                    glyphWeight = PhosphorWeight.Fill,
                    title = stringResource(MediaR.string.media_android_location_open_maps),
                    description = stringResource(MediaR.string.media_android_location_open_maps_detail),
                    onClick = { onChoose(MapApp.Device) },
                    modifier = Modifier.testTag(LocationOpenTags.DEVICE),
                )
            } else {
                OptionCard(
                    glyph = PhosphorGlyph.MapPin,
                    glyphWeight = PhosphorWeight.Fill,
                    title = stringResource(MediaR.string.media_android_location_open_osm),
                    description = stringResource(MediaR.string.media_android_location_open_osm_detail),
                    onClick = { onChoose(MapApp.Device) },
                    modifier = Modifier.testTag(LocationOpenTags.DEVICE),
                )
            }
            OptionCard(
                glyph = PhosphorGlyph.NavigationArrow,
                title = stringResource(R.string.conversation_location_open_google),
                description = stringResource(
                    if (choice.hasGoogleMapsApp) R.string.conversation_location_open_google_detail_app else R.string.conversation_location_open_google_detail_web,
                ),
                onClick = { onChoose(MapApp.Google) },
                modifier = Modifier.testTag(LocationOpenTags.GOOGLE),
            )
        }
        U2TextButton(
            title = stringResource(R.string.common_cancel),
            onClick = onCancel,
            tone = U2TextButtonTone.Accent,
            height = U2Size.textButtonS,
            modifier = Modifier.testTag(LocationOpenTags.CANCEL),
        )
    }
}

/**
 * The chooser for [choice] that carries the chosen app out once the sheet is down: put it inside a
 * floating `U2Sheet`; [onDismiss] asks the sheet to go down (a choice or “Vazgeç”). The app opens
 * when this content leaves the screen — never over the sheet, and not once the app has left the
 * foreground.
 */
@Composable
fun LocationChooser(choice: MapChoice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val haptics = LocalHaptics.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pending by remember(choice) { mutableStateOf<MapLaunch?>(null) }
    val currentPending by rememberUpdatedState(pending)
    DisposableEffect(choice) {
        onDispose {
            val launch = currentPending ?: return@onDispose
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@onDispose
            Handler(Looper.getMainLooper()).post { MapLauncher.open(context, guard, launch.place, launch.app) }
        }
    }
    LocationOpenContent(
        choice = choice,
        onChoose = { app ->
            haptics.selection()
            pending = MapLaunch(app, choice.place)
            onDismiss()
        },
        onCancel = {
            pending = null
            onDismiss()
        },
        modifier = modifier,
    )
}

/** The place a message points at: a location's place, or a live share's latest position (no name). */
internal fun placeOf(content: MessageContent?, livePosition: LivePosition?): MapPlace? = when (content) {
    is MessageContent.Location -> MapPlace(content.name, content.latitude, content.longitude)
        .takeIf { LivePosition(content.latitude, content.longitude).isValidCoordinate }
    is MessageContent.LiveLocation -> livePosition?.takeIf { it.isValidCoordinate }?.let { MapPlace("", it.latitude, it.longitude) }
    else -> null
}

/** The chooser for the place in [message] of [chat] (the entry's body). */
@Composable
internal fun MessageLocationChooser(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) {
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val messages by remember(chat) { messenger.messages(chat) }.collectAsState()
    val live by remember(chat) { messenger.liveLocations(chat) }.collectAsState()
    val found = messages.firstOrNull { it.id == message }
    val place = placeOf(found?.content, live.share(message)?.position)
    val choice = remember(place, found?.isDisappearing) {
        place?.let { MapLauncher.choice(context, it, isDisappearing = found?.isDisappearing == true) }
    }
    if (choice == null) {
        DisposableEffect(Unit) {
            onDismiss()
            onDispose {}
        }
        return
    }
    LocationChooser(choice, onDismiss, modifier)
}
