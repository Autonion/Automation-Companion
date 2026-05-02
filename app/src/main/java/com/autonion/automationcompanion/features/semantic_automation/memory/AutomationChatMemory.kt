package com.autonion.automationcompanion.features.semantic_automation.memory

import android.content.Context
import android.util.Log
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Record of a completed automation goal for persistent history.
 */
data class GoalRecord(
    val command: String,
    val outcome: String,
    val success: Boolean,
    val timestamp: Long,
    val appUsed: String?
)

/**
 * Manages chat memory for semantic automation across goals and sessions.
 *
 * Two layers:
 *   1. **Session memory** (LangChain4j [MessageWindowChatMemory]):
 *      Tracks agent turns within the current app session. Resets on app kill.
 *      Used for context like "do that again" or "search something else".
 *
 *   2. **Goal history** (SharedPreferences, JSON):
 *      Persists the last [MAX_GOAL_HISTORY] completed goals across app restarts.
 *      Used to provide context summaries for prompt injection.
 *
 * Thread-safe singleton — safe to call from any coroutine dispatcher.
 */
class AutomationChatMemory private constructor(context: Context) {

    companion object {
        private const val TAG = "AutomationChatMemory"
        private const val PREFS_NAME = "automation_chat_memory"
        private const val KEY_GOAL_HISTORY = "goal_history"
        private const val MAX_SESSION_MESSAGES = 10
        private const val MAX_GOAL_HISTORY = 10
        private const val MAX_CONTEXT_SUMMARY_CHARS = 500

        @Volatile
        private var INSTANCE: AutomationChatMemory? = null

        fun getInstance(context: Context): AutomationChatMemory {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutomationChatMemory(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Session memory — sliding window of agent turns within current app session.
     * Uses LangChain4j's MessageWindowChatMemory for compatibility with the existing
     * pattern used in OmniChatbotViewModel.
     */
    private val sessionMemory: ChatMemory = MessageWindowChatMemory.withMaxMessages(MAX_SESSION_MESSAGES)

    /**
     * Goal history — persisted list of completed goals (last N).
     * Loaded from SharedPreferences on init, saved after each update.
     */
    private val goalHistory: MutableList<GoalRecord> = loadGoalHistory()

    // ── Public API ──────────────────────────────────────────────

    /**
     * Records the start of a new automation goal.
     * Adds a user message to session memory with the command text.
     */
    @Synchronized
    fun recordGoalStart(command: String) {
        Log.d(TAG, "Goal started: $command")
        sessionMemory.add(UserMessage.from("Automation goal: $command"))
    }

    /**
     * Records the outcome of a completed automation goal.
     * Updates both session memory and persistent goal history.
     *
     * @param command The original user command
     * @param outcome Description of the outcome ("completed", "failed at step 2", etc.)
     * @param success Whether the goal was achieved
     * @param appUsed Optional app name that was used for this goal
     */
    @Synchronized
    fun recordGoalOutcome(command: String, outcome: String, success: Boolean, appUsed: String? = null) {
        Log.d(TAG, "Goal outcome: '$command' → $outcome (success=$success)")

        // Add to session memory
        val statusText = if (success) "✓ Completed" else "✗ Failed"
        sessionMemory.add(AiMessage.from("$statusText: $outcome"))

        // Add to persistent goal history
        goalHistory.add(GoalRecord(
            command = command,
            outcome = outcome,
            success = success,
            timestamp = System.currentTimeMillis(),
            appUsed = appUsed
        ))

        // Trim to max size
        while (goalHistory.size > MAX_GOAL_HISTORY) {
            goalHistory.removeAt(0)
        }

        // Persist
        persistGoalHistory()
    }

    /**
     * Records an intermediate agent turn (e.g., sub-goal completion).
     * Updates session memory only (not persisted as a separate goal).
     */
    @Synchronized
    fun recordAgentTurn(userIntent: String, agentAction: String) {
        Log.d(TAG, "Agent turn: '$userIntent' → $agentAction")
        sessionMemory.add(UserMessage.from("Sub-task: $userIntent"))
        sessionMemory.add(AiMessage.from("Result: $agentAction"))
    }

    /**
     * Builds a concise context summary for injection into LLM prompts.
     *
     * Includes:
     *   - Last 3 goal outcomes from persistent history
     *   - Formatted as natural language for LLM comprehension
     *   - Capped at [MAX_CONTEXT_SUMMARY_CHARS] characters
     *
     * @return Context summary string, or null if no history exists
     */
    @Synchronized
    fun buildContextSummary(): String? {
        if (goalHistory.isEmpty()) return null

        val summary = buildString {
            // Take last 3 goals for prompt injection (most recent context)
            val recentGoals = goalHistory.takeLast(3)

            for ((index, record) in recentGoals.withIndex()) {
                val status = if (record.success) "completed" else "failed: ${record.outcome}"
                val appInfo = if (record.appUsed != null) " on ${record.appUsed}" else ""

                when (index) {
                    recentGoals.lastIndex -> append("Most recent: \"${record.command}\"$appInfo ($status)")
                    recentGoals.lastIndex - 1 -> append("Before that: \"${record.command}\"$appInfo ($status)")
                    else -> append("Earlier: \"${record.command}\"$appInfo ($status)")
                }

                if (index < recentGoals.lastIndex) append(". ")
            }
        }

        // Enforce character limit
        val truncated = if (summary.length > MAX_CONTEXT_SUMMARY_CHARS) {
            summary.take(MAX_CONTEXT_SUMMARY_CHARS - 3) + "..."
        } else {
            summary
        }

        return truncated.ifBlank { null }
    }

    /**
     * Returns the number of goals in persistent history.
     */
    @Synchronized
    fun getGoalCount(): Int = goalHistory.size

    /**
     * Clears session memory only (current app session).
     * Goal history is preserved.
     */
    @Synchronized
    fun clearSession() {
        Log.d(TAG, "Session memory cleared")
        sessionMemory.clear()
    }

    /**
     * Clears all memory — both session and persistent history.
     */
    @Synchronized
    fun clearAll() {
        Log.d(TAG, "All memory cleared")
        sessionMemory.clear()
        goalHistory.clear()
        persistGoalHistory()
    }

    // ── Persistence (SharedPreferences) ─────────────────────────

    /**
     * Loads goal history from SharedPreferences.
     * Returns an empty list if no history exists or parsing fails.
     */
    private fun loadGoalHistory(): MutableList<GoalRecord> {
        val json = prefs.getString(KEY_GOAL_HISTORY, null) ?: return mutableListOf()

        return try {
            val array = JSONArray(json)
            val records = mutableListOf<GoalRecord>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                records.add(GoalRecord(
                    command = obj.getString("command"),
                    outcome = obj.getString("outcome"),
                    success = obj.getBoolean("success"),
                    timestamp = obj.getLong("timestamp"),
                    appUsed = obj.optString("app_used", "")
                        .let { if (it.isBlank() || it == "null") null else it }
                ))
            }

            Log.d(TAG, "Loaded ${records.size} goals from history")
            records
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load goal history: ${e.message}", e)
            mutableListOf()
        }
    }

    /**
     * Persists goal history to SharedPreferences as a JSON array.
     */
    private fun persistGoalHistory() {
        try {
            val array = JSONArray()
            for (record in goalHistory) {
                array.put(JSONObject().apply {
                    put("command", record.command)
                    put("outcome", record.outcome)
                    put("success", record.success)
                    put("timestamp", record.timestamp)
                    put("app_used", record.appUsed ?: JSONObject.NULL)
                })
            }

            prefs.edit().putString(KEY_GOAL_HISTORY, array.toString()).apply()
            Log.d(TAG, "Persisted ${goalHistory.size} goals to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist goal history: ${e.message}", e)
        }
    }
}
