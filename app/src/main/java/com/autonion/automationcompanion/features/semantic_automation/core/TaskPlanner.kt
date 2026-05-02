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

    // Track how many times submitSearch has been attempted without advancing
    private var submitAttempts = 0

    /**
     * Attempts to predict the next action deterministically.
     *
     * @return An [ActionIntent] if the planner can handle this step, null otherwise.
     */
    fun predict(goal: SemanticGoal, uiState: ScreenUIState, completedActions: List<String>): ActionIntent? {
        // Only handle search/play flows — other flows are too varied for deterministic planning
        if (goal.task !in listOf("search", "play")) return null
        if (goal.query.isNullOrBlank()) return null

        // Early success detection for 'play' goals
        if (goal.task == "play" && isVideoPlayingOrPaused(uiState, goal)) {
            Log.i(TAG, "TaskPlanner detected video playback screen, goal achieved!")
            return ActionIntent(
                type = ActionType.FINISH,
                description = "TaskPlanner: Detected video is playing or on playback screen."
            )
        }

        val phase = determinePhase(completedActions)
        Log.d(TAG, "Task='${goal.task}', phase=$phase, query='${goal.query}'")

        return when (phase) {
            Phase.FIND_SEARCH -> findSearchElement(uiState)
            Phase.TYPE_QUERY -> {
                submitAttempts = 0 // Reset submit counter when entering TYPE phase
                typeQuery(goal, uiState)
            }
            Phase.SUBMIT_SEARCH -> {
                submitAttempts++
                if (submitAttempts > 2) {
                    Log.d(TAG, "Submit attempts exhausted ($submitAttempts), advancing to LLM_TAKEOVER")
                    null // Let LLM take over
                } else {
                    submitSearch()
                }
            }
            Phase.LLM_TAKEOVER -> null // Let the LLM take over
        }
    }

    /**
     * Heuristic to determine if the media player screen is currently active.
     */
    private fun isVideoPlayingOrPaused(uiState: ScreenUIState, goal: SemanticGoal): Boolean {
        // Only apply heuristic for known media apps (YouTube, Spotify, etc.) or if the goal explicitly mentioned them
        val packages = listOf("youtube", "spotify", "music")
        val isMediaApp = uiState.packageName?.let { pkg -> packages.any { pkg.contains(it, ignoreCase = true) } } == true

        var hasTimeline = false
        var hasPlaybackControls = false

        for (el in uiState.elements) {
            val text = el.text?.lowercase() ?: continue

            // Check for timeline string e.g. "0 minutes 1 second of 1 minute 51 seconds"
            // Ensure exact wording " of " instead of substring "of" (which triggered on "Official")
            val hasTimeUnit = text.contains("minute") || text.contains("second") || text.contains("hour")
            if (hasTimeUnit && text.contains(" of ") && Regex("\\d+.* of \\d+").containsMatchIn(text)) {
                hasTimeline = true
            }

            // explicit controls
            if (text == "pause video" || text == "pause" || text == "next video") {
                hasPlaybackControls = true
            }
        }

        return if (isMediaApp) {
            hasTimeline || hasPlaybackControls
        } else {
            // Be more strict for unknown apps to avoid false positives
            hasTimeline && hasPlaybackControls
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
     * Phase 3: Submit the search by pressing Enter via IME, or clicking a submit button.
     *
     * The IME action is performed internally. If it succeeds, return a WAIT
     * marker so the engine records a SUBMIT step and advances the planner state.
     * If IME fails, the LLM can look for a visible search/submit button.
     */
    private fun submitSearch(): ActionIntent? {
        val success = AccessibilityTreeReader.performImeAction()
        Log.d(TAG, "Submit search via IME: $success")

        if (success) {
            return ActionIntent(
                type = ActionType.WAIT,
                targetId = "planner_submit",
                description = "TaskPlanner: Submitted search and waiting for results"
            )
        }

        // IME failed entirely - let the LLM handle finding a submit button.
        return null // Let LLM handle finding the submit button
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
