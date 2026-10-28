package com.syconx.us2us.core.messenger

/**
 * Failed attempts per key (a relay envelope id), in memory and bounded: the oldest keys are
 * forgotten first, so a flood of bad envelopes cannot grow it. Thread-safe.
 */
internal class AttemptCounter(private val capacity: Int) {
    private val counts = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > capacity
    }

    /** Records one more failure for [key] and returns the failures so far, this one included. */
    @Synchronized
    fun record(key: String): Int {
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        return next
    }

    @Synchronized
    fun forget(key: String) {
        counts.remove(key)
    }

    @Synchronized
    fun count(key: String): Int = counts[key] ?: 0
}
