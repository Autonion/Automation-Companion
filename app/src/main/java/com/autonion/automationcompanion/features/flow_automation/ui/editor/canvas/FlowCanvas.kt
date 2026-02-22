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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    editorColors: FlowEditorColors = FlowEditorColors.Dark,
    onCanvasTransform: (Offset, Float) -> Unit,
    onNodeTap: (String) -> Unit,
    onNodeDrag: (String, NodePosition) -> Unit,
    onEdgeTap: (String) -> Unit,
    onCanvasTap: () -> Unit,
    onOutputPortTap: (String) -> Unit,
    onFailurePortTap: (String) -> Unit,
    onNodeDropForConnection: (String) -> Unit,
    onDragEndpoint: (Offset?) -> Unit = {},
    onEdgeCut: (String) -> Unit = {},
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
            .background(editorColors.canvasBg)
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
                    val hitOutputPortId = findNodeOutputPort(graph.nodes, downCanvas)
                    val hitFailurePortId = findNodeFailurePort(graph.nodes, downCanvas)
                    val hitPortId = hitOutputPortId ?: hitFailurePortId
                    
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
                    
                    // Track if this is a port-drag (for drag-to-connect)
                    var isPortDrag = false
                    // Track actual last screen position for accurate drop detection
                    var lastScreenPos = downScreen

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
                            lastScreenPos = c.position

                            if (!isDrag && delta != Offset.Zero) {
                                screenAccum += delta
                                if (screenAccum.getDistance() > threshold) {
                                    isDrag = true
                                    canvasAccum = screenAccum / zoom
                                    
                                    // If drag started on a port, enter port-drag mode
                                    if (hitPortId != null) {
                                        isPortDrag = true
                                        if (hitOutputPortId != null) onOutputPortTap(hitOutputPortId)
                                        else if (hitFailurePortId != null) onFailurePortTap(hitFailurePortId)
                                    }
                                }
                            }

                            if (isDrag && !pinched) {
                                if (isPortDrag) {
                                    // ── Port drag → update rubber-band endpoint ──
                                    val currentCanvas = screenToCanvas(c.position, offset, zoom)
                                    onDragEndpoint(currentCanvas)
                                    c.consume()
                                } else if (initPos != null && hitNodeId != null) {
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

                    // 3. Handle release
                    if (isPortDrag) {
                        // Port drag released — use actual last finger position
                        val releaseCanvas = screenToCanvas(lastScreenPos, offset, zoom)
                        // Drop anywhere on a node to connect (full body + generous padding)
                        val dropNodeId = findNodeAt(latestGraph.nodes, releaseCanvas)
                            ?: findNodeNear(latestGraph.nodes, releaseCanvas)
                        if (dropNodeId != null) {
                            onNodeDropForConnection(dropNodeId)
                        } else {
                            // Dropped on empty space → cancel connection
                            onCanvasTap()
                        }
                        onDragEndpoint(null)
                    } else if (!isDrag && !pinched) {
                        // Tap detection (no drag, no pinch)
                        val connecting = latestIsConnecting
                        if (hitOutputPortId != null) {
                            if (connecting) onNodeDropForConnection(hitOutputPortId)
                            else onOutputPortTap(hitOutputPortId)
                        } else if (hitFailurePortId != null) {
                            if (connecting) onNodeDropForConnection(hitFailurePortId)
                            else onFailurePortTap(hitFailurePortId)
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
            drawGrid(editorColors)

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
                    glowAlpha, textMeasurer, editorColors
                )
            }

            // Rubber-band line during drag connection
            val dragEnd = state.dragConnectionEndpoint
            val fromNodeId = state.connectFromNodeId
            if (state.isConnecting && dragEnd != null && fromNodeId != null) {
                val fromNode = state.graph.nodeById(fromNodeId)
                if (fromNode != null) {
                    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT
                    val startPos = if (state.connectFromFailurePort) {
                        Offset(fromNode.position.x + w / 2f, fromNode.position.y + h)
                    } else {
                        Offset(fromNode.position.x + w, fromNode.position.y + h / 2f)
                    }
                    val lineColor = if (state.connectFromFailurePort) 
                        NodeColors.EdgeFailure.copy(0.9f) 
                    else 
                        NodeColors.EdgeActive.copy(0.9f)
                    
                    // Dashed rubber-band line
                    drawLine(
                        lineColor, startPos, dragEnd, 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), dashPhase)
                    )
                    // Small dot at endpoint
                    drawCircle(lineColor, 8f, dragEnd)
                    drawCircle(Color.White, 4f, dragEnd)
                }
            }
        }
    }
}

