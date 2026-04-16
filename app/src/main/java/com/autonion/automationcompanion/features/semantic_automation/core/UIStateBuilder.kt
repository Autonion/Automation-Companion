package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer
import com.autonion.automationcompanion.features.semantic_automation.model.ElementSource
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement
import org.json.JSONObject
import java.util.UUID

/**
 * Builds a [ScreenUIState] by choosing the best available source:
 *   1. Extension DOM snapshot (preferred for browsers — actual web page DOM)
 *   2. Accessibility tree (preferred for native apps — structured, fast, reliable)
 *   3. YOLO detection + OCR text extraction (fallback — for custom UIs / games)
 *
 * The output is always the same schema, so downstream consumers
 * (ActionPredictor) don't need to know about the source.
 */
class UIStateBuilder(private val context: Context) {

    companion object {
        private const val TAG = "UIStateBuilder"

        /** Browser package substrings — when these are the foreground app, prefer DOM over accessibility */
        private val BROWSER_PACKAGES = listOf(
            "chrome", "firefox", "fenix", "mozilla", "browser", "opera",
            "edge", "duckduckgo", "brave", "kiwi", "lemur"
        )
    }

    private var perceptionLayer: PerceptionLayer? = null
    private var ocrEngine: OcrEngine? = null

    /**
     * Build the current screen's UI state.
     * @param screenshot Required only if accessibility is unavailable.
     * @param extensionBridge Optional bridge server to check for browser DOM snapshots.
     */
    suspend fun build(
        screenshot: Bitmap? = null,
        extensionBridge: ExtensionBridgeServer? = null
    ): ScreenUIState {

        // Strategy 1: Extension DOM snapshot (for browser-based interactions)
        // This is the ONLY way to see actual web page content (inputs, buttons, links, etc.)
        // Android accessibility can only see the browser's native chrome (URL bar, toolbar).
        if (extensionBridge != null && extensionBridge.isConnected()) {
            val currentPackage = AccessibilityTreeReader.getCurrentPackageName()
            val isInBrowser = currentPackage != null && BROWSER_PACKAGES.any {
                currentPackage.contains(it, ignoreCase = true)
            }

            if (isInBrowser) {
                val domSnapshot = extensionBridge.latestDomSnapshot.value
                    ?: extensionBridge.requestDomSnapshot(timeoutMs = 5_000L)

                if (domSnapshot != null) {
                    val state = buildFromDomSnapshot(domSnapshot, currentPackage)
                    if (state.elements.isNotEmpty()) {
                        Log.d(TAG, "Built UI state from Extension DOM (${state.elements.size} elements)")
                        DebugLogger.info(
                            context, LogCategory.SCREEN_CONTEXT_AI,
                            "UI State: Extension DOM",
                            "Using browser DOM snapshot (${state.elements.size} elements) for this automation step",
                            TAG
                        )
                        return state
                    }
                }
                Log.d(TAG, "Extension connected but DOM snapshot empty/unavailable, falling back to Accessibility")
                DebugLogger.warning(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "DOM Snapshot Empty",
                    "Extension is connected but DOM snapshot is empty/unavailable. Falling back to Accessibility tree.",
                    TAG
                )
            }
        }

        // Strategy 2: Accessibility tree (native apps and browser fallback)
        if (AccessibilityTreeReader.isAvailable()) {
            val state = AccessibilityTreeReader.capture()
            if (state != null && state.elements.isNotEmpty()) {
                Log.d(TAG, "Built UI state from Accessibility (${state.elements.size} elements)")
                return state
            }
            Log.d(TAG, "Accessibility tree empty or null, falling back to YOLO")
        }

        // Strategy 3: YOLO + OCR (requires a screenshot)
        if (screenshot == null) {
            Log.w(TAG, "No screenshot provided and accessibility unavailable")
            return ScreenUIState(elements = emptyList(), source = ElementSource.YOLO_OCR)
        }

        return buildFromVision(screenshot)
    }

