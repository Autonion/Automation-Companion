package com.autonion.automationcompanion

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.visual_trigger.models.*
import com.autonion.automationcompanion.features.visual_trigger.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VisionEditorLayoutTest {
    private class Fixture(val sourceWidth: Int = 1080, val sourceHeight: Int = 2340) : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val source = File(context.cacheDir, "editor-ux-${UUID.randomUUID()}.png")
        val scenario: ActivityScenario<VisionEditorActivity>
        lateinit var model: VisionEditorViewModel
        val background = Color.rgb(62, 72, 82)

        init {
            assumeTrue("Unlock the phone for editor UI tests",
                !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(background)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = Color.rgb(255, 194, 65) }
            canvas.drawRect(100f, 400f, 300f, 600f, paint)
            paint.color = Color.rgb(64, 172, 245)
            canvas.drawRect(650f, 1200f, 850f, 1400f, paint)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            scenario = ActivityScenario.launch(Intent(context, VisionEditorActivity::class.java)
                .putExtra("IMAGE_PATH", source.absolutePath))
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                model = ViewModelProvider(it)[VisionEditorViewModel::class.java]
            }
            await { model.fullResWidth.value == sourceWidth && find("Captured screen") != null }
            settle()
        }

        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(400) }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Editor state timed out", condition())
        }
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            node.refresh()
            return listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
        }
        fun find(label: String): AccessibilityNodeInfo? =
            automation.rootInActiveWindow?.let(::nodes)?.firstOrNull {
                it.contentDescription?.toString() == label || it.text?.toString()?.split("\n")?.contains(label) == true
            }
        fun node(label: String): AccessibilityNodeInfo {
            await { find(label) != null }
            return find(label)!!
        }
        fun click(label: String) {
            var item = node(label)
            while (!item.isClickable && item.parent != null) item = item.parent
            item.refresh()
            if (!item.isEnabled) {
                screenshot("editor-ux-failed.png")
                android.util.Log.e("EditorUxTest", "Disabled $label: ${find("Captured screen")?.stateDescription}")
            }
            assertTrue("Disabled: $label", item.isEnabled)
            assertTrue("Could not click $label", item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            settle()
        }
        fun selected(label: String): Boolean {
            var item = node(label)
            item.refresh()
            while (!item.isSelected && !item.isChecked && !item.isClickable && item.parent != null) {
                item = item.parent
                item.refresh()
            }
            return item.isSelected || item.isChecked
        }
        fun bounds(label: String) = Rect().also { node(label).getBoundsInScreen(it) }
        fun event(down: Long, time: Long, action: Int, x: Float, y: Float) {
            MotionEvent.obtain(down, time, action, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        }
        fun tap(x: Float, y: Float, long: Boolean = false) {
            val b = bounds("Captured screen")
            val px = b.left + b.width() * x
            val py = b.top + b.height() * y
            val down = SystemClock.uptimeMillis()
            event(down, down, MotionEvent.ACTION_DOWN, px, py)
            SystemClock.sleep(if (long) 750 else 60)
            event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py)
            settle()
        }
        fun drag(x: Float, y: Float, endX: Float, endY: Float) {
            val b = bounds("Captured screen")
            val down = SystemClock.uptimeMillis()
            event(down, down, MotionEvent.ACTION_DOWN, b.left + b.width() * x, b.top + b.height() * y)
            for (step in 1..20) {
                SystemClock.sleep(16)
                val t = step / 20f
                event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    b.left + b.width() * (x + (endX - x) * t), b.top + b.height() * (y + (endY - y) * t))
            }
            event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                b.left + b.width() * endX, b.top + b.height() * endY)
            settle()
        }
        fun targets() {
            main {
                model.addRegion(Rect(100, 400, 300, 600))
                model.addRegion(Rect(650, 1200, 850, 1400))
            }
            settle()
        }
        fun screenshot(name: String) {
            val image = automation.takeScreenshot()
            File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun yellowPixels(): Int {
            val image = automation.takeScreenshot()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            image.recycle()
            return pixels.count { Color.red(it) > 220 && Color.green(it) in 160..220 && Color.blue(it) < 100 }
        }

        fun pinch() {
            val b = bounds("Captured screen")
            val cx = b.left + b.width() * 200f / sourceWidth
            val cy = b.top + b.height() * 500f / sourceHeight
            val down = SystemClock.uptimeMillis()
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            fun pair(action: Int, spread: Float) {
                val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
                    x = cx + if (index == 0) -spread else spread
                    y = cy; pressure = 1f; size = 1f
                } }
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                    instrumentation.sendPointerSync(it); it.recycle()
                }
            }
            event(down, down, MotionEvent.ACTION_DOWN, cx - 40f, cy)
            pair(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f)
            for (step in 1..20) {
                SystemClock.sleep(16)
                pair(MotionEvent.ACTION_MOVE, 40f + step * 3f)
            }
            pair(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f)
            event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cx - 100f, cy)
            settle()
        }
        fun clearCanvasCheck() {
            val imageBounds = bounds("Captured screen")
            assertEquals(sourceWidth.toFloat() / sourceHeight, imageBounds.width().toFloat() / imageBounds.height(), 0.005f)
            listOf("Cancel", "Undo", "Recapture", "Fit image", "Run behavior", "Save",
                "Target settings", "Edit search area", "Rotate target").forEach {
                val tool = bounds(it)
                assertFalse("$it must not cover the screenshot", Rect.intersects(tool, imageBounds))
                assertTrue("$it has empty bounds", tool.width() > 0 && tool.height() > 0)
            }
            val bitmap = automation.takeScreenshot()
            val pixel = bitmap.getPixel((imageBounds.left + imageBounds.width() * 0.4f).toInt(),
                (imageBounds.top + imageBounds.height() * 0.6f).toInt())
            assertEquals("Entire screen must not tint the capture", background, pixel)
            bitmap.recycle()
        }
        override fun close() { scenario.close(); source.delete() }
    }

    @Test fun portraitEdgesCanBeDrawnAndEntireScreenIsNotPainted() = Fixture().use { f ->
        f.drag(0.05f, 0.015f, 0.28f, 0.12f)
        assertEquals(1, f.model.regions.value.size)
        assertTrue(f.model.regions.value.single().rect.top < 100)
        assertEquals(Rect(0, 0, 1080, 2340), f.model.regions.value.single().searchRect)
        f.tap(0.9f, 0.6f)
        f.drag(0.70f, 0.87f, 0.94f, 0.98f)
        assertEquals(2, f.model.regions.value.size)
        assertTrue(f.model.regions.value.last().rect.bottom > 2250)
        f.clearCanvasCheck()
        f.screenshot("editor-ux-portrait.png")
    }

    @Test fun longPressSelectsCorrectTargetAndActionStateSurvivesReopening() = Fixture().use { f ->
        f.targets()
        f.tap(200f / 1080, 500f / 2340)
        f.tap(750f / 1080, 1300f / 2340, long = true)
        f.node("Target #2")
        assertTrue(f.selected("Entire screen"))
        assertTrue(f.selected("Fast match"))
        f.click("Choose action")
        f.click("Long press")
        assertEquals(VisionAction.LongClick, f.model.regions.value[1].action)
        f.click("Close settings")
        assertEquals("Target #2 selected", f.node("Captured screen").stateDescription.toString())
        f.click("Target settings")
        f.node("Long press")
        assertEquals(VisionAction.Click, f.model.regions.value[0].action)
        f.screenshot("editor-ux-target-settings.png")
    }

    @Test fun customAreaSupportsCancelAndResetWithCorrectSelection() = Fixture().use { f ->
        f.targets()
        f.tap(200f / 1080, 500f / 2340)
        f.click("Target settings")
        f.click("Custom area")
        f.click("Cancel search area")
        assertEquals(Rect(0, 0, 1080, 2340), f.model.regions.value.first().searchRect)
        f.click("Target settings")
        f.click("Custom area")
        f.drag(0.10f, 0.30f, 0.70f, 0.80f)
        assertEquals(2, f.model.regions.value.size)
        assertTrue(f.model.regions.value.first().searchRect!!.width() < 1080)
        f.screenshot("editor-ux-custom-area.png")
        f.tap(750f / 1080, 1300f / 2340)
        f.clearCanvasCheck()
        f.tap(200f / 1080, 500f / 2340)
        f.click("Target settings")
        assertTrue(f.selected("Custom area"))
        assertFalse(f.selected("Entire screen"))
        f.click("Entire screen")
        assertEquals(Rect(0, 0, 1080, 2340), f.model.regions.value.first().searchRect)
        f.screenshot("editor-ux-search-reset.png")
        assertTrue(f.selected("Entire screen"))
        assertFalse(f.selected("Custom area"))
        f.click("Close settings")
        f.clearCanvasCheck()
    }

    @Test fun runBehaviorChoicesPreserveExistingModeSemantics() = Fixture().use { f ->
        f.targets()
        f.click("Run behavior")
        assertTrue(f.selected("React to matches"))
        f.click("Concurrent")
        assertEquals(TapDispatchMode.CONCURRENT, f.model.tapDispatchMode.value)
        f.click("Follow a sequence")
        assertEquals(ExecutionMode.MANDATORY_SEQUENTIAL, f.model.executionMode.value)
        assertTrue(f.selected("Wait"))
        assertNull(f.find("Tap delivery"))
        f.click("Skip")
        assertEquals(ExecutionMode.OPTIONAL_SEQUENTIAL, f.model.executionMode.value)
        f.screenshot("editor-ux-run-settings.png")
        f.click("Close settings")
        f.click("Run behavior")
        assertTrue(f.selected("Skip"))
        f.click("React to matches")
        assertEquals(ExecutionMode.DETECT_ONLY, f.model.executionMode.value)
        assertTrue(f.selected("Concurrent"))
    }

    @Test fun landscapePreservesUnsavedTargetsAndKeepsToolsOutsideCanvas() = Fixture().use { f ->
        f.targets()
        f.tap(200f / 1080, 500f / 2340)
        f.main { f.model.updateRegionRotation(1, 15f) }
        f.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        f.await { f.context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        f.settle()
        f.clearCanvasCheck()
        assertEquals(2, f.model.regions.value.size)
        assertEquals(15f, f.model.regions.value.first().rotationDegrees, 0.001f)
        f.screenshot("editor-ux-landscape.png")
        f.click("Target settings")
        assertTrue(f.selected("Entire screen"))
    }

    @Test fun savedSearchScopesAreRecognizedWithoutChangingLegacyBehavior() {
        assertTrue(isEntireScreenSearch(null, 1080, 2340, ExecutionMode.DETECT_ONLY))
        assertFalse(isEntireScreenSearch(null, 1080, 2340, ExecutionMode.OPTIONAL_SEQUENTIAL))
        assertTrue(isEntireScreenSearch(Rect(0, 0, 1080, 2340), 1080, 2340, ExecutionMode.MANDATORY_SEQUENTIAL))
        assertFalse(isEntireScreenSearch(Rect(0, 0, 500, 600), 1080, 2340, ExecutionMode.DETECT_ONLY))
    }

    @Test fun wideCaptureFitsLandscapeWithoutBlockingImageEdges() = Fixture(2340, 1080).use { f ->
        f.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        f.await { f.context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        f.settle()
        f.drag(0.72f, 0.015f, 0.93f, 0.23f)
        assertEquals(1, f.model.regions.value.size)
        assertTrue(f.model.regions.value.single().rect.top < 70)
        f.clearCanvasCheck()
        f.screenshot("editor-ux-wide-landscape.png")
        val centerX = f.model.regions.value.single().rect.exactCenterX() / f.sourceWidth
        val handleY = 20f / f.bounds("Captured screen").height()
        f.drag(centerX, handleY, centerX + 0.05f, handleY + 0.12f)
        assertTrue("Top-edge rotation handle must remain usable", kotlin.math.abs(f.model.regions.value.single().rotationDegrees) > 5f)
    }

    @Test fun pinchZoomAndFitImageDoNotCreateOrMoveTargets() = Fixture().use { f ->
        val before = f.yellowPixels()
        assertTrue(before > 1000)
        f.pinch()
        assertTrue("Pinch must visibly zoom the screenshot", f.yellowPixels() > before * 2)
        assertTrue(f.model.regions.value.isEmpty())
        f.screenshot("editor-ux-zoom.png")
        f.click("Fit image")
        assertEquals(before.toFloat(), f.yellowPixels().toFloat(), before * 0.02f)
        assertTrue(f.model.regions.value.isEmpty())
        f.clearCanvasCheck()
    }
}