// ─── Drawing ─────────────────────────────────────────────────────────────────

private fun DrawScope.drawGrid(colors: FlowEditorColors) {
    val g = NodeDimensions.GRID_SIZE
    val c = colors.gridLine
    val b = Rect(-2000f, -2000f, size.width + 2000f, size.height + 2000f)
    var x = (b.left / g).toInt() * g
    while (x < b.right) { drawLine(c, Offset(x, b.top), Offset(x, b.bottom), 0.5f); x += g }
    var y = (b.top / g).toInt() * g
    while (y < b.bottom) { drawLine(c, Offset(b.left, y), Offset(b.right, y), 0.5f); y += g }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawNode(
    node: FlowNode, isSelected: Boolean, isActive: Boolean,
    glowAlpha: Float, textMeasurer: TextMeasurer, colors: FlowEditorColors
) {
    val x = node.position.x; val y = node.position.y
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT
    val r = NodeDimensions.CORNER_RADIUS
    val cr = androidx.compose.ui.geometry.CornerRadius(r)
    val (_, accent) = nodeColors(node)

    // ── Glow when running / active ──
    if (isActive) {
        val glowRadius = r + 16f
        drawRoundRect(
            accent.copy(alpha = glowAlpha * 0.4f),
            Offset(x - 16f, y - 16f), Size(w + 32f, h + 32f),
            androidx.compose.ui.geometry.CornerRadius(glowRadius),
        )
        drawRoundRect(
            accent.copy(alpha = glowAlpha * 0.6f),
            Offset(x - 8f, y - 8f), Size(w + 16f, h + 16f),
            androidx.compose.ui.geometry.CornerRadius(r + 4f),
        )
    }

    // ── Drop shadow ──
    val shadowOpacity = if (isSelected) 0.6f else 0.4f
    drawRoundRect(colors.nodeShadow.copy(alpha = shadowOpacity), Offset(x + 4f, y + 8f), Size(w, h), cr)
    drawRoundRect(colors.nodeShadow.copy(alpha = shadowOpacity * 0.5f), Offset(x, y + 2f), Size(w, h), cr)

    // ── Body gradient background ──
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            accent.copy(alpha = 0.25f), 
            colors.canvasBg, 
            colors.nodeBodyBg
        ),
        startY = y,
        endY = y + h
    )
    drawRoundRect(bgBrush, Offset(x, y), Size(w, h), cr)
    
    // Slight top inner highlight
    val highlightBrush = Brush.verticalGradient(
        colors = listOf(colors.nodeHighlight, Color.Transparent),
        startY = y, endY = y + h * 0.5f
    )
    drawRoundRect(highlightBrush, Offset(x, y), Size(w, h), cr)

    // ── Border (gradient) ──
    val borderAlpha = if (isSelected) 1f else 0.5f
    val borderWidth = if (isSelected) 2.5f else 1.5f
    val borderBrush = Brush.verticalGradient(
        colors = listOf(accent.copy(alpha = borderAlpha), accent.copy(alpha = borderAlpha * 0.2f)),
        startY = y,
        endY = y + h
    )
    drawRoundRect(borderBrush, Offset(x, y), Size(w, h), cr, style = Stroke(borderWidth))

    // ── Selection glow inside border ──
    if (isSelected) {
        drawRoundRect(
            accent.copy(alpha = 0.3f), Offset(x + 1f, y + 1f),
            Size(w - 2f, h - 2f), androidx.compose.ui.geometry.CornerRadius(r - 1f),
            style = Stroke(2f)
        )
    }

    // ── Icon circle (left side, vertically centered) — solid accent fill ──
    val iconCenterX = x + 72f
    val iconCenterY = y + h / 2f
    val iconRadius = 30f
    // Outer glow ring
    drawCircle(accent.copy(alpha = 0.15f), radius = iconRadius + 6f, center = Offset(iconCenterX, iconCenterY))
    // Solid accent circle
    drawCircle(accent, radius = iconRadius, center = Offset(iconCenterX, iconCenterY))
    // Subtle inner highlight
    drawCircle(Color.White.copy(alpha = 0.15f), radius = iconRadius - 2f, center = Offset(iconCenterX, iconCenterY))

    // Draw custom geometric icon (scaled up further)
    drawNodeIcon(node.nodeType, iconCenterX, iconCenterY, Color.White, accent, scale = 1.35f)

    // ── Title (bold) ──
    val textStartX = x + 120f
    val maxTextW = (w - 140f).toInt()
    val titleResult = textMeasurer.measure(
        AnnotatedString(node.label),
        TextStyle(colors.titleText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxTextW)
    )
    drawText(titleResult, topLeft = Offset(textStartX, iconCenterY - titleResult.size.height - 2f))

    // ── Subtitle (uppercase, accent colored in pill) ──
    val subtitle = when (node) {
        is StartNode -> "ENTRY POINT"
        is GestureNode -> node.gestureType.name.uppercase()
        is VisualTriggerNode -> "IMAGE MATCH"
        is ScreenMLNode -> node.mode.name.uppercase()
        is DelayNode -> "WAIT"
        is LaunchAppNode -> if (node.appPackageName.isNotBlank())
            node.appPackageName.substringAfterLast('.').uppercase()
        else "SELECT APP"
    }
    val subText = textMeasurer.measure(
        AnnotatedString(subtitle),
        TextStyle(accent.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxTextW)
    )
    val subY = iconCenterY + 4f
    drawRoundRect(
        color = accent.copy(alpha = colors.subtitleBgAlpha),
        topLeft = Offset(textStartX - 8f, subY - 4f),
        size = Size(Math.min(subText.size.width.toFloat(), maxTextW.toFloat()) + 16f, subText.size.height + 8f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f)
    )
    drawText(subText, topLeft = Offset(textStartX, subY))

    // ── Input port (left-center) ──
    val ip = Offset(x, y + h / 2f)
    drawCircle(colors.portBg, NodeDimensions.PORT_RADIUS + 5f, ip)
    drawCircle(accent.copy(alpha = 0.4f), NodeDimensions.PORT_RADIUS + 2f, ip)
    drawCircle(NodeColors.PortInput, NodeDimensions.PORT_RADIUS - 2f, ip)

    // ── Output port (right-center) ──
    val op = Offset(x + w, y + h / 2f)
    drawCircle(colors.portBg, NodeDimensions.PORT_RADIUS + 5f, op)
    drawCircle(Color(0xFF4ADE80).copy(alpha = 0.3f), NodeDimensions.PORT_RADIUS + 2f, op)
    drawCircle(NodeColors.PortOutput, NodeDimensions.PORT_RADIUS - 2f, op)

    // ── Failure port (bottom-center) ──
    if (node !is DelayNode && node !is LaunchAppNode) {
        val fp = Offset(x + w / 2f, y + h)
        drawCircle(colors.portBg, NodeDimensions.PORT_RADIUS + 5f, fp)
        drawCircle(NodeColors.PortFailure.copy(alpha = 0.3f), NodeDimensions.PORT_RADIUS + 2f, fp)
        drawCircle(NodeColors.PortFailure, NodeDimensions.PORT_RADIUS - 2f, fp)
        // Tiny ✗ inside
        val cross = (NodeDimensions.PORT_RADIUS - 2f) * 0.45f
        drawLine(Color.White.copy(alpha = 0.9f), Offset(fp.x - cross, fp.y - cross), Offset(fp.x + cross, fp.y + cross), 2.5f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.9f), Offset(fp.x + cross, fp.y - cross), Offset(fp.x - cross, fp.y + cross), 2.5f, cap = StrokeCap.Round)
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEdge(
    edge: FlowEdge, fromNode: FlowNode, toNode: FlowNode,
    isSelected: Boolean, isActive: Boolean,
    dashPhase: Float, textMeasurer: TextMeasurer
) {
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT

    val col = when {
        isSelected -> NodeColors.NodeSelected
        isActive -> NodeColors.EdgeActive
        edge.isFailurePath -> NodeColors.EdgeFailure
        else -> NodeColors.EdgeDefault
    }

    val isFailure = edge.isFailurePath

    // ── Self-loop ──
    if (edge.fromNodeId == edge.toNodeId) {
        val nx = fromNode.position.x; val ny = fromNode.position.y
        val loopRadius = 60f
        val p = Path()

        if (isFailure) {
            val startPt = Offset(nx + w / 2f, ny + h)
            val endPt = Offset(nx, ny + h / 2f)
            p.moveTo(startPt.x, startPt.y)
            p.cubicTo(
                startPt.x + loopRadius, startPt.y + loopRadius * 1.5f,
                endPt.x - loopRadius * 1.5f, endPt.y + loopRadius,
                endPt.x, endPt.y
            )
            drawStyledEdgePath(p, col, isSelected, isActive, isFailure, dashPhase)
            drawArrowhead(endPt, Offset(endPt.x - loopRadius, endPt.y + loopRadius), col)
            if (isSelected) edgeConditionLabel(edge)?.let {
                drawEdgeLabel(it, Offset(nx - loopRadius * 0.5f, ny + h + loopRadius * 0.5f), col, textMeasurer)
            }
        } else {
            val startPt = Offset(nx + w, ny + h / 2f)
            val endPt = Offset(nx + w / 2f, ny)
            p.moveTo(startPt.x, startPt.y)
            p.cubicTo(
                startPt.x + loopRadius * 1.5f, startPt.y - loopRadius,
                endPt.x + loopRadius, endPt.y - loopRadius * 1.5f,
                endPt.x, endPt.y
            )
            drawStyledEdgePath(p, col, isSelected, isActive, isFailure, dashPhase)
            drawArrowhead(endPt, Offset(endPt.x + loopRadius, endPt.y - loopRadius), col)
            if (isSelected) edgeConditionLabel(edge)?.let {
                drawEdgeLabel(it, Offset(nx + w + loopRadius * 0.3f, ny - loopRadius * 0.8f), col, textMeasurer)
            }
        }
        return
    }

    // ── Normal edge ──
    if (isFailure) {
        // Failure: bottom → target top, curve LEFT
        val start = Offset(fromNode.position.x + w / 2f, fromNode.position.y + h)
        val end = Offset(toNode.position.x + w / 2f, toNode.position.y)
        val dy = end.y - start.y
        val cpOff = -60f
        val cp1 = Offset(start.x + cpOff, start.y + dy * 0.4f)
        val cp2 = Offset(end.x + cpOff, end.y - dy * 0.4f)

        val p = Path().apply { moveTo(start.x, start.y); cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, end.x, end.y) }
        drawStyledEdgePath(p, col, isSelected, isActive, true, dashPhase)
        drawArrowhead(end, cp2, col)

        // Label only when selected
        if (isSelected) edgeConditionLabel(edge)?.let {
            drawEdgeLabel(it, Offset((start.x + end.x) / 2f + cpOff - 20f, (start.y + end.y) / 2f), col, textMeasurer)
        }
    } else {
        // Success: right → target left, curve RIGHT
        val start = Offset(fromNode.position.x + w, fromNode.position.y + h / 2f)
        val end = Offset(toNode.position.x, toNode.position.y + h / 2f)
        val dx = end.x - start.x
        val cpX = maxOf(80f, dx * 0.5f)
        val cp1 = Offset(start.x + cpX, start.y)
        val cp2 = Offset(end.x - cpX, end.y)

        val p = Path().apply { moveTo(start.x, start.y); cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, end.x, end.y) }
        drawStyledEdgePath(p, col, isSelected, isActive, false, dashPhase)
        drawArrowhead(end, cp2, col)

        // Label only when selected
        if (isSelected) edgeConditionLabel(edge)?.let { label ->
            val lr = textMeasurer.measure(AnnotatedString(label), TextStyle(col.copy(.8f), fontSize = 9.sp))
            val lo = Offset((start.x + end.x) / 2f - lr.size.width / 2f, (start.y + end.y) / 2f - lr.size.height - 4f)
            drawRoundRect(Color(0xFF1A1C1E), Offset(lo.x - 6f, lo.y - 3f), Size(lr.size.width + 12f, lr.size.height + 6f), androidx.compose.ui.geometry.CornerRadius(8f))
            drawText(lr, topLeft = lo)
        }
    }
}