    /**
     * Convert a DOM snapshot from the Extension Bridge into a [ScreenUIState].
     *
     * DOM snapshot structure:
     * {
     *   page: { url, title, viewport: { width, height, scroll_x, scroll_y } },
     *   dom_nodes: [ { id, tag, text, interactive, bounds, ... } ],
     *   interactive_elements: [ ... subset of dom_nodes ... ]
     * }
     */
    private fun buildFromDomSnapshot(snapshot: JSONObject, packageName: String?): ScreenUIState {
        val interactiveElements = snapshot.optJSONArray("interactive_elements")
            ?: snapshot.optJSONArray("dom_nodes")
            ?: return ScreenUIState(elements = emptyList(), source = ElementSource.EXTENSION_DOM)

        val elements = mutableListOf<UIStateElement>()
        val pageUrl = snapshot.optJSONObject("page")?.optString("url", "") ?: ""
        val pageTitle = snapshot.optJSONObject("page")?.optString("title", "") ?: ""

        for (i in 0 until interactiveElements.length()) {
            val node = interactiveElements.getJSONObject(i)
            val nodeId = node.optString("id", "dom_$i")
            val tag = node.optString("tag", "").lowercase()
            val text = node.optString("text", "").ifBlank { node.optString("aria_label", "") }
            val isInteractive = node.optBoolean("interactive", false)

            // Parse bounds: { x, y, width, height }
            val boundsObj = node.optJSONObject("bounds")
            val bounds = if (boundsObj != null) {
                val x = boundsObj.optDouble("x", 0.0).toFloat()
                val y = boundsObj.optDouble("y", 0.0).toFloat()
                val w = boundsObj.optDouble("width", 0.0).toFloat()
                val h = boundsObj.optDouble("height", 0.0).toFloat()
                RectF(x, y, x + w, y + h)
            } else {
                RectF(0f, 0f, 0f, 0f)
            }

            // Map HTML tags to semantic types
            val type = when (tag) {
                "input", "textarea", "select" -> "input"
                "button", "a" -> "button"
                "img", "video" -> "image"
                "h1", "h2", "h3", "h4", "h5", "h6", "p", "span", "label" -> "text"
                else -> if (isInteractive) "button" else "text"
            }

            val isEditable = tag in listOf("input", "textarea")
            val isClickable = isInteractive || tag in listOf("button", "a", "select")

            // Only include interactive or text-bearing elements
            if (isInteractive || text.isNotBlank()) {
                elements.add(
                    UIStateElement(
                        id = nodeId,
                        type = type,
                        text = text.ifBlank { null },
                        bounds = bounds,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        className = tag,
                        confidence = 1.0f,
                        source = ElementSource.EXTENSION_DOM
                    )
                )
            }
        }

        Log.d(TAG, "Parsed ${elements.size} elements from DOM snapshot (url=${pageUrl.take(60)})")

        return ScreenUIState(
            elements = elements,
            packageName = packageName,
            source = ElementSource.EXTENSION_DOM
        )
    }

    /**
     * Build UI state from YOLO detections enriched with OCR text.
     */
    private suspend fun buildFromVision(bitmap: Bitmap): ScreenUIState {
        // Lazy-init YOLO model
        if (perceptionLayer == null) {
            perceptionLayer = PerceptionLayer(context)
        }

        val elements = perceptionLayer!!.detectWithOcr(bitmap)

        val uiElements = elements.map { el ->
            UIStateElement(
                id = el.id,
                type = el.label.lowercase(),
                text = el.text,
                bounds = el.bounds,
                isClickable = el.label.lowercase() in listOf("button", "icon", "toggle", "checkbox", "radio", "dropdown"),
                isEditable = el.label.equals("input", ignoreCase = true),
                isChecked = if (el.label.equals("checkbox", ignoreCase = true) || el.label.equals("toggle", ignoreCase = true)) false else null,
                confidence = el.confidence,
                source = ElementSource.YOLO_OCR
            )
        }

        Log.d(TAG, "Built UI state from YOLO+OCR (${uiElements.size} elements)")
        return ScreenUIState(
            elements = uiElements,
            source = ElementSource.YOLO_OCR
        )
    }

    fun close() {
        perceptionLayer?.close()
        perceptionLayer = null
    }
}
