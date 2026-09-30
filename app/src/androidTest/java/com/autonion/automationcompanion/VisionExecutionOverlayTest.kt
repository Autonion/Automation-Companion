package com.autonion.automationcompanion

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import com.autonion.automationcompanion.core.ui.OverlayStyles
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisionExecutionOverlayTest {
    @Test fun overlayRemainsTouchableInBothStatesAndDragDoesNotTogglePlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = VisionExecutionService::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        fun method(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }
        val service = VisionExecutionService()
        var panel: LinearLayout? = null
        // Exercise the real overlay without starting capture, dispatching game taps,
        // or loading any of the user's presets.
        try {
            instrumentation.runOnMainSync {
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                field("windowManager").set(service, manager)
                method("showExecutionOverlay").invoke(service)
                panel = field("overlayView").get(service) as LinearLayout
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 3000
            while (panel!!.width == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            instrumentation.runOnMainSync {
                val view = panel!!
                assertTrue(view.width > 0)
                val button = view.getChildAt(0)
                val close = view.getChildAt(2)
                assertEquals("Start preset", button.contentDescription)
                assertEquals("Close preset", close.contentDescription)
                assertTrue(button.hasOnClickListeners())
                assertTrue(close.hasOnClickListeners())
                assertNull(button.background)
                assertNull(close.background)
                val dp = context.resources.displayMetrics.density
                assertEquals((OverlayStyles.CLOSE_BUTTON_SIZE_DP * dp).toInt(), button.width)
                assertEquals(button.width, close.width)
                assertTrue(view.width / dp < 128f)
                assertTrue(view.height / dp < 64f)
                for (paused in listOf(true, false, true)) {
                    field("isPaused").setBoolean(service, paused)
                    method("updateExecutionOverlayTouchPolicy").invoke(service)
                    assertEquals(View.VISIBLE, view.visibility)
                    val flags = (view.layoutParams as WindowManager.LayoutParams).flags
                    assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val overlaps = type.getDeclaredMethod("overlapsExecutionOverlay",
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                assertEquals(true, overlaps.invoke(service, location[0] + view.width / 2f, location[1] + view.height / 2f, 0f))
                assertEquals(false, overlaps.invoke(service, location[0] - 20f, location[1] + view.height / 2f, 0f))
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, y: Float) {
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                        button.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                touch(MotionEvent.ACTION_DOWN, 20f, 20f)
                assertTrue(field("overlayInteracting").getBoolean(service))
                touch(MotionEvent.ACTION_MOVE, -5000f, -5000f)
                touch(MotionEvent.ACTION_UP, -5000f, -5000f)
                assertFalse(field("overlayInteracting").getBoolean(service))
                assertTrue(field("isPaused").getBoolean(service))
                val layout = view.layoutParams as WindowManager.LayoutParams
                assertEquals(0, layout.x)
                assertEquals(0, layout.y)
                touch(MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(MotionEvent.ACTION_MOVE, 100f, 100f)
                touch(MotionEvent.ACTION_UP, 100f, 100f)
                assertTrue(layout.x > 0)
                assertTrue(layout.y > 0)
                val displayFrame = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(displayFrame)
                layout.x = 100_000
                layout.y = 100_000
                method("clampExecutionOverlay").invoke(service)
                assertEquals((displayFrame.width() - view.width).coerceAtLeast(0), layout.x)
                assertEquals((displayFrame.height() - view.height).coerceAtLeast(0), layout.y)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(context.cacheDir, "execution-overlay-controls.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync {
                panel?.let { manager.removeViewImmediate(it) }
                (field("job").get(service) as Job).cancel()
            }
        }
    }
}
