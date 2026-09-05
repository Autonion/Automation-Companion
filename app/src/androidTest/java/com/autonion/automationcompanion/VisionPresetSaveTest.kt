package com.autonion.automationcompanion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VisionPresetSaveTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repository = VisionRepository(app)
        val key = UUID.randomUUID().toString()
        val source = File(app.cacheDir, "save-test-$key.png")
        val ids = mutableSetOf<String>()
        val stores = mutableListOf<ViewModelStore>()

        init {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

        fun model(): Pair<VisionEditorViewModel, ViewModelStore> {
            lateinit var model: VisionEditorViewModel
            val store = ViewModelStore()
            stores.add(store)
            main {
                model = VisionEditorViewModel(app)
                store.put("editor", model)
            }
            return model to store
        }

        fun ready(model: VisionEditorViewModel) = await {
            model.imageBitmap.value != null && model.fullResWidth.value > 0
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("Timed out waiting for the editor", condition())
        }

        fun existing(): VisionPreset {
            val preset = VisionPreset(
                id = key, name = "Save test $key", captureImagePath = source.absolutePath,
                regions = listOf(VisionRegion.fromRect(
                    id = 1, rect = Rect(0, 0, 96, 96), templatePath = source.absolutePath,
                    action = VisionAction.Click, color = Color.GREEN,
                    sourceCapturePath = source.absolutePath
                ))
            )
            ids.add(preset.id)
            runBlocking { repository.savePreset(preset) }
            return preset
        }

        override fun close() {
            main { stores.forEach { it.clear() } }
            ids.forEach { id ->
                runBlocking { repository.deletePreset(id) }
                File(app.filesDir, "vision_presets/$id.json.new").delete()
                app.filesDir.listFiles()?.filter {
                    it.name.startsWith("viz_${id}_") || it.name.startsWith("viz_capture_${id}_")
                }?.forEach { it.delete() }
            }
            source.delete()
        }
    }

    @Test fun rapidSaveAndEditorCloseCreateOnePresetAndPreserveMovingSettings() = Fixture().use { f ->
        val (model, store) = f.model()
        f.main { model.loadImage(f.source.absolutePath) }
        f.ready(model)
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val savedId = AtomicReference<String>()
        f.main {
            model.addRegion(Rect(0, 0, 96, 96))
            model.updateRegionMatchMode(1, VisionMatchMode.MOVING)
            model.updateRegionSearchRect(1, Rect(0, 0, 96, 96))
            model.updateRegionThreshold(1, 0.85f)
            model.updateRegionTapLead(1, 55)
            repeat(8) {
                model.savePreset("New save test ${f.key}") { id ->
                    savedId.set(id)
                    callbacks.incrementAndGet()
                    store.clear()
                    completed.countDown()
                }
            }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        val id = savedId.get()
        f.ids.add(id)
        assertEquals(1, callbacks.get())
        assertNull(model.saveError.value)
        val preset = runBlocking { f.repository.getPreset(id) }!!
        val region = preset.regions.single()
        assertEquals(VisionMatchMode.MOVING, region.matchMode)
        assertEquals(0.85f, region.movingMatchThreshold, 0.001f)
        assertEquals(55, region.tapLeadMs)
        assertEquals(Rect(0, 0, 96, 96), region.customSearchRect())
        assertTrue(File(region.templatePath).length() > 0)
        val (reopened, _) = f.model()
        f.main { reopened.loadExistingPreset(id) { assertTrue(it) } }
        f.ready(reopened)
        assertEquals(VisionMatchMode.MOVING, reopened.regions.value.single().matchMode)
    }

    @Test fun freshCaptureValidationDoesNotSilentlySkipMissingMovingRoi() = Fixture().use { f ->
        val (model, _) = f.model()
        f.main { model.loadImage(f.source.absolutePath) }
        f.ready(model)
        f.main {
            model.addRegion(Rect(10, 10, 50, 50))
            model.updateRegionMatchMode(1, VisionMatchMode.MOVING)
            model.savePreset("Missing ROI ${f.key}") { fail("Invalid preset was saved") }
        }
        assertEquals("Select a search area for each moving object", model.saveError.value)
        assertFalse(model.isSaving.value)
    }

    @Test fun failedCommitPreservesExistingPresetAndAllowsRetry() = Fixture().use { f ->
        val original = f.existing()
        val json = File(f.app.filesDir, "vision_presets/${original.id}.json")
        val before = json.readBytes()
        val originalImage = f.source.readBytes()
        val (model, _) = f.model()
        f.main { model.loadExistingPreset(original.id) { assertTrue(it) } }
        f.ready(model)
        // Force AtomicFile.startWrite to fail without touching the published preset.
        val blockedOutput = File(f.app.filesDir, "vision_presets/${original.id}.json.new")
        assertTrue(blockedOutput.mkdir())
        f.main { model.savePreset("New Automation") { fail("Save should fail") } }
        f.await { model.saveError.value != null }
        assertArrayEquals(before, json.readBytes())
        assertArrayEquals(originalImage, f.source.readBytes())
        assertFalse(model.isSaving.value)
        assertFalse(f.app.filesDir.listFiles()!!.any { it.name.startsWith("viz_${original.id}_") })
        assertTrue(blockedOutput.delete())
        val completed = CountDownLatch(1)
        f.main {
            model.dismissSaveError()
            model.savePreset("New Automation") { completed.countDown() }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertNull(model.saveError.value)
        assertEquals(original.name, runBlocking { f.repository.getPreset(original.id) }!!.name)
    }

    @Test fun newPresetDoesNotModifyExistingAndMissingAppendTargetFailsClearly() = Fixture().use { f ->
        val original = f.existing()
        val before = File(f.app.filesDir, "vision_presets/${original.id}.json").readBytes()
        val (model, _) = f.model()
        f.main { model.loadImage(f.source.absolutePath) }
        f.ready(model)
        val completed = CountDownLatch(1)
        val savedId = AtomicReference<String>()
        f.main {
            model.addRegion(Rect(10, 10, 50, 50))
            model.savePreset("Different preset ${f.key}") {
                savedId.set(it)
                completed.countDown()
            }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        f.ids.add(savedId.get())
        assertNotEquals(original.id, savedId.get())
        assertArrayEquals(before, File(f.app.filesDir, "vision_presets/${original.id}.json").readBytes())

        val (append, _) = f.model()
        f.main {
            append.prepareForAppend("missing-${f.key}")
            append.loadImage(f.source.absolutePath)
        }
        f.ready(append)
        f.main {
            append.addRegion(Rect(10, 10, 50, 50))
            append.savePreset(original.name) { fail("Missing destination must not create a preset") }
        }
        f.await { append.saveError.value != null }
        assertTrue(append.saveError.value!!.contains("no longer exists"))
    }

    @Test fun intentionalAppendAndMultiPageEditKeepTheDestinationAndBothPages() = Fixture().use { f ->
        Fixture().use { second ->
            val original = f.existing()
            val (append, _) = f.model()
            f.main {
                append.prepareForAppend(original.id)
                append.loadImage(second.source.absolutePath)
            }
            f.ready(append)
            val appended = CountDownLatch(1)
            f.main {
                append.addRegion(Rect(10, 10, 60, 60))
                append.savePreset(original.name) { assertEquals(original.id, it); appended.countDown() }
            }
            assertTrue(appended.await(10, TimeUnit.SECONDS))
            assertEquals(2, runBlocking { f.repository.getPreset(original.id) }!!.regions.size)

            val (edit, _) = f.model()
            f.main { edit.loadExistingPreset(original.id) { assertTrue(it) } }
            f.ready(edit)
            assertEquals(2, edit.capturePages.value.size)
            f.main {
                edit.updateRegionThreshold(1, 0.9f)
                edit.navigateToPage(1)
            }
            f.await { edit.regions.value.singleOrNull()?.id == 2 }
            val edited = CountDownLatch(1)
            f.main {
                edit.updateRegionMatchMode(2, VisionMatchMode.MOVING)
                edit.updateRegionSearchRect(2, Rect(0, 0, 96, 96))
                edit.savePreset("New Automation") { edited.countDown() }
            }
            assertTrue(edited.await(10, TimeUnit.SECONDS))
            val saved = runBlocking { f.repository.getPreset(original.id) }!!
            assertEquals(original.name, saved.name)
            assertEquals(2, saved.regions.size)
            assertEquals(0.9f, saved.regions.first { it.id == 1 }.matchThreshold, 0.001f)
            assertEquals(VisionMatchMode.MOVING, saved.regions.first { it.id == 2 }.matchMode)
            saved.regions.forEach {
                assertTrue(File(it.templatePath).length() > 0)
                assertTrue(File(it.sourceCapturePath!!).length() > 0)
            }
        }
    }

    @Test fun flowSavePreservesSettingsWithoutCreatingStandalonePreset() = Fixture().use { f ->
        val (model, _) = f.model()
        f.main { model.loadImage(f.source.absolutePath) }
        f.ready(model)
        val completed = CountDownLatch(1)
        val savedFile = AtomicReference<String>()
        f.main {
            model.addRegion(Rect(5, 5, 65, 65))
            model.updateRegionMatchMode(1, VisionMatchMode.MOVING)
            model.updateRegionSearchRect(1, Rect(0, 0, 96, 96))
            model.saveForFlowMode(f.key) { savedFile.set(it); completed.countDown() }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        val jsonFile = File(savedFile.get())
        val preset = Json.decodeFromString<VisionPreset>(jsonFile.readText())
        try {
            assertEquals(VisionMatchMode.MOVING, preset.regions.single().matchMode)
            assertNull(runBlocking { f.repository.getPreset(preset.id) })
            assertTrue(File(preset.regions.single().templatePath).length() > 0)
        } finally {
            preset.regions.forEach { File(it.templatePath).delete() }
            File(preset.captureImagePath!!).delete()
            jsonFile.delete()
        }
    }
}
