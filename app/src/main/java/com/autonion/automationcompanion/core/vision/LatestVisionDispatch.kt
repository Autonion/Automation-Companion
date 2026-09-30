package com.autonion.automationcompanion.core.vision

import kotlinx.coroutines.channels.Channel

// Contains result metadata only, never Image/Bitmap ownership. The single
// consumer awaits gesture completion while the producer replaces queued results.
class LatestVisionDispatch<T> {
    private data class Entry<T>(val value: T, val generation: Int, val observedAtMs: Long)
    private val pending = Channel<Entry<T>>(Channel.CONFLATED)

    fun offer(value: T, generation: Int, observedAtMs: Long) {
        pending.trySend(Entry(value, generation, observedAtMs))
    }

    fun clear() { while (pending.tryReceive().isSuccess) Unit }
    fun close() { pending.close() }

    suspend fun consume(
        generation: () -> Int,
        nowMs: () -> Long,
        notBeforeMs: () -> Long,
        dispatch: suspend (T) -> Unit
    ) {
        for (entry in pending) {
            if (entry.generation != generation() || entry.observedAtMs <= notBeforeMs() ||
                !isFreshVisionFrame(entry.observedAtMs, nowMs())) continue
            dispatch(entry.value)
        }
    }
}
