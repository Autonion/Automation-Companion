package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionState
import com.autonion.automationcompanion.features.flow_automation.model.*
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Infinite canvas for the flow editor.
 *
 * All touch gestures are handled in a single unified pointer input:
 *  • Tap on output port → start / complete connection
 *  • Tap on node → select (or complete connection)
 *  • Tap on edge → select
 *  • Tap on empty → deselect
 *  • Drag on node → move node
 *  • Drag on empty → pan canvas
 *  • Pinch (2+ fingers) → zoom canvas
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun FlowCanvas(
    state: FlowEditorState,
    executionState: FlowExecutionState = FlowExecutionState.Idle,
    onCanvasTransform: (Offset, Float) -> Unit,
    onNodeTap: (String) -> Unit,
    onNodeDrag: (String, NodePosition) -> Unit,
    onEdgeTap: (String) -> Unit,
    onCanvasTap: () -> Unit,
    onOutputPortTap: (String) -> Unit,
    onNodeDropForConnection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Canvas transform state — kept as mutable state so gestures read latest values
    var zoom by remember { mutableFloatStateOf(state.canvasZoom) }
    var offset by remember { mutableStateOf(state.canvasOffset) }

    // Keep a ref to the LATEST graph & connection state so the gesture handler
    // (keyed on Unit to avoid mid-drag restarts) always reads fresh data.
    val latestGraph by rememberUpdatedState(state.graph)
    val latestIsConnecting by rememberUpdatedState(state.isConnecting)

    val textMeasurer = rememberTextMeasurer()

    // Marching-ants animation
    val isRunning = executionState is FlowExecutionState.Running
    val activeNodeId = (executionState as? FlowExecutionState.Running)?.currentNodeId

    val inf = rememberInfiniteTransition(label = "marchingAnts")
    val dashPhase by inf.animateFloat(
        0f, 20f,
        infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart),
        label = "dashPhase"
    )
    val glowAlpha by inf.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            // Key on Unit — the handler reads latestGraph via rememberUpdatedState
            // so it never restarts mid-drag when the graph changes.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 1. First finger down
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downScreen = down.position
                    val downCanvas = screenToCanvas(downScreen, offset, zoom)

                    // Hit-test using latest graph
                    val graph = latestGraph
                    val hitPortId = findNodeOutputPort(graph.nodes, downCanvas)
                    val hitNodeId = hitPortId ?: findNodeAt(graph.nodes, downCanvas)

                    // Capture initial node position for drag
                    val initPos: NodePosition? =
                        if (hitNodeId != null && hitPortId == null)
                            graph.nodeById(hitNodeId)?.position
                        else null

                    var isDrag = false
                    val threshold = 10f
                    var screenAccum = Offset.Zero
                    var canvasAccum = Offset.Zero
                    var pinched = false

                    // 2. Move/up loop
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val allUp = ev.changes.all { it.changedToUp() }

                        if (ev.changes.size >= 2) {
                            // ── Pinch zoom ──
                            pinched = true
                            val dz = ev.calculateZoom()
                            val dp = ev.calculatePan()
                            val nz = (zoom * dz).coerceIn(0.3f, 3f)
                            val no = offset + dp
                            zoom = nz; offset = no
                            onCanvasTransform(no, nz)
                            ev.changes.forEach { it.consume() }
                        } else if (ev.changes.size == 1) {
                            val c = ev.changes.first()
                            val delta = c.positionChange()

                            if (!isDrag && delta != Offset.Zero) {
                                screenAccum += delta
                                if (screenAccum.getDistance() > threshold) {
                                    isDrag = true
                                    canvasAccum = screenAccum / zoom
                                }
                            }

                            if (isDrag && !pinched) {
                                if (initPos != null && hitNodeId != null) {
                                    // ── Node drag ──
                                    canvasAccum += delta / zoom
                                    onNodeDrag(
                                        hitNodeId,
                                        snapToGrid(
                                            initPos.x + canvasAccum.x,
                                            initPos.y + canvasAccum.y
                                        )
                                    )
                                    c.consume()
                                } else {
                                    // ── Canvas pan ──
                                    offset += delta
                                    onCanvasTransform(offset, zoom)
                                    c.consume()
                                }
                            }
                        }

                        if (allUp) break
                    }

                    // 3. Tap detection (no drag, no pinch)
                    if (!isDrag && !pinched) {
                        val connecting = latestIsConnecting
                        if (hitPortId != null) {
                            if (connecting) onNodeDropForConnection(hitPortId)
                            else onOutputPortTap(hitPortId)
                        } else if (hitNodeId != null) {
                            if (connecting) onNodeDropForConnection(hitNodeId)
                            else onNodeTap(hitNodeId)
                        } else {
                            val edgeId = findEdgeAt(latestGraph, downCanvas)
                            if (edgeId != null) onEdgeTap(edgeId) else onCanvasTap()
                        }
                    }
                }
            }
    ) {
        withTransform({
            translate(offset.x, offset.y)
            scale(zoom, zoom, Offset.Zero)
        }) {
            drawGrid()

            // Edges
            state.graph.edges.forEach { edge ->
                val fn = state.graph.nodeById(edge.fromNodeId) ?: return@forEach
                val tn = state.graph.nodeById(edge.toNodeId) ?: return@forEach
                drawEdge(
                    edge, fn, tn,
                    isSelected = edge.id == state.selectedEdgeId,
                    isActive = isRunning && (edge.fromNodeId == activeNodeId || edge.toNodeId == activeNodeId),
                    dashPhase, textMeasurer
                )
            }

            // Nodes
            state.graph.nodes.forEach { node ->
                drawNode(
                    node,
                    isSelected = node.id == state.selectedNodeId,
                    isActive = node.id == activeNodeId,
                    glowAlpha, textMeasurer
                )
            }
        }
    }
}

