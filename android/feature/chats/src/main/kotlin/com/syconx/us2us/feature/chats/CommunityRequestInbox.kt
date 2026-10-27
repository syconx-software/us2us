package com.syconx.us2us.feature.chats

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.domain.CommunityCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class CommunityRequestInboxRow(val group: CommunityCard, val count: Int)

/** Owner endpoints only; refresh never opens a conversation or marks messages read. */
internal suspend fun CommunitiesService.requestInbox(previous: List<CommunityRequestInboxRow>): List<CommunityRequestInboxRow> {
    val snapshot = refresh()
    if (!snapshot.supported) return emptyList()
    return snapshot.groups.filter { it.active && it.isOwner }.mapNotNull { group ->
        val count = try { applications(group.id).size }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { previous.firstOrNull { it.group.id == group.id }?.count ?: 0 }
        currentCoroutineContext().ensureActive()
        if (count > 0) CommunityRequestInboxRow(group, count) else null
    }.sortedBy { it.group.id }
}

/** Memory-only, foreground polling; a lock cancels pending fetches and clears rows and sheets. */
@Composable
internal fun rememberCommunityRequestInbox(service: CommunitiesService?, refreshKey: Int, onLocked: () -> Unit): List<CommunityRequestInboxRow> {
    var rows by remember(service) { mutableStateOf(emptyList<CommunityRequestInboxRow>()) }
    val unlocked by (service?.unlocked ?: kotlinx.coroutines.flow.flowOf(false)).collectAsState(initial = false)
    val visible = com.syconx.us2us.ui.nav.LocalIsLayerVisible.current
    val settled = com.syconx.us2us.ui.nav.LocalLayerMotion.current?.isSettled != false
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clearSheet by rememberUpdatedState(onLocked)
    LaunchedEffect(service, unlocked, lifecycle, refreshKey, visible, settled) {
        if (service == null || !unlocked) {
            rows = emptyList(); clearSheet()
            return@LaunchedEffect
        }
        // Covered screens retain their last count but do not compete with navigation.
        if (!visible || !settled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val next = service.requestInbox(rows)
                    currentCoroutineContext().ensureActive()
                    rows = next
                } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (_: Exception) { /* Keep the last successful inbox during an outage. */ }
                delay(10_000)
            }
        }
    }
    return if (unlocked) rows else emptyList()
}
