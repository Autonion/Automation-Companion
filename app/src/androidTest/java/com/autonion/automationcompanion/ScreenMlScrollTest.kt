package com.autonion.automationcompanion

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ScrollAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ScreenMlScrollTest {
    @Test fun bottomEdgeTriggerUsesCentralSwipeWithoutAccessibilityTree() = runBlocking {
        val display = RectF(0f, 0f, 1264f, 2780f)
        assertTrue(ScrollAction.execute(null, display, PointF(1158.5f, 2447.5f), true) {
            assertEquals(632f, it.x, 0.1f)
            assertEquals(1807f, it.fromY, 0.1f)
            assertEquals(973f, it.toY, 0.1f)
            true
        })
        assertFalse(ScrollAction.execute(null, display, PointF(0f, 0f), false) {
            assertTrue(it.fromY < it.toY)
            false // A rejected fallback must propagate to playback.
        })
    }

    @Test fun containingPanelWinsAndOutsideFooterUsesLargestPanel() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            val page = ScrollAction.Target(node, RectF(0f, 100f, 1000f, 1700f), 1)
            val panel = ScrollAction.Target(node, RectF(600f, 800f, 950f, 1500f), 2)
            val targets = listOf(page, panel)
            assertSame(panel, ScrollAction.chooseTarget(targets, PointF(900f, 1450f)))
            assertSame(page, ScrollAction.chooseTarget(targets, PointF(900f, 1950f)))
            val swipe = ScrollAction.swipe(panel.bounds, true)!!
            assertTrue(panel.bounds.contains(swipe.x, swipe.fromY))
            assertTrue(panel.bounds.contains(swipe.x, swipe.toY))
            assertNull(ScrollAction.swipe(RectF(), true))
        } finally { node.recycle() }
    }

    @Test fun horizontalForwardActionIsNotUsedForVerticalScroll() = runBlocking {
        val root = AccessibilityNodeInfo.obtain().apply {
            setBoundsInScreen(Rect(0, 0, 1200, 700))
            isVisibleToUser = true
            isEnabled = true
            isScrollable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
        try {
            repeat(2) { attempt ->
                // Also cover a two-axis container at its vertical end, still able to move right.
                if (attempt == 1) root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
                assertTrue(ScrollAction.execute(root, RectF(0f, 0f, 1200f, 800f), PointF(1100f, 650f), true) {
                    assertEquals(600f, it.x, 0f)
                    assertTrue(it.fromY > it.toY)
                    true
                })
            }
        } finally { root.recycle() }
    }

    @Test fun footerTriggerScrollsRealPageWithNativeAccessibilityActions() = runBlocking {
        Fixture().use { fixture ->
            val root = fixture.instrumentation.uiAutomation.rootInActiveWindow!!
            try {
                assertTrue(ScrollAction.execute(root, fixture.viewport, fixture.trigger, true) {
                    fail("The visible ScrollView should accept its native scroll action")
                    false
                })
            } finally { root.recycle() }
            fixture.await { fixture.scrollY() > 0 }
            val previousY = fixture.scrollY()
            val updatedRoot = fixture.instrumentation.uiAutomation.rootInActiveWindow!!
            try {
                assertTrue(ScrollAction.execute(updatedRoot, fixture.viewport, fixture.trigger, false) {
                    fail("Scroll up should also use the container's native action")
                    false
                })
            } finally { updatedRoot.recycle() }
            fixture.await { fixture.scrollY() < previousY }
        }
    }

    @Test fun footerTriggerScrollsRealPageWithGestureFallback() = runBlocking {
        Fixture().use { fixture ->
            // Simulates OCR-only content, where no accessibility tree can identify the page.
            assertTrue(ScrollAction.execute(null, fixture.viewport, fixture.trigger, true, fixture::swipe))
            assertTrue("The page must move even though the trigger is in the fixed banner", fixture.scrollY() > 0)
            val previousY = fixture.scrollY()
            assertTrue(ScrollAction.execute(null, fixture.viewport, fixture.trigger, false, fixture::swipe))
            assertTrue(fixture.scrollY() < previousY)
        }
    }

    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val scenario = ActivityScenario.launch(ScreenMlScrollTestActivity::class.java)
        private lateinit var layout: LinearLayout
        private lateinit var scroll: ScrollView
        private lateinit var footer: TextView
        val viewport: RectF
        val trigger: PointF

        init {
            scenario.onActivity { activity ->
                fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
                layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                scroll = object : ScrollView(activity) {
                    override fun fling(velocityY: Int) = Unit // Deterministic final position after test gestures.
                }.apply { isSmoothScrollingEnabled = false }
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                repeat(80) { row ->
                    content.addView(TextView(activity).apply { text = "Scrollable row $row" },
                        LinearLayout.LayoutParams(-1, dp(64)))
                }
                scroll.addView(content)
                layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                val banner = FrameLayout(activity).apply { setOnTouchListener { _, _ -> true } }
                footer = TextView(activity).apply { text = "Ad" }
                banner.addView(footer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
                layout.addView(banner, LinearLayout.LayoutParams(-1, dp(120)))
                activity.setContentView(layout)
            }
            instrumentation.waitForIdleSync()
            viewport = bounds(layout)
            val label = bounds(footer)
            trigger = PointF(label.centerX(), label.centerY())
            await { instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                try { root.packageName == instrumentation.targetContext.packageName }
                finally { root.recycle() }
            } == true }
        }

        private fun bounds(view: View): RectF {
            val bounds = Rect()
            instrumentation.runOnMainSync { view.getGlobalVisibleRect(bounds) }
            return RectF(bounds)
        }

        fun scrollY(): Int {
            var result = 0
            instrumentation.runOnMainSync { result = scroll.scrollY }
            return result
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 3000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
            assertTrue("Scroll fixture did not reach expected state", condition())
        }

        suspend fun swipe(swipe: ScrollAction.Swipe): Boolean {
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, fraction: Float) {
                instrumentation.runOnMainSync {
                    val event = MotionEvent.obtain(downTime, downTime + (fraction * 500).toLong(), action,
                        swipe.x - viewport.left,
                        swipe.fromY + (swipe.toY - swipe.fromY) * fraction - viewport.top, 0)
                    try { layout.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            send(MotionEvent.ACTION_DOWN, 0f)
            for (step in 1..30) send(MotionEvent.ACTION_MOVE, step / 30f)
            send(MotionEvent.ACTION_UP, 1f)
            return true
        }

        override fun close() = scenario.close()
    }
}
