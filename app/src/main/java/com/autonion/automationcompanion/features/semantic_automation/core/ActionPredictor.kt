package com.autonion.automationcompanion.features.semantic_automation.core

import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement

/**
 * Predicts the next action to take given the current goal and screen state.
 *
 * Phase 1: Rule-based heuristics.
 * Phase 2 (future): Trained model from AMEX dataset.
 */
class ActionPredictor {

    companion object {
        private const val TAG = "ActionPredictor"
    }

    /**
     * Predict the next action.
     * Returns an [ActionIntent] describing what to do next, or null if no action can be determined.
     */
    fun predict(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        Log.d(TAG, "Predicting action for task='${goal.task}' with ${uiState.elements.size} elements")

        // Debug: log all visible elements so we can troubleshoot
        uiState.elements.forEachIndexed { i, el ->
            Log.d(TAG, "  [$i] type=${el.type} text='${el.text}' " +
                "clickable=${el.isClickable} checkable=${el.isChecked != null} " +
                "checked=${el.isChecked} editable=${el.isEditable} " +
                "class=${el.className} bounds=${el.bounds}")
        }

        if (uiState.elements.isEmpty()) {
            Log.w(TAG, "No UI elements visible — suggesting scroll")
            return ActionIntent(
                type = ActionType.SCROLL_DOWN,
                targetPoint = PointF(540f, 1200f),
                description = "Scroll to reveal elements"
            )
        }

        return when (goal.task) {
            "search" -> predictSearchAction(goal, uiState)
            "login" -> predictLoginAction(goal, uiState)
            "open" -> predictOpenAction(goal, uiState)
            "tap" -> predictTapAction(goal, uiState)
            "type" -> predictTypeAction(goal, uiState)
            "enable" -> predictEnableAction(goal, uiState, targetState = true)
            "disable" -> predictEnableAction(goal, uiState, targetState = false)
            "scroll" -> ActionIntent(
                type = ActionType.SCROLL_DOWN,
                targetPoint = centerOf(uiState.elements.first()),
                description = "Scroll as requested"
            )
            "back" -> ActionIntent(
                type = ActionType.FINISH,
                description = "Go back"
            )
            else -> predictGenericAction(goal, uiState)
        }
    }

    // ── Enable / Disable ────────────────────────────────────

    private fun predictEnableAction(goal: SemanticGoal, uiState: ScreenUIState, targetState: Boolean): ActionIntent? {
        val queryWord = goal.query?.lowercase() ?: ""
        val targetLabel = if (targetState) "enable" else "disable"

        // Strategy 1: Find a toggle/switch/checkbox that matches the query keyword
        //             and is not yet in the desired state.
        val matchToggle = uiState.elements.firstOrNull { el ->
            isToggleLike(el) && matchesQuery(el, queryWord)
        }
        if (matchToggle != null) {
            if (matchToggle.isChecked == targetState) {
                Log.d(TAG, "Toggle already ${if (targetState) "ON" else "OFF"}")
                return ActionIntent(
                    type = ActionType.FINISH,
                    description = "${goal.query} is already ${if (targetState) "on" else "off"}"
                )
            }
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(matchToggle),
                description = "$targetLabel toggle '${matchToggle.text ?: matchToggle.type}'"
            )
        }

