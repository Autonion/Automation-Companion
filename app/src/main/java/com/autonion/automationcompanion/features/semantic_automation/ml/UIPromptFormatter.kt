package com.autonion.automationcompanion.features.semantic_automation.ml

import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement
import com.autonion.automationcompanion.features.semantic_automation.model.ElementSource

/**
 * Record of one completed step, used to build context for the LLM.
 */
data class StepRecord(
    val iteration: Int,
    val action: String,           // e.g. "CLICK", "INPUT_TEXT"
    val elementText: String?,     // Text of the element the action targeted
    val elementIndex: Int,
    val success: Boolean,         // Whether the UI changed after the action
    val inputText: String? = null // For INPUT_TEXT actions
)

/**
 * Builds structured prompts for the Ollama Chat API.
 *
 * V2 improvements over V1:
 *  - Role-based (system + user) instead of single prompt blob
 *  - Pre-filters elements to reduce noise (max 15 candidates)
 *  - Includes step history so the LLM knows what it already tried
 *  - No model-specific tokens (Gemma <start_of_turn> etc.)
 */
object UIPromptFormatter {

    private const val MAX_ELEMENTS = 15

    /** Android layout class names that are meaningless noise for the LLM. */
    private val NOISY_TEXT = setOf(
        "viewgroup", "linearlayout", "framelayout", "relativelayout",
        "constraintlayout", "scrollview", "recyclerview", "coordinatorlayout",
        "appbarlayout", "toolbar", "imageview", "view"
    )

    // ── Legacy single-prompt (kept for OnDeviceSLMEngine compatibility) ──

    fun buildPrompt(goal: SemanticGoal, uiState: ScreenUIState): String {
        return "${buildSystemPrompt()}\n\n${buildUserPrompt(goal, uiState, emptyList())}"
    }

    // ── Chat API prompts ──────────────────────────────────────

    /**
     * Static system prompt: persona, rules, allowed actions, examples.
     * This is the same for every request and can be cached.
     */
    fun buildSystemPrompt(): String = buildString {
        append("You are an Android UI automation agent. You pick ONE action per turn.\n\n")

        append("RULES:\n")
        append("- ONLY use element_index values from the SCREEN ELEMENTS list below.\n")
        append("- ONLY pick elements marked (Clickable) for CLICK or (Editable) for INPUT_TEXT.\n")
        append("- text_to_type MUST come from the GOAL, never invent or copy from elsewhere.\n")
        append("- Use SCROLL_DOWN with element_index -1 if no element matches the goal.\n")
        append("- Use FINISH with element_index -1 when the goal is already achieved.\n")
        append("- Do NOT repeat actions from STEP HISTORY that failed.\n\n")

        append("OUTPUT FORMAT (JSON only, no other text):\n")
        append("{\"action\": \"<CLICK|INPUT_TEXT|SCROLL_DOWN|SCROLL_UP|FINISH>\", \"element_index\": <N>, \"text_to_type\": \"<text or empty>\"}\n")
    }

    /**
     * Dynamic user prompt: goal, filtered elements, step history.
     * This changes every iteration.
     */
    fun buildUserPrompt(
        goal: SemanticGoal,
        uiState: ScreenUIState,
        stepHistory: List<StepRecord>
    ): String = buildString {
        // Goal
        append("=== GOAL ===\n")
        append("${goal.rawCommand}\n\n")

        // Step History (last N steps)
        if (stepHistory.isNotEmpty()) {
            append("=== STEP HISTORY (most recent last) ===\n")
            for (step in stepHistory) {
                val status = if (step.success) "ok" else "FAILED-no-change"
                val detail = if (step.action == "INPUT_TEXT" && step.inputText != null)
                    " text='${step.inputText}'" else ""
                append("  Step ${step.iteration}: ${step.action} on '${step.elementText ?: "?"}' [${status}]${detail}\n")
            }
            append("\n")
        }

        // Filtered and ranked elements — ONLY actionable ones
        val filtered = filterAndRankElements(goal, uiState.elements)

        append("=== SCREEN ELEMENTS (${uiState.packageName ?: "unknown app"}) ===\n")
        if (filtered.isEmpty()) {
            append("No interactable elements found.\n")
        } else {
            filtered.forEachIndexed { index, el ->
                // Use the ORIGINAL index (position in the full list) so the engine can resolve it
                val originalIndex = uiState.elements.indexOf(el)
                append("[$originalIndex] ")

                // Capabilities — only show what the model can do with this element
                val caps = mutableListOf<String>()
                if (el.isEditable) caps.add("Editable")
                if (el.isClickable) caps.add("Clickable")
                if (el.isScrollable) caps.add("Scrollable")
                if (el.isChecked != null) caps.add(if (el.isChecked) "Checked" else "Unchecked")
                append("(${caps.joinToString(", ")}) ")

                // Type or HTML tag
                if (el.source == ElementSource.EXTENSION_DOM && !el.className.isNullOrBlank()) {
                    append("<${el.className}> ")
                } else {
                    append("[${el.type}] ")
                }

                // Text content (truncated to keep prompt compact)
                val fallbackText = if (el.source != ElementSource.EXTENSION_DOM) el.className?.substringAfterLast(".") else null
                val rawText = el.text?.ifBlank { null }
                    ?: fallbackText
                    ?: "no text"
                val contentText = rawText.replace("\n", " ").trim().take(60)
                append("\"$contentText\"\n")
            }
        }
    }

