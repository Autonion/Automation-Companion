package com.autonion.automationcompanion

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.ImageWriter
import android.media.projection.MediaProjectionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.visual_trigger.core.VisionFrame
import com.autonion.automationcompanion.features.visual_trigger.core.VisionMediaProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionCaptureResizeTest {
    @Test fun resizeAndStopDoNotInvalidateAnInFlightFrame() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projection = VisionMediaProjection(context,
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
        val type = VisionMediaProjection::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val slot = type.getDeclaredMethod("createReader", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(projection, 32, 64)
        val reader = slot.javaClass.getDeclaredField("reader").apply { isAccessible = true }.get(slot) as ImageReader
        field("readerSlot").set(projection, slot)
        val surface = reader.surface
        val writer = ImageWriter.newInstance(surface, 2)
        // A private virtual display exercises resize/setSurface without capturing
        // the user's screen, requesting projection consent, or injecting taps.
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "VisionResizeRegression", 32, 64, 160, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        assertNotNull(display)
        field("virtualDisplay").set(projection, display)
        val held = CompletableDeferred<VisionFrame>()
        val release = CompletableDeferred<Unit>()
        val consumer = launch(Dispatchers.Default) {
            projection.frames.take(1).collect { frame -> held.complete(frame); release.await() }
        }
        try {
            val image = writer.dequeueInputImage()
            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
            val frame = withTimeout(5000) { held.await() }
            val generation = frame.captureGeneration
            assertEquals(generation + 1, projection.resizeCapture(64, 32, 160))
            assertEquals(generation + 2, projection.resizeCapture(32, 64, 160))
            projection.stopProjection()
            assertTrue("Retired reader must stay valid until native finishes reading", surface.isValid)
            assertNotNull(frame.image.planes[0].buffer)
            assertEquals(32, frame.width)
            release.complete(Unit)
            withTimeout(5000) { consumer.join() }
            assertFalse("Retired reader must close after its last frame is released", surface.isValid)
        } finally {
            release.complete(Unit)
            consumer.cancelAndJoin()
            writer.close()
            projection.stopProjection()
        }
    }
}