/** Styled edge path: solid for success, dashed for failure. */
private fun DrawScope.drawStyledEdgePath(
    p: Path, col: Color, isSelected: Boolean, isActive: Boolean,
    isFailure: Boolean, dashPhase: Float
) {
    if (isActive) {
        drawPath(p, col.copy(.25f), style = Stroke(8f, cap = StrokeCap.Round))
        drawPath(p, col, style = Stroke(4f, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), dashPhase)))
    } else if (isFailure) {
        // Dashed line for failure paths
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        if (isSelected) {
            drawPath(p, col.copy(.2f), style = Stroke(8f, cap = StrokeCap.Round, pathEffect = dashEffect))
        }
        drawPath(p, col, style = Stroke(if (isSelected) 4f else 3f, cap = StrokeCap.Round, pathEffect = dashEffect))
    } else {
        // Solid line for success paths
        if (isSelected) {
            drawPath(p, col.copy(.2f), style = Stroke(8f, cap = StrokeCap.Round))
        }
        drawPath(p, col, style = Stroke(if (isSelected) 4f else 3f, cap = StrokeCap.Round))
    }
}

/** Draw a label badge near an edge. */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEdgeLabel(
    label: String, position: Offset, col: Color, textMeasurer: TextMeasurer
) {
    val lr = textMeasurer.measure(
        AnnotatedString(label), 
        TextStyle(col.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    )
    val lo = Offset(position.x - lr.size.width / 2f, position.y - lr.size.height / 2f)
    drawRoundRect(
        Color(0xFF101216).copy(alpha = 0.85f), 
        Offset(lo.x - 8f, lo.y - 4f), 
        Size(lr.size.width + 16f, lr.size.height + 8f), 
        androidx.compose.ui.geometry.CornerRadius(10f)
    )
    drawRoundRect(
        col.copy(alpha = 0.3f), 
        Offset(lo.x - 8f, lo.y - 4f), 
        Size(lr.size.width + 16f, lr.size.height + 8f), 
        androidx.compose.ui.geometry.CornerRadius(10f),
        style = Stroke(1.5f)
    )
    drawText(lr, topLeft = lo)
}

private fun DrawScope.drawArrowhead(tip: Offset, from: Offset, color: Color) {
    val a = atan2(tip.y - from.y, tip.x - from.x); val l = 14f; val ha = Math.toRadians(30.0).toFloat()
    drawPath(Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - l * cos(a - ha), tip.y - l * sin(a - ha))
        lineTo(tip.x - l * cos(a + ha), tip.y - l * sin(a + ha)); close()
    }, color)
}

