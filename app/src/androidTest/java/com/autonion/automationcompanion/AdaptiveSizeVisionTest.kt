package com.autonion.automationcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.core.vision.MatchResultNative
import com.autonion.automationcompanion.core.vision.VisionCaptureGeometry
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AdaptiveSizeVisionTest {
    @Before fun setup() { VisionNativeBridge.init() }
    @After fun close() { VisionNativeBridge.release() }

    private fun target(transparent: Boolean = false): Bitmap =
        Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawColor(if (transparent) Color.TRANSPARENT else Color.rgb(25, 30, 35))
            val p = Paint()
            p.color = Color.CYAN
            c.drawRect(6f, 5f, 57f, 41f, p)
            p.color = Color.MAGENTA
            c.drawRect(12f, 10f, 29f, 30f, p)
            p.color = Color.YELLOW
            c.drawCircle(42f, 23f, 9f, p)
            p.color = Color.BLACK
            c.drawRect(37f, 20f, 42f, 24f, p)
        }

    private fun includes(hit: MatchResultNative, rect: Rect): Boolean =
        abs(hit.x + hit.width / 2 - rect.centerX()) <= 4 &&
            abs(hit.y + hit.height / 2 - rect.centerY()) <= 4 &&
            abs(hit.width - rect.width()) <= maxOf(3, rect.width() / 7) &&
            abs(hit.height - rect.height()) <= maxOf(3, rect.height() / 7)

    private fun acquire(frame: Bitmap, expected: List<Rect>, frames: Int = 24): List<MatchResultNative> {
        var last = emptyList<MatchResultNative>()
        val times = mutableListOf<Long>()
        repeat(frames) {
            val start = SystemClock.elapsedRealtime()
            last = VisionNativeBridge.match(frame).filter { it.matched }
            times += SystemClock.elapsedRealtime() - start
            if (expected.all { rect -> last.any { hit -> includes(hit, rect) } }) {
                Log.i("AdaptiveSizeTest", "acquired after=${it + 1} scans times=$times hits=$last")
                return last
            }
        }
        fail("Scale reacquisition failed: expected=$expected last=$last times=$times")
        return last
    }

    @Test fun mixedSizeDuplicatesAreDiscoveredWhileOriginalSizeStaysVisible() {
        val target = target()
        val frame = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        val rects = listOf(Rect(20, 40, 84, 88), Rect(170, 60, 197, 80),
            Rect(40, 190, 140, 265), Rect(155, 390, 305, 503))
        try {
            frame.eraseColor(Color.rgb(8, 12, 20))
            rects.forEach { Canvas(frame).drawBitmap(target, null, it, Paint(Paint.FILTER_BITMAP_FLAG)) }
            VisionNativeBridge.addTemplate(1, target, 0, 0, 360, 720, .8f, false, false)
            val hits = acquire(frame, rects)
            assertEquals("Duplicate scale hypotheses must merge: $hits", rects.size, hits.size)
            val times = (0 until 8).map {
                val started = SystemClock.elapsedRealtime()
                val current = VisionNativeBridge.match(frame).filter { it.matched }
                assertEquals(rects.size, current.size)
                SystemClock.elapsedRealtime() - started
            }
            Log.i("AdaptiveSizeTest", "Mixed-size steady scans=$times")
            assertTrue("Steady scans exceed freshness budget: $times", times.sorted()[4] < 150)
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun zoomChangesAndBlankFramesNeverReuseOldCoordinates() {
        val target = target()
        val frame = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        try {
            VisionNativeBridge.addTemplate(1, target, 0, 0, 360, 720, .8f, false, false)
            for (width in listOf(64, 28, 135, 42, 64)) {
                frame.eraseColor(Color.rgb(8, 12, 20))
                val rect = Rect(100, 300, 100 + width, 300 + width * 3 / 4)
                Canvas(frame).drawBitmap(target, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                acquire(frame, listOf(rect))
                frame.eraseColor(Color.rgb(8, 12, 20))
                assertFalse("An old location was returned after disappearance", VisionNativeBridge.match(frame).any { it.matched })
            }
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun smallerTargetCanMatchInsideRoiSmallerThanOriginalTemplate() {
        val target = target()
        val frame = Bitmap.createBitmap(180, 240, Bitmap.Config.ARGB_8888)
        val rect = Rect(55, 80, 83, 101)
        try {
            frame.eraseColor(Color.BLACK)
            Canvas(frame).drawBitmap(target, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            Canvas(frame).drawBitmap(target, null, Rect(110, 160, 138, 181), null)
            VisionNativeBridge.addTemplate(1, target, 50, 70, 40, 40, .8f, false, false)
            assertEquals(1, acquire(frame, listOf(rect)).size)
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun transparentScaledTargetIgnoresBackground() {
        val target = target(true)
        val frame = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        val rect = Rect(100, 200, 196, 272)
        try {
            frame.eraseColor(Color.rgb(120, 30, 50))
            Canvas(frame).drawBitmap(target, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            VisionNativeBridge.addTemplate(1, target, 0, 0, 360, 720, .8f, false, false)
            assertEquals(1, acquire(frame, listOf(rect)).size)
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun suppliedScreenshotsUseExistingTemplateWithoutGameSpecificMatching() {
        val args = InstrumentationRegistry.getArguments()
        val presetId = args.getString("fixturePresetId")
        val paths = args.getString("adaptiveScreenshots")?.split(',')
        val forbidden = args.getString("adaptiveForbidden")?.split(':')?.map { entry ->
            entry.split(',').map(String::toInt).let { Rect(it[0], it[1], it[2], it[3]) }
        }
        assumeTrue("Optional external screenshot replay", presetId != null && paths != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preset = Json { ignoreUnknownKeys = true }.decodeFromString<VisionPreset>(
            File(context.filesDir, "vision_presets/$presetId.json").readText())
        val target = BitmapFactory.decodeFile(preset.regions.first().templatePath)
        try {
            for ((index, path) in paths!!.withIndex()) {
                val source = BitmapFactory.decodeFile(path)
                val geometry = VisionCaptureGeometry.create(source.width, source.height, minOf(target.width, target.height), true)
                val frame = Bitmap.createScaledBitmap(source, geometry.captureWidth, geometry.captureHeight, true)
                val scaled = Bitmap.createScaledBitmap(target, geometry.templateWidth(target.width), geometry.templateHeight(target.height), true)
                try {
                    VisionNativeBridge.clearTemplates()
                    VisionNativeBridge.addTemplate(1, scaled, 0, 0, frame.width, frame.height, .8f, false, false)
                    var hits = emptyList<MatchResultNative>()
                    val times = mutableListOf<Long>()
                    repeat(24) {
                        val started = SystemClock.elapsedRealtime()
                        val current = VisionNativeBridge.match(frame).filter { it.matched }
                        val screenHits = current.map(geometry::toScreen)
                        forbidden?.getOrNull(index)?.let { reject ->
                            assertFalse("Non-target matched in $path: $screenHits", screenHits.any {
                                reject.contains(it.x + it.width / 2, it.y + it.height / 2)
                            })
                        }
                        times += SystemClock.elapsedRealtime() - started
                        if (current.size > hits.size) hits = current
                    }
                    Log.i("AdaptiveSizeTest", "Screenshot=$path times=$times screenHits=${hits.map(geometry::toScreen)}")
                    assertTrue("No target reacquired in $path", hits.isNotEmpty())
                } finally {
                    if (frame !== source) frame.recycle()
                    if (scaled !== target) scaled.recycle()
                    source.recycle()
                }
            }
        } finally { target.recycle() }
    }

    @Test fun sameShapeWrongColorAndSolidDistractorsStayRejectedAcrossDiscovery() {
        val target = target()
        val wrong = target()
        val frame = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
            }
            Canvas(wrong).drawBitmap(target, 0f, 0f, paint)
            frame.eraseColor(Color.rgb(8, 12, 20))
            val c = Canvas(frame)
            c.drawBitmap(wrong, null, Rect(10, 20, 42, 44), null)
            c.drawBitmap(wrong, null, Rect(100, 180, 228, 276), null)
            c.drawCircle(100f, 450f, 24f, Paint().apply { color = Color.CYAN })
            VisionNativeBridge.addTemplate(1, target, 0, 0, 360, 720, .8f, false, false)
            repeat(24) { assertFalse("Distractor accepted on scan $it", VisionNativeBridge.match(frame).any { it.matched }) }
        } finally { target.recycle(); wrong.recycle(); frame.recycle() }
    }
}
