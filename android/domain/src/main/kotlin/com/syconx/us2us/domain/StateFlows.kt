package com.syconx.us2us.domain

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A read-only [StateFlow] derived from [this] without a coroutine scope: [StateFlow.value] is
 * computed on access, and collectors get the mapped values without repeats. Use it for cheap,
 * pure projections (a chat out of the chat list, a flag out of the settings).
 */
fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = MappedStateFlow(this, transform)

/**
 * A read-only [StateFlow] combining [first] and [second] without a coroutine scope: the value is
 * computed on access, collectors get the combined values without repeats.
 */
fun <A, B, R> combineStates(first: StateFlow<A>, second: StateFlow<B>, transform: (A, B) -> R): StateFlow<R> =
    CombinedStateFlow(first, second, transform)

/** A [StateFlow] that never changes. */
fun <T> constantStateFlow(value: T): StateFlow<T> = MutableStateFlow(value).asStateFlow()

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        // A StateFlow's collection never completes; the source's never does either.
        error("StateFlow collection ended")
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class CombinedStateFlow<A, B, R>(
    private val first: StateFlow<A>,
    private val second: StateFlow<B>,
    private val transform: (A, B) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(first.value, second.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        combine(first, second, transform).distinctUntilChanged().collect(collector)
        error("StateFlow collection ended")
    }
}