// ─── Drawing ─────────────────────────────────────────────────────────────────

private fun DrawScope.drawGrid() {
    val g = NodeDimensions.GRID_SIZE
    val c = Color(0xFF1E2128)
    val b = Rect(-2000f, -2000f, size.width + 2000f, size.height + 2000f)
    var x = (b.left / g).toInt() * g
    while (x < b.right) { drawLine(c, Offset(x, b.top), Offset(x, b.bottom), 0.5f); x += g }
    var y = (b.top / g).toInt() * g
    while (y < b.bottom) { drawLine(c, Offset(b.left, y), Offset(b.right, y), 0.5f); y += g }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawNode(
    node: FlowNode, isSelected: Boolean, isActive: Boolean,
    glowAlpha: Float, textMeasurer: TextMeasurer
) {
    val x = node.position.x; val y = node.position.y
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT
    val r = NodeDimensions.CORNER_RADIUS
    val cr = androidx.compose.ui.geometry.CornerRadius(r)
    val (bg, accent) = nodeColors(node)

    if (isActive) drawRoundRect(accent.copy(glowAlpha * .35f), Offset(x - 8f, y - 8f), Size(w + 16f, h + 16f), androidx.compose.ui.geometry.CornerRadius(r + 8f))
    drawRoundRect(Color.Black.copy(.3f), Offset(x + 2f, y + 3f), Size(w, h), cr)
    drawRoundRect(bg, Offset(x, y), Size(w, h), cr)

    // Accent bar
    drawPath(Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w, y + 6f,
            topLeftCornerRadius = cr, topRightCornerRadius = cr,
            bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f),
            bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f)))
    }, accent)

    if (isSelected) drawRoundRect(NodeColors.NodeSelected, Offset(x - 2f, y - 2f), Size(w + 4f, h + 4f), androidx.compose.ui.geometry.CornerRadius(r + 2f), style = Stroke(2.5f))
    if (isActive) drawRoundRect(accent.copy(glowAlpha), Offset(x - 3f, y - 3f), Size(w + 6f, h + 6f), androidx.compose.ui.geometry.CornerRadius(r + 3f), style = Stroke(2f))

    // Labels
    drawText(textMeasurer.measure(AnnotatedString(node.label), TextStyle(Color.White, fontSize = 13.sp), maxLines = 1), topLeft = Offset(x + 12f, y + 14f))
    val badge = when (node) {
        is StartNode -> "▶ START"; is GestureNode -> "👆 ${node.gestureType.name}"
        is VisualTriggerNode -> "🔍 IMAGE"; is ScreenMLNode -> "🧠 ${node.mode.name}"
        is DelayNode -> "⏱ DELAY"
    }
    drawText(textMeasurer.measure(AnnotatedString(badge), TextStyle(Color.White.copy(.6f), fontSize = 10.sp), maxLines = 1), topLeft = Offset(x + 12f, y + h - 24f))

    // Output port
    val pc = Offset(x + w, y + h / 2f)
    drawCircle(NodeColors.PortOutput, NodeDimensions.PORT_RADIUS, pc)
    drawCircle(Color.White, NodeDimensions.PORT_RADIUS * .4f, pc)

    // Failure port
    if (node !is DelayNode) drawCircle(NodeColors.PortFailure, NodeDimensions.PORT_RADIUS * .7f, Offset(x + w / 2f, y + h))
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEdge(
    edge: FlowEdge, fromNode: FlowNode, toNode: FlowNode,
    isSelected: Boolean, isActive: Boolean,
    dashPhase: Float, textMeasurer: TextMeasurer
) {
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT
    val (start, end) = if (edge.isFailurePath) {
        Offset(fromNode.position.x + w / 2f, fromNode.position.y + h) to
            Offset(toNode.position.x + w / 2f, toNode.position.y)
    } else {
        Offset(fromNode.position.x + w, fromNode.position.y + h / 2f) to
            Offset(toNode.position.x, toNode.position.y + h / 2f)
    }

    val col = when {
        isSelected -> NodeColors.NodeSelected
        isActive -> Color(0xFF64FFDA)
        edge.isFailurePath -> NodeColors.EdgeFailure
        else -> NodeColors.EdgeDefault
    }

    val mx = (start.x + end.x) / 2f
    val p = Path().apply { moveTo(start.x, start.y); cubicTo(mx, start.y, mx, end.y, end.x, end.y) }

    if (isActive) {
        drawPath(p, col.copy(.3f), style = Stroke(6f, cap = StrokeCap.Round))
        drawPath(p, col, style = Stroke(2.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), dashPhase)))
    } else {
        drawPath(p, col, style = Stroke(if (isSelected) 3f else 2f, cap = StrokeCap.Round))
    }

    drawArrowhead(end, start, col)

    edgeConditionLabel(edge)?.let { label ->
        val lr = textMeasurer.measure(AnnotatedString(label), TextStyle(col.copy(.8f), fontSize = 9.sp))
        val lo = Offset((start.x + end.x) / 2f - lr.size.width / 2f, (start.y + end.y) / 2f - lr.size.height - 4f)
        drawRoundRect(Color(0xFF1A1C1E), Offset(lo.x - 6f, lo.y - 3f), Size(lr.size.width + 12f, lr.size.height + 6f), androidx.compose.ui.geometry.CornerRadius(8f))
        drawText(lr, topLeft = lo)
    }
}

