package com.autonion.automationcompanion

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.screen_understanding_ml.core.*
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenMlOcrTest {
    private fun element(text: String, bounds: RectF) = UIElement(text + bounds, "Text", 1f, bounds, text)
    private fun step(text: String, bounds: RectF = RectF(10f, 10f, 150f, 50f)) =
        AutomationStep("step", 0, text, anchor = element(text, bounds),
            captureScreenWidth = 400f, captureScreenHeight = 800f)

    @Test fun exactLineWinsOverEarlierSubstringAndContainingBlock() {
        val exact = element("On", RectF(10f, 200f, 70f, 250f))
        val match = OcrMatching.findBest(listOf(
            element("Continue", RectF(10f, 10f, 150f, 50f)),
            element("Turn on notifications", RectF(10f, 60f, 350f, 150f)), exact
        ), step("On"), 400f, 800f)
        assertSame(exact, match)
    }

    @Test fun duplicateLabelsUseNormalizedSavedPosition() {
        val top = element("Open", RectF(20f, 20f, 300f, 100f))
        val bottom = element("Open", RectF(20f, 1200f, 300f, 1300f))
        assertSame(bottom, OcrMatching.findBest(listOf(top, bottom),
            step("Open", RectF(10f, 600f, 150f, 650f)), 800f, 1600f))
    }

    @Test fun multilineSavedAnchorMatchesWholeBlockAndEditorUsesLines() {
        val result = OcrResult("Sign in\nwith email", listOf(OcrBlock("Sign in\nwith email",
            RectF(10f, 10f, 200f, 100f), listOf(
                OcrLine("Sign in", RectF(10f, 10f, 120f, 45f), 1f),
                OcrLine("with email", RectF(10f, 60f, 200f, 100f), 1f)), 1f)))
        assertEquals(2, result.textElements().size)
        assertEquals("Sign in\nwith email", OcrMatching.findBest(
            result.textElements(true), step("Sign in with email"), 400f, 800f)?.text)
    }

    @Test fun smallTextInsideLargeButtonIsEnriched() {
        val button = UIElement("button", "button", 1f, RectF(0f, 0f, 400f, 200f))
        val result = OcrResult("Go", listOf(OcrBlock("Go", RectF(180f, 90f, 220f, 110f),
            listOf(OcrLine("Go", RectF(180f, 90f, 220f, 110f), 1f)), 1f)))
        assertEquals("Go", OcrMatching.enrich(listOf(button), result).single().text)
    }

    @Test fun frameCacheReleasesOldFramesAndKeepsOcrCopyAlive() {
        val cache = ScreenFrameCache()
        val generation = cache.generation()
        val first = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        assertTrue(cache.publish(first, generation))
        val copy = cache.copy()!!
        val snapshot = cache.copyAfter(-1)!!
        assertNull(cache.copyAfter(snapshot.revision))
        snapshot.bitmap.recycle()
        val second = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        assertTrue(cache.publish(second, generation))
        assertTrue(first.isRecycled)
        assertEquals(Color.RED, copy.getPixel(10, 10))
        cache.discard()
        assertTrue(second.isRecycled)
        assertNull(cache.copy())
        val third = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        assertTrue(cache.publish(third, generation))
        cache.clear()
        assertTrue(third.isRecycled)
        assertFalse(cache.publish(copy, generation))
        assertFalse(copy.isRecycled)
        copy.recycle()
    }

    @Test fun trackerDoesNotMutatePreviouslyPublishedList() {
        val tracker = TemporalTracker()
        val original = tracker.update(listOf(element("Open", RectF(10f, 10f, 150f, 50f))))
        tracker.clear()
        assertEquals(1, original.size)
    }

    @Test fun bundledDetectorReusesBuffersAcrossDifferentFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = PerceptionLayer(context)
        try {
            for ((width, height) in listOf(400 to 800, 800 to 400)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    detector.detect(bitmap)
                } finally { bitmap.recycle() }
            }
            assertEquals(2L, detector.getInferenceCount())
            assertNotEquals("Unknown", detector.getDelegate())
        } finally { detector.close() }
    }

    @Test fun rejectedGestureReturnsWithoutWaitingForCallback() = runBlocking {
        val disconnectedService = object : AccessibilityService() {
            init { attachBaseContext(InstrumentationRegistry.getInstrumentation().targetContext) }
            override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
            override fun onInterrupt() = Unit
        }
        ActionExecutor.onServiceConnected(disconnectedService)
        try {
            assertFalse(withTimeout(1500) { ActionExecutor.executeClick(PointF(10f, 10f)) })
        } finally {
            ActionExecutor.onServiceDisconnected()
            AccessibilityRouter.getService()?.let(ActionExecutor::onServiceConnected)
        }
    }

    // Synthetic images only: these tests do not capture the user's screen or tap other apps.
    @Test fun bundledOcrRecognizesTextAndCanReuseTheEngine() = runBlocking {
        val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f }
        canvas.drawText("Continue", 40f, 100f, paint)
        canvas.drawText("Sign in", 40f, 260f, paint)
        val engine = OcrEngine()
        try {
            repeat(3) { attempt ->
                val start = SystemClock.elapsedRealtime()
                val result = withTimeout(20000) { engine.recognizeText(bitmap) }
                Log.i("ScreenMlOcrTest", "Synthetic OCR attempt $attempt: ${SystemClock.elapsedRealtime() - start}ms")
                assertTrue(result.fullText.contains("Continue", ignoreCase = true))
                val match = OcrMatching.findBest(result.textElements(true), step("Sign in"), 800f, 500f)
                assertNotNull(match)
                assertTrue(match!!.bounds.centerY() > 180f)
            }
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }
}
