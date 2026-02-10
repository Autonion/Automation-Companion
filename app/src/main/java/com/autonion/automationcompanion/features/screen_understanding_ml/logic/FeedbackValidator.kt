package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.WorldState

class FeedbackValidator {

    fun validate(
        action: ActionIntent,
        previousState: WorldState,
        currentState: WorldState
    ): Boolean {
        // Simple heuristic validation
        // In a real system, we'd check if the target element disappeared, or if a new screen loaded.
        
        if (action.targetId != null) {
            // If we clicked something, maybe we expect it to NOT be there, or screen context to change
            // For now, let's just assume if the screen changed significantly, it worked.
            
            // Check if processed visual fingerprint changed or visible elements count changed
            if (previousState.visibleElements.size != currentState.visibleElements.size) {
                 return true
            }
        }
        
        return true // Default optimistic
    }
}
