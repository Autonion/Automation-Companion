package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement

/**
 * Hybrid element matching that combines three signal sources:
 *
 * 1. **UI Attributes** (AccessibilityNodeInfo) — Primary, most reliable when available.
 * 2. **YOLO Detection** — Visual ML detection of UI element types.
 * 3. **OCR** — Text recognition to verify text content.
 *
 * **Rotation-aware:** When screen orientation changes from capture time, spatial
 * IoU matching is replaced by text+label matching since element positions change
 * across rotations but text content remains the same.
 */
object HybridElementMatcher {

    private const val TAG = "HybridMatcher"

    private const val WEIGHT_ACCESSIBILITY = 0.50f
    private const val WEIGHT_YOLO = 0.30f
    private const val WEIGHT_OCR = 0.20f

    private const val MIN_HYBRID_CONFIDENCE = 0.50f
    private const val NO_YOLO_AGREEMENT_PENALTY = 0.5f

    data class HybridMatchResult(
        val element: UIElement,
        val hybridConfidence: Float,
        val accessibilityScore: Float,
        val yoloScore: Float,
        val ocrScore: Float,
        val source: String // "accessibility" or "yolo"
    )

    /**
     * Find the best-matching element using all available signal sources.
     *
     * @param isRotated True if screen orientation differs from capture time.
     *        When true, spatial IoU is skipped and text+label matching is used instead.
     * @param screenWidth Actual screen width (from displayMetrics), used for accessibility normalization.
     * @param screenHeight Actual screen height (from displayMetrics), used for accessibility normalization.
     */
    fun findBestMatch(
        anchorLabel: String,
        anchorBounds: RectF,
        anchorText: String?,
        yoloCandidates: List<UIElement>,
        normalizedAnchor: RectF?,
        currentScreenWidth: Float,
        currentScreenHeight: Float,
        isRotated: Boolean = false,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f
    ): HybridMatchResult? {

        if (isRotated) {
            Log.d(TAG, "Rotation detected — using text+label matching (spatial IoU skipped)")
            return findBestMatchRotated(
                anchorLabel, anchorText, yoloCandidates, screenWidth, screenHeight,
                anchorBounds, currentScreenWidth, currentScreenHeight
            )
        }

        return findBestMatchNormal(
            anchorLabel, anchorBounds, anchorText, yoloCandidates,
            normalizedAnchor, currentScreenWidth, currentScreenHeight
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROTATION MODE: Match by text + label + class (no spatial IoU)
    // ═══════════════════════════════════════════════════════════════════════

    private fun findBestMatchRotated(
        anchorLabel: String,
        anchorText: String?,
        yoloCandidates: List<UIElement>,
        screenWidth: Float,
        screenHeight: Float,
        anchorBounds: RectF,
        captureWidth: Float,
        captureHeight: Float
    ): HybridMatchResult? {
        // Compute anchor's relative area for size disambiguation
        val anchorArea = anchorBounds.width() * anchorBounds.height()
        val captureArea = captureWidth * captureHeight
        val anchorRelArea = if (captureArea > 0) anchorArea / captureArea else 0f
        val results = mutableListOf<HybridMatchResult>()

        // 1. Query accessibility tree by text/class — these have SCREEN coordinates
        val accMatches = queryAccessibilityByTextAndClass(anchorLabel, anchorText, screenWidth, screenHeight)

        // 2. Score YOLO candidates by label + text (no IoU)
        val yoloByLabel = yoloCandidates.filter { it.label.equals(anchorLabel, ignoreCase = true) }

        // 3. For each accessibility match, check if any YOLO candidate confirms it by text
        val screenArea = screenWidth * screenHeight
        for ((accElement, accScore) in accMatches) {
            // HARD GATE: If anchor text was captured, element MUST match text
            if (!anchorText.isNullOrBlank()) {
                val elementText = accElement.text
                if (!isTextMatching(elementText, anchorText)) {
                    continue // Strict exclusion
                }
            }

            val yoloAgreement = yoloByLabel.any { yolo ->
                !anchorText.isNullOrBlank() && isTextMatching(yolo.text, anchorText)
            }
            val yoloScore = if (yoloAgreement) 0.8f else if (yoloByLabel.isNotEmpty()) 0.3f else 0f
            val ocrScore = if (!anchorText.isNullOrBlank()) 1.0f else 0.5f

            // Size disambiguation: penalize candidates whose relative area differs greatly from anchor
            val candArea = accElement.bounds.width() * accElement.bounds.height()
            val candRelArea = if (screenArea > 0) candArea / screenArea else 0f
            val sizeRatio = if (anchorRelArea > 0 && candRelArea > 0) {
                maxOf(candRelArea, anchorRelArea) / minOf(candRelArea, anchorRelArea)
            } else 1f
            val sizePenalty = when {
                sizeRatio > 10f -> 0.3f  // Wildly different size
                sizeRatio > 5f  -> 0.5f  // Very different size
                sizeRatio > 3f  -> 0.7f  // Somewhat different
                else -> 1.0f             // Similar size
            }

            val rawConf = (WEIGHT_ACCESSIBILITY * accScore + WEIGHT_YOLO * yoloScore + WEIGHT_OCR * ocrScore)
                .coerceAtMost(1.0f)
            val conf = rawConf * sizePenalty

            Log.d(TAG, "Rotated acc match: text='${accElement.text}', bounds=${accElement.bounds}, " +
                    "acc=${"%.2f".format(accScore)}, yolo=${"%.2f".format(yoloScore)}, " +
                    "ocr=${"%.2f".format(ocrScore)}, sizeRatio=${"%.1f".format(sizeRatio)}, " +
                    "sizePenalty=$sizePenalty, hybrid=${"%.2f".format(conf)}")

            // Accessibility bounds are in screen coordinates → correct for clicking
            results.add(HybridMatchResult(accElement, conf, accScore, yoloScore, ocrScore, "accessibility"))
        }

        // 4. YOLO-only candidates (no accessibility match) — need coord conversion
        for (yolo in yoloByLabel) {
            // HARD GATE: If anchor text was captured, YOLO candidate MUST match text
            if (!anchorText.isNullOrBlank()) {
                if (!isTextMatching(yolo.text, anchorText)) {
                    continue // Strict exclusion
                }
            }

            val alreadyCovered = results.any { res ->
                !anchorText.isNullOrBlank() && isTextMatching(res.element.text, anchorText)
            }
            if (alreadyCovered) continue

            val textScore = if (!anchorText.isNullOrBlank()) 1.0f else 0f
            val yoloScore = yolo.confidence.coerceAtMost(1.0f)
            val conf = (WEIGHT_YOLO * yoloScore + WEIGHT_OCR * textScore).coerceAtMost(1.0f)

            // NOTE: YOLO bounds are in IMAGE space — caller must convert to screen space!
            if (conf > 0.2f) {
                Log.d(TAG, "Rotated YOLO-only: text='${yolo.text}', bounds=${yolo.bounds}, " +
                        "yolo=${"%.2f".format(yoloScore)}, text=${"%.2f".format(textScore)}, hybrid=${"%.2f".format(conf)}")
                results.add(HybridMatchResult(yolo, conf, 0f, yoloScore, textScore, "yolo"))
            }
        }

        val best = results.filter { it.hybridConfidence >= MIN_HYBRID_CONFIDENCE }
            .maxByOrNull { it.hybridConfidence }

        if (best != null) {
            Log.d(TAG, "Rotated match: label=$anchorLabel, conf=${"%.2f".format(best.hybridConfidence)} via ${best.source}")
        } else {
            Log.d(TAG, "No rotated match above $MIN_HYBRID_CONFIDENCE for label=$anchorLabel " +
                    "(${results.size} candidates, best=${results.maxByOrNull { it.hybridConfidence }?.hybridConfidence})")
        }
        return best
    }

    /**
     * Query accessibility tree by text and class for rotated mode.
     * Returns elements with SCREEN coordinates (correct for clicking).
     */
    private fun queryAccessibilityByTextAndClass(
        label: String,
        text: String?,
        screenWidth: Float,
        screenHeight: Float
    ): List<Pair<UIElement, Float>> {
        val allElements = AccessibilityAugmenter.captureAllInteractiveElements()
        if (allElements.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<UIElement, Float>>()
        for (el in allElements) {
            // Validate bounds are within screen
            if (el.bounds.right <= 0 || el.bounds.bottom <= 0) continue
            if (screenWidth > 0 && el.bounds.left > screenWidth) continue
            if (screenHeight > 0 && el.bounds.top > screenHeight) continue

            val nodeText = el.text
            if (!text.isNullOrBlank()) {
                if (!isTextMatching(nodeText, text)) continue
            } else {
                if (!el.label.equals(label, ignoreCase = true)) continue
            }

            val classScore = if (el.label.equals(label, ignoreCase = true)) 1.0f else 0.4f
            val textScore = if (!text.isNullOrBlank() && isTextMatching(nodeText, text)) 1.0f else 0.5f
            val conf = (textScore * 0.6f + classScore * 0.4f).coerceIn(0f, 1f)

            if (conf > 0.3f) {
                results.add(Pair(el, conf))
            }
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NORMAL MODE: Spatial IoU matching (no rotation)
    // ═══════════════════════════════════════════════════════════════════════

    private fun findBestMatchNormal(
        anchorLabel: String,
        anchorBounds: RectF,
        anchorText: String?,
        yoloCandidates: List<UIElement>,
        normalizedAnchor: RectF?,
        currentScreenWidth: Float,
        currentScreenHeight: Float
    ): HybridMatchResult? {
        val results = mutableListOf<HybridMatchResult>()

        val accessibilityMatches = queryAccessibilityTree(
            anchorLabel, anchorText, anchorBounds,
            normalizedAnchor, currentScreenWidth, currentScreenHeight
        )
        val yoloMatches = scoreYoloCandidates(
            anchorLabel, anchorBounds, anchorText,
            yoloCandidates, normalizedAnchor, currentScreenWidth, currentScreenHeight
        )

        for (accMatch in accessibilityMatches) {
            // HARD GATE: If anchor text is non-blank, accessibility candidate MUST match text
            if (!anchorText.isNullOrBlank()) {
                val t = accMatch.first.text
                if (!isTextMatching(t, anchorText)) {
                    continue // Strict exclusion
                }
            }

            val accScore = accMatch.second
            val yoloOverlap = if (currentScreenWidth > 0 && currentScreenHeight > 0) {
                val accNorm = normalizeRect(accMatch.first.bounds, currentScreenWidth, currentScreenHeight)
                yoloMatches.filter {
                    val yoloNorm = normalizeRect(it.first.bounds, currentScreenWidth, currentScreenHeight)
                    calculateIoU(yoloNorm, accNorm) > 0.1f
                }.maxByOrNull { it.second }
            } else {
                yoloMatches.filter { calculateIoU(it.first.bounds, accMatch.first.bounds) > 0.1f }
                    .maxByOrNull { it.second }
            }

            val yoloScore = yoloOverlap?.second ?: 0f
            val ocrScore = if (!anchorText.isNullOrBlank()) 1.0f else 0.5f

            var conf = WEIGHT_ACCESSIBILITY * accScore + WEIGHT_YOLO * yoloScore + WEIGHT_OCR * ocrScore
            if (yoloScore == 0f) {
                conf *= NO_YOLO_AGREEMENT_PENALTY
                Log.d(TAG, "Acc penalized (no YOLO): raw=${WEIGHT_ACCESSIBILITY * accScore + WEIGHT_OCR * ocrScore} → $conf")
            }

            results.add(HybridMatchResult(accMatch.first, conf, accScore, yoloScore, ocrScore, "accessibility"))
        }

        for (yoloMatch in yoloMatches) {
            // HARD GATE: If anchor text is non-blank, YOLO candidate MUST match text
            if (!anchorText.isNullOrBlank()) {
                if (!isTextMatching(yoloMatch.first.text, anchorText)) {
                    continue // Strict exclusion
                }
            }

            val covered = if (currentScreenWidth > 0 && currentScreenHeight > 0) {
                val yn = normalizeRect(yoloMatch.first.bounds, currentScreenWidth, currentScreenHeight)
                results.any { calculateIoU(normalizeRect(it.element.bounds, currentScreenWidth, currentScreenHeight), yn) > 0.3f }
            } else results.any { calculateIoU(it.element.bounds, yoloMatch.first.bounds) > 0.3f }
            if (covered) continue

            val yoloScore = yoloMatch.second
            val ocrScore = if (!anchorText.isNullOrBlank()) 1.0f else 0.5f

            val conf = WEIGHT_YOLO * yoloScore + WEIGHT_OCR * ocrScore
            results.add(HybridMatchResult(yoloMatch.first, conf, 0f, yoloScore, ocrScore, "yolo"))
        }

        val best = results.filter { it.hybridConfidence >= MIN_HYBRID_CONFIDENCE }.maxByOrNull { it.hybridConfidence }
        if (best != null) {
            Log.d(TAG, "Match: label=$anchorLabel, conf=${"%.2f".format(best.hybridConfidence)} " +
                    "[acc=${"%.2f".format(best.accessibilityScore)}, yolo=${"%.2f".format(best.yoloScore)}, " +
                    "ocr=${"%.2f".format(best.ocrScore)}] via ${best.source}")
        } else {
            Log.d(TAG, "No match above $MIN_HYBRID_CONFIDENCE for label=$anchorLabel " +
                    "(${results.size} candidates, best=${results.maxByOrNull { it.hybridConfidence }?.hybridConfidence})")
        }
        return best
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSIBILITY TREE QUERY (normal mode)
    // ═══════════════════════════════════════════════════════════════════════

    private fun queryAccessibilityTree(
        label: String, text: String?, expectedBounds: RectF,
        normalizedAnchor: RectF?, screenWidth: Float, screenHeight: Float
    ): List<Pair<UIElement, Float>> {
        val allElements = AccessibilityAugmenter.captureAllInteractiveElements()
        if (allElements.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<UIElement, Float>>()
        val useNormalized = normalizedAnchor != null && screenWidth > 0 && screenHeight > 0

        for (el in allElements) {
            val nodeText = el.text
            if (!text.isNullOrBlank()) {
                if (!isTextMatching(nodeText, text)) continue
            } else {
                if (!el.label.equals(label, ignoreCase = true)) continue
            }

            val spatialScore = if (useNormalized) {
                computeNormalizedSpatialScore(normalizeRect(el.bounds, screenWidth, screenHeight), normalizedAnchor!!)
            } else {
                computeRawSpatialScore(el.bounds, expectedBounds)
            }
            val classScore = if (el.label.equals(label, ignoreCase = true)) 1.0f else 0.5f
            val textScore = if (!text.isNullOrBlank() && isTextMatching(nodeText, text)) 1.0f else 0.5f
            val conf = (spatialScore * 0.5f + classScore * 0.2f + textScore * 0.3f).coerceIn(0f, 1f)

            Log.d(TAG, "Acc match: text='${el.text}', bounds=${el.bounds}, " +
                    "spatial=${"%.3f".format(spatialScore)}, class=${"%.2f".format(classScore)}, " +
                    "conf=${"%.3f".format(conf)}, normalized=$useNormalized")

            if (conf > 0.2f) {
                results.add(Pair(el, conf))
            }
        }
        return results
    }

    private fun computeNormalizedSpatialScore(nodeNorm: RectF, anchorNorm: RectF): Float {
        val iou = calculateIoU(nodeNorm, anchorNorm)
        if (iou > 0.05f) return iou.coerceAtMost(1.0f)
        val dist = centerDistance(nodeNorm, anchorNorm)
        return (1.0f - (dist / 0.3f).coerceAtMost(1.0f)).coerceAtLeast(0f)
    }

    private fun computeRawSpatialScore(boundsF: RectF, expectedBounds: RectF): Float {
        val iou = calculateIoU(boundsF, expectedBounds)
        if (iou > 0.05f) return iou.coerceAtMost(1.0f)
        val dist = centerDistance(boundsF, expectedBounds)
        val maxDim = maxOf(expectedBounds.width(), expectedBounds.height(), 100f)
        return (1.0f - (dist / maxDim).coerceAtMost(1.0f)).coerceAtLeast(0f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // YOLO CANDIDATE SCORING (normal mode)
    // ═══════════════════════════════════════════════════════════════════════

    private fun scoreYoloCandidates(
        anchorLabel: String, anchorBounds: RectF, anchorText: String?,
        candidates: List<UIElement>, normalizedAnchor: RectF?,
        screenWidth: Float, screenHeight: Float
    ): List<Pair<UIElement, Float>> {
        val useNorm = normalizedAnchor != null && screenWidth > 0 && screenHeight > 0
        return candidates.filter { it.label.equals(anchorLabel, ignoreCase = true) }.mapNotNull { el ->
            if (!anchorText.isNullOrBlank()) {
                // If anchorText is required, candidate must match text
                if (!isTextMatching(el.text, anchorText)) return@mapNotNull null
            }
            val iou = if (useNorm) calculateIoU(normalizeRect(el.bounds, screenWidth, screenHeight), normalizedAnchor!!)
                      else calculateIoU(el.bounds, anchorBounds)
            val textBoost = if (!anchorText.isNullOrBlank() && isTextMatching(el.text, anchorText)) 0.2f else 0f
            val score = (iou + textBoost).coerceAtMost(1.0f)
            if (score > 0.05f) Pair(el, score) else null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("…", "...")
            .replace(Regex("\\.{2,}"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isTextMatching(candidateText: String?, targetText: String): Boolean {
        if (candidateText.isNullOrBlank() || targetText.isBlank()) return false
        val normCand = normalizeText(candidateText)
        val normTarget = normalizeText(targetText)
        if (normCand.isEmpty() || normTarget.isEmpty()) return false

        // 1. Direct containment
        if (normCand.contains(normTarget) || normTarget.contains(normCand)) return true

        // 2. Token overlap (handles truncation / ellipsis like "bo..." vs "born", or multiline fragments)
        val candWords = normCand.split(" ").filter { it.isNotBlank() }
        val targetWords = normTarget.split(" ").filter { it.isNotBlank() }
        if (candWords.isNotEmpty() && targetWords.isNotEmpty()) {
            val candSet = candWords.toSet()
            val commonCount = targetWords.count { word ->
                candSet.contains(word) || candWords.any { cw ->
                    (cw.startsWith(word) || word.startsWith(cw)) && minOf(cw.length, word.length) >= 3
                }
            }
            val targetMatchRatio = commonCount.toFloat() / targetWords.size
            if (targetMatchRatio >= 0.70f || (commonCount >= 4 && targetWords.size >= 4)) {
                return true
            }
        }

        // 3. Fuzzy bigram similarity
        return fuzzyTextSimilarity(normCand, normTarget) >= 0.70f
    }

    private fun classMatchScore(node: AccessibilityNodeInfo, label: String): Float {
        val cn = node.className?.toString()?.lowercase() ?: return 0f
        return when (label.lowercase()) {
            "button" -> if (cn.contains("button") || node.isClickable) 1.0f else 0.2f
            "input" -> if (cn.contains("edittext") || node.isEditable) 1.0f else 0.1f
            "toggle" -> if (cn.contains("switch") || cn.contains("toggle")) 1.0f else 0.1f
            "checkbox" -> if (cn.contains("checkbox")) 1.0f else 0.1f
            "radio" -> if (cn.contains("radio")) 1.0f else 0.1f
            "dropdown" -> if (cn.contains("spinner")) 1.0f else 0.1f
            "icon" -> if (cn.contains("image")) 0.7f else 0.1f
            else -> 0.3f
        }
    }

    fun fuzzyTextSimilarity(a: String, b: String): Float {
        val normA = normalizeText(a)
        val normB = normalizeText(b)
        if (normA.isBlank() || normB.isBlank()) return 0f
        val ba = normA.windowed(2).toSet()
        val bb = normB.windowed(2).toSet()
        if (ba.isEmpty() && bb.isEmpty()) return 1f
        val inter = ba.intersect(bb).size.toFloat()
        val union = ba.union(bb).size.toFloat()
        return if (union > 0) inter / union else 0f
    }

    private fun normalizeRect(rect: RectF, w: Float, h: Float) = RectF(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)

    private fun centerDistance(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX(); val dy = a.centerY() - b.centerY()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left); val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        if (iR < iL || iB < iT) return 0f
        val iA = (iR - iL) * (iB - iT)
        val uA = a.width() * a.height() + b.width() * b.height() - iA
        return if (uA > 0) iA / uA else 0f
    }
}
