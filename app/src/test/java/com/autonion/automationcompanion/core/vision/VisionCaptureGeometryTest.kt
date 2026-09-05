package com.autonion.automationcompanion.core.vision

import org.junit.Assert.*
import org.junit.Test

class VisionCaptureGeometryTest {
    @Test fun downscalesLargeScreenAndRestoresTapCoordinates() {
        val g = VisionCaptureGeometry.create(1080, 2340, 72, true)
        assertEquals(720, g.captureHeight)
        assertEquals(332, g.captureWidth)
        val hit = g.toScreen(MatchResultNative(matched = true, x = g.captureX(540), y = g.captureY(1200),
            width = g.templateWidth(72), height = g.templateHeight(72)))
        assertEquals(576.0, hit.x + hit.width / 2.0, 3.0)
        assertEquals(1236.0, hit.y + hit.height / 2.0, 3.0)
        assertEquals(g.captureWidth, g.captureRight(1080))
        assertEquals(g.captureHeight, g.captureBottom(2340))
    }

    @Test fun preservesSmallTargetsAndLegacyRotationCoordinates() {
        assertEquals(2340, VisionCaptureGeometry.create(1080, 2340, 16, true).captureHeight)
        assertEquals(2340, VisionCaptureGeometry.create(1080, 2340, 100, false).captureHeight)
        assertEquals(720, VisionCaptureGeometry.create(480, 800, 100, true).captureHeight)
    }

    @Test fun producerTimestampIncludesCaptureQueueDelay() {
        val time = visionFrameTime(950_000_000L, 1_000_000_000L, 1000)
        assertEquals(950L, time.observedAtMs)
        assertTrue(time.producerTimestampUsed)
        assertTrue(isFreshVisionFrame(time.observedAtMs, 1100))
        assertFalse(isFreshVisionFrame(time.observedAtMs, 1101))
        assertFalse(isFreshVisionFrame(1100, 1099))
    }

    @Test fun incompatibleTimestampsAreExplicitlyAcquisitionFallback() {
        for (stamp in listOf(0L, 2_000_000_000L, 1L)) {
            val now = if (stamp == 1L) 10_000_000_000L else 1_000_000_000L
            val time = visionFrameTime(stamp, now, 1000)
            assertEquals(1000L, time.observedAtMs)
            assertFalse(time.producerTimestampUsed)
        }
    }
}
