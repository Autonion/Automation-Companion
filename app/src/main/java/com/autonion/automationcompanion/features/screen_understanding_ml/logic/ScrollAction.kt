package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The detected element chooses a scroll container; it is never the swipe's start point. */
internal object ScrollAction {
    private const val TAG = "ActionExecutor"
    private const val MAX_NODES = 512

    data class Swipe(val x: Float, val fromY: Float, val toY: Float)

    class Target(val node: AccessibilityNodeInfo, val bounds: RectF, val depth: Int)

    fun chooseTarget(targets: List<Target>, trigger: PointF): Target? =
        targets.filter { it.bounds.contains(trigger.x, trigger.y) }
            .minWithOrNull(compareBy<Target> { it.bounds.width() * it.bounds.height() }
                .thenByDescending { it.depth })
            // A fixed footer/ad can be outside the scrollable content. Use the main content then.
            ?: targets.maxByOrNull { it.bounds.width() * it.bounds.height() }

    fun swipe(bounds: RectF, down: Boolean): Swipe? {
        if (bounds.width() < 2f || bounds.height() < 2f ||
            !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) return null
        val upper = bounds.top + bounds.height() * 0.35f
        val lower = bounds.top + bounds.height() * 0.65f
        return Swipe(bounds.centerX(), if (down) lower else upper, if (down) upper else lower)
    }

    private fun isVerticalScroller(node: AccessibilityNodeInfo): Boolean {
        val actions = node.actionList.map { it.id }
        if (AccessibilityAction.ACTION_SCROLL_UP.id in actions ||
            AccessibilityAction.ACTION_SCROLL_DOWN.id in actions) return true
        // Generic FORWARD can mean sideways for a carousel. Do not scroll that instead of the page.
        if (AccessibilityAction.ACTION_SCROLL_LEFT.id in actions ||
            AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions ||
            node.className?.toString()?.contains("HorizontalScrollView") == true) return false
        return node.isScrollable
    }

    /** Borrows root; all node copies/children acquired during traversal are released here. */
    @Suppress("DEPRECATION")
    suspend fun execute(
        root: AccessibilityNodeInfo?,
        display: RectF,
        trigger: PointF,
        down: Boolean,
        dispatchSwipe: suspend (Swipe) -> Boolean
    ): Boolean {
        val viewport = RectF(display)
        root?.let {
            val bounds = Rect().also(it::getBoundsInScreen)
            val clipped = RectF(bounds)
            if (clipped.intersect(display) && clipped.width() >= 2f && clipped.height() >= 2f) {
                viewport.set(clipped)
            }
        }
        val targets = mutableListOf<Target>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        root?.let { pending.add(AccessibilityNodeInfo.obtain(it) to 0) }
        try {
            var visited = 0
            while (pending.isNotEmpty() && visited++ < MAX_NODES) {
                currentCoroutineContext().ensureActive()
                val (node, depth) = pending.removeFirst()
                try {
                    val bounds = RectF(Rect().also(node::getBoundsInScreen))
                    if (node.isVisibleToUser && node.isEnabled && isVerticalScroller(node) &&
                        bounds.intersect(viewport) && bounds.width() >= 2f && bounds.height() >= 2f) {
                        targets.add(Target(AccessibilityNodeInfo.obtain(node), bounds, depth))
                    }
                    // Bound both traversal work and queued nodes for large WebView trees.
                    val remaining = (MAX_NODES - visited - pending.size).coerceAtLeast(0)
                    for (index in 0 until minOf(node.childCount, remaining)) {
                        node.getChild(index)?.let { pending.add(it to depth + 1) }
                    }
                } finally { node.recycle() }
            }
            val target = chooseTarget(targets, trigger)
            val direction = if (down) "down" else "up"
            if (target != null) {
                val directional = if (down) AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityAction.ACTION_SCROLL_UP
                val generic = if (down) AccessibilityAction.ACTION_SCROLL_FORWARD else AccessibilityAction.ACTION_SCROLL_BACKWARD
                val advertised = target.node.actionList.map { it.id }
                // In a two-axis view FORWARD may mean right, even when UP is also available.
                val hasHorizontalActions = AccessibilityAction.ACTION_SCROLL_LEFT.id in advertised ||
                    AccessibilityAction.ACTION_SCROLL_RIGHT.id in advertised
                val actions = if (hasHorizontalActions) listOf(directional) else listOf(directional, generic)
                for (action in actions) {
                    currentCoroutineContext().ensureActive()
                    if (action.id in advertised && target.node.performAction(action.id)) {
                        Log.d(TAG, "Scroll $direction: container action accepted; trigger=$trigger, container=${target.bounds}, action=${action.id}")
                        return true
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val bounds = target?.bounds ?: viewport
            val swipe = swipe(bounds, down) ?: return false
            Log.d(TAG, "Scroll $direction: trigger=$trigger, ${if (target != null) "container" else "viewport"}=$bounds; swipe (${swipe.x}, ${swipe.fromY}) -> (${swipe.x}, ${swipe.toY})")
            return dispatchSwipe(swipe).also {
                Log.d(TAG, "Scroll $direction: swipe ${if (it) "delivered (content movement unverified)" else "failed"}")
            }
        } finally {
            pending.forEach { it.first.recycle() }
            targets.forEach { it.node.recycle() }
        }
    }
}
