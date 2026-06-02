package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import java.util.UUID

/**
 * Augments YOLO detections by filling gaps with elements from the Accessibility tree.
 *
 * The YOLO model detects 7 classes: button, icon, input, toggle, radio, checkbox, dropdown.
 * For any interactive accessibility node that does NOT overlap an existing YOLO detection
 * (IoU < [OVERLAP_THRESHOLD]), a new [UIElement] is created with `source = "accessibility"`.
 *
 * This gives us the best of both worlds:
 * - YOLO provides visually-confirmed detections with confidence scores
 * - Accessibility fills gaps for elements the model missed (non-standard widgets, system UI, etc.)
 */
object AccessibilityAugmenter {

    private const val TAG = "A11yAugmenter"

    /** IoU threshold — if an accessibility node overlaps a YOLO detection above this, skip it */
    private const val OVERLAP_THRESHOLD = 0.3f

    /** Confidence assigned to accessibility-sourced elements */
    private const val ACCESSIBILITY_CONFIDENCE = 0.85f

    /** Maximum tree traversal depth to avoid runaway recursion */
    private const val MAX_DEPTH = 12

    /** Minimum element dimension in pixels — filter out invisible/tiny nodes */
    private const val MIN_DIMENSION = 10

    /**
     * Returns accessibility tree elements that are NOT already covered by YOLO detections.
     *
     * @param yoloElements Current YOLO detections to check for overlap.
     * @return List of gap-fill [UIElement]s with `source = "accessibility"`.
     */
    fun getUndetectedElements(yoloElements: List<UIElement>): List<UIElement> {
        val accessibilityElements = captureAllInteractiveElements()
        return filterUndetected(accessibilityElements, yoloElements)
    }

    /**
     * Capture all interactive elements from the current accessibility tree.
     * Call this WHILE the target app is in the foreground — once another activity
     * opens, rootInActiveWindow will point to the new activity's UI.
     *
     * @return List of interactive [UIElement]s with `source = "accessibility"`.
     */
    fun captureAllInteractiveElements(): List<UIElement> {
        val service = AccessibilityRouter.getService() ?: run {
            Log.d(TAG, "Accessibility service not connected")
            return emptyList()
        }
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get rootInActiveWindow: ${e.message}")
            null
        } ?: return emptyList()

        val elements = mutableListOf<UIElement>()
        try {
            traverseForInteractive(root, elements, depth = 0)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }

