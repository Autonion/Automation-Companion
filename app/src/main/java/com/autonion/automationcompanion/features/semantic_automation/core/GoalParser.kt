package com.autonion.automationcompanion.features.semantic_automation.core

import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal

/**
 * Parses a natural language user command into a structured [SemanticGoal].
 *
 * Phase 1: Rule-based keyword extraction.
 * Phase 2 (future): Local LLM inference via MediaPipe / ONNX / TFLite.
 *
 * The parser is deliberately simple for now — it handles the most common
 * patterns and can be swapped for an LLM-backed implementation later.
 */
class GoalParser {

    companion object {
        private const val TAG = "GoalParser"

        /** Well-known app name aliases → package-style identifiers. */
        private val APP_ALIASES = mapOf(
            "amazon" to "amazon",
            "whatsapp" to "whatsapp",
            "instagram" to "instagram",
            "youtube" to "youtube",
            "chrome" to "chrome",
            "settings" to "settings",
            "gmail" to "gmail",
            "maps" to "maps",
            "google maps" to "maps",
            "play store" to "playstore",
            "twitter" to "twitter",
            "x" to "twitter",
            "spotify" to "spotify",
            "facebook" to "facebook",
            "telegram" to "telegram",
            "netflix" to "netflix",
            "uber" to "uber",
            "camera" to "camera",
            "calculator" to "calculator",
            "clock" to "clock",
            "calendar" to "calendar",
            "contacts" to "contacts",
            "messages" to "messages",
            "phone" to "phone",
            "files" to "files"
        )

        /** Keywords that indicate a task type. */
        private val TASK_PATTERNS = listOf(
            Regex("(?i)^(search|find|look for|look up)\\b") to "search",
            Regex("(?i)^(open|launch|start|go to)\\b") to "open",
            Regex("(?i)^(login|log in|sign in|signin)\\b") to "login",
            Regex("(?i)^(send|message|text|chat|write)\\b") to "send_message",
            Regex("(?i)^(play|watch|listen)\\b") to "play",
            Regex("(?i)^(navigate|directions|route)\\b") to "navigate",
            Regex("(?i)^(call|dial|ring)\\b") to "call",
            Regex("(?i)^(set|create|add|make)\\b") to "create",
            Regex("(?i)^(turn on|enable|activate)\\b") to "enable",
            Regex("(?i)^(turn off|disable|deactivate)\\b") to "disable",
            Regex("(?i)^(scroll|swipe)\\b") to "scroll",
            Regex("(?i)^(tap|click|press|hit)\\b") to "tap",
            Regex("(?i)^(type|enter|input|fill)\\b") to "type",
            Regex("(?i)^(go back|back|return)\\b") to "back"
        )

        /** Prepositions that separate the query from the target app. */
        private val APP_PREPOSITIONS = listOf(
            " on ", " in ", " using ", " with ", " via ", " through ", " at "
        )
    }

    /**
     * Parse a raw user command into a [SemanticGoal].
     */
    fun parse(rawCommand: String): SemanticGoal {
        val command = rawCommand.trim()
        Log.d(TAG, "Parsing: \"$command\"")

        // 1. Detect task type
        var task = "unknown"
        var remaining = command
        for ((pattern, taskType) in TASK_PATTERNS) {
            val match = pattern.find(command)
            if (match != null) {
                task = taskType
                remaining = command.substring(match.range.last + 1).trim()
                break
            }
        }

        // 2. Extract target app (check for "on <app>" / "in <app>" etc.)
        var targetApp: String? = null
        var query: String? = remaining.ifBlank { null }

        for (prep in APP_PREPOSITIONS) {
            val prepIndex = remaining.lowercase().lastIndexOf(prep)
            if (prepIndex >= 0) {
                val appCandidate = remaining.substring(prepIndex + prep.length).trim().lowercase()
                val matchedApp = APP_ALIASES.entries.firstOrNull { (alias, _) ->
                    appCandidate.startsWith(alias)
                }
                if (matchedApp != null) {
                    targetApp = matchedApp.value
                    query = remaining.substring(0, prepIndex).trim().ifBlank { null }
                    break
                }
            }
        }

        // 3. If task is "open" and no explicit app found, the entire remaining is the app
        if (task == "open" && targetApp == null && !remaining.isBlank()) {
            val appCandidate = remaining.trim().lowercase()
            val matchedApp = APP_ALIASES.entries.firstOrNull { (alias, _) ->
                appCandidate.contains(alias)
            }
            targetApp = matchedApp?.value ?: appCandidate
            query = null
        }

        val goal = SemanticGoal(
            task = task,
            query = query,
            targetApp = targetApp,
            rawCommand = command
        )

        Log.d(TAG, "Parsed → task=${goal.task}, query=${goal.query}, app=${goal.targetApp}")
        return goal
    }
}
