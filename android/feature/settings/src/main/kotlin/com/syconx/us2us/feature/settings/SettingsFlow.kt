package com.syconx.us2us.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.SettingsPage
import com.syconx.us2us.ui.env.LocalAppModel
import com.syconx.us2us.ui.env.U2AppModel
import com.syconx.us2us.ui.nav.U2Router
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Shared state of the settings feature (one per app model): the service, the confirmation toast
 * shown on 7.1, the erase sheet and the restore-access notice.
 *
 * [scope] outlives every settings screen (iOS unstructured `Task`s): settings written from a switch,
 * the erase that runs once its sheet has gone.
 */
@Stable
class SettingsFlow internal constructor(
    val messenger: MessengerService,
    val scope: CoroutineScope,
) {
    /** A short confirmation on 7.1 (“Gizli kodun değişti.”, “Yedek kaydedildi.”). */
    @get:StringRes
    var toast: Int? by mutableStateOf(null)

    /** 7.1 › “Her şeyi sil”: the account on the relay, then this phone. */
    val erasure = EraseAccountModel(messenger, scope)

    /** The notice after a restore whose token rotation is unanswered (7.1, 4.1). */
    val restoreAccess = RestoreAccessModel(messenger)

    private var accountResets: Int? = null

    /** Pushes a settings page on the main stack with the soft push. */
    fun open(page: SettingsPage, router: U2Router<AppRoute>) {
        router.push(if (page == SettingsPage.BackupExport) AppRoute.BackupExport else AppRoute.Page(page))
    }

    /**
     * The app's account count changed ([U2AppModel.accountResets]): the account the notice was about
     * is gone (erased, wiped, or replaced by a restore), so a later notice starts without the old
     * check's answer.
     */
    internal fun accountChanged(resets: Int) {
        val previous = accountResets
        accountResets = resets
        if (previous != null && previous != resets) restoreAccess.reset()
    }

    companion object {
        private val flows = WeakHashMap<U2AppModel, SettingsFlow>()

        /** The flow of [app], made on first use. */
        fun of(app: U2AppModel): SettingsFlow = synchronized(flows) {
            flows.getOrPut(app) { SettingsFlow(app.messenger, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
        }
    }
}

/** The settings flow of the current app model, kept in step with its account. */
@Composable
internal fun rememberSettingsFlow(): SettingsFlow {
    val app = LocalAppModel.current
    val flow = remember(app) { SettingsFlow.of(app) }
    LaunchedEffect(flow, app.accountResets) { flow.accountChanged(app.accountResets) }
    return flow
}
