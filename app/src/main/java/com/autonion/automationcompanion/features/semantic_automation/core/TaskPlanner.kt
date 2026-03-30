package com.autonion.automationcompanion.features.semantic_automation.core

import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement

/**
 * Deterministic task planner for common UI automation flows.
 *
 * Handles standard patterns like "search X on Y" without asking the LLM,
 * reserving LLM inference only for novel/complex UI interactions.
 *
 * Flow decomposition:
 *  - SEARCH flow: find search input → type query → press Enter → (LLM picks result)
 *  - OPEN flow:   handled entirely by AppLauncher pre-action
 *  - PLAY flow:   same as SEARCH (search for content, then LLM picks the video)
 *
 * Returns null when it cannot determine the next step deterministically,
 * signaling the engine to fall through to LLM inference.
 */
class TaskPlanner {

    companion object {
        private const val TAG = "TaskPlanner"
    }

    /**
     * Current phase of a deterministic plan.
     * The planner tracks which phase it's in via the step history.
     */
    enum class Phase {
        FIND_SEARCH,      // Looking for search input or search button
        TYPE_QUERY,       // Typing the search query
        SUBMIT_SEARCH,    // Pressing Enter to submit the search
        LLM_TAKEOVER      // Planner has done its job; LLM picks the result
    }

    /**
     * Attempts to predict the next action deterministically.
     *
     * @return An [ActionIntent] if the planner can handle this step, null otherwise.
     */
    fun predict(goal: SemanticGoal, uiState: ScreenUIState, completedActions: List<String>): ActionIntent? {
        // Only handle search/play flows — other flows are too varied for deterministic planning
        if (goal.task !in listOf("search", "play")) return null
        if (goal.query.isNullOrBlank()) return null

        val phase = determinePhase(completedActions)
        Log.d(TAG, "Task='${goal.task}', phase=$phase, query='${goal.query}'")

        return when (phase) {
            Phase.FIND_SEARCH -> findSearchElement(uiState)
            Phase.TYPE_QUERY -> typeQuery(goal, uiState)
            Phase.SUBMIT_SEARCH -> submitSearch()
            Phase.LLM_TAKEOVER -> null // Let the LLM take over
        }
    }

    /**
     * Determines the current phase based on what actions have been completed.
     * This is a simple state machine driven by the step history.
     */
    private fun determinePhase(completedActions: List<String>): Phase {
        val hasTyped = completedActions.any { it == "INPUT_TEXT" }
        val hasSubmitted = completedActions.any { it == "SUBMIT" }
        val hasClickedSearch = completedActions.any { it == "CLICK" }

        return when {
            hasSubmitted -> Phase.LLM_TAKEOVER  // Already submitted → LLM picks results
            hasTyped -> Phase.SUBMIT_SEARCH     // Already typed → submit
            hasClickedSearch -> Phase.TYPE_QUERY // Clicked search → type the query
            else -> Phase.FIND_SEARCH           // Haven't started → find search
        }
    }

    /**
     * Phase 1: Find the search input or search button on screen.
     */
    private fun findSearchElement(uiState: ScreenUIState): ActionIntent? {
        // Priority 1: Find an editable field (search input already visible)
        val editableField = uiState.elements.firstOrNull { it.isEditable }
        if (editableField != null) {
            val index = uiState.elements.indexOf(editableField)
            return makeAction(ActionType.CLICK, editableField, index,
                "TaskPlanner: Click search input '${editableField.text?.take(30) ?: "input"}'")
        }

        // Priority 2: Find a button/icon labeled "Search" or with search-related text
        val searchKeywords = listOf("search", "find", "magnify", "look")
        val searchButton = uiState.elements.firstOrNull { el ->
            el.isClickable && searchKeywords.any { kw ->
                el.text?.contains(kw, ignoreCase = true) == true
            }
        }
        if (searchButton != null) {
            val index = uiState.elements.indexOf(searchButton)
            return makeAction(ActionType.CLICK, searchButton, index,
                "TaskPlanner: Click search button '${searchButton.text?.take(30)}'")
        }

        // Can't find search — let LLM handle it
        Log.d(TAG, "No search element found deterministically, deferring to LLM")
        return null
    }

    /**
     * Phase 2: Type the search query into the focused input field.
     */
    private fun typeQuery(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val editableField = uiState.elements.firstOrNull { it.isEditable }
        if (editableField != null) {
            val index = uiState.elements.indexOf(editableField)
            val cx = (editableField.bounds.left + editableField.bounds.right) / 2f
            val cy = (editableField.bounds.top + editableField.bounds.bottom) / 2f
            return ActionIntent(
                type = ActionType.INPUT_TEXT,
                targetId = "slm_element_$index",
                targetPoint = PointF(cx, cy),
                inputText = goal.query,
                description = "TaskPlanner: Type '${goal.query}' into search field"
            )
        }
        Log.d(TAG, "No editable field found for typing, deferring to LLM")
        return null
    }

    /**
     * Phase 3: Submit the search by pressing Enter via IME.
     * Returns a special FINISH-like marker; the engine handles the actual IME press.
     *
     * Instead of returning an action, we directly trigger the IME action.
     */
    private fun submitSearch(): ActionIntent? {
        val success = AccessibilityTreeReader.performImeAction()
        Log.d(TAG, "Submit search via IME: $success")
        // Return null to signal the engine that we handled it internally
        // The engine will see the UI change on next iteration and proceed
        return if (success) {
            ActionIntent(
                type = ActionType.CLICK, // Placeholder — the IME was already pressed
                targetPoint = PointF(0f, 0f), // Dummy
                description = "TaskPlanner: Submitted search via Enter key"
            )
        } else null
    }

    private fun makeAction(type: ActionType, el: UIStateElement, index: Int, description: String): ActionIntent {
        val cx = (el.bounds.left + el.bounds.right) / 2f
        val cy = (el.bounds.top + el.bounds.bottom) / 2f
        return ActionIntent(
            type = type,
            targetId = "slm_element_$index",
            targetPoint = PointF(cx, cy),
            description = description
        )
    }
}