// ─── Hit Testing ─────────────────────────────────────────────────────────────

private fun findNodeAt(nodes: List<FlowNode>, pos: Offset) =
    nodes.lastOrNull { Rect(it.position.x, it.position.y, it.position.x + NodeDimensions.WIDTH, it.position.y + NodeDimensions.HEIGHT).contains(pos) }?.id

/** Expanded hit area for drop targets — 20px padding around the node body. */
private fun findNodeNear(nodes: List<FlowNode>, pos: Offset): String? {
    val pad = 20f
    return nodes.lastOrNull {
        Rect(
            it.position.x - pad, it.position.y - pad,
            it.position.x + NodeDimensions.WIDTH + pad, it.position.y + NodeDimensions.HEIGHT + pad
        ).contains(pos)
    }?.id
}

private fun findNodeOutputPort(nodes: List<FlowNode>, pos: Offset): String? {
    val r = NodeDimensions.PORT_HIT_RADIUS
    return nodes.lastOrNull { (pos - Offset(it.position.x + NodeDimensions.WIDTH, it.position.y + NodeDimensions.HEIGHT / 2f)).getDistance() < r }?.id
}

private fun findNodeFailurePort(nodes: List<FlowNode>, pos: Offset): String? {
    val r = NodeDimensions.PORT_HIT_RADIUS
    return nodes.lastOrNull { 
        it !is DelayNode &&
        it !is LaunchAppNode &&
        (pos - Offset(it.position.x + NodeDimensions.WIDTH / 2f, it.position.y + NodeDimensions.HEIGHT)).getDistance() < r 
    }?.id
}

