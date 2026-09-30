package com.autonion.automationcompanion.features.semantic_automation.ml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeGenerationTest {
    @Test
    fun stopWaitsForNativeExitBeforeAnotherRequestCanUseModel() = runBlocking {
        withTimeout(5000) {
            val gate = Mutex()
            val entered = CompletableDeferred<Unit>()
            val stopSeen = CompletableDeferred<Unit>()
            val releaseNative = CountDownLatch(1)
            val cancels = AtomicInteger()
            val automation = launch(Dispatchers.Default) {
                gate.withLock {
                    withNativeGenerationCancellation({
                        cancels.incrementAndGet()
                        stopSeen.complete(Unit)
                    }) {
                        withContext(Dispatchers.IO) {
                            entered.complete(Unit)
                            check(releaseNative.await(3, TimeUnit.SECONDS))
                        }
                    }
                }
            }
            entered.await()
            automation.cancel()
            stopSeen.await()
            val chat = async {
                gate.withLock { "chat response" }
            }
            try {
                assertFalse("Lock must survive cancellation until JNI exits", gate.tryLock())
                assertFalse(chat.isCompleted)
            } finally {
                releaseNative.countDown()
            }
            automation.join()
            assertTrue(automation.isCancelled)
            assertEquals("chat response", chat.await())
            val countAfterExit = cancels.get()
            assertEquals("next response", gate.withLock {
                withNativeGenerationCancellation({ error("Completed request cancelled the next request") }) {
                    "next response"
                }
            })
            assertEquals(countAfterExit, cancels.get())
        }
    }

    @Test
    fun cancellingQueuedRequestDoesNotCancelCurrentNativeGeneration() = runBlocking {
        withTimeout(5000) {
            val gate = Mutex()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first = async {
                gate.withLock {
                    withNativeGenerationCancellation({ error("Active request was cancelled by queued caller") }) {
                        entered.complete(Unit)
                        finish.await()
                        "first response"
                    }
                }
            }
            entered.await()
            val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                gate.withLock {
                    withNativeGenerationCancellation({ error("Queued request has no native ownership") }) {
                        error("Cancelled request must not start")
                    }
                }
            }
            queued.cancelAndJoin()
            finish.complete(Unit)
            assertEquals("first response", first.await())
        }
    }

    @Test
    fun cancellationBeforeNativeEntryIsRetriedAfterNativeResetsStopFlag() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            val firstCancel = CountDownLatch(1)
            val secondCancel = CountDownLatch(1)
            val attempts = AtomicInteger()
            val generation = launch(Dispatchers.Default) {
                withNativeGenerationCancellation({
                    if (attempts.incrementAndGet() == 1) firstCancel.countDown()
                    else secondCancel.countDown()
                }) {
                    withContext(Dispatchers.IO) {
                        entered.complete(Unit)
                        check(firstCancel.await(3, TimeUnit.SECONDS))
                        // Simulates a native call resetting an earlier cancellation flag on entry.
                        check(secondCancel.await(3, TimeUnit.SECONDS))
                    }
                }
            }
            entered.await()
            generation.cancelAndJoin()
            assertTrue(attempts.get() >= 2)
        }
    }

    @Test
    fun normalCompletionAndErrorsArePreservedWithoutCancellingNative() = runBlocking {
        assertEquals("response", withNativeGenerationCancellation({ error("Unexpected cancel") }) { "response" })
        val expected = IllegalStateException("context is full")
        try {
            withNativeGenerationCancellation({ error("Unexpected cancel") }) { throw expected }
            fail("Expected the original error")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy exceptions while retaining their type/message.
            assertEquals(expected.message, actual.message)
        }
    }

    @Test
    fun readinessRequiresSuccessfulLoadOfSelectedModel() {
        val loaded = SlmLoadState("qwen", SlmLoadPhase.READY)
        assertTrue(loaded.isReadyFor("qwen"))
        assertFalse(loaded.isReadyFor("gemma"))
        assertFalse(loaded.isReadyFor(null))
        assertFalse(SlmLoadState("qwen", SlmLoadPhase.LOADING).isReadyFor("qwen"))
        assertFalse(SlmLoadState("qwen", SlmLoadPhase.FAILED, "Cannot load").isReadyFor("qwen"))
    }
}
