package com.autonion.automationcompanion.features.flow_automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Conditions attached to edges to control flow traversal.
 *
 * The core "Conditional Edges" philosophy: logic lives on edges, not as
 * standalone nodes, to minimize visual noise on mobile screens.
 */
@Serializable
sealed class EdgeCondition {

    /** Always follow this edge (no real condition). */
    @Serializable
    @SerialName("always")
    object Always : EdgeCondition()

    /** Wait a fixed duration before following this edge. */
    @Serializable
    @SerialName("wait_seconds")
    data class WaitSeconds(val seconds: Float) : EdgeCondition()

    /** Follow if FlowContext[contextKey] contains the given substring. */
    @Serializable
    @SerialName("if_text_contains")
    data class IfTextContains(
        val contextKey: String,
        val substring: String
    ) : EdgeCondition()

    /** Follow if FlowContext[contextKey] equals the given value. */
    @Serializable
    @SerialName("if_context_equals")
    data class IfContextEquals(
        val key: String,
        val value: String
    ) : EdgeCondition()

    /** Follow if an image match was found (FlowContext[contextKey] is truthy). */
    @Serializable
    @SerialName("if_image_found")
    data class IfImageFound(val contextKey: String) : EdgeCondition()

    /** Follow if FlowContext[contextKey] does NOT contain the given substring. */
    @Serializable
    @SerialName("if_not_text_contains")
    data class IfNotTextContains(
        val contextKey: String,
        val substring: String
    ) : EdgeCondition()

    /** Follow if FlowContext[key] does NOT equal the given value. */
    @Serializable
    @SerialName("if_not_context_equals")
    data class IfNotContextEquals(
        val key: String,
        val value: String
    ) : EdgeCondition()

    /** Follow if no image match was found. */
    @Serializable
    @SerialName("if_not_image_found")
    data class IfNotImageFound(val contextKey: String) : EdgeCondition()

    /** Retry the source node up to [maxAttempts] times with a delay between each. */
    @Serializable
    @SerialName("retry")
    data class Retry(
        val maxAttempts: Int = 3,
        val delayMs: Long = 2000L
    ) : EdgeCondition()

    /** Follow this edge only if no other normal conditions match. */
    @Serializable
    @SerialName("else")
    object Else : EdgeCondition()

    /** Stop execution of the flow successfully. */
    @Serializable
    @SerialName("stop_execution")
    object StopExecution : EdgeCondition()
}
