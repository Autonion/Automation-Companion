package com.autonion.automationcompanion

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
import com.autonion.automationcompanion.features.screen_understanding_ml.core.findStepTarget
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import com.autonion.automationcompanion.features.screen_understanding_ml.ui.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ScreenMlEditorTest {
    @Test fun textTabFindsTargetsAndPreservesSelectionsWhenSwitchingViews() = Data().use { data ->
        var targetId: String? = null
        Editor(data).use { editor ->
            editor.main { editor.model.chooseMode(EditorDisplayMode.TEXT) }
            editor.await { !editor.model.scanning && editor.model.textElements.any { it.text == "Continue" } }
            editor.screenshot("screen-ml-text-outlines.png")
            val recognized = editor.model.textElements.first { it.text == "Continue" }
            editor.main { editor.model.add(recognized) }
            assertEquals(4, editor.model.steps.size)
            assertEquals("ocr", editor.model.steps.last().anchor.source)
            editor.main { editor.model.chooseMode(EditorDisplayMode.SELECTED); editor.model.chooseMode(EditorDisplayMode.TEXT) }
            assertFalse(editor.model.scanning)
            assertEquals(4, editor.model.steps.size)
            assertEquals("Continue", editor.model.steps.last().anchor.text)
            assertEquals(ActionType.SCROLL_DOWN, editor.model.steps[1].actionType)
            targetId = editor.model.steps.last().id
            editor.click("Save")
            editor.await { data.repository.getPreset(data.preset.id)?.steps?.size == 4 }
        }
        Editor(data).use { editor ->
            editor.main { editor.model.chooseMode(EditorDisplayMode.TEXT) }
            editor.await { !editor.model.scanning && editor.model.textElements.any { it.text == "Continue" } }
            val rescanned = editor.model.textElements.first { it.text == "Continue" }
            editor.tapBitmap(rescanned.bounds.centerX(), rescanned.bounds.centerY())
            assertEquals("Rescanning a saved target must not duplicate it", 4, editor.model.steps.size)
            assertEquals(targetId, editor.model.selectedId)
            editor.click("Selected elements")
            editor.screenshot("screen-ml-editor-selected.png")
        }
    }
    @Test fun recaptureRetainsUnsavedTargetsWithoutOverwritingTheSavedPreset() = Data().use { data ->
        var draftPath: String? = null
        Editor(data).use { editor ->
            editor.main {
                editor.model.update(editor.model.steps[0].copy(actionType = ActionType.WAIT))
                editor.model.recapture { draftPath = it }
            }
            editor.await { draftPath != null }
        }
        assertEquals(ActionType.CLICK, data.repository.getPreset(data.preset.id)!!.steps[0].actionType)
        val service = ScreenUnderstandingService()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(service, data.context)
        ScreenUnderstandingService::class.java.getDeclaredMethod("resetEditorDraft", String::class.java)
            .apply { isAccessible = true }.invoke(service, draftPath)
        val previous = ScreenUnderstandingService.instance
        ScreenUnderstandingService.instance = service
        try {
            val intent = Intent(data.context, CaptureEditorActivity::class.java)
                .putExtra("IMAGE_PATH", data.sources[1].absolutePath)
                .putExtra("A11Y_ONLY_MODE", true)
            Editor(data, intent).use { editor ->
                assertEquals(3, editor.model.steps.size)
                assertEquals(ActionType.WAIT, editor.model.steps[0].actionType)
                assertEquals(3, editor.model.pages.size)
                assertEquals(2, editor.model.pageIndex)
                editor.main { editor.model.add(data.preset.steps[2].anchor) }
                assertEquals(4, editor.model.steps.size)
                assertEquals(data.preset.steps.map { it.id }, editor.model.steps.take(3).map { it.id })
                var returned = false
                editor.main {
                    editor.model.rename("Renamed draft")
                    editor.model.recapture { assertNull(it); returned = true }
                }
                editor.await { returned }
                assertEquals("Renamed draft", service.getEditorPreset()!!.name)
                assertEquals(4, service.getEditorPreset()!!.steps.size)
                assertEquals(ActionType.CLICK, data.repository.getPreset(data.preset.id)!!.steps[0].actionType)
            }
        } finally { ScreenUnderstandingService.instance = previous; File(draftPath!!).delete() }
    }

    @Test fun pinchAndFitDoNotAddOrMoveSavedTargets() = Data().use { data ->
        Editor(data).use { editor ->
            val before = editor.model.steps.map { RectF(it.anchor.bounds) }
            editor.pinch()
            editor.await { editor.find("Captured screen")?.stateDescription?.toString()?.contains("zoom 1.0") == false }
            assertEquals(before, editor.model.steps.map { it.anchor.bounds })
            editor.click("Fit image")
            editor.await { editor.find("Captured screen")?.stateDescription?.toString()?.endsWith("zoom 1.0") == true }
            assertEquals(3, editor.model.steps.size)
        }
    }
    @Test fun waitAndSkipSettingsControlAdvancementAfterASearchMiss() = runBlocking {
        val element = UIElement("target", "Text", 1f, RectF(0f, 0f, 20f, 20f), "Ready")
        val step = AutomationStep("step", 0, "Ready", anchor = element)
        var attempts = 0
        assertSame(element, findStepTarget(step, ExecutionMode.STRICT, { true }) {
            if (++attempts == 3) element else null
        })
        assertEquals(3, attempts)
        for ((target, mode) in listOf(step.copy(isOptional = true) to ExecutionMode.STRICT, step to ExecutionMode.FLEXIBLE)) {
            attempts = 0
            assertNull(findStepTarget(target, mode, { true }) { attempts++; null })
            assertEquals(1, attempts)
        }
        var playing = true
        attempts = 0
        assertNull(findStepTarget(step, ExecutionMode.STRICT, { playing }) { attempts++; playing = false; null })
        assertEquals(1, attempts)
    }
    @Test fun snapshotsSurviveCacheRemovalAndAreCleanedUpWithTheirPreset() = Data().use { data ->
        val stored = data.repository.getPreset(data.preset.id)!!
        val paths = stored.steps.mapNotNull { it.captureImagePath }.distinct()
        assertEquals(2, paths.size)
        assertEquals(stored.steps[0].captureImagePath, stored.steps[1].captureImagePath)
        data.sources.forEach { assertTrue(it.delete()) }
        assertTrue(paths.all { File(it).isFile })
        data.repository.savePreset(stored.copy(steps = stored.steps.take(2)))
        assertTrue(File(paths[0]).exists())
        assertFalse("Unused page should be removed after saving", File(paths[1]).exists())
        data.repository.deletePreset(stored.id)
        assertFalse(File(paths[0]).exists())
    }

    @Test fun editingPreservesIdentityMetadataAndCoordinatesAndSurvivesRecreation() = Data().use { data ->
        Editor(data).use { editor ->
            editor.main { editor.model.select(data.preset.steps[1].id) }
            editor.click("Target settings")
            editor.click("Choose action")
            editor.click("Scroll up")
            editor.click("Move earlier")
            editor.replaceText("Ad", "Sponsored")
            editor.await { editor.model.steps.first().anchor.text == "Sponsored" }
            editor.screenshot("screen-ml-editor-settings.png")
            editor.click("Close settings")
            editor.scenario.recreate()
            editor.scenario.onActivity { editor.model = ViewModelProvider(it)[CaptureEditorViewModel::class.java] }
            editor.await { !editor.model.loading && editor.find("Captured screen") != null }
            assertEquals(data.preset.steps[1].id, editor.model.steps.first().id)
            assertEquals("Sponsored", editor.model.steps.first().anchor.text)
            editor.screenshot("screen-ml-editor-portrait.png")
            editor.click("Save")
            editor.await { data.repository.getPreset(data.preset.id)?.steps?.first()?.anchor?.text == "Sponsored" }
            val stored = data.repository.getPreset(data.preset.id)!!
            assertEquals(data.preset.id, stored.id)
            assertEquals(data.preset.createdAt, stored.createdAt)
            assertEquals(data.preset.scope, stored.scope)
            assertEquals(data.preset.targetPackageName, stored.targetPackageName)
            assertEquals(ActionType.SCROLL_UP, stored.steps.first().actionType)
            assertEquals(data.preset.steps[1].anchor.bounds, stored.steps.first().anchor.bounds)
            assertEquals(900f, stored.steps.first().captureScreenWidth)
            assertEquals(listOf(0, 1, 2), stored.steps.map { it.orderIndex })
            assertEquals(1, data.repository.getAllPresets().count { it.id == data.preset.id })
        }
    }

    @Test fun landscapeKeepsDraftAndToolsOutsideTheScreenshot() = Data().use { data ->
        Editor(data).use { editor ->
            editor.main { editor.model.select(data.preset.steps[0].id); editor.model.move(data.preset.steps[0].id, 1) }
            editor.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            editor.await { editor.bounds("Captured screen").width() > editor.bounds("Captured screen").height() }
            editor.settle()
            assertEquals(data.preset.steps[1].id, editor.model.steps.first().id)
            for (label in listOf("Save", "Cancel", "Target settings", "Selected elements", "Fit image", "Next page")) {
                assertFalse("Toolbar overlaps screenshot: $label", Rect.intersects(editor.bounds(label), editor.bounds("Captured screen")))
            }
            editor.screenshot("screen-ml-editor-landscape.png")
            editor.click("Next page")
            editor.await { !editor.model.loading && editor.model.pageIndex == 1 }
            editor.click("Selected elements")
            editor.await { editor.find("Edit target #3") != null }
            editor.click("Edit target #3")
            editor.await { editor.find("Text to enter") != null }
        }
    }

    @Test fun legacyPresetCanBeReviewedAndEditedWithoutScreenshotOrCaptureService() = Data().use { data ->
        data.repository.savePreset(data.preset.copy(steps = data.preset.steps.map { it.copy(captureImagePath = null) }))
        Editor(data).use { editor ->
            editor.await { editor.find("No saved snapshot") != null }
            editor.click("View selected elements")
            editor.click("Edit target #2")
            editor.click("Delete target")
            editor.click("Delete")
            editor.await { editor.model.steps.size == 2 }
            editor.click("Undo")
            assertEquals(3, editor.model.steps.size)
            editor.click("Save")
            editor.await { data.repository.getPreset(data.preset.id)?.steps?.size == 3 }
            assertTrue(data.repository.getPreset(data.preset.id)!!.steps.all { it.captureImagePath == null })
        }
    }

    @Test fun freshCaptureSelectsBottomElementAndKeepsTextModeWhenReviewingFlowTargets() = Data().use { data ->
        val bottom = data.preset.steps[1].anchor
        val intent = Intent(data.context, CaptureEditorActivity::class.java)
            .putExtra("IMAGE_PATH", data.sources[0].absolutePath)
            .putExtra("PRESET_NAME", "Capture fixture")
            .putExtra("A11Y_ONLY_MODE", true)
            .putExtra("ACC_ELEMENTS_DATA", Json.encodeToString(listOf(bottom)))
        Editor(data, intent).use { editor ->
            editor.await { !editor.model.scanning && editor.model.elements.isNotEmpty() }
            editor.screenshot("screen-ml-element-outlines.png")
            editor.tapBitmap(bottom.bounds.centerX(), bottom.bounds.centerY())
            editor.await { editor.model.steps.size == 1 }
            assertEquals(bottom.bounds, editor.model.steps.single().anchor.bounds)
            editor.main { editor.model.chooseMode(EditorDisplayMode.SELECTED) }
            assertEquals(1, editor.model.steps.size)
            editor.click("Selected elements")
            editor.click("Edit target #1")
            editor.await { editor.find("Scroll down") != null || editor.find("Tap") != null }
        }
        val flow = Intent(data.context, CaptureEditorActivity::class.java)
            .putExtra("IMAGE_PATH", data.sources[0].absolutePath)
            .putExtra("PRESET_NAME", "Flow fixture")
            .putExtra(FlowOverlayContract.EXTRA_FLOW_MODE, true)
            .putExtra("EXTRA_FLOW_ML_JSON", Json.encodeToString(listOf(data.preset.steps[1])))
        Editor(data, flow).use { editor ->
            editor.main { editor.model.chooseMode(EditorDisplayMode.SELECTED) }
            assertEquals("TEXT", editor.model.resultMode())
            assertEquals(data.preset.steps[1].id, editor.model.steps.single().id)
        }
    }

    @Test fun dashboardOpensSelectedTargetPreviewAndEditor() = Data().use { data ->
        OnboardingPreferences.getInstance(data.context).markTipSeen("screen_ml")
        ActivityScenario.launch<PresetDashboardActivity>(Intent(data.context, PresetDashboardActivity::class.java)).use {
            val ui = Ui()
            ui.await { ui.find(data.preset.name) != null && ui.find("Capture preview") != null }
            ui.screenshot("screen-ml-preset-cards.png")
            ui.click(data.preset.name)
            ui.await { ui.find("View target #2") != null }
            ui.screenshot("screen-ml-preset-details.png")
            ui.click("View target #2")
            ui.await { ui.find("Saved snapshot with selected target") != null }
            ui.screenshot("screen-ml-target-preview.png")
            ui.click("Edit preset")
            ui.await { ui.find("Selected elements") != null && ui.find("Save") != null }
            ui.click("Cancel")
        }
    }

    private class Data : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = PresetRepository(context)
        val sources = listOf(snapshot("Welcome", Color.rgb(246, 248, 252)), snapshot("Account", Color.rgb(230, 243, 248)))
        val preset = AutomationPreset(name = "Editor test ${UUID.randomUUID().toString().take(8)}", scope = ScopeType.APP_SPECIFIC,
            targetPackageName = "com.example.target", executionMode = ExecutionMode.STRICT, createdAt = 1234,
            steps = listOf(
                step("Continue", RectF(60f, 450f, 840f, 590f), 0, sources[0], "button", "accessibility"),
                step("Ad", RectF(790f, 1660f, 880f, 1740f), 1, sources[0], "Text", "ocr").copy(actionType = ActionType.SCROLL_DOWN),
                step("Email", RectF(60f, 700f, 840f, 820f), 2, sources[1], "EditText", "accessibility")
                    .copy(actionType = ActionType.INPUT_TEXT, inputText = "person@example.com")
            ))
        init { repository.savePreset(preset) }
        private fun step(text: String, bounds: RectF, index: Int, image: File, label: String, source: String) =
            AutomationStep(UUID.randomUUID().toString(), index, label,
                anchor = UIElement(UUID.randomUUID().toString(), label, 1f, bounds, text, source = source),
                captureScreenWidth = 900f, captureScreenHeight = 1800f, captureImagePath = image.absolutePath)
        private fun snapshot(title: String, color: Int): File {
            val bitmap = Bitmap.createBitmap(900, 1800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(25, 40, 58); paint.textSize = 64f
            canvas.drawText(title, 60f, 170f, paint)
            paint.textSize = 30f; canvas.drawText("Choose an option to continue", 60f, 235f, paint)
            paint.color = Color.rgb(42, 111, 227); canvas.drawRoundRect(60f, 450f, 840f, 590f, 16f, 16f, paint)
            paint.color = Color.WHITE; paint.textSize = 48f; canvas.drawText("Continue", 320f, 540f, paint)
            paint.color = Color.WHITE; canvas.drawRoundRect(60f, 700f, 840f, 820f, 12f, 12f, paint)
            paint.color = Color.DKGRAY; canvas.drawText("Email", 85f, 775f, paint)
            paint.color = Color.LTGRAY; canvas.drawRect(0f, 1620f, 900f, 1800f, paint)
            paint.color = Color.DKGRAY; paint.textSize = 44f; canvas.drawText("Ad", 795f, 1710f, paint)
            return File(context.cacheDir, "ml-editor-test-${UUID.randomUUID()}.png").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        override fun close() { repository.deletePreset(preset.id); sources.forEach { it.delete() } }
    }

    private class Editor(data: Data, intent: Intent = Intent(data.context, CaptureEditorActivity::class.java).putExtra("PRESET_ID", data.preset.id)) : Ui(), AutoCloseable {
        val scenario = ActivityScenario.launch<CaptureEditorActivity>(intent)
        lateinit var model: CaptureEditorViewModel
        init {
            scenario.onActivity { model = ViewModelProvider(it)[CaptureEditorViewModel::class.java] }
            await { !model.loading && (find("Captured screen") != null || find("No saved snapshot") != null) }
        }
        fun tapBitmap(x: Float, y: Float) {
            val b = bounds("Captured screen")
            val bitmap = model.bitmap!!
            val scale = minOf(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
            val px = b.centerX() + (x - bitmap.width / 2f) * scale
            val py = b.centerY() + (y - bitmap.height / 2f) * scale
            val down = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
            SystemClock.sleep(40)
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
            settle()
        }
        fun pinch() {
            val b = bounds("Captured screen")
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val down = SystemClock.uptimeMillis()
            val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            fun event(action: Int, spread: Float, pair: Boolean) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, if (pair) 2 else 1, properties,
                    Array(2) { i -> MotionEvent.PointerCoords().apply { x = cx + if (i == 0) -spread else spread; y = cy; pressure = 1f; size = 1f } },
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 40f, false)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f, true)
            for (step in 1..20) { SystemClock.sleep(16); event(MotionEvent.ACTION_MOVE, 40f + step * 4f, true) }
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120f, true)
            event(MotionEvent.ACTION_UP, 120f, false)
            settle()
        }
        override fun close() = scenario.close()
    }

    private open class Ui {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(250) }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Editor state timed out", condition())
        }
        private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                try {
                    if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child)?.let { return it } }
                    return null
                } finally { node.recycle() }
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun find(label: String): AccessibilityNodeInfo? = find { it.contentDescription?.toString() == label ||
            it.text?.toString()?.split("\n")?.contains(label) == true || it.hintText?.toString() == label }
        fun click(label: String) {
            await { find(label) != null }
            var node = find(label)!!
            while (!node.isClickable && node.parent != null) { val parent = node.parent; node.recycle(); node = parent }
            try { assertTrue("Disabled: $label", node.isEnabled); assertTrue("Could not click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            finally { node.recycle() }
            settle()
        }
        fun replaceText(old: String, value: String) {
            val node = find { it.isEditable && it.text?.toString()?.split("\n")?.contains(old) == true }!!
            try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) } finally { node.recycle() }
            settle()
        }
        fun bounds(label: String): Rect {
            val node = find(label) ?: return Rect()
            return Rect().also { node.getBoundsInScreen(it); node.recycle() }
        }
        fun screenshot(name: String) {
            settle()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
