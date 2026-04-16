package com.autonion.automationcompanion.features.omni_chatbot.model

import com.autonion.automationcompanion.features.nlu.IntentType
import java.util.UUID

/**
 * Represents a single message in the Omni-Chatbot.
 */
data class OmniChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val mode: ResponseMode = ResponseMode.DIRECT,
    val timestamp: Long = System.currentTimeMillis(),
    /** Optional widget to render in the message (e.g., Stop button for timers) */
    val actionWidget: ActionWidget? = null,
    /** Whether the message is still being updated (streaming-style) */
    val isStreaming: Boolean = false
)

/**
 * Indicates which engine/mode handled the response.
 * Displayed as a badge/icon in the chat bubble.
 */
enum class ResponseMode(val label: String, val emoji: String) {
    /** Performed locally with no LLM — key press, toggle */
    DIRECT("Direct", "🎯"),

    /** Running via SemanticAutomationEngine */
    AGENT("Agent", "🤖"),

    /** Sent to Desktop agent (with response) */
    DESKTOP("Desktop", "🔗"),

    /** Answered from FAQ database — instant, no LLM */
    FAQ("FAQ", "💡"),

    /** RAG-based answer from knowledge docs */
    KNOWLEDGE("Knowledge", "📚"),

    /** LLM conversational response */
    CHAT("Chat", "💬"),

    /** Timer/scheduled action running */
    SCHEDULED("Timer", "⏱️"),

    /** System/error message */
    SYSTEM("System", "⚙️")
}

/**
 * Optional interactive widget attached to a chat message.
 */
sealed class ActionWidget {
    /** Stop button for scheduled/long-running tasks */
    data class StopButton(val taskId: String) : ActionWidget()

    /** Progress indicator with current step info */
    data class Progress(val step: Int, val total: Int, val description: String) : ActionWidget()

    /** Quick-reply chips */
    data class QuickReplies(val options: List<String>) : ActionWidget()
}

/**
 * Contextual FAQ chip data — changes based on current screen.
 */
data class FAQChip(
    val question: String,
    val shortLabel: String = question.take(30) + if (question.length > 30) "…" else ""
)

/**
 * Maps navigation routes to contextual FAQ suggestions.
 */
object ContextualFAQs {
    private val homeChips = listOf(
        FAQChip("What can Autonion do?", "What can I do?"),
        FAQChip("How do I connect my desktop?", "Connect desktop"),
        FAQChip("How to set up Ollama?", "Set up Ollama"),
        FAQChip("What is Semantic Automation?", "Semantic Automation"),
        FAQChip("How to sync clipboard?", "Clipboard sync")
    )

    private val semanticChips = listOf(
        FAQChip("How does the AI agent work?", "How AI works"),
        FAQChip("Why is automation doing random things?", "Random actions fix"),
        FAQChip("How to change the AI model?", "Change model"),
        FAQChip("What prompts work best?", "Best prompts"),
        FAQChip("How to stop the agent?", "Stop agent")
    )

    private val crossDeviceChips = listOf(
        FAQChip("How to connect devices?", "Connect devices"),
        FAQChip("How to create automation rules?", "Create rules"),
        FAQChip("How to send commands to desktop?", "Send commands"),
        FAQChip("What is clipboard sync?", "Clipboard sync"),
        FAQChip("How to manage devices?", "Manage devices")
    )

    private val debuggerChips = listOf(
        FAQChip("How to read automation logs?", "Read logs"),
        FAQChip("What do error codes mean?", "Error codes"),
        FAQChip("How to debug failed automations?", "Debug failures")
    )

    private val flowBuilderChips = listOf(
        FAQChip("How to create a flow?", "Create flow"),
        FAQChip("What triggers are available?", "Available triggers"),
        FAQChip("How to chain actions?", "Chain actions")
    )

    private val defaultChips = homeChips

    /**
     * Get FAQ chips appropriate for the current screen/route.
     */
    fun getChipsForRoute(route: String?): List<FAQChip> {
        return when {
            route == null -> defaultChips
            route == "home" -> homeChips
            route.contains("semantic") -> semanticChips
            route.contains("cross_device") -> crossDeviceChips
            route.contains("debugger") -> debuggerChips
            route.contains("flow_builder") -> flowBuilderChips
            else -> defaultChips
        }
    }
}