private fun findEdgeAt(graph: FlowGraph, pos: Offset): String? {
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT
    val hitRadius = 30f

    return graph.edges.lastOrNull { e ->
        val f = graph.nodeById(e.fromNodeId) ?: return@lastOrNull false
        val t = graph.nodeById(e.toNodeId) ?: return@lastOrNull false

        val start: Offset
        val end: Offset
        if (e.isFailurePath) {
            start = Offset(f.position.x + w / 2f, f.position.y + h)
            end = Offset(t.position.x + w / 2f, t.position.y)
        } else {
            start = Offset(f.position.x + w, f.position.y + h / 2f)
            end = Offset(t.position.x, t.position.y + h / 2f)
        }

        // Sample along the edge for hit testing
        (0..7).any { i ->
            val t0 = i.toFloat() / 7
            (pos - Offset(lerp(start.x, end.x, t0), lerp(start.y, end.y, t0))).getDistance() < hitRadius
        }
    }?.id
}

/** Hit-test the left-center input port of a node (where edges connect TO). */
private fun findNodeInputPort(nodes: List<FlowNode>, pos: Offset): String? {
    val r = NodeDimensions.PORT_HIT_RADIUS * 1.5f  // Slightly larger hit area for drop targets
    return nodes.lastOrNull {
        (pos - Offset(it.position.x, it.position.y + NodeDimensions.HEIGHT / 2f)).getDistance() < r
    }?.id
}

