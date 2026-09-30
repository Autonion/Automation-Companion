package com.autonion.automationcompanion.features.semantic_automation.ml

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI generation blocks a worker thread. Cancelling its Flow alone cannot interrupt it.
 * Keep the caller (and its model lock) alive until native generation has actually exited.
 */
internal suspend fun <T> withNativeGenerationCancellation(
    cancelNative: () -> Unit,
    generate: suspend () -> T
): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val cancellationMonitor = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // Cancellation can precede JNI entry, which resets the native stop flag.
                // Repeat until the structured generation block has joined its workers.
                while (!finished.get()) {
                    cancelNative()
                    delay(25)
                }
            }
        }
    }
    try {
        generate()
    } finally {
        finished.set(true)
        withContext(NonCancellable) { cancellationMonitor.cancelAndJoin() }
    }
}