private fun DrawScope.drawArrowhead(tip: Offset, from: Offset, color: Color) {
    val a = atan2(tip.y - from.y, tip.x - from.x); val l = 10f; val h = Math.toRadians(25.0).toFloat()
    drawPath(Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - l * cos(a - h), tip.y - l * sin(a - h))
        lineTo(tip.x - l * cos(a + h), tip.y - l * sin(a + h)); close()
    }, color)
}

// ─── Hit Testing ─────────────────────────────────────────────────────────────

private fun findNodeAt(nodes: List<FlowNode>, pos: Offset) =
    nodes.lastOrNull { Rect(it.position.x, it.position.y, it.position.x + NodeDimensions.WIDTH, it.position.y + NodeDimensions.HEIGHT).contains(pos) }?.id

private fun findNodeOutputPort(nodes: List<FlowNode>, pos: Offset): String? {
    val r = NodeDimensions.PORT_RADIUS * 3.5f
    return nodes.lastOrNull { (pos - Offset(it.position.x + NodeDimensions.WIDTH, it.position.y + NodeDimensions.HEIGHT / 2f)).getDistance() < r }?.id
}

private fun findEdgeAt(graph: FlowGraph, pos: Offset) = graph.edges.lastOrNull { e ->
    val f = graph.nodeById(e.fromNodeId) ?: return@lastOrNull false
    val t = graph.nodeById(e.toNodeId) ?: return@lastOrNull false
    val s = Offset(f.position.x + NodeDimensions.WIDTH, f.position.y + NodeDimensions.HEIGHT / 2f)
    val en = Offset(t.position.x, t.position.y + NodeDimensions.HEIGHT / 2f)
    (pos - Offset((s.x + en.x) / 2f, (s.y + en.y) / 2f)).getDistance() < 40f
}?.id

// ─── Utilities ───────────────────────────────────────────────────────────────

private fun screenToCanvas(screen: Offset, canvasOffset: Offset, zoom: Float) = (screen - canvasOffset) / zoom

private fun snapToGrid(x: Float, y: Float): NodePosition {
    val g = NodeDimensions.GRID_SIZE
    return NodePosition((x / g).toInt() * g, (y / g).toInt() * g)
}

private fun nodeColors(node: FlowNode) = when (node) {
    is StartNode -> NodeColors.StartGreenBg to NodeColors.StartGreen
    is GestureNode -> NodeColors.GestureBlueBg to NodeColors.GestureBlue
    is VisualTriggerNode -> NodeColors.VisualTriggerPurpleBg to NodeColors.VisualTriggerPurple
    is ScreenMLNode -> NodeColors.ScreenMLAmberBg to NodeColors.ScreenMLAmber
    is DelayNode -> NodeColors.DelayGreyBg to NodeColors.DelayGrey
}

private fun edgeConditionLabel(edge: FlowEdge): String? {
    if (edge.isFailurePath) return "✗ on failure"
    return when (val c = edge.condition) {
        null -> null; is EdgeCondition.Always -> null
        is EdgeCondition.WaitSeconds -> "⏱ ${c.seconds}s"
        is EdgeCondition.IfTextContains -> "? \"${c.substring}\""
        is EdgeCondition.IfContextEquals -> "= ${c.key}"
        is EdgeCondition.IfImageFound -> "🔍 found?"
        is EdgeCondition.Retry -> "↻ ×${c.maxAttempts}"
    }
}
