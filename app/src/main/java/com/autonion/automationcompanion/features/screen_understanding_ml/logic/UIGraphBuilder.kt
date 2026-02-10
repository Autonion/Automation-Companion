package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement

class UIGraphBuilder {

    fun buildGraph(elements: List<UIElement>): Map<String, List<String>> {
        val graph = mutableMapOf<String, MutableList<String>>()

        // Brute force spatial relationships (O(N^2)) - okay for small N (<100)
        for (i in elements.indices) {
            val a = elements[i]
            for (j in elements.indices) {
                if (i == j) continue
                val b = elements[j]

                val relation = determineRelationship(a, b)
                if (relation != null) {
                    graph.getOrPut(a.id) { mutableListOf() }.add("${relation}:${b.id}")
                }
            }
        }
        return graph
    }

    private fun determineRelationship(a: UIElement, b: UIElement): String? {
        val aCenterY = a.bounds.centerY()
        val bCenterY = b.bounds.centerY()
        val aCenterX = a.bounds.centerX()
        val bCenterX = b.bounds.centerX()

        // Vertical relationships
        if (b.bounds.top > a.bounds.bottom) return "above" // a is above b
        if (a.bounds.top > b.bounds.bottom) return "below" // a is below b
        
        // Horizontal relationships (roughly aligned vertically)
        if (Math.abs(aCenterY - bCenterY) < 50) { // Threshold
            if (b.bounds.left > a.bounds.right) return "left_of"
            if (a.bounds.left > b.bounds.right) return "right_of"
        }
        
        // Containment
        if (a.bounds.contains(b.bounds)) return "contains"
        if (b.bounds.contains(a.bounds)) return "inside"

        return null
    }
}
