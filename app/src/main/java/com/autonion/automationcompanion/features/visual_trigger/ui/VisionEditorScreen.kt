package com.autonion.automationcompanion.features.visual_trigger.ui

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import com.autonion.automationcompanion.features.visual_trigger.models.TapDispatchMode
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private enum class DragMode { NONE, DRAW, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, ROTATE, SEARCH_AREA }

private const val RotationHandleOffsetPx = 56f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionTool(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xE622252B), contentColor = tint)
        ) { Icon(icon, label) }
    }
}

private fun normalizeRotationDegrees(degrees: Float): Float {
    var normalized = degrees % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized <= -180f) normalized += 360f
    return normalized
}

private fun DragMode.isResizeMode(): Boolean = this == DragMode.RESIZE_TL ||
    this == DragMode.RESIZE_TR ||
    this == DragMode.RESIZE_BL ||
    this == DragMode.RESIZE_BR

private fun rotatePoint(point: Offset, pivot: Offset, degrees: Float): Offset {
    if (abs(degrees) < 0.01f) return point
    val radians = Math.toRadians(degrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return Offset(
        x = pivot.x + dx * cos - dy * sin,
        y = pivot.y + dx * sin + dy * cos
    )
}

private fun rotateVector(vector: Offset, degrees: Float): Offset =
    rotatePoint(vector, Offset.Zero, degrees)

private fun regionCenter(rect: Rect, scale: Float): Offset = Offset(
    x = (rect.left + rect.width() / 2f) * scale,
    y = (rect.top + rect.height() / 2f) * scale
)

private fun rotatedRectCorners(rect: Rect, scale: Float, rotationDegrees: Float): List<Pair<DragMode, Offset>> {
    val center = regionCenter(rect, scale)
    return listOf(
        DragMode.RESIZE_TL to Offset(rect.left * scale, rect.top * scale),
        DragMode.RESIZE_TR to Offset(rect.right * scale, rect.top * scale),
        DragMode.RESIZE_BR to Offset(rect.right * scale, rect.bottom * scale),
        DragMode.RESIZE_BL to Offset(rect.left * scale, rect.bottom * scale)
    ).map { (mode, point) -> mode to rotatePoint(point, center, rotationDegrees) }
}

private fun rotatedTopCenter(rect: Rect, scale: Float, rotationDegrees: Float): Offset {
    val center = regionCenter(rect, scale)
    return rotatePoint(Offset(center.x, rect.top * scale), center, rotationDegrees)
}

private fun rotationHandlePosition(rect: Rect, scale: Float, rotationDegrees: Float): Offset {
    val center = regionCenter(rect, scale)
    val topCenter = rotatedTopCenter(rect, scale, rotationDegrees)
    val dx = topCenter.x - center.x
    val dy = topCenter.y - center.y
    val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    return Offset(
        x = topCenter.x + dx / distance * RotationHandleOffsetPx,
        y = topCenter.y + dy / distance * RotationHandleOffsetPx
    )
}

private fun pointInRotatedRect(point: Offset, rect: Rect, scale: Float, rotationDegrees: Float): Boolean {
    val center = regionCenter(rect, scale)
    val unrotated = rotatePoint(point, center, -rotationDegrees)
    return unrotated.x >= rect.left * scale &&
        unrotated.x <= rect.right * scale &&
        unrotated.y >= rect.top * scale &&
        unrotated.y <= rect.bottom * scale
}

private fun rotationFromCenter(point: Offset, center: Offset): Float {
    val degrees = Math.toDegrees(atan2(point.y - center.y, point.x - center.x).toDouble()).toFloat()
    return normalizeRotationDegrees(degrees + 90f)
}

private fun resizeRotatedRect(rect: Rect, rotationDegrees: Float, dragPoint: Offset, scale: Float, mode: DragMode): Rect {
    val halfWidth = rect.width() / 2f
    val halfHeight = rect.height() / 2f
    val center = Offset(rect.left + halfWidth, rect.top + halfHeight)
    val minSize = 6f

    val fixedLocal = when (mode) {
        DragMode.RESIZE_TL -> Offset(halfWidth, halfHeight)
        DragMode.RESIZE_TR -> Offset(-halfWidth, halfHeight)
        DragMode.RESIZE_BL -> Offset(halfWidth, -halfHeight)
        DragMode.RESIZE_BR -> Offset(-halfWidth, -halfHeight)
        else -> Offset.Zero
    }
    val fixedGlobal = center + rotateVector(fixedLocal, rotationDegrees)
    val draggedGlobal = Offset(dragPoint.x / scale, dragPoint.y / scale)
    val deltaLocal = rotateVector(draggedGlobal - fixedGlobal, -rotationDegrees)

    val newWidth: Float
    val newHeight: Float
    val centerOffsetLocal: Offset
    when (mode) {
        DragMode.RESIZE_TL -> {
            newWidth = (-deltaLocal.x).coerceAtLeast(minSize)
            newHeight = (-deltaLocal.y).coerceAtLeast(minSize)
            centerOffsetLocal = Offset(-newWidth / 2f, -newHeight / 2f)
        }
        DragMode.RESIZE_TR -> {
            newWidth = deltaLocal.x.coerceAtLeast(minSize)
            newHeight = (-deltaLocal.y).coerceAtLeast(minSize)
            centerOffsetLocal = Offset(newWidth / 2f, -newHeight / 2f)
        }
        DragMode.RESIZE_BL -> {
            newWidth = (-deltaLocal.x).coerceAtLeast(minSize)
            newHeight = deltaLocal.y.coerceAtLeast(minSize)
            centerOffsetLocal = Offset(-newWidth / 2f, newHeight / 2f)
        }
        DragMode.RESIZE_BR -> {
            newWidth = deltaLocal.x.coerceAtLeast(minSize)
            newHeight = deltaLocal.y.coerceAtLeast(minSize)
            centerOffsetLocal = Offset(newWidth / 2f, newHeight / 2f)
        }
        else -> return Rect(rect)
    }

    val newCenter = fixedGlobal + rotateVector(centerOffsetLocal, rotationDegrees)
    return Rect(
        (newCenter.x - newWidth / 2f).roundToInt(),
        (newCenter.y - newHeight / 2f).roundToInt(),
        (newCenter.x + newWidth / 2f).roundToInt(),
        (newCenter.y + newHeight / 2f).roundToInt()
    )
}

@Composable
fun VisionEditorScreen(
    imagePath: String,
    presetId: String? = null,
    appendPresetId: String? = null,
    presetName: String = "New Automation",
    isFlowMode: Boolean = false,
    flowNodeId: String? = null,
    onSaved: (String?) -> Unit,
    onCancel: () -> Unit,
    onRecapture: () -> Unit,
    flowVisionJson: String? = null,
    viewModel: VisionEditorViewModel = viewModel()
) {
    val initialized = remember { mutableStateOf(false) }
    if (!initialized.value) {
        if (flowVisionJson != null) {
            viewModel.loadFlowPreset(flowVisionJson) { success ->
                if (!success) viewModel.loadImage(imagePath)
            }
        } else if (presetId != null) {
            viewModel.loadExistingPreset(presetId) { success ->
                if (!success) viewModel.loadImage(imagePath)
            }
        } else {
            if (appendPresetId != null) {
                viewModel.prepareForAppend(appendPresetId)
            }
            viewModel.loadImage(imagePath)
        }
        initialized.value = true
    }

    val bitmap by viewModel.imageBitmap.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    BackHandler(enabled = isSaving) { }
    val fullResWidth by viewModel.fullResWidth.collectAsState()
    val fullResHeight by viewModel.fullResHeight.collectAsState()
    val regions by viewModel.regions.collectAsState()
    val executionMode by viewModel.executionMode.collectAsState()
    val tapDispatchMode by viewModel.tapDispatchMode.collectAsState()
    val capturePages by viewModel.capturePages.collectAsState()
    val currentPageIndex by viewModel.currentPageIndex.collectAsState()
    val isMultiPage = capturePages.size > 1
    val hasTapRegion = regions.any { it.action is VisionAction.Click }

    // Drawing / editing state
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var selectedRegionId by remember { mutableStateOf<Int?>(null) }
    var searchAreaRegionId by remember { mutableStateOf<Int?>(null) }
    var editingRect by remember { mutableStateOf<Rect?>(null) } // live rect during move/resize
    var editingRotation by remember { mutableStateOf<Float?>(null) }

    // Region detail dialog
    var dialogRegion by remember { mutableStateOf<VisionEditorViewModel.TempRegion?>(null) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Auto-dismiss instruction hint after 3 seconds
    var showInstructionHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3000L)
        showInstructionHint = false
    }
    // Also hide when first region is drawn
    LaunchedEffect(regions.size) {
        if (regions.isNotEmpty()) showInstructionHint = false
    }

    val handleRadius = 18f  // Larger handles for easier grab
    val handleHitRadius = handleRadius * 3.5f  // Even larger hit area for finger touch

    var userScale by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }
    val viewConfiguration = LocalViewConfiguration.current

    val startSearchAreaSelection: (Int) -> Unit = { regionId ->
        selectedRegionId = regionId
        searchAreaRegionId = regionId
        dragStart = null
        dragCurrent = null
        editingRect = null
        editingRotation = null
        dragMode = DragMode.NONE
        showRegionDialog = false
        dialogRegion = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D1A))
    ) {
        if (bitmap != null) {
            val imageBitmap = bitmap!!.asImageBitmap()
            val imageWidth = fullResWidth    // coordinate space = full-res
            val imageHeight = fullResHeight  // coordinate space = full-res

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            var pastTouchSlop = false
                            val touchSlop = viewConfiguration.touchSlop
                            var zoomAccumulator = 1f
                            var panAccumulator = Offset.Zero

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val canceled = event.changes.any { it.isConsumed }
                                if (!canceled && event.changes.size > 1) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)

                                    if (!pastTouchSlop) {
                                        zoomAccumulator *= zoomChange
                                        panAccumulator += panChange
                                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                        val zoomMotion = abs(1 - zoomAccumulator) * centroidSize
                                        val panMotion = panAccumulator.getDistance()
                                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                            pastTouchSlop = true
                                        }
                                    }

                                    if (pastTouchSlop) {
                                        val oldScale = userScale
                                        userScale = (userScale * zoomChange).coerceIn(1f, 10f)

                                        val newOffsetX = userOffset.x + panChange.x + centroid.x * (oldScale - userScale)
                                        val newOffsetY = userOffset.y + panChange.y + centroid.y * (oldScale - userScale)

                                        val maxX = 0f
                                        val minX = (size.width * (1 - userScale))
                                        val maxY = 0f
                                        val minY = (size.height * (1 - userScale))

                                        userOffset = Offset(
                                            x = newOffsetX.coerceIn(minX, maxX),
                                            y = newOffsetY.coerceIn(minY, maxY)
                                        )

                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } else if (event.changes.size <= 1) {
                                    pastTouchSlop = false
                                    zoomAccumulator = 1f
                                    panAccumulator = Offset.Zero
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer(
                        scaleX = userScale,
                        scaleY = userScale,
                        translationX = userOffset.x,
                        translationY = userOffset.y,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
                    .onGloballyPositioned { canvasSize = it.size }
                    .pointerInput(canvasSize, regions, selectedRegionId, searchAreaRegionId) {
                        if (canvasSize == IntSize.Zero) return@pointerInput
                        val scaleX = canvasSize.width.toFloat() / imageWidth
                        val scaleY = canvasSize.height.toFloat() / imageHeight
                        val scale = minOf(scaleX, scaleY)

                        detectDragGestures(
                            onDragStart = { pos ->
                                if (searchAreaRegionId != null) {
                                    selectedRegionId = searchAreaRegionId
                                    dragMode = DragMode.SEARCH_AREA
                                    dragStart = pos
                                    dragCurrent = pos
                                    editingRect = null
                                    editingRotation = null
                                    return@detectDragGestures
                                }

                                // Check selected region handles first.
                                val sel = if (selectedRegionId != null) regions.find { it.id == selectedRegionId } else null
                                if (sel != null) {
                                    val r = sel.rect
                                    val rotation = sel.rotationDegrees
                                    val rotationHandle = rotationHandlePosition(r, scale, rotation)
                                    val rhDx = pos.x - rotationHandle.x
                                    val rhDy = pos.y - rotationHandle.y
                                    if (rhDx * rhDx + rhDy * rhDy < handleHitRadius * handleHitRadius) {
                                        dragMode = DragMode.ROTATE
                                        editingRect = Rect(r)
                                        editingRotation = rotation
                                        dragStart = pos
                                        return@detectDragGestures
                                    }

                                    val corners = rotatedRectCorners(r, scale, rotation)
                                    val hitCorner = corners.find {
                                        val dx = pos.x - it.second.x
                                        val dy = pos.y - it.second.y
                                        dx * dx + dy * dy < handleHitRadius * handleHitRadius
                                    }
                                    if (hitCorner != null) {
                                        dragMode = hitCorner.first
                                        editingRect = Rect(r)
                                        dragStart = pos
                                        return@detectDragGestures
                                    }
                                    // Check if inside the selected region → move
                                    if (pointInRotatedRect(pos, r, scale, rotation)) {
                                        dragMode = DragMode.MOVE
                                        editingRect = Rect(r)
                                        dragStart = pos
                                        return@detectDragGestures
                                    }
                                }

                                // Check if touching any other region → select it
                                val hit = regions.asReversed().find { region ->
                                    pointInRotatedRect(pos, region.rect, scale, region.rotationDegrees)
                                }
                                if (hit != null) {
                                    selectedRegionId = hit.id
                                    dragMode = DragMode.MOVE
                                    editingRect = Rect(hit.rect)
                                    dragStart = pos
                                    return@detectDragGestures
                                }

                                // Otherwise → draw new region
                                selectedRegionId = null
                                editingRect = null
                                dragMode = DragMode.DRAW
                                dragStart = pos
                            },
                            onDrag = { change, _ ->
                                dragCurrent = change.position
                                val s = if (canvasSize.width > 0) minOf(canvasSize.width.toFloat() / imageWidth, canvasSize.height.toFloat() / imageHeight) else 1f

                                val selectedRegion = regions.find { it.id == selectedRegionId }
                                if (dragMode == DragMode.MOVE && editingRect != null && dragStart != null) {
                                    val origRect = selectedRegion?.rect ?: return@detectDragGestures
                                    val dx = ((change.position.x - dragStart!!.x) / s).toInt()
                                    val dy = ((change.position.y - dragStart!!.y) / s).toInt()
                                    editingRect = Rect(
                                        origRect.left + dx, origRect.top + dy,
                                        origRect.right + dx, origRect.bottom + dy
                                    )
                                } else if (dragMode.isResizeMode() && editingRect != null && selectedRegion != null) {
                                    val newRect = resizeRotatedRect(
                                        selectedRegion.rect,
                                        selectedRegion.rotationDegrees,
                                        change.position,
                                        s,
                                        dragMode
                                    )
                                    if (newRect.width() > 5 && newRect.height() > 5) {
                                        editingRect = newRect
                                    }
                                } else if (dragMode == DragMode.ROTATE && selectedRegion != null) {
                                    val activeRect = editingRect ?: selectedRegion.rect
                                    editingRotation = rotationFromCenter(change.position, regionCenter(activeRect, s))
                                }
                            },
                            onDragEnd = {
                                val s = if (canvasSize.width > 0) minOf(canvasSize.width.toFloat() / imageWidth, canvasSize.height.toFloat() / imageHeight) else 1f

                                when (dragMode) {
                                    DragMode.DRAW -> {
                                        if (dragStart != null && dragCurrent != null) {
                                            val start = dragStart!!
                                            val end = dragCurrent!!
                                            val left = minOf(start.x, end.x).toInt()
                                            val top = minOf(start.y, end.y).toInt()
                                            val right = maxOf(start.x, end.x).toInt()
                                            val bottom = maxOf(start.y, end.y).toInt()

                                            // Lower threshold — easier to draw small boxes
                                            if (right - left > 5 && bottom - top > 5) {
                                                val scaledRect = Rect(
                                                    (left / s).toInt(), (top / s).toInt(),
                                                    (right / s).toInt(), (bottom / s).toInt()
                                                )
                                                scaledRect.intersect(0, 0, imageWidth, imageHeight)
                                                if (scaledRect.width() > 3 && scaledRect.height() > 3) {
                                                    viewModel.addRegion(scaledRect)
                                                }
                                            }
                                        }
                                    }
                                    DragMode.SEARCH_AREA -> {
                                        val targetRegionId = searchAreaRegionId
                                        if (targetRegionId != null && dragStart != null && dragCurrent != null) {
                                            val start = dragStart!!
                                            val end = dragCurrent!!
                                            val left = minOf(start.x, end.x).toInt()
                                            val top = minOf(start.y, end.y).toInt()
                                            val right = maxOf(start.x, end.x).toInt()
                                            val bottom = maxOf(start.y, end.y).toInt()

                                            if (right - left > 5 && bottom - top > 5) {
                                                val scaledRect = Rect(
                                                    (left / s).toInt(), (top / s).toInt(),
                                                    (right / s).toInt(), (bottom / s).toInt()
                                                )
                                                if (scaledRect.intersect(0, 0, imageWidth, imageHeight) &&
                                                    scaledRect.width() > 3 &&
                                                    scaledRect.height() > 3
                                                ) {
                                                    viewModel.updateRegionSearchRect(targetRegionId, scaledRect)
                                                }
                                            }
                                        }
                                        searchAreaRegionId = null
                                    }
                                    DragMode.MOVE, DragMode.RESIZE_TL, DragMode.RESIZE_TR, DragMode.RESIZE_BL, DragMode.RESIZE_BR -> {
                                        if (selectedRegionId != null && editingRect != null) {
                                            val clamped = Rect(editingRect!!)
                                            clamped.intersect(0, 0, imageWidth, imageHeight)
                                            if (clamped.width() > 3 && clamped.height() > 3) {
                                                viewModel.updateRegionRect(selectedRegionId!!, clamped)
                                            }
                                        }
                                    }
                                    DragMode.ROTATE -> {
                                        if (selectedRegionId != null && editingRotation != null) {
                                            viewModel.updateRegionRotation(selectedRegionId!!, editingRotation!!)
                                        }
                                    }
                                    DragMode.NONE -> {}
                                }
                                dragStart = null
                                dragCurrent = null
                                editingRect = null
                                editingRotation = null
                                dragMode = DragMode.NONE
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                                editingRect = null
                                editingRotation = null
                                searchAreaRegionId = null
                                dragMode = DragMode.NONE
                            }
                        )
                    }
                    .pointerInput(canvasSize, regions, searchAreaRegionId) {
                        if (canvasSize == IntSize.Zero || searchAreaRegionId != null) return@pointerInput
                        val scaleX = canvasSize.width.toFloat() / imageWidth
                        val scaleY = canvasSize.height.toFloat() / imageHeight
                        val scale = minOf(scaleX, scaleY)

                        detectTapGestures(
                            onTap = { offset ->
                                val hit = regions.asReversed().find { region ->
                                    pointInRotatedRect(offset, region.rect, scale, region.rotationDegrees)
                                }
                                selectedRegionId = hit?.id // select or deselect
                            },
                            onLongPress = { offset ->
                                val region = regions.asReversed().find {
                                    pointInRotatedRect(offset, it.rect, scale, it.rotationDegrees)
                                }
                                if (region != null) {
                                    dialogRegion = region
                                    showRegionDialog = true
                                }
                            }
                        )
                    }
            ) {
                val viewWidth = size.width
                val viewHeight = size.height
                val scaleX = viewWidth / imageWidth.toFloat()
                val scaleY = viewHeight / imageHeight.toFloat()
                val scale = minOf(scaleX, scaleY)
                val drawWidth = (imageWidth * scale).toInt()
                val drawHeight = (imageHeight * scale).toInt()

                drawImage(
                    image = imageBitmap,
                    dstSize = androidx.compose.ui.unit.IntSize(drawWidth, drawHeight)
                )

                // Draw regions
                regions.forEachIndexed { index, region ->
                    // Use live editing rect during drag
                    val r = if (region.id == selectedRegionId && editingRect != null) editingRect!! else region.rect
                    val isSelected = region.id == selectedRegionId
                    val rotation = if (isSelected) editingRotation ?: region.rotationDegrees else region.rotationDegrees

                    val center = regionCenter(r, scale)
                    val corners = rotatedRectCorners(r, scale, rotation)
                    val cornerPoints = corners.map { it.second }
                    val regionPath = Path().apply {
                        moveTo(cornerPoints[0].x, cornerPoints[0].y)
                        cornerPoints.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }

                    region.searchRect?.let { searchRect ->
                        val roiColor = Color(0xFF00E5FF)
                        val roiTopLeft = Offset(searchRect.left * scale, searchRect.top * scale)
                        val roiSize = Size(searchRect.width() * scale, searchRect.height() * scale)
                        drawRect(
                            color = roiColor.copy(alpha = if (isSelected) 0.14f else 0.06f),
                            topLeft = roiTopLeft,
                            size = roiSize
                        )
                        drawRect(
                            color = roiColor,
                            topLeft = roiTopLeft,
                            size = roiSize,
                            style = Stroke(width = if (isSelected) 3f else 1.5f)
                        )
                    }

                    drawPath(color = Color(region.color), path = regionPath, alpha = if (isSelected) 0.35f else 0.25f)
                    drawPath(color = Color(region.color), path = regionPath, style = Stroke(width = if (isSelected) 4f else 2.5f))

                    // Sequence number badge (#1, #2, ...)
                    val seqText = "#${index + 1}"
                    val badgePaint = android.graphics.Paint().apply { color = 0xDD000000.toInt(); isAntiAlias = true }
                    val seqPaint = android.graphics.Paint().apply {
                        color = 0xFFFFFFFF.toInt(); textSize = 13f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true
                    }
                    val seqWidth = seqPaint.measureText(seqText)
                    val badgeCx = center.x
                    val badgeCy = center.y
                    val badgeW = seqWidth + 16f
                    val badgeH = seqPaint.textSize + 10f
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        badgeCx - badgeW / 2, badgeCy - badgeH / 2,
                        badgeCx + badgeW / 2, badgeCy + badgeH / 2,
                        8f, 8f, badgePaint
                    )
                    drawContext.canvas.nativeCanvas.drawText(seqText, badgeCx - seqWidth / 2, badgeCy + seqPaint.textSize / 3, seqPaint)

                    // Action label at top
                    val actionText = when (region.action) {
                        is VisionAction.Click -> "TAP"
                        is VisionAction.LongClick -> "LONG"
                        is VisionAction.Scroll -> "SCROLL ${(region.action as VisionAction.Scroll).direction.name}"
                    }
                    val actionPaint = android.graphics.Paint().apply {
                        color = region.color; textSize = 10f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true
                    }
                    val actionWidth = actionPaint.measureText(actionText)
                    val labelAnchor = cornerPoints.minByOrNull { it.y } ?: Offset.Zero
                    val aBadgeLeft = labelAnchor.x
                    val aBadgeTop = labelAnchor.y - actionPaint.textSize - 8f
                    if (aBadgeTop > 0) {
                        drawContext.canvas.nativeCanvas.drawRoundRect(
                            aBadgeLeft, aBadgeTop,
                            aBadgeLeft + actionWidth + 12f, labelAnchor.y - 2f,
                            6f, 6f, badgePaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(actionText, aBadgeLeft + 6f, labelAnchor.y - 6f, actionPaint)
                    }

                    // Corner handles when selected — larger and more visible
                    if (isSelected) {
                        cornerPoints.forEach { corner ->
                            // Outer ring for better visibility
                            drawCircle(color = Color.White, radius = handleRadius, center = corner)
                            drawCircle(color = Color(region.color), radius = handleRadius - 4f, center = corner)
                        }
                        val topCenter = rotatedTopCenter(r, scale, rotation)
                        val rotateHandle = rotationHandlePosition(r, scale, rotation)
                        drawLine(
                            color = Color.White.copy(alpha = 0.75f),
                            start = topCenter,
                            end = rotateHandle,
                            strokeWidth = 3f
                        )
                        drawCircle(color = Color.White, radius = handleRadius, center = rotateHandle)
                        drawCircle(color = Color(0xFF0D0D1A), radius = handleRadius - 4f, center = rotateHandle)
                        drawCircle(color = Color(region.color), radius = 6f, center = rotateHandle)
                    }
                }

                // Current drag rectangle
                if ((dragMode == DragMode.DRAW || dragMode == DragMode.SEARCH_AREA) && dragStart != null && dragCurrent != null) {
                    val start = dragStart!!
                    val end = dragCurrent!!
                    val tl = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                    val sz = Size(abs(end.x - start.x), abs(end.y - start.y))
                    val dragColor = if (dragMode == DragMode.SEARCH_AREA) Color(0xFF00E5FF) else Color.White
                    drawRect(color = dragColor, topLeft = tl, size = sz, style = Stroke(width = 3f))
                    drawRect(color = dragColor.copy(alpha = 0.15f), topLeft = tl, size = sz)
                }
            }

            // Instruction hint — auto-dismisses after 3s, doesn't block touches
            AnimatedVisibility(
                visible = showInstructionHint && regions.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isMultiPage) 72.dp else 24.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "Draw rectangles around UI elements to track",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // ─── Multi-Page Navigator Bar ─────────────────────────
            if (isMultiPage) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xE61A1A2E),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // Previous page
                        SmallFloatingActionButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    viewModel.navigateToPage(currentPageIndex - 1)
                                    selectedRegionId = null
                                    searchAreaRegionId = null
                                }
                            },
                            containerColor = if (currentPageIndex > 0) Color(0xFF00C853) else Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page", modifier = Modifier.size(20.dp))
                        }

                        // Page dots
                        capturePages.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == currentPageIndex) 10.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == currentPageIndex) Color(0xFF00C853)
                                        else Color.White.copy(alpha = 0.3f)
                                    )
                            )
                        }

                        // Page label
                        Text(
                            text = "${currentPageIndex + 1}/${capturePages.size}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        // Next page
                        SmallFloatingActionButton(
                            onClick = {
                                if (currentPageIndex < capturePages.size - 1) {
                                    viewModel.navigateToPage(currentPageIndex + 1)
                                    selectedRegionId = null
                                    searchAreaRegionId = null
                                }
                            },
                            containerColor = if (currentPageIndex < capturePages.size - 1) Color(0xFF00C853) else Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next page", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            val selectedTarget = regions.find { it.id == selectedRegionId }
            if (selectedTarget != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { dialogRegion = selectedTarget; showRegionDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xE622252B), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Tune, "Target settings", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(when {
                            searchAreaRegionId != null -> "Search area"
                            selectedTarget.matchMode == VisionMatchMode.MOVING -> "Rotating"
                            else -> "Fast match"
                        })
                    }
                    RegionTool("Select search area (ROI)", Icons.Default.CropFree, Color(0xFF00E5FF)) {
                        startSearchAreaSelection(selectedTarget.id)
                    }
                    RegionTool("Rotate selected region", Icons.Rounded.ScreenRotation) {
                        viewModel.updateRegionRotation(selectedTarget.id, normalizeRotationDegrees(selectedTarget.rotationDegrees + 15f))
                    }
                }
            }

        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00C853))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading capture...", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        // --- Floating toolbar at top — contains ALL action buttons ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Cancel and Execution Mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(
                    onClick = onCancel,
                    containerColor = Color(0xE61A1A2E),
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                }

                var showExecutionModeMenu by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { showExecutionModeMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xE61A1A2E), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            text = when (executionMode) {
                                ExecutionMode.MANDATORY_SEQUENTIAL -> "Mandatory Seq"
                                ExecutionMode.OPTIONAL_SEQUENTIAL -> "Optional Seq"
                                ExecutionMode.DETECT_ONLY -> when {
                                    !hasTapRegion -> "Detect Only"
                                    tapDispatchMode == TapDispatchMode.CONCURRENT -> "Detect: Multi"
                                    else -> "Detect: Seq"
                                }
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    DropdownMenu(
                        expanded = showExecutionModeMenu,
                        onDismissRequest = { showExecutionModeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mandatory Sequential") },
                            onClick = {
                                viewModel.updateExecutionMode(ExecutionMode.MANDATORY_SEQUENTIAL)
                                showExecutionModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Optional Sequential") },
                            onClick = {
                                viewModel.updateExecutionMode(ExecutionMode.OPTIONAL_SEQUENTIAL)
                                showExecutionModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Detect Only") },
                            onClick = {
                                viewModel.updateExecutionMode(ExecutionMode.DETECT_ONLY)
                                showExecutionModeMenu = false
                            }
                        )
                        if (executionMode == ExecutionMode.DETECT_ONLY && hasTapRegion) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "When multiple tap targets match",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                    )
                                },
                                enabled = false,
                                onClick = {}
                            )
                            DropdownMenuItem(
                                text = { Text("Sequential Tap") },
                                leadingIcon = {
                                    if (tapDispatchMode == TapDispatchMode.SEQUENTIAL) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    viewModel.updateTapDispatchMode(TapDispatchMode.SEQUENTIAL)
                                    showExecutionModeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Concurrent Tap") },
                                leadingIcon = {
                                    if (tapDispatchMode == TapDispatchMode.CONCURRENT) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    viewModel.updateTapDispatchMode(TapDispatchMode.CONCURRENT)
                                    showExecutionModeMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Right: Selected-region rotate, Recapture, Undo, Save — all grouped together
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(
                    onClick = onRecapture,
                    containerColor = Color(0xE61A1A2E),
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Recapture", modifier = Modifier.size(20.dp))
                }

                if (regions.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.undoLastRegion() },
                        containerColor = Color(0xE61A1A2E),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(20.dp))
                    }

                    // Save button — moved here from bottom-right to avoid obscuring drawn regions
                    SmallFloatingActionButton(
                        onClick = {
                            if (isSaving) return@SmallFloatingActionButton
                            if (isFlowMode && flowNodeId != null) {
                                viewModel.saveForFlowMode(flowNodeId) { filePath -> onSaved(filePath) }
                            } else {
                                viewModel.savePreset(presetName) { savedPresetId -> onSaved(savedPresetId) }
                            }
                        },
                        containerColor = Color(0xFF00C853),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else Icon(Icons.Default.Check, "Save", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    // --- Region Detail Dialog ---
    if (showRegionDialog && dialogRegion != null) {
        val region = dialogRegion!!
        AlertDialog(
            onDismissRequest = { showRegionDialog = false; dialogRegion = null },
            containerColor = Color(0xFF22252B),
            titleContentColor = Color.White,
            title = { Text("Region #${regions.indexOfFirst { it.id == region.id } + 1}", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Match mode", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(VisionMatchMode.STATIC, VisionMatchMode.MOVING).forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = region.matchMode == mode,
                                onClick = {
                                    viewModel.updateRegionMatchMode(region.id, mode)
                                    dialogRegion = region.copy(matchMode = mode)
                                },
                                enabled = mode == VisionMatchMode.STATIC ||
                                    (executionMode == ExecutionMode.DETECT_ONLY && region.action is VisionAction.Click),
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) { Text(if (mode == VisionMatchMode.STATIC) "Fast match" else "Rotating", fontSize = 12.sp) }
                        }
                    }
                    if (region.matchMode == VisionMatchMode.MOVING) {
                        Text("Prediction lead: ${region.tapLeadMs} ms", color = Color.White)
                        Slider(
                            value = region.tapLeadMs.toFloat(),
                            onValueChange = {
                                val lead = (it / 5).roundToInt() * 5
                                viewModel.updateRegionTapLead(region.id, lead)
                                dialogRegion = region.copy(tapLeadMs = lead)
                            },
                            valueRange = 0f..150f,
                            steps = 29
                        )
                    }
                    Text("Select action for this region:", color = Color.White.copy(alpha = 0.7f))

                    val actions = if (region.matchMode == VisionMatchMode.MOVING) {
                        listOf("Tap" to VisionAction.Click)
                    } else listOf(
                        "Tap" to VisionAction.Click,
                        "Long Press" to VisionAction.LongClick,
                        "Scroll Up" to VisionAction.Scroll(ScrollDirection.UP),
                        "Scroll Down" to VisionAction.Scroll(ScrollDirection.DOWN),
                    )

                    actions.forEach { (label, action) ->
                        val isSelected = when {
                            action is VisionAction.Click && region.action is VisionAction.Click -> true
                            action is VisionAction.LongClick && region.action is VisionAction.LongClick -> true
                            action is VisionAction.Scroll && region.action is VisionAction.Scroll ->
                                (action as VisionAction.Scroll).direction == (region.action as VisionAction.Scroll).direction
                            else -> false
                        }

                        Surface(
                            onClick = {
                                viewModel.updateRegionAction(region.id, action)
                                dialogRegion = dialogRegion!!.copy(action = action)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF00C853).copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00C853))
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFF00C853) else Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Match threshold: ${String.format("%.2f", if (region.matchMode == VisionMatchMode.MOVING) region.movingMatchThreshold else region.matchThreshold)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = if (region.matchMode == VisionMatchMode.MOVING) region.movingMatchThreshold else region.matchThreshold,
                        onValueChange = { value ->
                            val snapped = (Math.round(value * 20f) / 20f).coerceIn(0.5f, 1.0f)
                            viewModel.updateRegionThreshold(region.id, snapped)
                            dialogRegion = if (region.matchMode == VisionMatchMode.MOVING) region.copy(movingMatchThreshold = snapped) else region.copy(matchThreshold = snapped)
                        },
                        valueRange = 0.5f..1.0f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00C853),
                            activeTrackColor = Color(0xFF00C853)
                        )
                    )

                    val currentSearchRect = region.searchRect
                    val searchAreaText = currentSearchRect?.let {
                        "Custom: ${it.width()}x${it.height()} at ${it.left}, ${it.top}"
                    } ?: when {
                        region.matchMode == VisionMatchMode.MOVING -> "Required"
                        executionMode == ExecutionMode.DETECT_ONLY -> "Full screen"
                        else -> "Around target"
                    }

                    Text(
                        text = "Search area: $searchAreaText",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { startSearchAreaSelection(region.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.42f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Draw ROI",
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Surface(
                            onClick = {
                                val fullScreenRect = Rect(0, 0, fullResWidth, fullResHeight)
                                viewModel.updateRegionSearchRect(region.id, fullScreenRect)
                                dialogRegion = dialogRegion!!.copy(searchRect = fullScreenRect)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Full Screen",
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (currentSearchRect != null) {
                        Surface(
                            onClick = {
                                viewModel.updateRegionSearchRect(region.id, null)
                                dialogRegion = dialogRegion!!.copy(searchRect = null)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Use Auto Area",
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        onClick = { showDeleteConfirm = true },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFF1744).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Region", color = Color(0xFFFF1744), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDialog = false; dialogRegion = null }) {
                    Text("Done", color = Color(0xFF00C853))
                }
            }
        )
    }

    if (isSaving) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false
            )
        ) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Saving preset...")
                }
            }
        }
    }
    saveError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveError,
            title = { Text("Preset not saved") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSaveError) { Text("OK") }
            }
        )
    }

    // --- Delete confirmation ---
    if (showDeleteConfirm && dialogRegion != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF22252B),
            title = { Text("Delete Region?", color = Color.White) },
            text = { Text("This region will be removed.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRegion(dialogRegion!!.id)
                    showDeleteConfirm = false; showRegionDialog = false; dialogRegion = null
                    selectedRegionId = null
                    searchAreaRegionId = null
                }) { Text("Delete", color = Color(0xFFFF1744)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}
