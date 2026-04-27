package com.autonion.automationcompanion.features.semantic_automation.core

import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single atomic sub-goal decomposed from a complex user command.
 *
 * Example: "Open PPT and go to slide 10 and delete it"
 *   → SubGoal(1, "Open PPT"), SubGoal(2, "go to slide 10"), SubGoal(3, "delete it")
 */
data class SubGoal(
    val stepNumber: Int,
    val description: String,
    val dependsOnPrevious: Boolean = true
)

/**
 * Hybrid 3-tier task decomposer that breaks complex user commands into sequential sub-goals.
 *
 * Designed to minimize LLM dependency:
 *   - Tier 1: Regex-based splitting at conjunctions (~0ms, offline, handles ~80%)
 *   - Tier 2: NLP verb-boundary detection (~5ms, offline, handles ~15%)
 *   - Tier 3: LLM fallback for truly ambiguous cases (~2-3s, handles ~5%)
 *
 * Simple commands (single action) pass through unchanged as a 1-item list.
 */
class TaskDecomposer {

    companion object {
        private const val TAG = "TaskDecomposer"

        /**
         * Ordered list of conjunction patterns to split commands at.
         * More specific patterns come first to avoid partial matches.
         */
        private val CONJUNCTION_PATTERNS = listOf(
            "\\s+and\\s+then\\s+",        // "open X and then search Y"
            "\\s+and\\s+after\\s+that\\s+", // "open X and after that do Y"
            "\\s+after\\s+that\\s+",       // "open X after that do Y"
            "\\s+then\\s+",                // "open X then search Y"
            "\\s+next\\s+",                // "do X next do Y"
            ",\\s+then\\s+",               // "open X, then do Y"
            ",\\s+and\\s+",                // "open X, and do Y"
            ",\\s*(?=[a-z])"               // "open X, search Y" (comma + lowercase = new clause)
        )

        /**
         * Action verbs that indicate the start of a new sub-goal.
         * Used for both validation (Tier 1) and verb-boundary detection (Tier 2).
         */
        private val ACTION_VERBS = setOf(
            "open", "launch", "start",
            "search", "find", "look",
            "go", "navigate", "switch",
            "click", "tap", "press", "select",
            "type", "write", "enter", "input",
            "delete", "remove", "clear",
            "play", "pause", "stop", "resume",
            "close", "exit", "quit",
            "save", "download", "upload",
            "send", "share", "forward",
            "scroll", "swipe",
            "enable", "disable", "turn", "toggle",
            "copy", "paste", "cut"
        )

        /**
         * Words that commonly precede action verbs but don't indicate a new sub-goal.
         * e.g., "go TO the settings" — "to" precedes "the" not a new action verb.
         */
        private val NON_BOUNDARY_PREDECESSORS = setOf(
            "to", "and", "or", "the", "a", "an", "then",
            "it", "this", "that", "my", "your", "its"
        )

        /**
         * System prompt for Tier 3 LLM-based decomposition.
         * Only used when Tiers 1 and 2 fail to find clear boundaries.
         */
        private val DECOMPOSE_SYSTEM_PROMPT = """
            You are a task planner for an Android/Desktop automation assistant.
            Given a user command, break it into sequential atomic steps.

            RULES:
            - Each step must be a SINGLE action executable on ONE screen.
            - Order steps logically (you can't interact with an app before opening it).
            - For simple commands like "open spotify", return just 1 step.
            - For compound commands, split at natural action boundaries.
            - Keep step descriptions short and actionable.

            Return ONLY a JSON array. Example:
            [{"step":1,"action":"Open PowerPoint"},{"step":2,"action":"Go to slide 10"},{"step":3,"action":"Delete the current slide"}]
        """.trimIndent()
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Decomposes a raw user command into ordered, atomic sub-goals.
     *
     * - Simple commands ("open spotify") → 1-item list (no overhead)
     * - Compound commands ("open PPT and go to slide 10 and delete it") → ordered list
     *
     * Uses a 3-tier strategy to minimize LLM usage:
     *   Tier 1 (Regex) → Tier 2 (Verb Boundaries) → Tier 3 (LLM Fallback)
     *
     * @param rawCommand The user's raw natural language command
     * @param llmEngine Optional LLM engine for Tier 3 fallback
     * @param conversationContext Optional previous conversation context for ambiguity resolution
     * @return Ordered list of sub-goals (always at least 1 item)
     */
    suspend fun decompose(
        rawCommand: String,
        llmEngine: LocalServerLLMEngine? = null,
        conversationContext: String? = null
    ): List<SubGoal> {
        val command = rawCommand.trim()

        if (command.isBlank()) {
            return listOf(SubGoal(1, command))
        }

        // ── Tier 1: Regex-based conjunction splitting ──
        val regexResult = splitByConjunctions(command)
        if (regexResult.size > 1) {
            // Validate that each fragment has at least one action verb
            val allValid = regexResult.all { hasActionVerb(it) }
            if (allValid) {
                val subGoals = regexResult.mapIndexed { i, desc ->
                    SubGoal(stepNumber = i + 1, description = desc.trim())
                }
                Log.d(TAG, "Tier 1 (Regex): Decomposed into ${subGoals.size} sub-goals")
                subGoals.forEach { Log.d(TAG, "  Step ${it.stepNumber}: ${it.description}") }
                return subGoals
            }
            Log.d(TAG, "Tier 1: Split found ${regexResult.size} fragments, but not all have action verbs")
        }

        // ── Tier 2: Verb-boundary detection ──
        val verbResult = splitByVerbBoundaries(command)
        if (verbResult.size > 1) {
            val subGoals = verbResult.mapIndexed { i, desc ->
                SubGoal(stepNumber = i + 1, description = desc.trim())
            }
            Log.d(TAG, "Tier 2 (Verb Boundaries): Decomposed into ${subGoals.size} sub-goals")
            subGoals.forEach { Log.d(TAG, "  Step ${it.stepNumber}: ${it.description}") }
            return subGoals
        }

        // ── Check if decomposition is even needed ──
        val verbCount = countActionVerbs(command)
        if (verbCount <= 1) {
            Log.d(TAG, "Simple command (${verbCount} verb): no decomposition needed")
            return listOf(SubGoal(1, command))
        }

        // ── Tier 3: LLM fallback (only when multiple verbs but no clear split) ──
        Log.d(TAG, "Tier 3: $verbCount verbs but no clear split points, attempting LLM decomposition")
        if (llmEngine != null && llmEngine.connectionStatus.value == ServerConnectionStatus.CONNECTED) {
            val llmResult = decomposeWithLLM(command, llmEngine, conversationContext)
            if (llmResult != null && llmResult.size > 1) {
                Log.d(TAG, "Tier 3 (LLM): Decomposed into ${llmResult.size} sub-goals")
                llmResult.forEach { Log.d(TAG, "  Step ${it.stepNumber}: ${it.description}") }
                return llmResult
            }
        }

        // ── Fallback: treat as single goal ──
        Log.d(TAG, "All tiers exhausted, treating as single goal")
        return listOf(SubGoal(1, command))
    }

    // ── Tier 1: Regex-Based Conjunction Splitting ────────────────

    /**
     * Splits a command at conjunction boundaries (and then, then, after that, commas).
     * Returns the fragments. If no split is possible, returns a 1-item list.
     */
    internal fun splitByConjunctions(command: String): List<String> {
        for (pattern in CONJUNCTION_PATTERNS) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val parts = regex.split(command).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1) {
                return parts
            }
        }
        return listOf(command)
    }

    // ── Tier 2: Verb-Boundary Detection ────────────────────────

    /**
     * Splits a command by detecting action verb boundaries.
     *
     * For commands without explicit conjunctions like "open PPT go to slide 10 delete it",
     * this finds points where a new action verb starts a new clause.
     */
    internal fun splitByVerbBoundaries(command: String): List<String> {
        val words = command.split("\\s+".toRegex())
        if (words.size < 3) return listOf(command)

        val boundaries = mutableListOf(0) // First word is always a boundary start

        for (i in 1 until words.size) {
            val word = words[i].lowercase().trimEnd(',', '.', '!', '?')

            if (word in ACTION_VERBS) {
                // Check that the previous word isn't a connecting word
                val prev = words[i - 1].lowercase().trimEnd(',', '.', '!', '?')
                if (prev !in NON_BOUNDARY_PREDECESSORS) {
                    boundaries.add(i)
                }
            }
        }

        if (boundaries.size <= 1) return listOf(command)

        // Build fragments from boundaries
        val fragments = mutableListOf<String>()
        for (j in boundaries.indices) {
            val start = boundaries[j]
            val end = if (j + 1 < boundaries.size) boundaries[j + 1] else words.size
            val fragment = words.subList(start, end).joinToString(" ").trim()
            if (fragment.isNotBlank()) {
                fragments.add(fragment)
            }
        }

        return fragments
    }

    // ── Tier 3: LLM Fallback ────────────────────────────────────

    /**
     * Uses the LLM to decompose a command that Tiers 1 and 2 couldn't handle.
     * Returns null if LLM fails or returns unusable output.
     */
    private suspend fun decomposeWithLLM(
        command: String,
        llmEngine: LocalServerLLMEngine,
        conversationContext: String?
    ): List<SubGoal>? {
        val userPrompt = buildString {
            if (!conversationContext.isNullOrBlank()) {
                append("CONTEXT: $conversationContext\n\n")
            }
            append("COMMAND: $command")
        }

        val responseJson = llmEngine.chatSimpleJson(
            systemPrompt = DECOMPOSE_SYSTEM_PROMPT,
            userPrompt = userPrompt
        )

        if (responseJson == null) {
            Log.w(TAG, "LLM decomposition returned null")
            return null
        }

        return try {
            parseLLMDecomposition(responseJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM decomposition: $responseJson", e)
            null
        }
    }

    /**
     * Parses the LLM JSON response into SubGoal objects.
     * Handles both array format and wrapped {"steps": [...]} format.
     */
    private fun parseLLMDecomposition(json: String): List<SubGoal>? {
        val trimmed = json.trim()

        val jsonArray: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                // Try common wrapper keys
                when {
                    obj.has("steps") -> obj.getJSONArray("steps")
                    obj.has("sub_goals") -> obj.getJSONArray("sub_goals")
                    obj.has("tasks") -> obj.getJSONArray("tasks")
                    else -> return null
                }
            }
            else -> return null
        }

        if (jsonArray.length() == 0) return null

        val subGoals = mutableListOf<SubGoal>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val description = item.optString("action", "")
                .ifBlank { item.optString("description", "") }
                .ifBlank { item.optString("step_description", "") }
                .trim()

            if (description.isNotBlank()) {
                subGoals.add(SubGoal(
                    stepNumber = item.optInt("step", i + 1),
                    description = description
                ))
            }
        }

        return if (subGoals.isNotEmpty()) subGoals else null
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Checks if a text fragment contains at least one action verb.
     */
    internal fun hasActionVerb(text: String): Boolean {
        val words = text.lowercase().split("\\s+".toRegex())
        return words.any { word ->
            word.trimEnd(',', '.', '!', '?') in ACTION_VERBS
        }
    }

    /**
     * Counts the number of distinct action verbs in a command.
     * Used to decide if Tier 3 (LLM) is worth attempting.
     */
    internal fun countActionVerbs(command: String): Int {
        val words = command.lowercase().split("\\s+".toRegex())
        return words.count { word ->
            word.trimEnd(',', '.', '!', '?') in ACTION_VERBS
        }
    }
}
