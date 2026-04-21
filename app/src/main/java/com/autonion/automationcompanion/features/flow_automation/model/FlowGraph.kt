package com.autonion.automationcompanion.features.flow_automation.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Complete flow graph — the top-level serializable container.
 *
 * A flow is a directed graph of [FlowNode]s connected by [FlowEdge]s.
 * It is serialized to JSON for persistence and supports schema versioning
 * for future migration.
 */
@Serializable
data class FlowGraph(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: Int = 1,
    val nodes: List<FlowNode> = emptyList(),
    val edges: List<FlowEdge> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Find the single Start node, or null if missing. */
    fun findStartNode(): StartNode? = nodes.filterIsInstance<StartNode>().firstOrNull()

    /** Get a node by its ID. */
    fun nodeById(id: String): FlowNode? = nodes.firstOrNull { it.id == id }

    /** Get an edge by its ID. */
    fun edgeById(id: String): FlowEdge? = edges.firstOrNull { it.id == id }

    /** Get all edges originating from a given node. */
    fun outgoingEdges(nodeId: String): List<FlowEdge> =
        edges.filter { it.fromNodeId == nodeId }

    /** Get the failure edge for a node, if any. */
    fun failureEdge(nodeId: String): FlowEdge? =
        edges.firstOrNull { it.fromNodeId == nodeId && it.isFailurePath }

    /** Create an updated copy with a new/replaced node. */
    fun withNode(node: FlowNode): FlowGraph {
        val updated = nodes.toMutableList()
        val idx = updated.indexOfFirst { it.id == node.id }
        if (idx >= 0) updated[idx] = node else updated.add(node)
        return copy(nodes = updated, updatedAt = System.currentTimeMillis())
    }

    /** Create an updated copy with a new/replaced edge. */
    fun withEdge(edge: FlowEdge): FlowGraph {
        val updated = edges.toMutableList()
        val idx = updated.indexOfFirst { it.id == edge.id }
        if (idx >= 0) updated[idx] = edge else updated.add(edge)
        return copy(edges = updated, updatedAt = System.currentTimeMillis())
    }

    /** Remove a node and all its connected edges. Clean up dangling references. */
    fun withoutNode(nodeId: String): FlowGraph {
        // Collect IDs of edges that touch the deleted node
        val deletedEdgeIds = edges
            .filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
            .map { it.id }
            .toSet()

        // Bug #12 fix: Clear onFailureEdgeId on remaining nodes if it points to a deleted edge
        val cleanedNodes = nodes
            .filter { it.id != nodeId }
            .map { node ->
                if (node.onFailureEdgeId != null && node.onFailureEdgeId in deletedEdgeIds) {
                    clearOnFailureEdgeId(node)
                } else {
                    node
                }
            }

        return copy(
            nodes = cleanedNodes,
            edges = edges.filter { it.fromNodeId != nodeId && it.toNodeId != nodeId },
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Remove an edge by ID. */
    fun withoutEdge(edgeId: String): FlowGraph {
        return copy(
            edges = edges.filter { it.id != edgeId },
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Create a copy of a node with onFailureEdgeId cleared. */
    private fun clearOnFailureEdgeId(node: FlowNode): FlowNode = when (node) {
        is StartNode -> node.copy(onFailureEdgeId = null)
        is GestureNode -> node.copy(onFailureEdgeId = null)
        is VisualTriggerNode -> node.copy(onFailureEdgeId = null)
        is ScreenMLNode -> node.copy(onFailureEdgeId = null)
        is DelayNode -> node.copy(onFailureEdgeId = null)
        is LaunchAppNode -> node.copy(onFailureEdgeId = null)
        is RepeatNode -> node.copy(onFailureEdgeId = null)
    }
}
