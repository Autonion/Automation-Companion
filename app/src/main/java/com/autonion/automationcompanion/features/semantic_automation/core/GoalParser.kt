package com.autonion.automationcompanion.features.semantic_automation.core

import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import org.json.JSONObject

/**
 * Parses a natural language user command into a structured [SemanticGoal].
 *
 * Uses the connected Ollama LLM to extract structured intent from ANY prompt —
 * no hardcoded app aliases, no keyword matching, no TLD guessing.
 *
 * The LLM naturally understands:
 *  - App names (flipkart, spotify, telegram, etc.)
 *  - Website domains with correct TLDs (flipkart.com, amazon.in, chat.openai.com)
 *  - Compound commands ("open X and then search for Y")
 *  - System actions ("turn on wifi", "enable bluetooth")
 *
 * Requires the Ollama server to be connected. If not connected, returns null
 * to signal the caller to show a "connect to server" message.
 */
class GoalParser {

    companion object {
        private const val TAG = "GoalParser"

        /**
         * System prompt that instructs the LLM on how to parse user commands.
         */
        private val SYSTEM_PROMPT = """
            You are a command parser for an Android automation assistant.
            Extract the user's intent into structured JSON.

            RULES:
            - "task" must be one of: search, open, login, send_message, play, navigate, call, create, enable, disable, scroll, tap, type, back, unknown
            - "app_name" is the app the user wants to interact with (e.g. "flipkart", "youtube", "whatsapp", "settings"). Use lowercase. null if not mentioned.
            - "domain" is the website domain with correct TLD if the task involves a website (e.g. "flipkart.com", "amazon.in", "youtube.com", "chat.openai.com"). null for native-only apps like settings, calculator, whatsapp, phone dialer, etc.
            - "search_query" is what the user wants to search/find/play. null if not applicable.
            - For compound commands like "open flipkart and search for headphones", extract the final intent: task=search, app=flipkart, query=headphones.
            - For system commands like "turn on wifi", use task=enable, app_name=null, domain=null, search_query="wifi".
            - For "open <app>" commands with no search, use task=open, search_query=null.
        """.trimIndent()

        /**
         * JSON schema for Ollama's structured output constraint.
         * Forces the model to output exactly this shape.
         */
        val GOAL_PARSE_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task" to mapOf(
                    "type" to "string",
                    "description" to "The action type: search, open, login, send_message, play, navigate, call, create, enable, disable, scroll, tap, type, back, unknown"
                ),
                "app_name" to mapOf(
                    "type" to "string",
                    "description" to "The target app name in lowercase (e.g. flipkart, youtube, settings), or empty string if not applicable"
                ),
                "domain" to mapOf(
                    "type" to "string",
                    "description" to "The website domain with correct TLD (e.g. flipkart.com, amazon.in, youtube.com), or empty string for native-only apps"
                ),
                "search_query" to mapOf(
                    "type" to "string",
                    "description" to "The search query or content to find, or empty string if not applicable"
                )
            ),
            "required" to listOf("task", "app_name", "domain", "search_query")
        )
    }

    /**
     * Parse a raw user command into a [SemanticGoal] using the LLM.
     *
     * @param rawCommand The user's natural language command
     * @param llmEngine The connected Ollama engine (must be connected)
     * @return Parsed [SemanticGoal], or null if LLM is not connected or parsing failed
     */
    suspend fun parse(rawCommand: String, llmEngine: LocalServerLLMEngine): SemanticGoal? {
        val command = rawCommand.trim()
        Log.d(TAG, "Parsing with LLM: \"$command\"")

        if (llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
            Log.w(TAG, "LLM server not connected — cannot parse goal")
            return null
        }

        val responseJson = llmEngine.chatWithSchema(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = command,
            jsonSchema = GOAL_PARSE_SCHEMA
        )

        if (responseJson == null) {
            Log.w(TAG, "LLM returned null response for goal parsing")
            return null
        }

        return try {
            val json = JSONObject(responseJson.trim().let { raw ->
                // Safety: extract JSON object if there's extra text
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
            })

            val task = json.optString("task", "unknown").lowercase().trim()
            val appName = json.optString("app_name", "").trim().lowercase().ifBlank { null }
            val domain = json.optString("domain", "").trim().lowercase().ifBlank { null }
            val searchQuery = json.optString("search_query", "").trim().ifBlank { null }

            val goal = SemanticGoal(
                task = task,
                query = searchQuery,
                targetApp = appName,
                domain = domain,
                rawCommand = command
            )

            Log.d(TAG, "LLM parsed → task=${goal.task}, app=${goal.targetApp}, domain=${goal.domain}, query=${goal.query}")
            goal
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response: $responseJson", e)
            null
        }
    }
}
