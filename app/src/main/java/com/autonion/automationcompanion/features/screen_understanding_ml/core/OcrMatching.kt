package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.graphics.RectF
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.OcrResult
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import java.util.UUID

/** Lines give precise tap bounds; blocks retain compatibility with saved multiline anchors. */
fun OcrResult.textElements(includeBlocks: Boolean = false): List<UIElement> = blocks.flatMap { block ->
    val lines = block.lines.mapNotNull { line ->
        line.bounds?.takeUnless { it.isEmpty || line.text.isBlank() }?.let {
            UIElement(UUID.randomUUID().toString(), "Text", line.confidence ?: 0.9f,
                RectF(it), text = line.text, source = "ocr")
        }
    }
    val blockElement = block.bounds?.takeUnless { it.isEmpty || block.text.isBlank() }?.let {
        UIElement(UUID.randomUUID().toString(), "Text", block.confidence ?: 0.9f,
            RectF(it), text = block.text, source = "ocr")
    }
    if (lines.isEmpty()) listOfNotNull(blockElement)
    else if (includeBlocks && lines.size > 1) lines + listOfNotNull(blockElement)
    else lines
}

object OcrMatching {
    fun findBest(
        elements: List<UIElement>, step: AutomationStep, width: Float, height: Float
    ): UIElement? {
        val target = TextMatching.normalize(step.anchor.text.orEmpty())
        val expected = step.anchor.bounds
        val canComparePosition = width > 0 && height > 0 &&
            step.captureScreenWidth > 0 && step.captureScreenHeight > 0 &&
            ((width > height) == (step.captureScreenWidth > step.captureScreenHeight))
        fun distance(element: UIElement): Float {
            if (!canComparePosition) return 0f
            val dx = element.bounds.centerX() / width - expected.centerX() / step.captureScreenWidth
            val dy = element.bounds.centerY() / height - expected.centerY() / step.captureScreenHeight
            return dx * dx + dy * dy
        }
        return elements.asSequence().filter {
            !it.bounds.isEmpty && it.bounds.right > 0 && it.bounds.bottom > 0 &&
                (width <= 0 || it.bounds.left < width) && (height <= 0 || it.bounds.top < height)
        }.map { it to TextMatching.scoreNormalized(TextMatching.normalize(it.text.orEmpty()), target) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UIElement, Float>> { it.second }
                .thenBy { distance(it.first) }
                .thenBy { it.first.bounds.width() * it.first.bounds.height() })
            .firstOrNull()?.first
    }

    fun enrich(elements: List<UIElement>, result: OcrResult): List<UIElement> {
        val lines = result.textElements()
        return elements.map { element ->
            if (!element.text.isNullOrBlank()) return@map element
            val text = lines.filter { line ->
                val intersection = RectF(line.bounds)
                intersection.intersect(element.bounds) &&
                    intersection.width() * intersection.height() /
                    (line.bounds.width() * line.bounds.height()) >= 0.5f
            }.joinToString(" ") { it.text.orEmpty() }
            if (text.isBlank()) element else element.copy(text = text)
        }
    }
}
