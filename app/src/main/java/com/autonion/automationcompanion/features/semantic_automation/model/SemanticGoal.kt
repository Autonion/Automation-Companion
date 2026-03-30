package com.autonion.automationcompanion.features.semantic_automation.model

/**
 * Represents a parsed user goal for semantic automation.
 *
 * Example:
 *   User says: "search shoes on amazon"
 *   Parsed:  SemanticGoal(task="search", query="shoes", targetApp="amazon", rawCommand="search shoes on amazon")
 */
data class SemanticGoal(
    val task: String,              // e.g. "search", "login", "open", "send_message"
    val query: String? = null,     // e.g. "shoes", "john doe"
    val targetApp: String? = null, // e.g. "amazon", "whatsapp", "settings"
    val rawCommand: String,        // Original user input
    val extras: Map<String, String> = emptyMap() // Additional key-value pairs
)

/**
 * Status of a running semantic automation session.
 */
enum class AutomationStatus {
    IDLE,
    AWAITING_USER_INPUT,
    PARSING_GOAL,
    CAPTURING_SCREEN,
    BUILDING_UI_STATE,
    PREDICTING_ACTION,
    EXECUTING_ACTION,
    WAITING_FOR_SCREEN,
    COMPLETED,
    FAILED,
    CANCELLED
}