/**
 * Detect if a swipe segment (from segA to segB) crosses any edge in the graph.
 * Uses line-segment intersection to find crossings.
 */
private fun findEdgeCrossing(graph: FlowGraph, segA: Offset, segB: Offset): String? {
    val w = NodeDimensions.WIDTH; val h = NodeDimensions.HEIGHT

    return graph.edges.firstOrNull { e ->
        val f = graph.nodeById(e.fromNodeId) ?: return@firstOrNull false
        val t = graph.nodeById(e.toNodeId) ?: return@firstOrNull false

        val edgeStart: Offset
        val edgeEnd: Offset
        if (e.isFailurePath) {
            edgeStart = Offset(f.position.x + w / 2f, f.position.y + h)
            edgeEnd = Offset(t.position.x + w / 2f, t.position.y)
        } else {
            edgeStart = Offset(f.position.x + w, f.position.y + h / 2f)
            edgeEnd = Offset(t.position.x, t.position.y + h / 2f)
        }

        // Test multiple segments along the edge (since edges could be curved in the future)
        (0 until 8).any { i ->
            val t0 = i.toFloat() / 8; val t1 = (i + 1).toFloat() / 8
            val eA = Offset(lerp(edgeStart.x, edgeEnd.x, t0), lerp(edgeStart.y, edgeEnd.y, t0))
            val eB = Offset(lerp(edgeStart.x, edgeEnd.x, t1), lerp(edgeStart.y, edgeEnd.y, t1))
            segmentsIntersect(segA, segB, eA, eB)
        }
    }?.id
}

/** Check if two 2D line segments intersect. */
private fun segmentsIntersect(a1: Offset, a2: Offset, b1: Offset, b2: Offset): Boolean {
    val d1 = cross(b2 - b1, a1 - b1)
    val d2 = cross(b2 - b1, a2 - b1)
    val d3 = cross(a2 - a1, b1 - a1)
    val d4 = cross(a2 - a1, b2 - a1)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
        ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    ) return true
    return false
}

private fun cross(a: Offset, b: Offset) = a.x * b.y - a.y * b.x

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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
    is LaunchAppNode -> NodeColors.LaunchAppTealBg to NodeColors.LaunchAppTeal
}

