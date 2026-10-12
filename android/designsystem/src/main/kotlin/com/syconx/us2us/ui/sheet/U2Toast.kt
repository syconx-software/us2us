package com.syconx.us2us.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.env.LocalU2SafeArea
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.env.windowSafeArea
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.delay

/** How long a toast stays (iOS: 2.4 s). */
const val U2_TOAST_MILLIS = 2_400L

/**
 * A short confirmation floating under the top edge (design y 70): fade + 8 dp drop, gone after
 * 2.4 s or on a tap ([onDismiss] then sets [message] to `null`). Place it as the last child of the
 * screen's root box; it fills it and draws over the screen (iOS `.u2Toast($message)`).
 */
@Composable
fun U2Toast(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(message) }
    SideEffect { if (message != null) shown = message }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(U2_TOAST_MILLIS)
        currentOnDismiss()
    }
    val transition = U2Transitions.drop()
    CompositionLocalProvider(LocalU2SafeArea provides windowSafeArea()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible = message != null,
                enter = transition.enter,
                exit = transition.exit,
                modifier = Modifier.u2Top(70.dp),
            ) {
                val capsule = RoundedCornerShape(50)
                U2Text(
                    text = message ?: shown.orEmpty(),
                    style = U2TextStyles.label15Emphasis,
                    color = U2Theme.colors.text,
                    modifier = Modifier
                        .then(u2TransitionBlur(transition))
                        .u2BoxShadow(capsule, U2BoxShadows.floatingBanner)
                        .u2Backdrop(U2BackdropStyle.Banner, capsule)
                        .u2InnerStroke(U2Theme.colors.strokeCard, 1.dp, capsule)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { currentOnDismiss() }
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                        .testTag(U2_TOAST_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** The toast's test tag. */
const val U2_TOAST_TAG = "u2.toast"
