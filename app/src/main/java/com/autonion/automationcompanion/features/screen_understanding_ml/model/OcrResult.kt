package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Result of an OCR (text recognition) operation on a bitmap.
 */
data class OcrResult(
    /** All recognised text concatenated. */
    val fullText: String,
    /** Structured text blocks with bounding boxes. */
    val blocks: List<OcrBlock>
)

data class OcrBlock(
    val text: String,
    val bounds: RectF?,
    val lines: List<OcrLine>,
    val confidence: Float?
)

data class OcrLine(
    val text: String,
    val bounds: RectF?,
    val confidence: Float?
)
