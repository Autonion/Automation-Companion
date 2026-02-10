package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import com.autonion.automationcompanion.features.screen_understanding_ml.model.Anchor
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement

class AnchorMemorySystem {

    private val anchors = mutableMapOf<String, Anchor>()

    fun saveAnchor(anchor: Anchor) {
        anchors[anchor.id] = anchor
    }

    fun getAnchor(id: String): Anchor? = anchors[id]

    fun getAllAnchors(): List<Anchor> = anchors.values.toList()

    fun findMatches(visibleElements: List<UIElement>): Map<String, UIElement> {
        val matches = mutableMapOf<String, UIElement>()

        for (anchor in anchors.values) {
            // Find best match for this anchor
            // 1. Filter by class
            val candidates = visibleElements.filter { it.label == anchor.targetClass }
            
            // 2. Score candidates based on spatial proximity/relationships/text
            var bestCandidate: UIElement? = null
            var bestScore = 0f

            for (candidate in candidates) {
                var score = 0f
                
                // Text match (strong signal)
                if (anchor.targetText != null && candidate.text != null) {
                    if (candidate.text.contains(anchor.targetText, ignoreCase = true)) {
                        score += 1.0f
                    }
                }
                
                // Spatial match (relative match)
                // Normalize candidate center
                // This is simplified. Real impl would use spatial graph.
                
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }

            if (bestCandidate != null && bestScore > 0.5f) { // Threshold
                matches[anchor.id] = bestCandidate
            }
        }
        return matches
    }
}