        Log.d(TAG, "Captured ${elements.size} interactive accessibility elements")
        return elements
    }

    /**
     * Filter pre-captured accessibility elements to only those NOT covered by YOLO detections.
     * Use this when accessibility elements were captured earlier (e.g. pre-captured in the service)
     * and need to be merged with a new YOLO detection run.
     */
    fun filterUndetected(
        accessibilityElements: List<UIElement>,
        yoloElements: List<UIElement>
    ): List<UIElement> {
        if (accessibilityElements.isEmpty()) return emptyList()

        val gapFills = accessibilityElements.filter { accElement ->
            val overlaps = yoloElements.any { yoloElement ->
                calculateIoU(accElement.bounds, yoloElement.bounds) > OVERLAP_THRESHOLD
            }
            !overlaps
        }

        Log.d(TAG, "Accessibility augmentation: ${accessibilityElements.size} total → " +
                "${gapFills.size} gap-fills (${accessibilityElements.size - gapFills.size} overlapped with YOLO)")

        return gapFills
    }

    /**
     * Enriches existing YOLO elements with accessibility text where they overlap.
     * Returns the enriched list (modifies elements that have null text but overlap
     * an accessibility node with text).
     */
    fun enrichWithAccessibilityText(
        yoloElements: List<UIElement>,
        accessibilityElements: List<UIElement>? = null
    ): List<UIElement> {
        val accElements = accessibilityElements ?: run {
            val service = AccessibilityRouter.getService() ?: return yoloElements
            val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return yoloElements
            val elements = mutableListOf<UIElement>()
            try {
                traverseForInteractive(root, elements, depth = 0)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
            elements
        }

        if (accElements.isEmpty()) return yoloElements

        return yoloElements.map { yolo ->
            if (!yolo.text.isNullOrBlank()) return@map yolo // Already has text

            // Find the best overlapping accessibility element with text
            val bestMatch = accElements
                .filter { acc -> !acc.text.isNullOrBlank() && calculateIoU(yolo.bounds, acc.bounds) > OVERLAP_THRESHOLD }
                .maxByOrNull { acc -> calculateIoU(yolo.bounds, acc.bounds) }

            if (bestMatch != null) {
                yolo.copy(text = bestMatch.text)
            } else {
                yolo
            }
        }
    }

    // ── Tree Traversal ──────────────────────────────────────────

    private fun traverseForInteractive(
        node: AccessibilityNodeInfo,
        out: MutableList<UIElement>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip zero-size or tiny nodes
        if (bounds.width() < MIN_DIMENSION || bounds.height() < MIN_DIMENSION) {
            // Still traverse children — a container might be tiny but children visible
            traverseChildren(node, out, depth)
            return
        }

        val isInteractive = node.isClickable || node.isEditable || node.isCheckable
        val className = node.className?.toString() ?: ""
        val label = mapToYoloLabel(className, node)

        // Only include nodes we can map to one of the 7 YOLO classes
        if (isInteractive && label != null) {
            val boundsF = RectF(bounds)
            val text = node.text?.toString() ?: node.contentDescription?.toString()

            out.add(
                UIElement(
                    id = "a11y_${UUID.randomUUID().toString().take(8)}",
                    label = label,
                    confidence = ACCESSIBILITY_CONFIDENCE,
                    bounds = boundsF,
                    text = text,
                    source = "accessibility"
                )
            )
        }

        traverseChildren(node, out, depth)
    }

    private fun traverseChildren(
        node: AccessibilityNodeInfo,
        out: MutableList<UIElement>,
        depth: Int
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseForInteractive(child, out, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Maps an Android class name + node properties to one of the 7 YOLO labels.
     * Returns null if the node doesn't map to any detectable UI element type.
     */
    private fun mapToYoloLabel(className: String, node: AccessibilityNodeInfo): String? {
        val cn = className.lowercase()
        return when {
            // Editable fields → input
            cn.contains("edittext") || node.isEditable -> "input"
            // Buttons
            cn.contains("button") -> "button"
            // Toggles / switches
            cn.contains("switch") || cn.contains("togglebutton") -> "toggle"
            // Checkboxes
            cn.contains("checkbox") || cn.contains("compoundbutton") -> "checkbox"
            // Radio buttons
            cn.contains("radiobutton") -> "radio"
            // Dropdowns / spinners
            cn.contains("spinner") -> "dropdown"
            // Clickable images → icon
            cn.contains("imageview") && node.isClickable -> "icon"
            cn.contains("imagebutton") -> "icon"
            // Generic clickable views → button
            // Exclude only known structural/scrolling containers, NOT layout wrappers like
            // FrameLayout which are commonly used as clickable items (e.g. bottom nav tabs)
            node.isClickable
                    && !cn.contains("scrollview") && !cn.contains("recyclerview")
                    && !cn.contains("viewpager") && !cn.contains("drawerlayout")
                    && !cn.contains("coordinatorlayout") && !cn.contains("navigationbarview")
                    && !cn.contains("toolbar") && !cn.contains("appbar")
                    && !cn.contains("viewgroup") -> "button"
            // Everything else → not a detectable element type
            else -> null
        }
    }

    // ── IoU Calculation ─────────────────────────────────────────

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left)
        val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right)
        val iB = minOf(a.bottom, b.bottom)
        if (iR < iL || iB < iT) return 0f
        val iA = (iR - iL) * (iB - iT)
        val uA = a.width() * a.height() + b.width() * b.height() - iA
        return if (uA > 0) iA / uA else 0f
    }
}
