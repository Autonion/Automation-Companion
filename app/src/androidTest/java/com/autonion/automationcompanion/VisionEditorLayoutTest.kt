package com.autonion.automationcompanion

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorActivity
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorViewModel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisionEditorLayoutTest {
    @Test fun targetSettingsAndSaveRemainOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("fixturePresetId")
        assumeTrue(id != null)
        val context = instrumentation.targetContext
        assumeTrue("Unlock the phone for the layout check", !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val automation = instrumentation.uiAutomation
        val preset = Json { ignoreUnknownKeys = true }.decodeFromString<VisionPreset>(File(context.filesDir, "vision_presets/$id.json").readText())
        val intent = Intent(context, VisionEditorActivity::class.java).putExtra("PRESET_ID", id)
        ActivityScenario.launch<VisionEditorActivity>(intent).use { scenario ->
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 5000
            var targetX = 0f
            var targetY = 0f
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[VisionEditorViewModel::class.java]
                    val content = activity.findViewById<android.view.View>(android.R.id.content)
                    if (model.regions.value.isNotEmpty() && model.fullResWidth.value > 0 && content.height > 0) {
                        val xy = IntArray(2)
                        content.getLocationOnScreen(xy)
                        val scale = minOf(content.width.toFloat() / model.fullResWidth.value, content.height.toFloat() / model.fullResHeight.value)
                        targetX = xy[0] + preset.regions.first().toRect().exactCenterX() * scale
                        targetY = xy[1] + preset.regions.first().toRect().exactCenterY() * scale
                        loaded = true
                    }
                }
                SystemClock.sleep(50)
            }
            assertTrue("Editor failed to load", loaded)
            instrumentation.waitForIdleSync()
            val down = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, targetX, targetY, 0).also {
                instrumentation.sendPointerSync(it); it.recycle()
            }
            MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, targetX, targetY, 0).also {
                instrumentation.sendPointerSync(it); it.recycle()
            }
            SystemClock.sleep(400)
            fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(root) + (0 until root.childCount).flatMap { index -> root.getChild(index)?.let { nodes(it) }.orEmpty() }
            fun snapshot(name: String) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun root(): AccessibilityNodeInfo {
                val until = SystemClock.uptimeMillis() + 3000
                while (SystemClock.uptimeMillis() < until) {
                    automation.rootInActiveWindow?.let { return it }
                    SystemClock.sleep(50)
                }
                throw AssertionError("Editor accessibility tree unavailable")
            }
            val toolbar = nodes(root())
            val settings = toolbar.firstOrNull { it.contentDescription?.contains("Target settings") == true || it.text?.toString() == "Fast match" }
            assertNotNull("Target settings must be discoverable", settings)
            val save = toolbar.firstOrNull { it.contentDescription?.toString() == "Save" }
            assertNotNull("Save must be visible", save)
            val bounds = Rect()
            save!!.getBoundsInScreen(bounds)
            assertTrue(bounds.width() > 0 && bounds.right <= context.resources.displayMetrics.widthPixels)
            snapshot("moving-editor-toolbar.png")
            var clickable = settings!!
            while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(650)
            snapshot("moving-editor-dialog.png")
            val dialog = nodes(root())
            val moving = dialog.firstOrNull { it.text?.toString() == "Rotating" }
            assertNotNull("Rotating mode must be visible", moving)
            var movingButton = moving!!
            while (!movingButton.isClickable && movingButton.parent != null) movingButton = movingButton.parent
            assertTrue(movingButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(350)
            snapshot("moving-editor-settings.png")
            // Closing the activity discards these unsaved configuration edits.
        }
    }
}
