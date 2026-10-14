package com.syconx.us2us.feature.lock

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.nav.RestoreMode
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.DeviceBiometrics
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.sheet.U2Sheet
import com.syconx.us2us.ui.sheet.U2SheetStyle
import com.syconx.us2us.ui.theme.U2Theme
import java.net.URI

/**
 * The lock (03): Face ID (3.1) or the code (3.2), 3.3 and the wiped outcome — shown in the security
 * layer on every launch and every return from the background.
 *
 * [onUnlocked] after a successful unlock; [onRestore] when 3.3 › “Yedekten geri yükle” replaced the
 * account (onboarding continues at 2.2); [onErased] when everything was erased. The lock's background
 * is in place at once (it must hide everything before the first frame); its content fades in
 * (0.36 s). Screens swap with a 0.36 s cross-fade; nothing bounces.
 */
@Composable
fun LockFlow(onUnlocked: () -> Unit, onRestore: () -> Unit, onErased: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val messenger = LocalMessenger.current
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val harness = LocalHarnessState.current as? LockHarnessState
    val scope = rememberCoroutineScope()
    val unlocked by rememberUpdatedState(onUnlocked)
    val erased by rememberUpdatedState(onErased)
    val flow = remember {
        val override = harness?.biometry ?: (messenger as? FixtureControls)?.biometry
        val biometry = when {
            override != null -> override
            messenger.settings.value.faceIdEnabled -> DeviceBiometrics.enrolled(context)
            else -> Biometry.None
        }
        LockFlowModel(
            messenger = messenger,
            scope = scope,
            biometry = biometry,
            haptics = haptics,
            isFrozenForHarness = harness != null,
            startsWiped = app.showsWipedOutcome,
            onUnlock = { unlocked() },
            onErase = { erased() },
            onStartOver = { restoring -> app.leaveWipedLock(restoring) },
            onWiped = { app.forgetAccountPreferences() },
        ).also { harness?.apply(it) }
    }
    Box(modifier.fillMaxSize().background(U2Theme.colors.bg)) {
        LockFlowContent(
            flow = flow,
            onRestore = onRestore,
            showsDemoHint = app.configuration.usesFixtures && !app.configuration.frozenClock,
        )
        if (harness?.replacesLock == true) LockHarnessScreen(harness)
    }
    LaunchedEffect(flow) { runLockDemo(app.configuration.lockDemo, flow) }
}

/** The lock's screens and the 3.3 sheet. */
@Composable
internal fun LockFlowContent(flow: LockFlowModel, onRestore: () -> Unit, showsDemoHint: Boolean) {
    val prompt = BiometricPromptText(
        reason = stringResource(flow.biometry.reasonText()),
        cancelTitle = stringResource(R.string.lock_faceid_usecode),
    )
    val currentPrompt by rememberUpdatedState(prompt)

    // The app became active → the armed scan; back from the background → lock afresh. Our own
    // system activities (7.3's document picker) are not a background, unless the user stayed away
    // longer than the guard covers.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val guard = LocalExternalActivityGuard.current
    DisposableEffect(lifecycle, flow, guard) {
        var stoppedAt: Long? = null
        var guarded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stoppedAt = SystemClock.elapsedRealtime()
                    guarded = guard.isActive
                }
                Lifecycle.Event.ON_START -> stoppedAt?.let { since ->
                    stoppedAt = null
                    if (!guarded || !guard.covers(SystemClock.elapsedRealtime() - since)) flow.relock()
                }
                Lifecycle.Event.ON_RESUME -> flow.appBecameActive(currentPrompt)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 3.3 › “Yedekten geri yükle”: the system document picker for the `.u2u` file, in the lock (any
    // type: providers do not know ours). `U2PickBackup` picks the stand-in backup instead.
    val context = LocalContext.current
    val pickBackup = LocalAppModel.current.configuration.pickBackup
    val openDocument = rememberGuardedLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        flow.backupPickerClosed(uri?.let { URI(it.toString()) })
    }
    LaunchedEffect(flow.showsBackupPicker) {
        if (!flow.showsBackupPicker) return@LaunchedEffect
        if (pickBackup) {
            flow.backupPickerClosed(DesignFixtures.standInBackupFile(context.cacheDir))
            return@LaunchedEffect
        }
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            // No document provider on this phone: the lock stays as it is.
            flow.backupPickerClosed(null)
        }
    }

    // “cover present instantly; content inside fades in 0.36 s”
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, U2Motion.state.spec()) }

    val fade = U2Transitions.fade()
    val push = U2Transitions.push()
    AnimatedContent(
        targetState = flow.stage,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value },
        transitionSpec = {
            if (targetState is LockFlowModel.Stage.Restore) push.enter togetherWith push.exit else fade.enter togetherWith fade.exit
        },
        label = "lockStage",
    ) { stage ->
        when (stage) {
            LockFlowModel.Stage.Biometric -> LockBiometricScreen(flow, prompt)
            LockFlowModel.Stage.Code -> LockCodeScreen(flow, prompt, showsDemoHint)
            // 3.3 › “Yedekten geri yükle” → 7.3 inside the lock; when it is done, a new code (2.2 ×2 → 2.3 → 4.1).
            is LockFlowModel.Stage.Restore -> LocalFeatureScreens.current.RestoreBackup(
                mode = RestoreMode.Lock,
                file = stage.file,
                onClose = flow::leaveRestore,
                onFinished = onRestore,
                modifier = Modifier.fillMaxSize(),
            )
            // The optional wipe fired (PROTOCOL §5.2): what happened, and the two ways on — never “Kod yanlış”.
            LockFlowModel.Stage.Wiped -> LockWipedScreen(flow)
        }
    }

    U2Sheet(
        isPresented = flow.showsForgot,
        onDismissRequest = flow::closeForgot,
        style = U2SheetStyle.Floating,
        onDismissed = flow::forgotDidDismiss,
    ) {
        LockForgotSheet(flow)
    }
}
