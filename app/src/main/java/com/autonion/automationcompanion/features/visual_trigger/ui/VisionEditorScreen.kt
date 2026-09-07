package com.autonion.automationcompanion.features.visual_trigger.ui

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.*
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private enum class DragMode { NONE, DRAW, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, ROTATE, SEARCH_AREA }

private const val RotationHandleOffsetPx = 56f

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

private fun rotationHandlePosition(rect: Rect, scale: Float, rotationDegrees: Float, canvas: IntSize): Offset {
    val center = regionCenter(rect, scale)
    val topCenter = rotatedTopCenter(rect, scale, rotationDegrees)
    val dx = topCenter.x - center.x
    val dy = topCenter.y - center.y
    val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    return Offset(
        x = (topCenter.x + dx / distance * RotationHandleOffsetPx).coerceIn(20f, (canvas.width - 20f).coerceAtLeast(20f)),
        y = (topCenter.y + dy / distance * RotationHandleOffsetPx).coerceIn(20f, (canvas.height - 20f).coerceAtLeast(20f))
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
    val initialized = rememberSaveable { mutableStateOf(false) }
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
    var selectedRegionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var searchAreaRegionId by remember { mutableStateOf<Int?>(null) }
    var editingRect by remember { mutableStateOf<Rect?>(null) } // live rect during move/resize
    var editingRotation by remember { mutableStateOf<Float?>(null) }
    var rotationDragOffset by remember { mutableFloatStateOf(0f) }

    // Region detail dialog
    val dialogRegion = regions.find { it.id == selectedRegionId }
    var showRunSettings by rememberSaveable { mutableStateOf(false) }
    var showRegionDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
    }

    BackHandler(enabled = searchAreaRegionId != null) { searchAreaRegionId = null }
    LaunchedEffect(canvasSize, currentPageIndex) {
        userScale = 1f
        userOffset = Offset.Zero
        dragStart = null
        dragCurrent = null
        editingRect = null
        editingRotation = null
        dragMode = DragMode.NONE
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111416))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val landscape = maxWidth > maxHeight
        val toolbarHeight = if (isMultiPage) 145.dp else 97.dp
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(
                end = if (landscape) 104.dp else 0.dp,
                bottom = if (landscape) 0.dp else toolbarHeight
            ).padding(12.dp).clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
        if (bitmap != null && fullResWidth > 0 && fullResHeight > 0) {
            val imageBitmap = bitmap!!.asImageBitmap()
            val imageWidth = fullResWidth    // coordinate space = full-res
            val imageHeight = fullResHeight  // coordinate space = full-res

            Canvas(
                modifier = Modifier
                    .aspectRatio(imageWidth.toFloat() / imageHeight,
                        matchHeightConstraintsFirst = maxWidth / maxHeight > imageWidth.toFloat() / imageHeight)
                    .semantics {
                        contentDescription = "Captured screen"
                        stateDescription = if (selectedRegionId == null) "No target selected"
                            else "Target #${regions.indexOfFirst { it.id == selectedRegionId } + 1} selected"
                        customActions = regions.mapIndexed { index, region ->
                            CustomAccessibilityAction("Select target #${index + 1}") {
                                selectedRegionId = region.id
                                true
                            }
                        }
                    }
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
                    .pointerInput(canvasSize, regions, searchAreaRegionId) {
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
                                    val rotationHandle = rotationHandlePosition(r, scale, rotation, canvasSize)
                                    val rhDx = pos.x - rotationHandle.x
                                    val rhDy = pos.y - rotationHandle.y
                                    if (rhDx * rhDx + rhDy * rhDy < handleHitRadius * handleHitRadius) {
                                        dragMode = DragMode.ROTATE
                                        editingRect = Rect(r)
                                        editingRotation = rotation
                                        // Clamped edge handles need an angle offset to avoid jumping on drag.
                                        rotationDragOffset = rotation - rotationFromCenter(pos, regionCenter(r, scale))
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
                                    editingRotation = normalizeRotationDegrees(
                                        rotationFromCenter(change.position, regionCenter(activeRect, s)) + rotationDragOffset)
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
                                                    selectedRegionId = viewModel.regions.value.lastOrNull()?.id
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
                                    selectedRegionId = region.id
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

                    region.searchRect?.takeIf {
                        isSelected && !isEntireScreenSearch(it, imageWidth, imageHeight, executionMode)
                    }?.let { searchRect ->
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

                    drawPath(color = Color(region.color), path = regionPath, alpha = if (isSelected) 0.18f else 0.06f)
                    if (isSelected) drawPath(color = Color.White, path = regionPath, style = Stroke(width = 7f))
                    drawPath(color = Color(region.color), path = regionPath, style = Stroke(width = if (isSelected) 4f else 2.5f))

                    // Sequence number badge (#1, #2, ...)
                    val seqText = "#${index + 1}"
                    val badgePaint = android.graphics.Paint().apply { color = 0xDD000000.toInt(); isAntiAlias = true }
                    val seqPaint = android.graphics.Paint().apply {
                        color = 0xFFFFFFFF.toInt(); textSize = 11.sp.toPx() / userScale
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
                        color = region.color; textSize = 9.sp.toPx() / userScale
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
                        val rotateHandle = rotationHandlePosition(r, scale, rotation, canvasSize)
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

        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00C853))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading capture...", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        }
        val selectedTarget = regions.find { it.id == selectedRegionId }
        val primary = listOf(
            EditorToolAction("Cancel", Icons.Default.Close, enabled = !isSaving, onClick = onCancel),
            EditorToolAction("Undo", Icons.AutoMirrored.Filled.Undo, enabled = regions.isNotEmpty() && !isSaving) {
                viewModel.undoLastRegion()
                if (viewModel.regions.value.none { it.id == selectedRegionId }) selectedRegionId = null
            },
            EditorToolAction("Recapture", Icons.Default.CameraAlt, enabled = !isSaving, onClick = onRecapture),
            EditorToolAction("Fit image", Icons.Default.FitScreen) { userScale = 1f; userOffset = Offset.Zero },
            EditorToolAction("Run behavior", Icons.Default.Settings) { showRunSettings = true },
            EditorToolAction("Save", Icons.Default.Check, enabled = regions.isNotEmpty() && !isSaving, tint = Color(0xFF47E78B)) {
                if (isFlowMode && flowNodeId != null) {
                    viewModel.saveForFlowMode(flowNodeId) { path -> onSaved(path) }
                } else viewModel.savePreset(presetName) { id -> onSaved(id) }
            }
        )
        val targetTools = if (searchAreaRegionId != null) listOf(
            EditorToolAction("Cancel search area", Icons.Default.Close) { searchAreaRegionId = null }
        ) else listOf(
            EditorToolAction("Target settings", Icons.Default.Tune, enabled = selectedTarget != null) { showRegionDialog = true },
            EditorToolAction("Edit search area", Icons.Default.CropFree, enabled = selectedTarget != null) {
                selectedTarget?.let { startSearchAreaSelection(it.id) }
            },
            EditorToolAction("Rotate target", Icons.AutoMirrored.Filled.RotateRight, enabled = selectedTarget != null) {
                selectedTarget?.let { viewModel.updateRegionRotation(it.id, normalizeRotationDegrees(it.rotationDegrees + 15f)) }
            }
        )
        VisionEditorToolbar(
            modifier = if (landscape) Modifier.align(Alignment.CenterEnd).width(104.dp).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(toolbarHeight),
            landscape = landscape, primary = primary, target = targetTools,
            targetLabel = selectedTarget?.let { "Target #${regions.indexOf(it) + 1}" } ?: "No target selected",
            searchLabel = when {
                searchAreaRegionId != null -> "Editing search area"
                selectedTarget == null -> ""
                isEntireScreenSearch(selectedTarget.searchRect, fullResWidth, fullResHeight, executionMode) -> "Entire screen"
                selectedTarget.searchRect != null -> "Custom area"
                else -> "Near target (saved)"
            },
            page = currentPageIndex, pages = capturePages.size,
            onPage = {
                viewModel.navigateToPage(it)
                selectedRegionId = null
                searchAreaRegionId = null
                showRegionDialog = false
            }
        )
    }

    if (showRunSettings) {
        VisionRunSettings(executionMode, tapDispatchMode, hasTapRegion,
            regions.any { it.matchMode == VisionMatchMode.MOVING },
            viewModel::updateExecutionMode, viewModel::updateTapDispatchMode,
            onDismiss = { showRunSettings = false })
    }
    if (showRegionDialog && dialogRegion != null) {
        VisionTargetSettings(
            region = dialogRegion, number = regions.indexOf(dialogRegion) + 1, mode = executionMode,
            entireScreen = isEntireScreenSearch(dialogRegion.searchRect, fullResWidth, fullResHeight, executionMode),
            onEntireScreen = { viewModel.updateRegionSearchRect(dialogRegion.id, Rect(0, 0, fullResWidth, fullResHeight)) },
            onCustomArea = { startSearchAreaSelection(dialogRegion.id) },
            model = viewModel, onDelete = { showDeleteConfirm = true },
            onDismiss = { showRegionDialog = false }
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
            title = { Text("Delete target?", color = Color.White) },
            text = { Text("This target will be removed.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRegion(dialogRegion.id)
                    showDeleteConfirm = false; showRegionDialog = false
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
