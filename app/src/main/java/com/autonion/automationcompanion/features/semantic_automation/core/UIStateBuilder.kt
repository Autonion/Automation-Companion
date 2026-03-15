package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer
import com.autonion.automationcompanion.features.semantic_automation.model.ElementSource
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement
import java.util.UUID

/**
 * Builds a [ScreenUIState] by choosing the best available source:
 *   1. Accessibility tree (preferred — structured, fast, reliable)
 *   2. YOLO detection + OCR text extraction (fallback — for custom UIs / games)
 *
 * The output is always the same schema, so downstream consumers
 * (ActionPredictor) don't need to know about the source.
 */
class UIStateBuilder(private val context: Context) {

    companion object {
        private const val TAG = "UIStateBuilder"
    }

    private var perceptionLayer: PerceptionLayer? = null
    private var ocrEngine: OcrEngine? = null

    /**
     * Build the current screen's UI state.
     * @param screenshot Required only if accessibility is unavailable.
     */
    suspend fun build(screenshot: Bitmap? = null): ScreenUIState {
        // Strategy 1: Accessibility tree
        if (AccessibilityTreeReader.isAvailable()) {
            val state = AccessibilityTreeReader.capture()
            if (state != null && state.elements.isNotEmpty()) {
                Log.d(TAG, "Built UI state from Accessibility (${state.elements.size} elements)")
                return state
            }
            Log.d(TAG, "Accessibility tree empty or null, falling back to YOLO")
        }

        // Strategy 2: YOLO + OCR (requires a screenshot)
        if (screenshot == null) {
            Log.w(TAG, "No screenshot provided and accessibility unavailable")
            return ScreenUIState(elements = emptyList(), source = ElementSource.YOLO_OCR)
        }

        return buildFromVision(screenshot)
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
