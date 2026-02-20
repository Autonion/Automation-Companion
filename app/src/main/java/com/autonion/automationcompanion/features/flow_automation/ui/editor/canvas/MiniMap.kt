package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.flow_automation.model.*
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorState

private const val MINIMAP_PADDING = 20f

/**
 * A small corner overlay showing all nodes at miniature scale with a viewport
 * rectangle indicating the current visible area. Tap to navigate.
 */
@Composable
fun MiniMap(
    state: FlowEditorState,
    canvasSize: Size,
    onNavigate: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes = state.graph.nodes
    if (nodes.isEmpty()) return

    // Compute the bounding box of all nodes
    val minX = nodes.minOf { it.position.x } - MINIMAP_PADDING
    val minY = nodes.minOf { it.position.y } - MINIMAP_PADDING
    val maxX = nodes.maxOf { it.position.x + NodeDimensions.WIDTH } + MINIMAP_PADDING
    val maxY = nodes.maxOf { it.position.y + NodeDimensions.HEIGHT } + MINIMAP_PADDING

    val graphW = (maxX - minX).coerceAtLeast(1f)
    val graphH = (maxY - minY).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .size(width = 140.dp, height = 100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0D0F12.toInt()))
            .border(1.dp, Color(0xFF2A2D32), RoundedCornerShape(12.dp))
            .clickable {
                // Tap to center viewport on graph center
                val centerX = (minX + maxX) / 2f
                val centerY = (minY + maxY) / 2f
                onNavigate(Offset(centerX, centerY))
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val mapW = size.width
            val mapH = size.height

            // Scale factor to fit the full graph into the minimap
            val scaleX = mapW / graphW
            val scaleY = mapH / graphH
            val scale = minOf(scaleX, scaleY)

            // Center offset
            val offsetX = (mapW - graphW * scale) / 2f
            val offsetY = (mapH - graphH * scale) / 2f

            fun toMinimap(pos: NodePosition): Offset {
                return Offset(
                    offsetX + (pos.x - minX) * scale,
                    offsetY + (pos.y - minY) * scale
                )
            }

            // Draw edges as thin lines
            state.graph.edges.forEach { edge ->
                val fromNode = state.graph.nodeById(edge.fromNodeId) ?: return@forEach
                val toNode = state.graph.nodeById(edge.toNodeId) ?: return@forEach
                val from = toMinimap(fromNode.position)
                val to = toMinimap(toNode.position)
                drawLine(
                    color = if (edge.isFailurePath) Color(0x66FF5252) else Color(0x6690CAF9),
                    start = Offset(from.x + NodeDimensions.WIDTH * scale / 2f, from.y + NodeDimensions.HEIGHT * scale / 2f),
                    end = Offset(to.x + NodeDimensions.WIDTH * scale / 2f, to.y + NodeDimensions.HEIGHT * scale / 2f),
                    strokeWidth = 1f
                )
            }

            // Draw nodes as colored dots
            nodes.forEach { node ->
                val pos = toMinimap(node.position)
                val dotColor = when (node) {
                    is StartNode -> NodeColors.StartGreen
                    is GestureNode -> NodeColors.GestureBlue
                    is VisualTriggerNode -> NodeColors.VisualTriggerPurple
                    is ScreenMLNode -> NodeColors.ScreenMLAmber
                    is DelayNode -> NodeColors.DelayGrey
                }

                val dotSize = Size(
                    (NodeDimensions.WIDTH * scale).coerceAtLeast(4f),
                    (NodeDimensions.HEIGHT * scale).coerceAtLeast(3f)
                )

                drawRect(
                    color = dotColor,
                    topLeft = pos,
                    size = dotSize
                )

                // Highlight selected node
                if (node.id == state.selectedNodeId) {
                    drawRect(
                        color = NodeColors.NodeSelected,
                        topLeft = Offset(pos.x - 1f, pos.y - 1f),
                        size = Size(dotSize.width + 2f, dotSize.height + 2f),
                        style = Stroke(width = 1.5f)
                    )
                }
            }

            // Draw viewport rectangle
            if (canvasSize.width > 0 && canvasSize.height > 0) {
                val zoom = state.canvasZoom
                val vpLeft = (-state.canvasOffset.x / zoom - minX) * scale + offsetX
                val vpTop = (-state.canvasOffset.y / zoom - minY) * scale + offsetY
                val vpWidth = (canvasSize.width / zoom) * scale
                val vpHeight = (canvasSize.height / zoom) * scale

                drawRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(vpLeft, vpTop),
                    size = Size(vpWidth, vpHeight),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}