    // ── Element Pre-Filtering ─────────────────────────────────

    /**
     * Filters and ranks elements to reduce noise for the LLM.
     *
     * Strategy:
     *  1. HARD FILTER: Remove ALL non-interactive elements (can't act on them)
     *  2. Boost elements whose text matches goal keywords
     *  3. Cap at [MAX_ELEMENTS] to keep prompt size manageable
     */
    private fun filterAndRankElements(
        goal: SemanticGoal,
        elements: List<UIStateElement>
    ): List<UIStateElement> {
        // Step 1: Only keep elements the LLM can actually interact with
        // Also filter out elements whose only text is a raw Android class name
        val actionable = elements.filter { el ->
            val isInteractive = el.isClickable || el.isEditable || el.isScrollable || el.isChecked != null
            if (!isInteractive) return@filter false

            // Filter out elements with noisy/meaningless text
            val text = (el.text ?: el.className?.substringAfterLast(".") ?: "").trim().lowercase()
            text !in NOISY_TEXT
        }

        if (actionable.size <= MAX_ELEMENTS) return actionable

        val goalKeywords = extractKeywords(goal)

        // Step 2: Score each actionable element by relevance
        val scored = actionable.map { el ->
            val score = scoreElement(el, goalKeywords)
            el to score
        }

        // Sort by score descending, take top N
        return scored
            .sortedByDescending { it.second }
            .take(MAX_ELEMENTS)
            .map { it.first }
    }

    /**
     * Extracts meaningful keywords from the goal for matching.
     */
    private fun extractKeywords(goal: SemanticGoal): Set<String> {
        val words = mutableSetOf<String>()
        goal.query?.lowercase()?.split(" ")?.forEach { words.add(it) }
        goal.task.lowercase().let { words.add(it) }
        goal.rawCommand.lowercase().split(" ").forEach { words.add(it) }
        // Remove common stop words
        words.removeAll(setOf("on", "in", "the", "a", "an", "to", "for", "of", "and", "or", "is", "it"))
        return words
    }

    /**
     * Scores an element based on its relevance to the goal keywords.
     * Higher score = more likely to be useful.
     */
    private fun scoreElement(el: UIStateElement, keywords: Set<String>): Int {
        var score = 0

        // Interactive elements get baseline priority
        if (el.isClickable) score += 2
        if (el.isEditable) score += 3  // Editable fields are high-priority for search/type tasks
        if (el.isScrollable) score += 1

        // Text match gets big boost
        val text = el.text?.lowercase() ?: ""
        for (kw in keywords) {
            if (kw.length >= 3 && text.contains(kw)) {
                score += 5
            }
        }

        // Type-based bonus
        when (el.type) {
            "button" -> score += 2
            "input" -> score += 3
            "toggle", "checkbox" -> score += 2
            "text" -> score += 0 // Static text is lowest priority unless it matches keywords
            "icon" -> score += 1
        }

        // Penalty for ads and sponsored content
        if (text == "ad" || text.startsWith("ad ") || text.startsWith("ad\n") || text.contains("sponsor")) {
            score -= 50
        }

        // Elements with no text and not interactive are pure noise
        if (text.isBlank() && !el.isClickable && !el.isEditable) {
            score = -10
        }

        return score
    }

    // ── Ollama Structured Output Schema ───────────────────────

    /**
     * Returns the JSON schema for Ollama's `format` parameter.
     * This constrains the model's token generation to ONLY produce valid JSON
     * matching this exact structure. Eliminates hallucinated free-text entirely.
     */
    fun getOutputJsonSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "One of: CLICK, INPUT_TEXT, SCROLL_DOWN, SCROLL_UP, FINISH"
            ),
            "element_index" to mapOf(
                "type" to "integer",
                "description" to "Index of the target element from SCREEN ELEMENTS, or -1 for FINISH"
            ),
            "text_to_type" to mapOf(
                "type" to "string",
                "description" to "Text to enter for INPUT_TEXT actions, empty string otherwise"
            )
        ),
        "required" to listOf("action", "element_index", "text_to_type")
    )
}
