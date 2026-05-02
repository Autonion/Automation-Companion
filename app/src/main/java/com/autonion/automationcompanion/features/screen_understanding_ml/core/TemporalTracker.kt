package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.graphics.RectF
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import kotlin.math.max
import kotlin.math.min

class TemporalTracker {

    private val trackedElements = mutableListOf<UIElement>()
    private val iouThreshold = 0.5f
    private val retentionTimeMs = 500L // Keep lost elements for 500ms

    fun update(detectedElements: List<UIElement>): List<UIElement> {
        val currentTime = System.currentTimeMillis()
        val unmatchedDetected = detectedElements.toMutableList()
        val updatedTracks = mutableListOf<UIElement>()

        // 1. Match existing tracks to new detections
        // Simple greedy matching
        for (track in trackedElements) {
            var bestMatch: UIElement? = null
            var bestIoU = 0f

            val iterator = unmatchedDetected.iterator()
            while (iterator.hasNext()) {
                val detection = iterator.next()
                if (detection.label == track.label) {
                    val iou = calculateIoU(track.bounds, detection.bounds)
                    if (iou > iouThreshold && iou > bestIoU) {
                        bestIoU = iou
                        bestMatch = detection
                    }
                }
            }

            if (bestMatch != null) {
                // Update track
                updatedTracks.add(
                    track.copy(
                        bounds = bestMatch.bounds,
                        confidence = bestMatch.confidence,
                        lastSeenTimestamp = currentTime
                        // Keep the original ID
                    )
                )
                unmatchedDetected.remove(bestMatch)
            } else {
                // Track lost temporarily
                if (currentTime - track.lastSeenTimestamp < retentionTimeMs) {
                    updatedTracks.add(track)
                }
            }
        }

        // 2. Add new tracks for unmatched detections
        for (newDetection in unmatchedDetected) {
            updatedTracks.add(newDetection)
        }

        trackedElements.clear()
        trackedElements.addAll(updatedTracks)

        return trackedElements
    }

    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersectionLeft = max(rect1.left, rect2.left)
        val intersectionTop = max(rect1.top, rect2.top)
        val intersectionRight = min(rect1.right, rect2.right)
        val intersectionBottom = min(rect1.bottom, rect2.bottom)

        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val rect1Area = rect1.width() * rect1.height()
        val rect2Area = rect2.width() * rect2.height()
        val unionArea = rect1Area + rect2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    fun clear() {
        trackedElements.clear()
    }
}
