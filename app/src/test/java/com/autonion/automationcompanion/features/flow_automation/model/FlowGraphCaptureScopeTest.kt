package com.autonion.automationcompanion.features.flow_automation.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowGraphCaptureScopeTest {
    @Test
    fun reachableLaunchAndVisualTriggerRequireFullDisplay() {
        val graph = connectedGraph(
            StartNode(id = "start"),
            LaunchAppNode(id = "launch"),
            VisualTriggerNode(id = "visual")
        )

        assertTrue(graph.requiresFullDisplayCapture())
    }

    @Test
    fun disconnectedCaptureNodeDoesNotRequireFullDisplay() {
        val graph = FlowGraph(
            name = "Disconnected visual",
            nodes = listOf(
                StartNode(id = "start"),
                LaunchAppNode(id = "launch"),
                VisualTriggerNode(id = "visual")
            ),
            edges = listOf(FlowEdge(fromNodeId = "start", toNodeId = "launch"))
        )

        assertFalse(graph.requiresFullDisplayCapture())
    }

    @Test
    fun accessibilityOnlyScreenMlDoesNotRequireCapture() {
        val graph = connectedGraph(
            StartNode(id = "start"),
            LaunchAppNode(id = "launch"),
            ScreenMLNode(id = "screen-ml", mode = ScreenMLMode.UI_ATTRIBUTE)
        )

        assertFalse(graph.requiresFullDisplayCapture())
    }

    @Test
    fun reachableLaunchAndOcrRequireFullDisplay() {
        val graph = connectedGraph(
            StartNode(id = "start"),
            LaunchAppNode(id = "launch"),
            ScreenMLNode(id = "screen-ml", mode = ScreenMLMode.OCR)
        )

        assertTrue(graph.requiresFullDisplayCapture())
    }

    @Test
    fun configuredStartAppStillAllowsSingleAppCapture() {
        val graph = connectedGraph(
            StartNode(id = "start", appPackageName = "com.example.target"),
            VisualTriggerNode(id = "visual")
        )

        assertFalse(graph.requiresFullDisplayCapture())
    }

    private fun connectedGraph(vararg nodes: FlowNode): FlowGraph {
        val edges = mutableListOf<FlowEdge>()
        for (index in 0 until nodes.lastIndex) {
            edges += FlowEdge(fromNodeId = nodes[index].id, toNodeId = nodes[index + 1].id)
        }
        return FlowGraph(name = "Capture scope", nodes = nodes.toList(), edges = edges)
    }
}
