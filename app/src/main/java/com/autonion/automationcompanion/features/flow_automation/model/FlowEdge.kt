package com.autonion.automationcompanion.features.flow_automation.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A directed edge in the flow graph.
 *
 * Edges can carry [condition]s — this is the core "Conditional Edges" design:
 * logic (if/else, waits, retries) lives on edges rather than as standalone nodes,
 * reducing visual noise on mobile screens.
 */
@Serializable
data class FlowEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val condition: EdgeCondition? = null,
    val label: String? = null,
    val isFailurePath: Boolean = false
)