        // Strategy 2: Find ANY toggle/switch on the page (settings pages often
        //             have just one primary toggle at the top).
        val anyToggle = uiState.elements.firstOrNull { el -> isToggleLike(el) }
        if (anyToggle != null) {
            if (anyToggle.isChecked == targetState) {
                Log.d(TAG, "The toggle on screen is already ${if (targetState) "ON" else "OFF"}")
                return ActionIntent(
                    type = ActionType.FINISH,
                    description = "${goal.query} is already ${if (targetState) "on" else "off"}"
                )
            }
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(anyToggle),
                description = "$targetLabel toggle '${anyToggle.text ?: anyToggle.type}'"
            )
        }

        // Strategy 3: Find a clickable element whose text contains the query
        //             (maybe the toggle text itself says "Bluetooth" or "Wi-Fi").
        val textMatch = uiState.elements.firstOrNull { el ->
            el.isClickable && matchesQuery(el, queryWord)
        }
        if (textMatch != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(textMatch),
                description = "Tap '${textMatch.text}' to $targetLabel"
            )
        }

        // Strategy 4: On some phones the toggle itself is not directly accessible
        //             but there's a clickable row/container; tap the first clickable element.
        val firstClickable = uiState.elements.firstOrNull { el -> el.isClickable }
        if (firstClickable != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(firstClickable),
                description = "Tap first interactive element to $targetLabel"
            )
        }

        return scrollFallback(uiState)
    }

    /**
     * Check if an element looks like a toggle/switch/checkbox.
     */
    private fun isToggleLike(el: UIStateElement): Boolean {
        // Check type assigned by our mapper
        if (el.type in listOf("toggle", "checkbox", "radio")) return true

        // Check Android class name
        val cn = el.className?.lowercase() ?: ""
        if ("switch" in cn || "toggle" in cn || "checkbox" in cn) return true

        // Check if node is checkable (isChecked != null means isCheckable was true)
        if (el.isChecked != null) return true

        return false
    }

    /**
     * Check if an element's text matches the query keyword.
     */
    private fun matchesQuery(el: UIStateElement, query: String): Boolean {
        if (query.isBlank()) return false
        val text = el.text?.lowercase() ?: ""
        return text.contains(query)
    }

    // ── Search ──────────────────────────────────────────────

    private fun predictSearchAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val searchInput = uiState.elements.firstOrNull { el ->
            el.isEditable && (
                el.text?.contains("search", ignoreCase = true) == true ||
                el.text?.contains("find", ignoreCase = true) == true ||
                el.type == "input"
            )
        }
        if (searchInput != null) {
            return ActionIntent(
                type = ActionType.INPUT_TEXT,
                targetPoint = centerOf(searchInput),
                inputText = goal.query ?: goal.rawCommand,
                description = "Type '${goal.query}' into search field"
            )
        }

        val searchButton = uiState.elements.firstOrNull { el ->
            (el.type == "button" || el.type == "icon") &&
            el.text?.contains("search", ignoreCase = true) == true
        }
        if (searchButton != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(searchButton),
                description = "Tap search button"
            )
        }

        val iconElement = uiState.elements.firstOrNull { el ->
            el.type == "icon" && el.isClickable
        }
        if (iconElement != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(iconElement),
                description = "Tap icon (possible search)"
            )
        }

        return scrollFallback(uiState)
    }

    // ── Login ───────────────────────────────────────────────

    private fun predictLoginAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val usernameInput = uiState.elements.firstOrNull { el ->
            el.isEditable && (
                el.text?.contains("username", ignoreCase = true) == true ||
                el.text?.contains("email", ignoreCase = true) == true ||
                el.text?.contains("phone", ignoreCase = true) == true ||
                el.text?.contains("user", ignoreCase = true) == true
            )
        }
        if (usernameInput != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(usernameInput),
                description = "Focus username/email input"
            )
        }

        val passwordInput = uiState.elements.firstOrNull { el ->
            el.isEditable && el.text?.contains("password", ignoreCase = true) == true
        }
        if (passwordInput != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(passwordInput),
                description = "Focus password input"
            )
        }

        val loginButton = findButtonByText(uiState, "login", "log in", "sign in", "signin", "continue", "next")
        if (loginButton != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(loginButton),
                description = "Tap login button"
            )
        }

        return scrollFallback(uiState)
    }

    // ── Open ────────────────────────────────────────────────

    private fun predictOpenAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val target = goal.targetApp ?: goal.query ?: return null

        val matchEl = uiState.elements.firstOrNull { el ->
            el.isClickable && el.text?.contains(target, ignoreCase = true) == true
        }
        if (matchEl != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(matchEl),
                description = "Open '${matchEl.text}'"
            )
        }

        return scrollFallback(uiState)
    }

    // ── Tap ─────────────────────────────────────────────────

    private fun predictTapAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val target = goal.query ?: return null
        val el = uiState.elements.firstOrNull { it.text?.contains(target, ignoreCase = true) == true }
        if (el != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(el),
                description = "Tap '${el.text}'"
            )
        }
        return null
    }

    // ── Type ────────────────────────────────────────────────

    private fun predictTypeAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val input = uiState.elements.firstOrNull { it.isEditable }
        if (input != null) {
            return ActionIntent(
                type = ActionType.INPUT_TEXT,
                targetPoint = centerOf(input),
                inputText = goal.query ?: "",
                description = "Type '${goal.query}' into input"
            )
        }
        return null
    }

    // ── Generic fallback ────────────────────────────────────

    private fun predictGenericAction(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val query = goal.query ?: goal.rawCommand

        val matchEl = uiState.elements.firstOrNull { el ->
            el.isClickable && el.text?.contains(query, ignoreCase = true) == true
        }
        if (matchEl != null) {
            return ActionIntent(
                type = ActionType.CLICK,
                targetPoint = centerOf(matchEl),
                description = "Tap matching element '${matchEl.text}'"
            )
        }

        return scrollFallback(uiState)
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun findButtonByText(uiState: ScreenUIState, vararg keywords: String): UIStateElement? {
        return uiState.elements.firstOrNull { el ->
            (el.type == "button" || el.isClickable) &&
            keywords.any { kw -> el.text?.contains(kw, ignoreCase = true) == true }
        }
    }

    private fun centerOf(el: UIStateElement): PointF {
        return PointF(
            (el.bounds.left + el.bounds.right) / 2f,
            (el.bounds.top + el.bounds.bottom) / 2f
        )
    }

    private fun scrollFallback(uiState: ScreenUIState): ActionIntent {
        val scrollable = uiState.elements.firstOrNull { it.isScrollable }
        val point = if (scrollable != null) centerOf(scrollable) else PointF(540f, 1200f)
        return ActionIntent(
            type = ActionType.SCROLL_DOWN,
            targetPoint = point,
            description = "Scroll to find relevant elements"
        )
    }
}
