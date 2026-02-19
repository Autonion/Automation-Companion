package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.*
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Infinite canvas for the flow editor.
 * Supports pinch-to-zoom, pan, node dragging, tap detection, and edge drawing.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun FlowCanvas(
    state: FlowEditorState,
    onCanvasTransform: (Offset, Float) -> Unit,
    onNodeTap: (String) -> Unit,
    onNodeDrag: (String, NodePosition) -> Unit,
    onEdgeTap: (String) -> Unit,
    onCanvasTap: () -> Unit,
    onOutputPortTap: (String) -> Unit,
    onNodeDropForConnection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(state.canvasZoom) }
    var offset by remember { mutableStateOf(state.canvasOffset) }
    var draggedNodeId by remember { mutableStateOf<String?>(null) }

    val textMeasurer = rememberTextMeasurer()

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newZoom = (zoom * zoomChange).coerceIn(0.3f, 3f)
        val newOffset = offset + panChange
        zoom = newZoom
        offset = newOffset
        onCanvasTransform(newOffset, newZoom)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .transformable(transformState)
            .pointerInput(state.graph, zoom, offset) {
                detectTapGestures { tapOffset ->
                    val canvasPos = screenToCanvas(tapOffset, offset, zoom)

                    // Check if we tapped a node output port
                    val portNodeId = findNodeOutputPort(state.graph.nodes, canvasPos)
                    if (portNodeId != null) {
                        if (state.isConnecting) {
                            onNodeDropForConnection(portNodeId)
                        } else {
                            onOutputPortTap(portNodeId)
                        }
                        return@detectTapGestures
                    }

                    // Check if we tapped a node body
                    val nodeId = findNodeAt(state.graph.nodes, canvasPos)
                    if (nodeId != null) {
                        if (state.isConnecting) {
                            onNodeDropForConnection(nodeId)
                        } else {
                            onNodeTap(nodeId)
                        }
                        return@detectTapGestures
                    }

                    // Check if we tapped an edge
                    val edgeId = findEdgeAt(state.graph, canvasPos)
                    if (edgeId != null) {
                        onEdgeTap(edgeId)
                        return@detectTapGestures
                    }

                    // Tapped empty canvas
                    onCanvasTap()
                }
            }
            .pointerInput(state.graph, zoom, offset) {
                detectDragGestures(
                    onDragStart = { dragOffset ->
                        val canvasPos = screenToCanvas(dragOffset, offset, zoom)
                        draggedNodeId = findNodeAt(state.graph.nodes, canvasPos)
                    },
                    onDrag = { change, dragAmount ->
                        val nodeId = draggedNodeId ?: return@detectDragGestures
                        change.consume()
                        val node = state.graph.nodeById(nodeId) ?: return@detectDragGestures
                        val scaledDrag = dragAmount / zoom
                        val snapped = snapToGrid(
                            node.position.x + scaledDrag.x,
                            node.position.y + scaledDrag.y
                        )
                        onNodeDrag(nodeId, snapped)
                    },
                    onDragEnd = { draggedNodeId = null },
                    onDragCancel = { draggedNodeId = null }
                )
            }
    ) {
        // Apply canvas transform
        withTransform({
            translate(offset.x, offset.y)
            scale(zoom, zoom, Offset.Zero)
        }) {
            // Draw grid
            drawGrid()

            // Draw edges
            state.graph.edges.forEach { edge ->
                val fromNode = state.graph.nodeById(edge.fromNodeId) ?: return@forEach
                val toNode = state.graph.nodeById(edge.toNodeId) ?: return@forEach
                drawEdge(
                    edge = edge,
                    fromNode = fromNode,
                    toNode = toNode,
                    isSelected = edge.id == state.selectedEdgeId,
                    textMeasurer = textMeasurer
                )
            }

            // Draw nodes
            state.graph.nodes.forEach { node ->
                drawNode(
                    node = node,
                    isSelected = node.id == state.selectedNodeId,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

// ─── Drawing Functions ───────────────────────────────────────────────────────

private fun DrawScope.drawGrid() {
    val gridSize = NodeDimensions.GRID_SIZE
    val gridColor = Color(0xFF1E2128)
    val visibleRect = Rect(
        -2000f, -2000f,
        size.width / 1f + 2000f,
        size.height / 1f + 2000f
    )

    var x = (visibleRect.left / gridSize).toInt() * gridSize
    while (x < visibleRect.right) {
        drawLine(gridColor, Offset(x, visibleRect.top), Offset(x, visibleRect.bottom), strokeWidth = 0.5f)
        x += gridSize
    }
    var y = (visibleRect.top / gridSize).toInt() * gridSize
    while (y < visibleRect.bottom) {
        drawLine(gridColor, Offset(visibleRect.left, y), Offset(visibleRect.right, y), strokeWidth = 0.5f)
        y += gridSize
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawNode(
    node: FlowNode,
    isSelected: Boolean,
    textMeasurer: TextMeasurer
) {
    val x = node.position.x
    val y = node.position.y
    val w = NodeDimensions.WIDTH
    val h = NodeDimensions.HEIGHT
    val r = NodeDimensions.CORNER_RADIUS

    val (bgColor, accentColor) = nodeColors(node)

    // Node body (rounded rect with gradient-like effect)
    val nodeRect = Rect(x, y, x + w, y + h)

    // Shadow
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.3f),
        topLeft = Offset(x + 2f, y + 3f),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r)
    )

    // Background
    drawRoundRect(
        color = bgColor,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r)
    )

    // Accent top bar
    val barPath = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = x, top = y, right = x + w, bottom = y + 6f,
                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f),
                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f)
            )
        )
    }
    drawPath(barPath, accentColor)

    // Selection outline
    if (isSelected) {
        drawRoundRect(
            color = NodeColors.NodeSelected,
            topLeft = Offset(x - 2f, y - 2f),
            size = Size(w + 4f, h + 4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + 2f),
            style = Stroke(width = 2.5f)
        )
    }

    // Node label
    val typeLabel = when (node) {
        is StartNode -> "▶ START"
        is GestureNode -> "👆 ${node.gestureType.name}"
        is VisualTriggerNode -> "🔍 IMAGE"
        is ScreenMLNode -> "🧠 ${node.mode.name}"
        is DelayNode -> "⏱ DELAY"
    }

    val labelResult = textMeasurer.measure(
        AnnotatedString(node.label),
        style = TextStyle(
            color = Color.White,
            fontSize = 13.sp
        ),
        maxLines = 1
    )
    drawText(
        labelResult,
        topLeft = Offset(x + 12f, y + 14f)
    )

    val typeResult = textMeasurer.measure(
        AnnotatedString(typeLabel),
        style = TextStyle(
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp
        ),
        maxLines = 1
    )
    drawText(
        typeResult,
        topLeft = Offset(x + 12f, y + h - 24f)
    )

    // Output port (right edge center)
    drawCircle(
        color = NodeColors.PortOutput,
        radius = NodeDimensions.PORT_RADIUS,
        center = Offset(x + w, y + h / 2f)
    )

    // Failure port (bottom center) — only for non-delay nodes
    if (node !is DelayNode) {
        drawCircle(
            color = NodeColors.PortFailure,
            radius = NodeDimensions.PORT_RADIUS * 0.7f,
            center = Offset(x + w / 2f, y + h)
        )
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEdge(
    edge: FlowEdge,
    fromNode: FlowNode,
    toNode: FlowNode,
    isSelected: Boolean,
    textMeasurer: TextMeasurer
) {
    val w = NodeDimensions.WIDTH
    val h = NodeDimensions.HEIGHT

    val start: Offset
    val end: Offset

    if (edge.isFailurePath) {
        start = Offset(fromNode.position.x + w / 2f, fromNode.position.y + h)
        end = Offset(toNode.position.x + w / 2f, toNode.position.y)
    } else {
        start = Offset(fromNode.position.x + w, fromNode.position.y + h / 2f)
        end = Offset(toNode.position.x, toNode.position.y + h / 2f)
    }

    val edgeColor = when {
        isSelected -> NodeColors.NodeSelected
        edge.isFailurePath -> NodeColors.EdgeFailure
        else -> NodeColors.EdgeDefault
    }

    // Cubic bezier curve
    val midX = (start.x + end.x) / 2f
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(midX, start.y, midX, end.y, end.x, end.y)
    }

    drawPath(
        path,
        color = edgeColor,
        style = Stroke(
            width = if (isSelected) 3f else 2f,
            cap = StrokeCap.Round
        )
    )

    // Arrowhead at target
    drawArrowhead(end, start, edgeColor)

    // Condition label at midpoint
    val label = edgeConditionLabel(edge)
    if (label != null) {
        val labelResult = textMeasurer.measure(
            AnnotatedString(label),
            style = TextStyle(
                color = edgeColor.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
        )
        val labelOffset = Offset(
            (start.x + end.x) / 2f - labelResult.size.width / 2f,
            (start.y + end.y) / 2f - labelResult.size.height - 4f
        )
        // Background pill
        drawRoundRect(
            color = Color(0xFF1A1C1E),
            topLeft = Offset(labelOffset.x - 6f, labelOffset.y - 3f),
            size = Size(labelResult.size.width + 12f, labelResult.size.height + 6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f)
        )
        drawText(labelResult, topLeft = labelOffset)
    }
}

private fun DrawScope.drawArrowhead(tip: Offset, from: Offset, color: Color) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val arrowLen = 10f
    val arrowAngle = Math.toRadians(25.0).toFloat()

    val p1 = Offset(
        tip.x - arrowLen * cos(angle - arrowAngle),
        tip.y - arrowLen * sin(angle - arrowAngle)
    )
    val p2 = Offset(
        tip.x - arrowLen * cos(angle + arrowAngle),
        tip.y - arrowLen * sin(angle + arrowAngle)
    )

    val arrowPath = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(arrowPath, color)
}

