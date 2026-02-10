package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import com.autonion.automationcompanion.features.screen_understanding_ml.core.TemporalTracker
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import com.autonion.automationcompanion.features.screen_understanding_ml.model.WorldState

class WorldStateManager(
    private val temporalTracker: TemporalTracker,
    private val anchorMemorySystem: AnchorMemorySystem
) {
    private var currentState: WorldState = WorldState(0, emptyList(), emptyMap())

    fun update(detectedElements: List<UIElement>): WorldState {
        // 1. Update Tracking
        val trackedElements = temporalTracker.update(detectedElements)

        // 2. Find Anchors
        val anchorMatches = anchorMemorySystem.findMatches(trackedElements)

        // 3. Build State
        currentState = WorldState(
            timestamp = System.currentTimeMillis(),
            visibleElements = trackedElements,
            matchedAnchors = anchorMatches
        )

        return currentState
    }

    fun getCurrentState(): WorldState = currentState
}