private fun edgeConditionLabel(edge: FlowEdge): String? {
    if (edge.isFailurePath) return "✗ on failure"
    return when (val c = edge.condition) {
        null -> null; is EdgeCondition.Always -> null
        is EdgeCondition.WaitSeconds -> "⏱ ${c.seconds}s"
        is EdgeCondition.IfTextContains -> "? \"${c.substring}\""
        is EdgeCondition.IfNotTextContains -> "! \"${c.substring}\""
        is EdgeCondition.IfContextEquals -> "= ${c.key}"
        is EdgeCondition.IfNotContextEquals -> "≠ ${c.key}"
        is EdgeCondition.IfImageFound -> "🔍 found?"
        is EdgeCondition.IfNotImageFound -> "🔍 not found"
        is EdgeCondition.Retry -> "↻ ×${c.maxAttempts}"
        is EdgeCondition.Else -> "otherwise"
        is EdgeCondition.StopExecution -> "🛑 stop"
    }
}

internal fun DrawScope.drawNodeIcon(
    nodeType: FlowNodeType,
    iconCenterX: Float,
    iconCenterY: Float,
    iconColor: Color,
    accent: Color,
    scale: Float = 1f
) {
    withTransform({
        scale(scale, scale, Offset(iconCenterX, iconCenterY))
    }) {
        when (nodeType) {
            FlowNodeType.START -> {
                // Play icon (triangle inside a circle)
                drawCircle(iconColor, radius = 15f, center = Offset(iconCenterX, iconCenterY), style = Stroke(3f))
                val p = Path().apply {
                    moveTo(iconCenterX - 3f, iconCenterY - 6f)
                    lineTo(iconCenterX + 7f, iconCenterY)
                    lineTo(iconCenterX - 3f, iconCenterY + 6f)
                    close()
                }
                drawPath(p, iconColor)
            }
            FlowNodeType.GESTURE -> {
                // Pointing hand icon
                val p = Path().apply {
                    // Index finger
                    val ix = iconCenterX - 3f
                    val iy = iconCenterY - 2f
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = ix - 3.5f, top = iy - 12f, right = ix + 2.5f, bottom = iy + 3f,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                    ))
                    // Middle finger
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = ix + 2.5f, top = iy - 4.5f, right = ix + 8.5f, bottom = iy + 6f,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                    ))
                    // Ring finger
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = ix + 8.5f, top = iy - 1.5f, right = ix + 14.5f, bottom = iy + 7.5f,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                    ))
                    // Palm / body of hand
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = ix - 7.5f, top = iy + 3f, right = ix + 14.5f, bottom = iy + 15f,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.5f)
                    ))
                }
                drawPath(p, iconColor)
            }
            FlowNodeType.VISUAL_TRIGGER -> {
                // Image Match (Photo Frame with Magnifying Glass)
                // 1. Photo Frame
                drawRoundRect(
                    iconColor,
                    topLeft = Offset(iconCenterX - 13.5f, iconCenterY - 10.5f),
                    size = Size(21f, 21f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
                    style = Stroke(3f)
                )
                // Mountains inside frame
                val m = Path().apply {
                    moveTo(iconCenterX - 13.5f, iconCenterY + 6f)
                    lineTo(iconCenterX - 7.5f, iconCenterY - 1.5f)
                    lineTo(iconCenterX - 4.5f, iconCenterY + 1.5f)
                    lineTo(iconCenterX, iconCenterY - 4.5f)
                    lineTo(iconCenterX + 6f, iconCenterY + 4.5f)
                }
                drawPath(m, iconColor, style = Stroke(2.5f, join = StrokeJoin.Round))
                // 2. Overlapping Magnifying Glass (top right)
                drawCircle(accent, radius = 9f, center = Offset(iconCenterX + 7.5f, iconCenterY - 7.5f)) // mask out background
                drawCircle(iconColor, radius = 6f, center = Offset(iconCenterX + 7.5f, iconCenterY - 7.5f), style = Stroke(3f))
                drawLine(iconColor, Offset(iconCenterX + 12f, iconCenterY - 3f), Offset(iconCenterX + 18f, iconCenterY + 3f), strokeWidth = 3.5f, cap = StrokeCap.Round)
            }
            FlowNodeType.SCREEN_ML -> {
                // Document with Text Lines
                drawRoundRect(
                    iconColor,
                    topLeft = Offset(iconCenterX - 10.5f, iconCenterY - 13.5f),
                    size = Size(21f, 27f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
                    style = Stroke(3f)
                )
                drawLine(iconColor, Offset(iconCenterX - 4.5f, iconCenterY - 6f), Offset(iconCenterX + 4.5f, iconCenterY - 6f), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(iconColor, Offset(iconCenterX - 4.5f, iconCenterY), Offset(iconCenterX + 4.5f, iconCenterY), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(iconColor, Offset(iconCenterX - 4.5f, iconCenterY + 6f), Offset(iconCenterX + 1.5f, iconCenterY + 6f), strokeWidth = 3f, cap = StrokeCap.Round)
            }
            FlowNodeType.DELAY -> {
                // Hourglass
                val p = Path().apply {
                    // Top glass
                    moveTo(iconCenterX - 9f, iconCenterY - 10.5f)
                    lineTo(iconCenterX + 9f, iconCenterY - 10.5f)
                    lineTo(iconCenterX + 1.5f, iconCenterY)
                    lineTo(iconCenterX - 1.5f, iconCenterY)
                    close()
                    // Bottom glass
                    moveTo(iconCenterX - 1.5f, iconCenterY)
                    lineTo(iconCenterX + 1.5f, iconCenterY)
                    lineTo(iconCenterX + 9f, iconCenterY + 10.5f)
                    lineTo(iconCenterX - 9f, iconCenterY + 10.5f)
                    close()
                }
                drawPath(p, iconColor, style = Stroke(3f, join = StrokeJoin.Round))
                // Sand lines
                drawLine(iconColor, Offset(iconCenterX - 6f, iconCenterY - 9f), Offset(iconCenterX + 6f, iconCenterY - 9f), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(iconColor, Offset(iconCenterX - 4.5f, iconCenterY + 8f), Offset(iconCenterX + 4.5f, iconCenterY + 8f), strokeWidth = 3f, cap = StrokeCap.Round)
            }
            FlowNodeType.LAUNCH_APP -> {
                // Diagonal rocket icon (tilted 45° to fly upper-right)
                val cx = iconCenterX
                val cy = iconCenterY

                withTransform({
                    rotate(-45f, Offset(cx, cy))
                }) {
                    // Rocket body (rounded rectangle)
                    val bodyW = 10f
                    val bodyH = 18f
                    drawRoundRect(
                        iconColor,
                        topLeft = Offset(cx - bodyW / 2f, cy - bodyH / 2f + 2f),
                        size = androidx.compose.ui.geometry.Size(bodyW, bodyH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
                        style = Stroke(2.5f)
                    )

                    // Nose cone (triangle on top)
                    val nose = Path().apply {
                        moveTo(cx - bodyW / 2f, cy - bodyH / 2f + 2f)
                        lineTo(cx, cy - bodyH / 2f - 8f)
                        lineTo(cx + bodyW / 2f, cy - bodyH / 2f + 2f)
                        close()
                    }
                    drawPath(nose, iconColor)

                    // Window porthole
                    drawCircle(iconColor, radius = 3f, center = Offset(cx, cy - 1f), style = Stroke(2f))

                    // Left fin
                    val lFin = Path().apply {
                        moveTo(cx - bodyW / 2f, cy + bodyH / 2f - 2f)
                        lineTo(cx - bodyW / 2f - 5f, cy + bodyH / 2f + 5f)
                        lineTo(cx - bodyW / 2f, cy + bodyH / 2f + 2f)
                        close()
                    }
                    drawPath(lFin, iconColor)

                    // Right fin
                    val rFin = Path().apply {
                        moveTo(cx + bodyW / 2f, cy + bodyH / 2f - 2f)
                        lineTo(cx + bodyW / 2f + 5f, cy + bodyH / 2f + 5f)
                        lineTo(cx + bodyW / 2f, cy + bodyH / 2f + 2f)
                        close()
                    }
                    drawPath(rFin, iconColor)

                    // Flame (small inverted triangle at bottom)
                    val flame = Path().apply {
                        moveTo(cx - 3f, cy + bodyH / 2f + 2f)
                        lineTo(cx, cy + bodyH / 2f + 8f)
                        lineTo(cx + 3f, cy + bodyH / 2f + 2f)
                    }
                    drawPath(flame, iconColor, style = Stroke(2f, join = StrokeJoin.Round))
                }
            }
        }
    }
}
