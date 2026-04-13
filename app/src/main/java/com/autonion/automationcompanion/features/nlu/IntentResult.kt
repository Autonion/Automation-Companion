package com.autonion.automationcompanion.features.nlu

import kotlin.time.Duration

/**
 * Classification result from the NLU IntentClassifier.
 *
 * Contains the detected intent type, confidence score,
 * extracted entities, and the raw user prompt.
 */
data class IntentResult(
    val intent: IntentType,
    val confidence: Float,
    val entities: ExtractedEntities,
    val rawPrompt: String
)

/**
 * All recognized intent types for the Omni-Chatbot router.
 */
enum class IntentType {
    /** Direct key press — "press enter", "type next", "hit space" */
    DIRECT_KEY_ACTION,

    /** System toggle — "turn off wifi", "enable bluetooth" */
    DIRECT_TOGGLE,

    /** Repeating/scheduled action — "click next every 1 minute" */
    SCHEDULED_ACTION,

    /** On-device semantic automation — "search shoes on flipkart" */
    DEVICE_AUTOMATION,

    /** Cross-device command — "on my laptop open chrome" */
    CROSS_DEVICE,

    /** Matched an FAQ entry — instant local answer */
    FAQ,

    /** General Q&A requiring RAG or LLM — "explain the automation features" */
    Q_AND_A
}

/**
 * Entities extracted from the user prompt by [EntityExtractor].
 */
data class ExtractedEntities(
    /** Resolved Android KeyEvent name, e.g. "KEYCODE_ENTER" */
    val keyName: String? = null,

    /** Human-readable key label, e.g. "enter", "next" */
    val keyLabel: String? = null,

    /** Raw text to type (for text input, not key presses) */
    val textToType: String? = null,

    /** Target application name, e.g. "flipkart", "spotify" */
    val appName: String? = null,

    /** Search query within an app, e.g. "shoes under 2000" */
    val searchQuery: String? = null,

    /** Toggle target, e.g. "wifi", "bluetooth", "dnd" */
    val toggleTarget: String? = null,

    /** Desired toggle state — true = enable, false = disable */
    val toggleDesiredState: Boolean? = null,

    /** Interval duration for scheduled actions */
    val interval: Duration? = null,

    /** Number of repetitions, null = infinite (until stopped) */
    val repeatCount: Int? = null,

    /** Target device for cross-device commands, e.g. "desktop", "laptop" */
    val targetDevice: String? = null,

    /** Semantic modifiers detected, e.g. "random", "any" */
    val semanticModifiers: List<String> = emptyList(),

    /** The task/action verb, e.g. "search", "open", "play" */
    val taskVerb: String? = null,

    /** Additional key-value context pairs */
    val extras: Map<String, String> = emptyMap()
)
