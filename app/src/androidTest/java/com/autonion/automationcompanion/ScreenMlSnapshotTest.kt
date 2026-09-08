package com.autonion.automationcompanion

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.ImageWriter
import android.media.projection.MediaProjectionManager
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.screen_understanding_ml.core.MediaProjectionCore
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
class ScreenMlSnapshotTest {
    @Test fun snapshotDoesNotWaitForInferenceOrReturnItsOldFrame() = runBlocking {
        val fixture = Fixture()
        val held = CompletableDeferred<Bitmap>()
        val releaseInference = CompletableDeferred<Unit>()
        val inference = launch(Dispatchers.Default) {
            fixture.core.screenCaptureFlow.take(1).collect { bitmap ->
                try {
                    held.complete(bitmap)
                    releaseInference.await()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        try {
            fixture.queueOldRedFrame()
            val old = withTimeout(5000) { held.await() }
            assertEquals(Color.RED, old.getPixel(16, 32))
            val snapshot = withTimeout(5000) { fixture.core.captureFreshBitmap(4000) }
            assertNotNull("Snapshot must complete while inference is still blocked", snapshot)
            try {
                assertFalse(releaseInference.isCompleted)
                assertEquals("Must capture the current display, not the held red frame", Color.BLUE, snapshot!!.getPixel(16, 32))
                assertEquals("Refreshing the surface must not invalidate an in-flight frame", Color.RED, old.getPixel(16, 32))
            } finally {
                snapshot?.recycle()
            }
        } finally {
            releaseInference.complete(Unit)
            inference.cancelAndJoin()
            fixture.close()
        }
    }

    @Test fun repeatedSnapshotsRefreshAStaticDisplayWithoutCreatingAnotherProjection() = runBlocking {
        val fixture = Fixture()
        try {
            repeat(2) {
                val snapshot = withTimeout(5000) { fixture.core.captureFreshBitmap(4000) }
                assertNotNull("An unchanged screen must still produce a snapshot", snapshot)
                try {
                    assertEquals(Color.BLUE, snapshot!!.getPixel(16, 32))
                    assertSame(fixture.display, fixture.field("virtualDisplay").get(fixture.projection))
                } finally {
                    snapshot?.recycle()
                }
            }
        } finally {
            fixture.close()
        }
    }

    // Uses only an app-owned, offscreen display. No capture consent, user screenshots or taps.
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        val core = MediaProjectionCore(context, context.getSystemService(MediaProjectionManager::class.java))
        val projection = MediaProjectionCore::class.java.getDeclaredField("projection")
            .apply { isAccessible = true }.get(core) as VisionMediaProjection
        fun field(name: String) = VisionMediaProjection::class.java.getDeclaredField(name).apply { isAccessible = true }
        private val slot = VisionMediaProjection::class.java.getDeclaredMethod(
            "createReader", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(projection, 32, 64)
        private val reader = slot.javaClass.getDeclaredField("reader")
            .apply { isAccessible = true }.get(slot) as ImageReader
        private val writer = ImageWriter.newInstance(reader.surface, 2)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "ScreenMlSnapshotRegression", 32, 64, 160, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )!!
        private lateinit var presentation: Presentation

        init {
            field("readerSlot").set(projection, slot)
            field("virtualDisplay").set(projection, display)
            mapOf("captureWidth" to 32, "captureHeight" to 64, "captureDensity" to 160).forEach { (name, value) ->
                MediaProjectionCore::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(core, value)
            }
            instrumentation.runOnMainSync {
                presentation = Presentation(context, display.display).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    window!!.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    setContentView(View(context).apply { setBackgroundColor(Color.BLUE) })
                    show()
                }
            }
            instrumentation.waitForIdleSync()
        }

        fun queueOldRedFrame() {
            val image = writer.dequeueInputImage()
            val plane = image.planes[0]
            repeat(image.height) { y ->
                repeat(image.width) { x ->
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    plane.buffer.put(offset, 0xff.toByte())
                    plane.buffer.put(offset + 1, 0)
                    plane.buffer.put(offset + 2, 0)
                    plane.buffer.put(offset + 3, 0xff.toByte())
                }
            }
            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
        }

        override fun close() {
            instrumentation.runOnMainSync { presentation.dismiss() }
            writer.close()
            core.stopProjection()
        }
    }
}
