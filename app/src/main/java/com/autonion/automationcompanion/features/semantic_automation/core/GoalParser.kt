package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiConnectionStatus
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
class GoalParser(private val context: Context) {

    companion object {
        private const val TAG = "GoalParser"

        /**
         * System prompt that instructs the LLM on how to parse user commands.
         * Includes confidence self-rating to detect hallucinated/gibberish inputs.
         */
        private val SYSTEM_PROMPT = """
            You are a command parser for an Android automation assistant.
            Extract the user's intent into a JSON object with these fields:

            RULES:
            - "task" (required): one of: search, open, login, send_message, play, navigate, call, create, enable, disable, scroll, tap, type, back, unknown
            - "app_name": the app in lowercase (e.g. "flipkart", "youtube", "whatsapp", "settings"). Use null if not mentioned.
            - "domain": the website domain with correct TLD (e.g. "flipkart.com", "amazon.in", "youtube.com"). Use null for native-only apps or if not applicable.
            - "search_query": what the user wants to search/find/play. Use null if not applicable.
            - "confidence": a number from 0.0 to 1.0 indicating how confident you are in this parsing.
            - For compound commands like "open flipkart and search for headphones", extract the final intent: task=search, app=flipkart, query=headphones.
            - For system commands like "turn on wifi", use task=enable, search_query="wifi".
            - For "open <app>" commands with no search, use task=open, search_query=null.

            CONFIDENCE RULES:
            - Set confidence=1.0 when the command clearly mentions a well-known app or service and a clear action.
            - Set confidence=0.7-0.9 when the command is valid but somewhat ambiguous (e.g. missing app name).
            - Set confidence=0.3-0.6 when you are guessing the app name or task because the input is unclear.
            - Set confidence=0.0-0.2 when the command is gibberish, references apps/services you've never heard of, or makes no coherent sense.
            - If the app name in the command does NOT correspond to any real, well-known application or website, set confidence below 0.3 and task="unknown".
            - Do NOT invent or guess app names. If you don't recognize an app, set app_name=null and confidence low.

            RESPOND WITH ONLY A JSON OBJECT. Example:
            {"task":"play","app_name":null,"domain":null,"search_query":"one piece intro","confidence":0.8}
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
                    "description" to "The target app name in lowercase (e.g. flipkart, youtube, settings). Omit this field if no app is mentioned."
                ),
                "domain" to mapOf(
                    "type" to "string",
                    "description" to "The website domain with correct TLD (e.g. flipkart.com, amazon.in, youtube.com). Omit this field for native-only apps or when not applicable."
                ),
                "search_query" to mapOf(
                    "type" to "string",
                    "description" to "The search query or content to find. Omit this field if not applicable."
                ),
                "confidence" to mapOf(
                    "type" to "number",
                    "description" to "Confidence score 0.0-1.0 for how certain you are about this parsing. Low for gibberish or unrecognized apps."
                )
            ),
            "required" to listOf("task", "confidence")
        )
    }

    /**
     * Parse a raw user command into a [SemanticGoal] using the LLM.
     *
     * @param rawCommand The user's natural language command
     * @param llmEngine The connected Ollama engine (must be connected)
     * @param conversationContext Optional context from previous goals for better parsing
     * @return Parsed [SemanticGoal], or null if LLM is not connected or parsing failed
     */
    suspend fun parse(rawCommand: String, llmEngine: LocalServerLLMEngine, conversationContext: String? = null): SemanticGoal? {
        val command = rawCommand.trim()
        Log.d(TAG, "Parsing with semantic engine: \"$command\"")

        // All intent parsing is handled by the LLM. If the parsed goal is
        // missing info (e.g. which app), the engine's generic clarification
        // system will ask the user interactively.

        // ── Fallback: LLM-based parsing for complex/ambiguous commands ──
        if (llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
            Log.w(TAG, "LLM server not connected — cannot parse goal")
            return null
        }

        val userPrompt = buildString {
            if (!conversationContext.isNullOrBlank()) {
                append("CONTEXT: $conversationContext\n\n")
            }
            append(command)
        }

        val responseJson = llmEngine.chatSimpleJson(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt
        )

        if (responseJson == null) {
            Log.w(TAG, "LLM returned null response for goal parsing")
            return null
        }

        return parseGoalFromJson(command, responseJson)
    }

    /**
     * Parse a raw user command into a [SemanticGoal] using the Cloud API LLM.
     *
     * @param rawCommand The user's natural language command
     * @param cloudEngine The configured Cloud API engine
     * @param conversationContext Optional context from previous goals for better parsing
     * @return Parsed [SemanticGoal], or null if engine is not configured or parsing failed
     */
    suspend fun parse(rawCommand: String, cloudEngine: CloudApiLLMEngine, conversationContext: String? = null): SemanticGoal? {
        val command = rawCommand.trim()
        Log.d(TAG, "Parsing with Cloud API engine: \"$command\"")

        if (cloudEngine.connectionStatus.value != CloudApiConnectionStatus.CONNECTED) {
            if (cloudEngine.isConfigured) {
                Log.d(TAG, "Cloud API configured but not connected; initializing before goal parse")
                cloudEngine.initialize()
            }
            if (cloudEngine.connectionStatus.value != CloudApiConnectionStatus.CONNECTED) {
                Log.w(TAG, "Cloud API not connected — cannot parse goal")
                return null
            }
        }

        val userPrompt = buildString {
            if (!conversationContext.isNullOrBlank()) {
                append("CONTEXT: $conversationContext\n\n")
            }
            append(command)
        }

        val responseJson = cloudEngine.chatSimpleJson(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt
        )

        if (responseJson == null) {
            Log.w(TAG, "Cloud API returned null response for goal parsing")
            return null
        }

        return parseGoalFromJson(command, responseJson)
    }

    /**
     * Shared JSON → SemanticGoal parsing logic used by both engine overloads.
     */
    private fun parseGoalFromJson(rawCommand: String, responseJson: String): SemanticGoal? {
        return try {
            val json = JSONObject(responseJson.trim().let { raw ->
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
            })

            val task = json.optString("task", "unknown").lowercase().trim()
            val appName = json.optString("app_name", "").trim().lowercase()
                .let { if (it.isBlank() || it == "null") null else it }
            val domain = json.optString("domain", "").trim().lowercase()
                .let { if (it.isBlank() || it == "null") null else it }
            val searchQuery = json.optString("search_query", "").trim()
                .let { if (it.isBlank() || it == "null") null else it }

            val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)

            val goal = SemanticGoal(
                task = task,
                query = searchQuery,
                targetApp = appName,
                domain = domain,
                rawCommand = rawCommand,
                confidence = confidence
            )

            Log.d(TAG, "Parsed → task=${goal.task}, app=${goal.targetApp}, domain=${goal.domain}, query=${goal.query}, confidence=${goal.confidence}")
            goal
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $responseJson", e)
            null
        }
    }
}
