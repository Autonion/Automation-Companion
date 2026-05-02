package com.autonion.automationcompanion.features.semantic_automation.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.semantic_automation.model.ElementSource
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement

/**
 * Reads the Android Accessibility tree and converts it into a [ScreenUIState].
 * Also supports performing actions directly on nodes (click, setText),
 * bypassing coordinate-based gesture dispatch which can be blocked by overlays.
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

    /** Returns the current foreground app's package name (e.g. "org.mozilla.fenix"). */
    fun getCurrentPackageName(): String? {
        val service = serviceRef?.get() ?: return null
        // Prefer the actual application window rather than system/keyboard windows
        val windows = try { service.windows } catch (e: Exception) { null }
        val window = windows?.find { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val root = window?.root ?: service.rootInActiveWindow ?: return null
        
        val pkg = root.packageName?.toString()
        root.recycle()
        return pkg
    }

    /**
     * Capture the current accessibility tree and build a [ScreenUIState].
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

    // ──────────────────────────────────────────────────────────
    // Global actions — Back press, IME submit, etc.
    // ──────────────────────────────────────────────────────────

    /**
     * Press the system Back button via accessibility.
     * Used for wrong-app recovery (when the agent accidentally navigates away).
     */
    fun performPressBack(): Boolean {
        val service = serviceRef?.get() ?: return false
        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "Performed BACK press: $result")
        return result
    }

    /**
     * Press Enter/Search on the soft keyboard (IME action).
     * Used after INPUT_TEXT to submit search queries instead of clicking the input field.
     *
     * Tries multiple strategies:
     * 1. Find the currently focused editable node and trigger ACTION_IME_ENTER (API 30+)
     * 2. If no focused editable: find ANY editable, re-focus it, then retry IME
     * 3. Fall back to dispatching KEYCODE_ENTER via global key event
     */
    fun performImeAction(): Boolean {
        val service = serviceRef?.get() ?: return false
        val root = service.rootInActiveWindow ?: return false

        // Strategy 1: Find the focused editable node
        var target = findFocusedEditable(root)

        // Strategy 2: No focused editable → find ANY editable and re-focus it
        if (target == null) {
            Log.d(TAG, "No focused editable found, searching for any editable to re-focus")
            target = findAnyEditable(root)
            if (target != null) {
                // Re-focus the editable node so IME actions work
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Small wait for focus to settle (accessibility dispatch is async)
                Thread.sleep(300)
                Log.d(TAG, "Re-focused editable node: '${target.text}'")
            }
        }

        if (target != null) {
            // Try newer ACTION_IME_ENTER first (API 30+)
            val imeResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }

            if (imeResult) {
                Log.d(TAG, "IME_ENTER succeeded on editable node")
                target.recycle()
                root.recycle()
                return true
            }

            // Fallback: click the focused node (often triggers IME search on some apps)
            val clickResult = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Fallback click on editable for IME submit: $clickResult")
            target.recycle()

            // If click worked as an IME trigger, return
            if (clickResult) {
                root.recycle()
                return true
            }
        }

        // Strategy 3: Global KEYCODE_ENTER via dispatchGesture/key event
        // This sends Enter at the system level, reaching whatever is focused
        Log.d(TAG, "Falling back to global KEYCODE_ENTER key event")
        val enterResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // API 33+: use soft keyboard action
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
            // Not ideal — last resort: try sending Enter via instrumentation-like approach
            false
        } else {
            false
        }

        // Strategy 4: Send KEYCODE_ENTER via InputConnection simulation
        if (!enterResult) {
            try {
                val inst = android.app.Instrumentation()
                Thread {
                    try {
                        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
                        Log.d(TAG, "Sent KEYCODE_ENTER via Instrumentation")
                    } catch (e: Exception) {
                        Log.w(TAG, "Instrumentation KEYCODE_ENTER failed: ${e.message}")
                    }
                }.start()
                root.recycle()
                Thread.sleep(500) // Wait for key event to be processed
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Could not dispatch KEYCODE_ENTER: ${e.message}")
            }
        }

        root.recycle()
        Log.w(TAG, "All IME submit strategies exhausted")
        return false
    }

    /**
     * Find the currently focused editable node in the tree.
     */
    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First try finding the input-focused node
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        focused?.recycle()

        // Fallback: scan for any focused editable
        return findEditableByTraversal(root, requireFocus = true)
    }

    /**
     * Find ANY editable node in the tree (focused or not).
     * Used as fallback when no focused editable is found.
     */
    private fun findAnyEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findEditableByTraversal(root, requireFocus = false)
    }

    private fun findEditableByTraversal(node: AccessibilityNodeInfo, requireFocus: Boolean): AccessibilityNodeInfo? {
        if (node.isEditable && (!requireFocus || node.isFocused)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableByTraversal(child, requireFocus)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    // ──────────────────────────────────────────────────────────
    // Direct node actions — bypass coordinate taps / overlays
    // ──────────────────────────────────────────────────────────

    /**
     * Find a node on screen matching the given [UIStateElement] and perform ACTION_CLICK.
     * This uses the semantic accessibility action, NOT a coordinate-based gesture,
     * so it works even when a notification/overlay covers the element.
     *
     * @return true if the click was performed successfully.
     */
    fun performClickOnElement(element: UIStateElement): Boolean {
        val service = serviceRef?.get() ?: return false
        val root = service.rootInActiveWindow ?: return false

        val targetNode = findMatchingNode(root, element)
        val result = if (targetNode != null) {
            val clicked = performClickWithFallback(targetNode)
            Log.d(TAG, "Direct click on '${element.text}' (${element.type}): $clicked")
            targetNode.recycle()
            clicked
        } else {
            Log.w(TAG, "Could not find matching node for '${element.text}'")
            false
        }

        root.recycle()
        return result
    }

    /**
     * Find a node and set text on it using ACTION_SET_TEXT.
     * This is more reliable than focusing + typing via InputConnection.
     */
    fun performSetText(element: UIStateElement, text: String): Boolean {
        val service = serviceRef?.get() ?: return false
        val root = service.rootInActiveWindow ?: return false

        val targetNode = findMatchingNode(root, element)
        val result = if (targetNode != null) {
            // First focus the node. Many apps ignore SET_TEXT if the field isn't focused.
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) // Click to trigger IME/listeners

            // Small delay for focus to settle
            Thread.sleep(200)

            // 1. Clear the field first
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

            // 2. PRIMARY: Use ACTION_SET_TEXT (doesn't need clipboard access, works from background)
            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            if (success) {
                Log.d(TAG, "Used ACTION_SET_TEXT to set text")
            }

            // 3. FALLBACK: Try ACTION_PASTE if SET_TEXT failed (some Compose/React apps need it)
            if (!success) {
                Log.d(TAG, "ACTION_SET_TEXT failed, trying ACTION_PASTE fallback")
                val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (clipboard != null) {
                    try {
                        val clip = android.content.ClipData.newPlainText("automation", text)
                        clipboard.setPrimaryClip(clip)
                        success = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        if (success) {
                            Log.d(TAG, "Used ACTION_PASTE to set text")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ACTION_PASTE failed (clipboard access denied): ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Set text '$text' on '${element.text}': $success")
            targetNode.recycle()
            success
        } else {
            Log.w(TAG, "Could not find matching node for setText")
            false
        }

        root.recycle()
        return result
    }

    /**
     * Perform a click, trying the node itself first, then walking up to find a
     * clickable parent (Android often has clickable containers wrapping the actual widget).
     */
    private fun performClickWithFallback(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node itself
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // If it's checkable but not clickable, try ACTION_CLICK anyway (toggles)
        if (node.isCheckable) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) return true
        }

        // Walk up to find a clickable parent
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked parent at depth $depth: $success")
                current.recycle()
                return success
            }
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()

        // Last resort: try clicking anyway even if not marked clickable
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Find a node in the tree that matches the given UIStateElement
     * by bounds and text/type.
     */
    private fun findMatchingNode(root: AccessibilityNodeInfo, target: UIStateElement): AccessibilityNodeInfo? {
        // Strategy 1: Resource IDs are stable across captures when apps expose them.
        if (!target.resourceId.isNullOrBlank()) {
            try {
                val byId = root.findAccessibilityNodeInfosByViewId(target.resourceId)
                if (byId.isNotEmpty()) {
                    val match = byId.minByOrNull { node ->
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        boundsDifference(b, target.bounds)
                    }
                    byId.filter { it != match }.forEach { it.recycle() }
                    if (match != null) return match
                }
            } catch (e: Exception) {
                Log.d(TAG, "find by resourceId failed for ${target.resourceId}: ${e.message}")
            }
        }

        // Strategy 2: If we have text, find by text first.
        if (!target.text.isNullOrBlank()) {
            val byText = root.findAccessibilityNodeInfosByText(target.text)
            if (byText.isNotEmpty()) {
                // Find the one closest to our expected bounds
                val match = byText.minByOrNull { node ->
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    boundsDifference(b, target.bounds)
                }
                // Recycle the ones we don't use
                byText.filter { it != match }.forEach { it.recycle() }
                if (match != null) return match
            }
        }

        // Strategy 3: Traverse and match by bounds
        return findByBounds(root, target.bounds)
    }

    /**
     * Find a node whose screen bounds closely match the target bounds.
     */
    private fun findByBounds(node: AccessibilityNodeInfo, targetBounds: RectF, threshold: Float = 30f): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val diff = boundsDifference(bounds, targetBounds)
        if (diff < threshold && (node.isClickable || node.isCheckable || node.isEditable)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByBounds(child, targetBounds, threshold)
            child.recycle()
            if (found != null) return found
        }

        return null
    }

    private fun boundsDifference(actual: Rect, target: RectF): Float {
        return Math.abs(actual.left - target.left) +
               Math.abs(actual.top - target.top) +
               Math.abs(actual.right - target.right) +
               Math.abs(actual.bottom - target.bottom)
    }

    // ──────────────────────────────────────────────────────────
    // Tree traversal for capture
    // ──────────────────────────────────────────────────────────

    private fun traverseNode(node: AccessibilityNodeInfo, out: MutableList<UIStateElement>, path: String = "0") {
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val nodeText = node.text?.toString()
        val contentDescription = node.contentDescription?.toString()
        val text = nodeText ?: contentDescription
        val resourceId = node.viewIdResourceName
        val isInteractive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable

        if (isInteractive || !text.isNullOrBlank()) {
            val type = mapClassName(node.className?.toString(), node)
            val stableId = buildStableElementId(node, bounds, path, text, resourceId, contentDescription)
            out.add(
                UIStateElement(
                    id = stableId,
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
                    resourceId = resourceId,
                    contentDescription = contentDescription,
                    hierarchyPath = path,
                    isEnabled = node.isEnabled,
                    isFocused = node.isFocused,
                    confidence = 1.0f,
                    source = ElementSource.ACCESSIBILITY
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, out, "$path/$i")
            child.recycle()
        }
    }

    private fun buildStableElementId(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        path: String,
        text: String?,
        resourceId: String?,
        contentDescription: String?
    ): String {
        val bucketedBounds = "${bounds.left / 8},${bounds.top / 8},${bounds.width() / 8},${bounds.height() / 8}"
        val raw = listOf(
            node.packageName?.toString().orEmpty(),
            resourceId.orEmpty(),
            node.className?.toString().orEmpty(),
            text.orEmpty(),
            contentDescription.orEmpty(),
            bucketedBounds,
            path
        ).joinToString("|")
        return "a11y_${Integer.toHexString(raw.hashCode())}"
    }

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
            node.isClickable -> "button"
            else -> "view"
        }
    }
}
