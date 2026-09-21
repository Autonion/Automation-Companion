package com.autonion.automationcompanion.core.vision

import org.junit.Assert.*
import org.junit.Test

class MovingTapPredictionTest {
    private val bounds = TapBounds(0, 100, 500, 800)
    private val hit = MatchResultNative(1, true, 0.9f, 100, 200, 100, 100, 7, 0f, 1000f, 3)

    @Test fun predictsFromFrameAgeAndTapLead() {
        val point = predictMovingTap(hit, 1000, 1040, bounds, 35)!!
        assertEquals(150f, point.x, 0.01f)
        assertEquals(325f, point.y, 0.01f)
    }

    @Test fun rejectsOldFutureAndUntrackedFrames() {
        assertNull(predictMovingTap(hit, 1000, 1151, bounds))
        assertNull(predictMovingTap(hit, 1000, 999, bounds))
        assertNull(predictMovingTap(hit.copy(observations = 1), 1000, 1010, bounds))
        assertNull(predictMovingTap(hit.copy(velocityY = Float.NaN), 1000, 1010, bounds))
    }

    @Test fun leavesRoiByRejectingInsteadOfClamping() {
        assertNull(predictMovingTap(hit.copy(y = 730), 1000, 1040, bounds))
        assertNull(predictMovingTap(hit.copy(velocityY = 5000f), 1000, 1040, bounds))
    }

    @Test fun sequentialTapUsesUpdatedAge() {
        val first = predictMovingTap(hit, 1000, 1010, bounds)!!
        val second = predictMovingTap(hit, 1000, 1042, bounds)!!
        assertEquals(32f, second.y - first.y, 0.01f)
    }
}