// ─── Hit Testing ─────────────────────────────────────────────────────────────

private fun findNodeAt(nodes: List<FlowNode>, canvasPos: Offset): String? {
    return nodes.lastOrNull { node ->
        val rect = Rect(
            node.position.x, node.position.y,
            node.position.x + NodeDimensions.WIDTH,
            node.position.y + NodeDimensions.HEIGHT
        )
        rect.contains(canvasPos)
    }?.id
}

private fun findNodeOutputPort(nodes: List<FlowNode>, canvasPos: Offset): String? {
    val portRadius = NodeDimensions.PORT_RADIUS * 3f // Generous hit area
    return nodes.lastOrNull { node ->
        val portCenter = Offset(
            node.position.x + NodeDimensions.WIDTH,
            node.position.y + NodeDimensions.HEIGHT / 2f
        )
        (canvasPos - portCenter).getDistance() < portRadius
    }?.id
}

private fun findEdgeAt(graph: FlowGraph, canvasPos: Offset): String? {
    val hitThreshold = 15f
    return graph.edges.lastOrNull { edge ->
        val fromNode = graph.nodeById(edge.fromNodeId) ?: return@lastOrNull false
        val toNode = graph.nodeById(edge.toNodeId) ?: return@lastOrNull false

        val start = Offset(
            fromNode.position.x + NodeDimensions.WIDTH,
            fromNode.position.y + NodeDimensions.HEIGHT / 2f
        )
        val end = Offset(
            toNode.position.x,
            toNode.position.y + NodeDimensions.HEIGHT / 2f
        )
        val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
        (canvasPos - mid).getDistance() < hitThreshold * 2f
    }?.id
}

