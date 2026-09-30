package com.autonion.automationcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.core.vision.VisionCaptureGeometry
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import java.io.File

@RunWith(AndroidJUnit4::class)
class FastVisionTest {
    @Before fun setup() { VisionNativeBridge.init() }
    @After fun close() { VisionNativeBridge.release() }

    private fun star(color: Int = Color.rgb(108, 245, 25), transparent: Boolean = false): Bitmap =
        Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888).also { bitmap ->
            val c = Canvas(bitmap)
            c.drawColor(if (transparent) Color.TRANSPARENT else Color.BLACK)
            val path = Path()
            repeat(24) { i ->
                val a = i * Math.PI / 12
                val r = if (i % 2 == 0) 35 else 22
                val x = 36f + (cos(a) * r).toFloat()
                val y = 36f + (sin(a) * r).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val p = Paint().apply { this.color = color }
            c.drawPath(path, p)
            p.color = Color.YELLOW
            c.drawRect(28f, 27f, 44f, 43f, p)
            p.color = Color.WHITE
            c.drawRect(31f, 29f, 36f, 34f, p)
        }

    private fun frame(): Bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.BLACK)
    }

    @Test fun crowdedFallingTargetsDetectedOnFirstFrameWithoutTracking() {
        val target = star()
        val frame = frame()
        try {
            VisionNativeBridge.addTemplate(1, target, 0, 0, 1080, 2340, 0.80f, false, false)
            val positions = listOf(100 to 200, 500 to 220, 800 to 360, 300 to 700,
                900 to 850, 100 to 1100, 600 to 1250, 320 to 1600, 720 to 1780, 880 to 1980,
                200 to 450, 268 to 450)
            val times = mutableListOf<Long>()
            repeat(8) { n ->
                frame.eraseColor(Color.BLACK)
                val c = Canvas(frame)
                positions.forEach { (x, y) -> c.drawBitmap(target, x.toFloat(), y + n * 35f, null) }
                val startedAt = SystemClock.elapsedRealtime()
                val hits = VisionNativeBridge.match(frame, 1000L + n * 40).filter { it.matched }
                times += SystemClock.elapsedRealtime() - startedAt
                assertEquals("frame=$n hits=$hits", positions.size, hits.size)
                positions.forEach { (x, y) ->
                    assertTrue("Missing ($x,$y) on frame $n", hits.any {
                        abs(it.x + it.width / 2 - (x + 36)) <= 5 &&
                            abs(it.y + it.height / 2 - (y + n * 35 + 36)) <= 5
                    })
                }
                assertTrue(hits.all { it.observations == 0 })
            }
            Log.i("FastVisionTest", "12 falling targets: median=${times.sorted()[4]}ms max=${times.max()}ms samples=$times")
            assertTrue("Median scan exceeds freshness budget: $times", times.sorted()[4] < 150)
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun exactRoiExcludesOutsideTargetAndRejectsWrongColor() {
        val target = star()
        val wrongColor = star(Color.BLUE)
        val frame = frame()
        try {
            val c = Canvas(frame)
            c.drawBitmap(target, 200f, 200f, null)
            c.drawBitmap(target, 800f, 1900f, null)
            c.drawBitmap(wrongColor, 500f, 1300f, null)
            VisionNativeBridge.addTemplate(1, target, 50, 100, 950, 1500, .85f, false, false)
            val hits = VisionNativeBridge.match(frame).filter { it.matched }
            assertEquals("ROI/color rejection failed: $hits", 1, hits.size)
            assertEquals(236.0, hits.single().x + hits.single().width / 2.0, 4.0)
        } finally { target.recycle(); wrongColor.recycle(); frame.recycle() }
    }

    @Test fun transparentCropMatchesAcrossDifferentBackgrounds() {
        val target = star(transparent = true)
        val frame = frame()
        try {
            val c = Canvas(frame)
            c.drawColor(Color.rgb(75, 15, 90))
            c.drawBitmap(target, 300f, 700f, null)
            VisionNativeBridge.addTemplate(1, target, 100, 300, 800, 1400, .8f, false, false)
            val hits = VisionNativeBridge.match(frame).filter { it.matched }
            assertEquals("Transparent selection must ignore its background: $hits", 1, hits.size)
            assertEquals(736.0, hits.single().y + hits.single().height / 2.0, 4.0)
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun uniformBlackTemplateDoesNotMatchWhiteFrame() {
        val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val frame = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            VisionNativeBridge.addTemplate(1, target, 0, 0, 160, 240, .8f, false, false)
            assertFalse(VisionNativeBridge.match(frame).any { it.matched })
        } finally { target.recycle(); frame.recycle() }
    }

    @Test fun savedCaptureMatchesThroughScaledCaptureCoordinates() {
        val id = InstrumentationRegistry.getArguments().getString("fixturePresetId")
        assumeTrue("Optional local saved-capture fixture", id != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preset = Json { ignoreUnknownKeys = true }.decodeFromString<VisionPreset>(
            File(context.filesDir, "vision_presets/$id.json").readText())
        val region = preset.regions.first()
        val template = BitmapFactory.decodeFile(region.templatePath)
        val original = BitmapFactory.decodeFile(region.sourceCapturePath ?: preset.captureImagePath)
        val geometry = VisionCaptureGeometry.create(original.width, original.height, minOf(template.width, template.height), true)
        val screen = Bitmap.createScaledBitmap(original, geometry.captureWidth, geometry.captureHeight, true)
        val scaled = Bitmap.createScaledBitmap(template, geometry.templateWidth(template.width), geometry.templateHeight(template.height), true)
        try {
            val roi = region.customSearchRect() ?: Rect(0, 0, original.width, original.height)
            val left = geometry.captureX(roi.left)
            val top = geometry.captureY(roi.top)
            VisionNativeBridge.addTemplate(1, scaled, left, top,
                geometry.captureRight(roi.right) - left, geometry.captureBottom(roi.bottom) - top, .8f, false, false)
            val times = mutableListOf<Long>()
            repeat(10) {
                val start = SystemClock.elapsedRealtime()
                val hits = VisionNativeBridge.match(screen).filter { it.matched }.map(geometry::toScreen)
                times += SystemClock.elapsedRealtime() - start
                assertTrue("Saved selected target missing: $hits", hits.any { hit ->
                    abs(hit.x + hit.width / 2 - (region.x + region.width / 2)) <= 8 &&
                        abs(hit.y + hit.height / 2 - (region.y + region.height / 2)) <= 8
                })
            }
            Log.i("FastVisionTest", "Saved capture ${geometry.captureWidth}x${geometry.captureHeight}: median=${times.sorted()[5]}ms max=${times.max()}ms")
        } finally {
            if (screen !== original) screen.recycle()
            if (scaled !== template) scaled.recycle()
            original.recycle()
            template.recycle()
        }
    }
}
