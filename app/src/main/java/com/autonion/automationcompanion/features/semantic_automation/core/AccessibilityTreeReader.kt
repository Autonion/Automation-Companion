package com.autonion.automationcompanion.features.semantic_automation.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.semantic_automation.model.ElementSource
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement
import java.util.UUID

/**
 * Reads the Android Accessibility tree and converts it into a [ScreenUIState].
 * Registers itself with the [AccessibilityRouter] so it always holds a ref
 * to the active AccessibilityService.
 */
object AccessibilityTreeReader : AccessibilityFeature {

    private const val TAG = "A11yTreeReader"

    private var serviceRef: java.lang.ref.WeakReference<AccessibilityService>? = null

    init {
        AccessibilityRouter.register(this)
    }

    override fun onServiceConnected(service: AccessibilityService) {
        serviceRef = java.lang.ref.WeakReference(service)
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onServiceDisconnected() {
        serviceRef = null
        Log.d(TAG, "AccessibilityService disconnected")
    }

    /** Returns true when we can read the accessibility tree right now. */
    fun isAvailable(): Boolean = serviceRef?.get()?.rootInActiveWindow != null

    /**
     * Capture the current accessibility tree and build a [ScreenUIState].
     * Returns null if the service is not connected or the root window is unavailable.
     */
    fun capture(): ScreenUIState? {
        val service = serviceRef?.get() ?: run {
            Log.w(TAG, "Service not connected")
            return null
        }
        val root = service.rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return null
        }

        val elements = mutableListOf<UIStateElement>()
        traverseNode(root, elements)

        val packageName = root.packageName?.toString()
        root.recycle()

        Log.d(TAG, "Captured ${elements.size} accessible elements from $packageName")
        return ScreenUIState(
            packageName = packageName,
            elements = elements,
            source = ElementSource.ACCESSIBILITY
        )
    }

    /**
     * Recursively traverse the accessibility node tree and collect
     * interactive or text-bearing nodes.
     */
    private fun traverseNode(node: AccessibilityNodeInfo, out: MutableList<UIStateElement>) {
        // Skip invisible nodes
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip zero-area nodes
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // Collect nodes that are interactive or carry text
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        val isInteractive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable

        if (isInteractive || !text.isNullOrBlank()) {
            val type = mapClassName(node.className?.toString(), node)
            out.add(
                UIStateElement(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    text = text,
                    bounds = RectF(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat()
                    ),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    className = node.className?.toString(),
                    confidence = 1.0f,
                    source = ElementSource.ACCESSIBILITY
                )
            )
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, out)
            child.recycle()
        }
    }

    /**
     * Maps Android view class names to our unified type labels.
     */
    private fun mapClassName(className: String?, node: AccessibilityNodeInfo): String {
        if (className == null) return "unknown"
        return when {
            className.contains("Button", ignoreCase = true) -> "button"
            className.contains("EditText", ignoreCase = true) -> "input"
            className.contains("CheckBox", ignoreCase = true) -> "checkbox"
            className.contains("RadioButton", ignoreCase = true) -> "radio"
            className.contains("Switch", ignoreCase = true) || className.contains("Toggle", ignoreCase = true) -> "toggle"
            className.contains("Spinner", ignoreCase = true) -> "dropdown"
            className.contains("ImageView", ignoreCase = true) || className.contains("ImageButton", ignoreCase = true) -> "icon"
            className.contains("TextView", ignoreCase = true) -> "text"
            className.contains("ScrollView", ignoreCase = true) || className.contains("RecyclerView", ignoreCase = true) -> "scrollable"
            node.isClickable -> "button" // Clickable but unknown class → treat as button
            else -> "view"
        }
    }
}
