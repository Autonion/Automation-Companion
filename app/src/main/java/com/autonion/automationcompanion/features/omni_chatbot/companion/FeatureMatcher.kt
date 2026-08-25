package com.autonion.automationcompanion.features.omni_chatbot.companion

/**
 * Maps natural-language user queries to known feature IDs.
 *
 * When the user asks something like "how do I use the flow builder?",
 * this class extracts a feature ID (e.g. "flow_builder") so the
 * companion can start the corresponding walkthrough.
 */
object FeatureMatcher {

    /**
     * Keywords/phrases mapped to feature IDs.
     * Order doesn't matter; the first feature whose keyword set
     * contains a match wins.
     */
    private val featureKeywords: Map<String, List<String>> = mapOf(
        "flow_builder" to listOf(
            "flow builder", "flow automation", "create flow", "node editor",
            "flow editor", "automation flow", "build a flow", "build flow"
        ),
        "gesture_recording" to listOf(
            "gesture", "gesture recording", "record gesture", "gesture playback",
            "gesture replay", "tap recording", "swipe recording", "record actions"
        ),
        "semantic_automation" to listOf(
            "semantic", "semantic automation", "semantic ai",
            "smart automation", "ai automation"
        ),
        "cross_device" to listOf(
            "cross device", "cross-device", "desktop agent", "desktop automation",
            "remote control", "control my desktop", "send to desktop",
            "connect desktop", "autonion desktop", "cross device sync",
            "cross device automation"
        ),
        "visual_trigger" to listOf(
            "visual trigger", "image trigger", "vision trigger",
            "image match", "template match", "visual automation",
            "image-based trigger", "image checker"
        ),
        "screen_ml" to listOf(
            "screen ml", "screen context ai", "screen context",
            "screen understanding", "screenml", "screen context al"
        ),
        "system_context" to listOf(
            "system context", "context automation", "battery automation",
            "wifi automation", "location automation", "time of day",
            "app-specific", "app specific automation"
        ),
        "debugger" to listOf(
            "debugger", "debug", "automation logs", "automation debugger",
            "log viewer", "debug logs"
        )
    )

    /**
     * Queries containing these keywords should NEVER trigger a walkthrough,
     * because no walkthrough exists for these topics. This prevents the LLM
     * from hallucinating an unrelated walkthrough tag (e.g. flow_builder)
     * when the user asks about browser extensions or installation.
     */
    private val excludedKeywords = listOf(
        "extension", "browser extension", "autonion extension",
        "install extension", "download extension", "android extension",
        "chrome extension", "kiwi", "lemur"
    )

    /**
     * Phrases that signal a "how-to" / walkthrough intent.
     * If the query contains one of these AND matches a feature, we trigger
     * a walkthrough instead of a plain text answer.
     */
    private val walkthroughTriggers = listOf(
        "how do i use", "how to use", "how does", "how do i",
        "show me", "guide me", "teach me", "walk me through",
        "explain how to", "help me with", "take me to",
        "open", "navigate to", "go to", "show me how",
        "what is", "how can i"
    )

    /**
     * Attempts to match the user query to a feature ID.
     *
     * @return The feature ID (e.g. "flow_builder") if a match is found,
     *         or null if the query doesn't map to any known feature.
     */
    fun matchFeature(query: String): String? {
        val lower = query.lowercase().trim()

        // Exclude topics that have no walkthrough — prevents LLM fallback from
        // hallucinating an unrelated walkthrough tag for these subjects.
        if (excludedKeywords.any { lower.contains(it) }) {
            return null
        }

        for ((featureId, keywords) in featureKeywords) {
            if (keywords.any { lower.contains(it) }) {
                return featureId
            }
        }
        return null
    }

    /**
     * Returns true if the query looks like a walkthrough/how-to request
     * (as opposed to an action command like "turn on wifi").
     */
    fun isWalkthroughQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        return walkthroughTriggers.any { lower.contains(it) }
    }

    /**
     * Returns true if the query is about a topic that should NEVER show a
     * walkthrough button (e.g. browser extensions, installation guides).
     */
    fun isExcludedFromWalkthrough(query: String): Boolean {
        val lower = query.lowercase().trim()
        return excludedKeywords.any { lower.contains(it) }
    }
}
