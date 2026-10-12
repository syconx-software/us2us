package com.syconx.us2us.feature.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work a viewer starts that must finish after the viewer has gone (telling the messenger that a
 * view-once photo was opened, that something was saved): iOS starts an unstructured `Task` for it.
 */
internal object MediaWork {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
