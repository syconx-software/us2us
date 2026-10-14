package com.syconx.us2us.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.OnboardingRoute
import com.syconx.us2us.domain.nav.RestoreMode
import com.syconx.us2us.ui.env.DeviceBiometrics
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.LocalFeatureScreens
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.LocalHarnessState
import com.syconx.us2us.ui.env.LocalMessenger
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.nav.SoftNavigationStack

/**
 * Onboarding (01–02 and 7.3 from 1.1), starting at [entry]: the paged welcome (1.1–1.3), then the
 * setup steps (2.1–2.3) pushed on a soft stack; “Yedekten geri yükle” hands off to 7.3 and comes back
 * for a new secret code (2.2 ×2 → 2.3 → 4.1). [onFinished] runs when 2.3 or a restore completed and
 * the app may show 4.1.
 *
 * All state lives in [OnboardingFlowModel] behind `MessengerService` (fixtures today, the live
 * service later).
 */
@Composable
fun OnboardingFlow(entry: OnboardingEntry, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val messenger = LocalMessenger.current
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val harness = LocalHarnessState.current as? OnboardingHarnessState
    val scope = rememberCoroutineScope()
    val finished by rememberUpdatedState(onFinished)
    val flow = remember {
        OnboardingFlowModel(
            messenger = messenger,
            scope = scope,
            haptics = haptics,
            // 2.3 offers biometrics only when a strong one is enrolled; the fixtures' knob wins.
            biometry = { (messenger as? FixtureControls)?.biometry ?: DeviceBiometrics.enrolled(context) },
            onFinished = { finished() },
            onReturnToLock = { app.returnToLock() },
        ).also { flow ->
            when (entry) {
                OnboardingEntry.NewCodeAfterRestore -> flow.continueAfterRestore()
                OnboardingEntry.RestoreFromBackup -> flow.startRestore()
                OnboardingEntry.Welcome -> Unit
            }
            harness?.apply(flow)
        }
    }
    OnboardingFlowHost(
        flow = flow,
        modifier = modifier,
        focusesUsernameField = harness?.focusesUsernameField ?: true,
        showsFocusChrome = harness?.focusesUsernameField ?: false,
    )
    if (harness?.isDemo == true) {
        LaunchedEffect(flow) { harness.runDemo(flow) }
    }
}

/** The onboarding stack: the welcome pager at its root, the setup steps and 7.3 above it. */
@Composable
internal fun OnboardingFlowHost(
    flow: OnboardingFlowModel,
    modifier: Modifier = Modifier,
    focusesUsernameField: Boolean = true,
    showsFocusChrome: Boolean = false,
) {
    val keyboard = rememberU2Keyboard()
    SideEffect { flow.dismissKeyboard = { keyboard.dismiss() } }
    LaunchedEffect(flow) {
        var old = flow.router.path
        snapshotFlow { flow.router.path }.collect { new ->
            if (new != old) {
                val previous = old
                old = new
                flow.pathChanged(previous, new)
            }
        }
    }
    SoftNavigationStack(
        router = flow.router,
        modifier = modifier,
        allowsSwipeBack = flow::allowsSwipeBack,
        root = { WelcomePager(flow.pager, onCreateAccount = flow::startSetup, onRestore = flow::startRestore) },
    ) { route ->
        when (route) {
            OnboardingRoute.Username -> UsernameStep(
                model = flow.username,
                step = flow.stepLabel(route),
                onBack = { flow.router.pop() },
                onContinue = flow::usernameContinue,
                focusesOnAppear = focusesUsernameField,
                showsFocusChrome = showsFocusChrome,
            )
            OnboardingRoute.SecretCode -> SecretCodeStep(flow.code, flow.stepLabel(route), onBack = flow::codeBack)
            OnboardingRoute.NoWayBack -> NoWayBackStep(flow.finish, flow.stepLabel(route), onBack = { flow.router.pop() })
            // 7.3 · Yedekten geri yükle (built by the settings feature): it opens the system document
            // picker by itself, asks for the backup password and restores on the device; then a new code.
            OnboardingRoute.Restore -> LocalFeatureScreens.current.RestoreBackup(
                mode = RestoreMode.Onboarding,
                onClose = { flow.router.pop() },
                onFinished = flow::restoreFinished,
                modifier = Modifier,
            )
        }
    }
}
