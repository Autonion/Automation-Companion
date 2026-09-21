package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.graphics.Bitmap

/** Owns one completed frame. OCR receives a private copy that survives the next frame. */
internal class ScreenFrameCache {
    private var bitmap: Bitmap? = null
    private var generation = 0L
    private var revision = 0L
    data class Snapshot(val bitmap: Bitmap, val revision: Long)
    @Synchronized fun generation(): Long = generation
    @Synchronized fun size(): Pair<Float, Float> =
        (bitmap?.width?.toFloat() ?: 0f) to (bitmap?.height?.toFloat() ?: 0f)

    // Ownership transfers only on success. A cleared session rejects its old producer.
    @Synchronized fun publish(frame: Bitmap, producerGeneration: Long): Boolean {
        if (producerGeneration != generation) return false
        bitmap?.recycle()
        bitmap = frame
        revision++
        return true
    }

    @Synchronized fun copy(): Bitmap? = bitmap?.copy(Bitmap.Config.ARGB_8888, false)

    @Synchronized fun copyAfter(previousRevision: Long): Snapshot? {
        if (previousRevision == revision) return null
        return copy()?.let { Snapshot(it, revision) }
    }

    /** Forget pre-playback content while keeping the current capture producer valid. */
    @Synchronized fun discard() {
        bitmap?.recycle()
        bitmap = null
        revision++
    }

    @Synchronized fun clear() {
        generation++
        discard()
    }
}
