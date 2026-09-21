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
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.core.vision.TapBounds
import com.autonion.automationcompanion.core.vision.predictMovingTap
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class MovingVisionTest {
    @Before fun init() { VisionNativeBridge.init() }
    @After fun close() { VisionNativeBridge.release() }

    private fun target(): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(Color.rgb(163, 115, 59))
        val p = Paint().apply { color = Color.rgb(222, 178, 110); strokeWidth = 7f }
        c.drawLine(3f, 3f, 61f, 61f, p)
        c.drawLine(3f, 61f, 61f, 3f, p)
        p.style = Paint.Style.STROKE
        p.color = Color.rgb(98, 66, 37)
        c.drawRect(3f, 3f, 61f, 61f, p)
    }

    private fun background(): Bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(Color.rgb(187, 35, 39))
        val paint = Paint().apply { color = Color.rgb(30, 176, 225) }
        for (x in 15..420 step 80) for (y in 60..700 step 100) {
            c.drawRect(x.toFloat(), y.toFloat(), x + 45f, y + 70f, paint)
        }
    }

    private fun draw(c: Canvas, target: Bitmap, x: Float, y: Float, angle: Float, scale: Float = 1f) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        c.scale(scale, scale)
        c.drawBitmap(target, -target.width / 2f, -target.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    private fun scan(bitmap: Bitmap, ms: Long): Array<MatchResultNative> = VisionNativeBridge.match(bitmap, ms)

    @Test fun rotatingDuplicatesKeepSeparateTracks() {
        val target = target()
        val background = background()
        try {
            VisionNativeBridge.addTemplate(1, target, 30, 160, 420, 560, 0.8f, false, false, moving = true)
            assertTrue(scan(background, 1000).isEmpty())
            var detected = 0
            val ids = mutableSetOf<Int>()
            val times = mutableListOf<Long>()
            for (i in 1..12) {
                val frame = background.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val y = 220f + i * 28
                    val c = Canvas(frame)
                    draw(c, target, 130f, y, i * 23f)
                    draw(c, target, 345f, y + 15, i * -19f, 0.9f)
                    val start = SystemClock.elapsedRealtime()
                    val hits = scan(frame, 1000L + i * 40)
                    times += SystemClock.elapsedRealtime() - start
                    Log.i("MovingVisionTest", "fixture frame=$i hits=${hits.toList()} ms=${times.last()}")
                    assertEquals("Distinct objects must not share an identity", hits.size, hits.map { it.trackId }.toSet().size)
                    for (x in listOf(130f, 345f)) {
                        val expectedY = y + if (x == 345f) 15 else 0
                        val hit = hits.minByOrNull { hypot(it.x + it.width / 2f - x, it.y + it.height / 2f - expectedY) }
                        if (hit != null && hypot(hit.x + hit.width / 2f - x, hit.y + hit.height / 2f - expectedY) < 24) {
                            detected++
                            ids += hit.trackId
                        }
                    }
                } finally { frame.recycle() }
            }
            assertTrue("Detected $detected/24 targets", detected >= 21)
            assertTrue("Track IDs should persist across rotation: $ids", ids.size <= 4)
            assertTrue("Synthetic scan budget exceeded: $times", times.sorted()[10] < 100)
            Log.i("MovingVisionTest", "Synthetic scan median=${times.sorted()[times.size / 2]}ms max=${times.max()}ms")
        } finally { target.recycle(); background.recycle() }
    }

    @Test fun rejectsStaticBackgroundWrongColorAndSceneChanges() {
        val target = target()
        val background = background()
        try {
            VisionNativeBridge.addTemplate(1, target, 50, 200, 380, 400, 0.8f, false, false, moving = true)
            repeat(5) { assertTrue(scan(background, 1000L + it * 40).isEmpty()) }
            val frame = background.copy(Bitmap.Config.ARGB_8888, true)
            try {
                draw(Canvas(frame), target, 120f, 100f, 0f) // Outside the exact ROI.
                Canvas(frame).drawRect(250f, 300f, 314f, 364f, Paint().apply { color = Color.CYAN })
                assertTrue(scan(frame, 1240).isEmpty())
                Canvas(frame).drawCircle(350f, 450f, 32f, Paint().apply { color = Color.rgb(163, 115, 59) })
                assertTrue("Matching color alone must not identify the textured crate", scan(frame, 1260).isEmpty())
                frame.eraseColor(Color.WHITE)
                assertTrue("Scene change must not produce taps", scan(frame, 1280).isEmpty())
                assertTrue(scan(frame, 1320).isEmpty())
                VisionNativeBridge.resetMotion()
                assertTrue("Resume needs a fresh background", scan(background, 1360).isEmpty())
            } finally { frame.recycle() }
        } finally { target.recycle(); background.recycle() }
    }

    @Test fun rgbaStrideAndStaticDuplicatesRemainCorrect() {
        val target = target()
        val frame = background()
        try {
            draw(Canvas(frame), target, 130f, 300f, 0f)
            draw(Canvas(frame), target, 350f, 420f, 0f)
            VisionNativeBridge.addTemplate(1, target, 40, 200, 400, 400, 0.95f, false, false)
            val packed = ByteBuffer.allocateDirect(frame.byteCount)
            frame.copyPixelsToBuffer(packed)
            packed.rewind()
            val stride = frame.width * 4 + 64
            val padded = ByteBuffer.allocateDirect(stride * frame.height)
            val row = ByteArray(frame.width * 4)
            repeat(frame.height) { y ->
                packed.get(row)
                padded.position(y * stride)
                padded.put(row)
            }
            padded.rewind()
            val hits = VisionNativeBridge.matchRgba(padded, frame.width, frame.height, stride, 1000).filter { it.matched }
            assertEquals(2, hits.size)
            assertTrue(hits.any { kotlin.math.abs(it.x + it.width / 2 - 130) < 3 })
            VisionNativeBridge.clearTemplates()
            VisionNativeBridge.addTemplate(1, target, 0, 0, 10, 10, 0.8f, false, false)
            assertFalse("A too-small ROI must not fall back to the entire screen", scan(frame, 1040).any { it.matched })
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun savedGameFixtureAccuracyAndLatency() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("fixturePresetId")
        assumeTrue("Optional local game fixture", id != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preset = Json { ignoreUnknownKeys = true }.decodeFromString<VisionPreset>(File(context.filesDir, "vision_presets/$id.json").readText())
        val region = preset.regions.first()
        val target = BitmapFactory.decodeFile(region.templatePath)
        val background = BitmapFactory.decodeFile(region.sourceCapturePath ?: preset.captureImagePath)
        val roi = region.customSearchRect() ?: Rect(0, 0, background.width, background.height)
        try {
            VisionNativeBridge.addTemplate(1, target, roi.left, roi.top, roi.width(), roi.height(), 0.8f, false, false, moving = true)
            assertTrue(scan(background, 1000).isEmpty())
            val times = mutableListOf<Long>()
            var detected = 0
            for (i in 1..10) {
                val frame = background.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val y = roi.top + target.height * 0.75f + i * 17
                    val c = Canvas(frame)
                    val positions = listOf(roi.left + roi.width() * 0.25f, roi.left + roi.width() * 0.75f)
                    positions.forEach { x -> draw(c, target, x, y, i * 27f) }
                    val start = SystemClock.elapsedRealtime()
                    val hits = scan(frame, 1000L + i * 40)
                    times += SystemClock.elapsedRealtime() - start
                    positions.forEach { x ->
                        if (hits.any { hypot(it.x + it.width / 2f - x, it.y + it.height / 2f - y) < target.width * 0.3f }) detected++
                    }
                    Log.i("MovingVisionTest", "Game fixture frame=$i hits=${hits.size} ms=${times.last()}")
                } finally { frame.recycle() }
            }
            Log.i("MovingVisionTest", "Game fixture detected=$detected/20 median=${times.sorted()[5]}ms max=${times.max()}ms")
            assertTrue("Game fixture detected $detected/20", detected >= 18)
            assertTrue("Game scan budget exceeded: $times", times.sorted()[8] < 150)
            // Restore the larger fall path. Detection work should stay local
            // to the two moving objects even though the search area grows.
            val fallArea = Rect(roi.left, roi.top, roi.right, background.height - 200)
            VisionNativeBridge.clearTemplates()
            VisionNativeBridge.addTemplate(1, target, fallArea.left, fallArea.top, fallArea.width(), fallArea.height(), 0.8f, false, false, moving = true)
            assertTrue(scan(background, 2000).isEmpty())
            val fallTimes = mutableListOf<Long>()
            var predictedHits = 0
            for (i in 1..10) {
                val frame = background.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val y = fallArea.top + target.height * 0.75f + i * 60
                    val x = fallArea.left + fallArea.width() * 0.5f
                    draw(Canvas(frame), target, x, y, i * 27f)
                    val start = SystemClock.elapsedRealtime()
                    val hits = scan(frame, 2000L + i * 40)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    fallTimes += elapsed
                    if (i >= 3) {
                        val tap = hits.mapNotNull { predictMovingTap(it, 2000L + i * 40, 2000L + i * 40 + elapsed,
                            TapBounds(fallArea.left, fallArea.top, fallArea.right, fallArea.bottom)) }.firstOrNull()
                        val futureY = y + (elapsed + 35) * 1.5f
                        if (tap != null && hypot(tap.x - x, tap.y - futureY) < region.width * 0.4f) predictedHits++
                    }
                } finally { frame.recycle() }
            }
            Log.i("MovingVisionTest", "Large ROI=${fallArea.width()}x${fallArea.height()} speed=1500px/s predictedHits=$predictedHits/8 median=${fallTimes.sorted()[5]}ms max=${fallTimes.max()}ms")
            assertTrue("Predicted hit rate on fast fall path: $predictedHits/8", predictedHits >= 7)
        } finally { target.recycle(); background.recycle() }
    }
}
