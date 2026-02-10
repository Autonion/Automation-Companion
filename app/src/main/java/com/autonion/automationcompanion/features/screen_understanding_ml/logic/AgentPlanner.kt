package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.WorldState
import com.autonion.automationcompanion.features.screen_understanding_ml.model.Anchor
import android.graphics.PointF

class AgentPlanner {

    // Simple sequential planner for now
    private var currentStepIndex = 0
    private var steps: List<AutomationStep> = emptyList()

    data class AutomationStep(
        val targetAnchorId: String,
        val actionType: ActionType,
        val inputText: String? = null
    )

    fun loadPreset(newSteps: List<AutomationStep>) {
        steps = newSteps
        currentStepIndex = 0
    }

    fun plan(worldState: WorldState): ActionIntent {
        if (currentStepIndex >= steps.size) {
            return ActionIntent(ActionType.FINISH, description = "All steps completed")
        }

        val currentStep = steps[currentStepIndex]
        val targetElement = worldState.matchedAnchors[currentStep.targetAnchorId]

        if (targetElement != null) {
            // Target found!
            val center = PointF(targetElement.bounds.centerX(), targetElement.bounds.centerY())
            
            // Advance step (simulated, real agent would wait for validation)
            currentStepIndex++
            
            return ActionIntent(
                type = currentStep.actionType,
                targetId = targetElement.id,
                targetPoint = center,
                inputText = currentStep.inputText,
                description = "Execute ${currentStep.actionType} on ${targetElement.label}"
            )
        } else {
            // Target not found
            return ActionIntent(
                type = ActionType.WAIT,
                description = "Waiting for ${currentStep.targetAnchorId}..."
            )
        }
    }
}
