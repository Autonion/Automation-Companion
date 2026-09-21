package com.autonion.automationcompanion.core.vision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LatestVisionDispatchTest {
    @Test fun detectionContinuesDuringGestureAndOnlyNewestResultIsDispatched() = runBlocking {
        val queue = LatestVisionDispatch<Int>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseGesture = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val dispatched = mutableListOf<Int>()
        var active = 0
        var maximumActive = 0
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.consume({ 1 }, { 1100 }, { 0 }) { value ->
                active++
                maximumActive = maxOf(maximumActive, active)
                dispatched += value
                if (value == 1) {
                    firstStarted.complete(Unit)
                    releaseGesture.await()
                }
                active--
                if (value == 3) finished.complete(Unit)
            }
        }
        queue.offer(1, 1, 1000)
        withTimeout(2000) { firstStarted.await() }
        queue.offer(2, 1, 1010)
        queue.offer(3, 1, 1020)
        assertEquals(listOf(1), dispatched)
        releaseGesture.complete(Unit)
        withTimeout(2000) { finished.await() }
        queue.close()
        consumer.join()
        assertEquals(listOf(1, 3), dispatched)
        assertEquals(1, maximumActive)
    }

    @Test fun rotationAndPauseInvalidateQueuedOldCoordinates() = runBlocking {
        val queue = LatestVisionDispatch<Int>()
        var generation = 1
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<Int>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.consume({ generation }, { 1100 }, { 0 }) { value ->
                values += value
                if (value == 1) { started.complete(Unit); release.await() }
            }
        }
        queue.offer(1, 1, 1000)
        withTimeout(2000) { started.await() }
        queue.offer(2, 1, 1001)
        generation++
        release.complete(Unit)
        yield()
        queue.offer(3, 2, 1002)
        yield()
        queue.offer(4, 2, 1003)
        generation++
        queue.clear()
        queue.close()
        consumer.join()
        assertEquals(listOf(1, 3), values)
    }

    @Test fun staleFutureAndPreGestureFramesAreNeverDispatched() = runBlocking {
        val queue = LatestVisionDispatch<Int>()
        val values = mutableListOf<Int>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.consume({ 1 }, { 1200 }, { 1100 }) { values += it }
        }
        queue.offer(1, 1, 1000); yield()
        queue.offer(2, 1, 1201); yield()
        queue.offer(3, 1, 1099); yield()
        queue.offer(5, 1, 1100); yield()
        queue.offer(4, 1, 1150); yield()
        queue.close()
        consumer.join()
        assertEquals(listOf(4), values)
    }
}