// ─── Utilities ───────────────────────────────────────────────────────────────

private fun screenToCanvas(screenPos: Offset, canvasOffset: Offset, zoom: Float): Offset {
    return (screenPos - canvasOffset) / zoom
}

private fun snapToGrid(x: Float, y: Float): NodePosition {
    val g = NodeDimensions.GRID_SIZE
    return NodePosition(
        x = (x / g).toInt() * g,
        y = (y / g).toInt() * g
    )
}

private fun nodeColors(node: FlowNode): Pair<Color, Color> {
    return when (node) {
        is StartNode -> NodeColors.StartGreenBg to NodeColors.StartGreen
        is GestureNode -> NodeColors.GestureBlueBg to NodeColors.GestureBlue
        is VisualTriggerNode -> NodeColors.VisualTriggerPurpleBg to NodeColors.VisualTriggerPurple
        is ScreenMLNode -> NodeColors.ScreenMLAmberBg to NodeColors.ScreenMLAmber
        is DelayNode -> NodeColors.DelayGreyBg to NodeColors.DelayGrey
    }
}

private fun edgeConditionLabel(edge: FlowEdge): String? {
    if (edge.isFailurePath) return "✗ on failure"
    return when (val c = edge.condition) {
        null -> null
        is EdgeCondition.Always -> null
        is EdgeCondition.WaitSeconds -> "⏱ ${c.seconds}s"
        is EdgeCondition.IfTextContains -> "? \"${c.substring}\""
        is EdgeCondition.IfContextEquals -> "= ${c.key}"
        is EdgeCondition.IfImageFound -> "🔍 found?"
        is EdgeCondition.Retry -> "↻ ×${c.maxAttempts}"
    }
}
