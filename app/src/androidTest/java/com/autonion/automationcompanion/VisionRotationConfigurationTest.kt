package com.autonion.automationcompanion

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.core.vision.VisionCaptureGeometry
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisionRotationConfigurationTest {
    @Test fun landscapeStartupAndRoundTripRemapSavedRoiWithoutChangingPreset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captureFile = File.createTempFile("rotation-capture", ".png", context.cacheDir)
        val targetFile = File.createTempFile("rotation-target", ".png", context.cacheDir)
        val type = VisionExecutionService::class.java
        val service = VisionExecutionService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        val metricsType = type.declaredClasses.single { it.simpleName == "CaptureDisplayMetrics" }
        val constructor = metricsType.declaredConstructors.single().apply { isAccessible = true }
        val portrait = constructor.newInstance(200, 400, 160, 0)
        val landscape = constructor.newInstance(400, 200, 160, 1)
        type.getDeclaredField("referenceDisplay").apply { isAccessible = true }.set(service, landscape)
        val configure = type.getDeclaredMethod("configureTemplates", VisionPreset::class.java, metricsType)
            .apply { isAccessible = true }
        fun writeBitmap(file: File, width: Int, height: Int) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        VisionNativeBridge.init()
        try {
            writeBitmap(captureFile, 200, 400)
            writeBitmap(targetFile, 32, 32)
            val region = VisionRegion(1, 40, 120, 32, 32, targetFile.path, color = Color.GREEN,
                sourceCapturePath = captureFile.path, searchX = 20, searchY = 80, searchWidth = 160, searchHeight = 280)
            val fullScreen = region.copy(id = 2, searchX = 0, searchY = 0, searchWidth = 200, searchHeight = 400)
            val preset = VisionPreset(name = "Rotation regression", regions = listOf(region, fullScreen), captureImagePath = captureFile.path)
            for (display in listOf(landscape, portrait, landscape, portrait)) {
                val configured = configure.invoke(service, preset, display)
                val runtime = configured.javaClass.getDeclaredField("preset").apply { isAccessible = true }.get(configured) as VisionPreset
                val geometry = configured.javaClass.getDeclaredField("geometry").apply { isAccessible = true }.get(configured) as VisionCaptureGeometry
                val expected = if (display === landscape) Rect(40, 40, 360, 180) else Rect(20, 80, 180, 360)
                assertEquals(expected, runtime.regions.first().customSearchRect())
                assertEquals(Rect(0, 0, geometry.screenWidth, geometry.screenHeight), runtime.regions.last().customSearchRect())
                assertEquals(Rect(20, 80, 180, 360), preset.regions.first().customSearchRect())
                assertEquals(32, runtime.regions.first().width)
            }
        } finally {
            (type.getDeclaredField("job").apply { isAccessible = true }.get(service) as Job).cancel()
            VisionNativeBridge.release()
            captureFile.delete()
            targetFile.delete()
        }
    }
}
